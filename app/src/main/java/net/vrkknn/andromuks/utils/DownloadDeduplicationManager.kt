package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Download deduplication system to prevent duplicate downloads.
 * 
 * This system provides:
 * - Smart download deduplication
 * - Download queue management
 * - Retry logic with exponential backoff
 * - Download progress tracking
 * - Network optimization
 */
object DownloadDeduplicationManager {
    private const val TAG = "DownloadDeduplicationManager"
    private const val MAX_RETRIES = 3
    private const val BASE_RETRY_DELAY = 1000L
    private const val MAX_CONCURRENT_DOWNLOADS = 3
    
    data class DownloadInfo(
        val mxcUrl: String,
        val httpUrl: String,
        val authToken: String,
        val startTime: Long,
        val retryCount: Int = 0
    )
    
    // Thread-safe collections for download management
    private val activeDownloads = ConcurrentHashMap<String, Deferred<File>>()
    private val downloadQueue = ConcurrentHashMap<String, DownloadInfo>()
    private val downloadHistory = ConcurrentHashMap<String, Long>()
    private val downloadMutex = Mutex()
    
    // Download statistics
    private var totalDownloads = 0
    private var successfulDownloads = 0
    private var failedDownloads = 0
    private var duplicatePrevented = 0
    
    /**
     * Download media with deduplication and smart management.
     * 
     * @param context Android context
     * @param mxcUrl MXC URL for the media
     * @param httpUrl HTTP URL for the media
     * @param authToken Authentication token
     * @return Downloaded file
     */
    suspend fun downloadMedia(
        context: Context,
        mxcUrl: String,
        httpUrl: String,
        authToken: String
    ): File = withContext(Dispatchers.IO) {
        // Check if already cached
        IntelligentMediaCache.getCachedFile(context, mxcUrl)?.let { cachedFile ->
            Log.d(TAG, "Using cached file for $mxcUrl")
            return@withContext cachedFile
        }
        
        // Check if download is already in progress
        activeDownloads[mxcUrl]?.let { deferred ->
            Log.d(TAG, "Download already in progress for $mxcUrl")
            duplicatePrevented++
            return@withContext deferred.await()
        }
        
        // Check if we've reached the concurrent download limit
        if (activeDownloads.size >= MAX_CONCURRENT_DOWNLOADS) {
            Log.d(TAG, "Download queue full, queuing $mxcUrl")
            downloadQueue[mxcUrl] = DownloadInfo(mxcUrl, httpUrl, authToken, System.currentTimeMillis())
            
            // Wait for a slot to become available
            while (activeDownloads.size >= MAX_CONCURRENT_DOWNLOADS) {
                delay(100)
            }
        }
        
        // Start new download
        val downloadJob = async {
            performDownload(context, mxcUrl, httpUrl, authToken)
        }
        
        activeDownloads[mxcUrl] = downloadJob
        totalDownloads++
        
        try {
            val result = downloadJob.await()
            IntelligentMediaCache.cacheFile(context, mxcUrl, result)
            downloadHistory[mxcUrl] = System.currentTimeMillis()
            successfulDownloads++
            
            Log.d(TAG, "Successfully downloaded $mxcUrl")
            result
            
        } catch (e: Exception) {
            failedDownloads++
            Log.e(TAG, "Failed to download $mxcUrl", e)
            throw e
            
        } finally {
            activeDownloads.remove(mxcUrl)
            
            // Process queued downloads
            processDownloadQueue(context)
        }
    }
    
    /**
     * Process queued downloads when slots become available.
     */
    private suspend fun processDownloadQueue(context: Context) {
        if (downloadQueue.isEmpty() || activeDownloads.size >= MAX_CONCURRENT_DOWNLOADS) return
        
        val queuedDownload = downloadQueue.entries.firstOrNull()
        queuedDownload?.let { (mxcUrl, downloadInfo) ->
            downloadQueue.remove(mxcUrl)
            
            try {
                downloadMedia(context, mxcUrl, downloadInfo.httpUrl, downloadInfo.authToken)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process queued download for $mxcUrl", e)
            }
        }
    }
    
