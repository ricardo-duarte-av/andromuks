package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Global broadcast receiver for handling notification mark read actions
 * Dismisses the notification immediately and sends broadcast to MainActivity if running
 */
class NotificationMarkReadReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NotificationMarkReadReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called with action: ${intent.action}")
        
        val roomId = intent.getStringExtra("room_id")
        val eventId = intent.getStringExtra("event_id")
        
        Log.d(TAG, "Mark read - roomId: $roomId, eventId: $eventId")
        
        if (roomId != null) {
            // Send broadcast to MainActivity (will handle if running)
            val broadcastIntent = Intent("net.vrkknn.andromuks.MARK_READ").apply {
                putExtra("room_id", roomId)
                putExtra("event_id", eventId ?: "")
            }
            context.sendBroadcast(broadcastIntent)
            Log.d(TAG, "Sent mark read broadcast to MainActivity")
            
            // Dismiss the notification immediately for instant feedback
            try {
                val notificationManager = NotificationManagerCompat.from(context)
                val notifID = roomId.hashCode()
                notificationManager.cancel(notifID)
                Log.d(TAG, "Dismissed notification for room: $roomId")
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing notification", e)
            }
        }
    }
}

