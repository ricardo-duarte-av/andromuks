package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Global broadcast receiver for handling notification mark read actions
 * Sends websocket command to mark room as read and dismisses the notification
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

        // Try to send the mark_read directly via a registered ViewModel (works even when
        // MainActivity is not in the foreground). This mirrors the reply path in
        // NotificationReplyReceiver and avoids the broadcast being silently dropped when
        // MainActivity's dynamically-registered receiver isn't alive.
        val viewModel = WebSocketService.getRegisteredViewModels().firstOrNull()
        if (viewModel != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Found registered ViewModel, sending mark_read directly")
            viewModel.markRoomAsReadFromNotification(roomId, eventId ?: "") {
                if (BuildConfig.DEBUG) Log.d(TAG, "mark_read completed via ViewModel")
            }
        } else {
            // Fallback: MainActivity isn't running and no ViewModel is registered.
            // Send the broadcast and hope MainActivity wakes up, or start it explicitly.
            if (BuildConfig.DEBUG) Log.d(TAG, "No ViewModel available, falling back to broadcast")
            val broadcastIntent = Intent("net.vrkknn.andromuks.MARK_READ").apply {
                setPackage(context.packageName)
                putExtra("room_id", roomId)
                putExtra("event_id", eventId ?: "")
            }
            context.sendBroadcast(broadcastIntent)
        }

        // Only dismiss notification if no bubble is open (cancelling destroys the bubble)
        val isBubbleOpen = BubbleTracker.isBubbleOpen(roomId)
        if (isBubbleOpen) {
            if (BuildConfig.DEBUG) Log.d(TAG, "NOT dismissing notification for room: $roomId - bubble is open")
            return
        }

        try {
            NotificationManagerCompat.from(context).cancel(roomId.hashCode())
            if (BuildConfig.DEBUG) Log.d(TAG, "Dismissed notification for room: $roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification", e)
        }
    }
}

