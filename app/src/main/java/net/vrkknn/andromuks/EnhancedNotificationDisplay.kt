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
import net.vrkknn.andromuks.utils.MediaUtils
import net.vrkknn.andromuks.utils.htmlToNotificationText
import androidx.core.content.FileProvider
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
                setSound(android.net.Uri.parse("android.resource://" + context.packageName + "/" + R.raw.bright), null)
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
                setSound(android.net.Uri.parse("android.resource://" + context.packageName + "/" + R.raw.descending), null)
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
                setSound(android.net.Uri.parse("android.resource://" + context.packageName + "/" + R.raw.bright), null)
            }
            
            notificationManager.createNotificationChannel(conversationChannel)
        }
    }
    
    /**
     * Creates a conversation channel for a specific room/conversation
     * This is required for per-conversation notification settings
     */
    private fun createConversationChannel(roomId: String, roomName: String, isGroupRoom: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create a unique channel ID for this conversation
            val conversationChannelId = "${CONVERSATION_CHANNEL_ID}_$roomId"
            
            // Choose sound based on room type
            val soundResource = if (isGroupRoom) R.raw.descending else R.raw.bright
            
            // Create native Android notification channel
            val channel = NotificationChannel(
                conversationChannelId,
                roomName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for $roomName"
                enableVibration(true)
                enableLights(true)
                setSound(android.net.Uri.parse("android.resource://" + context.packageName + "/" + soundResource), null)
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
            val hasImage = !notificationData.image.isNullOrEmpty()
            Log.d(TAG, "showEnhancedNotification - hasImage: $hasImage, image: ${notificationData.image}")
            // Load avatars asynchronously with fallbacks
            val roomAvatarIcon = notificationData.roomAvatarUrl?.let { 
                loadAvatarAsIcon(it) 
            } ?: run {
                // Create fallback avatar for room (use room name + room ID)
                Log.d(TAG, "No room avatar URL, creating fallback for: ${notificationData.roomName}")
                createFallbackAvatarIcon(notificationData.roomName, notificationData.roomId)
            }
            
            val senderAvatarIcon = notificationData.avatarUrl?.let { 
                loadAvatarAsIcon(it)
            } ?: run {
                // Create fallback avatar for sender
                Log.d(TAG, "No sender avatar URL, creating fallback for: ${notificationData.senderDisplayName}")
                createFallbackAvatarIcon(notificationData.senderDisplayName, notificationData.sender)
            }
            
            // Load room avatar bitmap for large icon
            val roomAvatarBitmap = notificationData.roomAvatarUrl?.let { 
                loadAvatarBitmap(it) 
            } ?: createFallbackAvatarBitmap(notificationData.roomName, notificationData.roomId, 128)
            val circularRoomAvatar = createCircularBitmap(roomAvatarBitmap)
            
            // Load sender avatar bitmap
            val senderAvatarBitmap = notificationData.avatarUrl?.let { 
                loadAvatarBitmap(it)
            } ?: createFallbackAvatarBitmap(notificationData.senderDisplayName, notificationData.sender, 128)
            val circularSenderAvatar = createCircularBitmap(senderAvatarBitmap)
            
            // Get current user info for MessagingStyle (the local user, not the room)
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val currentUserId = sharedPrefs.getString("current_user_id", "self") ?: "self"
            val currentUserDisplayName = sharedPrefs.getString("current_user_display_name", "Me") ?: "Me"
            
            // Load current user's avatar for "me" person
            val currentUserAvatarIcon = try {
                val avatarUrl = sharedPrefs.getString("current_user_avatar_url", null)
                if (!avatarUrl.isNullOrEmpty()) {
                    val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
                    val avatarBitmap = if (cachedFile != null) {
                        Log.d(TAG, "Using cached avatar for current user: $avatarUrl")
                        android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                    } else {
                        // Try to download avatar for current user
                        val httpUrl = MediaUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
                        if (httpUrl != null) {
                            val downloadedFile = MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
                            if (downloadedFile != null) {
                                Log.d(TAG, "Downloaded current user avatar to cache: ${downloadedFile.absolutePath}")
                                android.graphics.BitmapFactory.decodeFile(downloadedFile.absolutePath)
                            } else {
                                Log.d(TAG, "Failed to download current user avatar: $avatarUrl")
                                null
                            }
                        } else {
                            Log.d(TAG, "Failed to convert current user avatar MXC URL: $avatarUrl")
                            null
                        }
                    }
                    
                    // Apply circular transformation to match other avatars
                    avatarBitmap?.let { 
                        IconCompat.createWithBitmap(createCircularBitmap(it))
                    }
                } else {
                    Log.d(TAG, "No avatar URL stored for current user")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading current user avatar", e)
                null
            }
            
            // Create "me" person for MessagingStyle root (the local user) WITH avatar
            val me = Person.Builder()
                .setName(currentUserDisplayName)
                .setKey(currentUserId)
                .apply {
                    if (currentUserAvatarIcon != null) {
                        setIcon(currentUserAvatarIcon)
                    }
                }
                .build()
            
            // Create message person WITH sender avatar icon for individual messages
            // Always create messagePerson, even for DMs
            val messagePerson = Person.Builder()
                .setKey(notificationData.sender)
                .setName(notificationData.senderDisplayName ?: notificationData.sender.substringAfterLast(":"))
                .setIcon(senderAvatarIcon)
                .build()
            
            // Download image for image notifications
            val imageUri = if (hasImage && notificationData.image != null) {
                try {
                    Log.d(TAG, "Downloading image for notification: ${notificationData.image}")
                    
                    // Parse the image URL and convert to MXC format for caching
                    val (mxcUrl, httpUrl) = when {
                        notificationData.image.startsWith("mxc://") -> {
                            // Already an MXC URL
                            val mxc = notificationData.image
                            val http = MediaUtils.mxcToHttpUrl(mxc, homeserverUrl)
                            Pair(mxc, http)
                        }
                        notificationData.image.startsWith("_gomuks/media/") -> {
                            // Relative _gomuks URL - convert to MXC and HTTP
                            val parts = notificationData.image.removePrefix("_gomuks/media/").split("?")[0].split("/", limit = 2)
                            val mxc = if (parts.size == 2) "mxc://${parts[0]}/${parts[1]}" else null
                            val http = if (mxc != null) "$homeserverUrl/${notificationData.image}" else null // Keep query params like ?encrypted=true
                            Pair(mxc, http)
                        }
                        notificationData.image.contains("/_gomuks/media/") -> {
                            // Full HTTP URL containing _gomuks/media/ - extract the relative part
                            val gomuksIndex = notificationData.image.indexOf("/_gomuks/media/")
                            val relativePart = notificationData.image.substring(gomuksIndex + 1) // Remove leading /
                            val parts = relativePart.removePrefix("_gomuks/media/").split("?")[0].split("/", limit = 2)
                            val mxc = if (parts.size == 2) "mxc://${parts[0]}/${parts[1]}" else null
                            val http = notificationData.image // Keep full URL including query params like ?encrypted=true
                            Pair(mxc, http)
                        }
                        else -> {
                            Log.w(TAG, "Unrecognized image URL format: ${notificationData.image}")
                            Pair(null, null)
                        }
                    }
                    
                    Log.d(TAG, "Parsed image URLs - MXC: $mxcUrl, HTTP: $httpUrl")
                    
                    if (mxcUrl != null && httpUrl != null) {
                        // Check cache first
                        val cachedFile = MediaCache.getCachedFile(context, mxcUrl)
                        if (cachedFile != null) {
                            Log.d(TAG, "Using cached image: ${cachedFile.absolutePath}")
                            // Use FileProvider to create a content:// URI that can be accessed by the notification system
                            FileProvider.getUriForFile(
                                context,
                                "pt.aguiarvieira.andromuks.fileprovider",
                                cachedFile
                            )
                        } else {
                            // Download and cache
                            Log.d(TAG, "Downloading image from: $httpUrl")
                            val downloadedFile = MediaCache.downloadAndCache(context, mxcUrl, httpUrl, authToken)
                            if (downloadedFile != null) {
                                Log.d(TAG, "Downloaded image to cache: ${downloadedFile.absolutePath}")
                                // Use FileProvider to create a content:// URI that can be accessed by the notification system
                                FileProvider.getUriForFile(
                                    context,
                                    "pt.aguiarvieira.andromuks.fileprovider",
                                    downloadedFile
                                )
                            } else {
                                Log.w(TAG, "Failed to download image for notification")
                                null
                            }
                        }
                    } else {
                        Log.w(TAG, "Could not parse image URL: ${notificationData.image}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading image for notification", e)
                    null
                }
            } else {
                null
            }
            
            // Create messaging style - extract existing style if available
            val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifID = notificationData.roomId.hashCode()
            
            // Process message body - use HTML if available, otherwise fall back to plain text
            val messageBody = if (!notificationData.htmlBody.isNullOrEmpty()) {
                try {
                    htmlToNotificationText(notificationData.htmlBody)
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting HTML to notification text, falling back to plain text", e)
                    notificationData.body
                }
            } else {
                notificationData.body
            }
            
            // Extract existing MessagingStyle if available (with proper error handling)
            val existingStyle = try {
                systemNotificationManager.activeNotifications
                    ?.lastOrNull { it.id == notifID }
                    ?.let { MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract messaging style", e)
                null
            }
            
            // Create or update MessagingStyle
            val messagingStyle = (existingStyle ?: NotificationCompat.MessagingStyle(me))
                .setConversationTitle(
                    if (isGroupRoom) notificationData.roomName else null
                )
                .setGroupConversation(isGroupRoom) // Helps Android decide when to show sender avatar vs your own
            
            // Add message to style
            val message = if (hasImage && imageUri != null) {
                // Image message with downloaded image
                Log.d(TAG, "Adding image message to notification with URI: $imageUri")
                MessagingStyle.Message(
                    "[Image]",
                    notificationData.timestamp ?: System.currentTimeMillis(),
                    messagePerson // Always use messagePerson, even for DMs
                ).setData("image/*", imageUri)
            } else {
                // Text message
                MessagingStyle.Message(
                    messageBody,
                    notificationData.timestamp ?: System.currentTimeMillis(),
                    messagePerson // Always use messagePerson, even for DMs
                )
            }
            
            messagingStyle.addMessage(message)
            
            // Create or update conversation shortcut SYNCHRONOUSLY before showing notification
            // This ensures the shortcut exists with proper icon before bubble is created
            val shortcutInfo = conversationsApi?.let { api ->
                try {
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
                    
                    // Update shortcuts asynchronously (non-blocking)
                    Log.d(TAG, "━━━ TIMING: Triggering async shortcut update NOW ━━━")
                    api.updateConversationShortcuts(roomList)
                    
                    Log.d(TAG, "Shortcuts update triggered (async) for: ${notificationData.roomId}")
                    
                    // Try to get existing shortcut (may not exist yet)
                    val existingShortcut = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
                        .firstOrNull { it.id == notificationData.roomId }
                    
                    if (existingShortcut != null) {
                        Log.d(TAG, "Found existing shortcut: ${existingShortcut.shortLabel}")
                        existingShortcut
                    } else {
                        Log.d(TAG, "No existing shortcut yet, will be created asynchronously")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating/getting shortcut info", e)
                    null
                }
            }
            
            // Create conversation channel for this room
            createConversationChannel(notificationData.roomId, notificationData.roomName ?: notificationData.roomId.substringAfterLast(":"), isGroupRoom)
            
            // Use conversation channel for all notifications
            val channelId = "${CONVERSATION_CHANNEL_ID}_${notificationData.roomId}"
            
            // Create bubble metadata for chat bubbles (Android 11+)
            val bubbleMetadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bubbleIntent = createBubbleIntent(notificationData)
                
                // Grant persistent URI permission for the bubble icon if it's a content URI
                // (Skip for bitmap-based icons which is what we use now)
                if (roomAvatarIcon is IconCompat) {
                    try {
                        // Check if this is a URI-based icon (not bitmap)
                        // IconCompat.getUri() throws if icon type is BITMAP/BITMAP_MASKABLE
                        val iconType = roomAvatarIcon.type
                        if (iconType == androidx.core.graphics.drawable.IconCompat.TYPE_URI ||
                            iconType == androidx.core.graphics.drawable.IconCompat.TYPE_URI_ADAPTIVE_BITMAP) {
                            val uri = roomAvatarIcon.uri
                            if (uri != null && uri.scheme == "content") {
                                Log.d(TAG, "Granting persistent URI read permission for bubble icon: $uri")
                                // Grant persistent permission so URI stays valid
                                context.grantUriPermission(
                                    context.packageName,
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                )
                                // Take persistable permission
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                                Log.d(TAG, "Persistent URI permission granted and taken")
                            }
                        } else {
                            Log.d(TAG, "Bubble icon is bitmap-based (not ContentUri), no URI permissions needed")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not grant persistent URI permission for bubble icon", e)
                    }
                }
                
                BubbleMetadata.Builder(
                    bubbleIntent,
                    roomAvatarIcon
                ).apply {
                    setAutoExpandBubble(true) // Auto-expand the bubble
                    setSuppressNotification(false) // Don't suppress the notification when bubble is active
                    // Set desired height for the bubble (optional)
                    setDesiredHeight(600) // 600dp height for the bubble
                }.build()
            } else null
            
            // Determine large icon based on room type
            // For DMs: use sender's avatar (the conversation-level avatar)
            // For groups: use room avatar (the conversation-level avatar)
            val largeIconBitmap = if (isGroupRoom) {
                circularRoomAvatar ?: circularSenderAvatar // Prefer room avatar for groups
            } else {
                circularSenderAvatar ?: circularRoomAvatar // Prefer sender avatar for DMs
            }
            
            // Create main notification
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.matrix) // Minimized icon in status bar
                .setStyle(messagingStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(createRoomIntent(notificationData))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setGroup(notificationData.roomId) // Group by room
                .setGroupSummary(false)
                .apply {
                    // Set large icon (always set if available)
                    if (largeIconBitmap != null) {
                        setLargeIcon(largeIconBitmap)
                    }
                    
                    // Link to shortcut - prefer full shortcut info over just ID
                    if (shortcutInfo != null) {
                        setShortcutInfo(shortcutInfo)
                    } else {
                        setShortcutId(notificationData.roomId)
                    }
                    
                    // Store event_id in extras for later retrieval
                    if (notificationData.eventId != null) {
                        addExtras(android.os.Bundle().apply {
                            putString("event_id", notificationData.eventId)
                        })
                    }
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
            
            // Grant URI permission for image if present
            if (imageUri != null) {
                try {
                    Log.d(TAG, "Granting URI permission for notification image: $imageUri")
                    context.grantUriPermission(
                        "com.android.systemui",  // System UI package for notifications
                        imageUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not grant URI permission for notification image", e)
                }
            }
            
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
                    Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION // Grant permission to read content URIs
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
        
        // Use broadcast receiver to avoid trampoline and UI visibility
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = "net.vrkknn.andromuks.ACTION_REPLY"
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
        
        // Use broadcast receiver to avoid trampoline and UI visibility
        val markReadIntent = Intent(context, NotificationMarkReadReceiver::class.java).apply {
            action = "net.vrkknn.andromuks.ACTION_MARK_READ"
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
     * Load avatar as IconCompat using content:// URI for better bubble support
     * Falls back to bitmap-based icon if ContentUri creation fails
     */
    private suspend fun loadAvatarAsIcon(avatarUrl: String): IconCompat? {
        return try {
            Log.d(TAG, "━━━ loadAvatarAsIcon called ━━━")
            Log.d(TAG, "  Avatar URL: $avatarUrl")
            
            // Load bitmap (from cache or download)
            val bitmap = loadAvatarBitmap(avatarUrl)
            Log.d(TAG, "  Bitmap loaded: ${bitmap != null}")
            
            if (bitmap != null) {
                // Make it circular and use directly as adaptive bitmap
                val circularBitmap = createCircularBitmap(bitmap)
                Log.d(TAG, "  ✓✓✓ SUCCESS: Created circular bitmap icon for notification Person")
                IconCompat.createWithAdaptiveBitmap(circularBitmap)
            } else {
                Log.w(TAG, "  ✗✗✗ FAILED: loadAvatarBitmap returned null, using default icon")
                createDefaultAdaptiveIcon()
            }
        } catch (e: Exception) {
            Log.e(TAG, "  ✗✗✗ EXCEPTION: Error loading avatar as icon: $avatarUrl", e)
            createDefaultAdaptiveIcon()
        }
    }
    
    /**
     * Create fallback avatar icon with initials (like AvatarUtils but as Bitmap)
     */
    private fun createFallbackAvatarIcon(displayName: String?, userId: String): IconCompat {
        return try {
            val fallbackBitmap = createFallbackAvatarBitmap(displayName, userId, 128)
            val circularBitmap = createCircularBitmap(fallbackBitmap)
            IconCompat.createWithAdaptiveBitmap(circularBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fallback avatar icon", e)
            createDefaultAdaptiveIcon()
        }
    }
    
    /**
     * Create a bitmap-based fallback avatar with user initial
     * Uses same color/character logic as AvatarUtils for consistency
     */
    private fun createFallbackAvatarBitmap(displayName: String?, userId: String, size: Int): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Get color and character using AvatarUtils
        val colorHex = AvatarUtils.getUserColor(userId)
        val character = AvatarUtils.getFallbackCharacter(displayName, userId)
        
        // Parse hex color
        val color = try {
            android.graphics.Color.parseColor("#$colorHex")
        } catch (e: Exception) {
            android.graphics.Color.parseColor("#d991de") // Fallback color
        }
        
        // Draw background
        val bgPaint = android.graphics.Paint().apply {
            this.color = color
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)
        
        // Draw text (character/initial)
        if (character.isNotEmpty()) {
            val textPaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.WHITE
                textSize = size * 0.5f // 50% of size
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            
            // Center text vertically (accounting for font metrics)
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(character, 0, character.length, textBounds)
            val y = size / 2f + textBounds.height() / 2f
            
            canvas.drawText(character, size / 2f, y, textPaint)
        }
        
        return bitmap
    }
    
    private fun createDefaultAdaptiveIcon(): IconCompat {
        return try {
            // Create a simple default icon using a bitmap instead of resource
            val defaultBitmap = createDefaultMatrixIcon()
            IconCompat.createWithAdaptiveBitmap(defaultBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating default adaptive icon, falling back to resource", e)
            // Last resort fallback to resource icon
            IconCompat.createWithResource(context, R.drawable.ic_matrix_notification)
        }
    }
    
    private fun createDefaultMatrixIcon(): android.graphics.Bitmap {
        val size = 64 // 64dp size for the icon
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Create a simple matrix-style icon
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#00D4AA") // Matrix green
            isAntiAlias = true
        }
        
        // Draw a simple "M" shape
        val path = android.graphics.Path()
        val margin = size * 0.1f
        val width = size - 2 * margin
        val height = size - 2 * margin
        
        path.moveTo(margin, margin + height)
        path.lineTo(margin + width * 0.3f, margin)
        path.lineTo(margin + width * 0.5f, margin + height * 0.4f)
        path.lineTo(margin + width * 0.7f, margin)
        path.lineTo(margin + width, margin + height)
        path.lineTo(margin + width * 0.8f, margin + height)
        path.lineTo(margin + width * 0.5f, margin + height * 0.6f)
        path.lineTo(margin + width * 0.2f, margin + height)
        path.close()
        
        canvas.drawPath(path, paint)
        
        return bitmap
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
     * Updates notification with a sent reply message
     * This adds the message to the MessagingStyle and re-issues the notification
     */
    fun updateNotificationWithReply(roomId: String, replyText: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifID = roomId.hashCode()
            
            // Find the existing notification
            val existingNotification = notificationManager.activeNotifications.firstOrNull { it.id == notifID }
            if (existingNotification == null) {
                Log.w(TAG, "No existing notification found for room: $roomId")
                return
            }
            
            // Extract the existing MessagingStyle
            val existingStyle = MessagingStyle.extractMessagingStyleFromNotification(existingNotification.notification)
            if (existingStyle == null) {
                Log.w(TAG, "Could not extract MessagingStyle from notification for room: $roomId")
                return
            }
            
            // Extract the event_id from the notification extras
            val eventId = existingNotification.notification.extras?.getString("event_id")
            Log.d(TAG, "Extracted event_id from notification: $eventId")
            
            // Get current user info to create the same "me" Person as the original notification
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val currentUserId = sharedPrefs.getString("current_user_id", "self") ?: "self"
            val currentUserDisplayName = sharedPrefs.getString("current_user_display_name", "Me") ?: "Me"
            
            // Try to use cached avatar for the reply (don't download to avoid delays)
            val currentUserAvatarIcon = try {
                val avatarUrl = sharedPrefs.getString("current_user_avatar_url", null)
                if (!avatarUrl.isNullOrEmpty()) {
                    val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
                    val avatarBitmap = if (cachedFile != null) {
                        Log.d(TAG, "Using cached avatar for reply: $avatarUrl")
                        android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                    } else {
                        Log.d(TAG, "Avatar not cached, reply will show without avatar: $avatarUrl")
                        null
                    }
                    
                    // Apply circular transformation to match other avatars
                    avatarBitmap?.let { 
                        IconCompat.createWithBitmap(createCircularBitmap(it))
                    }
                } else {
                    Log.d(TAG, "No avatar URL stored for current user")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading current user avatar for reply", e)
                null
            }
            
            // Create the "me" Person with the same key and avatar as the original
            val mePerson = Person.Builder()
                .setName(currentUserDisplayName)
                .setKey(currentUserId)
                .apply {
                    if (currentUserAvatarIcon != null) {
                        setIcon(currentUserAvatarIcon)
                    }
                }
                .build()
            
            // Add the reply message to the style
            existingStyle.addMessage(
                MessagingStyle.Message(
                    replyText,
                    System.currentTimeMillis(),
                    mePerson
                )
            )
            
            // Get the channel ID from the notification
            val channelId = "${CONVERSATION_CHANNEL_ID}_${roomId}"
            
            // Get shortcut info for the notification
            val shortcutId = roomId
            
            // Rebuild the notification with the updated style
            val updatedNotification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.matrix)
                .setStyle(existingStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(existingNotification.notification.contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setGroup(roomId)
                .setGroupSummary(false)
                .setShortcutId(shortcutId)
                .setLargeIcon(existingNotification.notification.getLargeIcon()?.let { 
                    // Convert Icon to Bitmap if possible
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            it.loadDrawable(context)?.let { drawable ->
                                val bitmap = android.graphics.Bitmap.createBitmap(
                                    drawable.intrinsicWidth,
                                    drawable.intrinsicHeight,
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val canvas = android.graphics.Canvas(bitmap)
                                drawable.setBounds(0, 0, canvas.width, canvas.height)
                                drawable.draw(canvas)
                                bitmap
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading icon as bitmap", e)
                            null
                        }
                    } else {
                        null
                    }
                })
                .apply {
                    // Preserve event_id in extras
                    if (eventId != null) {
                        addExtras(android.os.Bundle().apply {
                            putString("event_id", eventId)
                        })
                    }
                    
                    // Note: We don't copy bubble metadata because it's tied to the specific Intent
                    // and recreating it would require the original PendingIntent which we don't have.
                    // The bubble will work from the original notification creation.
                    
                    // Re-create reply and mark read actions for the notification
                    // We need to recreate them because we can't directly copy Notification.Action to NotificationCompat.Action
                    val notificationData = NotificationData(
                        roomId = roomId,
                        eventId = eventId,  // Use the extracted event_id
                        sender = "",
                        senderDisplayName = "",
                        roomName = "",
                        body = "",
                        type = "dm",
                        avatarUrl = null,
                        roomAvatarUrl = null,
                        timestamp = System.currentTimeMillis(),
                        unreadCount = 0,
                        image = null
                    )
                    addAction(createReplyAction(notificationData))
                    addAction(createMarkReadAction(notificationData))
                }
                .build()
            
            // Re-issue the notification with the updated content
            val notificationManagerCompat = NotificationManagerCompat.from(context)
            notificationManagerCompat.notify(notifID, updatedNotification)
            
            Log.d(TAG, "Updated notification with reply for room: $roomId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification with reply", e)
        }
    }
    
    /**
     * Updates notification to mark it as read
     * This updates the MessagingStyle and ShortcutInfo to clear unread state
     */
    fun updateNotificationAsRead(roomId: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifID = roomId.hashCode()
            
            // Find the existing notification
            val existingNotification = notificationManager.activeNotifications.firstOrNull { it.id == notifID }
            if (existingNotification == null) {
                Log.w(TAG, "No existing notification found for room: $roomId")
                return
            }
            
            // Extract the existing MessagingStyle
            var existingStyle = MessagingStyle.extractMessagingStyleFromNotification(existingNotification.notification)
            if (existingStyle == null) {
                Log.w(TAG, "Could not extract MessagingStyle from notification for room: $roomId")
                return
            }
            
            // Extract the event_id from the notification extras
            val eventId = existingNotification.notification.extras?.getString("event_id")
            Log.d(TAG, "Extracted event_id from notification for mark read: $eventId")
            
            // Note: Unread count clearing is handled automatically by our shortcut updates
            // No need to call clearUnreadCount() which can lose icons
            
            // Dismiss the notification now that it's been marked as read
            val notificationManagerCompat = NotificationManagerCompat.from(context)
            notificationManagerCompat.cancel(notifID)
            
            Log.d(TAG, "Dismissed notification and cleared unread count for room: $roomId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification as read", e)
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
