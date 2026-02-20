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
     * Generate cache key from MXC URL (returns relative path with server subdirectory).
     * MXC URLs are immutable and their server/mediaId path is already filesystem-safe.
     * Uses subdirectories per server to avoid thousands of files in a single directory.
     * e.g. "mxc://aguiarvieira.pt/MGUPvTkAoIFxQBkqdQIlyvxP" → "aguiarvieira.pt/MGUPvTkAoIFxQBkqdQIlyvxP"
     */
    fun getCacheKey(mxcUrl: String): String {
        return mxcUrl.removePrefix("mxc://")
    }
    
    /**
     * Get the full cache file path, ensuring the server subdirectory exists.
     */
    private fun getCacheFile(context: Context, mxcUrl: String): File {
        val cacheDir = getCacheDir(context)
        val relativePath = getCacheKey(mxcUrl)
        val cacheFile = File(cacheDir, relativePath)
        
        // Ensure parent directory exists (server subdirectory)
        cacheFile.parentFile?.mkdirs()
        
        return cacheFile
    }
    
    // PERFORMANCE: In-memory set of mxcUrls known to exist on disk.
    // Avoids file I/O (File.exists()) on every scroll frame after first load.
    private val knownOnDisk = java.util.concurrent.ConcurrentHashMap<String, File>(256)
    
    /**
     * Get cached circular avatar file for MXC URL.
     * PERFORMANCE: Lock-free fast path — checks in-memory map first, falls back to
     * file I/O only once per mxcUrl. No Mutex contention during scrolling.
     * 
     * @param context Android context
     * @param mxcUrl MXC URL to look up
     * @return Cached file if exists, null otherwise
     */
    suspend fun getCachedFile(context: Context, mxcUrl: String): File? {
        // Fast path: already known to exist on disk
        knownOnDisk[mxcUrl]?.let { file ->
            if (file.exists()) return file
            // File was deleted externally — evict from map
            knownOnDisk.remove(mxcUrl)
        }
        
        // Slow path: check disk (still no Mutex needed for a read-only file existence check)
        val cacheFile = getCacheFile(context, mxcUrl)
        
        if (cacheFile.exists() && cacheFile.length() > 0) {
            knownOnDisk[mxcUrl] = cacheFile
            return cacheFile
        }
        
        return null
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
        val cacheFile = getCacheFile(context, mxcUrl)
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
                    
                    // PERFORMANCE: Register in lock-free lookup map so future reads skip file I/O
                    knownOnDisk[mxcUrl] = cacheFile
                    // Also update the in-memory URL resolution cache
                    AvatarUtils.updateResolvedUrl(mxcUrl, cacheFile.absolutePath)
                    
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
     * Recursively walks subdirectories to find all cached files.
     */
    private suspend fun ensureCacheSize(context: Context) = withContext(Dispatchers.IO) {
        val cacheDir = getCacheDir(context)
        
        // Recursively collect all files from subdirectories
        fun collectFiles(dir: File): List<File> {
            val files = mutableListOf<File>()
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    files.addAll(collectFiles(file))
                } else if (file.isFile) {
                    files.add(file)
                }
            }
            return files
        }
        
        val files = collectFiles(cacheDir)
        if (files.isEmpty()) return@withContext
        
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
     * Recursively deletes files from subdirectories.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val cacheDir = getCacheDir(context)
            
            // Recursively delete all files from subdirectories
            fun deleteFiles(dir: File): Pair<Int, Long> {
                var deletedCount = 0
                var deletedSize = 0L
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        val (count, size) = deleteFiles(file)
                        deletedCount += count
                        deletedSize += size
                        // Delete empty subdirectory
                        file.delete()
                    } else if (file.isFile) {
                        val size = file.length()
                        if (file.delete()) {
                            deletedCount++
                            deletedSize += size
                        }
                    }
                }
                return deletedCount to deletedSize
            }
            
            val (deletedCount, deletedSize) = deleteFiles(cacheDir)
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "CircleAvatarCache: Cleared $deletedCount files (${deletedSize / 1024 / 1024}MB)")
            }
        }
    }
}

