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
            
            // Create ImageLoader with GIF support
            val imageLoader = ImageLoader.Builder(context)
                .components {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
            
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
            
            // Create ImageLoader with GIF support
            val imageLoader = ImageLoader.Builder(context)
                .components {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
            
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
     * Create or update room shortcut (exactly like working Gomuks app)
     */
    suspend fun createOrUpdateRoomShortcut(
        roomId: String,
        roomName: String,
        roomAvatarUrl: String?,
        senderName: String
    ) {
        try {
            val isGroupRoom = roomName != senderName
            
            // Create intent for the room with proper matrix: URI
            val matrixUri = if (realMatrixHomeserverUrl.isNotEmpty()) {
                val serverHost = Uri.parse(realMatrixHomeserverUrl).host ?: ""
                "matrix:roomid/${roomId.substring(1)}?via=$serverHost"
            } else {
                "matrix:roomid/${roomId.substring(1)}"
            }
            Log.d(TAG, "Creating room shortcut with matrix URI: $matrixUri")
            val roomIntent = Intent(context, MainActivity::class.java).apply {
                setAction(Intent.ACTION_VIEW)
                setData(Uri.parse(matrixUri))
            }
            
            // Download room avatar and get first frame as circular bitmap
            Log.d(TAG, "Creating shortcut for room: $roomName, avatarUrl: $roomAvatarUrl")
            val circularRoomAvatar = getCircularBitmapFromUrl(roomAvatarUrl)
            Log.d(TAG, "Circular room avatar result: ${circularRoomAvatar != null}")
            val shortcutIcon = if (circularRoomAvatar != null) {
                Log.d(TAG, "Using room avatar for shortcut")
                IconCompat.createWithBitmap(circularRoomAvatar)
            } else {
                Log.d(TAG, "Using fallback icon for shortcut")
                IconCompat.createWithResource(context, R.drawable.ic_matrix_notification)
            }
            
            // Create shortcut for the room
            val shortcut = ShortcutInfoCompat.Builder(context, roomId)
                .setShortLabel(roomName)
                .setLongLabel(roomName)
                .setIcon(shortcutIcon)
                .setIntent(roomIntent)
                .setCategories(setOf("android.shortcut.conversation"))
                .setLongLived(true)
                .apply {
                    // setIsConversation() is only available on API 30+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setIsConversation()
                    }
                }
                .build()
            
            // Add or update the shortcut
            try {
                // Remove any previous shortcut with the same id (for icon update)
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(roomId))
                // Add the updated shortcut
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                Log.d(TAG, "Created/updated shortcut for room: $roomName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create shortcut for room: $roomName", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating room shortcut", e)
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
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun updateShortcuts(shortcuts: List<ConversationShortcut>) {
        withContext(Dispatchers.Main) {
            try {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                
                val shortcutInfos = shortcuts.map { shortcut ->
                    createShortcutInfo(shortcut)
                }
                
                shortcutManager.dynamicShortcuts = shortcutInfos
                Log.d(TAG, "Updated ${shortcutInfos.size} conversation shortcuts")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating shortcuts", e)
            }
        }
    }
    
    /**
     * Create a ShortcutInfo from ConversationShortcut
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
                Log.d(TAG, "Creating shortcut icon for room: ${shortcut.roomName}, avatarUrl: ${shortcut.roomAvatarUrl}")
                getCircularBitmapFromUrl(shortcut.roomAvatarUrl)?.let { bitmap ->
                    Log.d(TAG, "Using room avatar for shortcut icon")
                    Icon.createWithAdaptiveBitmap(bitmap)
                } ?: run {
                    Log.d(TAG, "Room avatar is null, using fallback icon")
                    Icon.createWithResource(context, R.drawable.ic_matrix_notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading avatar for shortcut", e)
                Icon.createWithResource(context, R.drawable.ic_matrix_notification)
            }
        } else {
            Log.d(TAG, "No room avatar URL, using fallback icon")
            Icon.createWithResource(context, R.drawable.ic_matrix_notification)
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
            
            // Create ImageLoader with GIF support
            val imageLoader = ImageLoader.Builder(context)
                .components {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
            
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
     */
    fun updateShortcutForNewMessage(roomId: String, message: String, timestamp: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                    val existingShortcuts = shortcutManager.dynamicShortcuts.toMutableList()
                    
                    // Find and update the shortcut for this room
                    val shortcutIndex = existingShortcuts.indexOfFirst { it.id == roomId }
                    if (shortcutIndex >= 0) {
                        // Move to top of list
                        val updatedShortcut = existingShortcuts[shortcutIndex]
                        existingShortcuts.removeAt(shortcutIndex)
                        existingShortcuts.add(0, updatedShortcut)
                        
                        shortcutManager.dynamicShortcuts = existingShortcuts
                        Log.d(TAG, "Updated shortcut for room: $roomId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating shortcut for new message", e)
                }
            }
        }
    }
}
