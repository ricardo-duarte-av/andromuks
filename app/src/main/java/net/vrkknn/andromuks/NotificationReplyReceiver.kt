package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.ExecApi
import kotlin.concurrent.thread

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
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "Skipping duplicate reply processing - processed ${timeSinceLastProcess}ms ago (dedup window: ${DEDUP_WINDOW_MS}ms)",
                )
            }
            // Return early to prevent duplicate processing
            return
        }

        // Mark this reply as processed (before forwarding to prevent race conditions)
        recentProcessedReplies[dedupKey] = now

        // Suppress the automatic dismiss FCM the backend sends when our reply marks the room read.
        // Any dismiss arriving within 5 seconds of this inline reply is ours, not a remote dismissal.
        FCMService.markRoomReplied(roomId)

        // Clean up old entries (keep only recent entries within dedup window)
        val cutoffTime = now - DEDUP_WINDOW_MS
        recentProcessedReplies.entries.removeAll { it.value < cutoffTime }

        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val useBatterySaver = prefs.getBoolean("use_battery_saver_mode", false)

        if (useBatterySaver) {
            sendViaExec(context, roomId, replyText)
            return
        }

        // CRITICAL FIX: Try to send message directly via WebSocketService if available
        // This works even when MainActivity is not running
        val registeredViewModels = WebSocketService.getRegisteredViewModels()
        val viewModel = registeredViewModels.firstOrNull()

        if (viewModel != null) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "Found registered ViewModel, sending message directly (MainActivity may not be running)",
                )
            }
            // Send message directly via ViewModel - this adds to FIFO buffer
            viewModel.sendMessageFromNotification(roomId, replyText) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Message sent successfully via ViewModel")
                // Dismiss the inline-reply spinner by updating the notification.
                // Android only stops the spinner when notify() is called again with the same ID.
                val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                val authToken = net.vrkknn.andromuks.utils.CredentialStore.getAuthToken(sharedPrefs)
                EnhancedNotificationDisplay(context, homeserverUrl, authToken)
                    .updateNotificationWithReply(roomId, replyText)
            }
            return
        }

        // Fallback: Start MainActivity with reply data if no ViewModel is available
        // MainActivity will process the reply when it starts
        if (BuildConfig.DEBUG) Log.d(TAG, "No ViewModel available, starting MainActivity with reply data")
        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            action = "net.vrkknn.andromuks.ACTION_REPLY"
            putExtra("room_id", roomId)
            putExtra("event_id", intent.getStringExtra("event_id"))
            putExtra("from_reply_receiver", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val resultsBundle = Bundle().apply {
            putCharSequence(KEY_REPLY_TEXT, replyText)
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply").build()),
            mainActivityIntent,
            resultsBundle,
        )

        context.startActivity(mainActivityIntent)
        if (BuildConfig.DEBUG) Log.d(TAG, "Started MainActivity with reply data for roomId: $roomId")
    }

    /**
     * Battery-saver mode: POST `send_message` to `/exec`, which works while the WebSocket is closed.
     *
     * Every outcome must re-post the notification — Android only stops the inline-reply spinner on a
     * re-post, so any path that skips it leaves the spinner running forever (GH #41). Success appends
     * the reply to the conversation; failure re-posts it unchanged and says so with a toast.
     * `/exec send_message` answers as soon as the send is queued, so this is one short request plus
     * [ExecApi]'s network retries. Every outcome goes to Androlog under "Reply": the rest of this
     * path only has `Log.d`, which R8 strips from release builds.
     */
    private fun sendViaExec(context: Context, roomId: String, replyText: String) {
        val pendingResult = goAsync()
        thread(name = "batterySaver-reply") {
            var failure: String? = null
            try {
                // readCredentials, not a hand-built Credentials: it carries the HTTP-basic fallback,
                // so a rejected gomuks_auth cookie no longer fails the reply outright.
                val creds = ExecApi.readCredentials(context)
                val result = ExecApi.sendMessage(creds, roomId, replyText)
                val display = EnhancedNotificationDisplay(context, creds.homeserverUrl, creds.authToken)
                if (result is ExecApi.ExecResult.Success) {
                    Androlog("Reply", "Room $roomId: sent via /exec")
                    display.updateNotificationWithReply(roomId, replyText)
                } else {
                    failure = describe(result, creds)
                    display.clearReplySpinner(roomId)
                }
            } catch (t: Throwable) {
                failure = "threw ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "Notification reply via /exec threw", t)
                EnhancedNotificationDisplay(context, "", "").clearReplySpinner(roomId)
            }
            if (failure == null) {
                pendingResult.finish()
            } else {
                Log.w(TAG, "Notification reply not sent for $roomId: $failure")
                Androlog("Reply", "Room $roomId: NOT sent via /exec — $failure")
                // Toast.show() must run on a Looper thread; finish only once it has been enqueued,
                // or the process can be frozen before the toast is handed to the system.
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(context.applicationContext, "Reply not sent", Toast.LENGTH_LONG).show()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    /** Androlog text for a failed send. Never includes the token or the reply text. */
    private fun describe(result: ExecApi.ExecResult, creds: ExecApi.Credentials): String = when (result) {
        is ExecApi.ExecResult.AuthMissing ->
            "auth rejected (token ${if (creds.authToken.isBlank()) "blank" else "present"}, " +
                "basic fallback ${if (creds.basicAuthProvider != null) "available" else "unavailable"})"

        is ExecApi.ExecResult.CommandError -> "command error: ${result.message}"

        is ExecApi.ExecResult.HttpError -> "HTTP ${result.code} ${result.message}"

        is ExecApi.ExecResult.NetworkError -> "network error: ${result.message}"

        is ExecApi.ExecResult.IdempotencyRejected -> "idempotency rejected: ${result.errcode}"

        is ExecApi.ExecResult.Success -> "success"
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
