package net.vrkknn.andromuks.utils


import android.content.Context
import android.util.Log
import java.net.URLEncoder

object AvatarUtils {
    private const val FALLBACK_COLOR_COUNT = 10
    
    // Fallback colors matching the web client (hex codes converted to Color objects)
    // These should stay in sync with the colors used in the web client
    private val FALLBACK_COLORS = intArrayOf(
        0xFF4a9b89.toInt(), // teal
        0xFF9b4a8b.toInt(), // purple
        0xFF8b4a9b.toInt(), // violet
        0xFF4a8b9b.toInt(), // cyan
        0xFF9b8b4a.toInt(), // olive
        0xFF8b9b4a.toInt(), // lime
        0xFF4a8b4a.toInt(), // green
        0xFF9b4a4a.toInt(), // red
        0xFF4a4a9b.toInt(), // blue
        0xFFd991de.toInt()  // pink
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
     * Get a deterministic color for a user ID (Performance: Returns Int color directly)
     * @param userId The Matrix user ID (e.g., "@user:matrix.org")
     * @return A color int (ARGB)
     */
    fun getUserColor(userId: String): Int {
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
        val source = displayName?.takeIf { it.isNotBlank() } ?: userId.removePrefix("@").substringBefore(":")
        if (source.isEmpty() || source.length <= index) {
            return ""
        }
        
        // Handle Unicode properly by converting to a list of code points
        val chars = source.codePoints().toArray().map { Character.toString(it) }
        return chars.getOrNull(index)?.uppercase() ?: ""
    }
    
    /**
     * Sanitizes a Matrix room ID or user ID for use as Android shortcut ID or notification channel ID.
     * Android requires IDs to be stable, safe strings without special characters that could cause issues.
     * 
     * Rules:
     * - Removes or replaces special characters (!, :, @, etc.)
     * - Ensures ID is valid for Android's ID requirements
     * - Maintains uniqueness by preserving alphanumeric characters
     * - Limits length to prevent platform issues
     * 
     * @param roomId The Matrix room ID (e.g., "!abc123:matrix.org") or user ID
     * @param maxLength Maximum length for the sanitized ID (default 100, Android limit is ~1000 but shorter is safer)
     * @return Sanitized ID safe for use in shortcuts and channels (e.g., "abc123_matrix_org")
     */
    fun sanitizeIdForAndroid(roomId: String, maxLength: Int = 100): String {
        if (roomId.isEmpty()) return "unknown"
        
        // Replace special characters with safe alternatives
        // ! -> removed (room IDs start with !)
        // : -> _ (separator)
        // @ -> removed (user IDs start with @)
        // Keep alphanumeric and underscore
        val sanitized = roomId
            .removePrefix("!")  // Remove leading ! from room IDs
            .removePrefix("@")   // Remove leading @ from user IDs
            .replace(":", "_")   // Replace : with _
            .replace("/", "_")  // Replace / with _
            .replace("?", "_")   // Replace ? with _
            .replace("&", "_")   // Replace & with _
            .replace("=", "_")   // Replace = with _
            .replace(" ", "_")   // Replace spaces with _
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' } // Keep only safe characters
        
        // Ensure it's not empty after sanitization
        val result = if (sanitized.isEmpty()) {
            // Fallback: use hash of original ID
            "id_${roomId.hashCode().toString().replace("-", "n")}"
        } else {
            sanitized
        }
        
        // Limit length to prevent platform issues
        return if (result.length > maxLength) {
            result.take(maxLength - 8) + "_" + result.hashCode().toString().take(7).replace("-", "n")
        } else {
            result
        }
    }
    
    /**
     * Converts an MXC URL to a proper HTTP URL for loading avatars.
     * When [userId] is provided the backend fallback parameter is appended so the server
     * renders a coloured-initial placeholder when the media is unavailable.
     */
    fun mxcToHttpUrl(
        mxcUrl: String?,
        homeserverUrl: String,
        userId: String? = null,
        displayName: String? = null
    ): String? {
        return buildMediaUrl(mxcUrl, homeserverUrl, includeAvatarParams = true, userId = userId, displayName = displayName)
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
        homeserverUrl: String,
        userId: String? = null,
        displayName: String? = null
    ): String? {
        return mxcToHttpUrl(mxcUrl, homeserverUrl, userId, displayName)
    }
    
    /**
     * Converts an MXC URL to the original media URL without thumbnail parameters.
     * Useful for full-width backgrounds where we want the source image instead of a downsized avatar.
     */
    fun getFullImageUrl(
        context: Context,
        mxcUrl: String?,
        homeserverUrl: String
    ): String? {
        if (mxcUrl.isNullOrBlank() || mxcUrl == "null") return null
        return buildMediaUrl(mxcUrl, homeserverUrl, includeAvatarParams = false)
    }
    
    private fun buildMediaUrl(
        mxcUrl: String?,
        homeserverUrl: String,
        includeAvatarParams: Boolean,
        userId: String? = null,
        displayName: String? = null
    ): String? {
        if (mxcUrl.isNullOrBlank() || mxcUrl == "null") return null

        return try {
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

            val thumbnailParams = if (includeAvatarParams) {
                val fallbackParam = if (userId != null) {
                    val colorInt = getUserColor(userId)
                    val colorHex = String.format("#%06x", 0xFFFFFF and colorInt)
                    val letter = getFallbackCharacter(displayName, userId)
                    "&fallback=" + URLEncoder.encode("$colorHex:$letter", "UTF-8")
                } else ""
                "?thumbnail=avatar&size=256$fallbackParam"
            } else ""

            val rawUrl = "$homeserverUrl/_gomuks/media/$server/$mediaId$thumbnailParams"

            if (rawUrl.contains("/_gomuks/media/")) {
                val split = rawUrl.split("/_gomuks/media/", limit = 2)
                if (split.size == 2) {
                    "${split[0].lowercase()}/_gomuks/media/${split[1]}"
                } else {
                    rawUrl
                }
            } else {
                rawUrl.lowercase()
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "AvatarUtils: Error converting MXC URL: $mxcUrl", e)
            null
        }
    }
}
