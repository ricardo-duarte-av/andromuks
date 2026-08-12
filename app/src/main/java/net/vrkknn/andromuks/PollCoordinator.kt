package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.MSC4391_COMMAND_KEY
import net.vrkknn.andromuks.utils.POLL_END_STABLE
import net.vrkknn.andromuks.utils.POLL_END_UNSTABLE
import net.vrkknn.andromuks.utils.POLL_RESPONSE_STABLE
import net.vrkknn.andromuks.utils.POLL_RESPONSE_UNSTABLE
import net.vrkknn.andromuks.utils.PollEndInfo
import net.vrkknn.andromuks.utils.PollResults
import net.vrkknn.andromuks.utils.PollStartInfo
import net.vrkknn.andromuks.utils.PollVote
import net.vrkknn.andromuks.utils.computePollResults
import net.vrkknn.andromuks.utils.isAuthorizedPollEnd
import net.vrkknn.andromuks.utils.isPollStartType
import net.vrkknn.andromuks.utils.parsePollEnd
import net.vrkknn.andromuks.utils.parsePollResponse
import net.vrkknn.andromuks.utils.parsePollStart
import net.vrkknn.andromuks.utils.pollEventType
import net.vrkknn.andromuks.utils.quoteCommandArg
import org.json.JSONArray
import org.json.JSONObject

/**
 * Poll (MSC3381) orchestration for [AppViewModel]: raw event stores, aggregate recomputation,
 * WebSocket voting, and response handling. Wire parsing and the pure aggregator live in
 * [net.vrkknn.andromuks.utils.PollFunctions]; the UI lives in
 * [net.vrkknn.andromuks.utils.PollMessageContent].
 *
 * Design note: unlike reactions, this coordinator never applies incremental deltas. Every change
 * triggers a full [recomputePoll] from the raw stores. Polls have at most one effective vote per
 * user (latest wins) so recomputation is cheap, and it makes redactions, out-of-order delivery and
 * superseded votes correct by construction rather than by careful bookkeeping. See docs/POLLS.md.
 */
internal class PollCoordinator(private val vm: AppViewModel) {

    companion object {
        /**
         * gomuks' internal local-bot address. Deliberately not a full MXID (no domain part) — this
         * is the literal value webmuks sends, and gomuks matches on it.
         */
        const val GOMUKS_BOT_USER_ID = "@gomuks"

        /** A one-option "poll" offers no choice, so the composer requires at least two. */
        const val MIN_POLL_OPTIONS = 2
    }

    /**
     * Human-readable `/poll` fallback for the command envelope's `body`.
     *
     * MSC4391 treats the body as non-authoritative (the JSON arguments win, and the body may be
     * omitted entirely), but we generate a faithful one anyway: every option is quoted, so unlike
     * webmuks' own example — where unquoted `Fourth Option` reads back as two separate options — this
     * round-trips through a shell-style parser without changing meaning.
     *
     * The quoting now comes from the shared [quoteCommandArg], which is a port of mautrix-go's
     * `cmdschema.quoteString` and therefore also quotes the `<`/`>` array delimiters. Polls have no
     * array arguments, so this only ever quotes *more* than before — never less.
     */
    private fun pollCommandFallbackBody(question: String, options: List<String>, maxSelections: Int): String = buildString {
        append("/poll")
        append(GOMUKS_BOT_USER_ID)
        append(' ')
        append(quoteCommandArg(question))
        append(' ')
        append(maxSelections)
        options.forEach {
            append(' ')
            append(quoteCommandArg(it))
        }
    }

    /**
     * The `relates_to` for a poll created inside a thread, matching the shape gomuks/webmuks send.
     *
     * `is_falling_back = true` sits *alongside* `m.in_reply_to` on purpose: per the threads spec that
     * pairing means "this in_reply_to is a fallback for non-threaded clients, not a real reply",
     * which is exactly the case for a poll posted into a thread. This path was already correct when
     * the media path was not (GH #28); both now share [net.vrkknn.andromuks.utils.threadRelatesTo].
     */
    private fun pollThreadRelatesTo(roomId: String, threadRootEventId: String, replyToEventId: String?): Map<String, Any> =
        // Always a fallback: creating a poll in a thread is never itself a reply to a message.
        net.vrkknn.andromuks.utils.threadRelatesTo(
            threadRootEventId = threadRootEventId,
            replyToEventId = replyToEventId
                ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId,
            isFallback = true,
        )

