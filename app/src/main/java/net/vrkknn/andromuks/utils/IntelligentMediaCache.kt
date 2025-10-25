package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Intelligent media cache with viewport prioritization.
 * 
 * This system provides:
 * - Viewport-aware caching that prioritizes visible content
 * - Smart cache eviction based on usage and visibility
 * - Intelligent cache size management
 * - Performance monitoring and statistics
 */
object IntelligentMediaCache {
    private const val TAG = "IntelligentMediaCache"
    private const val CACHE_DIR_NAME = "intelligent_media_cache"
    private const val MAX_CACHE_SIZE = 2L * 1024 * 1024 * 1024L // 2GB
    private const val VISIBLE_PRIORITY_BOOST = 1000L
    private const val RECENTLY_VIEWED_BOOST = 500L
    private const val ACCESS_COUNT_BOOST = 100L
    
    data class CacheEntry(
        val file: File,
        val mxcUrl: String,
        val size: Long,
        val lastAccessed: Long,
        val isVisible: Boolean = false,
        val accessCount: Int = 0,
        val priority: Long = 0,
        val fileType: String = "unknown"
    )
    
    // Thread-safe collections for cache management
    private val cacheEntries = ConcurrentHashMap<String, CacheEntry>()
    private val visibleMedia = ConcurrentHashMap<String, Boolean>()
    private val cacheMutex = Mutex()
    
