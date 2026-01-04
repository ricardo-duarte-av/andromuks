@file:Suppress("DEPRECATION")

package net.vrkknn.andromuks.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.R
import net.vrkknn.andromuks.database.AndromuksDatabase
import net.vrkknn.andromuks.database.ProfileRepository
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.MediaCache
import android.graphics.Color
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Auto screen showing the timeline/messages for a specific room.
 */
class CarRoomTimelineScreen(
    carContext: CarContext,
    private val roomId: String,
    private val roomName: String
) : Screen(carContext) {
    
    companion object {
        private const val TAG = "CarRoomTimelineScreen"
        private const val MESSAGES_LIMIT = 50 // Limit messages for performance
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var messages: List<MessageItem> = emptyList()
    private var homeserverUrl: String = ""
    private var authToken: String = ""
    private val avatarCache = mutableMapOf<String, CarIcon>() // Cache avatars by sender userId
    
    init {
        scope.launch {
            loadCredentials()
            loadMessages()
        }
    }
    
    private suspend fun loadCredentials() {
        try {
            val sharedPrefs = carContext.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
            authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Loaded credentials - homeserverUrl: ${if (homeserverUrl.isNotEmpty()) "present" else "missing"}, authToken: ${if (authToken.isNotEmpty()) "present" else "missing"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading credentials", e)
        }
    }
    
    private fun loadMessages() {
        scope.launch {
            try {
                val database = AndromuksDatabase.getInstance(carContext)
                val eventDao = database.eventDao()
                val roomStateDao = database.roomStateDao()
                
                // Get room state for sender names
                val roomState = withContext(Dispatchers.IO) {
                    roomStateDao.get(roomId)
                }
                
                // Get recent messages (most recent first)
                val events = withContext(Dispatchers.IO) {
                    eventDao.getEventsForRoomDesc(roomId, MESSAGES_LIMIT)
                }
                
                // Get room members for display names and avatars
                val roomMemberDao = database.roomMemberDao()
                val allMembers = withContext(Dispatchers.IO) {
                    roomMemberDao.getMembersForRoom(roomId)
                        .associateBy { it.userId }
                }
                
                // Load user profiles from ProfileRepository for more up-to-date display names
                val uniqueSenderIds = events.mapNotNull { it.sender }.distinct()
                val profileRepository = ProfileRepository(carContext)
                val globalProfiles = withContext(Dispatchers.IO) {
                    if (uniqueSenderIds.isNotEmpty()) {
                        profileRepository.loadProfiles(uniqueSenderIds)
                    } else {
                        emptyMap()
                    }
                }
                
                // Convert events to message items
                messages = events.mapNotNull { event ->
                    try {
                        // Priority: 1. ProfileRepository, 2. RoomMember displayName, 3. username localpart
                        val senderName = if (event.sender != null) {
                            globalProfiles[event.sender]?.displayName
                                ?: allMembers[event.sender]?.displayName
                                ?: event.sender.substringAfter("@").substringBefore(":")
                        } else {
                            "Unknown"
                        }
                        
                        // Use ProfileRepository avatar URL first, then RoomMember avatar URL
                        val senderAvatarUrl = if (event.sender != null) {
                            globalProfiles[event.sender]?.avatarUrl
                                ?: allMembers[event.sender]?.avatarUrl
                        } else {
                            null
                        }
                        
                        val messageText = when {
                            event.type == "m.room.encrypted" && (event.decryptedType == "m.room.message" || event.decryptedType == "m.text") -> {
                                // Extract text from decrypted content
                                extractTextFromEvent(event.rawJson) ?: "[Encrypted message]"
                            }
                            event.type == "m.room.message" -> {
                                extractTextFromEvent(event.rawJson) ?: "[Message]"
                            }
                            event.type == "m.room.member" -> {
                                "[Member event]"
                            }
                            event.isRedaction -> {
                                "[Redacted]"
                            }
                            else -> {
                                "[${event.type}]"
                            }
                        }
                        
                        MessageItem(
                            sender = senderName,
                            senderId = event.sender,
                            senderAvatarUrl = senderAvatarUrl,
                            text = messageText,
                            timestamp = event.timestamp,
                            eventId = event.eventId
                        )
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) {
                            Log.w(TAG, "Error processing event ${event.eventId}", e)
                        }
                        null
                    }
                } // Keep in descending order (newest first) - most recent messages on top for Android Auto
                
                invalidate() // Refresh the UI
                
                // Preload sender avatars in background
                preloadAvatars(messages)
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded ${messages.size} messages for room $roomId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading messages", e)
            }
        }
    }
    
    /**
     * Extract text content from event JSON.
     * For encrypted messages, looks for "decrypted" object first, then falls back to "content".
     * Matches the pattern used in RoomTimelineViewer.kt for consistent decryption handling.
     */
    private fun extractTextFromEvent(rawJson: String?): String? {
        if (rawJson == null) return null
        
        return try {
            val json = org.json.JSONObject(rawJson)
            
            // For encrypted messages, try decrypted content first (matches RoomTimelineViewer.kt pattern)
            val decrypted = json.optJSONObject("decrypted")
            val content = decrypted ?: json.optJSONObject("content")
            
            if (content == null) {
                return null
            }
            
            // Check if this is an edit event (m.replace) - use m.new_content
            val relatesTo = content.optJSONObject("m.relates_to")
            val isEdit = relatesTo?.optString("rel_type") == "m.replace"
            val actualContent = if (isEdit) {
                content.optJSONObject("m.new_content") ?: content
            } else {
                content
            }
            
            // Try body field first (plain text)
            val body = actualContent.optString("body", "")
            if (body.isNotBlank()) {
                return if (isEdit) "[EDITED] $body" else body
            }
            
            // Try formatted_body (HTML) - extract text from it
            val formattedBody = actualContent.optString("formatted_body", "")
            if (formattedBody.isNotBlank()) {
                // Simple HTML tag removal for preview
                val text = formattedBody
                    .replace(Regex("<[^>]*>"), " ")
                    .replace("&nbsp;", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                return if (isEdit) "[EDITED] $text" else text
            }
            
            // For media messages, show filename or msgtype
            val msgtype = actualContent.optString("msgtype", "")
            val filename = actualContent.optString("filename", "")
            when {
                filename.isNotBlank() -> "[$msgtype] $filename"
                msgtype.isNotBlank() -> when {
                    msgtype == "m.image" -> "📷 Image"
                    msgtype == "m.video" -> "🎥 Video"
                    msgtype == "m.audio" -> "🎵 Audio"
                    msgtype == "m.file" -> "📎 File"
                    else -> "[$msgtype]"
                }
                else -> null
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error parsing event JSON", e)
            }
            null
        }
    }
    
    override fun onGetTemplate(): Template {
        val backAction = Action.BACK
        
        val exitAction = Action.Builder()
            .setTitle(carContext.getString(R.string.car_action_close))
            .setOnClickListener {
                carContext.finishCarApp()
            }
            .build()
        
        val actionStrip = ActionStrip.Builder()
            .addAction(exitAction)
            .build()
        
        // Build the list of messages
        val itemListBuilder = ItemList.Builder()
        
        if (messages.isEmpty()) {
            // Show empty state
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("No messages")
                    .addText("This conversation doesn't have any messages yet.")
                    .build()
            )
        } else {
            // Add each message as a row
            messages.forEach { message ->
                val timeString = formatTimestamp(message.timestamp)
                
                val messageText = if (message.text.length > 100) {
                    message.text.take(97) + "..."
                } else {
                    message.text
                }
                
                val rowBuilder = Row.Builder()
                    .setTitle(message.sender)
                    .addText("$timeString: $messageText")
                
                // Add sender avatar (only use cached - preloadAvatars handles loading)
                message.senderId?.let { senderId ->
                    avatarCache[senderId]?.let { carIcon ->
                        rowBuilder.setImage(carIcon)
                    }
                }
                
                itemListBuilder.addItem(rowBuilder.build())
            }
        }
        
        val displayName = if (roomName.length > 30) {
            roomName.take(27) + "..."
        } else {
            roomName
        }
        
        return ListTemplate.Builder()
            .setTitle(displayName)
            .setHeaderAction(backAction)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return ""
        
        val date = Date(timestamp)
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "Just now" // Less than 1 minute
            diff < 3600_000 -> "${diff / 60_000}m ago" // Less than 1 hour
            diff < 86400_000 -> "${diff / 3600_000}h ago" // Less than 1 day
            else -> {
                // More than 1 day - show date
                val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
                dateFormat.format(date)
            }
        }
    }
    
    /**
     * Preload avatars for message senders in background and cache them
     */
    private fun preloadAvatars(messages: List<MessageItem>) {
        scope.launch {
            // Only proceed if credentials are loaded
            if (homeserverUrl.isEmpty()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Cannot preload avatars - homeserverUrl not loaded yet, will retry later")
                return@launch
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Starting to preload avatars for ${messages.size} messages")
            
            messages.forEach { message ->
                if (message.senderId != null && !avatarCache.containsKey(message.senderId)) {
                    // Create fallback avatar immediately if no avatar URL (don't wait for async load)
                    if (message.senderAvatarUrl == null) {
                        val fallbackIcon = createFallbackAvatar(message.sender, message.senderId)
                        if (fallbackIcon != null) {
                            avatarCache[message.senderId] = fallbackIcon
                            if (BuildConfig.DEBUG) Log.d(TAG, "Created fallback avatar for sender ${message.senderId}")
                            invalidate()
                        }
                    } else {
                        // Try to load real avatar from URL
                        try {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Loading avatar for sender ${message.senderId} from ${message.senderAvatarUrl}")
                            val carIcon = loadUserAvatar(message.senderAvatarUrl, message.senderId)
                            if (carIcon != null) {
                                avatarCache[message.senderId] = carIcon
                                if (BuildConfig.DEBUG) Log.d(TAG, "✓ Successfully loaded and cached avatar for sender ${message.senderId}")
                                invalidate() // Refresh UI when avatar is loaded
                            } else {
                                // Avatar load failed, create fallback
                                val fallbackIcon = createFallbackAvatar(message.sender, message.senderId)
                                if (fallbackIcon != null) {
                                    avatarCache[message.senderId] = fallbackIcon
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Avatar load failed for ${message.senderId}, created fallback")
                                    invalidate()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading avatar for sender ${message.senderId}", e)
                            // On error, create fallback
                            val fallbackIcon = createFallbackAvatar(message.sender, message.senderId)
                            if (fallbackIcon != null) {
                                avatarCache[message.senderId] = fallbackIcon
                                if (BuildConfig.DEBUG) Log.d(TAG, "Created fallback avatar after error for sender ${message.senderId}")
                                invalidate()
                            }
                        }
                    }
                } else if (BuildConfig.DEBUG && message.senderId != null && avatarCache.containsKey(message.senderId)) {
                    Log.d(TAG, "Sender ${message.senderId} avatar already cached")
                }
            }
        }
    }
    
    /**
     * Load user avatar as CarIcon for display in Android Auto
     */
    private suspend fun loadUserAvatar(avatarUrl: String?, userId: String): CarIcon? = withContext(Dispatchers.IO) {
        try {
            if (avatarUrl.isNullOrEmpty() || homeserverUrl.isEmpty()) {
                return@withContext null
            }
            
            // Check cache first
            val cachedFile = MediaCache.getCachedFile(carContext, avatarUrl)
            val bitmap = if (cachedFile != null && cachedFile.exists()) {
                BitmapFactory.decodeFile(cachedFile.absolutePath)
            } else {
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
                    return@withContext null
                }
                
                // Download and cache
                val downloadedFile = MediaCache.downloadAndCache(carContext, avatarUrl, httpUrl, authToken)
                if (downloadedFile != null && downloadedFile.exists()) {
                    BitmapFactory.decodeFile(downloadedFile.absolutePath)
                } else {
                    null
                }
            }
            
            if (bitmap != null) {
                // Create circular bitmap for avatar
                val circularBitmap = createCircularBitmap(bitmap)
                // CarIcon requires regular bitmap, not adaptive bitmap (which creates type 5/CUSTOM)
                val iconCompat = IconCompat.createWithBitmap(circularBitmap)
                CarIcon.Builder(iconCompat).build()
            } else {
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error loading user avatar for $userId", e)
            }
            null
        }
    }
    
    /**
     * Create a fallback avatar with user's initial on a colored background.
     * Uses the same color/character logic as AvatarUtils for consistency.
     */
    private fun createFallbackAvatar(displayName: String?, userId: String): CarIcon? {
        return try {
            // Get color and character using AvatarUtils (same as main app)
            val colorHex = AvatarUtils.getUserColor(userId)
            val character = AvatarUtils.getFallbackCharacter(displayName, userId)
            
            // Parse hex color
            val color = try {
                Color.parseColor("#$colorHex")
            } catch (e: Exception) {
                Color.parseColor("#d991de") // Fallback color (pink)
            }
            
            // Create bitmap (128x128 for good quality)
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Draw background circle with color
            val bgPaint = Paint().apply {
                this.color = color
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
            
            // Draw text (character/initial) in white
            if (character.isNotEmpty()) {
                val textPaint = Paint().apply {
                    this.color = Color.WHITE
                    textSize = size * 0.5f // 50% of bitmap size
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                
                // Center text vertically
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(character, 0, character.length, textBounds)
                val textY = size / 2f + textBounds.height() / 2f - textBounds.bottom
                
                canvas.drawText(character, size / 2f, textY, textPaint)
            }
            
            // Make it circular and convert to CarIcon
            val circularBitmap = createCircularBitmap(bitmap)
            val iconCompat = IconCompat.createWithBitmap(circularBitmap)
            CarIcon.Builder(iconCompat).build()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error creating fallback avatar for $userId", e)
            }
            null
        }
    }
    
    /**
     * Create a circular bitmap from a source bitmap (for avatar display)
     */
    private fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        // Convert hardware bitmap to software bitmap if needed
        val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
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
    
    private data class MessageItem(
        val sender: String,
        val senderId: String?,
        val senderAvatarUrl: String?,
        val text: String,
        val timestamp: Long,
        val eventId: String
    )
}
