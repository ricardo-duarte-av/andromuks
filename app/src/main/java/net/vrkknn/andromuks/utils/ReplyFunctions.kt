package net.vrkknn.andromuks.utils

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReplyInfo
import net.vrkknn.andromuks.TimelineEvent

/**
 * Displays a reply preview showing the original message being replied to.
 * 
 * This function renders a nested bubble structure where the replied-to message
 * is displayed in an inner bubble within the main reply bubble. The original
 * message is clickable and will scroll to the original message when tapped.
 * The preview shows the sender's name and a truncated version of the original
 * message content.
 * 
 * @param replyInfo ReplyInfo object containing sender and eventId of the original message
 * @param originalEvent TimelineEvent object of the original message being replied to
 * @param userProfileCache Map of user IDs to MemberProfile objects for display names
 * @param homeserverUrl Base URL of the Matrix homeserver (unused but kept for consistency)
 * @param authToken Authentication token (unused but kept for consistency)
 * @param isMine Whether this reply was sent by the current user (affects styling)
 * @param modifier Modifier to apply to the reply preview container
 * @param onOriginalMessageClick Callback function called when the original message is clicked
 */
@Composable
fun ReplyPreview(
    replyInfo: ReplyInfo,
    originalEvent: TimelineEvent?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    modifier: Modifier = Modifier,
    onOriginalMessageClick: () -> Unit = {}
) {
    val originalSender = originalEvent?.sender ?: replyInfo.sender
    val originalBody = originalEvent?.let { event ->
        when {
            event.type == "m.room.message" -> event.content?.optString("body", "")
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optString("body", "")
            else -> null
        }
    } ?: "Message not found"
    
    val memberProfile = userProfileCache[originalSender]
    val senderName = memberProfile?.displayName ?: originalSender.substringAfterLast(":")
    
    // Outer container for the reply
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Nested bubble for original message (clickable) - optimized spacing
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOriginalMessageClick() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    // Sender name
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    
                    // Original message content
                    Text(
                        text = originalBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 0.9
                    )
                }
            }
        }
    }
}
