package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.ui.components.AvatarImage

@Composable
fun SystemEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel? = null,
    roomId: String
) {
    val content = event.content
    val eventType = event.type
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side - centered content
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Small avatar for the actor
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = displayName,
                size = 20.dp,
                userId = event.sender,
                displayName = displayName
            )
        
        // Narrator text
        when (eventType) {
            "m.room.member" -> {
                val membership = content?.optString("membership", "")
                val targetDisplayName = content?.optString("displayname", "")
                val reason = content?.optString("reason", "")
                
                when (membership) {
                    "join" -> {
                        // Check if this is a profile change vs actual join
                        val unsigned = event.unsigned
                        val prevContent = unsigned?.optJSONObject("prev_content")
                        val prevDisplayName = prevContent?.optString("displayname", "")
                        val prevAvatar = prevContent?.optString("avatar_url", "")
                        
                        if (prevDisplayName != null && prevDisplayName != targetDisplayName) {
                            // Display name change
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" changed their display name")
                                }
                            )
                        } else if (prevAvatar != null && prevAvatar != content?.optString("avatar_url", "")) {
                            // Avatar change
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" changed their avatar")
                                }
                            )
                        } else if (prevDisplayName != null && prevAvatar != null && 
                                 (prevDisplayName != targetDisplayName || prevAvatar != content?.optString("avatar_url", ""))) {
                            // Both display name and avatar change
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" changed their profile")
                                }
                            )
                        } else {
                            // Actual join
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" joined the room")
                                }
                            )
                        }
                    }
                    "leave" -> {
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" left the room")
                                // Safe null check for reason string (was causing compilation error)
            if (!reason.isNullOrEmpty()) {
                                    append(": $reason")
                                }
                            }
                        )
                    }
                    "invite" -> {
                        val invitedUserId = content?.optString("state_key", "")
        // Get invited user's display name and avatar using the getMemberProfile function
        // (Fixed from previous incorrect memberProfileCache usage)
        val invitedDisplayName = invitedUserId?.let { userId ->
            appViewModel?.getMemberProfile(roomId, userId)?.displayName
        }
        val invitedAvatarUrl = invitedUserId?.let { userId ->
            appViewModel?.getMemberProfile(roomId, userId)?.avatarUrl
        }
                        
                        // Request profile if not found
                        if (invitedDisplayName == null && appViewModel != null && invitedUserId != null) {
                            appViewModel.requestUserProfile(invitedUserId)
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" invited ")
                                }
                            )
                            
                            // Invited user avatar
                            AvatarImage(
                                mxcUrl = invitedAvatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                fallbackText = invitedDisplayName ?: invitedUserId?.substringAfterLast(":") ?: "?",
                                size = 16.dp,
                                userId = invitedUserId,
                                displayName = invitedDisplayName
                            )
                            
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(invitedDisplayName ?: invitedUserId?.substringAfterLast(":") ?: "Unknown")
                                    }
                                    // Safe null check for reason string (was causing compilation error)
            if (!reason.isNullOrEmpty()) {
                                        append(" for $reason")
                                    }
                                }
                            )
                        }
                    }
                    "ban" -> {
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" banned ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(targetDisplayName)
                                }
                                // Safe null check for reason string (was causing compilation error)
            if (!reason.isNullOrEmpty()) {
                                    append(": $reason")
                                }
                            }
                        )
                    }
                    "kick" -> {
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" kicked ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(targetDisplayName)
                                }
                                // Safe null check for reason string (was causing compilation error)
            if (!reason.isNullOrEmpty()) {
                                    append(": $reason")
                                }
                            }
                        )
                    }
                }
            }
            "m.room.name" -> {
                val roomName = content?.optString("name", "")
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room name to ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(roomName)
                        }
                    }
                )
            }
            "m.room.topic" -> {
                val topic = content?.optString("topic", "")
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room topic")
                    }
                )
            }
            "m.room.avatar" -> {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room avatar")
                    }
                )
            }
            "m.room.pinned_events" -> {
                val pinnedArray = content?.optJSONArray("pinned")
                val pinnedEventId = pinnedArray?.optString(0)
                if (pinnedEventId != null) {
                    PinnedEventNarration(
                        displayName = displayName,
                        avatarUrl = avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        pinnedEventId = pinnedEventId,
                        appViewModel = appViewModel,
                        roomId = roomId
                    )
                } else {
                    NarratorText(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(displayName)
                            }
                            append(" pinned an event")
                        }
                    )
                }
            }
            else -> {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" performed an action")
                    }
                )
            }
        }
        }
        
        // Right side - timestamp
        Text(
            text = formatTimestamp(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
    }
}


/**
 * Simple narrator text composable for displaying system event messages.
 * 
 * This function renders annotated text with italic styling for system events
 * like user joins, leaves, room name changes, etc. Used by the system event
 * narrator to display formatted text about room activities.
 * 
 * @param text The annotated text to display with formatting
 */
@Composable
fun NarratorText(
    text: AnnotatedString
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic
    )
}



private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(date)
}

@Composable
private fun PinnedEventNarration(
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    pinnedEventId: String,
    appViewModel: AppViewModel?,
    roomId: String
) {
    var showDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<TimelineEvent?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NarratorText(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(displayName)
                }
                append(" pinned an event: ")
            }
        )

        Text(
            text = pinnedEventId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                if (event == null && !isLoading && appViewModel != null) {
                    isLoading = true
                    errorMessage = null
                    appViewModel.getEvent(roomId, pinnedEventId) { fetchedEvent ->
                        event = fetchedEvent
                        if (fetchedEvent == null) {
                            errorMessage = "Unable to load event (404)."
                        }
                        isLoading = false
                        showDialog = true
                    }
                } else {
                    showDialog = true
                }
            }
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
            },
            title = { Text("Pinned Event") },
            text = {
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    errorMessage != null -> {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                    }
                    event != null -> {
                        TimelineEventItem(
                            event = event!!,
                            timelineEvents = listOf(event!!),
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            userProfileCache = appViewModel?.getMemberMap(roomId) ?: emptyMap(),
                            isMine = false,
                            myUserId = appViewModel?.currentUserId
                        )
                    }
                    else -> {
                        Text("Event data not available.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
