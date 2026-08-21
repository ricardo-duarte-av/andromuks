package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Takes a room's notification down when `sync_complete` says the conversation has been read,
 * independently of the dismiss FCM.
 *
 * ## Why the socket, when there is already a dismiss push
 *
 * The FCM dismiss is the only signal Andromuks used to act on, and it goes missing in three
 * documented ways (all detailed in `GOMUKS_UPSTREAM_ISSUES.md` at the repo root):
 *
 *  1. gomuks only emits a dismiss when `room.UnreadNotifications > 0` was true *before* the read
 *     receipt was applied. Upstream's own TODO next to that condition concedes the old count is
 *     sometimes already zero, in which case **no dismiss push is generated at all** and no amount of
 *     client-side FCM handling can recover.
 *  2. Dismisses are capped at ten per sync, so marking many rooms read at once drops the rest.
 *  3. A dismiss-only payload carries no `Sound`, so it ships at normal FCM priority and Doze defers
 *     it — sometimes for a long time.
 *
 * `sync_complete` carries the same per-room `dismiss_notifications` boolean that *generates* that
 * push, over a socket we already hold. Acting on it directly sidesteps (2) and (3) entirely, and the
 * unread-is-zero arm below covers (1).
 *
 * ## Why this runs in SyncRepository rather than the ViewModel
 *
 * Every per-room consumer downstream is unsuitable for a dismissal:
 *  - `SyncRoomsCoordinator.processSyncCompleteAtomic`, `SpaceRoomParser` and `SyncIngestor` all sit
 *    behind [SyncBatchProcessor], which in always-on backgrounded mode flushes on a **5-minute**
 *    timer. A notification that clears five minutes after the user read the room is not a fix.
 *  - `SyncRoomsCoordinator`'s receipt loop skips any room that is not actively cached or open.
 *  - In UI-less always-on there is no ViewModel at all and the payload goes into `noVmBuffer`, so
 *    none of that code runs until an Activity attaches.
 *
 * [SyncRepository.processSyncCompletePipeline] is upstream of all three, runs once per arriving
 * sync on the single ordered consumer, and needs no ViewModel.
 */
object NotificationSyncReconciler {
    private const val TAG = "NotifSyncReconciler"

    /**
     * Room ids in [syncJson] whose notifications should be taken down.
     *
     * Pure and side-effect free so it can be unit tested directly (see
     * `NotificationSyncReconcilerTest`) — the Android calls live in [applyDismissals].
     *
     * A room is selected when **either**:
     *
     *  - `dismiss_notifications` is true — the backend's explicit signal, the same one that produces
     *    the FCM dismiss; or
     *  - the room is in [postedRoomIds], its `meta` reports every unread counter at zero, and the
     *    room object carries no `notifications` array. This is the belt-and-braces arm that recovers
     *    the cases where gomuks never emits the flag. Gating it on [postedRoomIds] is what keeps it
     *    cheap and safe: unread-zero is the steady state for nearly every room in the account, so
     *    without that gate this would consider hundreds of rooms per sync to no purpose.
     *
     * The `notifications` check matters — a sync can carry both a fresh notification and zeroed
     * counters for the same room (read on another device, then a new message arrives in the same
     * batch). That room is *not* dismissible; the new message should notify.
     */
    fun collectDismissibleRooms(syncJson: JSONObject, postedRoomIds: Set<String>): Set<String> {
        val rooms = syncJson.optJSONObject("data")?.optJSONObject("rooms") ?: return emptySet()
        return rooms.keys().asSequence()
            .filter { roomId ->
                val roomObj = rooms.optJSONObject(roomId)
                roomObj != null && isDismissible(roomId, roomObj, postedRoomIds)
            }
            .toSet()
    }

