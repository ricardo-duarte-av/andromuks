package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import org.json.JSONObject
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
 * best ordering we can establish locally. See [GOMUKS_UPSTREAM_ISSUES.md] at the repo root for the
 * upstream limitations this works around.
 *
 * ## Why the state is persisted
 *
 * [dismissedAt] and [deferred] used to be RAM-only with a 60 s TTL. Both assumptions were wrong for
 * the consumer that needs them most. [NotificationImageWorker] retries on a 15/30/60 s exponential
 * backoff and is additionally deferrable by WorkManager quota and Doze, so it can easily reach its
 * re-post **minutes** after the dismiss that should stop it — by which time a 60 s entry is gone.
 * The worker's only remaining guard is then `activeNotifications`, which
 * [FCMService.handleDismissNotification] itself documents as stale on some OEMs, and the notification
 * gets resurrected. Process death made it worse: FCMService is short-lived, so a worker resuming in
 * a fresh process saw an empty map and no deferrals at all.
 *
 * So both maps are mirrored into `AndromuksAppPrefs` and hydrated lazily on first use in a new
 * process, and [TTL_MS] is now comfortably longer than the worker's worst-case latency. The TTL is
 * still hygiene rather than correctness — it only bounds the stored size — but it now outlives every
 * reader that depends on it.
 */
object NotificationDismissTracker {
    private const val TAG = "NotificationDismissTracker"

    private const val PREFS_NAME = "AndromuksAppPrefs"
    private const val KEY_TOMBSTONES = "notif_dismiss_tombstones"
    private const val KEY_DEFERRED = "notif_deferred_dismisses"

    /** roomId -> wall-clock ms of the most recent dismiss processed for that room. */
    private val dismissedAt = ConcurrentHashMap<String, Long>()

    /** roomId -> monitor serialising a dismiss's cancel against a post's notify. */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Bound on how long a tombstone is kept. Must stay comfortably above the worst-case delay of
     * every reader — dominated by [NotificationImageWorker], whose three retries alone span ~105 s
     * of backoff before Doze deferral is even considered. 30 minutes leaves that a wide margin while
     * still bounding the persisted map.
     */
    private const val TTL_MS = 30 * 60 * 1000L

    /**
     * Application context for the persisted mirror, captured from whichever notification path runs
     * first in this process. Every caller already holds a Context, so this never has to be injected.
     */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var hydrated = false

    /**
     * Give the tracker a context so it can persist and restore. Safe (and cheap) to call repeatedly;
     * hydration from disk happens exactly once per process, on the first call.
     */
    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        hydrate()
    }

    private fun hydrate() {
        if (hydrated) return
        val ctx = appContext ?: return
        synchronized(this) {
            if (hydrated) return
            hydrated = true
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val cutoff = System.currentTimeMillis() - TTL_MS
                val stored = prefs.getString(KEY_TOMBSTONES, null)
                if (!stored.isNullOrBlank()) {
                    val obj = JSONObject(stored)
                    for (roomId in obj.keys()) {
                        val ts = obj.optLong(roomId, 0L)
                        // Only restore entries that are still inside the TTL — a tombstone older
                        // than that can no longer block anything a live reader cares about.
                        if (ts >= cutoff) dismissedAt.putIfAbsent(roomId, ts)
                    }
                }
                prefs.getStringSet(KEY_DEFERRED, emptySet())?.let { deferred.addAll(it) }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Hydrated ${dismissedAt.size} tombstone(s), ${deferred.size} deferral(s)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hydrate persisted dismiss state", e)
            }
        }
    }

    private fun persistTombstones() {
        val ctx = appContext ?: return
        try {
            val obj = JSONObject()
            for ((roomId, ts) in dismissedAt) obj.put(roomId, ts)
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOMBSTONES, obj.toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist dismiss tombstones", e)
        }
    }

    private fun persistDeferred() {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                // Defensive copy: SharedPreferences must not be handed a live mutable set.
                .putStringSet(KEY_DEFERRED, deferred.toSet())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist deferred dismisses", e)
        }
    }

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
        persistTombstones()
    }

    /**
     * True iff a dismiss for [roomId] was processed strictly after [messageReceivedAt] — i.e. the
     * message that wants to post was already read by the time the dismiss landed, so it must not
     * post (or, for the worker, must not re-post).
     */
    fun isDismissedAfter(roomId: String, messageReceivedAt: Long): Boolean {
        hydrate()
        val t = dismissedAt[roomId] ?: return false
        return t > messageReceivedAt
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        dismissedAt.entries.removeIf { it.value < cutoff }
    }

    /**
     * Rooms whose dismiss arrived while it could not be applied — a bubble was open for the room
     * (cancelling would destroy the bubble) or the reply-protection window was still running.
     *
     * Both cases used to `continue` past the dismiss entirely, dropping it forever: the backend
     * never re-sends, and nothing re-evaluated when the blocking condition cleared. Since
     * [PushDismiss] carries only a room id there is nothing to replay from, so the deferral is
     * simply the room id — [drainDeferred] re-runs the normal dismissal for it later.
     */
    private val deferred = ConcurrentHashMap.newKeySet<String>()

    /** Remember that [roomId]'s dismiss could not be applied yet. */
    fun deferDismiss(roomId: String) {
        deferred.add(roomId)
        if (BuildConfig.DEBUG) Log.d(TAG, "Deferred dismiss for room: $roomId")
        persistDeferred()
    }

    /**
     * Apply every deferred dismiss whose blocker has cleared. Rooms that are still blocked (a
     * bubble came back) stay deferred for the next drain.
     *
     * Called when a bubble closes and after the reply-protection window elapses.
     */
    fun drainDeferred(context: Context) {
        attach(context)
        if (deferred.isEmpty()) return
        val cancelled = mutableSetOf<Int>()
        var changed = false
        for (roomId in deferred.toList()) {
            if (BubbleTracker.isBubbleOpen(roomId)) continue
            deferred.remove(roomId)
            changed = true
            cancelled += EnhancedNotificationDisplay.dismissRoomNotification(
                context,
                roomId,
                reason = "deferred dismiss drained",
            )
        }
        if (changed) persistDeferred()
        if (cancelled.isNotEmpty()) {
            EnhancedNotificationDisplay.refreshGroupSummary(context, justCancelledIds = cancelled)
        }
    }

    /** Clear all state (testing / app reset). */
    fun clear() {
        dismissedAt.clear()
        locks.clear()
        deferred.clear()
        persistTombstones()
        persistDeferred()
    }
}
