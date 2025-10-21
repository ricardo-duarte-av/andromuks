package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import android.os.Bundle

/**
 * Global broadcast receiver for handling notification reply actions
 * Sends broadcast to MainActivity if running, otherwise starts it
 */
class NotificationReplyReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NotificationReplyReceiver"
        private const val KEY_REPLY_TEXT = "key_reply_text"
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
        
        // Delegate to the same action flow used by PendingIntent so we don't double-send
        val forwardIntent = Intent("net.vrkknn.andromuks.ACTION_REPLY").apply {
            setPackage(context.packageName)
            putExtra("room_id", roomId)
            putExtra("event_id", intent.getStringExtra("event_id"))
            putExtra("from_reply_receiver", true)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }

        val resultsBundle = Bundle().apply {
            putCharSequence(KEY_REPLY_TEXT, replyText)
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply").build()),
            forwardIntent,
            resultsBundle
        )
        
        context.sendOrderedBroadcast(forwardIntent, null)
        Log.d(TAG, "Forwarded reply via ordered ACTION_REPLY broadcast for roomId: $roomId")
    }
    
    private fun getReplyText(intent: Intent): String? {
        Log.d(TAG, "getReplyText called")
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        Log.d(TAG, "RemoteInput results: $remoteInputResults")
        
        if (remoteInputResults == null) {
            Log.e(TAG, "RemoteInput results is null")
            return null
        }
        
        val replyText = remoteInputResults.getCharSequence(KEY_REPLY_TEXT)?.toString()
        Log.d(TAG, "Extracted reply text: '$replyText'")
        return replyText
    }
}

