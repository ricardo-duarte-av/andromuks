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
        
        if (roomId != null) {
            // Send broadcast to MainActivity with explicit package to trigger websocket command
            val broadcastIntent = Intent("net.vrkknn.andromuks.MARK_READ").apply {
                setPackage(context.packageName)
                putExtra("room_id", roomId)
                putExtra("event_id", eventId ?: "")
            }
            context.sendBroadcast(broadcastIntent)
            if (BuildConfig.DEBUG) Log.d(TAG, "Sent mark read broadcast to package: ${context.packageName}")
            
            // Dismiss the notification immediately for instant feedback
            try {
                val notificationManager = NotificationManagerCompat.from(context)
                val notifID = roomId.hashCode()
                notificationManager.cancel(notifID)
                if (BuildConfig.DEBUG) Log.d(TAG, "Dismissed notification for room: $roomId")
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing notification", e)
            }
        }
    }
}

