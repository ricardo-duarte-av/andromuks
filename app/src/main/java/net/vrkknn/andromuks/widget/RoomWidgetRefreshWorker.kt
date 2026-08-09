package net.vrkknn.andromuks.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Runs [RoomWidgetRefresher] off the caller's thread and pushes the result to the widget.
 *
 * WorkManager rather than a bare coroutine because the callers are all short-lived contexts that
 * may be killed mid-flight — a broadcast receiver, an FCM callback, a Glance action — and a refresh
 * that dies halfway leaves the widget showing a spinner forever.
 *
 * Work is keyed per room with [ExistingWorkPolicy.REPLACE], which doubles as the debounce: a burst
 * of sync events for one room collapses into a single fetch.
 */
class RoomWidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val widgetIds = RoomWidgetStore.widgetIdsForRoom(applicationContext, roomId)
        if (widgetIds.isEmpty()) {
            // The widget was removed while this was queued. Nothing to do, and not an error.
            return Result.success()
        }

        val limit = RoomWidgetStore.MAX_MESSAGE_LIMIT
        val previous = widgetIds.firstNotNullOfOrNull { RoomWidgetStore.readSnapshot(applicationContext, it) }
        val snapshot = RoomWidgetRefresher.refresh(applicationContext, roomId, limit, previous)

        RoomWidgetStore.writeSnapshotForRoom(applicationContext, roomId, snapshot)

        // Prune after the write, and against every widget's snapshot — the avatar directory is
        // shared, so a keep-set covering only this room would delete the files other widgets are
        // displaying. Doing it after the write also means the snapshot we just stored is always
        // part of the keep-set rather than racing it.
        RoomWidgetAvatars.prune(applicationContext, RoomWidgetStore.allReferencedAvatarPaths(applicationContext))

        RoomWidgetUpdater.redraw(applicationContext)
        Log.i(TAG, "Refreshed $roomId: ${snapshot.messages.size} message(s), state=${snapshot.state}")
        return Result.success()
    }

    companion object {
        private const val TAG = "RoomWidgetRefreshWorker"
        private const val KEY_ROOM_ID = "room_id"

        /** Unique work name per room, so REPLACE debounces within a room but never across rooms. */
        private fun workName(roomId: String) = "room-widget-refresh:$roomId"

        /**
         * Queue a refresh for [roomId].
         *
         * [expedited] is for user-visible latency — the manual refresh button — where waiting on
         * normal scheduling would feel broken. Background triggers use the default so a chatty room
         * cannot spend the app's expedited-work quota.
         */
        fun enqueue(context: Context, roomId: String, expedited: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<RoomWidgetRefreshWorker>()
                .setInputData(workDataOf(KEY_ROOM_ID to roomId))
                .apply {
                    if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName(roomId), ExistingWorkPolicy.REPLACE, request)
        }
    }
}
