package net.vrkknn.andromuks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.LocusIdCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
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
import net.vrkknn.andromuks.BuildConfig

import androidx.core.content.FileProvider
import java.io.IOException
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
        private val autoExpandedBubbleRooms =
            Collections.synchronizedSet(mutableSetOf<String>())
    }
    
    private val conversationsApi = ConversationsApi(context, homeserverUrl, authToken, "")
    
    // Per-room locks to prevent concurrent notification updates for the same room
    private val roomNotificationLocks = ConcurrentHashMap<String, Any>()
    
    // In-memory cache for avatar icons to avoid reloading on every notification update
    // Key: avatar URL (MXC or HTTP), Value: IconCompat
    // Using LRU cache with max size of 100 to limit memory usage
    private val avatarIconCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, IconCompat>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IconCompat>?): Boolean {
                return size > 100
            }
        }
    )
    
    /**
     * Get or create a lock object for a specific room
     */
    private fun getRoomLock(roomId: String): Any {
        return roomNotificationLocks.computeIfAbsent(roomId) { Any() }
    }
    
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowBubbles(true)
                }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowBubbles(true)
                }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowBubbles(true)
                }
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
            
            // If a channel already exists but is lower than HIGH, recreate it to bump importance.
            notificationManager.getNotificationChannel(conversationChannelId)?.let { existing ->
                if (existing.importance < NotificationManager.IMPORTANCE_HIGH) {
                    notificationManager.deleteNotificationChannel(conversationChannelId)
                }
            }

            // Create native Android notification channel
            // Use IMPORTANCE_HIGH for Android Auto compatibility
            val channel = NotificationChannel(
                conversationChannelId,
                roomName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for $roomName"
                enableVibration(true)
                enableLights(true)
                setSound(android.net.Uri.parse("android.resource://" + context.packageName + "/" + soundResource), null)
                setAllowBubbles(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
            // Check if room is marked as low priority - skip notifications for low priority rooms
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val lowPriorityRooms = sharedPrefs.getStringSet("low_priority_rooms", emptySet()) ?: emptySet()
            
            if (lowPriorityRooms.contains(notificationData.roomId)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipping notification for low priority room (EnhancedNotificationDisplay): ${notificationData.roomId} (${notificationData.roomName})")
                return
            }
            
            // Check if this room is currently open in the app - skip notifications if user is viewing the room
            val currentOpenRoomId = sharedPrefs.getString("current_open_room_id", null)
            val appIsVisible = sharedPrefs.getBoolean("app_is_visible", false)
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "Notification check - appVisible: $appIsVisible, currentOpenRoomId: '$currentOpenRoomId', notificationRoomId: '${notificationData.roomId}', match: ${currentOpenRoomId == notificationData.roomId}"
            )
            if (appIsVisible && currentOpenRoomId != null && currentOpenRoomId == notificationData.roomId) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipping notification for currently visible room: ${notificationData.roomId} (${notificationData.roomName}) - user is already viewing this room")
                return
            }
            
            val isGroupRoom = notificationData.roomName != notificationData.senderDisplayName
            val hasImage = !notificationData.image.isNullOrEmpty()
            if (BuildConfig.DEBUG) Log.d(TAG, "showEnhancedNotification - hasImage: $hasImage, image: ${notificationData.image}")
            // Load avatars asynchronously with fallbacks
            val roomAvatarIcon = notificationData.roomAvatarUrl?.let { 
                loadAvatarAsIcon(it) 
            } ?: run {
                // Create fallback avatar for room (use room name + room ID)
                if (BuildConfig.DEBUG) Log.d(TAG, "No room avatar URL, creating fallback for: ${notificationData.roomName}")
                createFallbackAvatarIcon(notificationData.roomName, notificationData.roomId)
            }
            
            val senderAvatarIcon = notificationData.avatarUrl?.let { 
                loadAvatarAsIcon(it)
            } ?: run {
                // Create fallback avatar for sender
                if (BuildConfig.DEBUG) Log.d(TAG, "No sender avatar URL, creating fallback for: ${notificationData.senderDisplayName}")
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
            val currentUserId = sharedPrefs.getString("current_user_id", "self") ?: "self"
            val currentUserDisplayName = sharedPrefs.getString("current_user_display_name", "Me") ?: "Me"
            
            // Load current user's avatar for "me" person
            val currentUserAvatarIcon = try {
                val avatarUrl = sharedPrefs.getString("current_user_avatar_url", null)
                if (!avatarUrl.isNullOrEmpty()) {
                    val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
                    val avatarBitmap = if (cachedFile != null) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Using cached avatar for current user: $avatarUrl")
                        android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                    } else {
                        // Try to download avatar for current user
                        val httpUrl = MediaUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
                        if (httpUrl != null) {
                            val downloadedFile = MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
                            if (downloadedFile != null) {
                                if (BuildConfig.DEBUG) Log.d(TAG, "Downloaded current user avatar to cache: ${downloadedFile.absolutePath}")
                                android.graphics.BitmapFactory.decodeFile(downloadedFile.absolutePath)
                            } else {
                                if (BuildConfig.DEBUG) Log.d(TAG, "Failed to download current user avatar: $avatarUrl")
                                null
                            }
                        } else {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Failed to convert current user avatar MXC URL: $avatarUrl")
                            null
                        }
                    }
                    
                    // Apply circular transformation to match other avatars
                    avatarBitmap?.let { 
                        IconCompat.createWithBitmap(createCircularBitmap(it))
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "No avatar URL stored for current user")
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
                .setName(notificationData.senderDisplayName ?: notificationData.sender)
                .setIcon(senderAvatarIcon)
                .build()
            
            // Download image for image notifications
            val imageUri = if (hasImage && notificationData.image != null) {
                try {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Downloading image for notification: ${notificationData.image}")
                    
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
                    
                    if (BuildConfig.DEBUG) Log.d(TAG, "Parsed image URLs - MXC: $mxcUrl, HTTP: $httpUrl")
                    
                    if (mxcUrl != null && httpUrl != null) {
                        // Check cache first
                        val cachedFile = MediaCache.getCachedFile(context, mxcUrl)
                        if (cachedFile != null) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Using cached image: ${cachedFile.absolutePath}")
                            // Use FileProvider to create a content:// URI that can be accessed by the notification system
                            FileProvider.getUriForFile(
                                context,
                                "pt.aguiarvieira.andromuks.fileprovider",
                                cachedFile
                            )
                        } else {
                            // Download and cache
                            if (BuildConfig.DEBUG) Log.d(TAG, "Downloading image from: $httpUrl")
                            val downloadedFile = MediaCache.downloadAndCache(context, mxcUrl, httpUrl, authToken)
                            if (downloadedFile != null) {
                                if (BuildConfig.DEBUG) Log.d(TAG, "Downloaded image to cache: ${downloadedFile.absolutePath}")
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
            
            // SYNCHRONIZATION: Use per-room lock to prevent concurrent updates for the same room
            // This ensures only one notification update happens at a time per room, preventing flicker
            val roomLock = getRoomLock(notificationData.roomId)
            synchronized(roomLock) {
                // Create messaging style - extract existing style if available
                val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notifID = notificationData.roomId.hashCode()
                
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
                    if (BuildConfig.DEBUG) Log.d(TAG, "Adding image message to notification with URI: $imageUri")
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
                
                // Create RoomItem for shortcut update
                val roomItem = RoomItem(
                    id = notificationData.roomId,
                    name = notificationData.roomName ?: notificationData.roomId,
                    messagePreview = notificationData.body,
                    messageSender = notificationData.senderDisplayName ?: notificationData.sender,
                    unreadCount = 1,
                    highlightCount = 0,
                    avatarUrl = notificationData.roomAvatarUrl,
                    sortingTimestamp = notificationData.timestamp ?: System.currentTimeMillis()
                )
                
                // SHORTCUT OPTIMIZATION: Update shortcut when notification is shown
                // This ensures the room is in shortcuts when user receives messages (similar to when sending)
                // Uses efficient single-room update method, not full list processing
                conversationsApi?.updateShortcutsFromSyncRooms(listOf(roomItem))
                
                // Try to get existing shortcut for notification bubble
                val shortcutInfo = conversationsApi?.let { api ->
                try {
                    val existingShortcut = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
                        .firstOrNull { it.id == notificationData.roomId }
                    
                        if (existingShortcut != null) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Found existing shortcut: ${existingShortcut.shortLabel}")
                            existingShortcut
                        } else {
                            if (BuildConfig.DEBUG) Log.d(TAG, "No existing shortcut yet, will be created asynchronously")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting shortcut info", e)
                        null
                    }
                }
                
                // Create conversation channel for this room
                createConversationChannel(notificationData.roomId, notificationData.roomName ?: notificationData.roomId, isGroupRoom)
                
                // Use conversation channel for all notifications
                val channelId = "${CONVERSATION_CHANNEL_ID}_${notificationData.roomId}"
                
                // CRITICAL FIX: Check if a bubble already exists for this room
                // When bubble is open, we still need to post the notification (for unread indicator and bubble lifecycle)
                // but make it silent and non-interruptive to avoid closing the inactive bubble
                val bubbleAlreadyOpen = BubbleTracker.isBubbleOpen(notificationData.roomId)
                val bubbleIsVisible = BubbleTracker.isBubbleVisible(notificationData.roomId)
                val totalOpenBubbles = BubbleTracker.getOpenBubbles().size
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Bubble check for room ${notificationData.roomId}: open = $bubbleAlreadyOpen, visible = $bubbleIsVisible, total open bubbles = $totalOpenBubbles")
                    if (totalOpenBubbles >= 4) {
                        Log.w(TAG, "WARNING: ${totalOpenBubbles} bubbles are currently open - this may cause issues")
                    }
                }
                
                // Always create bubble metadata - needed for bubble lifecycle management
                // The metadata's setSuppressNotification will be set based on visibility
                val bubbleMetadata = createBubbleMetadata(
                    notificationData = notificationData,
                    isGroupRoom = isGroupRoom,
                    roomAvatarIcon = roomAvatarIcon,
                    senderAvatarIcon = senderAvatarIcon
                )
                
                // Determine large icon based on room type
                // For DMs: use sender's avatar (the conversation-level avatar)
                // For groups: use room avatar (the conversation-level avatar)
                val largeIconBitmap = if (isGroupRoom) {
                    circularRoomAvatar ?: circularSenderAvatar // Prefer room avatar for groups
                } else {
                    circularSenderAvatar ?: circularRoomAvatar // Prefer sender avatar for DMs
                }
                
                // Create main notification
                // CRITICAL: When bubble is open, make notification silent and non-interruptive
                // This keeps the bubble alive and updates unread indicator without closing/recreating the bubble
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.matrix) // Minimized icon in status bar
                    .setStyle(messagingStyle)
                    .setContentIntent(createRoomIntent(notificationData))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Required for Android Auto
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
                    
                    // Always set bubble metadata - needed for bubble lifecycle management
                    bubbleMetadata?.let { setBubbleMetadata(it) }
                    
                    // Add LocusId for conversation tracking (Android 10+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val locusId = LocusIdCompat(notificationData.roomId)
                            setLocusId(locusId)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.w(TAG, "Could not set LocusId", e)
                        }
                    }
                    
                    // CRITICAL: When bubble is open, make notification silent and non-interruptive
                    if (bubbleAlreadyOpen) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Bubble is open - making notification silent and non-interruptive for room: ${notificationData.roomId}")
                        // Silent notification - no sound, vibration, or heads-up
                        setSilent(true)
                        setOnlyAlertOnce(true) // Avoid re-alerting
                        setPriority(NotificationCompat.PRIORITY_LOW) // Lower priority to avoid interruption
                        setDefaults(0) // No sound, vibration, or lights
                    } else {
                        // Normal notification behavior when bubble is not open
                        setOnlyAlertOnce(true) // Prevent heads-up flicker on rapid updates
                        setPriority(NotificationCompat.PRIORITY_HIGH)
                        setDefaults(NotificationCompat.DEFAULT_ALL)
                    }
                    
                    // Store event_id in extras for later retrieval
                    if (notificationData.eventId != null) {
                        addExtras(android.os.Bundle().apply {
                            putString("event_id", notificationData.eventId)
                        })
                    }
                    // Add reply action
                    val replyAction = createReplyAction(notificationData)
                    addAction(replyAction)
                    // Add mark as read action
                    val markReadAction = createMarkReadAction(notificationData)
                    addAction(markReadAction)
                    
                    // Android Auto: surface the conversation via CarExtender/UnreadConversation
                    extend(
                        NotificationCompat.CarExtender()
                            .setUnreadConversation(
                                createUnreadConversation(
                                    notificationData = notificationData,
                                    messagingStyle = messagingStyle,
                                    replyAction = replyAction,
                                    markReadAction = markReadAction
                                )
                            )
                        )
                    }
                    .build()
                
                // Grant URI permission for image if present
                if (imageUri != null) {
                try {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Granting URI permission for notification image: $imageUri")
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
            } // End synchronized block
            
            // SHORTCUT OPTIMIZATION: Shortcuts already updated above when notification is shown
            // Using efficient single-room update via updateShortcutsFromSyncRooms
            
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
            // OPTIMIZATION #2: Store room ID directly instead of complex URI parsing
            putExtra("room_id", notificationData.roomId)
            putExtra("event_id", notificationData.eventId)
            putExtra("direct_navigation", true) // Flag for optimized processing
            putExtra("from_notification", true) // Flag to identify notification source
            // Add notification timestamp for freshness check
            notificationData.timestamp?.let { timestamp ->
                putExtra("notification_timestamp", timestamp)
            }
            // Keep URI for compatibility but make it simpler
            data = android.net.Uri.parse("matrix:roomid/${notificationData.roomId.substring(1)}${notificationData.eventId?.let { "/e/${it.substring(1)}" } ?: ""}")
        }
        
        return PendingIntent.getActivity(
            context,
            notificationData.roomId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * Create bubble metadata for conversation notifications
     */
    private fun createBubbleMetadata(
        notificationData: NotificationData,
        isGroupRoom: Boolean,
        roomAvatarIcon: IconCompat?,
        senderAvatarIcon: IconCompat?
    ): NotificationCompat.BubbleMetadata? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // CRITICAL FIX: Use FLAG_ACTIVITY_SINGLE_TOP instead of CLEAR_TOP to prevent activity restarts
                // SINGLE_TOP brings existing activity to front without restarting, preventing crashes
                // Also add FLAG_ACTIVITY_NEW_DOCUMENT for proper bubble task management
                val bubbleIntent = Intent(context, ChatBubbleActivity::class.java).apply {
                    action = "net.vrkknn.andromuks.ACTION_OPEN_BUBBLE"
                    data = android.net.Uri.parse("matrix:bubble/${notificationData.roomId.substring(1)}")
                    putExtra("room_id", notificationData.roomId)
                    putExtra("direct_navigation", true)
                    putExtra("bubble_mode", true)
                    // Use SINGLE_TOP to prevent restarting existing bubble activity
                    // Use NEW_DOCUMENT for proper bubble task isolation
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                }
                
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                
                // CRITICAL FIX: Use stable request code per room to prevent multiple bubble instances
                // The request code must be stable per room so Android recognizes it's the same bubble
                // If we change the request code for each notification, Android creates new bubble instances
                // This causes confusion when multiple bubbles are open (especially after 4+ bubbles)
                val stableRequestCode = notificationData.roomId.hashCode().let { 
                    // Ensure positive value for request code
                    if (it < 0) -it else it 
                }
                
                val bubblePendingIntent = PendingIntent.getActivity(
                    context,
                    stableRequestCode, // Stable per room - don't change for each notification
                    bubbleIntent,
                    pendingIntentFlags
                )
                
                val bubbleIcon = (if (isGroupRoom) roomAvatarIcon else senderAvatarIcon)
                    ?: createDefaultAdaptiveIcon()
                val shouldAutoExpand = autoExpandedBubbleRooms.add(notificationData.roomId)
                
                val desiredHeight = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    480f,
                    context.resources.displayMetrics
                ).toInt()
                
                // CRITICAL FIX: Only suppress notification in bubble metadata if bubble is visible
                // When bubble is open but not visible (minimized), we still want the notification
                // to update the bubble's unread indicator, so setSuppressNotification should be false
                // The notification itself will be made silent via setSilent(true) in the main notification builder
                val shouldSuppressNotification = BubbleTracker.isBubbleVisible(notificationData.roomId)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Bubble metadata - suppress notification: $shouldSuppressNotification (visible: ${BubbleTracker.isBubbleVisible(notificationData.roomId)})")
                }
                
                NotificationCompat.BubbleMetadata.Builder(bubblePendingIntent, bubbleIcon)
                    .setDesiredHeight(desiredHeight)
                    .setAutoExpandBubble(shouldAutoExpand)
                    .setSuppressNotification(shouldSuppressNotification)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating bubble metadata", e)
                null
            }
        } else {
            null
        }
    }
    
    
    /**
     * Create reply action
     */
    private fun createReplyAction(data: NotificationData): NotificationCompat.Action {
        if (BuildConfig.DEBUG) Log.d(TAG, "createReplyAction: Creating reply action for room: ${data.roomId}, event: ${data.eventId}")
        
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        
        // Use broadcast receiver to avoid trampoline and UI visibility
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = "net.vrkknn.andromuks.ACTION_REPLY"
            putExtra("room_id", data.roomId)
            putExtra("event_id", data.eventId)
        }
        
        if (BuildConfig.DEBUG) Log.d(TAG, "createReplyAction: Intent created with room_id: ${data.roomId}, event_id: ${data.eventId}")
        
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
        
        if (BuildConfig.DEBUG) Log.d(TAG, "createReplyAction: PendingIntent created successfully")
        
        return NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Reply",
            replyPendingIntent
        )
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(remoteInput)
            .build()
    }
    
    /**
     * Create mark as read action
     */
    private fun createMarkReadAction(data: NotificationData): NotificationCompat.Action {
        if (BuildConfig.DEBUG) Log.d(TAG, "createMarkReadAction: Creating mark read action for room: ${data.roomId}, event: ${data.eventId}")
        
        // Use broadcast receiver to avoid trampoline and UI visibility
        val markReadIntent = Intent(context, NotificationMarkReadReceiver::class.java).apply {
            action = "net.vrkknn.andromuks.ACTION_MARK_READ"
            putExtra("room_id", data.roomId)
            putExtra("event_id", data.eventId)
        }
        
        if (BuildConfig.DEBUG) Log.d(TAG, "createMarkReadAction: Intent created with room_id: ${data.roomId}, event_id: ${data.eventId}")
        
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            data.roomId.hashCode() + 2,
            markReadIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        if (BuildConfig.DEBUG) Log.d(TAG, "createMarkReadAction: PendingIntent created successfully")
        
        return NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Mark Read",
            markReadPendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /**
     * Create UnreadConversation for Android Auto from the latest MessagingStyle messages.
     * This allows DHU/Android Auto to announce and reply via voice using the same actions.
     */
    private fun createUnreadConversation(
        notificationData: NotificationData,
        messagingStyle: MessagingStyle,
        replyAction: NotificationCompat.Action,
        markReadAction: NotificationCompat.Action
    ): NotificationCompat.CarExtender.UnreadConversation {
        val conversationTitle = when {
            notificationData.roomName != null && notificationData.roomName != notificationData.senderDisplayName ->
                notificationData.roomName
            else -> notificationData.senderDisplayName ?: notificationData.roomId
        } ?: "Conversation"

        // Limit to a few recent messages for car UI
        val latestMessages = messagingStyle.messages.takeLast(5)

        val builder = NotificationCompat.CarExtender.UnreadConversation.Builder(conversationTitle)
            .setLatestTimestamp(
                latestMessages.lastOrNull()?.timestamp
                    ?: notificationData.timestamp
                    ?: System.currentTimeMillis()
            )
            .setReadPendingIntent(markReadAction.actionIntent)
            .setReplyAction(
                replyAction.actionIntent,
                RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply").build()
            )

        latestMessages.forEach { msg ->
            builder.addMessage(msg.text?.toString() ?: "")
        }

        return builder.build()
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
     * Uses in-memory cache to avoid reloading the same avatar on every notification update
     */
    private suspend fun loadAvatarAsIcon(avatarUrl: String): IconCompat? {
        return try {
            // Check cache first to avoid reloading
            avatarIconCache[avatarUrl]?.let { cachedIcon ->
                if (BuildConfig.DEBUG) Log.d(TAG, "Using cached avatar icon for: $avatarUrl")
                return cachedIcon
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "━━━ loadAvatarAsIcon called ━━━")
            if (BuildConfig.DEBUG) Log.d(TAG, "  Avatar URL: $avatarUrl")
            
            // Load bitmap (from cache or download)
            val bitmap = loadAvatarBitmap(avatarUrl)
            if (BuildConfig.DEBUG) Log.d(TAG, "  Bitmap loaded: ${bitmap != null}")
            
            val icon = if (bitmap != null) {
                // Make it circular and use directly as adaptive bitmap
                val circularBitmap = createCircularBitmap(bitmap)
                if (BuildConfig.DEBUG) Log.d(TAG, "  ✓✓✓ SUCCESS: Created circular bitmap icon for notification Person")
                IconCompat.createWithAdaptiveBitmap(circularBitmap)
            } else {
                Log.w(TAG, "  ✗✗✗ FAILED: loadAvatarBitmap returned null, using default icon")
                createDefaultAdaptiveIcon()
            }
            
            // Cache the icon for future use
            avatarIconCache[avatarUrl] = icon
            
            icon
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Converting hardware bitmap to software bitmap")
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
            if (BuildConfig.DEBUG) Log.d(TAG, "loadAvatarBitmap called with: $avatarUrl")
            
            if (avatarUrl.isEmpty()) {
                Log.w(TAG, "Avatar URL is empty, returning null")
                return@withContext null
            }
            
            // Check if we have a cached version first
            val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
            
            if (cachedFile != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Using cached avatar file: ${cachedFile.absolutePath}")
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
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Downloading and caching avatar from: $httpUrl")
            
            // Download and cache using existing MediaCache infrastructure
            val downloadedFile = MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
            
            if (downloadedFile != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Successfully downloaded and cached avatar: ${downloadedFile.absolutePath}")
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
            // CRITICAL: Don't update notification if bubble is open
            // Updating the notification removes bubble metadata, causing Android to close the bubble
            // The bubble will receive the reply via WebSocket updates anyway
            val isBubbleOpen = BubbleTracker.isBubbleOpen(roomId)
            if (isBubbleOpen) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipping notification update - bubble is open for room: $roomId (prevents bubble from closing)")
                return
            }
            
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Extracted event_id from notification: $eventId")
            
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
                        if (BuildConfig.DEBUG) Log.d(TAG, "Using cached avatar for reply: $avatarUrl")
                        android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                    } else {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Avatar not cached, reply will show without avatar: $avatarUrl")
                        null
                    }
                    
                    // Apply circular transformation to match other avatars
                    avatarBitmap?.let { 
                        IconCompat.createWithBitmap(createCircularBitmap(it))
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "No avatar URL stored for current user")
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
            
            // Avoid duplicate reply entries - system may already add the reply automatically
            val replyAlreadyPresent = existingStyle.messages.any { message ->
                val messageText = message.text?.toString()
                val messagePersonKey = message.person?.key
                val matchesText = messageText == replyText
                val matchesPerson = messagePersonKey == currentUserId
                matchesText && matchesPerson
            }

            if (!replyAlreadyPresent) {
                // Add the reply message to the style
                existingStyle.addMessage(
                    MessagingStyle.Message(
                        replyText,
                        System.currentTimeMillis(),
                        mePerson
                    )
                )
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "Reply already present in notification, skipping duplicate entry")
            }
            
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
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Required for Android Auto
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
                    
                    // Note: Bubble metadata is not preserved when updating notifications
                    // This is why we skip updates when bubble is open (see early return above)
                    
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
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Updated notification with reply for room: $roomId")
            
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Extracted event_id from notification for mark read: $eventId")
            
            // Note: Unread count clearing is handled automatically by our shortcut updates
            // No need to call clearUnreadCount() which can lose icons
            
            // CRITICAL FIX: Only dismiss notification if bubble is not open
            // Cancelling the notification when a bubble is open causes Android to destroy the bubble
            val isBubbleOpen = BubbleTracker.isBubbleOpen(roomId)
            if (isBubbleOpen) {
                if (BuildConfig.DEBUG) Log.d(TAG, "NOT dismissing notification for room: $roomId - bubble is open (prevents bubble destruction)")
                // Shortcuts will be updated when user sends a message or receives new notifications
                // No need to update shortcuts when marking as read
                return
            }
            
            // Dismiss the notification now that it's been marked as read (only if no bubble)
            val notificationManagerCompat = NotificationManagerCompat.from(context)
            notificationManagerCompat.cancel(notifID)
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Dismissed notification and cleared unread count for room: $roomId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification as read", e)
        }
    }
    
    /**
     * Clear notification for specific room
     * CRITICAL: Only clears if bubble is not open to prevent bubble destruction
     */
    fun clearNotificationForRoom(roomId: String) {
        // CRITICAL FIX: Only dismiss notification if bubble is not open
        // Cancelling the notification when a bubble is open causes Android to destroy the bubble
        val isBubbleOpen = BubbleTracker.isBubbleOpen(roomId)
        if (isBubbleOpen) {
            if (BuildConfig.DEBUG) Log.d(TAG, "NOT clearing notification for room: $roomId - bubble is open (prevents bubble destruction)")
            return
        }
        
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
