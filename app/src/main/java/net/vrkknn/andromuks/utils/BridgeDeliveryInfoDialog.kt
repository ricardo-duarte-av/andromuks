package net.vrkknn.andromuks.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.BridgeDeliveryInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDeliveryTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * Floating dialog showing bridge delivery details for a sent message.
 *
 * @param deliveryInfo   The [BridgeDeliveryInfo] for the message (sentAt + per-user deliveries).
 * @param status         The current bridge send status string (e.g. "sent", "delivered",
 *                       "error_retriable", "error_permanent").
 * @param networkName    Display name of the bridge network (e.g. "WhatsApp"), or null.
 * @param homeserverUrl  Used for avatar resolution.
 * @param authToken      Auth token for avatar requests.
 * @param onDismiss      Called when the dialog should close.
 * @param onUserClick    Called with userId when a user row is tapped.
 * @param appViewModel   Used for profile lookups.
 * @param roomId         Room context for profile lookups.
 */
@Composable
fun BridgeDeliveryInfoDialog(
    deliveryInfo: BridgeDeliveryInfo,
    status: String,
    networkName: String?,
    homeserverUrl: String,
    authToken: String,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit = {},
    appViewModel: AppViewModel? = null,
    roomId: String? = null
) {
    // Sort deliveries by timestamp ascending so earliest recipient is first
    val sortedDeliveries = remember(deliveryInfo.deliveries) {
        deliveryInfo.deliveries.entries.sortedBy { it.value }
    }

    // Opportunistically load profiles for delivery recipients when dialog opens
    LaunchedEffect(sortedDeliveries.map { it.key }, roomId, appViewModel?.memberUpdateCounter) {
        if (appViewModel != null && roomId != null) {
            sortedDeliveries.forEach { (userId, _) ->
                if (appViewModel.getUserProfile(userId, roomId) == null) {
                    appViewModel.requestUserProfileOnDemand(userId, roomId)
                }
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var isVisible by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val enterDuration = 220
    val exitDuration = 160

    LaunchedEffect(Unit) {
        isDismissing = false
        isVisible = true
    }

    fun dismissWithAnimation(afterDismiss: () -> Unit = {}) {
        if (isDismissing) return
        isDismissing = true
        coroutineScope.launch {
            isVisible = false
            delay(exitDuration.toLong())
            onDismiss()
            afterDismiss()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { dismissWithAnimation() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = true) { dismissWithAnimation() },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isVisible,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = enterDuration,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                ) + androidx.compose.animation.scaleIn(
                    initialScale = 0.85f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = enterDuration,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = exitDuration,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                ) + androidx.compose.animation.scaleOut(
                    targetScale = 0.85f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = exitDuration,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { }, // consume clicks to prevent dismissal
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Title
                        Text(
                            text = "Delivery Info",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // --- Sent row ---
                        val networkLabel = if (!networkName.isNullOrBlank()) networkName else "other network"
                        val sentIcon = when (status) {
                            "delivered"       -> Icons.Filled.DoneAll
                            "sent"            -> Icons.Filled.Check
                            "error_retriable" -> Icons.Filled.Warning
                            else              -> Icons.Filled.Error
                        }
                        val sentIconTint = when (status) {
                            "error_retriable", "error_permanent" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = sentIcon,
                                contentDescription = null,
                                tint = sentIconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = when (status) {
                                        "error_retriable" -> "Failed to reach $networkLabel (will retry)"
                                        "error_permanent" -> "Failed to reach $networkLabel"
                                        else              -> "Sent to $networkLabel"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                if (deliveryInfo.sentAt != null) {
                                    Text(
                                        text = formatDeliveryTime(deliveryInfo.sentAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // --- Delivery list ---
                        if (sortedDeliveries.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Received by",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                            ) {
                                items(
                                    items = sortedDeliveries,
                                    key = { it.key }
                                ) { (userId, timestamp) ->
                                    val userProfile = remember(userId, appViewModel?.memberUpdateCounter) {
                                        appViewModel?.getUserProfile(userId, roomId)
                                    }
                                    DeliveryUserRow(
                                        userId = userId,
                                        timestamp = timestamp,
                                        userProfile = userProfile,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        onUserClick = { uid ->
                                            dismissWithAnimation { onUserClick(uid) }
                                        }
                                    )
                                }
                            }
                        } else if (status == "sent") {
                            // Sent but no delivery confirmed yet
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No delivery confirmation yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryUserRow(
    userId: String,
    timestamp: Long,
    userProfile: net.vrkknn.andromuks.MemberProfile?,
    homeserverUrl: String,
    authToken: String,
    onUserClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(userId) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        net.vrkknn.andromuks.ui.components.AvatarImage(
            mxcUrl = userProfile?.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = (userProfile?.displayName ?: userId).take(1),
            size = 40.dp,
            userId = userId,
            displayName = userProfile?.displayName
        )

        Row(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = userProfile?.displayName ?: userId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDeliveryTime(timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
