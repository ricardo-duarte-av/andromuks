package net.vrkknn.andromuks.utils

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding // Added to fix unresolved reference error
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReadReceipt
import net.vrkknn.andromuks.ui.components.AvatarImage

/**
 * Displays read receipt avatars for a message in an inline format.
 * 
 * This function renders small overlapping avatars of users who have read the message,
 * excluding the current user and the message sender. It shows up to 3 avatars with
 * a count indicator if there are more than 3 receipts.
 * 
 * @param receipts List of ReadReceipt objects containing user IDs, event IDs, and timestamps
 * @param userProfileCache Map of user IDs to MemberProfile objects for avatar and display name data
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param appViewModel AppViewModel instance for accessing current user ID and other app state
 * @param messageSender User ID of the message sender (excluded from receipt display)
 */
@Composable
fun InlineReadReceiptAvatars(
    receipts: List<ReadReceipt>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel,
    messageSender: String
) {
    // Filter out receipts from the current user, from the message sender, and limit to 3 avatars
    val otherUsersReceipts = receipts
        .filter { it.userId != appViewModel.currentUserId && it.userId != messageSender }
        .distinctBy { it.userId }
        .take(3)
    
    if (otherUsersReceipts.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(-4.dp), // Overlap avatars slightly
            verticalAlignment = Alignment.CenterVertically
        ) {
            otherUsersReceipts.forEach { receipt ->
                val profile = userProfileCache[receipt.userId]
                val displayName = profile?.displayName
                val avatarUrl = profile?.avatarUrl
                
                AvatarImage(
                    mxcUrl = avatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = (displayName ?: receipt.userId.substringAfter("@").substringBefore(":")).take(1),
                    size = 16.dp,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        )
                )
            }
            
            // Show count if there are more than 3 receipts
            if (receipts.size > 3) {
                Text(
                    text = "+${receipts.size - 3}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7f
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}