    /**
     * Routes any poll event into the raw stores. Returns true if it changed anything.
     *
     * Does not recompute — callers batch a set of events and then call [recomputePoll] (or
     * [recomputePolls]) once, so a sync batch touching one poll rebuilds it a single time.
     */
    fun ingestPollEvent(event: TimelineEvent): String? {
        val type = pollEventType(event) ?: return null

        if (isPollStartType(type)) {
            val start = parsePollStart(event) ?: return null
            vm.pollStartInfos[start.eventId] = start
            return start.eventId
        }

        parsePollResponse(event)?.let { (pollStartEventId, vote) ->
            synchronized(vm.pollLock) {
                vm.pollVoteEvents.getOrPut(pollStartEventId) { mutableMapOf() }[vote.eventId] = vote
            }
            // A confirmed vote of our own retires the optimistic one we rendered on tap.
            if (vote.voter == vm.currentUserId) {
                vm.pollLocalVotes.remove(pollStartEventId)
            }
            return pollStartEventId
        }

        parsePollEnd(event)?.let { (pollStartEventId, end) ->
            synchronized(vm.pollLock) {
                vm.pollEndEvents.getOrPut(pollStartEventId) { mutableMapOf() }[end.eventId] = end
            }
            return pollStartEventId
        }

        return null
    }

