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
     * Loads an avatar with automatic fallback to generated SVG if the MXC URL fails
     * This function uses Coil's caching mechanism and returns either the server avatar
     * or a locally generated SVG fallback
     * 
     * @param context Android context for Coil ImageLoader
     * @param mxcUrl The MXC URL from the room/user data
     * @param homeserverUrl The homeserver URL
     * @param authToken The authentication token for downloading from the server
     * @param displayName The display name for fallback generation (optional)
     * @param userId The Matrix user ID for fallback generation
     * @return A URL (http:// or data:) that can be used with Coil's AsyncImage
     */
    suspend fun getAvatarUrlWithFallback(
        context: Context,
        mxcUrl: String?,
        homeserverUrl: String,
        authToken: String,
        displayName: String?,
        userId: String
    ): String {
        // First, check if we have a cached file from MediaCache
        val cachedFile = if (mxcUrl != null) {
            MediaCache.getCachedFile(context, mxcUrl)
        } else {
            null
        }
        
        if (cachedFile != null) {
            Log.d("Andromuks", "AvatarUtils: Using cached file for $mxcUrl")
            return cachedFile.absolutePath
        }
        
        // If we have an MXC URL, try to convert and load it
        val httpUrl = mxcToHttpUrl(mxcUrl, homeserverUrl)
        
        if (httpUrl != null) {
            // Try to load the image with Coil to verify it works and cache it
            val imageLoader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(httpUrl)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .build()
            
            when (val result = imageLoader.execute(request)) {
                is SuccessResult -> {
                    Log.d("Andromuks", "AvatarUtils: Successfully loaded avatar from $httpUrl")
                    // Download and cache for future use
                    if (mxcUrl != null) {
                        MediaCache.downloadAndCache(context, mxcUrl, httpUrl, authToken)
                    }
                    return httpUrl
                }
                is ErrorResult -> {
                    Log.w("Andromuks", "AvatarUtils: Failed to load avatar from $httpUrl: ${result.throwable.message}")
                    // Fall through to generate SVG fallback
                }
            }
        }
        
        // No valid MXC URL or download failed, generate local fallback
        Log.d("Andromuks", "AvatarUtils: Generating SVG fallback for user: $userId")
        return generateLocalFallbackAvatar(displayName, userId)
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
