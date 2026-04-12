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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
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
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.MediaUtils
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
                // Handle image reactions — adaptive width so non-square images aren't squished
                ImageReaction(emoji, homeserverUrl, authToken, adaptiveWidth = true)
            } else {
                // Handle emoji/text reactions — maxLines=1 ensures consistent single-line
                // width during SubcomposeLayout measurement passes
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.8f
                    ),
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
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
 * @param modifier Modifier applied when [adaptiveWidth] is false (default: 16×16 dp square)
 * @param adaptiveWidth When true, the image is sized at 16 dp height with width determined by
 *   the image's natural aspect ratio (capped at 40 dp). Used inside reaction badges so that
 *   non-square images are not squished into a single-emoji-sized square.
 */
@Composable
fun ImageReaction(
    mxcUrl: String,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
    adaptiveWidth: Boolean = false,
) {
    val context = LocalContext.current

    val httpUrl = remember(mxcUrl, homeserverUrl) {
        MediaUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
    }

    val imageLoader = remember { ImageLoaderSingleton.get(context) }

    // For badge context (adaptiveWidth = true): track the loaded image's aspect ratio so the
    // badge can be sized proportionally. Starts null (square fallback) until the first
    // successful load; the SubcomposeLayout in ReactionBadges remeasures automatically when
    // this state changes (usually on the first cached-load frame).
    var aspectRatio by remember(mxcUrl) { mutableStateOf<Float?>(null) }

    val resolvedModifier = if (adaptiveWidth) {
        if (aspectRatio != null) {
            // Natural aspect-ratio width at 16 dp height, capped so very wide images
            // (e.g. 5:1 banners) don't break the badge row layout.
            Modifier
                .height(16.dp)
                .widthIn(min = 16.dp, max = 40.dp)
                .aspectRatio(aspectRatio!!, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(4.dp))
        } else {
            // Fallback: square until the image loads (avoids measuring a zero-width badge)
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
        }
    } else {
        modifier
    }

    if (httpUrl != null) {
        var bypassCoilCache by remember(mxcUrl) { mutableStateOf(false) }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(httpUrl)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .memoryCachePolicy(if (bypassCoilCache) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .diskCachePolicy(if (bypassCoilCache) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .build(),
            imageLoader = imageLoader,
            contentDescription = "Reaction",
            modifier = resolvedModifier,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ImageReaction: Successfully loaded image for $mxcUrl")
                if (adaptiveWidth && aspectRatio == null) {
                    val iSize = state.painter.intrinsicSize
                    if (iSize.width.isFinite() && iSize.height.isFinite() && iSize.height > 0f) {
                        aspectRatio = iSize.width / iSize.height
                    }
                }
            },
            onError = {
                bypassCoilCache = true
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
    /**
     * Optional width (in pixels) of the message bubble. When > 0, reactions wrap at this
     * width so they never extend past the bubble edge. When 0, reactions wrap at the
     * available layout width instead.
     */
    bubbleWidthPx: Int = 0,
    onReactionClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

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

    // Row 1: flat-top badges that visually "hang" from the bubble bottom.
    // Rows 2+: fully-rounded pills for overflow reactions.
    val badgeShape = RoundedCornerShape(
        topStart = 0.dp, topEnd = 0.dp,
        bottomStart = 12.dp, bottomEnd = 12.dp
    )
    val pillShape = RoundedCornerShape(12.dp)

    SubcomposeLayout(modifier = modifier) { constraints ->
        val spacingPx = 4.dp.roundToPx()
        val rowSpacingPx = 2.dp.roundToPx()
        val maxWidth = when {
            bubbleWidthPx > 0 -> bubbleWidthPx
            constraints.hasBoundedWidth -> constraints.maxWidth
            else -> Int.MAX_VALUE
        }

        // --- Pass 1: measure all badges (shape doesn't affect width) to get widths ---
        val measuredWidths = subcompose("measure") {
            reactions.forEach { reaction ->
                ReactionBadge(
                    emoji = reaction.emoji,
                    count = reaction.count,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    backgroundColor = backgroundColor,
                    contentColor = contentColor,
                    shape = pillShape,
                )
            }
        }.map { it.measure(Constraints()).width }

        // --- Assign reactions to rows ---
        val rows = mutableListOf<MutableList<Int>>()
        var currentRow = mutableListOf<Int>()
        var currentRowWidth = 0

        for (i in reactions.indices) {
            val w = measuredWidths.getOrElse(i) { 0 }
            val needed = if (currentRow.isEmpty()) w else w + spacingPx
            if (currentRow.isNotEmpty() && currentRowWidth + needed > maxWidth) {
                rows.add(currentRow)
                currentRow = mutableListOf(i)
                currentRowWidth = w
            } else {
                currentRow.add(i)
                currentRowWidth += needed
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        // --- Compute layout dimensions ---
        val maxRowWidth = rows.maxOfOrNull { rowItems ->
            rowItems.sumOf { measuredWidths.getOrElse(it) { 0 } } +
                maxOf(0, rowItems.size - 1) * spacingPx
        } ?: 0
        val badgeHeightPx = 20.dp.roundToPx()
        val totalHeight = rows.size * badgeHeightPx + maxOf(0, rows.size - 1) * rowSpacingPx

        // --- Pass 2: compose each row with the correct shape and collect placements ---
        val xPositions = mutableListOf<Int>()
        val yPositions = mutableListOf<Int>()
        val placedBadges = mutableListOf<androidx.compose.ui.layout.Placeable>()

        rows.forEachIndexed { rowIndex, rowItems ->
            val shape = if (rowIndex == 0) badgeShape else pillShape
            val rowY = rowIndex * (badgeHeightPx + rowSpacingPx)
            val rowWidth = rowItems.sumOf { measuredWidths.getOrElse(it) { 0 } } +
                maxOf(0, rowItems.size - 1) * spacingPx
            // For "mine" messages, right-align each row within the composable bounds.
            val rowStartX = if (isMine) maxRowWidth - rowWidth else 0

            val rowPlaceables = subcompose("row_$rowIndex") {
                rowItems.forEach { i ->
                    val reaction = reactions[i]
                    ReactionBadge(
                        emoji = reaction.emoji,
                        count = reaction.count,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        backgroundColor = backgroundColor,
                        contentColor = contentColor,
                        onClick = { onReactionClick(reaction.emoji) },
                        shape = shape,
                    )
                }
            }.map { it.measure(Constraints()) }

            var x = rowStartX
            rowPlaceables.forEach { placeable ->
                placedBadges.add(placeable)
                xPositions.add(x)
                yPositions.add(rowY)
                x += placeable.width + spacingPx
            }
        }

        layout(maxRowWidth, totalHeight) {
            placedBadges.forEachIndexed { i, placeable ->
                placeable.placeRelative(xPositions[i], yPositions[i])
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

private fun normalizeReactionTimestamp(primary: Long, vararg fallbacks: Long): Long {
    if (primary > 0) return primary
    for (candidate in fallbacks) {
        if (candidate > 0) return candidate
    }
    return System.currentTimeMillis()
}

/**
 * Extract [ReactionEvent] from a timeline `m.reaction` event (shared by [net.vrkknn.andromuks.ReactionCoordinator]).
 */
fun extractReactionEventFromTimeline(event: TimelineEvent): ReactionEvent? {
    val content = event.content ?: return null
    val relatesTo = content.optJSONObject("m.relates_to") ?: return null

    val relatesToEventId = relatesTo.optString("event_id")
    val emoji = relatesTo.optString("key")
    val relType = relatesTo.optString("rel_type")

    return if (relatesToEventId.isNotBlank() && emoji.isNotBlank() && relType == "m.annotation") {
        ReactionEvent(
            roomId = event.roomId,
            eventId = event.eventId,
            sender = event.sender,
            emoji = emoji,
            relatesToEventId = relatesToEventId,
            timestamp = normalizeReactionTimestamp(
                event.timestamp,
                event.unsigned?.optLong("age_ts") ?: 0L
            )
        )
    } else {
        null
    }
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

        // Main text area: name + timestamp on first row, reaction content on second row.
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = userProfile?.displayName ?: reaction.userId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formatReactionTime(reaction.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Second row: reaction content on its own line (text or image)
            if (reaction.emoji.startsWith("mxc://")) {
                ImageReaction(
                    mxcUrl = reaction.emoji,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else {
                Text(
                    text = reaction.emoji,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun formatReactionTime(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return formatter.format(date)
}
