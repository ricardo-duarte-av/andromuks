package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private const val MAX_CACHE_SIZE = 4L * 1024 * 1024 * 1024L // 4GB
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

    // Number of immediate in-call attempts for a media download. This is distinct from
    // (and complementary to) WorkManager's worker-level retries: these handle transient
    // glitches within a single execution (a captive-portal HTML page, a mid-stream reset),
    // while the worker's exponential-backoff retries ride out longer connectivity outages.
    private const val DOWNLOAD_ATTEMPTS = 3

    // Shared OkHttp client for media downloads. Bounded timeouts are the whole point of the
    // switch from java.net.HttpURLConnection: callTimeout caps the entire request (connect +
    // read + body) so a slow-trickle response on flaky 5G / corporate wifi can't hang the
    // worker until WorkManager force-kills it.
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Retry with the opposite ?encrypted= flag when the backend reports a mismatch between
            // the room-derived flag and how the media was actually uploaded. Single-shot; no loop.
            .addInterceptor(EncryptedMediaRetryInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    
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
     * Generate cache key from MXC URL (returns relative path with server subdirectory).
     * MXC URLs are immutable and their server/mediaId path is already filesystem-safe.
     * Uses subdirectories per server to avoid thousands of files in a single directory.
     * e.g. "mxc://aguiarvieira.pt/MGUPvTkAoIFxQBkqdQIlyvxP" → "aguiarvieira.pt/MGUPvTkAoIFxQBkqdQIlyvxP"
     */
    fun getCacheKey(mxcUrl: String): String {
        return mxcUrl.removePrefix("mxc://")
    }
    
    private fun getCacheFile(context: Context, mxcUrl: String): File {
        val cacheDir = getCacheDir(context)
        val relativePath = getCacheKey(mxcUrl)
        return File(cacheDir, relativePath)
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
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Updated visibility for ${visibleUrls.size} media items")
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
    // The body does File.exists() and File.length() — disk reads. Run on Dispatchers.IO so
    // callers from a Main-bound LaunchedEffect (e.g. ImageViewerDialog) don't trip StrictMode.
    suspend fun getCachedFile(context: Context, mxcUrl: String): File? = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
        val entry = cacheEntries[mxcUrl]
        if (entry != null && entry.file.exists() && entry.file.length() > 0L) {
            // Update access statistics
            val updatedEntry = entry.copy(
                lastAccessed = System.currentTimeMillis(),
                accessCount = entry.accessCount + 1,
                priority = calculatePriority(entry, entry.isVisible)
            )
            cacheEntries[mxcUrl] = updatedEntry
            
            // PERFORMANCE: Reduce logging frequency to avoid log spam during fast scrolling
            // Only log every 10th access to reduce overhead
            if (BuildConfig.DEBUG && updatedEntry.accessCount % 10 == 0) {
                Log.d(TAG, "Cache hit for $mxcUrl (access count: ${updatedEntry.accessCount})")
            }
            return@withLock entry.file
        }
        
        // RAM<->Disk mismatch recovery:
        // - We keep an in-memory index in `cacheEntries`.
        // - `AndromuksApplication` clears that index on memory pressure via clearInMemoryCache().
        // - After being idle/trimmed, disk files may still exist, but the index is gone.
        // So, fall back to checking the expected on-disk cache location.
        val diskFile = getCacheFile(context, mxcUrl)
        if (diskFile.exists() && diskFile.length() > 0L) {
            val isVisible = visibleMedia.containsKey(mxcUrl)
            val now = System.currentTimeMillis()
            val baseEntry = CacheEntry(
                file = diskFile,
                mxcUrl = mxcUrl,
                size = diskFile.length(),
                lastAccessed = now,
                isVisible = isVisible,
                accessCount = 1,
                priority = 0L,
                fileType = entry?.fileType ?: "unknown"
            )
            val diskEntry = baseEntry.copy(priority = calculatePriority(baseEntry, isVisible))
            cacheEntries[mxcUrl] = diskEntry
            return@withLock diskFile
        }

        // PERFORMANCE: Only log misses in debug builds, and reduce frequency
        if (BuildConfig.DEBUG) Log.d(TAG, "Cache miss for $mxcUrl")

        return@withLock null
        }
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
        if (BuildConfig.DEBUG) Log.d(TAG, "Cached file for $mxcUrl (size: ${entry.size / 1024}KB, type: $fileType)")
        
        // Ensure cache size is within limits
        ensureCacheSize(context)
    }
    
    /**
     * Ensure cache size is within limits with intelligent eviction.
     */
    private suspend fun ensureCacheSize(context: Context) {
        val totalSize = cacheEntries.values.sumOf { it.size }
        if (totalSize <= MAX_CACHE_SIZE) return
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Cache size ${totalSize / 1024 / 1024}MB exceeds limit ${MAX_CACHE_SIZE / 1024 / 1024}MB, evicting...")
        
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Evicted ${entry.mxcUrl} (priority: ${entry.priority}, size: ${entry.size / 1024}KB)")
            }
        }
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Evicted $evictedCount files, new cache size: ${currentSize / 1024 / 1024}MB")
    }
    
    /**
     * Check if MXC URL is cached.
     */
    fun isCached(mxcUrl: String): Boolean {
        return cacheEntries.containsKey(mxcUrl) && cacheEntries[mxcUrl]?.file?.exists() == true
    }
    
    /**
     * Check if MXC URL is cached (compatibility method with context parameter).
     */
    fun isCached(context: Context, mxcUrl: String): Boolean {
        return isCached(mxcUrl)
    }
    
    /**
     * Download and cache MXC URL (compatibility method matching MediaCache API).
     *
     * Hardened for hostile networks (5G, corporate wifi / captive portals):
     * - Uses OkHttp with bounded timeouts so a stalled connection can't hang indefinitely.
     * - Downloads to a `.tmp` sibling and only renames into the final cache path once the
     *   payload is confirmed to be an actual image. A mid-stream reset therefore leaves
     *   only a temp file (cleaned up), never a truncated file masquerading as a cache hit.
     * - Rejects non-image responses (captive-portal HTML, 302 login pages, error bodies)
     *   by sniffing magic bytes, then retries instead of caching the garbage.
     */
    suspend fun downloadAndCache(
        context: Context,
        mxcUrl: String,
        httpUrl: String,
        authToken: String
    ): File? = withContext(Dispatchers.IO) {
        val cachedFile = getCacheFile(context, mxcUrl)
        cachedFile.parentFile?.mkdirs()

        // Already fully cached (non-empty) — register and return.
        if (cachedFile.exists() && cachedFile.length() > 0L) {
            cacheFile(context, mxcUrl, cachedFile, "unknown")
            return@withContext cachedFile
        }

        val tmpFile = File(cachedFile.parentFile, "${cachedFile.name}.tmp")

        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            tmpFile.delete()
            try {
                val request = Request.Builder()
                    .url(httpUrl)
                    .header("Cookie", "gomuks_auth=$authToken")
                    .header("User-Agent", getUserAgent())
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Log enough to diagnose auth/URL failures without leaking the token: the full
                        // URL (shows whether image_auth was appended), the cookie token length, and the
                        // server's error body (gomuks returns e.g. FI.MAU.GOMUKS.INVALID_COOKIE here).
                        val bodySnippet = try { response.body?.string()?.take(300) } catch (e: Exception) { "<unreadable: ${e.message}>" }
                        Log.w(TAG, "Media download HTTP ${response.code} for $mxcUrl (attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS) " +
                            "url=$httpUrl hasImageAuth=${httpUrl.contains("image_auth=")} cookieLen=${authToken.length} body=$bodySnippet")
                        return@use
                    }

                    val body = response.body ?: run {
                        Log.w(TAG, "Empty body for $mxcUrl (attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS)")
                        return@use
                    }

                    val expectedLength = body.contentLength() // -1 if unknown / chunked
                    tmpFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }

                    // Validate before promoting the temp file to the real cache path.
                    if (tmpFile.length() == 0L) {
                        Log.w(TAG, "Zero-byte download for $mxcUrl (attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS)")
                        return@use
                    }
                    if (expectedLength > 0 && tmpFile.length() != expectedLength) {
                        Log.w(TAG, "Truncated download for $mxcUrl: got ${tmpFile.length()} of $expectedLength bytes (attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS)")
                        return@use
                    }
                    if (!looksLikeImage(tmpFile)) {
                        Log.w(TAG, "Download for $mxcUrl is not an image (captive portal / error page?) (attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS)")
                        return@use
                    }

                    // Atomic-ish promotion: rename is atomic on the same filesystem; fall back to copy.
                    cachedFile.delete()
                    if (!tmpFile.renameTo(cachedFile)) {
                        tmpFile.copyTo(cachedFile, overwrite = true)
                        tmpFile.delete()
                    }

                    cacheFile(context, mxcUrl, cachedFile, "image")
                    return@withContext cachedFile
                }
            } catch (e: Exception) {
                Log.w(TAG, "Media download failed for $mxcUrl (attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS): ${e.message}")
            }

            // Short linear backoff between in-call attempts; the worker handles longer outages.
            if (attempt < DOWNLOAD_ATTEMPTS - 1) delay(500L * (attempt + 1))
        }

        tmpFile.delete()
        Log.e(TAG, "Failed to cache media after $DOWNLOAD_ATTEMPTS attempts: $mxcUrl")
        null
    }

    /**
     * Sniff the leading bytes of a file to decide whether it is a real raster image.
     *
     * This guards against captive portals and error pages that return HTTP 200 with an
     * HTML/text body — content-type headers are unreliable through MITM proxies, so we
     * trust the bytes on disk instead. Covers the formats the app actually renders.
     */
    private fun looksLikeImage(file: File): Boolean {
        return try {
            val header = ByteArray(12)
            val read = file.inputStream().use { it.read(header) }
            if (read < 4) return false

            fun u(i: Int) = header[i].toInt() and 0xFF
            when {
                // JPEG: FF D8 FF
                u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> true
                // PNG: 89 50 4E 47 0D 0A 1A 0A
                u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 -> true
                // GIF: "GIF8"
                u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x38 -> true
                // BMP: "BM"
                u(0) == 0x42 && u(1) == 0x4D -> true
                // WEBP: "RIFF"...."WEBP"
                read >= 12 && u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
                    u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50 -> true
                // HEIC/HEIF/AVIF: bytes 4..7 == "ftyp"
                read >= 8 && u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70 -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not sniff image header for ${file.name}: ${e.message}")
            false
        }
    }
    
    /**
     * Clean up cache if size exceeds limit (compatibility method matching MediaCache API).
     */
    suspend fun cleanupCache(context: Context) = cacheMutex.withLock {
        try {
            // Calculate total cache size (recursively handles subdirectories)
            val totalSize = getCacheDirectorySize(context)
            
            if (totalSize > MAX_CACHE_SIZE) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Cache size ${totalSize / 1024 / 1024}MB exceeds limit ${MAX_CACHE_SIZE / 1024 / 1024}MB, cleaning up...")
                
                // Use intelligent eviction (uses in-memory cacheEntries map)
                ensureCacheSize(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup cache", e)
        }
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
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleaned up $cleanedCount old files (${cleanedSize / 1024 / 1024}MB)")
    }
    
    /**
     * Clear all cache entries.
     * Recursively deletes files from subdirectories.
     */
    suspend fun clearCache(context: Context) = cacheMutex.withLock {
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
        
        cacheEntries.clear()
        visibleMedia.clear()
        
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleared cache: $deletedCount files (${deletedSize / 1024 / 1024}MB)")
    }
    
    /**
     * Get cache directory size.
     * Recursively sums files from subdirectories.
     */
    fun getCacheDirectorySize(context: Context): Long {
        val cacheDir = getCacheDir(context)
        
        fun sumFiles(dir: File): Long {
            var total = 0L
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    total += sumFiles(file)
                } else if (file.isFile) {
                    total += file.length()
                }
            }
            return total
        }
        
        return sumFiles(cacheDir)
    }
    
    /**
     * Get all cached MXC URLs (for gallery display).
     * Returns a map of cache key (file name) to MXC URL.
     */
    fun getAllCachedMxcUrls(): Map<String, String> {
        return cacheEntries.map { (mxcUrl, entry) ->
            val cacheKey = getCacheKey(mxcUrl)
            cacheKey to mxcUrl
        }.toMap()
    }
    
    /**
     * Get MXC URL for a cache file by matching cache key.
     */
    fun getMxcUrlForFile(fileName: String): String? {
        // Try direct match first
        val cacheKey = fileName
        for ((mxcUrl, entry) in cacheEntries) {
            val entryCacheKey = getCacheKey(mxcUrl)
            if (entryCacheKey == cacheKey || fileName.contains(entryCacheKey) || entryCacheKey.contains(fileName)) {
                return mxcUrl
            }
        }
        return null
    }
    
    /**
     * Clear in-memory cache entries (non-suspend version for onTrimMemory).
     * 
     * This clears the in-memory cache entry map to prevent stale references
     * after Android's LMK kills cached bitmaps. Disk files are preserved.
     * 
     * This should be called from Application.onTrimMemory() to prevent cache corruption.
     */
    fun clearInMemoryCache() {
        // Clear cache entries map (thread-safe, no mutex needed for clear)
        cacheEntries.clear()
        visibleMedia.clear()
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Cleared in-memory cache entries (disk files preserved)")
        }
    }

    /**
     * Evict a single cache entry.
     *
     * Used when a cached file is known-bad (decode fails, read fails, etc).
     * Deletes the on-disk file and removes the in-memory index entry.
     */
    suspend fun evictCachedFile(context: Context, mxcUrl: String) = cacheMutex.withLock {
        try {
            // Remove RAM index first
            cacheEntries.remove(mxcUrl)
            visibleMedia.remove(mxcUrl)

            // Remove disk file if present (even if RAM index was already cleared)
            val diskFile = getCacheFile(context, mxcUrl)
            if (diskFile.exists()) {
                diskFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to evict cached file for $mxcUrl", e)
        }
    }
}
