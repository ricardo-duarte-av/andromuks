package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem

/**
 * Renders a single event (plus minimal context) in an overlay dialog.
 * Used for showing original content of redacted/deleted messages.
 */
@Composable
fun SingleEventRendererDialog(
    event: TimelineEvent?,
    contextEvents: List<TimelineEvent>,
    appViewModel: AppViewModel?,
    homeserverUrl: String,
    authToken: String,
    onDismiss: () -> Unit,
    error: String? = null
 ) {
    if (event == null && error == null) {
        return
    }

    // Combine event + context for relation helpers inside TimelineEventItem
    val allEvents = remember(event, contextEvents) {
        val list = mutableListOf<TimelineEvent>()
        list.addAll(contextEvents)
        event?.let { list.add(it) }
        list
    }

    val memberMap = remember(appViewModel, event, contextEvents) {
        val roomId = event?.roomId
        if (roomId != null && appViewModel != null) {
            // Use fallback-aware member map so display names/avatars resolve
            appViewModel.getMemberMapWithFallback(roomId, allEvents)
        } else {
            emptyMap()
        }
    }

    // Opportunistically request sender/self profiles if missing
    LaunchedEffect(appViewModel, event?.sender, event?.roomId) {
        val vm = appViewModel ?: return@LaunchedEffect
        val roomId = event?.roomId ?: return@LaunchedEffect
        val sender = event.sender
        val currentUserId = vm.currentUserId
        if (vm.getUserProfile(sender, roomId)?.displayName.isNullOrBlank()) {
            vm.requestUserProfileOnDemand(sender, roomId)
        }
        if (!currentUserId.isNullOrBlank() &&
            vm.getUserProfile(currentUserId, roomId)?.displayName.isNullOrBlank()
        ) {
            vm.requestUserProfileOnDemand(currentUserId, roomId)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            if (error != null || event == null) {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    confirmButton = {
                        TextButton(onClick = onDismiss) { Text("Close") }
                    },
                    title = { Text("Unable to load") },
                    text = { Text(error ?: "Original message not found") }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Original message",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Card(
                        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        TimelineEventItem(
                            event = event,
                            timelineEvents = allEvents,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            userProfileCache = memberMap,
                            isMine = appViewModel?.currentUserId == event.sender,
                            myUserId = appViewModel?.currentUserId,
                            isConsecutive = false,
                            appViewModel = appViewModel,
                            onScrollToMessage = {},
                            onReply = {},
                            onReact = {},
                            onEdit = {},
                            onDelete = {},
                            onUserClick = {},
                            onRoomLinkClick = {},
                            onThreadClick = {},
                            onNewBubbleAnimationStart = null
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

