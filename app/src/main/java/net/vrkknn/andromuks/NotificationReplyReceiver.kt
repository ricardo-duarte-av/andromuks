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
        
        if (roomId == null) {
            Log.e(TAG, "roomId is null, cannot send reply")
            return
        }
        
        if (replyText == null) {
            Log.e(TAG, "replyText is null, cannot send reply")
            return
        }
        
        // Send broadcast to MainActivity with explicit package
        val broadcastIntent = Intent("net.vrkknn.andromuks.SEND_MESSAGE").apply {
            setPackage(context.packageName)
            putExtra("room_id", roomId)
            putExtra("message_text", replyText)
        }
        context.sendBroadcast(broadcastIntent)
        Log.d(TAG, "Sent reply broadcast to package: ${context.packageName} - roomId: $roomId, text: $replyText")
    }
    
    private fun getReplyText(intent: Intent): String? {
        Log.d(TAG, "getReplyText called")
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        Log.d(TAG, "RemoteInput results: $remoteInputResults")
        
        if (remoteInputResults == null) {
            Log.e(TAG, "RemoteInput results is null")
            return null
        }
        
        val replyText = remoteInputResults.getCharSequence("key_reply_text")?.toString()
        Log.d(TAG, "Extracted reply text: '$replyText'")
        return replyText
    }
}

