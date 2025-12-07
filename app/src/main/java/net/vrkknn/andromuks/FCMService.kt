package net.vrkknn.andromuks

import android.app.ActivityManager
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
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.Encryption
import net.vrkknn.andromuks.BuildConfig

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
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "FCMService: Retrieved encrypted key from SharedPreferences: $encryptedKey")
        
        if (encryptedKey != null) {
            // The key is stored as base64-encoded bytes
            val decodedKey = android.util.Base64.decode(encryptedKey, android.util.Base64.DEFAULT)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "FCMService: Decoded key of size: ${decodedKey.size} bytes")
            if (BuildConfig.DEBUG) Log.d("Andromuks", "FCMService: Decoded key (first 8 bytes): ${decodedKey.take(8).joinToString { "%02x".format(it) }}")
            decodedKey
        } else {
            Log.e("Andromuks", "FCMService: No push encryption key found in SharedPreferences")
            null
        }
    } catch (e: Exception) {
        Log.e("Andromuks", "FCMService: Error getting push encryption key", e)
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
    
    private var enhancedNotificationDisplay: EnhancedNotificationDisplay? = null
    
    override fun onCreate() {
        super.onCreate()
        
        // Get auth token and homeserver URL from SharedPreferences
        val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
        val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        
        if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
            enhancedNotificationDisplay = EnhancedNotificationDisplay(this, homeserverUrl, authToken)
            enhancedNotificationDisplay?.createNotificationChannel()
        }
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        if (BuildConfig.DEBUG) Log.d(TAG, "FCM message received - data: ${remoteMessage.data}, notification: ${remoteMessage.notification}")
        
        // Handle data payload (matches the other Gomuks client approach)
        if (remoteMessage.data.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Processing data payload: ${remoteMessage.data}")
            
            // Get push encryption key (matches the other Gomuks client)
            val pushEncKey = getExistingPushEncryptionKey(this)
            if (pushEncKey == null) {
                Log.e(TAG, "No push encryption key found to handle $remoteMessage")
                return
            }
            
            // Debug: Log key and payload info
            val encryptedPayload = remoteMessage.data["payload"]
            if (encryptedPayload == null) {
                Log.e(TAG, "No 'payload' field in FCM data: ${remoteMessage.data.keys}")
                return
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Using push encryption key of size: ${pushEncKey.size} bytes")
            if (BuildConfig.DEBUG) Log.d(TAG, "Key (first 8 bytes): ${pushEncKey.take(8).joinToString { "%02x".format(it) }}")
            if (BuildConfig.DEBUG) Log.d(TAG, "Encrypted payload length: ${encryptedPayload.length}")
            if (BuildConfig.DEBUG) Log.d(TAG, "Encrypted payload (first 50 chars): ${encryptedPayload.take(50)}")
            if (BuildConfig.DEBUG) Log.d(TAG, "Encrypted payload (last 50 chars): ${encryptedPayload.takeLast(50)}")
            
            // Determine payload type based on length
            val payloadType = when {
                encryptedPayload.length < 200 -> "SHORT_PAYLOAD (likely message notification)"
                encryptedPayload.length > 1000 -> "LONG_PAYLOAD (likely mark_read/sync notification)"
                else -> "MEDIUM_PAYLOAD (unknown type)"
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Detected payload type: $payloadType")
            
            // Check if payload might be JSON with multiple encrypted parts
            if (encryptedPayload.startsWith("{") || encryptedPayload.contains("\"")) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Payload appears to be JSON format, not raw encrypted data")
                if (BuildConfig.DEBUG) Log.d(TAG, "Full payload: $encryptedPayload")
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
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Successfully decrypted payload: ${decryptedPayload.take(100)}...")
            
            // Parse the decrypted JSON and handle different payload types
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val jsonObject = JSONObject(decryptedPayload)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Decrypted JSON keys: ${jsonObject.keys().asSequence().toList()}")
                    
                    // Handle different payload types
                    when {
                        jsonObject.has("messages") -> {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Processing message notification payload")
                            handleMessageNotification(jsonObject)
                        }
                        jsonObject.has("dismiss") -> {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Processing dismiss notification payload")
                            handleDismissNotification(jsonObject)
                        }
                        else -> {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Unknown payload type, trying legacy parsing")
                            // Try legacy parsing for backward compatibility
                            val jsonDataMap = mutableMapOf<String, String>()
                            jsonObject.keys().forEach { key ->
                                jsonDataMap[key] = jsonObject.getString(key)
                            }
                            
                            val notificationData = NotificationDataParser.parseNotificationData(jsonDataMap)
                            if (notificationData != null) {
                                // Check if room is marked as low priority - skip notifications for low priority rooms
                                val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                val lowPriorityRooms = sharedPrefs.getStringSet("low_priority_rooms", emptySet()) ?: emptySet()
                                
                                if (lowPriorityRooms.contains(notificationData.roomId)) {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Skipping notification for low priority room (legacy path): ${notificationData.roomId} (${notificationData.roomName})")
                                } else if (shouldSuppressNotification(notificationData.roomId)) {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Suppressing notification for room (legacy path): ${notificationData.roomId} (${notificationData.roomName}) - room is open and app is in foreground")
                                } else {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        try {
                                            enhancedNotificationDisplay?.showEnhancedNotification(notificationData)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error showing enhanced notification", e)
                                            e.printStackTrace()
                                        }
                                    }
                                }
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Processing notification payload - title: ${notification.title}, body: ${notification.body}")
            showNotification(
                title = notification.title ?: "New message",
                body = notification.body ?: "",
                data = remoteMessage.data
            )
        }
    }
    
    /**
     * Check if the app is actually running in the foreground by checking ActivityManager.
     * This is a more reliable check than SharedPreferences which can have timing issues.
     */
    private fun isAppInForeground(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val runningAppProcesses = activityManager?.runningAppProcesses ?: return false
            
            val packageName = packageName
            for (processInfo in runningAppProcesses) {
                if (processInfo.processName == packageName) {
                    val importance = processInfo.importance
                    // IMPORTANCE_FOREGROUND = 100, IMPORTANCE_VISIBLE = 200
                    val isForeground = importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                    return isForeground
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking app foreground state", e)
            false
        }
    }
    
    /**
     * Check if notifications should be suppressed for a given room.
     * Notifications are suppressed when:
     * 1. The room is currently open AND
     * 2. The app is in the foreground
     * 
     * This ensures notifications are only suppressed when the user can actually see the room.
     * If the app is backgrounded, notifications should play even if the room is "open" in memory.
     * 
     * Uses both SharedPreferences (fast) and ActivityManager (reliable fallback) to check app visibility.
     * 
     * @param roomId The room ID to check
     * @return true if notifications should be suppressed, false otherwise
     */
    private fun shouldSuppressNotification(roomId: String): Boolean {
        if (roomId.isEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "shouldSuppressNotification: roomId is empty, not suppressing")
            return false
        }
        
        val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
        val currentOpenRoomId = sharedPrefs.getString("current_open_room_id", "") ?: ""
        val isAppVisiblePrefs = sharedPrefs.getBoolean("app_is_visible", false)
        
        // Normalize room IDs for comparison (remove "!" prefix if present)
        val normalizedRoomId = roomId.removePrefix("!")
        val normalizedCurrentRoomId = currentOpenRoomId.removePrefix("!")
        
        val roomMatches = normalizedCurrentRoomId == normalizedRoomId && normalizedCurrentRoomId.isNotEmpty()
        
        // Use ActivityManager as fallback if SharedPreferences says false (handles timing issues)
        val isAppVisible = if (isAppVisiblePrefs) {
            true
        } else {
            // Double-check using ActivityManager if SharedPreferences says false
            val isForeground = isAppInForeground()
            if (isForeground) {
                if (BuildConfig.DEBUG) Log.d(TAG, "shouldSuppressNotification: SharedPreferences says app not visible, but ActivityManager says app is in foreground - using ActivityManager result")
            }
            isForeground
        }
        
        val shouldSuppress = roomMatches && isAppVisible
        
        if (BuildConfig.DEBUG) Log.d(TAG, "shouldSuppressNotification: roomId='$roomId' (normalized='$normalizedRoomId'), " +
                "currentOpenRoomId='$currentOpenRoomId' (normalized='$normalizedCurrentRoomId'), " +
                "isAppVisiblePrefs=$isAppVisiblePrefs, isAppVisible=$isAppVisible, roomMatches=$roomMatches, shouldSuppress=$shouldSuppress")
        
        if (shouldSuppress) {
            if (BuildConfig.DEBUG) Log.d(TAG, "✅ Suppressing notification for room $roomId - room is open AND app is in foreground")
        } else {
            val reason = when {
                !roomMatches -> "room doesn't match or no room open"
                !isAppVisible -> "app is not visible (backgrounded)"
                else -> "unknown"
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "❌ NOT suppressing notification for room $roomId - $reason")
        }
        
        return shouldSuppress
    }
    
    /**
     * Handle message notification payload
     */
    private suspend fun handleMessageNotification(jsonObject: JSONObject) {
        try {
            val messagesArray = jsonObject.getJSONArray("messages")
            if (BuildConfig.DEBUG) Log.d(TAG, "Found ${messagesArray.length()} messages in notification")
            
            // Process each message
            for (i in 0 until messagesArray.length()) {
                val message = messagesArray.getJSONObject(i)
                if (BuildConfig.DEBUG) Log.d(TAG, "Processing message: $message")
                
                // Extract message data from the correct JSON structure
                val roomId = message.optString("room_id", "")
                val eventId = message.optString("event_id", "")
                val roomName = message.optString("room_name", roomId)
                val text = message.optString("text", "New message")
                val htmlText = message.optString("html", null)
                val timestamp = message.optLong("timestamp", System.currentTimeMillis())
                val sound = message.optBoolean("sound", true)
                
                // Extract sender information
                val senderObject = message.optJSONObject("sender")
                val sender = senderObject?.optString("id", "") ?: ""
                val senderDisplayName = senderObject?.optString("name", sender) ?: sender
                val senderAvatar = senderObject?.optString("avatar", null)
                
                // Extract room avatar
                val roomAvatar = message.optString("room_avatar", null)
                
                // Extract image field for image notifications
                val image = message.optString("image", null)
                if (BuildConfig.DEBUG) Log.d(TAG, "Extracted image field: $image")
                
                // Convert relative URLs to full URLs
                val avatarUrl = senderAvatar?.let { convertToFullUrl(it) }
                val roomAvatarUrl = roomAvatar?.let { convertToFullUrl(it) }
                val imageUrl = image?.let { convertToFullUrl(it) }
                
                if (BuildConfig.DEBUG) Log.d(TAG, "Avatar URLs - sender: $avatarUrl, room: $roomAvatarUrl, image: $imageUrl")
                
                // Determine if this is a DM or Group room
                val isDirectMessage = roomName == senderDisplayName
                
                val notificationData = NotificationData(
                    roomId = roomId,
                    eventId = eventId,
                    sender = sender,
                    senderDisplayName = senderDisplayName,
                    roomName = roomName,
                    body = text,
                    htmlBody = htmlText,
                    type = if (isDirectMessage) "dm" else "group",
                    avatarUrl = avatarUrl,
                    roomAvatarUrl = roomAvatarUrl,
                    timestamp = timestamp,
                    unreadCount = 1,
                    image = imageUrl
                )
                
                // Check if room is marked as low priority - skip notifications for low priority rooms
                val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                val lowPriorityRooms = sharedPrefs.getStringSet("low_priority_rooms", emptySet()) ?: emptySet()
                
                if (lowPriorityRooms.contains(roomId)) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Skipping notification for low priority room: $roomId ($roomName)")
                    continue
                }
                
                // Check if notification should be suppressed (room is open and app is foreground)
                if (shouldSuppressNotification(roomId)) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Suppressing notification for room: $roomId ($roomName) - room is open and app is in foreground")
                    continue
                }
                
                if (BuildConfig.DEBUG) Log.d(TAG, "Showing notification for room: $roomId, sender: $senderDisplayName, text: $text")
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        enhancedNotificationDisplay?.showEnhancedNotification(notificationData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing enhanced notification", e)
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message notification", e)
        }
    }
    
    /**
     * Convert relative Gomuks media URL to full URL
     */
    private fun convertToFullUrl(relativeUrl: String?): String? {
        if (relativeUrl.isNullOrEmpty()) return null
        
        return when {
            relativeUrl.startsWith("mxc://") -> {
                // Get homeserver URL from SharedPreferences
                val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                if (homeserverUrl.isNotEmpty()) {
                    AvatarUtils.mxcToHttpUrl(relativeUrl, homeserverUrl)
                } else {
                    null
                }
            }
            relativeUrl.startsWith("_gomuks/") -> {
                // Get homeserver URL from SharedPreferences
                val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                if (homeserverUrl.isNotEmpty()) {
                    "$homeserverUrl/$relativeUrl"
                } else {
                    null
                }
            }
            else -> {
                relativeUrl
            }
        }
    }
    
    /**
     * Handle dismiss notification payload
     */
    private fun handleDismissNotification(jsonObject: JSONObject) {
        try {
            val dismissArray = jsonObject.getJSONArray("dismiss")
            if (BuildConfig.DEBUG) Log.d(TAG, "Found ${dismissArray.length()} dismiss requests")
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationManagerCompat = NotificationManagerCompat.from(this)
            
            // Dismiss notifications for each room
            for (i in 0 until dismissArray.length()) {
                val dismissItem = dismissArray.getJSONObject(i)
                val roomId = dismissItem.optString("room_id", "")
                
                if (roomId.isEmpty()) {
                    Log.w(TAG, "Empty room_id in dismiss request, skipping")
                    continue
                }
                
                if (BuildConfig.DEBUG) Log.d(TAG, "Processing dismiss request for room: $roomId")
                
                val notifID = roomId.hashCode()
                val existingNotification = notificationManager.activeNotifications.firstOrNull { it.id == notifID }
                
                if (existingNotification == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "No notification found for room: $roomId - nothing to dismiss")
                    continue
                }
                
                // CRITICAL: Check if bubble is open using multiple methods
                // 1. Check BubbleTracker (primary source of truth)
                val isBubbleOpenTracked = BubbleTracker.isBubbleOpen(roomId)
                
                // 2. Fallback: Check if notification has bubble metadata (indicates bubble could be open)
                // If a notification has bubble metadata, it means a bubble was created and could still be open
                // This is a critical fallback when BubbleTracker hasn't tracked it yet
                val notificationHasBubbleMetadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        existingNotification.notification.bubbleMetadata != null
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Error checking bubble metadata", e)
                        false
                    }
                } else {
                    false
                }
                
                // If notification has bubble metadata, assume bubble might be open (safer to not dismiss)
                // This prevents destroying bubbles that are open but not yet tracked
                val isBubbleOpen = isBubbleOpenTracked || notificationHasBubbleMetadata
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Room $roomId - Bubble state check:")
                    Log.d(TAG, "  - BubbleTracker: $isBubbleOpenTracked")
                    Log.d(TAG, "  - Has bubble metadata: $notificationHasBubbleMetadata")
                    Log.d(TAG, "  - Final result: isBubbleOpen=$isBubbleOpen")
                    if (notificationHasBubbleMetadata && !isBubbleOpenTracked) {
                        Log.w(TAG, "  - WARNING: Notification has bubble metadata but BubbleTracker says closed - preserving bubble anyway")
                    }
                }
                
                if (isBubbleOpen) {
                    // Bubble is open - don't dismiss to preserve bubble
                    if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId - NOT dismissing notification (bubble is open)")
                    if (BuildConfig.DEBUG) Log.d(TAG, "This prevents the bubble from disappearing when conversation is marked as read")
                } else {
                    // Safe to dismiss - no active bubble
                    if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId - Dismissing notification (no active bubble)")
                    notificationManagerCompat.cancel(notifID)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Successfully dismissed notification for room: $roomId")
                }
                
                // NOTE: Do NOT remove room shortcut when dismissing notifications
                // The shortcut should remain so the chat bubble stays open
                // Only the notification should be dismissed, not the conversation shortcut
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling dismiss notification", e)
        }
    }
    
    // Note: onStartCommand is final in FirebaseMessagingService, so we handle actions differently
    // The reply and mark read actions will be handled via the notification actions directly
    
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
        
        // Check if room is marked as low priority - skip notifications for low priority rooms
        if (roomId != null) {
            val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
            val lowPriorityRooms = sharedPrefs.getStringSet("low_priority_rooms", emptySet()) ?: emptySet()
            
            if (lowPriorityRooms.contains(roomId)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipping background notification for low priority room: $roomId")
                return
            }
            
            // Check if notification should be suppressed (room is open and app is foreground)
            if (shouldSuppressNotification(roomId)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Suppressing background notification for room: $roomId - room is open and app is in foreground")
                return
            }
        }
        
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
        
        // Determine if this is likely a group room based on available data
        // If room_name != sender_display_name, it's a group room
        // If room_name == sender_display_name or room_name is null, it's a DM
        val roomName = data["room_name"]
        val senderDisplayName = data["sender_display_name"] ?: data["sender"]
        val isLikelyGroupRoom = when {
            roomName != null && senderDisplayName != null -> roomName != senderDisplayName
            roomName != null -> true // If we have room name but no sender name, assume group
            else -> title.contains(":") || title.length > 20 // Fallback to heuristic
        }
        
        // Choose sound based on room type
        val soundResource = if (isLikelyGroupRoom) R.raw.descending else R.raw.bright
        
        // Create notification with reply action
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Required for Android Auto
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(android.net.Uri.parse("android.resource://" + packageName + "/" + soundResource))
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
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "FCMService: New FCM token: $token")
    }
    
}
