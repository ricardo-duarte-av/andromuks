package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ui.components.AvatarImage

/**
 * Displays a typing notification area that shows which users are currently typing.
 * 
 * This function renders a fixed-height area that displays mini avatars of typing users
 * along with a text message indicating who is typing. The area is always reserved to prevent
 * layout shifts when users start/stop typing.
 * 
 * @param typingUsers List of user IDs who are currently typing
 * @param roomId The ID of the current room (for context)
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param userProfileCache Map of user IDs to MemberProfile objects for avatar and display name data
 */
@Composable
fun TypingNotificationArea(
    typingUsers: List<String>,
    roomId: String,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>
) {
    // Always reserve space for typing area
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp) // Fixed height for exclusive space
    ) {
        if (typingUsers.isNotEmpty()) {
            Row(
            modifier = Modifier
                .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Show mini avatars for all typing users
                typingUsers.forEachIndexed { index, user ->
                    val profile = userProfileCache[user]
                    val avatarUrl = profile?.avatarUrl
                    val displayName = profile?.displayName
                    
                    AvatarImage(
                        mxcUrl = avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = displayName ?: user.substringAfter("@").substringBefore(":"),
                        modifier = Modifier.size(12.dp), // Mini avatar size
                        userId = user,
                        displayName = displayName
                    )
                    
                    // Add spacing between avatars (except after the last one)
                    if (index < typingUsers.size - 1) {
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }
                
                Spacer(modifier = Modifier.width(4.dp))
                
                // Typing text (smaller and italic)
                Text(
                    text = when {
                        typingUsers.size == 1 -> {
                            val user = typingUsers.first()
                            val profile = userProfileCache[user]
                            val displayName = profile?.displayName
                            val userName = displayName ?: user.substringAfter("@").substringBefore(":")
                            "$userName is typing..."
                        }
                        else -> {
                            // Build comma-separated list of user names
                            val userNames = typingUsers.map { user ->
                                val profile = userProfileCache[user]
                                profile?.displayName ?: user.substringAfter("@").substringBefore(":")
                            }
                            
                            when (userNames.size) {
                                2 -> "${userNames[0]} and ${userNames[1]} are typing..."
                                else -> {
                                    // For 3+ users: "User1, User2, User3 and FinalUser are typing"
                                    val allButLast = userNames.dropLast(1).joinToString(", ")
                                    val lastUser = userNames.last()
                                    "$allButLast and $lastUser are typing..."
                                }
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.5f // Half size
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }
}
