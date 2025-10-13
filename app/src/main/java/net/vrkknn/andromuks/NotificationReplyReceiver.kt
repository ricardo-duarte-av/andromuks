package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * Global broadcast receiver for handling notification reply actions
 * Sends broadcast to MainActivity if running, otherwise starts it
 */
class NotificationReplyReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NotificationReplyReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called with action: ${intent.action}")
        
        val roomId = intent.getStringExtra("room_id")
        val replyText = getReplyText(intent)
        
        Log.d(TAG, "Reply - roomId: $roomId, replyText: $replyText")
        
        if (roomId != null && replyText != null) {
            // Send broadcast to MainActivity
            val broadcastIntent = Intent("net.vrkknn.andromuks.SEND_MESSAGE").apply {
                putExtra("room_id", roomId)
                putExtra("message_text", replyText)
            }
            context.sendBroadcast(broadcastIntent)
            Log.d(TAG, "Sent reply broadcast to MainActivity")
        }
    }
    
    private fun getReplyText(intent: Intent): String? {
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        return remoteInputResults?.getCharSequence("key_reply_text")?.toString()
    }
}