    /**
     * Get cache directory for media files.
     */
    fun getCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    /**
     * Generate cache key from MXC URL.
     */
    fun getCacheKey(mxcUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(mxcUrl.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Update visibility status for media items.
     * 
     * @param visibleUrls Set of currently visible media URLs
     */
    suspend fun updateVisibility(visibleUrls: Set<String>) = cacheMutex.withLock {
        // Update visibility status
        visibleMedia.clear()
        visibleUrls.forEach { url ->
            visibleMedia[url] = true
        }
        
        // Update cache entries with new visibility status
        cacheEntries.values.forEach { entry ->
            val isVisible = visibleMedia.containsKey(entry.mxcUrl)
            if (entry.isVisible != isVisible) {
                cacheEntries[entry.mxcUrl] = entry.copy(
                    isVisible = isVisible,
                    priority = calculatePriority(entry, isVisible)
                )
            }
        }
        
        Log.d(TAG, "Updated visibility for ${visibleUrls.size} media items")
    }
    
    /**
     * Calculate priority score for cache entry.
     */
    private fun calculatePriority(entry: CacheEntry, isVisible: Boolean): Long {
        var priority = entry.accessCount * ACCESS_COUNT_BOOST
        priority += (System.currentTimeMillis() - entry.lastAccessed) / 1000L
        
        if (isVisible) {
            priority += VISIBLE_PRIORITY_BOOST
        }
        
        if (entry.accessCount > 0) {
            priority += RECENTLY_VIEWED_BOOST
        }
        
        return priority
    }
    
    /**
     * Get cached file for MXC URL.
     * 
     * @param context Android context
     * @param mxcUrl MXC URL to look up
     * @return Cached file if exists, null otherwise
     */
    suspend fun getCachedFile(context: Context, mxcUrl: String): File? = cacheMutex.withLock {
        val entry = cacheEntries[mxcUrl]
        if (entry != null && entry.file.exists()) {
            // Update access statistics
            val updatedEntry = entry.copy(
                lastAccessed = System.currentTimeMillis(),
                accessCount = entry.accessCount + 1,
                priority = calculatePriority(entry, entry.isVisible)
            )
            cacheEntries[mxcUrl] = updatedEntry
            
            Log.d(TAG, "Cache hit for $mxcUrl (access count: ${updatedEntry.accessCount})")
            return@withLock entry.file
        }
        
        Log.d(TAG, "Cache miss for $mxcUrl")
        return@withLock null
    }
    
    /**
     * Cache file with intelligent management.
     * 
     * @param context Android context
     * @param mxcUrl MXC URL for the file
     * @param file File to cache
     * @param fileType Type of file (image, video, audio, etc.)
     */
    suspend fun cacheFile(
        context: Context,
        mxcUrl: String,
        file: File,
        fileType: String = "unknown"
    ) = cacheMutex.withLock {
        val entry = CacheEntry(
            file = file,
            mxcUrl = mxcUrl,
            size = file.length(),
            lastAccessed = System.currentTimeMillis(),
            isVisible = visibleMedia.containsKey(mxcUrl),
            accessCount = 1,
            priority = calculatePriority(
                CacheEntry(file, mxcUrl, file.length(), System.currentTimeMillis()),
                visibleMedia.containsKey(mxcUrl)
            ),
            fileType = fileType
        )
        
        cacheEntries[mxcUrl] = entry
        Log.d(TAG, "Cached file for $mxcUrl (size: ${entry.size / 1024}KB, type: $fileType)")
        
        // Ensure cache size is within limits
        ensureCacheSize(context)
    }
    
    /**
     * Ensure cache size is within limits with intelligent eviction.
     */
    private suspend fun ensureCacheSize(context: Context) {
        val totalSize = cacheEntries.values.sumOf { it.size }
        if (totalSize <= MAX_CACHE_SIZE) return
        
        Log.d(TAG, "Cache size ${totalSize / 1024 / 1024}MB exceeds limit ${MAX_CACHE_SIZE / 1024 / 1024}MB, evicting...")
        
        // Sort by priority (lowest first for eviction)
        val sortedEntries = cacheEntries.values.sortedBy { it.priority }
        
        var currentSize = totalSize
        var evictedCount = 0
        
        for (entry in sortedEntries) {
            if (currentSize <= MAX_CACHE_SIZE) break
            
            // Don't evict visible media unless absolutely necessary
            if (entry.isVisible && currentSize > MAX_CACHE_SIZE * 0.8) continue
            
            // Evict low-priority entries
            if (entry.file.delete()) {
                cacheEntries.remove(entry.mxcUrl)
                currentSize -= entry.size
                evictedCount++
                Log.d(TAG, "Evicted ${entry.mxcUrl} (priority: ${entry.priority}, size: ${entry.size / 1024}KB)")
            }
        }
        
        Log.d(TAG, "Evicted $evictedCount files, new cache size: ${currentSize / 1024 / 1024}MB")
    }
    
    /**
     * Check if MXC URL is cached.
     */
    fun isCached(mxcUrl: String): Boolean {
        return cacheEntries.containsKey(mxcUrl) && cacheEntries[mxcUrl]?.file?.exists() == true
    }
    
    /**
     * Get cache statistics for monitoring.
     */
    fun getCacheStats(): Map<String, Any> {
        val totalSize = cacheEntries.values.sumOf { it.size }
        val visibleCount = cacheEntries.values.count { it.isVisible }
        val totalAccessCount = cacheEntries.values.sumOf { it.accessCount }
        val averageAccessCount = if (cacheEntries.isNotEmpty()) {
            totalAccessCount / cacheEntries.size
        } else 0
        
        return mapOf(
            "total_entries" to cacheEntries.size,
            "total_size_mb" to (totalSize / 1024 / 1024),
            "max_size_mb" to (MAX_CACHE_SIZE / 1024 / 1024),
            "visible_entries" to visibleCount,
            "total_access_count" to totalAccessCount,
            "average_access_count" to averageAccessCount,
            "cache_utilization" to ((totalSize.toDouble() / MAX_CACHE_SIZE) * 100).toInt()
        )
    }
    
    /**
     * Get detailed cache entry information.
     */
    fun getCacheEntryInfo(mxcUrl: String): Map<String, Any>? {
        val entry = cacheEntries[mxcUrl] ?: return null
        
        return mapOf(
            "mxc_url" to entry.mxcUrl,
            "file_path" to entry.file.absolutePath,
            "size_kb" to (entry.size / 1024),
            "last_accessed" to entry.lastAccessed,
            "is_visible" to entry.isVisible,
            "access_count" to entry.accessCount,
            "priority" to entry.priority,
            "file_type" to entry.fileType
        )
    }
    
    /**
     * Clean up old cache files manually.
     */
    suspend fun cleanupOldFiles(context: Context, maxAge: Long = 7 * 24 * 60 * 60 * 1000L) = cacheMutex.withLock {
        val currentTime = System.currentTimeMillis()
        val oldEntries = cacheEntries.values.filter { entry ->
            currentTime - entry.lastAccessed > maxAge && !entry.isVisible
        }
        
        var cleanedCount = 0
        var cleanedSize = 0L
        
        oldEntries.forEach { entry ->
            if (entry.file.delete()) {
                cacheEntries.remove(entry.mxcUrl)
                cleanedCount++
                cleanedSize += entry.size
            }
        }
        
        Log.d(TAG, "Cleaned up $cleanedCount old files (${cleanedSize / 1024 / 1024}MB)")
    }
    
    /**
     * Clear all cache entries.
     */
    suspend fun clearCache(context: Context) = cacheMutex.withLock {
        val cacheDir = getCacheDir(context)
        val files = cacheDir.listFiles() ?: emptyArray()
        
        var deletedCount = 0
        var deletedSize = 0L
        
        files.forEach { file ->
            if (file.delete()) {
                deletedCount++
                deletedSize += file.length()
            }
        }
        
        cacheEntries.clear()
        visibleMedia.clear()
        
        Log.d(TAG, "Cleared cache: $deletedCount files (${deletedSize / 1024 / 1024}MB)")
    }
    
    /**
     * Get cache directory size.
     */
    fun getCacheDirectorySize(context: Context): Long {
        val cacheDir = getCacheDir(context)
        val files = cacheDir.listFiles() ?: emptyArray()
        return files.sumOf { it.length() }
    }
}
