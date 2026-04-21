package net.vrkknn.andromuks.utils

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A pill-shaped overlay that slides down + fades in below the header to show the date of
 * the oldest visible event. Behaviour:
 *  - Appears (slide-down + fade-in) when the user scrolls toward older content and the
 *    oldest visible event is not from today.
 *  - Stays visible while the user is actively scrolling through old content (the 3-second
 *    timer resets on every item-boundary crossing).
 *  - Disappears immediately (slide-up + fade-out) when the user scrolls toward newer content.
 *  - Auto-hides (slide-up + fade-out) after 3 seconds of no scroll activity.
 *
 * @param oldestVisibleDate formatted "dd / MM / yyyy" date of the oldest on-screen event
 * @param scrollPositionKey changes on every item-boundary scroll crossing
 * @param reverseScrollLayout pass `true` for reverseLayout LazyColumns (Room/Bubble timelines)
 *   where firstVisibleItemIndex *increases* when scrolling toward older content, and `false`
 *   for top-down layouts (Thread/EventContext) where it *decreases*.
 */
@Composable
fun StickyDateIndicator(
    oldestVisibleDate: String?,
    scrollPositionKey: Int = 0,
    reverseScrollLayout: Boolean = true,
    modifier: Modifier = Modifier
) {
    val today = remember {
        SimpleDateFormat("dd / MM / yyyy", Locale.getDefault()).format(Date())
    }

    var showPill by remember { mutableStateOf(false) }
    var displayDate by remember { mutableStateOf("") }
    // Tracks the previous scroll key so we can detect direction of travel.
    var prevScrollKey by remember { mutableIntStateOf(scrollPositionKey) }

    // Re-fires on every item-boundary scroll crossing *and* on date changes.
    LaunchedEffect(oldestVisibleDate, scrollPositionKey) {
        val keyDelta = scrollPositionKey - prevScrollKey
        prevScrollKey = scrollPositionKey

        // Determine if the user is scrolling toward newer content.
        // reverseLayout  → key decreasing = toward newer (index 0 = newest at bottom)
        // normal layout  → key increasing = toward newer (index 0 = oldest at top)
        val movingTowardNewer = if (reverseScrollLayout) keyDelta < 0 else keyDelta > 0

        when {
            movingTowardNewer -> {
                // Follow the scroll direction — hide immediately.
                showPill = false
            }
            oldestVisibleDate != null && oldestVisibleDate != today -> {
                // At old content and either stationary or scrolling toward older — show pill.
                displayDate = oldestVisibleDate
                showPill = true
                delay(3000)
                showPill = false
            }
            else -> {
                // Today's content or no date — ensure pill is hidden.
                showPill = false
            }
        }
    }

    AnimatedVisibility(
        visible = showPill,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            // Slightly transparent so timeline content is visible through it
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.82f),
            tonalElevation = 4.dp,
            shadowElevation = 2.dp
        ) {
            Text(
                text = displayDate,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}
