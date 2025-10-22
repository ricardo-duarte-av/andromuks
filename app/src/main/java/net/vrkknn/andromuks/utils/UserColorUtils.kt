package net.vrkknn.andromuks.utils

import androidx.compose.ui.graphics.Color

/**
 * Utility class for generating consistent user colors based on user ID
 */
object UserColorUtils {
    
    // Define 10 consistent user colors (matching the webapp CSS colors)
    private val SENDER_COLORS = listOf(
        Color(0xFF89B4FA), // --sender-color-0 #89b4fa
        Color(0xFF74C7EC), // --sender-color-1 #74c7ec
        Color(0xFFEBA0AC), // --sender-color-2 #eba0ac
        Color(0xFFFAB387), // --sender-color-3 #fab387
        Color(0xFFF9E1AE), // --sender-color-4 #f9e1ae
        Color(0xFFA6E3A1), // --sender-color-5 #a6e3a1
        Color(0xFF94E2D5), // --sender-color-6 #94e2d5
        Color(0xFFF38BA8), // --sender-color-7 #f38ba8
        Color(0xFFCBA6F7), // --sender-color-8 #cba6f7
        Color(0xFF89B4FA)  // --sender-color-9 #89b4fa (same as 0 for 10 colors total)
    )
    
    private const val FALLBACK_COLOR_COUNT = 10
    
    /**
     * Get a consistent color for a user based on their user ID
     * This matches the JavaScript implementation from the webapp
     * 
     * @param userID The Matrix user ID (e.g., @username:servername.com)
     * @return A consistent Color for this user
     */
    fun getUserColor(userID: String): Color {
        val colorIndex = getUserColorIndex(userID)
        return SENDER_COLORS[colorIndex]
    }
    
    /**
     * Get the color index for a user ID (0-9)
     * This is the Kotlin equivalent of the JavaScript getUserColorIndex function
     * 
     * @param userID The Matrix user ID
     * @return An index from 0 to 9
     */
    private fun getUserColorIndex(userID: String): Int {
        return userID.sumOf { it.code } % FALLBACK_COLOR_COUNT
    }
}
