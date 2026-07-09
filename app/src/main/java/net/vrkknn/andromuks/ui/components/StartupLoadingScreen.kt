package net.vrkknn.andromuks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Startup loading screen shown during cold start.
 *
 * Renders the current user's avatar (via [topContent], which carries the shared-element tag so it
 * flies into the RoomListScreen header) and their global display name below it. The avatar is
 * framed with Material 3 Expressive wavy bezels by the caller's morph mask. The former progress
 * message box has been removed — progress messages are no longer surfaced to the user.
 */
@Composable
fun StartupLoadingScreen(modifier: Modifier = Modifier, displayName: String? = null, topContent: (@Composable () -> Unit)? = null) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
        ) {
            if (topContent != null) {
                topContent()
            } else {
                ExpressiveLoadingIndicator(
                    modifier = Modifier.size(96.dp),
                    indicatorColor = MaterialTheme.colorScheme.primary,
                )
            }

            if (!displayName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
