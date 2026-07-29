package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.ProfileCache
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONObject

/**
 * Read-only `/exec` lookups that give a notification the context its FCM payload lacks: recent room
 * history, the message a reply answers, sender display names, and a missing room name.
 *
 * Every entry point is best-effort and budget-gated ([ExecBudget]) — a failure yields "no
 * enrichment", never a lost notification. See docs/NOTIFICATIONS.md for which step runs when.
 *
 * ## Why this exists
 *
 * `EnhancedNotificationDisplay` builds its `MessagingStyle` from `roomMessageCache`, an in-memory
 * deque fed **only by notifications we previously posted**. That is empty whenever the process was
 * killed, the user swiped the shade clear, or this is the first message from a room — which is most
 * of the time. The user then sees a single message with no idea what preceded it. Now that gomuks'
 * `/exec` endpoint exposes the whole RPC command set, a cold cache can be seeded from the server
 * before the notification is posted, with no WebSocket and no ViewModel.
 *
 * ## What it deliberately does not do
 *
 * - **No avatar downloads.** Senders get a lettermark via the caller's existing fallback path; the
 *   real avatars land later through `upgradeAvatarsInCache`. Fetching N avatars inline would blow
 *   the [ExecBudget] and delay the notification for a cosmetic gain.
 * - **No decryption work of its own.** gomuks returns plaintext in `decrypted` for E2EE rooms;
 *   [TimelineEvent.getMessagePayload] already picks the right side. An event we cannot read is
 *   skipped rather than rendered as ciphertext.
 * - **No cache writes.** Backfilled events are not fed into `RoomTimelineCache`. This path runs
 *   without a ViewModel and must not race the real sync pipeline's view of the timeline; the
 *   messages it produces exist only to render one notification.
 */
object NotificationBackfill {
    private const val TAG = "NotifBackfill"

    /** Gomuks pagination direction token for "older than the tip". */
    private const val DIRECTION_BACKWARD = "b"

    /**
     * Over-fetch factor. The window contains state changes, reactions and redactions that we drop,
     * so asking for exactly [limit] renderable messages would usually come up short.
     */
    private const val OVERFETCH_FACTOR = 4

    /** Max distinct senders we will spend a member-state lookup on. See [resolveUnknownSenders]. */
    private const val MAX_MEMBER_LOOKUPS = 2

    /** One historical message, flattened to what `MessagingStyle` actually needs. */
    data class BackfilledMessage(
        val eventId: String,
        val senderId: String,
        val senderDisplayName: String,
        val text: String,
        val timestamp: Long,
        /**
         * False when [senderDisplayName] is the MXID localpart fallback rather than a real profile
         * name. Drives which senders are worth a member-state lookup.
         */
        val nameResolved: Boolean = true,
    )

    /**
     * Everything one `/exec` enrichment pass produced for a notification.
     *
     * @param messages Recent room history, oldest first, excluding the event being notified about.
     * @param replyParent The message the incoming event replies to, when it is *not* already in
     *   [messages]. Null when the incoming event is not a reply, when its parent is already in the
     *   history window, or when the lookup was skipped or failed.
     */
    data class RoomHistory(val messages: List<BackfilledMessage>, val replyParent: BackfilledMessage?) {
        fun isEmpty(): Boolean = messages.isEmpty() && replyParent == null

        companion object {
            val EMPTY = RoomHistory(emptyList(), null)
        }
    }

