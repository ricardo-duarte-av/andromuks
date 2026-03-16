package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.ImageLoader
import net.vrkknn.andromuks.MessageReaction
import net.vrkknn.andromuks.ReactionEvent
import net.vrkknn.andromuks.utils.MediaUtils
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch



/**
 * Displays a single reaction badge showing either an emoji or an image.
 * 
 * @param emoji The emoji text or MXC URL for the reaction
 * @param count The number of users who reacted with this emoji
 * @param homeserverUrl The homeserver URL for MXC conversion
 * @param authToken The authentication token for MXC URL downloads
 * @param onClick Callback invoked when the reaction badge is clicked
 */
@Composable
fun ReactionBadge(
    emoji: String,
    count: Int,
    homeserverUrl: String,
    authToken: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit = {},
    shape: RoundedCornerShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
) {
    // Detect dark mode for custom shadow/glow
    val isDarkMode = isSystemInDarkTheme()
    
    Surface(
        shape = shape,
        color = backgroundColor,
        modifier = Modifier
            .height(20.dp)
            .clickable(onClick = onClick)
            // In dark mode, add a light glow effect
            .then(
                if (isDarkMode) {
                    Modifier.shadow(
                        elevation = 3.dp,
                        shape = shape,
                        ambientColor = Color.White.copy(alpha = 0.15f), // Light glow in dark mode
                        spotColor = Color.White.copy(alpha = 0.2f)
                    )
                } else {
                    Modifier
                }
            ),
        tonalElevation = 3.dp,  // Provides color changes for elevation
        shadowElevation = if (isDarkMode) 0.dp else 3.dp  // Shadows in light mode only
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (emoji.startsWith("mxc://")) {
                // Handle image reactions
                ImageReaction(emoji, homeserverUrl, authToken)
            } else {
                // Handle emoji reactions
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.8f
                    ),
                    color = contentColor
                )
            }
            
            if (count > 1) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.7f
                    ),
                    color = contentColor
                )
            }
        }
    }
}

/**
 * Displays an image reaction by loading the image from MXC URL using Coil (supports GIFs).
 * 
 * @param mxcUrl The MXC URL for the reaction image
 * @param homeserverUrl The homeserver URL for MXC conversion
 * @param authToken The authentication token for MXC URL downloads
 */
@Composable
fun ImageReaction(
    mxcUrl: String,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
) {
    val context = LocalContext.current
    
    
    // Convert MXC URL to HTTP URL
    val httpUrl = remember(mxcUrl, homeserverUrl) {
        MediaUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
    }
    
    // Use shared ImageLoader singleton with custom User-Agent
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    
    if (httpUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(httpUrl)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            imageLoader = imageLoader,
            contentDescription = "Reaction",
            modifier = modifier,
            contentScale = ContentScale.Crop,
            onSuccess = {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ImageReaction: Successfully loaded image for $mxcUrl")
            },
            onError = { }
        )
    } else {
        android.util.Log.e("Andromuks", "ImageReaction: Failed to convert MXC URL to HTTP URL: $mxcUrl")
        Text(
            text = "❌",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.8f
            )
        )
    }
}

/**
 * Displays reaction badges for a message showing emoji reactions and their counts.
 * 
 * This function renders a horizontal row of reaction badges, where each badge shows
 * an emoji or image and optionally a count if more than one user reacted with the same emoji.
 * The badges are styled with rounded corners and surface variant background.
 * 
 * @param eventId The ID of the message event these reactions belong to
 * @param reactions List of MessageReaction objects containing emoji and count data
 * @param homeserverUrl The homeserver URL for MXC conversion
 * @param authToken The authentication token for MXC URL downloads
 * @param onReactionClick Callback invoked when a reaction is clicked, receives the emoji/text/mxc:// string
 * @param modifier Modifier to apply to the reaction badges container
 */
@Composable
fun ReactionBadges(
    eventId: String,
    reactions: List<MessageReaction>,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    bubbleColor: Color? = null,
    onReactionClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (reactions.isNotEmpty()) {
        val backgroundColor =
            bubbleColor ?: if (isMine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        val contentColor =
            bubbleColor?.let { contentColorFor(it) } ?: if (isMine) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        val badgeShape =
            RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            )
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            reactions.forEach { reaction ->
                ReactionBadge(
                    emoji = reaction.emoji,
                    count = reaction.count,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    backgroundColor = backgroundColor,
                    contentColor = contentColor,
                    onClick = { onReactionClick(reaction.emoji) },
                    shape = badgeShape
                )
            }
        }
    }
}

/**
 * Processes a reaction event and returns the updated message reactions map.
 * 
 * This function handles adding, removing, or updating reactions for a specific message.
 * It manages the reaction state by either adding new reactions or updating existing ones
 * based on whether the user is already in the reaction list.
 * 
 * @param reactionEvent The reaction event to process
 * @param currentRoomId The ID of the current room (only processes reactions for current room)
 * @param messageReactions The current map of message reactions
 * @return Updated map of message reactions
 */
