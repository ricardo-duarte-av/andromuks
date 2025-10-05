package net.vrkknn.andromuks.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object AvatarUtils {
    /**
     * Converts an MXC URL to a proper HTTP URL for loading avatars
     * @param mxcUrl The MXC URL from the room data (e.g., "mxc://nexy7574.co.uk/CedROJ82UFmQys8c6nOzlBsL0W4TeIsJ")
     * @param homeserverUrl The homeserver URL (e.g., "https://webmuks.aguiarvieira.pt")
     * @return The HTTP URL for loading the avatar, or null if conversion fails
     */
    fun mxcToHttpUrl(mxcUrl: String?, homeserverUrl: String): String? {
        if (mxcUrl.isNullOrBlank()) return null
        
        try {
            // Parse MXC URL: mxc://server/mediaId
            if (!mxcUrl.startsWith("mxc://")) {
                Log.w("Andromuks", "AvatarUtils: Invalid MXC URL format: $mxcUrl")
                return null
            }
            
            val mxcPath = mxcUrl.removePrefix("mxc://")
            val parts = mxcPath.split("/", limit = 2)
            if (parts.size != 2) {
                Log.w("Andromuks", "AvatarUtils: Invalid MXC URL structure: $mxcUrl")
                return null
            }
            
            val server = parts[0]
            val mediaId = parts[1]
            
            // Construct HTTP URL: https://gomuks-backend/_gomuks/media/server/mediaId?thumbnail=avatar
            // For avatars, we want the small thumbnail version
            val httpUrl = "$homeserverUrl/_gomuks/media/$server/$mediaId?thumbnail=avatar"
            
            // Sanitize URL: convert everything before /_gomuks/media/ to lowercase
            val sanitizedUrl = if (httpUrl.contains("/_gomuks/media/")) {
                val parts = httpUrl.split("/_gomuks/media/", limit = 2)
                if (parts.size == 2) {
                    "${parts[0].lowercase()}/_gomuks/media/${parts[1]}"
                } else {
                    httpUrl
                }
            } else {
                httpUrl.lowercase()
            }
            
            Log.d("Andromuks", "AvatarUtils: Converted MXC URL: $mxcUrl -> $sanitizedUrl")
            return sanitizedUrl
            
        } catch (e: Exception) {
            Log.e("Andromuks", "AvatarUtils: Error converting MXC URL: $mxcUrl", e)
            return null
        }
    }
    
    /**
     * Load bitmap from URL (handles both MXC and HTTP URLs)
     */
    suspend fun loadBitmapFromUrl(url: String, homeserverUrl: String, authToken: String? = null): Bitmap? = withContext(Dispatchers.IO) {
        Log.d("Andromuks", "AvatarUtils: loadBitmapFromUrl called with URL: $url")
        Log.d("Andromuks", "AvatarUtils: Homeserver URL: $homeserverUrl")
        Log.d("Andromuks", "AvatarUtils: Auth token provided: ${authToken != null && authToken.isNotBlank()}")
        
        try {
            // Convert URL to proper format and get HTTP URL
            val httpUrl = when {
                url.startsWith("mxc://") -> {
                    Log.d("Andromuks", "AvatarUtils: Processing MXC URL")
                    MediaUtils.mxcToHttpUrl(url, homeserverUrl) ?: return@withContext null
                }
                url.startsWith("_gomuks/") -> {
                    Log.d("Andromuks", "AvatarUtils: Processing _gomuks URL")
                    "$homeserverUrl/$url"
                }
                else -> {
                    Log.d("Andromuks", "AvatarUtils: Processing direct HTTP URL")
                    url
                }
            }
            
            Log.d("Andromuks", "AvatarUtils: Converted HTTP URL: $httpUrl")
            
            // For MXC URLs, use MediaCache for authentication
            if (url.startsWith("mxc://") && authToken != null) {
                Log.d("Andromuks", "AvatarUtils: Using MediaCache for MXC URL with authentication")
                
                // Check cache first
                val cachedFile = MediaCache.getCachedFile(android.app.Application(), url)
                Log.d("Andromuks", "AvatarUtils: Cached file check: ${cachedFile?.absolutePath ?: "null"}")
                
                if (cachedFile != null && cachedFile.exists()) {
                    Log.d("Andromuks", "AvatarUtils: Using cached bitmap: ${cachedFile.absolutePath}")
                    val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    Log.d("Andromuks", "AvatarUtils: Cached bitmap loaded: ${bitmap != null}")
                    return@withContext bitmap
                }
                
                // Download and cache using MediaCache
                Log.d("Andromuks", "AvatarUtils: Downloading and caching with MediaCache...")
                val downloadedFile = MediaCache.downloadAndCache(android.app.Application(), url, httpUrl, authToken)
                Log.d("Andromuks", "AvatarUtils: MediaCache download result: ${downloadedFile?.absolutePath ?: "null"}")
                
                if (downloadedFile != null && downloadedFile.exists()) {
                    Log.d("Andromuks", "AvatarUtils: Successfully downloaded and cached bitmap: ${downloadedFile.absolutePath}")
                    val bitmap = BitmapFactory.decodeFile(downloadedFile.absolutePath)
                    Log.d("Andromuks", "AvatarUtils: Downloaded bitmap loaded: ${bitmap != null}")
                    return@withContext bitmap
                } else {
                    Log.w("Andromuks", "AvatarUtils: MediaCache download failed or file doesn't exist")
                }
            } else {
                Log.d("Andromuks", "AvatarUtils: Not using MediaCache - MXC: ${url.startsWith("mxc://")}, AuthToken: ${authToken != null}")
            }
            
            // Fallback to direct download for non-MXC URLs or when authToken is not available
            Log.d("Andromuks", "AvatarUtils: Falling back to direct download")
            val connection = URL(httpUrl).openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val inputStream = connection.getInputStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            Log.d("Andromuks", "AvatarUtils: Direct download result: ${bitmap != null}")
            if (bitmap != null) {
                Log.d("Andromuks", "AvatarUtils: Successfully loaded bitmap: ${bitmap.width}x${bitmap.height}")
            }
            bitmap
            
        } catch (e: Exception) {
            Log.e("Andromuks", "AvatarUtils: Error loading bitmap from URL: $url", e)
            null
        }
    }
}
