package net.vrkknn.andromuks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    private var pushEncryptionKey: ByteArray? = null
    
    override fun onCreate() {
        super.onCreate()
        
        // Get auth token and homeserver URL from SharedPreferences
        val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
        val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        
        // Get push encryption key from SharedPreferences
        val encryptedKey = sharedPrefs.getString("push_enc_key", null)
        if (encryptedKey != null) {
            try {
                // The key is stored as base64-encoded bytes from WebClientPushIntegration
                pushEncryptionKey = android.util.Base64.decode(encryptedKey, android.util.Base64.DEFAULT)
                Log.d(TAG, "Loaded push encryption key (${pushEncryptionKey!!.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading push encryption key", e)
            }
        } else {
            Log.w(TAG, "No push encryption key found in SharedPreferences")
        }
        
        enhancedNotificationDisplay = EnhancedNotificationDisplay(this, homeserverUrl, authToken)
        enhancedNotificationDisplay.createNotificationChannel()
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d(TAG, "FCM message received - data: ${remoteMessage.data}, notification: ${remoteMessage.notification}")
        
        // Handle data payload
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Processing data payload: ${remoteMessage.data}")
            CoroutineScope(Dispatchers.Main).launch {
                handleNotificationData(remoteMessage.data)
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
        
        // If we have data but no notification payload, show a notification anyway
        if (remoteMessage.data.isNotEmpty() && remoteMessage.notification == null) {
            Log.d(TAG, "Data-only payload detected, showing notification")
            CoroutineScope(Dispatchers.Main).launch {
                handleNotificationData(remoteMessage.data)
            }
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
    
    private suspend fun handleNotificationData(data: Map<String, String>) {
        try {
            Log.d(TAG, "handleNotificationData called with data: $data")
            
            // Get the encrypted payload
            val encryptedPayload = data["payload"]
            if (encryptedPayload != null && pushEncryptionKey != null) {
                Log.d(TAG, "Found encrypted payload: ${encryptedPayload.take(50)}...")
                
                try {
                    // Decrypt the payload using the push encryption key
                    val encryption = Encryption.fromPlainKey(pushEncryptionKey!!)
                    val decryptedPayload = encryption.decrypt(encryptedPayload)
                    
                    if (decryptedPayload != null) {
                        Log.d(TAG, "Successfully decrypted payload: ${decryptedPayload.take(100)}...")
                        
                        // Parse the decrypted JSON payload
                        val jsonObject = JSONObject(decryptedPayload)
                        Log.d(TAG, "Parsed decrypted JSON object: $jsonObject")
                        
                        // Convert JSON to Map<String, String> for the parser
                        val jsonDataMap = mutableMapOf<String, String>()
                        jsonObject.keys().forEach { key ->
                            jsonDataMap[key] = jsonObject.getString(key)
                        }
                        
                        Log.d(TAG, "Converted to map: $jsonDataMap")
                        
                        // Parse notification data using our parser
                        val notificationData = NotificationDataParser.parseNotificationData(jsonDataMap)
                        Log.d(TAG, "Parsed notification data: $notificationData")
                        
                        if (notificationData != null) {
                            Log.d(TAG, "Calling showEnhancedNotification")
                            // Use enhanced notification display
                            enhancedNotificationDisplay.showEnhancedNotification(notificationData)
                            Log.d(TAG, "showEnhancedNotification completed")
                        } else {
                            Log.w(TAG, "Failed to parse notification data from decrypted payload")
                        }
                    } else {
                        Log.e(TAG, "Failed to decrypt payload")
                    }
                } catch (decryptException: Exception) {
                    Log.e(TAG, "Error decrypting payload: $encryptedPayload", decryptException)
                }
            } else if (encryptedPayload != null) {
                Log.e(TAG, "Found encrypted payload but no push encryption key available")
            } else {
                Log.d(TAG, "No encrypted payload found, trying direct data parsing")
                // Fallback to direct data parsing (for testing or non-encrypted payloads)
                val notificationData = NotificationDataParser.parseNotificationData(data)
                Log.d(TAG, "Parsed notification data: $notificationData")
                
                if (notificationData != null) {
                    Log.d(TAG, "Calling showEnhancedNotification")
                    enhancedNotificationDisplay.showEnhancedNotification(notificationData)
                    Log.d(TAG, "showEnhancedNotification completed")
                } else {
                    Log.w(TAG, "Failed to parse notification data from: $data")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification data", e)
            e.printStackTrace()
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
