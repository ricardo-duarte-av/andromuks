package net.vrkknn.andromuks.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * True while the timeline LazyColumn is scrolling (animated FAB scroll, thread return, or fast
 * user fling). Consumers use this to suspend disk and network image loads, showing only
 * memory-cached images or BlurHash placeholders until scrolling settles.
 */
val LocalIsScrollingFast = compositionLocalOf { false }
