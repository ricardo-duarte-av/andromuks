package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig

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
        
        // In-memory deduplication to prevent processing the same reply multiple times
        // Key: "roomId|replyText|timestamp" (using timestamp to allow same message after window)
        // Value: processing time
        private val recentProcessedReplies = mutableMapOf<String, Long>()
        // CRITICAL FIX: Unify deduplication window with AppViewModel (5 seconds)
        // This prevents race conditions where one layer thinks it's a duplicate but the other doesn't
        private const val DEDUP_WINDOW_MS = 5000L // 5 seconds deduplication window (matches AppViewModel)
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onReceive called with action: ${intent.action}")
        
        val roomId = intent.getStringExtra("room_id")
        val replyText = getReplyText(intent)
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Reply - roomId: $roomId, replyText: $replyText")
        
        if (roomId == null) {
            Log.e(TAG, "roomId is null, cannot send reply")
            return
        }
        
        if (replyText == null) {
            Log.e(TAG, "replyText is null, cannot send reply")
            return
        }
        
        // DEDUPLICATION: Check if we've processed this exact reply recently
        // Use a combination of roomId and replyText to create a unique key
        // Include a timestamp component to allow same message after dedup window expires
        val now = System.currentTimeMillis()
        val dedupKey = "$roomId|$replyText"
        val lastProcessedTime = recentProcessedReplies[dedupKey]
        
        if (lastProcessedTime != null && (now - lastProcessedTime) < DEDUP_WINDOW_MS) {
            val timeSinceLastProcess = now - lastProcessedTime
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping duplicate reply processing - processed ${timeSinceLastProcess}ms ago (dedup window: ${DEDUP_WINDOW_MS}ms)")
            // Return early to prevent duplicate processing
            return
        }
        
        // Mark this reply as processed (before forwarding to prevent race conditions)
        recentProcessedReplies[dedupKey] = now
        
        // Clean up old entries (keep only recent entries within dedup window)
        val cutoffTime = now - DEDUP_WINDOW_MS
        recentProcessedReplies.entries.removeAll { it.value < cutoffTime }
        
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
        
        // CRITICAL FIX: Use ordered broadcast with explicit parameters to ensure MainActivity receives it first
        // This prevents multiple receivers from processing the same reply (causing 3x sends)
        // MainActivity will call abortBroadcast() to prevent other receivers from processing
        context.sendOrderedBroadcast(
            forwardIntent,
            null,  // permission
            null,  // resultReceiver (not needed)
            null,  // scheduler (use default)
            0,     // initialCode (0 = no result code needed)
            null,  // initialData
            null   // initialExtras
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "Forwarded reply via ordered ACTION_REPLY broadcast for roomId: $roomId")
    }
    
    private fun getReplyText(intent: Intent): String? {
        if (BuildConfig.DEBUG) Log.d(TAG, "getReplyText called")
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        if (BuildConfig.DEBUG) Log.d(TAG, "RemoteInput results: $remoteInputResults")
        
        if (remoteInputResults == null) {
            Log.e(TAG, "RemoteInput results is null")
            return null
        }
        
        val replyText = remoteInputResults.getCharSequence(KEY_REPLY_TEXT)?.toString()
        if (BuildConfig.DEBUG) Log.d(TAG, "Extracted reply text: '$replyText'")
        return replyText
    }
}

