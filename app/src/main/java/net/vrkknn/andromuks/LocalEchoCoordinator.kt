package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Optimistic send-time placeholders ("local echoes") and their lifecycle state machine.
 *
 * When the user sends anything (message / reply / thread reply / media / sticker / location), we
 * insert a placeholder bubble IMMEDIATELY — before any server frame — so the UI reacts instantly.
 * The bubble then walks a small state machine, encoded in the placeholder's `local_content`:
 *
 * ```
 *   Sending  (local_send_state=sending) → tertiary, elevated   [Timer A armed]
 *   Sent     (local_send_state=sent)    → tertiary, flat        [response arrived; Timer A→B]
 *   Confirmed                           → real bubble           [send_complete ok / sync_complete evicts]
 *   Failed   (send_error set)           → error color           [no response | send_complete error | backstop]
 * ```
 *
 * **Identity.** The placeholder keeps a stable client-local id (`~local-<uuid>`) as its
 * `eventChainMap` key / LazyColumn key for its entire life, so the Sending→Sent transition never
 * recreates the item (animation continuity). The leading `~` makes the existing `isPendingEcho`
 * rendering apply. On the `response` we learn the backend `transaction_id` and register
 * `pendingEchoMap[txId] = localId`, so the EXISTING reconciliation paths
 * ([EditVersionCoordinator.addNewEventToChain] for `sync_complete`, and
 * [AppViewModel.processSendCompleteEvent] for `send_complete`) evict/fail the echo by
 * `transaction_id` exactly as they did the old response-time echo.
 *
 * **Why we never auto-retry.** `send_message` is non-idempotent (the backend mints a fresh
 * transaction_id per call; there is no client-supplied idempotency key — see
 * [PersistenceCoordinator] Layer 1 and docs/MESSAGE_SENDING.md). A stranded placeholder is surfaced
 * as Failed for the user, never silently resent.
 *
 * **Failure triggers (two of them):**
 *  1. No `response` within [RESPONSE_TIMEOUT_MS] — the backend never received the send.
 *  2. `send_complete` with a Matrix-server error (e.g. event too large) — handled by
 *     [AppViewModel.processSendCompleteEvent]; note no `sync_complete` ever follows in this case.
 *  A long [CONFIRM_BACKSTOP_MS] watchdog covers the rare dropped-`send_complete`-error frame.
 */
internal class LocalEchoCoordinator(private val vm: AppViewModel) {

    companion object {
        const val LOCAL_ECHO_PREFIX = "~local-"

        /**
         * `local_content` flag: this placeholder is hidden because a confirmed event that probably
         * supersedes it arrived before we could link them. Render-time only — the entry stays in
         * `eventChainMap` so `onResponse` can still evict it by `transaction_id`. Cleared if the
         * placeholder ends up Failed, so a send that genuinely did not go out is never swallowed.
         */
        const val LOCAL_SUPERSEDED = "local_superseded"

        // The `response` (synchronous RPC ack) is fast even in E2EE rooms — encryption latency lives
        // in send_complete, not response — so this can be short.
        private const val RESPONSE_TIMEOUT_MS = 20_000L

        // response arrived but never confirmed (a dropped send_complete *error* frame). Generous so
        // E2EE's slower send_complete never trips it falsely; Layer 2 makes it almost never fire.
        private const val CONFIRM_BACKSTOP_MS = 90_000L
    }

    // requestId → localId. The `response` echoes our request_id, letting us find the placeholder.
    // ConcurrentHashMap: insert() runs on Main, onResponse() on Dispatchers.Default.
    private val requestToLocalId = java.util.concurrent.ConcurrentHashMap<Int, String>()

