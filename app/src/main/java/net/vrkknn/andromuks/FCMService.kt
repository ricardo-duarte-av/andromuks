package net.vrkknn.andromuks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.Encryption
import org.json.JSONObject
import java.util.UUID

/**
 * Get the push encryption key that was used for registration
 * This is the same key that was sent to the Gomuks Backend
 */
private fun getExistingPushEncryptionKey(context: Context): ByteArray? {
    return try {
        // Use the same key retrieval method as WebClientPushIntegration
        val sharedPref = context.getSharedPreferences("web_client_prefs", Context.MODE_PRIVATE)
        val encryptedKey = sharedPref.getString("push_encryption_key", null)
        
        Log.d("FCMService", "Retrieved encrypted key from SharedPreferences: $encryptedKey")
        
        if (encryptedKey != null) {
            // The key is stored as base64-encoded bytes
            val decodedKey = android.util.Base64.decode(encryptedKey, android.util.Base64.DEFAULT)
            Log.d("FCMService", "Decoded key of size: ${decodedKey.size} bytes")
            Log.d("FCMService", "Decoded key (first 8 bytes): ${decodedKey.take(8).joinToString { "%02x".format(it) }}")
            decodedKey
        } else {
            Log.e("FCMService", "No push encryption key found in SharedPreferences")
            null
        }
    } catch (e: Exception) {
        Log.e("FCMService", "Error getting push encryption key", e)
        null
    }
}

class FCMService : FirebaseMessagingService() {
    
    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "matrix_notifications"
        private const val CHANNEL_NAME = "Matrix Messages"
        private const val CHANNEL_DESCRIPTION = "Notifications for Matrix messages and events"
        
        // Notification action constants
        private const val ACTION_REPLY = "action_reply"
        private const val ACTION_MARK_READ = "action_mark_read"
        private const val EXTRA_ROOM_ID = "extra_room_id"
        private const val EXTRA_EVENT_ID = "extra_event_id"
        private const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
    
    private lateinit var enhancedNotificationDisplay: EnhancedNotificationDisplay
    
