package net.vrkknn.andromuks

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide tracker that records when a room's notifications were dismissed by the backend,
 * so an in-flight or deferred notification post can suppress itself instead of resurrecting a
 * conversation the user has already read.
 *
 * ## Why this exists
 *
 * A dismiss FCM (`{ "dismiss": [{ "room_id": … }] }`) and a message FCM race. The dismiss is a
 * fire-and-forget `cancel()` with no durable memory, so anything that posts *after* it wins:
 *  - **Race 1** — the dismiss is processed while [FCMService.handleMessageNotification] is mid-post;
 *    its `cancel()` no-ops (nothing posted yet) and the in-flight `notify()` lands afterward.
 *  - **Race 2** — the dismiss cancels a posted notification, then [NotificationImageWorker]
 *    re-posts after its multi-second download window and resurrects it.
 *
 * The fix is a per-room dismiss **timestamp** (not a flag) plus a per-room monitor:
 *  - Every post site checks [isDismissedAfter] under [lockFor] immediately before `notify()`.
 *  - The dismiss path records via [recordDismiss] under the same lock immediately before `cancel()`.
 *
 * ## Why a timestamp, not a flag
 *
 * The comparison is directional: a post is suppressed only when the dismiss was processed *after*
 * the message that triggered it was received. Both times are on-device wall-clock, so they are
 * directly comparable. The tombstone is a high-water mark — a stale dismiss can never block a
 * *newer* message, because the newer message's receipt time is greater. This makes quick bursts
 * of messages in the same room safe: only the messages that were actually read get suppressed.
 *
 * The dismiss payload from gomuks carries only `room_id` (no event id / timestamp), so this is the
 * best ordering we can establish locally. The one case it cannot resolve — a dismiss FCM delivered
 * *before* the message it follows (possible only if FCM downgrades the high-priority message to
 * normal under quota and reorders it past the normal-priority dismiss) — degrades to a lingering
 * notification, the strictly-less-bad failure, rather than a lost one.
 *
 * Lives in-process; [FCMService] and [NotificationImageWorker] run in the same process and already
 * share [EnhancedNotificationDisplay.roomMessageCache], so no persistence is required.
 */
object NotificationDismissTracker {
    private const val TAG = "NotificationDismissTracker"

    /** roomId -> wall-clock ms of the most recent dismiss processed for that room. */
    private val dismissedAt = ConcurrentHashMap<String, Long>()

    /** roomId -> monitor serialising a dismiss's cancel against a post's notify. */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * TTL is hygiene only — it bounds the map size. Correctness comes from the [isDismissedAfter]
     * timestamp comparison, not from entry expiry.
     */
    private const val TTL_MS = 60_000L

    /**
     * The per-room monitor. Wrap the synchronous `notify()`/`cancel()` (never a suspension point)
     * in `synchronized(lockFor(roomId)) { … }` so check-then-notify and record-then-cancel are
     * mutually exclusive for a given room.
     */
    fun lockFor(roomId: String): Any = locks.getOrPut(roomId) { Any() }

    /** Record that the backend dismissed [roomId]. Call under [lockFor], just before `cancel()`. */
    fun recordDismiss(roomId: String) {
        dismissedAt[roomId] = System.currentTimeMillis()
        if (BuildConfig.DEBUG) Log.d(TAG, "Recorded dismiss for room: $roomId")
        prune()
    }

    /**
     * True iff a dismiss for [roomId] was processed strictly after [messageReceivedAt] — i.e. the
     * message that wants to post was already read by the time the dismiss landed, so it must not
     * post (or, for the worker, must not re-post).
     */
    fun isDismissedAfter(roomId: String, messageReceivedAt: Long): Boolean {
        val t = dismissedAt[roomId] ?: return false
        return t > messageReceivedAt
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        dismissedAt.entries.removeIf { it.value < cutoff }
    }

    /** Clear all state (testing / app reset). */
    fun clear() {
        dismissedAt.clear()
        locks.clear()
    }
}