    /**
     * Perform the actual download with retry logic.
     */
    private suspend fun performDownload(
        context: Context,
        mxcUrl: String,
        httpUrl: String,
        authToken: String
    ): File = withContext(Dispatchers.IO) {
        val cacheDir = IntelligentMediaCache.getCacheDir(context)
        val cacheKey = IntelligentMediaCache.getCacheKey(mxcUrl)
        val cachedFile = File(cacheDir, cacheKey)
        
        var retryCount = 0
        var lastException: Exception? = null
        
        while (retryCount < MAX_RETRIES) {
            try {
                Log.d(TAG, "Downloading $mxcUrl (attempt ${retryCount + 1}/$MAX_RETRIES)")
                
                val connection = URL(httpUrl).openConnection().apply {
                    setRequestProperty("Cookie", "gomuks_auth=$authToken")
                    setRequestProperty("User-Agent", "Andromuks/1.0")
                    setRequestProperty("Accept", "*/*")
                    connectTimeout = 10000
                    readTimeout = 30000
                }
                
                cachedFile.outputStream().use { output ->
                    connection.getInputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                
                Log.d(TAG, "Successfully downloaded $mxcUrl (${cachedFile.length() / 1024}KB)")
                return@withContext cachedFile
                
            } catch (e: Exception) {
                lastException = e
                retryCount++
                
                Log.w(TAG, "Download failed for $mxcUrl (attempt $retryCount/$MAX_RETRIES): ${e.message}")
                
                if (retryCount < MAX_RETRIES) {
                    val delay = BASE_RETRY_DELAY * (1L shl retryCount) // Exponential backoff
                    Log.d(TAG, "Retrying download for $mxcUrl in ${delay}ms")
                    delay(delay)
                }
            }
        }
        
        throw Exception("Failed to download $mxcUrl after $MAX_RETRIES attempts", lastException)
    }
    
    /**
     * Cancel a specific download.
     */
    suspend fun cancelDownload(mxcUrl: String) = downloadMutex.withLock {
        activeDownloads[mxcUrl]?.cancel()
        activeDownloads.remove(mxcUrl)
        downloadQueue.remove(mxcUrl)
        Log.d(TAG, "Cancelled download for $mxcUrl")
    }
    
    /**
     * Cancel all active downloads.
     */
    suspend fun cancelAllDownloads() = downloadMutex.withLock {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        downloadQueue.clear()
        Log.d(TAG, "Cancelled all downloads")
    }
    
    /**
     * Get download statistics for monitoring.
     */
    fun getDownloadStats(): Map<String, Any> {
        val successRate = if (totalDownloads > 0) {
            (successfulDownloads.toDouble() / totalDownloads * 100).toInt()
        } else 0
        
        return mapOf(
            "total_downloads" to totalDownloads,
            "successful_downloads" to successfulDownloads,
            "failed_downloads" to failedDownloads,
            "duplicate_prevented" to duplicatePrevented,
            "success_rate_percent" to successRate,
            "active_downloads" to activeDownloads.size,
            "queued_downloads" to downloadQueue.size,
            "download_history_size" to downloadHistory.size
        )
    }
    
    /**
     * Get detailed download information for a specific MXC URL.
     */
    fun getDownloadInfo(mxcUrl: String): Map<String, Any>? {
        val activeDownload = activeDownloads[mxcUrl]
        val queuedDownload = downloadQueue[mxcUrl]
        val historyTime = downloadHistory[mxcUrl]
        
        return mapOf(
            "mxc_url" to mxcUrl,
            "is_active" to (activeDownload != null),
            "is_queued" to (queuedDownload != null),
            "in_history" to (historyTime != null),
            "history_time" to (historyTime ?: 0L),
            "retry_count" to (queuedDownload?.retryCount ?: 0)
        )
    }
    
    /**
     * Clear download history.
     */
    fun clearDownloadHistory() {
        downloadHistory.clear()
        Log.d(TAG, "Cleared download history")
    }
    
    /**
     * Get download queue information.
     */
    fun getDownloadQueueInfo(): List<Map<String, Any>> {
        return downloadQueue.values.map { downloadInfo ->
            mapOf(
                "mxc_url" to downloadInfo.mxcUrl,
                "start_time" to downloadInfo.startTime,
                "retry_count" to downloadInfo.retryCount,
                "wait_time_ms" to (System.currentTimeMillis() - downloadInfo.startTime)
            )
        }
    }
    
    /**
     * Check if a download is in progress.
     */
    fun isDownloadInProgress(mxcUrl: String): Boolean {
        return activeDownloads.containsKey(mxcUrl) || downloadQueue.containsKey(mxcUrl)
    }
    
    /**
     * Get the number of active downloads.
     */
    fun getActiveDownloadCount(): Int {
        return activeDownloads.size
    }
    
    /**
     * Get the number of queued downloads.
     */
    fun getQueuedDownloadCount(): Int {
        return downloadQueue.size
    }
    
    /**
     * Clean up resources and cancel all downloads.
     */
    suspend fun cleanup() = downloadMutex.withLock {
        cancelAllDownloads()
        clearDownloadHistory()
        Log.d(TAG, "DownloadDeduplicationManager cleanup completed")
    }
}