fun processReactionEvent(
    reactionEvent: ReactionEvent,
    currentRoomId: String?,
    messageReactions: Map<String, List<MessageReaction>>
): Map<String, List<MessageReaction>> {
    //android.util.Log.d("Andromuks", "ReactionFunctions: processReactionEvent called - currentRoomId: $currentRoomId, relatesToEventId: ${reactionEvent.relatesToEventId}, emoji: ${reactionEvent.emoji}, sender: ${reactionEvent.sender}")
    
    // Only process reactions for the current room
    if (currentRoomId != null) {
        val currentReactions = messageReactions.toMutableMap()
        val eventReactions = currentReactions[reactionEvent.relatesToEventId]?.toMutableList() ?: mutableListOf()
        
        // Find existing reaction with same emoji
        val existingReactionIndex = eventReactions.indexOfFirst { it.emoji == reactionEvent.emoji }
        
        if (existingReactionIndex >= 0) {
            // Update existing reaction
            val existingReaction = eventReactions[existingReactionIndex]
            val updatedUsers = existingReaction.users.toMutableList()
            val updatedUserReactions = existingReaction.userReactions.toMutableList()
            
            val existingUserIndex = updatedUserReactions.indexOfFirst { it.userId == reactionEvent.sender }
            
            if (existingUserIndex >= 0) {
                // Remove user from reaction
                updatedUsers.remove(reactionEvent.sender)
                updatedUserReactions.removeAt(existingUserIndex)
                if (updatedUserReactions.isEmpty()) {
                    eventReactions.removeAt(existingReactionIndex)
                } else {
                    eventReactions[existingReactionIndex] = existingReaction.copy(
                        count = updatedUserReactions.size,
                        users = updatedUsers,
                        userReactions = updatedUserReactions
                    )
                }
            } else {
                // Add user to reaction
                updatedUsers.add(reactionEvent.sender)
                updatedUserReactions.add(net.vrkknn.andromuks.UserReaction(reactionEvent.sender, reactionEvent.timestamp))
                eventReactions[existingReactionIndex] = existingReaction.copy(
                    count = updatedUserReactions.size,
                    users = updatedUsers,
                    userReactions = updatedUserReactions
                )
            }
        } else {
            // Add new reaction
            eventReactions.add(MessageReaction(
                emoji = reactionEvent.emoji,
                count = 1,
                users = listOf(reactionEvent.sender),
                userReactions = listOf(net.vrkknn.andromuks.UserReaction(reactionEvent.sender, reactionEvent.timestamp))
            ))
        }
        
        currentReactions[reactionEvent.relatesToEventId] = eventReactions
        //android.util.Log.d("Andromuks", "ReactionFunctions: Updated reactions for event ${reactionEvent.relatesToEventId}, new count: ${eventReactions.size}")
        return currentReactions
    }
    
    //android.util.Log.d("Andromuks", "ReactionFunctions: Skipping reaction processing - currentRoomId is null")
    return messageReactions
}
// --- Reaction Details Dialog ---

/**
 * Data class representing a flattened reaction with user info and timestamp for the list view
 */
data class FlattenedReaction(
    val userId: String,
    val emoji: String,
    val timestamp: Long
)

/**
 * Composable for showing reaction details in a floating dialog (similar to read receipts)
 */
@androidx.compose.runtime.Composable
fun ReactionDetailsDialog(
    reactions: List<MessageReaction>,
    homeserverUrl: String,
    authToken: String,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit = {},
    appViewModel: net.vrkknn.andromuks.AppViewModel? = null,
    roomId: String? = null
) {
    val flattenedReactions = remember(reactions) {
        reactions.flatMap { reaction ->
            reaction.userReactions.map { ur ->
                FlattenedReaction(ur.userId, reaction.emoji, ur.timestamp)
            }
        }.sortedByDescending { it.timestamp }
    }

    // OPPORTUNISTIC PROFILE LOADING: Request profiles for reaction users when dialog opens
    androidx.compose.runtime.LaunchedEffect(flattenedReactions.map { it.userId }, roomId, appViewModel?.memberUpdateCounter) {
        if (appViewModel != null && roomId != null && flattenedReactions.isNotEmpty()) {
            flattenedReactions.forEach { reaction ->
                val existingProfile = appViewModel.getUserProfile(reaction.userId, roomId)
                if (existingProfile == null) {
                    appViewModel.requestUserProfileOnDemand(reaction.userId, roomId)
                }
            }
        }
    }

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var isVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var isDismissing by androidx.compose.runtime.remember { mutableStateOf(false) }
    val enterDuration = 220
    val exitDuration = 160

    androidx.compose.runtime.LaunchedEffect(Unit) {
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
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = true) { dismissWithAnimation() },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isVisible,
                enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(durationMillis = enterDuration, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                    androidx.compose.animation.scaleIn(
                        initialScale = 0.85f,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = enterDuration, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    ),
                exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(durationMillis = exitDuration, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                    androidx.compose.animation.scaleOut(
                        targetScale = 0.85f,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = exitDuration, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { }, // Consume clicks on content to prevent dismissal
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Reactions",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        androidx.compose.foundation.lazy.LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            items(
                                items = flattenedReactions,
                                key = { "${it.userId}_${it.emoji}_${it.timestamp}" }
                            ) { reaction ->
                                val userProfile = appViewModel?.getUserProfile(reaction.userId, roomId)
                                ReactionListItem(
                                    reaction = reaction,
                                    userProfile = userProfile,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    onUserClick = { userId ->
                                        dismissWithAnimation {
                                            onUserClick(userId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionListItem(
    reaction: FlattenedReaction,
    userProfile: net.vrkknn.andromuks.MemberProfile?,
    homeserverUrl: String,
    authToken: String,
    onUserClick: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(reaction.userId) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        net.vrkknn.andromuks.ui.components.AvatarImage(
            mxcUrl = userProfile?.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = (userProfile?.displayName ?: reaction.userId).take(1),
            size = 40.dp,
            userId = reaction.userId,
            displayName = userProfile?.displayName
        )
        
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        ) {
            Text(
                text = userProfile?.displayName ?: reaction.userId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            
            Text(
                text = formatReactionTime(reaction.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        // Display the emoji/reaction at the end
        if (reaction.emoji.startsWith("mxc://")) {
            ImageReaction(
                mxcUrl = reaction.emoji, 
                homeserverUrl = homeserverUrl, 
                authToken = authToken,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            Text(
                text = reaction.emoji,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private fun formatReactionTime(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return formatter.format(date)
}
