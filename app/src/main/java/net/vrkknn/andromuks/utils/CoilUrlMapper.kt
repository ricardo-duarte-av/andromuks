package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Maps HTTP URLs (used by Coil) to MXC URLs for cache gallery display.
 * 
 * This solves the "Unknown MXC URL" problem by tracking which HTTP URLs
 * correspond to which MXC URLs when images are loaded.
 */
object CoilUrlMapper {
    private const val TAG = "CoilUrlMapper"
    private const val MAPPING_FILE = "coil_url_mapping.json"
    private val urlMappings = ConcurrentHashMap<String, String>() // HTTP URL -> MXC URL
    private val cacheMutex = Mutex()
    private var isLoaded = false
    
    /**
     * Register a mapping from HTTP URL to MXC URL.
     * This should be called whenever an image is loaded with a known MXC URL.
     */
    suspend fun registerMapping(httpUrl: String, mxcUrl: String) = cacheMutex.withLock {
        if (httpUrl.isNotEmpty() && mxcUrl.isNotEmpty() && mxcUrl.startsWith("mxc://")) {
            urlMappings[httpUrl] = mxcUrl
            // Keep mapping size reasonable (limit to 10,000 entries)
            if (urlMappings.size > 10000) {
                // Remove oldest 20% of entries (simple FIFO)
                val keysToRemove = urlMappings.keys.take(2000)
                keysToRemove.forEach { urlMappings.remove(it) }
            }
        }
    }
    
    /**
     * Get MXC URL for an HTTP URL.
     */
    fun getMxcUrl(httpUrl: String): String? {
        return urlMappings[httpUrl]
    }
    
    /**
     * Try to find MXC URL for a Coil cache file by checking registered mappings.
     * This is a best-effort lookup that may not always succeed.
     */
    suspend fun findMxcUrlForCacheFile(context: Context, file: File): String? = cacheMutex.withLock {
        // Coil cache files are named based on URL hash, so we need to check all mappings
        // This is not perfect but better than nothing
        val fileName = file.name
        
        // Try to match by checking if any HTTP URL's hash matches the file name
        // Coil uses URL.hashCode() or similar for cache keys
        for ((httpUrl, mxcUrl) in urlMappings) {
            // Simple heuristic: check if file name contains parts of the URL
            val urlHash = httpUrl.hashCode().toString()
            if (fileName.contains(urlHash) || 
                fileName.contains(httpUrl.replace("https://", "").replace("http://", "").take(20))) {
                return@withLock mxcUrl
            }
        }
        
        null
    }
    
    /**
     * Load mappings from disk (called on app startup).
     */
    suspend fun loadMappings(context: Context) = cacheMutex.withLock {
        if (isLoaded) return@withLock
        
        try {
            val mappingFile = File(context.cacheDir, MAPPING_FILE)
            if (mappingFile.exists() && mappingFile.canRead()) {
                val content = mappingFile.readText()
                val json = org.json.JSONObject(content)
                val keys = json.keys()
                
                var loadedCount = 0
                while (keys.hasNext()) {
                    val httpUrl = keys.next()
                    val mxcUrl = json.getString(httpUrl)
                    if (mxcUrl.startsWith("mxc://")) {
                        urlMappings[httpUrl] = mxcUrl
                        loadedCount++
                    }
                }
                
                Log.d(TAG, "Loaded $loadedCount URL mappings from disk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load URL mappings", e)
        }
        
        isLoaded = true
    }
    
    /**
     * Save mappings to disk (called periodically or on app shutdown).
     */
    suspend fun saveMappings(context: Context) = cacheMutex.withLock {
        try {
            val mappingFile = File(context.cacheDir, MAPPING_FILE)
            val json = org.json.JSONObject()
            
            // Save up to 5000 most recent mappings to avoid large files
            val mappingsToSave = urlMappings.entries.take(5000)
            for ((httpUrl, mxcUrl) in mappingsToSave) {
                json.put(httpUrl, mxcUrl)
            }
            
            mappingFile.writeText(json.toString())
            Log.d(TAG, "Saved ${mappingsToSave.size} URL mappings to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save URL mappings", e)
        }
    }
    
    /**
     * Clear all mappings (for testing or cache clearing).
     */
    suspend fun clearMappings(context: Context) = cacheMutex.withLock {
        urlMappings.clear()
        try {
            val mappingFile = File(context.cacheDir, MAPPING_FILE)
            if (mappingFile.exists()) {
                mappingFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete mapping file", e)
        }
    }
}

