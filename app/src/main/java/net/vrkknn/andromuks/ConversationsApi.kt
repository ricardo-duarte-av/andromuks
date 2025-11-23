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
import net.vrkknn.andromuks.BuildConfig

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
        private const val SHORTCUT_UPDATE_DEBOUNCE_MS = 30000L // 30 seconds debounce
        private const val MIN_SHORTCUT_UPDATE_INTERVAL_MS = 300000L // 5 minutes: cooldown after actual update to prevent spam (reduced from 1 hour to allow avatar updates)
    }
    
    // Debouncing mechanism to prevent excessive shortcut updates
    private var lastShortcutUpdateTime = 0L
    private var lastShortcutUpdateCompletedTime = 0L // Track when update actually finished
    private var pendingShortcutUpdate: kotlinx.coroutines.Job? = null
    
    // Cache to track existing shortcuts and avoid unnecessary updates
    private var lastShortcutData: Map<String, ConversationShortcut> = emptyMap()
    private var lastShortcutHash: Int = 0
    // Stable-state caches to prevent needless updates (ignore unread/timestamps)
    // Use Set for order-independent comparison
    private var lastShortcutStableIds: Set<String> = emptySet()
    private var lastNameAvatar: Map<String, Pair<String, String?>> = emptyMap()
    private val lastAvatarCachePresence: MutableMap<String, Boolean> = mutableMapOf()
    
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
        
        if (avatarUrl.isNullOrEmpty()) {
            Log.w(TAG, "Avatar URL is null or empty, returning null")
            return@withContext null
        }
        
        return@withContext try {
            // Check if we have a cached version first
            val cachedFile = MediaCache.getCachedFile(context, avatarUrl)
            
            val imageUrl = if (cachedFile != null) {
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
            //Log.d(TAG, "Loaded drawable type: ${drawable?.javaClass?.simpleName ?: "null"}")
            val bitmap = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> {
                    //Log.d(TAG, "Got BitmapDrawable, bitmap size: ${drawable.bitmap.width}x${drawable.bitmap.height}")
                    drawable.bitmap
                }
                is android.graphics.drawable.AnimationDrawable -> {
                    //Log.d(TAG, "Got AnimationDrawable with ${drawable.numberOfFrames} frames")
                    // For animated GIFs, get the first frame
                    if (drawable.numberOfFrames > 0) {
                        val firstFrame = drawable.getFrame(0)
                        if (firstFrame is android.graphics.drawable.BitmapDrawable) {
                            //Log.d(TAG, "Got first frame as BitmapDrawable, bitmap size: ${firstFrame.bitmap.width}x${firstFrame.bitmap.height}")
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
            //Log.d(TAG, "Circular bitmap result: ${circularBitmap != null}")
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
            //Log.d(TAG, "Removed shortcut for room: $roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove shortcut for room: $roomId", e)
        }
    }
    
    /**
     * Update conversation shortcuts based on recent rooms
     * Returns immediately for non-blocking calls with debouncing to prevent UI freeze
     */
    fun updateConversationShortcuts(rooms: List<RoomItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            //Log.d(TAG, "updateConversationShortcuts called with ${rooms.size} rooms, realMatrixHomeserverUrl: $realMatrixHomeserverUrl")
            
            // Cancel any pending update
            pendingShortcutUpdate?.cancel()
            
            // Debounce updates to prevent excessive shortcut operations
            val currentTime = System.currentTimeMillis()
            val timeSinceLastUpdate = currentTime - lastShortcutUpdateTime
            
            if (timeSinceLastUpdate < SHORTCUT_UPDATE_DEBOUNCE_MS) {
                //Log.d(TAG, "Debouncing shortcut update (${timeSinceLastUpdate}ms since last update)")
                pendingShortcutUpdate = CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(SHORTCUT_UPDATE_DEBOUNCE_MS - timeSinceLastUpdate)
                    try {
                        val shortcuts = createShortcutsFromRooms(rooms)
                        updateShortcuts(shortcuts)
                        lastShortcutUpdateTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating conversation shortcuts (debounced)", e)
                    }
                }
            } else {
                // Update immediately
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val shortcuts = createShortcutsFromRooms(rooms)
                        updateShortcuts(shortcuts)
                        lastShortcutUpdateTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating conversation shortcuts", e)
                    }
                }
            }
        }
    }
    
    /**
     * Update conversation shortcuts synchronously - waits for completion
     * Used when we need the shortcut to exist before showing notification
     * Bypasses debouncing for immediate updates
     */
    suspend fun updateConversationShortcutsSync(rooms: List<RoomItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            //Log.d(TAG, "updateConversationShortcutsSync called with ${rooms.size} rooms (bypassing debounce)")
            
            // Cancel any pending debounced update
            pendingShortcutUpdate?.cancel()
            
            withContext(Dispatchers.IO) {
                try {
                    val shortcuts = createShortcutsFromRooms(rooms)
                    updateShortcuts(shortcuts)
                    lastShortcutUpdateTime = System.currentTimeMillis()
                    //Log.d(TAG, "Synchronous shortcut update completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating conversation shortcuts synchronously", e)
                }
            }
        }
    }
    
    /**
     * Force immediate shortcut update without debouncing
     * Used for critical updates like notifications
     */
    fun updateConversationShortcutsImmediate(rooms: List<RoomItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            //Log.d(TAG, "updateConversationShortcutsImmediate called with ${rooms.size} rooms (bypassing debounce)")
            
            // Cancel any pending debounced update
            pendingShortcutUpdate?.cancel()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val shortcuts = createShortcutsFromRooms(rooms)
                    updateShortcuts(shortcuts)
                    lastShortcutUpdateTime = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating conversation shortcuts immediately", e)
                }
            }
        }
    }
    
    /**
     * BATTERY OPTIMIZATION: Update shortcuts incrementally from sync_complete rooms only
     * Implements lightweight workflow: only processes rooms that changed in sync_complete (typically 2-3 rooms)
     * Never sorts all 588 rooms - much more efficient than the old approach
     * 
     * Workflow:
     * 1. Extract rooms from sync_complete
     * 2. For each room:
     *    - If already in shortcuts: update and move to top (pushDynamicShortcut handles this)
     *    - If not in shortcuts: remove oldest if full, then add new
     * 
     * @param syncRooms List of rooms from sync_complete (only changed rooms, typically 2-3)
     */
    fun updateShortcutsFromSyncRooms(syncRooms: List<RoomItem>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }
        
        if (syncRooms.isEmpty()) {
            return // Nothing to process
        }
        
        // Cancel any pending debounced update
        pendingShortcutUpdate?.cancel()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get current shortcut count from our cache
                val currentShortcutCount = lastShortcutStableIds.size
                
                // Process each room from sync_complete
                for (room in syncRooms) {
                    // Skip rooms without valid timestamp (they're not active)
                    if (room.sortingTimestamp == null || room.sortingTimestamp <= 0) {
                        continue
                    }
                    
                    val isInShortcuts = lastShortcutStableIds.contains(room.id)
                    
                    if (isInShortcuts) {
                        // Room already in shortcuts - update and move to top
                        // pushDynamicShortcut() automatically moves to top when called
                        updateSingleShortcut(room)
                    } else {
                        // Room not in shortcuts
                        if (currentShortcutCount < MAX_SHORTCUTS) {
                            // Not full yet - just add
                            addShortcut(room)
                        } else {
                            // Full - remove oldest, then add new
                            removeOldestShortcut()
                            addShortcut(room)
                        }
                    }
                }
                
                lastShortcutUpdateTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating shortcuts from sync rooms", e)
            }
        }
    }
    
    /**
     * Update a single shortcut if it needs updating (name/avatar changed)
     */
    private suspend fun updateSingleShortcut(room: RoomItem) {
        val existingShortcut = lastShortcutData[room.id]
        
        // Check if shortcut needs update (name/avatar changed)
        val needsUpdate = if (existingShortcut != null) {
            val nameChanged = existingShortcut.roomName != room.name
            val avatarChanged = existingShortcut.roomAvatarUrl != room.avatarUrl
            val avatarNeedsDownload = room.avatarUrl?.let { url ->
                MediaCache.getCachedFile(context, url) == null
            } ?: false
            
            nameChanged || avatarChanged || avatarNeedsDownload
        } else {
            true // No existing shortcut data, update it
        }
        
        if (needsUpdate) {
            val shortcut = roomToShortcut(room)
            val shortcutInfoCompat = createShortcutInfoCompat(shortcut)
            
            // Check if avatar is in cache AFTER createShortcutInfoCompat (which may have downloaded it)
            val avatarInCache = room.avatarUrl?.let { url ->
                MediaCache.getCachedFile(context, url) != null
            } ?: false
            
            val previouslyCached = lastAvatarCachePresence[room.id] ?: false
            
            // Only refresh icon when avatar transitions from not-cached -> cached
            if (avatarInCache && !previouslyCached) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Removing old shortcut to refresh icon (avatar cache became available)")
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(room.id))
            }
            
            // pushDynamicShortcut() automatically moves shortcut to top
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcutInfoCompat)
            
            // Update cache
            lastShortcutData = lastShortcutData + (room.id to shortcut)
            lastShortcutStableIds = lastShortcutStableIds + room.id
            lastNameAvatar = lastNameAvatar + (room.id to (room.name to room.avatarUrl))
            lastAvatarCachePresence[room.id] = avatarInCache
            
            lastShortcutUpdateCompletedTime = System.currentTimeMillis()
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Updated shortcut for room: ${room.name} (moved to top)")
        } else {
            // Still move to top even if no update needed (just push again)
            val shortcut = roomToShortcut(room)
            val shortcutInfoCompat = createShortcutInfoCompat(shortcut)
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcutInfoCompat)
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Moved shortcut to top for room: ${room.name} (no update needed)")
        }
    }
    
    /**
     * Add a new shortcut (assumes we have space or oldest was already removed)
     */
    private suspend fun addShortcut(room: RoomItem) {
        val shortcut = roomToShortcut(room)
        val shortcutInfoCompat = createShortcutInfoCompat(shortcut)
        
        // pushDynamicShortcut() automatically adds to top
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcutInfoCompat)
        
        // Update cache
        lastShortcutData = lastShortcutData + (room.id to shortcut)
        lastShortcutStableIds = lastShortcutStableIds + room.id
        lastNameAvatar = lastNameAvatar + (room.id to (room.name to room.avatarUrl))
        
        val avatarInCache = room.avatarUrl?.let { url ->
            MediaCache.getCachedFile(context, url) != null
        } ?: false
        lastAvatarCachePresence[room.id] = avatarInCache
        
        lastShortcutUpdateCompletedTime = System.currentTimeMillis()
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Added shortcut for room: ${room.name}")
    }
    
    /**
     * Remove the oldest shortcut (by timestamp)
     */
    private suspend fun removeOldestShortcut() {
        if (lastShortcutData.isEmpty()) {
            return
        }
        
        // Find shortcut with oldest timestamp
        val oldestShortcut = lastShortcutData.values.minByOrNull { it.timestamp }
        
        if (oldestShortcut != null) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(oldestShortcut.roomId))
            
            // Update cache
            lastShortcutData = lastShortcutData - oldestShortcut.roomId
            lastShortcutStableIds = lastShortcutStableIds - oldestShortcut.roomId
            lastNameAvatar = lastNameAvatar - oldestShortcut.roomId
            lastAvatarCachePresence.remove(oldestShortcut.roomId)
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Removed oldest shortcut: ${oldestShortcut.roomName}")
        }
    }
    
    /**
     * Convert RoomItem to ConversationShortcut
     */
    private fun roomToShortcut(room: RoomItem): ConversationShortcut {
        return ConversationShortcut(
            roomId = room.id,
            roomName = room.name,
            roomAvatarUrl = room.avatarUrl,
            lastMessage = room.messagePreview,
            unreadCount = room.unreadCount ?: 0,
            timestamp = room.sortingTimestamp ?: System.currentTimeMillis()
        )
    }
    
    /**
     * Create shortcuts from room list
     * Prioritizes rooms with recent activity (within last 30 days) or unread messages
     * NOTE: This is the OLD approach that sorts all rooms. Use updateShortcutsFromSyncRooms() for better performance.
     */
    private suspend fun createShortcutsFromRooms(rooms: List<RoomItem>): List<ConversationShortcut> {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000) // 30 days in milliseconds
        
        // Filter and prioritize rooms:
        // 1. Must have a valid timestamp
        // 2. Prioritize: unread > recent activity (last 30 days) > older activity
        val recentRooms = rooms
            .filter { it.sortingTimestamp != null && it.sortingTimestamp > 0 }
            .sortedWith(compareByDescending<RoomItem> { room ->
                // Primary sort: unread count (rooms with unread come first)
                room.unreadCount ?: 0
            }.thenByDescending { room ->
                // Secondary sort: recent activity (within 30 days gets priority)
                val timestamp = room.sortingTimestamp ?: 0L
                if (timestamp >= thirtyDaysAgo) {
                    // Recent activity: use timestamp as-is
                    timestamp
                } else {
                    // Old activity: reduce priority by subtracting a large offset
                    // This ensures recent rooms always come before old ones
                    timestamp - (365L * 24 * 60 * 60 * 1000) // Subtract 1 year to deprioritize
                }
            })
            .take(MAX_SHORTCUTS)
        
        if (BuildConfig.DEBUG && recentRooms.isNotEmpty()) {
            Log.d(TAG, "Creating shortcuts for ${recentRooms.size} rooms:")
            recentRooms.forEach { room ->
                val daysAgo = (now - (room.sortingTimestamp ?: 0L)) / (24 * 60 * 60 * 1000)
                Log.d(TAG, "  - ${room.name} (${room.id}): ${daysAgo} days ago, unread: ${room.unreadCount ?: 0}")
            }
        }
        
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
     * Check if shortcuts need updating by comparing with cached data
     * Returns: Pair(needsUpdate: Boolean, isOrderOnlyChange: Boolean)
     */
    private suspend fun shortcutsNeedUpdate(newShortcuts: List<ConversationShortcut>): Pair<Boolean, Boolean> {
        // Compare set of IDs (order-independent) to avoid churn from reordering
        val newIdsSet = newShortcuts.map { it.roomId }.toSet()
        val idsChanged = newIdsSet != lastShortcutStableIds

        // Compare name/avatar for same IDs
        var nameAvatarChanged = false
        if (!idsChanged) {
            // Only check name/avatar if same IDs
            for (s in newShortcuts) {
                val prev = lastNameAvatar[s.roomId]
                if (prev == null || prev.first != s.roomName || prev.second != s.roomAvatarUrl) {
                    nameAvatarChanged = true
                    break
                }
            }
        }

        // Check if any shortcuts have avatars that need to be downloaded
        // This forces an update even if IDs/URLs haven't changed, to refresh missing avatars
        var avatarsNeedDownload = false
        if (!idsChanged && !nameAvatarChanged) {
            for (s in newShortcuts) {
                if (s.roomAvatarUrl != null) {
                    val cachedFile = MediaCache.getCachedFile(context, s.roomAvatarUrl)
                    if (cachedFile == null || !cachedFile.exists()) {
                        // Avatar URL exists but not cached - need to download
                        avatarsNeedDownload = true
                        if (BuildConfig.DEBUG) Log.d(TAG, "Shortcut ${s.roomId} has avatar URL but not cached - forcing update")
                        break
                    }
                }
            }
        }

        // If set/name/avatar unchanged AND avatars are all cached, skip update
        if (!idsChanged && !nameAvatarChanged && !avatarsNeedDownload) {
            //Log.d(TAG, "Shortcuts stable (set unchanged, skipping update)")
            return false to false
        }

        //Log.d(TAG, "Shortcuts will update (idsChanged=$idsChanged, nameAvatarChanged=$nameAvatarChanged, avatarsNeedDownload=$avatarsNeedDownload)")
        return true to false
    }
    
    /**
     * Update shortcuts in the system
     * Intelligently merges with existing shortcuts to preserve good icons
     * Runs entirely in background to avoid UI blocking
     * Only updates shortcuts that actually changed
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun updateShortcuts(shortcuts: List<ConversationShortcut>) {
        try {
            // Check if shortcuts actually need updating
            val (needsUpdate, _) = shortcutsNeedUpdate(shortcuts)
            if (!needsUpdate) {
                //Log.d(TAG, "No shortcut changes detected, skipping update")
                return
            }
            
            // Check if this update is needed for missing avatars (bypass rate limiting)
            val hasMissingAvatars = shortcuts.any { shortcut ->
                shortcut.roomAvatarUrl != null && 
                MediaCache.getCachedFile(context, shortcut.roomAvatarUrl) == null
            }
            
            // Rate limiting: enforce cooldown after last completed update
            // BUT: bypass rate limiting if avatars need to be downloaded
            val now = System.currentTimeMillis()
            val timeSinceLastCompleted = now - lastShortcutUpdateCompletedTime
            if (!hasMissingAvatars && timeSinceLastCompleted < MIN_SHORTCUT_UPDATE_INTERVAL_MS) {
                //Log.d(TAG, "Rate-limiting shortcut update (${timeSinceLastCompleted}ms since last completed, min=${MIN_SHORTCUT_UPDATE_INTERVAL_MS}ms)")
                return
            }
            
            if (hasMissingAvatars && BuildConfig.DEBUG) {
                Log.d(TAG, "Bypassing rate limit to download missing avatars for shortcuts")
            }

            //Log.d(TAG, "Updating ${shortcuts.size} shortcuts using pushDynamicShortcut() (background thread)")
            
            // Update cache (use Set for order-independent comparison)
            val newIdsSet = shortcuts.map { it.roomId }.toSet()
            lastShortcutData = shortcuts.associateBy { it.roomId }
            lastShortcutHash = lastShortcutData.hashCode()
            lastShortcutStableIds = newIdsSet
            lastNameAvatar = shortcuts.associate { it.roomId to (it.roomName to it.roomAvatarUrl) }
            
            for (shortcut in shortcuts) {
                try {
                    //Log.d(TAG, "Updating shortcut - roomId: '${shortcut.roomId}', roomName: '${shortcut.roomName}'")
                    
                    // Check if avatar was previously cached (before download attempt)
                    val previouslyCached = lastAvatarCachePresence[shortcut.roomId] ?: false
                    
                    // Create ShortcutInfoCompat (AndroidX version)
                    // This will now download and cache the avatar if not already cached
                    val shortcutInfoCompat = createShortcutInfoCompat(shortcut)
                    
                    // Check if avatar is in cache AFTER createShortcutInfoCompat (which may have downloaded it)
                    val avatarInCache = shortcut.roomAvatarUrl?.let { url ->
                        MediaCache.getCachedFile(context, url) != null
                    } ?: false
                    
                    //Log.d(TAG, "  Avatar in cache (after download attempt): $avatarInCache, previously: $previouslyCached")
                    
                    // Only refresh icon when avatar transitions from not-cached -> cached
                    // This ensures shortcuts get updated with real avatars when they become available
                    if (avatarInCache && !previouslyCached) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "  Removing old shortcut to refresh icon (avatar cache became available)")
                        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcut.roomId))
                    }
                    
                    // Push/update the shortcut (conversation-optimized API)
                    // This preserves other shortcuts automatically!
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcutInfoCompat)
                    //Log.d(TAG, "  ✓ Shortcut pushed successfully")

                    // Record avatar cache presence for next comparison
                    lastAvatarCachePresence[shortcut.roomId] = avatarInCache
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating shortcut for room: ${shortcut.roomName}", e)
                }
            }
            
            // Mark update as completed for cooldown tracking
            lastShortcutUpdateCompletedTime = System.currentTimeMillis()
            //Log.d(TAG, "Finished updating shortcuts")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateShortcuts", e)
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
        //Log.d(TAG, "Creating conversation shortcut with matrix URI: $matrixUri")
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(matrixUri),
            context,
            MainActivity::class.java
        ).apply {
            putExtra("room_id", shortcut.roomId)
            putExtra("direct_navigation", true) // Flag for optimized processing
            putExtra("from_shortcut", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val icon = if (shortcut.roomAvatarUrl != null) {
            try {
                // Check if we have a cached version first
                var cachedFile = MediaCache.getCachedFile(context, shortcut.roomAvatarUrl)
                
                // If not cached, download and cache it (similar to EnhancedNotificationDisplay)
                if (cachedFile == null || !cachedFile.exists()) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Avatar not in MediaCache, downloading for shortcut: ${shortcut.roomId}")
                    
                    // Convert MXC URL to HTTP URL
                    val httpUrl = when {
                        shortcut.roomAvatarUrl.startsWith("mxc://") -> {
                            AvatarUtils.mxcToHttpUrl(shortcut.roomAvatarUrl, homeserverUrl)
                        }
                        shortcut.roomAvatarUrl.startsWith("_gomuks/") -> {
                            "$homeserverUrl/${shortcut.roomAvatarUrl}"
                        }
                        else -> {
                            shortcut.roomAvatarUrl
                        }
                    }
                    
                    if (httpUrl != null) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Downloading avatar for shortcut ${shortcut.roomId} from: $httpUrl")
                        // Download and cache using existing MediaCache infrastructure
                        cachedFile = MediaCache.downloadAndCache(context, shortcut.roomAvatarUrl, httpUrl, authToken)
                        if (cachedFile != null) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "✓ Successfully downloaded and cached avatar for shortcut: ${shortcut.roomId} (${cachedFile.length()} bytes)")
                        } else {
                            Log.w(TAG, "✗ Failed to download avatar for shortcut: ${shortcut.roomId} from: $httpUrl")
                        }
                    } else {
                        Log.w(TAG, "Failed to convert avatar URL to HTTP URL: ${shortcut.roomAvatarUrl}")
                    }
                }
                
                // Load bitmap from cached file (or fallback if download failed)
                if (cachedFile != null && cachedFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    
                    if (bitmap != null) {
                        val circularBitmap = getCircularBitmap(bitmap)
                        
                        if (circularBitmap != null) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "✓✓✓ SUCCESS: Created shortcut icon with avatar for: ${shortcut.roomId}")
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
                    Log.w(TAG, "  ✗✗✗ FAILED: Avatar not in cache and download failed, creating fallback with initials")
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
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(matrixUri),
            context,
            MainActivity::class.java
        ).apply {
            putExtra("room_id", shortcut.roomId)
            putExtra("direct_navigation", true) // Flag for optimized processing
            putExtra("from_shortcut", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val icon = if (shortcut.roomAvatarUrl != null) {
            try {
                // Check if we have a cached version first
                var cachedFile = MediaCache.getCachedFile(context, shortcut.roomAvatarUrl)
                
                // If not cached, download and cache it (similar to EnhancedNotificationDisplay)
                if (cachedFile == null || !cachedFile.exists()) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Avatar not in MediaCache, downloading for shortcut: ${shortcut.roomId}")
                    
                    // Convert MXC URL to HTTP URL
                    val httpUrl = when {
                        shortcut.roomAvatarUrl.startsWith("mxc://") -> {
                            AvatarUtils.mxcToHttpUrl(shortcut.roomAvatarUrl, homeserverUrl)
                        }
                        shortcut.roomAvatarUrl.startsWith("_gomuks/") -> {
                            "$homeserverUrl/${shortcut.roomAvatarUrl}"
                        }
                        else -> {
                            shortcut.roomAvatarUrl
                        }
                    }
                    
                    if (httpUrl != null) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Downloading avatar for shortcut ${shortcut.roomId} from: $httpUrl")
                        // Download and cache using existing MediaCache infrastructure
                        cachedFile = MediaCache.downloadAndCache(context, shortcut.roomAvatarUrl, httpUrl, authToken)
                        if (cachedFile != null) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "✓ Successfully downloaded and cached avatar for shortcut: ${shortcut.roomId} (${cachedFile.length()} bytes)")
                        } else {
                            Log.w(TAG, "✗ Failed to download avatar for shortcut: ${shortcut.roomId} from: $httpUrl")
                        }
                    } else {
                        Log.w(TAG, "Failed to convert avatar URL to HTTP URL: ${shortcut.roomAvatarUrl}")
                    }
                }
                
                // Load bitmap from cached file (or fallback if download failed)
                if (cachedFile != null && cachedFile.exists()) {
                    // Create circular bitmap from cached file
                    val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    
                    if (bitmap != null) {
                        val circularBitmap = getCircularBitmap(bitmap)
                        
                        if (circularBitmap != null) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "✓✓✓ SUCCESS: Created shortcut icon with avatar for: ${shortcut.roomId}")
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
                    Log.w(TAG, "  ✗✗✗ FAILED: Avatar not in cache and download failed, creating fallback with initials")
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
                
                // Clear cache
                lastShortcutData = emptyMap()
                lastShortcutHash = 0
                lastShortcutStableIds = emptySet()
                lastNameAvatar = emptyMap()
                lastAvatarCachePresence.clear()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing shortcuts", e)
            }
        }
    }
    
    /**
     * Clear shortcut cache (useful when user changes servers or logs out)
     */
    fun clearShortcutCache() {
        lastShortcutData = emptyMap()
        lastShortcutHash = 0
        lastShortcutStableIds = emptySet()
        lastNameAvatar = emptyMap()
        lastAvatarCachePresence.clear()
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
        if (BuildConfig.DEBUG) Log.d(TAG, "updateShortcutForNewMessage called for $roomId - using regular update flow instead")
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