    override fun onCreate() {
        super.onCreate()
        
        // Get auth token and homeserver URL from SharedPreferences
        val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
        val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        
        enhancedNotificationDisplay = EnhancedNotificationDisplay(this, homeserverUrl, authToken)
        enhancedNotificationDisplay.createNotificationChannel()
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d(TAG, "FCM message received - data: ${remoteMessage.data}, notification: ${remoteMessage.notification}")
        
        // Handle data payload (matches the other Gomuks client approach)
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Processing data payload: ${remoteMessage.data}")
            
            // Get push encryption key (matches the other Gomuks client)
            val pushEncKey = getExistingPushEncryptionKey(this)
            if (pushEncKey == null) {
                Log.e(TAG, "No push encryption key found to handle $remoteMessage")
                return
            }
            
            // Debug: Log key and payload info
            val encryptedPayload = remoteMessage.data.getValue("payload")
            Log.d(TAG, "Using push encryption key of size: ${pushEncKey.size} bytes")
            Log.d(TAG, "Key (first 8 bytes): ${pushEncKey.take(8).joinToString { "%02x".format(it) }}")
            Log.d(TAG, "Encrypted payload length: ${encryptedPayload.length}")
            Log.d(TAG, "Encrypted payload (first 50 chars): ${encryptedPayload.take(50)}")
            Log.d(TAG, "Encrypted payload (last 50 chars): ${encryptedPayload.takeLast(50)}")
            
            // Determine payload type based on length
            val payloadType = when {
                encryptedPayload.length < 200 -> "SHORT_PAYLOAD (likely message notification)"
                encryptedPayload.length > 1000 -> "LONG_PAYLOAD (likely mark_read/sync notification)"
                else -> "MEDIUM_PAYLOAD (unknown type)"
            }
            Log.d(TAG, "Detected payload type: $payloadType")
            
            // Check if payload might be JSON with multiple encrypted parts
            if (encryptedPayload.startsWith("{") || encryptedPayload.contains("\"")) {
                Log.d(TAG, "Payload appears to be JSON format, not raw encrypted data")
                Log.d(TAG, "Full payload: $encryptedPayload")
                return // Skip decryption for JSON payloads
            }
            
            // Decrypt the payload (matches the other Gomuks client)
            val decryptedPayload: String = try {
                Encryption.fromPlainKey(pushEncKey).decrypt(encryptedPayload)
            } catch (e: Exception) {
                Log.e(TAG, "Error decrypting $payloadType", e)
                Log.e(TAG, "This might be a mark_read notification or different payload format")
                return
            }
            
            Log.d(TAG, "Successfully decrypted payload: ${decryptedPayload.take(100)}...")
            
            // Parse the decrypted JSON and handle different payload types
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val jsonObject = JSONObject(decryptedPayload)
                    Log.d(TAG, "Decrypted JSON keys: ${jsonObject.keys().asSequence().toList()}")
                    
                    // Handle different payload types
                    when {
                        jsonObject.has("messages") -> {
                            Log.d(TAG, "Processing message notification payload")
                            handleMessageNotification(jsonObject)
                        }
                        jsonObject.has("dismiss") -> {
                            Log.d(TAG, "Processing dismiss notification payload")
                            handleDismissNotification(jsonObject)
                        }
                        else -> {
                            Log.d(TAG, "Unknown payload type, trying legacy parsing")
                            // Try legacy parsing for backward compatibility
                            val jsonDataMap = mutableMapOf<String, String>()
                            jsonObject.keys().forEach { key ->
                                jsonDataMap[key] = jsonObject.getString(key)
                            }
                            
                            val notificationData = NotificationDataParser.parseNotificationData(jsonDataMap)
                            if (notificationData != null) {
                                enhancedNotificationDisplay.showEnhancedNotification(notificationData)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing decrypted payload", e)
                }
            }
        }
        
        // Handle notification payload (for when app is in background)
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "Processing notification payload - title: ${notification.title}, body: ${notification.body}")
            showNotification(
                title = notification.title ?: "New message",
                body = notification.body ?: "",
                data = remoteMessage.data
            )
        }
    }
    
    /**
     * Handle message notification payload
     */
    private fun handleMessageNotification(jsonObject: JSONObject) {
        try {
            val messagesArray = jsonObject.getJSONArray("messages")
            Log.d(TAG, "Found ${messagesArray.length()} messages in notification")
            
            // Process each message
            for (i in 0 until messagesArray.length()) {
                val message = messagesArray.getJSONObject(i)
                Log.d(TAG, "Processing message: $message")
                
                // Extract message data and create notification
                val roomId = message.optString("room_id", "")
                val eventId = message.optString("event_id", "")
                val sender = message.optString("sender", "")
                val senderDisplayName = message.optString("sender_display_name", sender)
                val roomName = message.optString("room_name", roomId)
                val body = message.optString("body", "New message")
                val type = message.optString("type", "")
                val avatarUrl = message.optString("avatar_url", null)
                val roomAvatarUrl = message.optString("room_avatar_url", null)
                val timestamp = message.optLong("timestamp", System.currentTimeMillis())
                val unreadCount = message.optInt("unread_count", 1)
                
                val notificationData = NotificationData(
                    roomId = roomId,
                    eventId = eventId,
                    sender = sender,
                    senderDisplayName = senderDisplayName,
                    roomName = roomName,
                    body = body,
                    type = type,
                    avatarUrl = avatarUrl,
                    roomAvatarUrl = roomAvatarUrl,
                    timestamp = timestamp,
                    unreadCount = unreadCount
                )
                
                Log.d(TAG, "Showing notification for room: $roomId")
                enhancedNotificationDisplay.showEnhancedNotification(notificationData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message notification", e)
        }
    }
    
    /**
     * Handle dismiss notification payload
     */
    private fun handleDismissNotification(jsonObject: JSONObject) {
        try {
            val dismissArray = jsonObject.getJSONArray("dismiss")
            Log.d(TAG, "Found ${dismissArray.length()} dismiss requests")
            
            // Dismiss notifications for each room
            for (i in 0 until dismissArray.length()) {
                val dismissItem = dismissArray.getJSONObject(i)
                val roomId = dismissItem.optString("room_id", "")
                
                Log.d(TAG, "Dismissing notifications for room: $roomId")
                
                // Dismiss the notification for this room
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(roomId.hashCode())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling dismiss notification", e)
        }
    }
    
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        
        // Register the new token with the Matrix backend
        CoroutineScope(Dispatchers.IO).launch {
            registerTokenWithBackend(token)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    
    private fun showNotification(
        title: String,
        body: String,
        data: Map<String, String>
    ) {
        val roomId = data["room_id"]
        val eventId = data["event_id"]
        val notificationId = generateNotificationId(roomId)
        
        // Create intent for opening the app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("room_id", roomId)
            putExtra("event_id", eventId)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create notification with reply action
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        
        // Show notification
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(notificationId, notification)
    }
    
    private fun generateNotificationId(roomId: String?): Int {
        // Generate a consistent notification ID for the room
        return roomId?.hashCode()?.let { kotlin.math.abs(it) } ?: UUID.randomUUID().hashCode()
    }
    
    private suspend fun registerTokenWithBackend(token: String) {
        // TODO: Implement token registration with Matrix backend
        // This should make an HTTP request to your Matrix homeserver's push gateway
        // with the FCM token, user credentials, etc.
        
        // Example implementation would be:
        // 1. Get user credentials from SharedPreferences or secure storage
        // 2. Make HTTP POST to /_matrix/push/v1/register endpoint
        // 3. Handle response and store registration status
        
        // For now, we'll just log the token
        android.util.Log.d("FCMService", "New FCM token: $token")
    }
}
