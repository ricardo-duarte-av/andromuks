package net.vrkknn.andromuks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.MediaUtils
import java.io.IOException
import java.net.URL

class EnhancedNotificationDisplay(private val context: Context, private val homeserverUrl: String, private val authToken: String) {
    
    companion object {
        private const val TAG = "EnhancedNotificationDisplay"
        private const val DM_CHANNEL_ID = "matrix_direct_messages"
        private const val GROUP_CHANNEL_ID = "matrix_group_messages"
        private const val DM_CHANNEL_NAME = "Direct Messages"
        private const val GROUP_CHANNEL_NAME = "Group Messages"
        private const val CHANNEL_DESCRIPTION = "Notifications for Matrix messages and events"
        
        // Notification action constants
        private const val ACTION_REPLY = "action_reply"
        private const val ACTION_MARK_READ = "action_mark_read"
        private const val ACTION_VIEW_ROOM = "action_view_room"
        
        // Remote input key
        private const val KEY_REPLY_TEXT = "key_reply_text"
    }
    
    private val conversationsApi = ConversationsApi(context, homeserverUrl, authToken)
    
    /**
     * Create notification channel
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create Direct Messages channel
            val dmChannel = NotificationChannel(
                DM_CHANNEL_ID,
                DM_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Direct message notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            
            // Create Group Messages channel
            val groupChannel = NotificationChannel(
                GROUP_CHANNEL_ID,
                GROUP_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Group message notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannel(dmChannel)
            notificationManager.createNotificationChannel(groupChannel)
        }
    }
    
    /**
     * Show enhanced notification with conversation features
     */
    suspend fun showEnhancedNotification(notificationData: NotificationData) {
        try {
            val notificationId = generateNotificationId(notificationData.roomId)
            
            // Create conversation person (use room avatar for conversation, sender avatar for message)
            val conversationPerson = Person.Builder()
                .setKey(notificationData.roomId)
                .setName(notificationData.roomName ?: notificationData.roomId.substringAfterLast(":"))
                .setIcon(loadPersonIconSync(notificationData.roomAvatarUrl))
                .build()
            
            val messagePerson = createPersonFromNotificationData(notificationData)
            
            // Create messaging style
            val messagingStyle = NotificationCompat.MessagingStyle(conversationPerson)
                .setConversationTitle(notificationData.roomName)
                .addMessage(
                    notificationData.body,
                    notificationData.timestamp ?: System.currentTimeMillis(),
                    messagePerson
                )
            
            // Determine which channel to use based on notification type
            val channelId = when (notificationData.type) {
                "dm" -> DM_CHANNEL_ID
                "group" -> GROUP_CHANNEL_ID
                else -> DM_CHANNEL_ID // Default to DM channel
            }
            
            // Create main notification
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setStyle(messagingStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(createRoomIntent(notificationData))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setGroup(notificationData.roomId) // Group by room
                .setGroupSummary(false)
                .setShortcutId(notificationData.roomId) // Important for Conversations settings
                .apply {
                    // Add reply action
                    addAction(createReplyAction(notificationData))
                    // Add mark as read action
                    addAction(createMarkReadAction(notificationData))
                }
                .build()
            
            // Show notification
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notificationId, notification)
            
            // Update conversation shortcuts
            updateConversationShortcuts(notificationData)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing enhanced notification", e)
        }
    }
    
    /**
     * Create person from notification data
     */
    private fun createPersonFromNotificationData(data: NotificationData): Person {
        return Person.Builder()
            .setKey(data.sender)
            .setName(data.senderDisplayName ?: data.sender.substringAfterLast(":"))
            .setIcon(loadPersonIconSync(data.avatarUrl))
            .build()
    }
    
    /**
     * Create room intent
     */
    private fun createRoomIntent(data: NotificationData): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("room_id", data.roomId)
            putExtra("event_id", data.eventId)
        }
        
        return PendingIntent.getActivity(
            context,
            data.roomId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * Create reply action
     */
    private fun createReplyAction(data: NotificationData): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        
        val replyIntent = Intent("net.vrkknn.andromuks.ACTION_REPLY").apply {
            setPackage(context.packageName)
            putExtra("room_id", data.roomId)
            putExtra("event_id", data.eventId)
        }
        
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            data.roomId.hashCode() + 1,
            replyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        return NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()
    }
    
    /**
     * Create mark as read action
     */
    private fun createMarkReadAction(data: NotificationData): NotificationCompat.Action {
        val markReadIntent = Intent("net.vrkknn.andromuks.ACTION_MARK_READ").apply {
            setPackage(context.packageName)
            putExtra("room_id", data.roomId)
        }
        
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            data.roomId.hashCode() + 2,
            markReadIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        return NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Mark Read",
            markReadPendingIntent
        ).build()
    }
    
    /**
     * Load person icon synchronously (fallback to default)
     */
    private fun loadPersonIconSync(avatarUrl: String?): IconCompat? {
        return if (avatarUrl != null) {
            try {
                // For now, use default icon since we can't call suspend function here
                // In a real implementation, you'd need to load this asynchronously
                IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating person icon", e)
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Load person icon (async version - not used in current implementation)
     */
    private suspend fun loadPersonIcon(avatarUrl: String?): IconCompat? {
        return if (avatarUrl != null) {
            try {
                loadAvatarBitmap(avatarUrl)?.let { bitmap ->
                    IconCompat.createWithBitmap(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading person icon", e)
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Load avatar bitmap from URL (handles both MXC and HTTP URLs)
     */
    private suspend fun loadAvatarBitmap(avatarUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Convert MXC URL or relative Gomuks URL to HTTP URL if needed
            val httpUrl = when {
                avatarUrl.startsWith("mxc://") -> {
                    MediaUtils.mxcToHttpUrl(avatarUrl, homeserverUrl) ?: return@withContext null
                }
                avatarUrl.startsWith("_gomuks/") -> {
                    "$homeserverUrl/$avatarUrl"
                }
                else -> {
                    avatarUrl
                }
            }
            
            Log.d(TAG, "Loading avatar bitmap from: $httpUrl")
            val connection = URL(httpUrl).openConnection()
            connection.setRequestProperty("Cookie", "gomuks_auth=$authToken")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val inputStream = connection.getInputStream()
            android.graphics.BitmapFactory.decodeStream(inputStream)
        } catch (e: IOException) {
            Log.e(TAG, "Error loading avatar bitmap: $avatarUrl", e)
            null
        }
    }
    
    /**
     * Update conversation shortcuts
     */
    private fun updateConversationShortcuts(data: NotificationData) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                conversationsApi.updateShortcutForNewMessage(
                    data.roomId,
                    data.body,
                    data.timestamp ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error updating conversation shortcuts", e)
            }
        }
    }
    
    /**
     * Generate notification ID
     */
    private fun generateNotificationId(roomId: String): Int {
        return roomId.hashCode().let { kotlin.math.abs(it) }
    }
    
    /**
     * Show group summary notification
     */
    fun showGroupSummaryNotification(roomCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val summaryNotification = NotificationCompat.Builder(context, DM_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Matrix Messages")
                .setContentText("$roomCount unread conversations")
                .setGroupSummary(true)
                .setGroup("matrix_messages")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify("matrix_summary".hashCode(), summaryNotification)
        }
    }
    
    /**
     * Clear notification for specific room
     */
    fun clearNotificationForRoom(roomId: String) {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(roomId.hashCode().let { kotlin.math.abs(it) })
    }
    
    /**
     * Clear all notifications
     */
    fun clearAllNotifications() {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancelAll()
    }
}
