package net.vrkknn.andromuks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationCompat.BubbleMetadata
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.MediaCache
import java.io.IOException
import java.net.URL

class EnhancedNotificationDisplay(private val context: Context, private val homeserverUrl: String, private val authToken: String) {
    
    companion object {
        private const val TAG = "EnhancedNotificationDisplay"
    private const val DM_CHANNEL_ID = "matrix_direct_messages"
    private const val GROUP_CHANNEL_ID = "matrix_group_messages"
    private const val CONVERSATION_CHANNEL_ID = "conversation_channel"
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
    
    private val conversationsApi = ConversationsApi(context, homeserverUrl, authToken, "")
    
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
            
            // Single conversation channel for all conversations
            val conversationChannel = NotificationChannel(
                CONVERSATION_CHANNEL_ID,
                "Conversations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Conversation notifications"
                enableVibration(true)
                enableLights(true)
            }
            
            notificationManager.createNotificationChannel(conversationChannel)
        }
    }
    
    /**
     * Creates a conversation channel for a specific room/conversation
     * This is required for per-conversation notification settings
     */
    private fun createConversationChannel(roomId: String, roomName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create a unique channel ID for this conversation
            val conversationChannelId = "${CONVERSATION_CHANNEL_ID}_$roomId"
            
            // Create native Android notification channel
            val channel = NotificationChannel(
                conversationChannelId,
                roomName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for $roomName"
                enableVibration(true)
                enableLights(true)
            }
            
            // Set conversation ID for Android 11+ conversation features
            channel.setConversationId(CONVERSATION_CHANNEL_ID, roomId)
            
            // Create the channel
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    
    /**
     * Remove room shortcut when notifications are dismissed
     */
    fun removeRoomShortcut(roomId: String) {
        conversationsApi?.removeRoomShortcut(roomId)
    }


    
    /**
     * Show enhanced notification with conversation features
     */
    suspend fun showEnhancedNotification(notificationData: NotificationData) {
        try {

            val isGroupRoom = notificationData.roomName != notificationData.senderDisplayName
            // Load avatars asynchronously
            val roomAvatarIcon = notificationData.roomAvatarUrl?.let { 
                loadAvatarAsIcon(it) 
            } ?: IconCompat.createWithResource(context, R.drawable.ic_matrix_notification)
            
            val senderAvatarIcon = notificationData.avatarUrl?.let { 
                loadAvatarAsIcon(it) 
            } ?: IconCompat.createWithResource(context, R.drawable.ic_matrix_notification)
            
            // Load room avatar for large icon
            val roomAvatarBitmap = notificationData.roomAvatarUrl?.let { 
                loadAvatarBitmap(it) 
            }
            val circularRoomAvatar = roomAvatarBitmap?.let { createCircularBitmap(it) }
            
            // Create conversation person (use room avatar for conversation, sender avatar for message)
            val conversationPerson = Person.Builder()
                .setKey(notificationData.roomId)
                .setName(notificationData.roomName ?: notificationData.roomId.substringAfterLast(":"))
                .setIcon(roomAvatarIcon)
                .build()
            
            val messagePerson = Person.Builder()
                .setKey(notificationData.sender)
                .setName(notificationData.senderDisplayName ?: notificationData.sender.substringAfterLast(":"))
                .setIcon(senderAvatarIcon)
                .build()
            
            // Create messaging style - extract existing style if available
            val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifID = notificationData.roomId.hashCode()
            
            val messagingStyle = (systemNotificationManager.activeNotifications.lastOrNull { it.id == notifID }?.let {
                MessagingStyle.extractMessagingStyleFromNotification(it.notification)
            } ?: NotificationCompat.MessagingStyle(conversationPerson))
                .setConversationTitle(
                    if (isGroupRoom) notificationData.roomName else null
                )
                .addMessage(
                    MessagingStyle.Message(
                        notificationData.body,
                        notificationData.timestamp ?: System.currentTimeMillis(),
                        if (isGroupRoom) messagePerson else null
                    )
                )
            
            // Create or update conversation shortcut for this room using ConversationsApi
            conversationsApi?.let { api ->
                CoroutineScope(Dispatchers.IO).launch {
                    // Create a single room list with this room to update shortcuts
                    val roomList = listOf(
                        RoomItem(
                            id = notificationData.roomId,
                            name = notificationData.roomName ?: notificationData.roomId.substringAfterLast(":"),
                            messagePreview = notificationData.body,
                            messageSender = notificationData.senderDisplayName ?: notificationData.sender.substringAfterLast(":"),
                            unreadCount = 1,
                            highlightCount = 0,
                            avatarUrl = notificationData.roomAvatarUrl,
                            sortingTimestamp = System.currentTimeMillis()
                        )
                    )
                    api.updateConversationShortcuts(roomList)
                }
            }
            
            // Create conversation channel for this room
            createConversationChannel(notificationData.roomId, notificationData.roomName ?: notificationData.roomId.substringAfterLast(":"))
            
            // Use conversation channel for all notifications
            val channelId = "${CONVERSATION_CHANNEL_ID}_${notificationData.roomId}"
            
            // Create bubble metadata for chat bubbles (Android 11+)
            val bubbleMetadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BubbleMetadata.Builder(
                    createBubbleIntent(notificationData),
                    roomAvatarIcon
                ).apply {
                    setAutoExpandBubble(true) // Auto-expand the bubble
                    setSuppressNotification(true) // Suppress the notification when bubble is active
                    // Set desired height for the bubble (optional)
                    setDesiredHeight(600) // 600dp height for the bubble
                }.build()
            } else null
            
            // Create main notification
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_matrix_notification)
                .setStyle(messagingStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(createRoomIntent(notificationData))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setGroup(notificationData.roomId) // Group by room
                .setGroupSummary(false)
                .setShortcutId(notificationData.roomId) // Link to the room shortcut for per-room settings
                .setLargeIcon(circularRoomAvatar) // Use circular room avatar as large icon
                .apply {
                    // Add bubble metadata for chat bubbles (Android 11+)
                    if (bubbleMetadata != null) {
                        setBubbleMetadata(bubbleMetadata)
                    }
                    // Add reply action
                    addAction(createReplyAction(notificationData))
                    // Add mark as read action
                    addAction(createMarkReadAction(notificationData))
                }
                .build()
            
            // Show notification
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notifID, notification)
            
            // Update conversation shortcuts
            updateConversationShortcuts(notificationData)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing enhanced notification", e)
        }
    }
    
    
    /**
     * Create room intent with Matrix URI scheme
     */
    private fun createRoomIntent(notificationData: NotificationData): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("matrix:roomid/${notificationData.roomId.substring(1)}${notificationData.eventId?.let { "/e/${it.substring(1)}" } ?: ""}")
            putExtra("room_id", notificationData.roomId)
            putExtra("event_id", notificationData.eventId)
        }
        
        return PendingIntent.getActivity(
            context,
            notificationData.roomId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * Create bubble intent for ChatBubbleScreen
     */
    private fun createBubbleIntent(notificationData: NotificationData): PendingIntent {
        val intent = Intent(context, ChatBubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("matrix://bubble/${notificationData.roomId.substring(1)}")
            putExtra("room_id", notificationData.roomId)
            flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS
        }
        
        return PendingIntent.getActivity(
            context,
            notificationData.roomId.hashCode() + 1000, // Different request code for bubble
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
    }
    
    /**
     * Create reply action
     */
    private fun createReplyAction(data: NotificationData): NotificationCompat.Action {
        Log.d(TAG, "createReplyAction: Creating reply action for room: ${data.roomId}, event: ${data.eventId}")
        
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        
        val replyIntent = Intent("net.vrkknn.andromuks.ACTION_REPLY").apply {
            setPackage(context.packageName)
            putExtra("room_id", data.roomId)
            putExtra("event_id", data.eventId)
        }
        
        Log.d(TAG, "createReplyAction: Intent created with room_id: ${data.roomId}, event_id: ${data.eventId}")
        
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
        
        Log.d(TAG, "createReplyAction: PendingIntent created successfully")
        
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
        Log.d(TAG, "createMarkReadAction: Creating mark read action for room: ${data.roomId}, event: ${data.eventId}")
        
        val markReadIntent = Intent("net.vrkknn.andromuks.ACTION_MARK_READ").apply {
            setPackage(context.packageName)
            putExtra("room_id", data.roomId)
            putExtra("event_id", data.eventId)
        }
        
        Log.d(TAG, "createMarkReadAction: Intent created with room_id: ${data.roomId}, event_id: ${data.eventId}")
        
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
        
        Log.d(TAG, "createMarkReadAction: PendingIntent created successfully")
        
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
                IconCompat.createWithResource(context, R.drawable.ic_matrix_notification)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating person icon", e)
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Load avatar as IconCompat (synchronous version that loads the actual avatar)
     */
    private suspend fun loadAvatarAsIcon(avatarUrl: String): IconCompat? {
        return try {
            val bitmap = loadAvatarBitmap(avatarUrl)
            if (bitmap != null) {
                val circularBitmap = createCircularBitmap(bitmap)
                IconCompat.createWithAdaptiveBitmap(circularBitmap) // Use adaptive bitmap for better transparency
            } else {
                Log.w(TAG, "Failed to load avatar bitmap, using default icon")
                IconCompat.createWithResource(context, R.drawable.ic_matrix_notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar as icon: $avatarUrl", e)
            IconCompat.createWithResource(context, R.drawable.ic_matrix_notification)
        }
    }
    
    /**
     * Convert a bitmap to a circular shape
     */
    private fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        // Convert hardware bitmap to software bitmap if needed
        val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            Log.d(TAG, "Converting hardware bitmap to software bitmap")
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        
        val size = Math.min(softwareBitmap.width, softwareBitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        val radius = size / 2f
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(softwareBitmap, null, rect, paint)
        
        // Clean up the software bitmap if we created a copy
        if (softwareBitmap != bitmap) {
            softwareBitmap.recycle()
        }
        
        return output
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
            Log.d(TAG, "loadAvatarBitmap called with: $avatarUrl")
            
            if (avatarUrl.isEmpty()) {
                Log.w(TAG, "Avatar URL is empty, returning null")
                return@withContext null
            }
            
            // Check if we have a cached version first
            val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
            
            if (cachedFile != null) {
                Log.d(TAG, "Using cached avatar file: ${cachedFile.absolutePath}")
                return@withContext android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
            }
            
            // Convert MXC URL to HTTP URL
            val httpUrl = when {
                avatarUrl.startsWith("mxc://") -> {
                    AvatarUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
                }
                avatarUrl.startsWith("_gomuks/") -> {
                    "$homeserverUrl/$avatarUrl"
                }
                else -> {
                    avatarUrl
                }
            }
            
            if (httpUrl == null) {
                Log.e(TAG, "Failed to convert avatar URL to HTTP URL: $avatarUrl")
                return@withContext null
            }
            
            Log.d(TAG, "Downloading and caching avatar from: $httpUrl")
            
            // Download and cache using existing MediaCache infrastructure
            val downloadedFile = MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
            
            if (downloadedFile != null) {
                Log.d(TAG, "Successfully downloaded and cached avatar: ${downloadedFile.absolutePath}")
                android.graphics.BitmapFactory.decodeFile(downloadedFile.absolutePath)
            } else {
                Log.e(TAG, "Failed to download avatar")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during avatar download: $avatarUrl", e)
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
