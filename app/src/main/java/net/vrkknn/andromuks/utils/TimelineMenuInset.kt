package net.vrkknn.andromuks.utils

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import net.vrkknn.andromuks.ui.theme.scaledTweenMs

// Bottom inset reserved in the timeline slot while the attachment / message menu bar is open.
//
// AttachmentMenuBar and MessageMenuBar are overlay siblings of the timeline Column — they are
// translated above the composer with graphicsLayer and take part in no layout pass, so without this
// inset they simply cover the bottom ~100 dp of the LazyColumn (including the very message that was
// long-pressed). Shrinking the timeline slot by the bar's height moves the last message up into the
// visible area instead.
//
// Because every timeline list here is reverseLayout = true (index 0 == visual bottom), shrinking the
// viewport from the bottom keeps index 0 pinned to the new bottom edge on its own — no extra
// scrollToItem and no interference with the screens' isAttachedToBottom tracking.

/**
 * Height the menu bars occupy before either one has been measured: `12.dp` top padding + `56.dp`
 * button + `4.dp` spacer + one `labelSmall` line + `12.dp` bottom padding. Both bars share that
 * structure exactly (see [AttachmentMenuBar] and `MessageMenuBar`). Only used for the very first
 * open; after that the real measured height takes over, which also covers labels wrapping to two
 * lines at large font scales.
 */
@Composable
fun estimatedMenuBarHeight(): Dp {
    val lineHeight = MaterialTheme.typography.labelSmall.lineHeight
    val labelHeight = if (lineHeight.isSpecified) with(LocalDensity.current) { lineHeight.toDp() } else 16.dp
    return 12.dp + 56.dp + 4.dp + labelHeight + 12.dp
}

/**
 * Animated bottom inset for the timeline slot: [menuBarHeight] while [menuOpen], `0.dp` otherwise.
 *
 * The timing mirrors the bars' own slide animation so the list and the bar move together — in
 * immediately over 120 ms, out over 120 ms after the 500 ms button fade-out has run. Durations go
 * through [scaledTweenMs] so the inset honours the app's animation-speed setting.
 */
@Composable
fun rememberTimelineMenuInset(menuOpen: Boolean, menuBarHeight: Dp): Dp {
    val inset by animateDpAsState(
        targetValue = if (menuOpen) menuBarHeight else 0.dp,
        animationSpec = if (menuOpen) {
            tween(durationMillis = scaledTweenMs(120))
        } else {
            tween(durationMillis = scaledTweenMs(120), delayMillis = scaledTweenMs(500))
        },
        label = "timelineMenuInset",
    )
    return inset
}
