package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Standalone BroadcastReceiver for handling notification actions
 * This receiver is independent of MainActivity and can handle actions even when the app is not running
 */
class NotificationActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NotificationActionReceiver"
        const val ACTION_REPLY = "net.vrkknn.andromuks.ACTION_REPLY"
        const val ACTION_MARK_READ = "net.vrkknn.andromuks.ACTION_MARK_READ"
        private const val KEY_REPLY_TEXT = "key_reply_text"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called with action: ${intent.action}")
        
        when (intent.action) {
            ACTION_REPLY -> handleReply(context, intent)
            ACTION_MARK_READ -> handleMarkRead(context, intent)
        }
    }
    
    private fun handleReply(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra("room_id")
        val eventId = intent.getStringExtra("event_id")
        val replyText = getReplyText(intent)
        
        Log.d(TAG, "Reply action - roomId: $roomId, eventId: $eventId, replyText: '$replyText'")
        
        if (roomId.isNullOrEmpty() || replyText.isNullOrEmpty()) {
            Log.w(TAG, "Missing required data for reply - roomId: $roomId, replyText: $replyText")
            return
        }
        
        // Launch MainActivity with the reply action
        // This ensures the app wakes up and the websocket reconnects if needed
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = "net.vrkknn.andromuks.INTERNAL_REPLY"
            putExtra("room_id", roomId)
            putExtra("event_id", eventId)
            putExtra("reply_text", replyText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        Log.d(TAG, "Launching MainActivity for reply action")
        context.startActivity(launchIntent)
        
        // Alternatively, if MainActivity is already running, send a broadcast to it
        val broadcastIntent = Intent("net.vrkknn.andromuks.SEND_MESSAGE").apply {
            putExtra("room_id", roomId)
            putExtra("message_text", replyText)
        }
        context.sendBroadcast(broadcastIntent)
    }
    
    private fun handleMarkRead(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra("room_id")
        val eventId = intent.getStringExtra("event_id")
        
        Log.d(TAG, "Mark read action - roomId: $roomId, eventId: $eventId")
        
        if (roomId.isNullOrEmpty()) {
            Log.w(TAG, "Missing required data for mark read - roomId: $roomId")
            return
        }
        
        // Launch MainActivity with the mark read action
        // This ensures the app wakes up and the websocket reconnects if needed
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = "net.vrkknn.andromuks.INTERNAL_MARK_READ"
            putExtra("room_id", roomId)
            putExtra("event_id", eventId ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        Log.d(TAG, "Launching MainActivity for mark read action")
        context.startActivity(launchIntent)
        
        // Alternatively, if MainActivity is already running, send a broadcast to it
        val broadcastIntent = Intent("net.vrkknn.andromuks.MARK_READ").apply {
            putExtra("room_id", roomId)
            putExtra("event_id", eventId ?: "")
        }
        context.sendBroadcast(broadcastIntent)
    }
    
    private fun getReplyText(intent: Intent): String? {
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        return remoteInputResults?.getCharSequence(KEY_REPLY_TEXT)?.toString()
    }
}