    /**
     * Drops a redacted poll response/end from the raw stores.
     *
     * Poll satellite events are never in `timelineEvents`, so the redaction handler looks them up in
     * [RoomTimelineCache] and routes them here — the same shape as the reaction redaction path.
     * Returns the affected poll start event id, or null if the event was not a poll satellite.
     */
    fun removeRedactedPollEvent(event: TimelineEvent): String? {
        val type = pollEventType(event) ?: return null
        if (isPollStartType(type)) return null

        val pollStartEventId = net.vrkknn.andromuks.utils.pollRelatesToEventId(event) ?: return null
        var changed = false
        synchronized(vm.pollLock) {
            if (vm.pollVoteEvents[pollStartEventId]?.remove(event.eventId) != null) changed = true
            if (vm.pollEndEvents[pollStartEventId]?.remove(event.eventId) != null) changed = true
        }
        if (!changed) return null

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "PollCoordinator: removed redacted poll event ${event.eventId} from poll $pollStartEventId",
            )
        }
        return pollStartEventId
    }

    /**
     * Rebuilds one poll's aggregate from the raw stores and publishes it.
     *
     * No-ops when the poll start has not arrived yet — responses stay buffered in [vm.pollVoteEvents]
     * and are picked up the moment the start lands, so out-of-order delivery needs no special case.
     */
    fun recomputePoll(pollStartEventId: String) {
        recomputePolls(setOf(pollStartEventId))
    }

    /** Batch form of [recomputePoll] — one Compose state write for the whole set. */
    fun recomputePolls(pollStartEventIds: Set<String>) {
        if (pollStartEventIds.isEmpty()) return

        val powerLevels = vm.currentRoomState?.powerLevels
        val myUserId = vm.currentUserId.takeIf { it.isNotBlank() }
        val computed = mutableMapOf<String, PollResults>()

        for (pollStartEventId in pollStartEventIds) {
            val start = vm.pollStartInfos[pollStartEventId] ?: continue
            val votes: List<PollVote>
            val ends: List<net.vrkknn.andromuks.utils.PollEndInfo>
            synchronized(vm.pollLock) {
                votes = vm.pollVoteEvents[pollStartEventId]?.values?.toList() ?: emptyList()
                ends = vm.pollEndEvents[pollStartEventId]?.values?.toList() ?: emptyList()
            }

            computed[pollStartEventId] = computePollResults(
                start = start,
                votes = votes,
                ends = ends,
                myUserId = myUserId,
                powerLevels = powerLevels,
                localVote = vm.pollLocalVotes[pollStartEventId],
            )
        }

        if (computed.isEmpty()) return

        PollCache.putAll(computed)
        // mutableStateOf writes must happen on Main to avoid Compose snapshot conflicts
        vm.viewModelScope.launch(Dispatchers.Main) {
            vm.pollUpdateCounter++
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "PollCoordinator: recomputed ${computed.size} poll(s): " +
                    computed.entries.joinToString { "${it.key}=${it.value.totalVoters} voters" },
            )
        }
    }

    /**
     * Ingests a batch of events and recomputes every poll they touched. Safe to hand a mixed list —
     * non-poll events are ignored. Returns the number of poll events consumed.
     */
    fun processPollEvents(events: List<TimelineEvent>): Int {
        if (events.isEmpty()) return 0
        val touched = mutableSetOf<String>()
        var consumed = 0
        for (event in events) {
            val pollStartEventId = ingestPollEvent(event) ?: continue
            touched.add(pollStartEventId)
            consumed++
        }
        recomputePolls(touched)
        return consumed
    }

    /**
     * Rebuilds every poll in a room from the caches — the full-recompute entry point used when a
     * room is opened.
     *
     * Poll starts come from the room's timeline events; responses and ends come from the dedicated
     * [RoomTimelineCache] poll bucket (they are never timeline rows). Mirrors
     * [ReactionCoordinator.loadReactionsForRoom].
     */
    fun loadPollsForRoom(roomId: String, cachedEvents: List<TimelineEvent>, forceReload: Boolean = false) {
        if (!forceReload && !vm.roomsWithLoadedPolls.add(roomId)) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "PollCoordinator: polls for room $roomId already loaded, skipping")
            }
            return
        }
        if (forceReload) vm.roomsWithLoadedPolls.add(roomId)

        val pollEvents = RoomTimelineCache.getCachedPollEvents(roomId)
        val startEvents = cachedEvents.filter { isPollStartType(pollEventType(it)) }
        if (startEvents.isEmpty() && pollEvents.isEmpty()) return

        val touched = mutableSetOf<String>()
        for (event in startEvents) {
            ingestPollEvent(event)?.let { touched.add(it) }
        }
        for (event in pollEvents) {
            ingestPollEvent(event)?.let { touched.add(it) }
        }
        recomputePolls(touched)

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "PollCoordinator: loadPollsForRoom($roomId) - ${startEvents.size} poll starts, " +
                    "${pollEvents.size} cached response/end events, ${touched.size} polls rebuilt",
            )
        }
    }

    /** Forgets a room's poll state so the next open rebuilds it from cache. */
    fun clearRoomPollState(roomId: String) {
        vm.roomsWithLoadedPolls.remove(roomId)
    }

    /**
     * Fetches every response/end for a poll via `get_related_events`.
     *
     * Needed when the poll start is older than our cached window: the votes may never have been
     * delivered to this client. Mirrors [ReactionCoordinator.requestReactionDetails].
     */
    fun requestPollDetails(roomId: String, pollStartEventId: String) {
        if (!vm.isWebSocketConnected()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "PollCoordinator: skipping requestPollDetails - WebSocket not connected (poll=$pollStartEventId)",
                )
            }
            return
        }

        val requestId = vm.getAndIncrementRequestId()
        vm.pollRequests[requestId] = pollStartEventId

        val result = vm.sendWebSocketCommand(
            "get_related_events",
            requestId,
            mapOf(
                "room_id" to roomId,
                "event_id" to pollStartEventId,
                "relation_type" to "m.reference",
            ),
        )
        if (result != WebSocketResult.SUCCESS) {
            vm.pollRequests.remove(requestId)
            android.util.Log.w(
                "Andromuks",
                "PollCoordinator: get_related_events failed to send for poll $pollStartEventId (result=$result)",
            )
        }
    }

    /** Handles the `get_related_events` response for [requestPollDetails]. */
    fun handlePollRelatedEventsResponse(requestId: Int, data: Any) {
        val pollStartEventId = vm.pollRequests.remove(requestId) ?: return

        val eventsArray: JSONArray? = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("events") ?: JSONArray().apply { put(data) }
            else -> null
        }
        if (eventsArray == null || eventsArray.length() == 0) return

        val events = mutableListOf<TimelineEvent>()
        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i) ?: continue
            try {
                events.add(TimelineEvent.fromJson(eventJson))
            } catch (e: Exception) {
                android.util.Log.e(
                    "Andromuks",
                    "PollCoordinator: error parsing related event $i for poll $pollStartEventId: ${e.message}",
                    e,
                )
            }
        }

        val consumed = processPollEvents(events)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "PollCoordinator: related_events for poll $pollStartEventId - consumed $consumed of ${events.size}",
            )
        }
    }

    /**
     * Creates a poll by asking the gomuks bot to build it, via an MSC4391 command envelope.
     *
     * We deliberately do NOT construct the `poll.start` event ourselves. gomuks exposes a `poll`
     * command (`pkg/hicli/cmdspec/commands.go`) and builds the event server-side, which is what
     * webmuks does; following it keeps us interoperable and means this rides the ordinary
     * `send_message` path — including its local echo — instead of the echo-less `send_event`.
     *
     * Two consequences of gomuks' implementation are baked in here:
     *
     *  - **`max_selections` is always sent explicitly, and always in range.** `handleCmdPoll` clamps
     *    with `if maxSelections <= 0 || maxSelections > len(options) { maxSelections = len(options) }`,
     *    and since Go unmarshals a missing field to 0, *omitting* it yields a poll where every option
     *    is selectable — not the `DefaultValue: 1` the command schema advertises.
     *  - **No `kind` argument exists.** gomuks hardcodes `org.matrix.msc3381.disclosed`, so undisclosed
     *    polls cannot be created through this route at all.
     */
    fun sendPollCreate(
        roomId: String,
        question: String,
        options: List<String>,
        maxSelections: Int,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
    ) {
        val cleanQuestion = question.trim()
        val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanQuestion.isEmpty() || cleanOptions.size < MIN_POLL_OPTIONS) {
            android.util.Log.w(
                "Andromuks",
                "PollCoordinator: refusing to create poll - question blank or fewer than $MIN_POLL_OPTIONS options",
            )
            return
        }
        val effectiveMaxSelections = maxSelections.coerceIn(1, cleanOptions.size)

        val commandArguments = mapOf(
            "question" to cleanQuestion,
            "max_selections" to effectiveMaxSelections,
            "options" to cleanOptions,
        )
        val baseContent = mapOf(
            "msgtype" to "m.text",
            "body" to pollCommandFallbackBody(cleanQuestion, cleanOptions, effectiveMaxSelections),
            MSC4391_COMMAND_KEY to mapOf(
                "command" to "poll",
                "arguments" to commandArguments,
            ),
        )

        val requestId = vm.getAndIncrementRequestId()
        vm.trackOutgoingRequest(requestId, roomId)
        vm.messageRequests[requestId] = roomId

        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to "",
            // MSC4391: mention the bot so the command cannot be picked up by the wrong one.
            // "@gomuks" has no domain part — that is gomuks' internal local-bot address, not a
            // malformed MXID. Sent verbatim, matching webmuks.
            "mentions" to mapOf("user_ids" to listOf(GOMUKS_BOT_USER_ID), "room" to false),
            "url_previews" to emptyList<Any>(),
        )
        // A poll started from a thread carries the thread relation, so gomuks roots the resulting
        // poll.start in that thread rather than the main timeline.
        if (threadRootEventId != null) {
            commandData["relates_to"] = pollThreadRelatesTo(roomId, threadRootEventId, replyToEventId)
        }

        val result = vm.sendWebSocketCommand("send_message", requestId, commandData)

        if (result == WebSocketResult.SUCCESS) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "PollCoordinator: sent poll command for $roomId - ${cleanOptions.size} options, " +
                        "max_selections=$effectiveMaxSelections, thread=$threadRootEventId",
                )
            }
        } else {
            vm.messageRequests.remove(requestId)
            android.util.Log.w("Andromuks", "PollCoordinator: failed to send poll command (result=$result)")
        }
    }

    /**
     * Closes a poll, freezing its results.
     *
     * Built and sent directly rather than through a command envelope, because gomuks has no
     * poll-end command — mautrix-go does not even define a poll-end content type (`event/content.go`
     * registers only `EventUnstablePollStart` and `EventUnstablePollResponse`). Other clients
     * (Element) do honour `poll.end`, and so do we, so sending it ourselves is interoperable.
     *
     * The end is only honoured by receivers if the sender is the poll's creator or has the room's
     * redact power level — see [net.vrkknn.andromuks.utils.isAuthorizedPollEnd] — so callers should
     * only offer this where [canEndPoll] is true.
     */
    fun sendPollEnd(roomId: String, pollStartEventId: String) {
        val start = vm.pollStartInfos[pollStartEventId]
        if (start == null) {
            android.util.Log.w("Andromuks", "PollCoordinator: cannot end unknown poll $pollStartEventId")
            return
        }
        if (vm.pollResults[pollStartEventId]?.isEnded == true) return
        if (WebSocketService.getWebSocket() == null) return

        // Echo the poll's own prefix flavour, as the response path does.
        val endType = if (start.isStablePrefix) POLL_END_STABLE else POLL_END_UNSTABLE

        val requestId = vm.getAndIncrementRequestId()
        val result = vm.sendWebSocketCommand(
            "send_event",
            requestId,
            mapOf(
                "room_id" to roomId,
                "type" to endType,
                "content" to mapOf(
                    "m.relates_to" to mapOf(
                        "rel_type" to "m.reference",
                        "event_id" to pollStartEventId,
                    ),
                    endType to emptyMap<String, Any>(),
                ),
                "disable_encryption" to false,
                "synchronous" to false,
            ),
        )

        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w(
                "Andromuks",
                "PollCoordinator: failed to end poll $pollStartEventId (result=$result)",
            )
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "PollCoordinator: sent $endType for poll $pollStartEventId")
        }
    }

    /**
     * Whether the local user may close [pollStartEventId] — i.e. whether an end event from us would
     * actually be honoured. Mirrors the receive-side authorisation check so the UI never offers an
     * action that every client (including ours) would then ignore.
     */
    fun canEndPoll(pollStartEventId: String): Boolean {
        val results = vm.pollResults[pollStartEventId] ?: return false
        if (results.isEnded) return false
        val myUserId = vm.currentUserId.takeIf { it.isNotBlank() } ?: return false
        return isAuthorizedPollEnd(
            PollEndInfo(eventId = "", sender = myUserId, timestamp = 0L),
            results.start,
            vm.currentRoomState?.powerLevels,
        )
    }

    /**
     * Casts (or withdraws, with an empty [answerIds]) the local user's vote.
     *
     * Sends the *full* new selection, since a response replaces the sender's previous one entirely.
     * The optimistic vote is rendered immediately and retired when the server echoes it back through
     * [ingestPollEvent]. It is stored apart from the real votes so it can never beat the server's
     * copy under the latest-wins rule (our clock may run ahead of the homeserver's).
     */
    fun sendPollResponse(roomId: String, pollStartEventId: String, answerIds: List<String>) {
        val start = vm.pollStartInfos[pollStartEventId]
        if (start == null) {
            android.util.Log.w("Andromuks", "PollCoordinator: cannot vote on unknown poll $pollStartEventId")
            return
        }
        if (vm.pollResults[pollStartEventId]?.isEnded == true) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "PollCoordinator: ignoring vote on ended poll $pollStartEventId")
            }
            return
        }
        if (WebSocketService.getWebSocket() == null) return

        val myUserId = vm.currentUserId.takeIf { it.isNotBlank() } ?: return
        val selection = answerIds.take(start.maxSelections)

        // Echo the poll's own prefix flavour so stable-prefix polls get stable-prefix responses.
        val responseType = if (start.isStablePrefix) POLL_RESPONSE_STABLE else POLL_RESPONSE_UNSTABLE

        val previousLocalVote = vm.pollLocalVotes[pollStartEventId]
        vm.pollLocalVotes[pollStartEventId] = PollVote(
            eventId = "~local-$pollStartEventId",
            voter = myUserId,
            answerIds = selection,
            timestamp = System.currentTimeMillis(),
        )
        recomputePoll(pollStartEventId)

        val requestId = vm.getAndIncrementRequestId()
        val result = vm.sendWebSocketCommand(
            "send_event",
            requestId,
            mapOf(
                "room_id" to roomId,
                "type" to responseType,
                "content" to mapOf(
                    "m.relates_to" to mapOf(
                        "rel_type" to "m.reference",
                        "event_id" to pollStartEventId,
                    ),
                    responseType to mapOf("answers" to selection),
                ),
                "disable_encryption" to false,
                "synchronous" to false,
            ),
        )

        if (result != WebSocketResult.SUCCESS) {
            // Roll the optimistic vote back so the UI doesn't claim a vote that never left the device.
            if (previousLocalVote != null) {
                vm.pollLocalVotes[pollStartEventId] = previousLocalVote
            } else {
                vm.pollLocalVotes.remove(pollStartEventId)
            }
            recomputePoll(pollStartEventId)
            android.util.Log.w(
                "Andromuks",
                "PollCoordinator: failed to send vote on poll $pollStartEventId (result=$result)",
            )
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "PollCoordinator: sent $responseType with ${selection.size} answer(s) for poll $pollStartEventId",
            )
        }
    }

    /** Convenience for the UI: toggle one answer, respecting `max_selections`. */
    fun toggleAnswer(roomId: String, pollStartEventId: String, answerId: String) {
        val start: PollStartInfo = vm.pollStartInfos[pollStartEventId] ?: return
        val current = vm.pollResults[pollStartEventId]?.myAnswerIds ?: emptySet()

        val next = when {
            answerId in current -> current - answerId

            // At the cap, adding is rejected rather than silently evicting an earlier pick —
            // a surprise deselect is worse than an ignored tap.
            current.size >= start.maxSelections && start.maxSelections > 1 -> return

            // Single-selection polls behave like radio buttons.
            start.maxSelections == 1 -> setOf(answerId)

            else -> current + answerId
        }

        sendPollResponse(roomId, pollStartEventId, next.toList())
    }
}
