package net.vrkknn.andromuks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Startup loading screen with Material 3 spinner and progress message list
 * Shows the last 10 progress messages with newest on top
 */
@Composable
fun StartupLoadingScreen(
    progressMessages: List<String>,
    modifier: Modifier = Modifier,
    topContent: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            if (topContent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    topContent()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Material 3 spinner with primary color
                    ExpressiveLoadingIndicator(
                        modifier = Modifier.size(72.dp),
                        indicatorColor = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Progress messages list (last 10, newest on top)
            // Note: progressMessages already has newest first, so we display them in order
            val displayMessages = progressMessages.take(10)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 320.dp),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
            ) {
                if (displayMessages.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Top
                    ) {
                        items(displayMessages) { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 16.dp)
                            )
                        }
                    }
                } else {
                    // Fallback message if no progress messages yet
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Starting...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