    // localId → lifecycle watchdog job (Timer A while Sending, Timer B while Sent).
    private val watchdogJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Insert a Sending placeholder for an outgoing event and arm its response watchdog. [content]
     * is the message content JSON the bubble renders (msgtype/body/url/info/m.relates_to/...).
     *
     * Returns the client-local id, or null if we didn't insert one (e.g. the send targets a room
     * other than the one currently open, where there is no timeline to render into).
     */
    fun insert(roomId: String, requestId: Int, type: String, content: JSONObject, relationType: String? = null, relatesTo: String? = null): String? {
        if (roomId != vm.currentRoomId) return null
        val localId = LOCAL_ECHO_PREFIX + UUID.randomUUID().toString()
        val echo = TimelineEvent(
            rowid = 0L,
            timelineRowid = 0L,
            roomId = roomId,
            eventId = localId,
            sender = vm.currentUserId,
            type = type,
            timestamp = System.currentTimeMillis(),
            content = content,
            localContent = JSONObject().put("local_send_state", "sending"),
            relationType = relationType,
            relatesTo = relatesTo,
            transactionId = null,
        )
        vm.editVersionCoordinator.addNewEventToChain(echo)
        requestToLocalId[requestId] = localId
        vm.buildTimelineFromChain(expectedRoomId = roomId)

        watchdogJobs.remove(localId)?.cancel()
        watchdogJobs[localId] = vm.viewModelScope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            if (isStillSending(localId)) {
                markFailed(localId, "No response from server", roomId)
            }
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Local echo inserted $localId (reqId=$requestId, type=$type)",
            )
        }
        return localId
    }

    /**
     * The `response` arrived for [requestId]: upgrade Sending → Sent and register [transactionId]
     * so downstream eviction/failure paths reconcile this echo. Returns true if a placeholder was
     * found and upgraded (caller then skips the legacy response-time echo insertion).
     */
    fun onResponse(requestId: Int, transactionId: String?): Boolean {
        val localId = requestToLocalId.remove(requestId) ?: return false
        val entry = vm.eventChainMap[localId] ?: return false
        val bubble = entry.ourBubble ?: return false
        // Already failed (e.g. response arrived after Timer A fired) — leave it Failed.
        if (bubble.localContent?.optString("send_error")?.isNotBlank() == true) {
            watchdogJobs.remove(localId)?.cancel()
            return true
        }
        // Race guard: `response` can be processed AFTER `send_complete`/`sync_complete`, because
        // acks ride SyncRepository.ackEvents while sync_complete rides syncCompleteChannel — two
        // independent collectors with no mutual ordering. `pendingEchoMap` has exactly one producer
        // (this method) and two consumers (processSendCompleteEvent, addNewEventToChain); if both
        // consumers run before us they find no entry and the confirmed ($-prefixed) event lands in
        // the chain WITHOUT evicting the echo. Were we to then upgrade-and-register as usual, the
        // echo would strand forever (nothing left to consume the map entry) until the room is
        // reopened. So: if the confirmed event carrying our transaction_id is already in the chain,
        // evict the echo here instead of upgrading it. A foreign transaction_id (an event sent from
        // another of the user's own sessions) is generated by that client and never equals our
        // send's txId, so this can only ever match our own confirmed event.
        if (transactionId != null) {
            val confirmed = vm.eventChainMap.values.firstOrNull {
                !it.eventId.startsWith("~") && it.ourBubble?.transactionId == transactionId
            }
            if (confirmed != null) {
                vm.eventChainMap.remove(localId)
                watchdogJobs.remove(localId)?.cancel()
                // The pending echo already played its slide-in; pre-mark the confirmed event so it
                // settles in place rather than re-animating an entrance.
                vm.markTimelineEntrancePlayed(confirmed.eventId)
                vm.buildTimelineFromChain(expectedRoomId = bubble.roomId)
                Androlog(
                    "LocalEcho",
                    "echo $localId evicted on response — confirmed ${confirmed.eventId} " +
                        "already present for txId=$transactionId",
                )
                return true
            }
        }
        val newLocalContent = (bubble.localContent?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("local_send_state", "sent")
        vm.eventChainMap[localId] = entry.copy(
            ourBubble = bubble.copy(localContent = newLocalContent, transactionId = transactionId),
        )
        if (transactionId != null) vm.pendingEchoMap[transactionId] = localId
        vm.buildTimelineFromChain(expectedRoomId = bubble.roomId)

        // Re-arm as a long backstop: a dropped send_complete *error* would otherwise strand us in Sent.
        watchdogJobs.remove(localId)?.cancel()
        watchdogJobs[localId] = vm.viewModelScope.launch {
            delay(CONFIRM_BACKSTOP_MS)
            if (vm.eventChainMap.containsKey(localId) && !isFailed(localId)) {
                markFailed(localId, "Send not confirmed", bubble.roomId)
            }
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Local echo $localId → Sent (txId=$transactionId)",
            )
        }
        return true
    }

    /**
     * A confirmed event from our own user arrived carrying a `transaction_id` we cannot resolve,
     * because its `response` has not been processed yet. Hide the oldest still-unresolved
     * placeholder so the user does not see the same message twice.
     *
     * **Why a guess is safe here.** The four frames of a send form a *chain*, not a mesh:
     *
     * ```
     *   request_id ──[response]── transaction_id ──[send_complete]── event_id
     *        │                                   └─[sync_complete]─┘
     *        └─ all the echo knows at send time
     * ```
     *
     * `response` is a cut vertex — it is the only frame carrying both `request_id` and
     * `transaction_id`, so until it lands there is provably *nothing* linking a confirmed event
     * back to its placeholder. (`send_complete` cannot lose this race: it shares the ordered
     * `ackEvents` flow with `response`. Only `sync_complete`, which rides its own channel, can.)
     *
     * But we do not need the link to avoid rendering two bubbles. [requestToLocalId] being
     * non-empty means a link is *pending*, not absent. So we hide optimistically and let the real
     * `transaction_id` reconciliation in [onResponse] delete the correct placeholder a moment
     * later. The hide is a render-time guess; the eviction stays authoritative.
     *
     * Oldest-first because request ids are allocated monotonically, so the lowest outstanding id
     * is the earliest unconfirmed send. Already-superseded and already-failed placeholders are
     * skipped, so N confirmed events hide N distinct placeholders.
     *
     * Returns the local id that was hidden, or null if there was nothing to hide.
     */
    fun supersedeOldestOutstanding(): String? {
        val requestId = requestToLocalId.keys.sorted().firstOrNull { isSupersedable(requestToLocalId[it]) }
            ?: return null
        val localId = requestToLocalId[requestId] ?: return null
        val entry = vm.eventChainMap[localId] ?: return null
        val bubble = entry.ourBubble ?: return null
        val newLocalContent = (bubble.localContent?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put(LOCAL_SUPERSEDED, true)
        vm.eventChainMap[localId] = entry.copy(
            ourBubble = bubble.copy(localContent = newLocalContent),
        )
        // Release-visible: this race is intermittent and only reproduces in the field, so the
        // evidence has to survive R8 stripping Log.d.
        Androlog(
            "LocalEcho",
            "echo $localId hidden — confirmed event arrived before its response " +
                "(reqId=$requestId, ${requestToLocalId.size} send(s) in flight)",
        )
        return localId
    }

    /** A placeholder can be hidden only if it is still live: present, not already hidden, not failed. */
    private fun isSupersedable(localId: String?): Boolean {
        val bubble = localId?.let { vm.eventChainMap[it]?.ourBubble } ?: return false
        val lc = bubble.localContent ?: return true
        return !lc.optBoolean(LOCAL_SUPERSEDED) && lc.optString("send_error").isBlank()
    }

    /** Stop watching a placeholder once it's resolved elsewhere (confirmed / evicted / failed). */
    fun cancel(localId: String?) {
        if (localId == null) return
        watchdogJobs.remove(localId)?.cancel()
    }

    private fun isStillSending(localId: String): Boolean {
        val lc = vm.eventChainMap[localId]?.ourBubble?.localContent ?: return false
        if (lc.optString("send_error").isNotBlank()) return false
        return lc.optString("local_send_state") == "sending"
    }

    private fun isFailed(localId: String): Boolean = vm.eventChainMap[localId]?.ourBubble?.localContent?.optString("send_error")?.isNotBlank() == true

    private fun markFailed(localId: String, reason: String, roomId: String) {
        val entry = vm.eventChainMap[localId] ?: return
        val bubble = entry.ourBubble ?: return
        // Un-hide on the way to Failed. If a placeholder was superseded but no response ever
        // arrived, the confirmed event that triggered the hide was not ours (another session, most
        // likely) — this send really did fail and the user must see it.
        val newLocalContent = (bubble.localContent?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("send_error", reason)
        newLocalContent.remove(LOCAL_SUPERSEDED)
        vm.eventChainMap[localId] = entry.copy(ourBubble = bubble.copy(localContent = newLocalContent))
        vm.buildTimelineFromChain(expectedRoomId = roomId)
        watchdogJobs.remove(localId)
        android.util.Log.w("Andromuks", "AppViewModel: Local echo $localId marked Failed: $reason")
    }
}
