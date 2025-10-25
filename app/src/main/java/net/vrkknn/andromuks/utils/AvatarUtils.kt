package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.ErrorResult
import coil.request.SuccessResult
import java.net.URLEncoder

object AvatarUtils {
    private const val FALLBACK_COLOR_COUNT = 10
    
    // Fallback colors matching the web client (hex codes without #)
    // These should stay in sync with the colors used in the web client
    private val FALLBACK_COLORS = arrayOf(
        "4a9b89", // teal
        "9b4a8b", // purple
        "8b4a9b", // violet
        "4a8b9b", // cyan
        "9b8b4a", // olive
        "8b9b4a", // lime
        "4a8b4a", // green
        "9b4a4a", // red
        "4a4a9b", // blue
        "d991de"  // pink
    )
    
    /**
     * Get a deterministic color index (0-9) for a user ID
     * @param userId The Matrix user ID (e.g., "@user:matrix.org")
     * @return An index from 0 to FALLBACK_COLOR_COUNT-1
     */
    fun getUserColorIndex(userId: String): Int {
        return userId.sumOf { it.code } % FALLBACK_COLOR_COUNT
    }
    
    /**
     * Get a deterministic hex color for a user ID
     * @param userId The Matrix user ID (e.g., "@user:matrix.org")
     * @return A hex color string without the # prefix (e.g., "d991de")
     */
    fun getUserColor(userId: String): String {
        return FALLBACK_COLORS[getUserColorIndex(userId)]
    }
    
    /**
     * Extract the fallback character from a display name or user ID
     * Handles Unicode properly and returns the first character uppercased
     * @param displayName The display name to extract from (can be null)
     * @param userId The Matrix user ID to fall back to
     * @param index The character index to extract (default 0 for first character)
     * @return The uppercase character, or empty string if extraction fails
     */
    fun getFallbackCharacter(displayName: String?, userId: String, index: Int = 0): String {
        val source = displayName?.takeIf { it.isNotBlank() } ?: userId
        if (source.isEmpty() || source.length <= index) {
            return ""
        }
        
        // Handle Unicode properly by converting to a list of code points
        val chars = source.codePoints().toArray().map { Character.toString(it) }
        return chars.getOrNull(index)?.uppercase() ?: ""
    }
    
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
            
            // QUALITY IMPROVEMENT: Use higher quality avatar settings
            // Construct HTTP URL: https://gomuks-backend/_gomuks/media/server/mediaId?thumbnail=avatar&size=256
            // For avatars, we want a higher quality thumbnail version
            val httpUrl = "$homeserverUrl/_gomuks/media/$server/$mediaId?thumbnail=avatar&size=256"
            
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
     * Gets the avatar URL without preemptive loading - lets Coil handle caching naturally
     * Returns either a cached file path, HTTP URL, or null (fallback handled by AsyncImage)
     * 
     * @param context Android context
     * @param mxcUrl The MXC URL from the room/user data
     * @param homeserverUrl The homeserver URL
     * @return A URL (file:// or http://) that can be used with Coil's AsyncImage, or null for fallback
     */
    fun getAvatarUrl(
        context: Context,
        mxcUrl: String?,
        homeserverUrl: String
    ): String? {
        // First, check if we have a cached file from MediaCache
        val cachedFile = if (mxcUrl != null) {
            MediaCache.getCachedFile(context, mxcUrl)
        } else {
            null
        }
        
        if (cachedFile != null) {
            return cachedFile.absolutePath
        }
        
        // If we have an MXC URL, convert to HTTP and let AsyncImage handle loading
        return mxcToHttpUrl(mxcUrl, homeserverUrl)
    }
    
    /**
     * Generate a local SVG fallback avatar as a data URI
     * This can be used if the server request fails
     * @param displayName The display name to extract the letter from
     * @param userId The Matrix user ID for color generation
     * @return A data URI containing an SVG fallback avatar
     */
    fun generateLocalFallbackAvatar(displayName: String?, userId: String): String {
        val color = getUserColor(userId)
        val letter = getFallbackCharacter(displayName, userId)
        
        return makeFallbackAvatarDataUri(color, letter)
    }
    
    /**
     * Creates an SVG data URI for a fallback avatar
     * Note: This should stay in sync with fallbackAvatarTemplate in cmd/gomuks/media.go
     */
    private fun makeFallbackAvatarDataUri(backgroundColor: String, fallbackCharacter: String): String {
        val escapedChar = escapeXmlChar(fallbackCharacter)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000">
  <rect x="0" y="0" width="1000" height="1000" fill="#$backgroundColor"/>
  <text x="500" y="750" text-anchor="middle" fill="#fff" font-weight="bold" font-size="666"
    font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
  >$escapedChar</text>
</svg>"""
        
        // URL encode the SVG for data URI
        val encoded = URLEncoder.encode(svg, "UTF-8")
            .replace("+", "%20") // Space should be %20 in URIs, not +
        
        return "data:image/svg+xml,$encoded"
    }
    
    /**
     * Escape special XML characters
     */
    private fun escapeXmlChar(char: String): String {
        return when (char) {
            "&" -> "&amp;"
            "<" -> "&lt;"
            ">" -> "&gt;"
            "\"" -> "&quot;"
            "'" -> "&apos;"
            else -> char
        }
    }
}
