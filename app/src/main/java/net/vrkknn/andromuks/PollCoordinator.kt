package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.POLL_RESPONSE_STABLE
import net.vrkknn.andromuks.utils.POLL_RESPONSE_UNSTABLE
import net.vrkknn.andromuks.utils.PollResults
import net.vrkknn.andromuks.utils.PollStartInfo
import net.vrkknn.andromuks.utils.PollVote
import net.vrkknn.andromuks.utils.computePollResults
import net.vrkknn.andromuks.utils.isPollStartType
import net.vrkknn.andromuks.utils.parsePollEnd
import net.vrkknn.andromuks.utils.parsePollResponse
import net.vrkknn.andromuks.utils.parsePollStart
import net.vrkknn.andromuks.utils.pollEventType
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
