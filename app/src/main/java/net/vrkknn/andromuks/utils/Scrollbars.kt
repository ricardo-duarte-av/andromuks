package net.vrkknn.andromuks.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Always-visible Material-3-style scrollbar overlays for [ScrollState]-backed scroll containers.
 *
 * Jetpack Compose (Android) has no built-in scrollbar, so these draw a rounded thumb on top of the
 * content via [drawWithContent]. Apply the modifier to the *viewport* element (the one whose size
 * is the visible area) — not to the scrolled content — so the thumb is sized and positioned
 * relative to what's on screen. The thumb is hidden only when there is nothing to scroll.
 */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color,
    thickness: Dp = 4.dp,
): Modifier = drawWithContent {
    drawContent()
    val maxValue = state.maxValue
    if (maxValue <= 0) return@drawWithContent
    val viewport = size.height
    val totalContent = viewport + maxValue
    val thumbHeight = (viewport / totalContent) * viewport
    val thumbOffset = (state.value.toFloat() / maxValue) * (viewport - thumbHeight)
    val widthPx = thickness.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - widthPx, thumbOffset),
        size = Size(widthPx, thumbHeight),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)
    )
}

/**
 * Vertical scrollbar for a [LazyListState]-backed list (e.g. a line-by-line JSON viewer). The thumb
 * is estimated from item indices, which is exact when items are uniform height (e.g. single-line
 * monospace rows) and a good approximation otherwise.
 */
fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color,
    thickness: Dp = 4.dp,
): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val totalItems = info.totalItemsCount
    val visibleItems = info.visibleItemsInfo.size
    if (totalItems == 0 || visibleItems >= totalItems) return@drawWithContent
    val viewport = size.height
    val thumbHeight = (visibleItems.toFloat() / totalItems) * viewport
    val thumbOffset = (state.firstVisibleItemIndex.toFloat() / totalItems) * viewport
    val widthPx = thickness.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - widthPx, thumbOffset),
        size = Size(widthPx, thumbHeight),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)
    )
}

fun Modifier.horizontalScrollbar(
    state: ScrollState,
    color: Color,
    thickness: Dp = 4.dp,
): Modifier = drawWithContent {
    drawContent()
    val maxValue = state.maxValue
    if (maxValue <= 0) return@drawWithContent
    val viewport = size.width
    val totalContent = viewport + maxValue
    val thumbWidth = (viewport / totalContent) * viewport
    val thumbOffset = (state.value.toFloat() / maxValue) * (viewport - thumbWidth)
    val heightPx = thickness.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(thumbOffset, size.height - heightPx),
        size = Size(thumbWidth, heightPx),
        cornerRadius = CornerRadius(heightPx / 2f, heightPx / 2f)
    )
}
