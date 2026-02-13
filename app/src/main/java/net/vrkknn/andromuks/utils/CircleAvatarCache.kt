package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Disk cache for room avatar thumbnails (square JPEG images).
 * 
 * This cache stores pre-scaled square avatar images to reduce decoding overhead
 * during fast scrolling in RoomListScreen. The UI uses GPU clipping (CircleShape)
 * which is much cheaper than CPU-based circular bitmap processing.
 * 
 * Cache hierarchy:
 * 1. CircleAvatarCache (pre-scaled square JPEG thumbnails)
 * 2. MediaCache (original images)
 * 3. Coil cache (original images)
 * 4. Network (download)
 */
object CircleAvatarCache {
    private const val TAG = "CircleAvatarCache"
    private const val CACHE_DIR_NAME = "circle_avatar_cache"
    private const val MAX_CACHE_SIZE = 200L * 1024 * 1024L // 200MB max cache size (smaller since JPEG)
    private const val TARGET_SIZE = 128 // Target size for cached square avatars (128x128 JPEG)
    
    private val cacheMutex = Mutex()
    private val inProgressCache = mutableSetOf<String>() // Track MXC URLs being cached
    
    /**
     * Get cache directory for circular avatars.
     */
    fun getCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    /**
     * Generate cache key from MXC URL (SHA-256 hash).
     */
    fun getCacheKey(mxcUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(mxcUrl.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Get cached circular avatar file for MXC URL.
     * 
     * @param context Android context
     * @param mxcUrl MXC URL to look up
     * @return Cached file if exists, null otherwise
     */
    suspend fun getCachedFile(context: Context, mxcUrl: String): File? = cacheMutex.withLock {
        val cacheKey = getCacheKey(mxcUrl)
        val cacheFile = File(getCacheDir(context), cacheKey)
        
        if (cacheFile.exists() && cacheFile.length() > 0) {
            // PERFORMANCE: Reduced logging to avoid log spam during fast scrolling
            // Logging removed - use logcat filters if needed for debugging
            return@withLock cacheFile
        }
        
        // PERFORMANCE: Reduced logging to avoid log spam during fast scrolling
        // Logging removed - use logcat filters if needed for debugging
        return@withLock null
    }
    
    /**
     * Create a square thumbnail from a bitmap (center-cropped and scaled).
     * 
     * OPTIMIZED: Crop to square first, then scale down to target size.
     * This processes fewer pixels than scaling first then cropping.
     * We save as JPEG (no transparency) which is smaller and faster than PNG.
     * The UI will use GPU clipping (CircleShape) which is much cheaper.
     * 
     * @param source Original bitmap
     * @param size Target size for the square thumbnail
     * @return Square bitmap
     */
    private fun createSquareThumbnail(source: Bitmap, size: Int): Bitmap {
        // Step 1: Crop to center square from original image
        val sourceSize = minOf(source.width, source.height)
        val startX = (source.width - sourceSize) / 2
        val startY = (source.height - sourceSize) / 2
        
        // Extract center square
        val squareBitmap = Bitmap.createBitmap(
            source,
            startX,
            startY,
            sourceSize,
            sourceSize
        )
        
        // Step 2: Scale down to target size if needed
        val finalBitmap = if (sourceSize != size) {
            Bitmap.createScaledBitmap(squareBitmap, size, size, true)
        } else {
            squareBitmap
        }
        
        // Clean up intermediate bitmap if we created it and it's not the final one
        if (squareBitmap != source && squareBitmap != finalBitmap) {
            squareBitmap.recycle()
        }
        
        return finalBitmap
    }
    
    /**
     * Cache a square avatar thumbnail from a source image URL.
     * 
     * Saves as JPEG (no transparency) which is smaller and faster than PNG.
     * The UI uses GPU clipping (CircleShape) which is much cheaper than CPU processing.
     * 
     * @param context Android context
     * @param mxcUrl MXC URL for the avatar
     * @param sourceImageUrl Source image URL (HTTP URL or file path)
     * @param imageLoader Coil ImageLoader instance
     * @param authToken Auth token for HTTP requests
     */
    suspend fun cacheCircularAvatar(
        context: Context,
        mxcUrl: String,
        sourceImageUrl: String,
        imageLoader: ImageLoader,
        authToken: String
    ) = withContext(Dispatchers.IO) {
        // Prevent duplicate caching operations
        if (inProgressCache.contains(mxcUrl)) {
            return@withContext
        }
        
        // Check if already cached (fast check before acquiring mutex)
        val cacheKey = getCacheKey(mxcUrl)
        val cacheFile = File(getCacheDir(context), cacheKey)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext
        }
        
        cacheMutex.withLock {
            // Double-check after lock
            if (inProgressCache.contains(mxcUrl)) return@withLock
            inProgressCache.add(mxcUrl)
        }
        
        try {
            // OPTIMIZATION: Use software bitmap config to avoid texture readback from GPU
            // This is critical for performance when saving to disk
            val request = ImageRequest.Builder(context)
                .data(sourceImageUrl)
                .apply {
                    if (sourceImageUrl.startsWith("http")) {
                        addHeader("Cookie", "gomuks_auth=$authToken")
                    }
                }
                .size(TARGET_SIZE)
                .allowHardware(false) // CRITICAL: Prevent Hardware bitmap to avoid slow copyPixelsToBuffer
                .bitmapConfig(Bitmap.Config.ARGB_8888) // Ensure compatible config
                .build()
            
            when (val result = imageLoader.execute(request)) {
                is SuccessResult -> {
                    // Extract bitmap
                    val sourceBitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext
                    
                    // Create square thumbnail (center-cropped and scaled)
                    // Note: Since we requested TARGET_SIZE in Coil, sourceBitmap might already be close to size
                    val squareBitmap = createSquareThumbnail(sourceBitmap, TARGET_SIZE)
                    
                    // Save to cache as JPEG (smaller, faster than PNG with transparency)
                    // UI uses GPU clipping (CircleShape) so we don't need transparency
                    FileOutputStream(cacheFile).use { out ->
                        squareBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    
                    // Clean up square bitmap if we created a new one
                    if (squareBitmap != sourceBitmap) {
                        squareBitmap.recycle()
                    }
                    
                    // Ensure cache size is within limits (occasional check)
                    if (Math.random() < 0.05) { // 5% chance to check cleanup to avoid overhead
                        ensureCacheSize(context)
                    }
                }
                is ErrorResult -> {
                    // Ignore errors silently
                }
            }
        } catch (e: Exception) {
            // Ignore errors
        } finally {
            cacheMutex.withLock {
                inProgressCache.remove(mxcUrl)
            }
        }
    }
    
    /**
     * Ensure cache size is within limits by evicting oldest files.
     */
    private suspend fun ensureCacheSize(context: Context) = withContext(Dispatchers.IO) {
        val cacheDir = getCacheDir(context)
        val files = cacheDir.listFiles() ?: return@withContext
        
        var totalSize = files.sumOf { it.length() }
        
        if (totalSize > MAX_CACHE_SIZE) {
            // Sort by last modified (oldest first)
            val sortedFiles = files.sortedBy { it.lastModified() }
            
            // Remove oldest files until we're under the limit
            for (file in sortedFiles) {
                if (totalSize <= MAX_CACHE_SIZE) break
                
                val fileSize = file.length()
                file.delete()
                totalSize -= fileSize
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "CircleAvatarCache: Evicted ${file.name} (${fileSize / 1024}KB)")
                }
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "CircleAvatarCache: Cache size after cleanup: ${totalSize / 1024 / 1024}MB")
            }
        }
    }
    
    /**
     * Clear all cached circular avatars.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val cacheDir = getCacheDir(context)
            val files = cacheDir.listFiles() ?: return@withLock
            
            var deletedCount = 0
            var deletedSize = 0L
            
            for (file in files) {
                val size = file.length()
                if (file.delete()) {
                    deletedCount++
                    deletedSize += size
                }
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "CircleAvatarCache: Cleared $deletedCount files (${deletedSize / 1024 / 1024}MB)")
            }
        }
    }
}

