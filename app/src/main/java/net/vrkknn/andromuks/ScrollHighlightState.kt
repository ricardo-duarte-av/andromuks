package net.vrkknn.andromuks

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Shared state that lets deeply nested message bubbles know which event should
 * temporarily highlight (e.g., after jumping to a reply target).
 */
data class ScrollHighlightState(
    val eventId: String? = null,
    val requestId: Int = 0
)

val LocalScrollHighlightState = staticCompositionLocalOf { ScrollHighlightState() }

