package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import net.vrkknn.andromuks.TimelineEvent
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
                size = 20.dp
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
                                if (!reason.isNullOrEmpty()) {
                                    append(": $reason")
                                }
                            }
                        )
                    }
                    "invite" -> {
                        val invitedUserId = content?.optString("state_key", "")
                        val invitedDisplayName = invitedUserId?.let { userId ->
                            appViewModel?.getMemberMap(roomId)?.get(userId)?.displayName
                                ?: appViewModel?.memberProfileCache?.get(userId)?.displayName
                        }
                        val invitedAvatarUrl = invitedUserId?.let { userId ->
                            appViewModel?.getMemberMap(roomId)?.get(userId)?.avatarUrl
                                ?: appViewModel?.memberProfileCache?.get(userId)?.avatarUrl
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
                                size = 16.dp
                            )
                            
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(invitedDisplayName ?: invitedUserId?.substringAfterLast(":") ?: "Unknown")
                                    }
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


@Composable
private fun NarratorText(
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
