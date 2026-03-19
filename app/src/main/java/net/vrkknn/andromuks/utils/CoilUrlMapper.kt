package net.vrkknn.andromuks.utils



import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
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
    private val cacheFileKeyMappings = ConcurrentHashMap<String, String>() // Disk cache file key/hash -> MXC URL
    private val cacheMutex = Mutex()
    private var isLoaded = false
    
    /**
     * Register a mapping from HTTP URL to MXC URL.
     * This should be called whenever an image is loaded with a known MXC URL.
     */
    suspend fun registerMapping(httpUrl: String, mxcUrl: String) = cacheMutex.withLock {
        if (httpUrl.isNotEmpty() && mxcUrl.isNotEmpty() && mxcUrl.startsWith("mxc://")) {
            urlMappings[httpUrl] = mxcUrl
            val candidates = generateCacheKeyCandidates(httpUrl)
            candidates.forEach { candidate ->
                cacheFileKeyMappings[candidate] = mxcUrl
            }
            if (BuildConfig.DEBUG) {
                val suffix = httpUrl.substringBefore('?').takeLast(80)
                Log.d(TAG, "registerMapping: mxc=$mxcUrl urlSuffix=$suffix candidateCount=${candidates.size}")
            }
            // Keep mapping size reasonable (limit to 10,000 entries)
            if (urlMappings.size > 10000) {
                // Remove oldest 20% of entries (simple FIFO)
                val keysToRemove = urlMappings.keys.take(2000)
                keysToRemove.forEach { urlMappings.remove(it) }
            }
            if (cacheFileKeyMappings.size > 30000) {
                val keysToRemove = cacheFileKeyMappings.keys.take(6000)
                keysToRemove.forEach { cacheFileKeyMappings.remove(it) }
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
        val fileName = file.name
        val fileNameNoExtension = fileName.substringBeforeLast('.', fileName)

        // Deterministic path: exact cache file key/hash lookup.
        cacheFileKeyMappings[fileName]?.let {
            if (BuildConfig.DEBUG) Log.d(TAG, "resolveByFile: exact match fileName=$fileName -> $it")
            return@withLock it
        }
        cacheFileKeyMappings[fileNameNoExtension]?.let {
            if (BuildConfig.DEBUG) Log.d(TAG, "resolveByFile: exact match noExt=$fileNameNoExtension -> $it")
            return@withLock it
        }

        // Some file names include extra suffixes; try normalized tokens.
        fileName.split('.', '_', '-')
            .asSequence()
            .filter { it.isNotBlank() && it.length >= 8 }
            .forEach { token ->
                cacheFileKeyMappings[token]?.let {
                    if (BuildConfig.DEBUG) Log.d(TAG, "resolveByFile: token match token=$token file=$fileName -> $it")
                    return@withLock it
                }
            }

        // Fallback heuristics for older mapping files.
        // Try to match by checking if any HTTP URL's hash matches the file name
        // Coil uses URL.hashCode() or similar for cache keys
        for ((httpUrl, mxcUrl) in urlMappings) {
            // Simple heuristic: check if file name contains parts of the URL
            val urlHash = httpUrl.hashCode().toString()
            if (fileName.contains(urlHash) || 
                fileName.contains(httpUrl.replace("https://", "").replace("http://", "").take(20))) {
                if (BuildConfig.DEBUG) Log.d(TAG, "resolveByFile: heuristic match file=$fileName viaUrlHash=$urlHash -> $mxcUrl")
                return@withLock mxcUrl
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "resolveByFile: miss file=$fileName mappings(url=${urlMappings.size}, fileKeys=${cacheFileKeyMappings.size})"
            )
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
                var loadedCount = 0

                if (json.has("url_mappings")) {
                    val urlJson = json.getJSONObject("url_mappings")
                    val urlKeys = urlJson.keys()
                    while (urlKeys.hasNext()) {
                        val httpUrl = urlKeys.next()
                        val mxcUrl = urlJson.getString(httpUrl)
                        if (mxcUrl.startsWith("mxc://")) {
                            urlMappings[httpUrl] = mxcUrl
                            loadedCount++
                        }
                    }
                } else {
                    // Backward compatibility with legacy flat format.
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val httpUrl = keys.next()
                        val mxcUrl = json.getString(httpUrl)
                        if (mxcUrl.startsWith("mxc://")) {
                            urlMappings[httpUrl] = mxcUrl
                            loadedCount++
                        }
                    }
                }

                if (json.has("file_key_mappings")) {
                    val fileKeyJson = json.getJSONObject("file_key_mappings")
                    val fileKeyKeys = fileKeyJson.keys()
                    while (fileKeyKeys.hasNext()) {
                        val fileKey = fileKeyKeys.next()
                        val mxcUrl = fileKeyJson.getString(fileKey)
                        if (mxcUrl.startsWith("mxc://")) {
                            cacheFileKeyMappings[fileKey] = mxcUrl
                        }
                    }
                }
                
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded $loadedCount URL mappings from disk")
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
            val urlJson = org.json.JSONObject()
            val fileKeyJson = org.json.JSONObject()
            
            // Save up to 5000 most recent mappings to avoid large files
            val mappingsToSave = urlMappings.entries.take(5000)
            for ((httpUrl, mxcUrl) in mappingsToSave) {
                urlJson.put(httpUrl, mxcUrl)
            }
            val fileKeyMappingsToSave = cacheFileKeyMappings.entries.take(15000)
            for ((cacheKey, mxcUrl) in fileKeyMappingsToSave) {
                fileKeyJson.put(cacheKey, mxcUrl)
            }
            json.put("url_mappings", urlJson)
            json.put("file_key_mappings", fileKeyJson)
            
            mappingFile.writeText(json.toString())
            if (BuildConfig.DEBUG) Log.d(TAG, "Saved ${mappingsToSave.size} URL mappings and ${fileKeyMappingsToSave.size} file-key mappings to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save URL mappings", e)
        }
    }
    
    /**
     * Clear all mappings (for testing or cache clearing).
     */
    suspend fun clearMappings(context: Context) = cacheMutex.withLock {
        urlMappings.clear()
        cacheFileKeyMappings.clear()
        try {
            val mappingFile = File(context.cacheDir, MAPPING_FILE)
            if (mappingFile.exists()) {
                mappingFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete mapping file", e)
        }
    }

    private fun generateCacheKeyCandidates(httpUrl: String): Set<String> {
        val candidates = linkedSetOf<String>()
        val normalized = httpUrl.trim()
        val normalizedNoQuery = normalized.substringBefore('?')
        val lastSegment = normalizedNoQuery.substringAfterLast('/', "")
        val pathSuffix = normalizedNoQuery.substringAfter("/_gomuks/media/", "")

        candidates.add(normalized.hashCode().toString())
        if (normalizedNoQuery != normalized) {
            candidates.add(normalizedNoQuery.hashCode().toString())
        }
        if (lastSegment.isNotBlank()) {
            candidates.add(lastSegment)
            candidates.add(lastSegment.hashCode().toString())
        }
        if (pathSuffix.isNotBlank()) {
            candidates.add(pathSuffix)
            candidates.add(pathSuffix.hashCode().toString())
        }

        candidates.add(sha256Hex(normalized))
        candidates.add(md5Hex(normalized))
        if (normalizedNoQuery != normalized) {
            candidates.add(sha256Hex(normalizedNoQuery))
            candidates.add(md5Hex(normalizedNoQuery))
        }
        return candidates.filter { it.isNotBlank() }.toSet()
    }

    private fun sha256Hex(value: String): String = digestHex("SHA-256", value)
    private fun md5Hex(value: String): String = digestHex("MD5", value)

    private fun digestHex(algorithm: String, value: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

