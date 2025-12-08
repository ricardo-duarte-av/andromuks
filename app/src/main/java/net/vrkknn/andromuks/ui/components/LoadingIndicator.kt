package net.vrkknn.andromuks.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    indicatorColor: Color = LoadingIndicatorDefaults.indicatorColor
) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        withFrameNanos { start = it }
        while (true) {
            withFrameNanos { now ->
                val elapsedMs = (now - start) / 1_000_000f
                // Loop the value to prevent runaway growth and avoid visible resets.
                progress = (elapsedMs % 1600f) / 1600f
            }
        }
    }

    LoadingIndicator(
        progress = { progress },
        modifier = modifier,
        color = indicatorColor,
        // Use the looping polygon set to avoid visible resets at the end of each cycle
        polygons = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainedExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    indicatorColor: Color = LoadingIndicatorDefaults.indicatorColor,
    contentPadding: Dp = 8.dp
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        tonalElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            ExpressiveLoadingIndicator(
                modifier = Modifier.fillMaxSize(),
                indicatorColor = indicatorColor
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveStatusRow(
    text: String,
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 32.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    shape: Shape = CircleShape
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ContainedExpressiveLoadingIndicator(
            modifier = Modifier.size(indicatorSize),
            shape = shape,
            containerColor = containerColor,
            indicatorColor = indicatorColor,
            contentPadding = indicatorSize.coerceAtLeast(8.dp) / 4
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

