package net.vrkknn.andromuks.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Material 3 Expressive scroll indicator - a vertical pill that moves down as the list is scrolled.
 * 
 * @param listState The LazyListState to observe for scroll position
 * @param modifier Modifier for the indicator container
 * @param indicatorColor Color of the scroll indicator pill
 * @param hideDelayMs Delay in milliseconds before hiding the indicator after scrolling stops
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveScrollIndicator(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    indicatorColor: Color = LoadingIndicatorDefaults.indicatorColor,
    hideDelayMs: Long = 1500L
) {
    var isVisible by remember { mutableStateOf(false) }
    
    // Track scroll position changes
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect {
                isVisible = true
                
                // Hide after scrolling stops
                delay(hideDelayMs)
                isVisible = false
            }
    }
    
    // Calculate scroll progress (0f to 1f) based on actual scroll position
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val viewportHeight = layoutInfo.viewportSize.height
    val firstVisibleIndex = listState.firstVisibleItemIndex
    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisibleIndex
    
    val scrollProgress = if (totalItems == 0 || viewportHeight == 0) {
        0f
    } else {
        // Check if we're at the bottom (last item is visible or past it)
        val isAtBottom = lastVisibleIndex >= totalItems - 1
        
        if (isAtBottom) {
            1f // At bottom
        } else {
            // Calculate progress based on item indices
            // This is more accurate than height estimation
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                0f
            } else {
                // Use item index ratio as base, but adjust for partial visibility
                val itemIndexProgress = firstVisibleIndex.toFloat() / (totalItems - 1).coerceAtLeast(1)
                
                // Adjust for scroll offset within the first visible item
                val firstItem = visibleItems.firstOrNull()
                val scrollOffset = listState.firstVisibleItemScrollOffset
                val itemOffsetProgress = if (firstItem != null && firstItem.size > 0) {
                    (scrollOffset.toFloat() / firstItem.size.toFloat()).coerceIn(0f, 1f) / totalItems
                } else {
                    0f
                }
                
                // Combine index progress with offset progress
                (itemIndexProgress + itemOffsetProgress).coerceIn(0f, 0.98f) // Cap at 0.98 until actually at bottom
            }
        }
    }
    
    val animatedProgress by animateFloatAsState(
        targetValue = scrollProgress,
        animationSpec = tween(durationMillis = 200),
        label = "scroll_progress"
    )
    
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(200)) + scaleIn(
            initialScale = 0.8f,
            animationSpec = tween(200)
        ),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(
            targetScale = 0.8f,
            animationSpec = tween(300)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            // Vertical pill that moves down as list scrolls
            val pillPosition = animatedProgress.coerceIn(0f, 1f)
            val availableHeight = viewportHeight - 16.dp.value // Account for padding
            val pillSize = 40.dp // Height of the pill
            
            Box(
                modifier = Modifier
                    .offset(y = (pillPosition * (availableHeight - pillSize.value)).dp)
                    .width(4.dp)
                    .height(pillSize)
                    .background(
                        indicatorColor,
                        CircleShape
                    )
            )
        }
    }
}

