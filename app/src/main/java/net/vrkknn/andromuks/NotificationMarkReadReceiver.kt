package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlin.concurrent.thread
import net.vrkknn.andromuks.utils.ExecApi

/**
 * Global broadcast receiver for handling notification mark read actions
 * Sends websocket command to mark room as read and dismisses the notification.
 *
 * In battery-saver mode (use_battery_saver_mode pref enabled), routes the mark_read through
 * the HTTP batterySaver instead of the WebSocket so it works while the persistent
 * connection is closed.
 */
class NotificationMarkReadReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationMarkReadReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onReceive called with action: ${intent.action}")

        val roomId = intent.getStringExtra("room_id")
        val eventId = intent.getStringExtra("event_id")

        if (BuildConfig.DEBUG) Log.d(TAG, "Mark read - roomId: $roomId, eventId: $eventId")

        if (roomId == null) return

        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val useBatterySaver = prefs.getBoolean("use_battery_saver_mode", false)

        if (useBatterySaver) {
            // Battery-saver mode: bypass the ViewModel/WebSocket entirely and POST to the batterySaver.
            // BroadcastReceiver onReceive runs on the main thread; ExecApi is blocking,
            // so it must run on a worker thread. goAsync() extends the receiver lifetime.
            val pendingResult = goAsync()
            thread(name = "batterySaver-markread") {
                try {
                    val creds = ExecApi.readCredentials(context)
                    val ok = ExecApi.markRead(creds, roomId, eventId ?: "")
                    if (BuildConfig.DEBUG) Log.d(TAG, "BatterySaver mark_read result: $ok")
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            // Persistent-WebSocket mode: hand off to a registered ViewModel.
            val viewModel = WebSocketService.getRegisteredViewModels().firstOrNull()
            if (viewModel != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Found registered ViewModel, sending mark_read directly")
                viewModel.markRoomAsReadFromNotification(roomId, eventId ?: "") {
                    if (BuildConfig.DEBUG) Log.d(TAG, "mark_read completed via ViewModel")
                }
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "No ViewModel available, falling back to broadcast")
                val broadcastIntent = Intent("net.vrkknn.andromuks.MARK_READ").apply {
                    setPackage(context.packageName)
                    putExtra("room_id", roomId)
                    putExtra("event_id", eventId ?: "")
                }
                context.sendBroadcast(broadcastIntent)
            }
        }

        // Only dismiss notification if no bubble is open (cancelling destroys the bubble)
        val isBubbleOpen = BubbleTracker.isBubbleOpen(roomId)
        if (isBubbleOpen) {
            if (BuildConfig.DEBUG) Log.d(TAG, "NOT dismissing notification for room: $roomId - bubble is open")
            return
        }

        try {
            val notifID = roomId.hashCode()
            NotificationManagerCompat.from(context).cancel(notifID)
            // Keep the group summary in sync (and remove it once this was the last child).
            EnhancedNotificationDisplay.refreshGroupSummary(context, justCancelledId = notifID)
            if (BuildConfig.DEBUG) Log.d(TAG, "Dismissed notification for room: $roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification", e)
        }
    }
}
