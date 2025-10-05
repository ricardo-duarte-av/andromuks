package net.vrkknn.andromuks.utils

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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

/**
 * Displays a single reaction badge showing either an emoji or an image.
 * 
 * @param emoji The emoji text or MXC URL for the reaction
 * @param count The number of users who reacted with this emoji
 * @param homeserverUrl The homeserver URL for MXC conversion
 * @param authToken The authentication token for MXC URL downloads
 */
@Composable
fun ReactionBadge(
    emoji: String,
    count: Int,
    homeserverUrl: String,
    authToken: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.height(20.dp)
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
                    )
                )
            }
            
            if (count > 1) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.7f
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    authToken: String
) {
    val context = LocalContext.current
    
    android.util.Log.d("Andromuks", "ImageReaction: Starting to load image from MXC URL: $mxcUrl")
    android.util.Log.d("Andromuks", "ImageReaction: Homeserver URL: $homeserverUrl")
    android.util.Log.d("Andromuks", "ImageReaction: Auth token available: ${authToken.isNotBlank()}")
    
    // Convert MXC URL to HTTP URL
    val httpUrl = remember(mxcUrl, homeserverUrl) {
        MediaUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
    }
    
    // Create ImageLoader with GIF support
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    
    android.util.Log.d("Andromuks", "ImageReaction: Converted HTTP URL: $httpUrl")
    
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
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
            onSuccess = {
                android.util.Log.d("Andromuks", "ImageReaction: Successfully loaded image for $mxcUrl")
            },
            onError = { state ->
                android.util.Log.e("Andromuks", "ImageReaction: Error loading image from $mxcUrl", state.result.throwable)
            }
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
 * @param modifier Modifier to apply to the reaction badges container
 */
@Composable
fun ReactionBadges(
    eventId: String,
    reactions: List<MessageReaction>,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier
) {
    if (reactions.isNotEmpty()) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            reactions.forEach { reaction ->
                ReactionBadge(
                    emoji = reaction.emoji,
                    count = reaction.count,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken
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
            
            if (reactionEvent.sender in updatedUsers) {
                // Remove user from reaction
                updatedUsers.remove(reactionEvent.sender)
                if (updatedUsers.isEmpty()) {
                    eventReactions.removeAt(existingReactionIndex)
                } else {
                    eventReactions[existingReactionIndex] = existingReaction.copy(
                        count = updatedUsers.size,
                        users = updatedUsers
                    )
                }
            } else {
                // Add user to reaction
                updatedUsers.add(reactionEvent.sender)
                eventReactions[existingReactionIndex] = existingReaction.copy(
                    count = updatedUsers.size,
                    users = updatedUsers
                )
            }
        } else {
            // Add new reaction
            eventReactions.add(MessageReaction(
                emoji = reactionEvent.emoji,
                count = 1,
                users = listOf(reactionEvent.sender)
            ))
        }
        
        currentReactions[reactionEvent.relatesToEventId] = eventReactions
        return currentReactions
    }
    
    return messageReactions
}
