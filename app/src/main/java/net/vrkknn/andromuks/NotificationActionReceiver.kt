package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver to handle notification actions
 */
class NotificationActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NotificationActionReceiver"
        const val ACTION_REPLY = "net.vrkknn.andromuks.ACTION_REPLY"
        const val ACTION_MARK_READ = "net.vrkknn.andromuks.ACTION_MARK_READ"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_REPLY -> {
                val roomId = intent.getStringExtra("room_id")
                val eventId = intent.getStringExtra("event_id")
                val replyText = getReplyText(intent)
                
                if (roomId != null && replyText != null) {
                    Log.d(TAG, "Handling reply action for room: $roomId, text: $replyText")
                    sendReplyMessage(context, roomId, replyText)
                }
            }
            ACTION_MARK_READ -> {
                val roomId = intent.getStringExtra("room_id")
                val eventId = intent.getStringExtra("event_id")
                
                if (roomId != null) {
                    Log.d(TAG, "Handling mark read action for room: $roomId, event: $eventId")
                    markMessageAsRead(context, roomId, eventId)
                }
            }
        }
    }
    
    private fun getReplyText(intent: Intent): String? {
        return androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence("key_reply_text")
            ?.toString()
    }
    
    private fun sendReplyMessage(context: Context?, roomId: String, text: String) {
        Log.d(TAG, "Sending reply message to room $roomId: $text")
        
        // Send a broadcast to the main app to handle the WebSocket command
        val intent = Intent("net.vrkknn.andromuks.SEND_MESSAGE").apply {
            putExtra("room_id", roomId)
            putExtra("message_text", text)
        }
        context?.sendBroadcast(intent)
    }
    
    private fun markMessageAsRead(context: Context?, roomId: String, eventId: String?) {
        Log.d(TAG, "Marking message as read in room $roomId, event: $eventId")
        
        // Send a broadcast to the main app to handle the WebSocket command
        val intent = Intent("net.vrkknn.andromuks.MARK_READ").apply {
            putExtra("room_id", roomId)
            putExtra("event_id", eventId)
        }
        context?.sendBroadcast(intent)
    }
}
