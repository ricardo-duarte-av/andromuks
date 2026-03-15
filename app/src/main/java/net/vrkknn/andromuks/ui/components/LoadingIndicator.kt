package net.vrkknn.andromuks.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    val infiniteTransition = rememberInfiniteTransition(label = "expressive_loading")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "expressive_loading_progress"
    )

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
    shape: Shape = CircleShape,
    progress: Map<String, Float> = emptyMap()
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (progress.isNotEmpty()) {
                if (net.vrkknn.andromuks.BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "ExpressiveStatusRow: Rendering ${progress.size} progress bars: $progress")
                }
                Spacer(modifier = Modifier.height(6.dp))
                progress.entries.forEachIndexed { index, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (entry.key == "thumbnail") "THUMB" else "MAIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.width(42.dp)
                        )
                        LinearWavyProgressIndicator(
                            progress = { entry.value },
                            modifier = Modifier.weight(1f).height(6.dp),
                            color = indicatorColor,
                            trackColor = indicatorColor.copy(alpha = 0.2f)
                        )
                        Text(
                            text = "${(entry.value * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                    }
                    if (index < progress.size - 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