    /**
     * Builds the enrichment payload for one notification: up to [limit] recent messages (oldest
     * first) plus, when the incoming event is a reply to something outside that window, its parent.
     *
     * Returns [RoomHistory.EMPTY] on any failure — no credentials, budget exhausted, transport
     * error, unparseable response. Enrichment is best-effort by construction: the caller posts its
     * notification either way.
     *
     * Spends between one and four budget slots: one for the history page, at most one for the reply
     * parent, at most [MAX_MEMBER_LOOKUPS] for sender names. Ordered most-valuable-first so a tight
     * budget drops the least useful lookups.
     *
     * @param excludeEventId The event that triggered this notification. It is about to be appended
     *   by the caller, so it must not also appear in the backfilled history — and it is the event
     *   whose reply parent we resolve.
     */
    suspend fun fetchHistory(context: Context, roomId: String, limit: Int, excludeEventId: String?, budget: ExecBudget): RoomHistory {
        if (limit <= 0) return RoomHistory.EMPTY
        if (!budget.tryConsume("history-backfill")) return RoomHistory.EMPTY

        return withContext(Dispatchers.IO) {
            val creds = ExecApi.readCredentials(context)
            if (!creds.isValid()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "No /exec credentials; skipping backfill for $roomId")
                return@withContext RoomHistory.EMPTY
            }
            val body = JSONObject().apply {
                put("room_id", roomId)
                put("direction", DIRECTION_BACKWARD)
                put("limit", limit * OVERFETCH_FACTOR)
                put("since", "")
            }
            val response = ExecApi.callObject(creds, "paginate_manual", body)
            if (response == null) {
                Log.w(TAG, "paginate_manual failed for $roomId; notification will have no history")
                return@withContext RoomHistory.EMPTY
            }
            val messages = parseMessages(response, roomId, limit, excludeEventId)
            val parent = resolveReplyParent(creds, response, roomId, excludeEventId, messages, budget)
            // Resolve names across history and parent in one pass so both share the lookup budget
            // and a sender appearing in both costs a single request.
            val resolved = resolveUnknownSenders(creds, roomId, messages + listOfNotNull(parent), budget)
            if (parent == null) {
                RoomHistory(resolved, null)
            } else {
                RoomHistory(resolved.dropLast(1), resolved.last())
            }
        }
    }

    /**
     * Maps a `ManualPaginationResponse` onto renderable messages, oldest first.
     *
     * The server returns newest-first for a backward page, so we walk it in order, keep the first
     * [limit] events we can render, then reverse — that yields the *newest* [limit] messages in
     * chronological order, which is what the shade should show.
     */
    private fun parseMessages(response: JSONObject, roomId: String, limit: Int, excludeEventId: String?): List<BackfilledMessage> {
        val events = response.optJSONArray("events") ?: return emptyList()
        val collected = (0 until events.length())
            .asSequence()
            .mapNotNull { events.optJSONObject(it) }
            .mapNotNull { parseEventOrNull(it) }
            .filterNot { it.eventId.isNotBlank() && it.eventId == excludeEventId }
            .mapNotNull { toBackfilledMessage(it, roomId) }
            .take(limit)
            .toList()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Backfilled ${collected.size} message(s) for $roomId from ${events.length()} event(s)")
        }
        return collected.reversed()
    }

    /**
     * The message the incoming event replies to, or null when there is nothing useful to add.
     *
     * The reply relation is read out of the history page we already fetched — the incoming event is
     * normally the newest event in that window, so learning "is this a reply, and to what" costs
     * nothing. Only when the parent falls *outside* the window do we spend a `get_event` on it; a
     * parent already in the history is going to be rendered anyway.
     *
     * FCM's payload carries no relation data, which is why this has to come from the timeline.
     */
    private fun resolveReplyParent(
        creds: ExecApi.Credentials,
        response: JSONObject,
        roomId: String,
        incomingEventId: String?,
        history: List<BackfilledMessage>,
        budget: ExecBudget,
    ): BackfilledMessage? {
        if (incomingEventId.isNullOrBlank()) return null
        val parentId = findReplyParentId(response, incomingEventId) ?: return null
        if (history.any { it.eventId == parentId }) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Reply parent $parentId already in history window")
            return null
        }
        if (!budget.tryConsume("reply-parent")) return null

        val body = JSONObject().apply {
            put("room_id", roomId)
            put("event_id", parentId)
            put("unredact", false)
        }
        val eventJson = ExecApi.callObject(creds, "get_event", body) ?: return null
        return parseEventOrNull(eventJson)?.let { toBackfilledMessage(it, roomId) }
    }

    /** Digs the `m.in_reply_to` target out of the incoming event inside a pagination page. */
    private fun findReplyParentId(response: JSONObject, incomingEventId: String): String? {
        val events = response.optJSONArray("events") ?: return null
        val incoming = (0 until events.length())
            .asSequence()
            .mapNotNull { events.optJSONObject(it) }
            .firstOrNull { it.optString("event_id") == incomingEventId }
            ?: return null
        // getReplyInfo already excludes thread fallback relations, which are not real replies.
        return parseEventOrNull(incoming)?.getReplyInfo()?.eventId
    }

    /** [TimelineEvent.fromJson] with malformed events treated as absent rather than fatal. */
    private fun parseEventOrNull(json: JSONObject): TimelineEvent? = try {
        TimelineEvent.fromJson(json)
    } catch (e: org.json.JSONException) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Skipping unparseable event", e)
        null
    }

    /**
     * Replaces MXID-localpart fallbacks with real display names via `get_specific_room_state`.
     *
     * [ProfileCache] is in-memory, so on a cold FCM wake-up every backfilled sender renders as a
     * bare localpart. One member-state lookup per unresolved sender fixes that, capped at
     * [MAX_MEMBER_LOOKUPS] because the cost scales with distinct senders and the notification is
     * waiting. Senders beyond the cap keep their localpart, which is the pre-existing behaviour.
     *
     * Nothing is written back to [ProfileCache]: this runs outside the sync pipeline and must not
     * race `MemberProfilesCoordinator`'s view of room membership.
     */
    private fun resolveUnknownSenders(
        creds: ExecApi.Credentials,
        roomId: String,
        messages: List<BackfilledMessage>,
        budget: ExecBudget,
    ): List<BackfilledMessage> {
        val unresolved = messages.filterNot { it.nameResolved }.map { it.senderId }.distinct()
        if (unresolved.isEmpty()) return messages

        val names = mutableMapOf<String, String>()
        for (userId in unresolved.take(MAX_MEMBER_LOOKUPS)) {
            if (!budget.tryConsume("member-state")) break
            val body = JSONObject().apply {
                put("room_id", roomId)
                put("type", "m.room.member")
                put("state_key", userId)
            }
            val name = ExecApi.callObject(creds, "get_specific_room_state", body)
                ?.optJSONObject("content")
                ?.optString("displayname")
                ?.takeIf { it.isNotBlank() }
            if (name != null) names[userId] = name
        }
        if (names.isEmpty()) return messages
        return messages.map { message ->
            names[message.senderId]
                ?.let { message.copy(senderDisplayName = it, nameResolved = true) }
                ?: message
        }
    }

    /**
     * The room's display name via `get_room_summary`, for pushes that arrive without one.
     *
     * Worth a call because the room name is not cosmetic here: it becomes the conversation title,
     * the notification channel name and the shortcut label, and "!abc:server.tld" in the shade is
     * conspicuously broken. Returns null when the push already had a name (no call is made), when
     * the budget is out, or on any failure.
     */
    suspend fun fetchRoomName(context: Context, roomId: String, budget: ExecBudget): String? {
        if (!budget.tryConsume("room-summary")) return null
        return withContext(Dispatchers.IO) {
            val creds = ExecApi.readCredentials(context)
            if (!creds.isValid()) return@withContext null
            val body = JSONObject().apply { put("room_id", roomId) }
            val summary = ExecApi.callObject(creds, "get_room_summary", body)
            summary?.optString("name")?.takeIf { it.isNotBlank() }
                ?: summary?.optString("canonical_alias")?.takeIf { it.isNotBlank() }
        }
    }

    /** Converts one event to a renderable message, or null when it is not a displayable message. */
    private fun toBackfilledMessage(event: TimelineEvent, roomId: String): BackfilledMessage? {
        // A redaction that arrived after the original must not resurrect the original's text.
        if (event.redactedBy != null) return null
        if (event.type != "m.room.message" && event.type != "m.room.encrypted") return null
        val payload = event.getMessagePayload() ?: return null
        // An edit replaces an earlier message that is probably also in this window; rendering both
        // would double up. The unedited original still shows, which is the lesser wrong.
        if (payload.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") return null
        val text = messageText(payload) ?: return null
        val sender = event.sender.takeIf { it.isNotBlank() } ?: return null
        val cachedName = cachedDisplayName(roomId, sender)
        return BackfilledMessage(
            eventId = event.eventId,
            senderId = sender,
            senderDisplayName = cachedName ?: localpartOf(sender),
            text = text,
            timestamp = event.timestamp,
            nameResolved = cachedName != null,
        )
    }

    /**
     * Display text for a message payload, mirroring the room list's preview conventions so the
     * shade and the room list label the same message the same way.
     */
    private fun messageText(payload: JSONObject): String? {
        galleryPreviewLabel(payload)?.let { return it }
        val body = payload.optString("body").takeIf { it.isNotBlank() }
        val msgtype = payload.optString("msgtype")
        mediaLabel(msgtype, body)?.let { return it }
        return body
    }

    /** Labels for non-text messages, aligned with the room list preview style. */
    private fun mediaLabel(msgtype: String, body: String?): String? = when (msgtype) {
        "m.image" -> "📷 Image"
        "m.video" -> "🎥 Video"
        "m.audio" -> "🎵 Audio"
        "m.file" -> "📎 File"
        "m.location" -> "📍 Location"
        "m.sticker" -> body?.takeIf { it.isNotBlank() }?.let { "🎨 $it" } ?: "🎨 Sticker"
        else -> null
    }

    /**
     * Sender name from [ProfileCache], or null when it holds nothing for this user. The cache is
     * in-memory, so it is populated when the app process is warm and empty on a cold FCM wake-up —
     * a null here is what makes [resolveUnknownSenders] spend a member-state lookup.
     */
    private fun cachedDisplayName(roomId: String, userId: String): String? =
        ProfileCache.getFlattenedProfile(roomId, userId)?.displayName?.takeIf { it.isNotBlank() }
            ?: ProfileCache.getGlobalProfileProfile(userId)?.displayName?.takeIf { it.isNotBlank() }

    /** `@alice:server.tld` → `alice`. The last-resort name when no profile is available anywhere. */
    private fun localpartOf(userId: String): String = userId.removePrefix("@").substringBefore(':').ifBlank { userId }
}
