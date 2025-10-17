package net.vrkknn.andromuks

import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.Person as CorePerson
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.MediaCache
import net.vrkknn.andromuks.utils.MediaUtils
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ConversationShortcut(
    val roomId: String,
    val roomName: String,
    val roomAvatarUrl: String?,
    val lastMessage: String?,
    val unreadCount: Int,
    val timestamp: Long
)

class ConversationsApi(private val context: Context, private val homeserverUrl: String, private val authToken: String, private val realMatrixHomeserverUrl: String = "") {
    
    companion object {
        private const val TAG = "ConversationsApi"
        private const val MAX_SHORTCUTS = 4
    }
    
    /**
     * Build a Person object from notification data (like the working Gomuks app)
     */
    suspend fun buildPersonFromNotificationData(
        userId: String,
        displayName: String,
        avatarUrl: String?
    ): CorePerson {
        val userAvatar = downloadAvatar(avatarUrl)
        return CorePerson.Builder()
            .setKey(userId)
            .setName(displayName)
            .setUri("matrix:u/${userId.substring(1)}")
            .setIcon(if (userAvatar != null) IconCompat.createWithAdaptiveBitmap(userAvatar) else null)
            .build()
    }
    
    /**
     * Download avatar using Coil with MediaCache integration
     */
    private suspend fun downloadAvatar(avatarUrl: String?): Bitmap? = withContext(Dispatchers.IO) {
        Log.d(TAG, "downloadAvatar called with: $avatarUrl")
        
        if (avatarUrl.isNullOrEmpty()) {
            Log.w(TAG, "Avatar URL is null or empty, returning null")
            return@withContext null
        }
        
        return@withContext try {
            // Check if we have a cached version first
            val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
            
            val imageUrl = if (cachedFile != null) {
                Log.d(TAG, "Using cached avatar file: ${cachedFile.absolutePath}")
                cachedFile.absolutePath
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
                    Log.e(TAG, "Failed to convert avatar URL to HTTP URL: $avatarUrl")
                    return@withContext null
                }
                
                Log.d(TAG, "Downloading and caching avatar from: $httpUrl")
                
                // Download and cache using existing MediaCache infrastructure
                val downloadedFile = MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
                
                if (downloadedFile != null) {
                    Log.d(TAG, "Successfully downloaded and cached avatar: ${downloadedFile.absolutePath}")
                    downloadedFile.absolutePath
                } else {
                    Log.e(TAG, "Failed to download avatar")
                    return@withContext null
                }
            }
            
            // Use shared ImageLoader singleton with custom User-Agent
            val imageLoader = ImageLoaderSingleton.get(context)
            
            // Load bitmap using Coil
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    if (cachedFile == null) {
                        addHeader("Cookie", "gomuks_auth=$authToken")
                    }
                }
                .build()
            
