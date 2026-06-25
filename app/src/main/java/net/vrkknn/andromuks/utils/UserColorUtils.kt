package net.vrkknn.andromuks.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.harmonize
import com.materialkolor.ktx.toColor
import net.vrkknn.andromuks.AppViewModel

/**
 * Utility class for generating consistent user colors based on user ID.
 *
 * Modes are selected by [AppViewModel.displayNameColorMode] ([DisplayNameColorMode]):
 *  - DYNAMIC (default): a theme-harmonized color derived from the active Material 3
 *    color scheme ([getUserColorHct]), so names stay legible and on-theme under
 *    dynamic color / light & dark.
 *  - FIXED: a 10-entry Catppuccin palette ([getUserColor]), matching the webapp.
 *  - THEME: no per-user color — a single theme color for me, another for everyone else.
 *
 * Call sites in composables should use [rememberUserColor], which picks the mode
 * and caches the result.
 */
object UserColorUtils {

    // Define 10 consistent user colors (matching the webapp CSS colors)
    private val SENDER_COLORS = listOf(
        Color(0xFF89B4FA), // --sender-color-0 #89b4fa (Blue)
        Color(0xFF74C7EC), // --sender-color-1 #74c7ec (Sapphire)
        Color(0xFFEBA0AC), // --sender-color-2 #eba0ac (Maroon)
        Color(0xFFFAB387), // --sender-color-3 #fab387 (Peach)
        Color(0xFFF9E1AE), // --sender-color-4 #f9e1ae (Yellow)
        Color(0xFFA6E3A1), // --sender-color-5 #a6e3a1 (Green)
        Color(0xFF94E2D5), // --sender-color-6 #94e2d5 (Teal)
        Color(0xFFF38BA8), // --sender-color-7 #f38ba8 (Red)
        Color(0xFFCBA6F7), // --sender-color-8 #cba6f7 (Mauve)
        Color(0xFFF5C2E7)  // --sender-color-9 #f5c2e7 (Pink — distinct 10th color)
    )

    private const val FALLBACK_COLOR_COUNT = 10

    // HCT generation parameters. Chroma is fixed for vividness; tone is pinned per
    // theme brightness so names contrast against the surface (lighter in dark themes,
    // darker in light themes).
    private const val HCT_CHROMA = 48.0
    private const val HCT_TONE_LIGHT = 45.0
    private const val HCT_TONE_DARK = 80.0

    /**
     * Get a consistent color for a user from the fixed Catppuccin palette.
     * Matches the JavaScript implementation from the webapp.
     *
     * @param userID The Matrix user ID (e.g., @username:servername.com)
     * @return A consistent Color for this user
     */
    fun getUserColor(userID: String): Color {
        val colorIndex = getUserColorIndex(userID)
        return SENDER_COLORS[colorIndex]
    }

    /**
     * Get the color index for a user ID (0-9).
     * This is the Kotlin equivalent of the JavaScript getUserColorIndex function.
     */
    private fun getUserColorIndex(userID: String): Int {
        return userID.sumOf { it.code } % FALLBACK_COLOR_COUNT
    }

    /**
     * Get a consistent, theme-harmonized color for a user via the HCT color space.
     *
     * The user ID hashes to a stable hue; chroma and tone are derived from the active
     * Material 3 scheme so the result contrasts correctly in light/dark. The color is
     * then harmonized toward [seed] (usually the scheme's primary) so it reads as part
     * of the current Material You theme rather than an arbitrary fixed color.
     *
     * @param userID The Matrix user ID
     * @param seed The color to harmonize toward (typically colorScheme.primary)
     * @param isDark Whether the active theme is dark
     */
    fun getUserColorHct(userID: String, seed: Color, isDark: Boolean): Color {
        val hue = (userID.sumOf { it.code } % 360).toDouble()
        val tone = if (isDark) HCT_TONE_DARK else HCT_TONE_LIGHT
        val base = Hct.from(hue, HCT_CHROMA, tone).toColor()
        return base.harmonize(seed)
    }
}

/**
 * How display names are colored in the timeline. Persisted via its [prefValue].
 */
enum class DisplayNameColorMode(val prefValue: String) {
    /** Per-user color derived from the active Material 3 theme via HCT, harmonized. Default. */
    DYNAMIC("dynamic"),
    /** Per-user color from the fixed Catppuccin palette (matches the webapp). */
    FIXED("fixed"),
    /** No per-user coloring: a single theme color, one for me, one for everyone else. */
    THEME("theme");

    companion object {
        fun fromPref(value: String?): DisplayNameColorMode =
            entries.firstOrNull { it.prefValue == value } ?: DYNAMIC
    }
}

/**
 * Resolve the display-name color for [userID], honoring the user's
 * [AppViewModel.displayNameColorMode] preference. Reads the active Material 3 color
 * scheme so the DYNAMIC and THEME modes react to dynamic color and light/dark changes.
 */
@Composable
fun rememberUserColor(userID: String, appViewModel: AppViewModel?): Color {
    val mode = appViewModel?.displayNameColorMode ?: DisplayNameColorMode.DYNAMIC
    val scheme = MaterialTheme.colorScheme
    // Derive darkness from the scheme itself so it stays correct regardless of how the
    // app decides dark mode (system, manual override, etc.).
    val isDark = scheme.surface.luminance() < 0.5f
    val seed = scheme.primary
    // THEME mode distinguishes only "me" vs "everyone else".
    val isMine = appViewModel != null && appViewModel.currentUserId.isNotBlank() &&
        appViewModel.currentUserId == userID
    val mineColor = scheme.primary
    val otherColor = scheme.tertiary
    return remember(userID, mode, seed, isDark, isMine, mineColor, otherColor) {
        when (mode) {
            DisplayNameColorMode.FIXED -> UserColorUtils.getUserColor(userID)
            DisplayNameColorMode.DYNAMIC -> UserColorUtils.getUserColorHct(userID, seed, isDark)
            DisplayNameColorMode.THEME -> if (isMine) mineColor else otherColor
        }
    }
}
