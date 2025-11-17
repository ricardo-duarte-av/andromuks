package net.vrkknn.andromuks.utils

import android.util.Log
import kotlinx.coroutines.launch

object MediaUtils {
    /**
     * Converts an MXC URL to a proper HTTP URL for loading media files
     * @param mxcUrl The MXC URL from the media data (e.g., "mxc://aguiarvieira.pt/fca77a7d521c78a11464518d35f661cdad768f971970990774417883136")
     * @param homeserverUrl The gomuks backend URL (e.g., "https://testmuks.aguiarvieira.pt")
     * @return The HTTP URL for loading the media file, or null if conversion fails
     */
    fun mxcToHttpUrl(mxcUrl: String?, homeserverUrl: String, registerMapping: Boolean = true): String? {
        if (mxcUrl.isNullOrBlank()) return null
        
        try {
            // Parse MXC URL: mxc://server/mediaId
            if (!mxcUrl.startsWith("mxc://")) {
                Log.w("Andromuks", "MediaUtils: Invalid MXC URL format: $mxcUrl")
                return null
            }
            
            val mxcPath = mxcUrl.removePrefix("mxc://")
            val parts = mxcPath.split("/", limit = 2)
            if (parts.size != 2) {
                Log.w("Andromuks", "MediaUtils: Invalid MXC URL structure: $mxcUrl")
                return null
            }
            
            val server = parts[0]
            val mediaId = parts[1]
            
            // Construct HTTP URL: https://gomuks-backend/_gomuks/media/server/mediaId
            // For media files, we want the full-size image, not a thumbnail
            val httpUrl = "$homeserverUrl/_gomuks/media/$server/$mediaId"
            
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
            
            //Log.d("Andromuks", "MediaUtils: Converted MXC URL: $mxcUrl -> $sanitizedUrl")
            
            // Register URL mapping for cache gallery display (async, non-blocking)
            if (registerMapping) {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        CoilUrlMapper.registerMapping(sanitizedUrl, mxcUrl)
                    } catch (e: Exception) {
                        // Ignore errors in mapping registration
                    }
                }
            }
            
            return sanitizedUrl
            
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUtils: Error converting MXC URL: $mxcUrl", e)
            return null
        }
    }
    
    /**
     * Converts an MXC URL to a thumbnail URL for media files
     * @param mxcUrl The MXC URL from the media data
     * @param homeserverUrl The gomuks backend URL
     * @param width Optional thumbnail width (default: 600)
     * @param height Optional thumbnail height (default: 600)
     * @return The HTTP URL for loading the media thumbnail, or null if conversion fails
     */
    fun mxcToThumbnailUrl(mxcUrl: String?, homeserverUrl: String, width: Int = 600, height: Int = 600): String? {
        if (mxcUrl.isNullOrBlank()) return null
        
        try {
            // Parse MXC URL: mxc://server/mediaId
            if (!mxcUrl.startsWith("mxc://")) {
                Log.w("Andromuks", "MediaUtils: Invalid MXC URL format for thumbnail: $mxcUrl")
                return null
            }
            
            val mxcPath = mxcUrl.removePrefix("mxc://")
            val parts = mxcPath.split("/", limit = 2)
            if (parts.size != 2) {
                Log.w("Andromuks", "MediaUtils: Invalid MXC URL structure for thumbnail: $mxcUrl")
                return null
            }
            
            val server = parts[0]
            val mediaId = parts[1]
            
            // Construct HTTP URL: https://gomuks-backend/_gomuks/media/server/mediaId?thumbnail=width,height
            val httpUrl = "$homeserverUrl/_gomuks/media/$server/$mediaId?thumbnail=$width,$height"
            
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
            
            //Log.d("Andromuks", "MediaUtils: Converted MXC URL to thumbnail: $mxcUrl -> $sanitizedUrl")
            return sanitizedUrl
            
        } catch (e: Exception) {
            Log.e("Andromuks", "MediaUtils: Error converting MXC URL to thumbnail: $mxcUrl", e)
            return null
        }
    }
}