            val drawable = imageLoader.execute(request).drawable
            if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else {
                Log.w(TAG, "Failed to get bitmap from Coil drawable")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during avatar download: $avatarUrl", e)
            null
        }
    }
    
    /**
     * Get circular bitmap from first frame of GIF or static image
     */
    private fun getCircularBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        
        // Convert hardware bitmap to software bitmap if needed
        val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            Log.d(TAG, "Converting hardware bitmap to software bitmap")
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        
        val size = Math.min(softwareBitmap.width, softwareBitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint()
        val rect = android.graphics.Rect(0, 0, size, size)
        val rectF = android.graphics.RectF(rect)
        val radius = size / 2f
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(softwareBitmap, null, rect, paint)
        
        // Clean up the software bitmap if we created a copy
        if (softwareBitmap != bitmap) {
            softwareBitmap.recycle()
        }
        
        return output
    }
    
    /**
     * Get first frame of GIF or static image as circular bitmap
     */
    private suspend fun getCircularBitmapFromUrl(avatarUrl: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (avatarUrl.isNullOrEmpty()) return@withContext null
        
        try {
            // Check if we have a cached version first
            val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
            
            val imageUrl = if (cachedFile != null) {
                Log.d(TAG, "Using cached file for circular bitmap: ${cachedFile.absolutePath}")
                cachedFile.absolutePath
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
                    Log.e(TAG, "Failed to convert avatar URL to HTTP URL: $avatarUrl")
                    return@withContext null
                }
                
                // Download and cache using existing MediaCache infrastructure
                val downloadedFile = MediaCache.downloadAndCache(context, avatarUrl, httpUrl, authToken)
                
                if (downloadedFile != null) {
                    Log.d(TAG, "Successfully downloaded and cached avatar: ${downloadedFile.absolutePath}")
                    downloadedFile.absolutePath
                } else {
                    Log.e(TAG, "Failed to download avatar")
                    return@withContext null
                }
            }
            
            // Use shared ImageLoader singleton with custom User-Agent
            val imageLoader = ImageLoaderSingleton.get(context)
            
            // Load first frame using Coil
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    if (cachedFile == null) {
                        addHeader("Cookie", "gomuks_auth=$authToken")
                    }
                }
                .build()
            
            val drawable = imageLoader.execute(request).drawable
            Log.d(TAG, "Loaded drawable type: ${drawable?.javaClass?.simpleName ?: "null"}")
            val bitmap = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> {
                    Log.d(TAG, "Got BitmapDrawable, bitmap size: ${drawable.bitmap.width}x${drawable.bitmap.height}")
                    drawable.bitmap
                }
                is android.graphics.drawable.AnimationDrawable -> {
                    Log.d(TAG, "Got AnimationDrawable with ${drawable.numberOfFrames} frames")
                    // For animated GIFs, get the first frame
                    if (drawable.numberOfFrames > 0) {
                        val firstFrame = drawable.getFrame(0)
                        if (firstFrame is android.graphics.drawable.BitmapDrawable) {
                            Log.d(TAG, "Got first frame as BitmapDrawable, bitmap size: ${firstFrame.bitmap.width}x${firstFrame.bitmap.height}")
                            firstFrame.bitmap
                        } else {
                            Log.w(TAG, "First frame is not a BitmapDrawable")
                            null
                        }
                    } else {
                        Log.w(TAG, "AnimationDrawable has no frames")
                        null
                    }
                }
                else -> {
                    Log.w(TAG, "Unexpected drawable type: ${drawable?.javaClass?.simpleName ?: "null"}")
                    null
                }
            }
            
            // Convert to circular bitmap
            val circularBitmap = getCircularBitmap(bitmap)
            Log.d(TAG, "Circular bitmap result: ${circularBitmap != null}")
            circularBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Exception during circular bitmap creation: $avatarUrl", e)
            null
        }
    }
    
    
    /**
     * Remove room shortcut when notifications are dismissed
     */
    fun removeRoomShortcut(roomId: String) {
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(roomId))
            Log.d(TAG, "Removed shortcut for room: $roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove shortcut for room: $roomId", e)
        }
    }
    
    /**
     * Update conversation shortcuts based on recent rooms
     * Returns immediately for non-blocking calls
     */
    fun updateConversationShortcuts(rooms: List<RoomItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Log.d(TAG, "updateConversationShortcuts called with ${rooms.size} rooms, realMatrixHomeserverUrl: $realMatrixHomeserverUrl")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val shortcuts = createShortcutsFromRooms(rooms)
                    updateShortcuts(shortcuts)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating conversation shortcuts", e)
                }
            }
        }
    }
    
    /**
     * Update conversation shortcuts synchronously - waits for completion
     * Used when we need the shortcut to exist before showing notification
     */
    suspend fun updateConversationShortcutsSync(rooms: List<RoomItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Log.d(TAG, "updateConversationShortcutsSync called with ${rooms.size} rooms")
            withContext(Dispatchers.IO) {
                try {
                    val shortcuts = createShortcutsFromRooms(rooms)
                    updateShortcuts(shortcuts)
                    Log.d(TAG, "Synchronous shortcut update completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating conversation shortcuts synchronously", e)
                }
            }
        }
    }
    
    /**
     * Create shortcuts from room list
     */
    private suspend fun createShortcutsFromRooms(rooms: List<RoomItem>): List<ConversationShortcut> {
        // Get recent rooms with messages, sorted by timestamp
        val recentRooms = rooms
            .filter { it.sortingTimestamp != null && it.sortingTimestamp > 0 }
            .sortedByDescending { it.sortingTimestamp }
            .take(MAX_SHORTCUTS)
        
        return recentRooms.map { room ->
            ConversationShortcut(
                roomId = room.id,
                roomName = room.name,
                roomAvatarUrl = room.avatarUrl,
                lastMessage = room.messagePreview,
                unreadCount = room.unreadCount ?: 0,
                timestamp = room.sortingTimestamp ?: 0L
            )
        }
    }
    
    /**
     * Update shortcuts in the system
     * Intelligently merges with existing shortcuts to preserve good icons
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun updateShortcuts(shortcuts: List<ConversationShortcut>) {
        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Updating ${shortcuts.size} shortcuts using pushDynamicShortcut()")
                
                for (shortcut in shortcuts) {
                    try {
                        Log.d(TAG, "Updating shortcut - roomId: '${shortcut.roomId}', roomName: '${shortcut.roomName}'")
                        
                        // Check if avatar is in cache
                        val avatarInCache = shortcut.roomAvatarUrl?.let { url ->
                            MediaCache.getCachedFile(context, url) != null
                        } ?: false
                        
                        Log.d(TAG, "  Avatar in cache: $avatarInCache")
                        
                        // Create ShortcutInfoCompat (AndroidX version)
                        val shortcutInfoCompat = createShortcutInfoCompat(shortcut)
                        
                        // If avatar just became available, remove old shortcut to force icon update
                        if (avatarInCache) {
                            Log.d(TAG, "  Removing old shortcut to refresh icon")
                            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcut.roomId))
                        }
                        
                        // Push/update the shortcut (conversation-optimized API)
                        // This preserves other shortcuts automatically!
                        ShortcutManagerCompat.pushDynamicShortcut(context, shortcutInfoCompat)
                        Log.d(TAG, "  ✓ Shortcut pushed successfully")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating shortcut for room: ${shortcut.roomName}", e)
                    }
                }
                
                Log.d(TAG, "Finished updating shortcuts")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in updateShortcuts", e)
            }
        }
    }
    
    /**
     * Create a ShortcutInfoCompat from ConversationShortcut (AndroidX version)
     */
    private suspend fun createShortcutInfoCompat(shortcut: ConversationShortcut): ShortcutInfoCompat {
        // Create proper matrix: URI with via parameter
        val matrixUri = if (realMatrixHomeserverUrl.isNotEmpty()) {
            val serverHost = Uri.parse(realMatrixHomeserverUrl).host ?: ""
            "matrix:roomid/${shortcut.roomId.substring(1)}?via=$serverHost"
        } else {
            "matrix:roomid/${shortcut.roomId.substring(1)}"
        }
        Log.d(TAG, "Creating conversation shortcut with matrix URI: $matrixUri")
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_VIEW
            data = android.net.Uri.parse(matrixUri)
            putExtra("room_id", shortcut.roomId)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val icon = if (shortcut.roomAvatarUrl != null) {
            try {
                Log.d(TAG, "━━━ Creating shortcut icon ━━━")
                Log.d(TAG, "  Room: ${shortcut.roomName}")
                Log.d(TAG, "  Avatar URL: ${shortcut.roomAvatarUrl}")
                
                // Only use cached avatar - don't download to avoid blocking
                val cachedFile = MediaCache.getCachedFile(context, shortcut.roomAvatarUrl)
                Log.d(TAG, "  Cached file: ${cachedFile?.absolutePath}")
                Log.d(TAG, "  File exists: ${cachedFile?.exists()}")
                
                if (cachedFile != null && cachedFile.exists()) {
                    Log.d(TAG, "  ✓ Avatar is in cache, loading bitmap...")
                    val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    Log.d(TAG, "  Bitmap loaded: ${bitmap != null} (${bitmap?.width}x${bitmap?.height})")
                    
                    if (bitmap != null) {
                        val circularBitmap = getCircularBitmap(bitmap)
                        Log.d(TAG, "  Circular bitmap created: ${circularBitmap != null}")
                        
                        if (circularBitmap != null) {
                            Log.d(TAG, "  ✓✓✓ SUCCESS: Using circular bitmap for shortcut icon")
                            IconCompat.createWithAdaptiveBitmap(circularBitmap)
                        } else {
                            Log.e(TAG, "  ✗✗✗ FAILED: getCircularBitmap returned null, creating fallback with initials")
                            createFallbackShortcutIconCompat(shortcut.roomName, shortcut.roomId)
                        }
                    } else {
                        Log.e(TAG, "  ✗✗✗ FAILED: BitmapFactory.decodeFile returned null, creating fallback with initials")
                        createFallbackShortcutIconCompat(shortcut.roomName, shortcut.roomId)
                    }
                } else {
                    Log.w(TAG, "  ✗✗✗ FAILED: Avatar not in cache, creating fallback with initials")
                    createFallbackShortcutIconCompat(shortcut.roomName, shortcut.roomId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "  ✗✗✗ EXCEPTION: Error loading avatar for shortcut, creating fallback", e)
                e.printStackTrace()
                createFallbackShortcutIconCompat(shortcut.roomName, shortcut.roomId)
            }
        } else {
            Log.w(TAG, "━━━ No room avatar URL provided, creating fallback with initials ━━━")
            createFallbackShortcutIconCompat(shortcut.roomName, shortcut.roomId)
        }
        
        return ShortcutInfoCompat.Builder(context, shortcut.roomId)
            .setShortLabel(shortcut.roomName)
            .setLongLabel(shortcut.roomName)
            .setIcon(icon)
            .setIntent(intent)
            .setCategories(setOf("android.shortcut.conversation"))
            .setIsConversation()
            .setLongLived(true)
            .build()
    }
    
    /**
     * Create a ShortcutInfo from ConversationShortcut (platform API version - kept for compatibility)
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun createShortcutInfo(shortcut: ConversationShortcut): ShortcutInfo {
        // Create proper matrix: URI with via parameter
        val matrixUri = if (realMatrixHomeserverUrl.isNotEmpty()) {
            val serverHost = Uri.parse(realMatrixHomeserverUrl).host ?: ""
            "matrix:roomid/${shortcut.roomId.substring(1)}?via=$serverHost"
        } else {
            "matrix:roomid/${shortcut.roomId.substring(1)}"
        }
        Log.d(TAG, "Creating conversation shortcut with matrix URI: $matrixUri")
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_VIEW
            data = android.net.Uri.parse(matrixUri)
            putExtra("room_id", shortcut.roomId)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val icon = if (shortcut.roomAvatarUrl != null) {
            try {
                Log.d(TAG, "━━━ Creating shortcut icon ━━━")
                Log.d(TAG, "  Room: ${shortcut.roomName}")
                Log.d(TAG, "  Avatar URL: ${shortcut.roomAvatarUrl}")
                
                // Only use cached avatar - don't download to avoid blocking
                val cachedFile = MediaCache.getCachedFile(context, shortcut.roomAvatarUrl)
                Log.d(TAG, "  Cached file: ${cachedFile?.absolutePath}")
                Log.d(TAG, "  File exists: ${cachedFile?.exists()}")
                
                if (cachedFile != null && cachedFile.exists()) {
                    Log.d(TAG, "  ✓ Avatar is in cache, loading bitmap...")
                    // Create circular bitmap from cached file
                    val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    Log.d(TAG, "  Bitmap loaded: ${bitmap != null} (${bitmap?.width}x${bitmap?.height})")
                    
                    if (bitmap != null) {
                        val circularBitmap = getCircularBitmap(bitmap)
                        Log.d(TAG, "  Circular bitmap created: ${circularBitmap != null}")
                        
                        if (circularBitmap != null) {
                            Log.d(TAG, "  ✓✓✓ SUCCESS: Using circular bitmap for shortcut icon")
                            Icon.createWithAdaptiveBitmap(circularBitmap)
                        } else {
                            Log.e(TAG, "  ✗✗✗ FAILED: getCircularBitmap returned null, creating fallback with initials")
                            createFallbackShortcutIcon(shortcut.roomName, shortcut.roomId)
                        }
                    } else {
                        Log.e(TAG, "  ✗✗✗ FAILED: BitmapFactory.decodeFile returned null, creating fallback with initials")
                        createFallbackShortcutIcon(shortcut.roomName, shortcut.roomId)
                    }
                } else {
                    Log.w(TAG, "  ✗✗✗ FAILED: Avatar not in cache, creating fallback with initials")
                    createFallbackShortcutIcon(shortcut.roomName, shortcut.roomId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "  ✗✗✗ EXCEPTION: Error loading avatar for shortcut, creating fallback", e)
                e.printStackTrace()
                createFallbackShortcutIcon(shortcut.roomName, shortcut.roomId)
            }
        } else {
            Log.w(TAG, "━━━ No room avatar URL provided, creating fallback with initials ━━━")
            createFallbackShortcutIcon(shortcut.roomName, shortcut.roomId)
        }
        
        return ShortcutInfo.Builder(context, shortcut.roomId)
            .setShortLabel(shortcut.roomName)
            //.setLongLabel(shortcut.roomName)
            .setIcon(icon)
            .setIntent(intent)
            .setRank(0) // Simple rank, can be improved later
            .setCategories(setOf("android.shortcut.conversation")) // Use standard conversation category
            .setLongLived(true)
            .build()
    }
    
    /**
     * Create conversation persons for notification grouping
     */
    fun createConversationPerson(roomId: String, roomName: String, avatarUrl: String?): Person {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Person.Builder()
                .setKey(roomId)
                .setName(roomName)
                .setIcon(createPersonIcon(avatarUrl))
                .build()
        } else {
            Person.Builder()
                .setKey(roomId)
                .setName(roomName)
                .build()
        }
    }
    
    /**
     * Create person icon from avatar URL
     */
    private fun createPersonIcon(avatarUrl: String?): Icon? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && avatarUrl != null) {
            try {
                // For now, use default icon since we can't call suspend function here
                // In a real implementation, you'd need to load this asynchronously
                Icon.createWithResource(context, R.mipmap.ic_launcher)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating person avatar", e)
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Load bitmap from URL using Coil with MediaCache integration
     */
    private suspend fun loadBitmapFromUrl(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Check if we have a cached version first
            val cachedFile = if (url.startsWith("mxc://")) {
                MediaCache.getCachedFile(context, url)
            } else null
            
            val imageUrl = if (cachedFile != null && cachedFile.exists()) {
                Log.d(TAG, "Using cached bitmap file: ${cachedFile.absolutePath}")
                cachedFile.absolutePath
            } else {
                // Convert URL to proper format and get HTTP URL
                val httpUrl = when {
                    url.startsWith("mxc://") -> {
                        AvatarUtils.mxcToHttpUrl(url, homeserverUrl) ?: return@withContext null
                    }
                    url.startsWith("_gomuks/") -> {
                        "$homeserverUrl/$url"
                    }
                    else -> {
                        url
                    }
                }
                
                Log.d(TAG, "Loading bitmap from: $httpUrl")
                
                // Download and cache if not cached
                if (url.startsWith("mxc://")) {
                    val downloadedFile = MediaCache.downloadAndCache(context, url, httpUrl, authToken)
                    if (downloadedFile != null) {
                        downloadedFile.absolutePath
                    } else {
                        Log.e(TAG, "Failed to download and cache bitmap")
                        return@withContext null
                    }
                } else {
                    httpUrl
                }
            }
            
            // Use shared ImageLoader singleton with custom User-Agent
            val imageLoader = ImageLoaderSingleton.get(context)
            
            // Load bitmap using Coil
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    if (cachedFile == null) {
                        addHeader("Cookie", "gomuks_auth=$authToken")
                    }
                }
                .build()
            
            val drawable = imageLoader.execute(request).drawable
            if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else {
                Log.w(TAG, "Failed to get bitmap from Coil drawable")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URL: $url", e)
            null
        }
    }
    
    /**
     * Remove all conversation shortcuts
     */
    fun clearConversationShortcuts() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            try {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                shortcutManager.dynamicShortcuts = emptyList()
                Log.d(TAG, "Cleared all conversation shortcuts")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing shortcuts", e)
            }
        }
    }
    
    /**
     * Update a specific shortcut when new message arrives
     * 
     * NOTE: We don't re-publish existing shortcuts here because it causes Android system warnings:
     * "Re-publishing ShortcutInfo returned by server is not supported. Some information such as icon may lost from shortcut."
     * 
     * Instead, shortcuts are updated by the regular updateConversationShortcuts() flow which creates
     * fresh ShortcutInfo objects with proper icons. This is called periodically from AppViewModel
     * on sync updates.
     */
    fun updateShortcutForNewMessage(roomId: String, message: String, timestamp: Long) {
        // This function is deprecated and does nothing to avoid Android system warnings
        // Regular shortcut updates via updateConversationShortcuts() handle everything properly
        Log.d(TAG, "updateShortcutForNewMessage called for $roomId - using regular update flow instead")
    }
    
    /**
     * Clear unread count for a conversation (mark as read)
     * 
     * DEPRECATED: This function causes "Re-publishing ShortcutInfo" warnings and loses icons!
     * The system doesn't support re-publishing shortcuts returned by getShortcuts().
     * Unread state is now cleared automatically by our regular shortcut updates.
     */
    @Deprecated("Causes icon loss. Unread clearing handled automatically by shortcut updates.")
    fun clearUnreadCount(roomId: String) {
        // DO NOT USE - causes system warning and icon loss
        Log.w(TAG, "clearUnreadCount() called but deprecated - unread clearing happens automatically")
    }
    
    /**
     * Create fallback shortcut icon with initials (AndroidX IconCompat version)
     */
    private fun createFallbackShortcutIconCompat(displayName: String?, userId: String): IconCompat {
        return try {
            // Get color and character using AvatarUtils
            val colorHex = AvatarUtils.getUserColor(userId)
            val character = AvatarUtils.getFallbackCharacter(displayName, userId)
            
            // Parse hex color
            val color = try {
                android.graphics.Color.parseColor("#$colorHex")
            } catch (e: Exception) {
                android.graphics.Color.parseColor("#d991de") // Fallback color
            }
            
            // Create bitmap
            val size = 128
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            
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
                
                // Center text vertically
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(character, 0, character.length, textBounds)
                val y = size / 2f + textBounds.height() / 2f
                
                canvas.drawText(character, size / 2f, y, textPaint)
            }
            
            // Make it circular
            val circularBitmap = getCircularBitmap(bitmap)
            if (circularBitmap != null) {
                IconCompat.createWithAdaptiveBitmap(circularBitmap)
            } else {
                IconCompat.createWithResource(context, R.drawable.matrix)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fallback shortcut icon", e)
            IconCompat.createWithResource(context, R.drawable.matrix)
        }
    }
    
    /**
     * Create fallback shortcut icon with initials (platform Icon version - kept for compatibility)
     */
    private fun createFallbackShortcutIcon(displayName: String?, userId: String): Icon {
        return try {
            // Get color and character using AvatarUtils
            val colorHex = AvatarUtils.getUserColor(userId)
            val character = AvatarUtils.getFallbackCharacter(displayName, userId)
            
            // Parse hex color
            val color = try {
                android.graphics.Color.parseColor("#$colorHex")
            } catch (e: Exception) {
                android.graphics.Color.parseColor("#d991de") // Fallback color
            }
            
            // Create bitmap
            val size = 128
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            
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
                
                // Center text vertically
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(character, 0, character.length, textBounds)
                val y = size / 2f + textBounds.height() / 2f
                
                canvas.drawText(character, size / 2f, y, textPaint)
            }
            
            // Make it circular
            val circularBitmap = getCircularBitmap(bitmap)
            if (circularBitmap != null) {
                Icon.createWithAdaptiveBitmap(circularBitmap)
            } else {
                Icon.createWithResource(context, R.drawable.matrix)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fallback shortcut icon", e)
            Icon.createWithResource(context, R.drawable.matrix)
        }
    }
}