    /** The two-arm selection rule described on [collectDismissibleRooms], for a single room. */
    private fun isDismissible(roomId: String, roomObj: JSONObject, postedRoomIds: Set<String>): Boolean {
        if (roomObj.optBoolean("dismiss_notifications", false)) return true
        if (roomId !in postedRoomIds) return false
        if ((roomObj.optJSONArray("notifications")?.length() ?: 0) > 0) return false
        val meta = roomObj.optJSONObject("meta") ?: return false
        return isFullyRead(meta)
    }

    /**
     * True when every unread counter present in [meta] is zero.
     *
     * `unread_notifications` is read defensively: it is not in the payloads we see today (only
     * `unread_messages` and `unread_highlights` are), but it exists on the backend's room model and
     * costs nothing to honour if it starts being sent.
     */
    private fun isFullyRead(meta: JSONObject): Boolean = meta.optInt("unread_messages", 0) <= 0 &&
        meta.optInt("unread_highlights", 0) <= 0 &&
        meta.optInt("unread_notifications", 0) <= 0

    /**
     * Apply [collectDismissibleRooms] to [syncJson], cancelling what can be cancelled and deferring
     * what cannot.
     *
     * Mirrors [FCMService.handleDismissNotification] deliberately — same guards, same helper, same
     * deferral mechanism — so the two paths cannot drift into disagreeing about when a notification
     * may be taken down. Cheap on the common sync, which selects nothing at all.
     */
    fun reconcile(context: Context, syncJson: JSONObject) {
        val posted = EnhancedNotificationDisplay.roomsWithPostedNotifications()
        val dismissible = try {
            collectDismissibleRooms(syncJson, posted)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan sync_complete for dismissals", e)
            return
        }
        if (dismissible.isEmpty()) return
        applyDismissals(context, dismissible)
    }

    private fun applyDismissals(context: Context, roomIds: Set<String>) {
        NotificationDismissTracker.attach(context)
        // Accumulate and refresh the group summary ONCE after the loop. cancel() is asynchronous
        // across the binder, so a per-room refresh counts the rooms cancelled by earlier iterations
        // and re-posts a summary with nothing left under it. See refreshGroupSummary's KDoc.
        val cancelledIds = mutableSetOf<Int>()
        var anyDeferred = false
        for (roomId in roomIds) {
            when {
                BubbleTracker.isBubbleOpen(roomId) -> {
                    // Cancelling would destroy the open bubble. Defer, don't drop.
                    NotificationDismissTracker.deferDismiss(roomId)
                    Androlog("Notifications", "Room $roomId: sync dismiss deferred (bubble open)")
                }

                FCMService.isReplyProtected(roomId) -> {
                    // The mark-read this sync reports is the echo of the user's own inline reply.
                    NotificationDismissTracker.deferDismiss(roomId)
                    anyDeferred = true
                    Androlog("Notifications", "Room $roomId: sync dismiss deferred (reply-protection window)")
                }

                else -> {
                    cancelledIds += EnhancedNotificationDisplay.dismissRoomNotification(
                        context,
                        roomId,
                        reason = "sync_complete read reconciliation",
                    )
                }
            }
        }
        if (cancelledIds.isNotEmpty()) {
            EnhancedNotificationDisplay.refreshGroupSummary(context, justCancelledIds = cancelledIds)
        }
        if (anyDeferred) scheduleDeferredDrain(context)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Reconciled ${roomIds.size} room(s) from sync: cancelled=${cancelledIds.size}")
        }
    }

    /**
     * Re-check the reply-protection deferrals once the window has elapsed. Unlike the bubble case
     * there is no event to hang the drain off — the window simply expires — so this waits it out.
     * Uses the process-wide application scope rather than a service scope: this path can run with no
     * FCMService alive, which is exactly when the deferral would otherwise be lost.
     */
    private fun scheduleDeferredDrain(context: Context) {
        val appContext = context.applicationContext
        AndromuksApplication.applicationScope.launch {
            delay(FCMService.REPLY_DISMISS_PROTECTION_MS + 500L)
            NotificationDismissTracker.drainDeferred(appContext)
        }
    }
}
