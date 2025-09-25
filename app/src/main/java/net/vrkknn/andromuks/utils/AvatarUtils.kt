package net.vrkknn.andromuks.utils

import android.util.Log

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
}
