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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
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
            
            // Load avatars asynchronously
            val roomAvatarIcon = notificationData.roomAvatarUrl?.let { 
                loadAvatarAsIcon(it) 
            } ?: IconCompat.createWithResource(context, R.drawable.ic_notification)
            
            val senderAvatarIcon = notificationData.avatarUrl?.let { 
                loadAvatarAsIcon(it) 
            } ?: IconCompat.createWithResource(context, R.drawable.ic_notification)
            
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
                .setSmallIcon(R.drawable.ic_notification)
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
                .setLargeIcon(circularRoomAvatar) // Use circular room avatar as large icon
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
     * Create room intent with Matrix URI scheme
     */
    private fun createRoomIntent(data: NotificationData): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("matrix:roomid/${data.roomId.substring(1)}${data.eventId?.let { "/e/${it.substring(1)}" } ?: ""}")
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
                IconCompat.createWithResource(context, R.drawable.ic_notification)
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
                IconCompat.createWithAdaptiveBitmap(circularBitmap)
            } else {
                Log.w(TAG, "Failed to load avatar bitmap, using default icon")
                IconCompat.createWithResource(context, R.drawable.ic_notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar as icon: $avatarUrl", e)
            IconCompat.createWithResource(context, R.drawable.ic_notification)
        }
    }
    
    /**
     * Convert a bitmap to a circular shape
     */
    private fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val circularBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(circularBitmap)
        
        val paint = Paint().apply {
            isAntiAlias = true
        }
        
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        
        // Draw circle
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        // Set paint to use source bitmap
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        
        // Scale and draw the bitmap to fit the circle
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)
        
        // Recycle the scaled bitmap to free memory
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        
        return circularBitmap
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
            // Convert URL to proper format and get HTTP URL
            val httpUrl = when {
                avatarUrl.startsWith("mxc://") -> {
                    AvatarUtils.mxcToHttpUrl(avatarUrl, homeserverUrl) ?: return@withContext null
                }
                avatarUrl.startsWith("_gomuks/") -> {
                    "$homeserverUrl/$avatarUrl"
                }
                else -> {
                    avatarUrl
                }
            }
            
            Log.d(TAG, "Loading avatar bitmap from: $httpUrl")
            
            // Try to get from cache first
            val cachedFile = if (avatarUrl.startsWith("mxc://")) {
                MediaCache.getCachedFile(context, avatarUrl)
            } else null
            
            if (cachedFile != null && cachedFile.exists()) {
                Log.d(TAG, "Using cached avatar file: ${cachedFile.absolutePath}")
                return@withContext android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
            }
            
            // Download and cache if not cached
            val connection = URL(httpUrl).openConnection()
            connection.setRequestProperty("Cookie", "gomuks_auth=$authToken")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val inputStream = connection.getInputStream()
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            
            // Cache the downloaded avatar if it's an MXC URL
            if (bitmap != null && avatarUrl.startsWith("mxc://")) {
                MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
            }
            
            bitmap
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
