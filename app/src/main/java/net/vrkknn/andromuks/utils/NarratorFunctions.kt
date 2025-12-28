package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reply
import net.vrkknn.andromuks.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import net.vrkknn.andromuks.LocalScrollHighlightState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReadReceipt
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.ui.components.AvatarImage
import org.json.JSONObject

@Composable
fun SystemEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel? = null,
    roomId: String,
    onUserClick: (String) -> Unit = {},
    onRoomClick: (String) -> Unit = {},
    onReply: (TimelineEvent) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var narratorBounds by remember { mutableStateOf<Rect?>(null) }
    val content = event.content
    val eventType = event.type
    
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    
    // Highlight animation when this event is the active scroll target
    val scrollHighlightState = LocalScrollHighlightState.current
    val isHighlightTarget =
        scrollHighlightState.requestId > 0 && scrollHighlightState.eventId == event.eventId
    val highlightAnim = remember(event.eventId) { Animatable(0f) }
    
    LaunchedEffect(scrollHighlightState.requestId, isHighlightTarget) {
        if (isHighlightTarget) {
            highlightAnim.snapTo(1f)
            highlightAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 5000, easing = FastOutSlowInEasing)
            )
        } else if (!isHighlightTarget && highlightAnim.value > 0f) {
            highlightAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
            )
        }
    }
    
    val highlightValue = highlightAnim.value
    
    // Get read receipts for this event
    val readReceipts = remember(event.eventId, appViewModel?.readReceiptsUpdateCounter) {
        if (appViewModel != null) {
            ReceiptFunctions.getReadReceipts(
                event.eventId,
                appViewModel.getReadReceiptsMap()
            )
        } else {
            emptyList()
        }
    }
    
    // Get member map for user profiles (for read receipt avatars)
    val memberMap = remember(roomId, appViewModel?.memberUpdateCounter) {
        appViewModel?.getMemberMap(roomId) ?: emptyMap()
    }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    // Capture the narrator row's position on screen
                    narratorBounds = layoutCoordinates.boundsInWindow()
                }
                .then(
                    // Add glow effect when highlighted
                    if (highlightValue > 0.01f) {
                        val glowColor = MaterialTheme.colorScheme.tertiary
                        val glowIntensity = highlightValue * 0.6f // Max 60% opacity
                        Modifier.shadow(
                            elevation = (8.dp + 12.dp * highlightValue),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            ambientColor = glowColor.copy(alpha = glowIntensity * 0.4f),
                            spotColor = glowColor.copy(alpha = glowIntensity * 0.6f)
                        )
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(
                    onClick = { /* Regular click does nothing */ },
                    onLongClick = { 
                        if (BuildConfig.DEBUG) android.util.Log.d("NarratorFunctions", "SystemEventNarrator: Long press detected")
                        showMenu = true 
                    },
                    onLongClickLabel = "Show message options"
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
        // Left side - centered content
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .then(
                    // Add subtle background glow when highlighted
                    if (highlightValue > 0.01f) {
                        val glowColor = MaterialTheme.colorScheme.tertiary
                        val glowAlpha = highlightValue * 0.15f // Subtle background glow
                        Modifier.background(
                            color = glowColor.copy(alpha = glowAlpha),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = if (highlightValue > 0.01f) 4.dp else 0.dp, vertical = if (highlightValue > 0.01f) 2.dp else 0.dp)
        ) {
            // Small avatar for the actor (clickable)
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = displayName,
                size = 20.dp,
                userId = event.sender,
                displayName = displayName,
                modifier = Modifier.clickable { onUserClick(event.sender) }
            )
        
        // Narrator text
        when (eventType) {
            "m.room.member" -> {
                MemberEventNarrator(
                    event = event,
                    displayName = displayName,
                    content = content,
                    appViewModel = appViewModel,
                    roomId = roomId,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken
                )
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
            "m.room.tombstone" -> {
                TombstoneEventNarrator(
                    event = event,
                    displayName = displayName,
                    content = content,
                    onRoomClick = onRoomClick
                )
            }
            "m.space.parent" -> {
                SpaceParentEventNarrator(
                    event = event,
                    displayName = displayName,
                    onRoomClick = onRoomClick
                )
            }
            "m.room.pinned_events" -> {
                PinnedEventsNarrator(
                    event = event,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    appViewModel = appViewModel,
                    roomId = roomId,
                    onUserClick = onUserClick
                )
            }
            "m.room.server_acl" -> {
                ServerAclEventNarrator(
                    event = event,
                    displayName = displayName,
                    content = content
                )
            }
            "m.room.power_levels" -> {
                PowerLevelsEventNarrator(
                    event = event,
                    displayName = displayName,
                    content = content,
                    appViewModel = appViewModel,
                    roomId = roomId
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
        
        // Right side - read receipts (if any) + timestamp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Read receipts (left of timestamp)
            if (readReceipts.isNotEmpty()) {
                InlineReadReceiptAvatars(
                    receipts = readReceipts,
                    userProfileCache = memberMap,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    appViewModel = appViewModel,
                    messageSender = event.sender,
                    roomId = roomId,
                    onUserClick = onUserClick
                )
            }
            
            // Timestamp
            Text(
                text = formatSmartTimestamp(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
        }
        
        // Floating menu for system events (only Reply option) - same style as MessageBubbleWithMenu
        if (showMenu) {
            Popup(
                onDismissRequest = {
                    if (BuildConfig.DEBUG) android.util.Log.d("NarratorFunctions", "SystemEventNarrator: Popup dismissed")
                    showMenu = false
                },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Fullscreen transparent scrim to capture outside taps
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color.Transparent)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (BuildConfig.DEBUG) android.util.Log.d("NarratorFunctions", "SystemEventNarrator: Scrim tapped, dismissing menu")
                                        showMenu = false
                                    }
                                )
                            }
                    )
                    
                    // Card with Reply button positioned above narrator row
                    if (narratorBounds != null) {
                        Card(
                            modifier = Modifier
                                .offset {
                                    with(density) {
                                        // Calculate menu position relative to narrator row
                                        val buttonCount = 1 // Only Reply button
                                        val menuWidth = ((44 * buttonCount).dp + 16.dp).toPx() // 44dp per button + padding
                                        val menuHeight = 50.dp.toPx()
                                        val narratorCenterX = narratorBounds!!.left + (narratorBounds!!.width / 2)
                                        val menuX = narratorCenterX - (menuWidth / 2)
                                        val menuY = narratorBounds!!.top - menuHeight - 8.dp.toPx()
                                        
                                        // Clamp to keep menu on screen
                                        val margin = 8.dp.toPx()
                                        val clampedX = menuX
                                            .coerceAtLeast(margin)
                                            .coerceAtMost(screenWidth - menuWidth - margin)
                                        val clampedY = menuY.coerceAtLeast(margin)
                                        
                                        if (BuildConfig.DEBUG) android.util.Log.d("NarratorFunctions", "SystemEventNarrator: Menu position: x=$clampedX, y=$clampedY, menuWidth=$menuWidth")
                                        
                                        IntOffset(
                                            x = clampedX.toInt(),
                                            y = clampedY.toInt()
                                        )
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Reply button (only option for system events)
                                IconButton(
                                    onClick = {
                                        if (BuildConfig.DEBUG) android.util.Log.d("NarratorFunctions", "SystemEventNarrator: Reply clicked")
                                        showMenu = false
                                        onReply(event)
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Reply,
                                        contentDescription = "Reply",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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

/**
 * Smart time formatting: shows time for today, date and time for other days
 */
private fun formatSmartTimestamp(timestamp: Long): String {
    val eventDate = java.util.Date(timestamp)
    val today = java.util.Date()
    
    val eventCalendar = java.util.Calendar.getInstance()
    val todayCalendar = java.util.Calendar.getInstance()
    
    eventCalendar.time = eventDate
    todayCalendar.time = today
    
    val isToday = eventCalendar.get(java.util.Calendar.YEAR) == todayCalendar.get(java.util.Calendar.YEAR) &&
                  eventCalendar.get(java.util.Calendar.DAY_OF_YEAR) == todayCalendar.get(java.util.Calendar.DAY_OF_YEAR)
    
    return if (isToday) {
        // Show time for today
        val timeFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        timeFormatter.format(eventDate)
    } else {
        // Show date and time for other days
        val dateTimeFormatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        dateTimeFormatter.format(eventDate)
    }
}

/**
 * Renders an emote message (m.emote) in narrator style.
 * Format: "[Display name] [action text]"
 * Example: "Alice really likes onions"
 */
@Composable
fun EmoteEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    onReply: () -> Unit = {},
    onReact: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onUserClick: (String) -> Unit = {}
) {
    // For encrypted messages, prioritize decrypted content, otherwise use regular content
    val content = if (event.type == "m.room.encrypted" && event.decrypted != null) {
        event.decrypted
    } else {
        event.content
    }
    val body = content?.optString("body", "") ?: ""
    
    var showMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(
                onClick = { showMenu = true },
                onClickLabel = "Show message options"
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side - centered content
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Small avatar for the actor (clickable)
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = displayName,
                size = 20.dp,
                userId = event.sender,
                displayName = displayName,
                modifier = Modifier.clickable { onUserClick(event.sender) }
            )
            
            // Emote text
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" ")
                    append(body)
                }
            )
        }
        
        // Right side - timestamp
        Text(
            text = formatSmartTimestamp(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
    }
    
    // Context menu for emote messages
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("Message Options") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showMenu = false
                            onReply()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reply")
                    }
                    TextButton(
                        onClick = {
                            showMenu = false
                            onReact()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("React")
                    }
                    TextButton(
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Edit")
                    }
                    TextButton(
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMenu = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PinnedEventNarration(
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    pinnedEventId: String,
    appViewModel: AppViewModel?,
    roomId: String,
    onUserClick: (String) -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<TimelineEvent?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Get member map that observes memberUpdateCounter for TimelineEventItem profile updates
    val memberMap = remember(roomId, appViewModel?.memberUpdateCounter) {
        appViewModel?.getMemberMap(roomId) ?: emptyMap()
    }

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
                            userProfileCache = memberMap,
                            isMine = false,
                            myUserId = appViewModel?.currentUserId,
                            onUserClick = onUserClick
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

@Composable
private fun UnpinnedEventNarration(
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    unpinnedEventId: String,
    appViewModel: AppViewModel?,
    roomId: String,
    onUserClick: (String) -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<TimelineEvent?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Get member map that observes memberUpdateCounter for TimelineEventItem profile updates
    val memberMap = remember(roomId, appViewModel?.memberUpdateCounter) {
        appViewModel?.getMemberMap(roomId) ?: emptyMap()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NarratorText(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(displayName)
                }
                append(" unpinned an event: ")
            }
        )

        Text(
            text = unpinnedEventId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                if (event == null && !isLoading && appViewModel != null) {
                    isLoading = true
                    errorMessage = null
                    appViewModel.getEvent(roomId, unpinnedEventId) { fetchedEvent ->
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
            title = { Text("Unpinned Event") },
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
                            userProfileCache = memberMap,
                            isMine = false,
                            myUserId = appViewModel?.currentUserId,
                            onUserClick = onUserClick
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

// Extracted event handler functions to reduce SystemEventNarrator size

@Composable
private fun MemberEventNarrator(
    event: TimelineEvent,
    displayName: String,
    content: JSONObject?,
    appViewModel: AppViewModel?,
    roomId: String,
    homeserverUrl: String,
    authToken: String
) {
    val membership = content?.optString("membership", "")
    val reason = content?.optString("reason", "")
    
    when (membership) {
        "join" -> {
            // Check if this is a profile change vs actual join
            val unsigned = event.unsigned
            val prevContent = unsigned?.optJSONObject("prev_content")
            val prevMembership = prevContent?.optString("membership", "")
            val isProfileChange = prevContent != null && prevMembership == "join"
            
            if (isProfileChange) {
                val prevDisplayName = prevContent.optString("displayname", "")
                val prevAvatar = prevContent.optString("avatar_url", "")
                val currentDisplayName = content?.optString("displayname", "") ?: ""
                val currentAvatar = content?.optString("avatar_url", "") ?: ""
                
                val contentMatches = prevDisplayName == currentDisplayName && prevAvatar == currentAvatar
                
                if (contentMatches) {
                    NarratorText(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(displayName)
                            }
                            append(" made no change")
                        }
                    )
                } else {
                    ProfileChangeNarrator(
                        displayName = displayName,
                        prevDisplayName = prevDisplayName,
                        currentDisplayName = currentDisplayName,
                        prevAvatar = prevAvatar,
                        currentAvatar = currentAvatar
                    )
                }
            } else {
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
            val unsigned = event.unsigned
            val prevContent = unsigned?.optJSONObject("prev_content")
            val prevMembership = prevContent?.optString("membership", "")
            
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
            val invitedUserId = event.stateKey
            val invitedProfile = invitedUserId?.let { userId ->
                appViewModel?.getUserProfile(userId, roomId)
            }
            val invitedDisplayName = invitedProfile?.displayName
            val invitedAvatarUrl = invitedProfile?.avatarUrl
            
            if (invitedDisplayName == null && appViewModel != null && invitedUserId != null) {
                appViewModel.requestUserProfile(invitedUserId, roomId)
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" invited ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic
                )
                
                AvatarImage(
                    mxcUrl = invitedAvatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = invitedDisplayName ?: invitedUserId ?: "?",
                    size = 16.dp,
                    userId = invitedUserId,
                    displayName = invitedDisplayName
                )
                
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(invitedDisplayName ?: invitedUserId ?: "Unknown")
                        }
                        if (!reason.isNullOrEmpty()) {
                            append(" for $reason")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    maxLines = Int.MAX_VALUE
                )
            }
        }
        "ban" -> {
            val bannedUserId = event.stateKey
            val bannedProfile = bannedUserId?.let { userId ->
                appViewModel?.getUserProfile(userId, roomId)
            }
            val bannedDisplayName = bannedProfile?.displayName
            
            if (bannedDisplayName == null && appViewModel != null && bannedUserId != null) {
                appViewModel.requestUserProfile(bannedUserId, roomId)
            }
            
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" banned ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(bannedDisplayName ?: bannedUserId ?: "Unknown")
                    }
                    if (!reason.isNullOrEmpty()) {
                        append(": $reason")
                    }
                }
            )
        }
        "kick" -> {
            val kickedUserId = event.stateKey
            val kickedProfile = kickedUserId?.let { userId ->
                appViewModel?.getUserProfile(userId, roomId)
            }
            val kickedDisplayName = kickedProfile?.displayName
            
            if (kickedDisplayName == null && appViewModel != null && kickedUserId != null) {
                appViewModel.requestUserProfile(kickedUserId, roomId)
            }
            
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" kicked ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(kickedDisplayName ?: kickedUserId ?: "Unknown")
                    }
                    if (!reason.isNullOrEmpty()) {
                        append(": $reason")
                    }
                }
            )
        }
    }
}

@Composable
private fun ProfileChangeNarrator(
    displayName: String,
    prevDisplayName: String,
    currentDisplayName: String,
    prevAvatar: String,
    currentAvatar: String
) {
    val displayNameRemoved = !prevDisplayName.isNullOrEmpty() && currentDisplayName.isNullOrEmpty()
    val displayNameChanged = !prevDisplayName.isNullOrEmpty() && 
                             !currentDisplayName.isNullOrEmpty() && 
                             prevDisplayName != currentDisplayName
    val displayNameAdded = prevDisplayName.isNullOrEmpty() && !currentDisplayName.isNullOrEmpty()
    
    val avatarRemoved = !prevAvatar.isNullOrEmpty() && currentAvatar.isNullOrEmpty()
    val avatarChanged = !prevAvatar.isNullOrEmpty() && 
                        !currentAvatar.isNullOrEmpty() && 
                        prevAvatar != currentAvatar
    val avatarAdded = prevAvatar.isNullOrEmpty() && !currentAvatar.isNullOrEmpty()
    
    when {
        displayNameRemoved && avatarRemoved -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" removed their display name and avatar")
                }
            )
        }
        displayNameRemoved && avatarChanged -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" removed their display name and changed their avatar")
                }
            )
        }
        displayNameRemoved && avatarAdded -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" removed their display name and set their avatar")
                }
            )
        }
        displayNameRemoved -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" removed their display name")
                }
            )
        }
        displayNameChanged && avatarRemoved -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed their display name and removed their avatar")
                }
            )
        }
        displayNameChanged && avatarChanged -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed their profile")
                }
            )
        }
        displayNameChanged && avatarAdded -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed their display name and set their avatar")
                }
            )
        }
        displayNameChanged -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed their display name")
                }
            )
        }
        displayNameAdded && avatarRemoved -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set their display name and removed their avatar")
                }
            )
        }
        displayNameAdded && avatarChanged -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set their display name and changed their avatar")
                }
            )
        }
        displayNameAdded && avatarAdded -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set their display name and avatar")
                }
            )
        }
        displayNameAdded -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set their display name")
                }
            )
        }
        avatarRemoved -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" removed their avatar")
                }
            )
        }
        avatarChanged -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed their avatar")
                }
            )
        }
        avatarAdded -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set their avatar")
                }
            )
        }
        else -> {
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
}

@Composable
private fun TombstoneEventNarrator(
    event: TimelineEvent,
    displayName: String,
    content: JSONObject?,
    onRoomClick: (String) -> Unit
) {
    val reason = content?.optString("body", "")?.takeIf { it.isNotBlank() }
    val replacementRoom = content?.optString("replacement_room", "")?.takeIf { it.isNotBlank() }
    
    if (replacementRoom != null) {
        val useShortLink = replacementRoom.length > 20
        val linkText = if (useShortLink) "this" else replacementRoom
        
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" replaced this room with ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic
                )
                
                Text(
                    text = linkText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.clickable { onRoomClick(replacementRoom) }
                )
            }
            
            if (reason != null) {
                Text(
                    text = "for reason: $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    } else {
        NarratorText(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(displayName)
                }
                append(" tombstoned this room")
            }
        )
    }
}

@Composable
private fun SpaceParentEventNarrator(
    event: TimelineEvent,
    displayName: String,
    onRoomClick: (String) -> Unit
) {
    val spaceParent = event.stateKey?.takeIf { it.isNotBlank() }
    
    if (spaceParent != null) {
        val useShortLink = spaceParent.length > 20
        val linkText = if (useShortLink) "this" else spaceParent
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" defined the ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
            
            Text(
                text = linkText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.clickable { onRoomClick(spaceParent) }
            )
            
            Text(
                text = " for this room",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
    } else {
        NarratorText(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(displayName)
                }
                append(" updated the space parent for this room")
            }
        )
    }
}

@Composable
private fun PinnedEventsNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    onUserClick: (String) -> Unit
) {
    val pinnedArray = event.content?.optJSONArray("pinned")
    val unsigned = event.unsigned
    val prevContent = unsigned?.optJSONObject("prev_content")
    val prevPinnedArray = prevContent?.optJSONArray("pinned")
    
    val currentPinned = pinnedArray?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
    } ?: emptySet()
    
    val previousPinned = prevPinnedArray?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
    } ?: emptySet()
    
    val newlyPinned = currentPinned - previousPinned
    val unpinned = previousPinned - currentPinned
    
    when {
        newlyPinned.isNotEmpty() && unpinned.isEmpty() -> {
            if (newlyPinned.size == 1) {
                PinnedEventNarration(
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    pinnedEventId = newlyPinned.first(),
                    appViewModel = appViewModel,
                    roomId = roomId,
                    onUserClick = onUserClick
                )
            } else {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" pinned ${newlyPinned.size} events")
                    }
                )
            }
        }
        unpinned.isNotEmpty() && newlyPinned.isEmpty() -> {
            if (unpinned.size == 1) {
                UnpinnedEventNarration(
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    unpinnedEventId = unpinned.first(),
                    appViewModel = appViewModel,
                    roomId = roomId,
                    onUserClick = onUserClick
                )
            } else {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" unpinned ${unpinned.size} events")
                    }
                )
            }
        }
        newlyPinned.isNotEmpty() && unpinned.isNotEmpty() -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" updated pinned events")
                }
            )
        }
        else -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" updated pinned events")
                }
            )
        }
    }
}

@Composable
private fun ServerAclEventNarrator(
    event: TimelineEvent,
    displayName: String,
    content: JSONObject?
) {
    val denyArray = content?.optJSONArray("deny")
    val unsigned = event.unsigned
    val prevContent = unsigned?.optJSONObject("prev_content")
    val prevDenyArray = prevContent?.optJSONArray("deny")
    
    val currentDeny = denyArray?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
    } ?: emptySet()
    
    val previousDeny = prevDenyArray?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }.toSet()
    } ?: emptySet()
    
    val isInitialSetup = prevContent == null
    val newlyAdded = currentDeny - previousDeny
    val removed = previousDeny - currentDeny
    
    when {
        isInitialSetup -> {
            if (currentDeny.size == 1 && currentDeny.first() == "*") {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" banned all servers from participating in the room")
                    }
                )
            } else if (currentDeny.isEmpty()) {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" set ACL List (all servers allowed)")
                    }
                )
            } else {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" added ${currentDeny.size} servers to the ACL list")
                    }
                )
            }
        }
        newlyAdded.isNotEmpty() && removed.isEmpty() -> {
            if (newlyAdded.size == 1) {
                val server = newlyAdded.first()
                if (server == "*") {
                    NarratorText(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(displayName)
                            }
                            append(" banned all servers from participating in the room")
                        }
                    )
                } else {
                    NarratorText(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(displayName)
                            }
                            append(" added server ")
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(server)
                            }
                            append(" to the ACL List")
                        }
                    )
                }
            } else {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" added ${newlyAdded.size} servers to the ACL list")
                    }
                )
            }
        }
        removed.isNotEmpty() && newlyAdded.isEmpty() -> {
            if (removed.size == 1) {
                val server = removed.first()
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" removed server ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(server)
                        }
                        append(" from the ACL List")
                    }
                )
            } else {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" removed ${removed.size} servers from the ACL list")
                    }
                )
            }
        }
        newlyAdded.isNotEmpty() && removed.isNotEmpty() -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" added ${newlyAdded.size} server${if (newlyAdded.size == 1) "" else "s"} to the ACL list")
                    append(", and removed ${removed.size} server${if (removed.size == 1) "" else "s"}")
                }
            )
        }
        else -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" updated ACL List")
                }
            )
        }
    }
}

@Composable
private fun PowerLevelsEventNarrator(
    event: TimelineEvent,
    displayName: String,
    content: JSONObject?,
    appViewModel: AppViewModel?,
    roomId: String
) {
    val unsigned = event.unsigned
    val prevContent = unsigned?.optJSONObject("prev_content")
    
    val currentUsers = content?.optJSONObject("users") ?: org.json.JSONObject()
    val previousUsers = prevContent?.optJSONObject("users") ?: org.json.JSONObject()
    
    val currentUserIds = currentUsers.keys().asSequence().toSet()
    val previousUserIds = previousUsers.keys().asSequence().toSet()
    
    val addedUsers = currentUserIds - previousUserIds
    val removedUsers = previousUserIds - currentUserIds
    val changedUsers = currentUserIds.intersect(previousUserIds).filter { userId ->
        currentUsers.optInt(userId, -1) != previousUsers.optInt(userId, -1)
    }
    
    val roomPowerLevelKeys = setOf(
        "ban", "kick", "redact", "invite", "historical",
        "events_default", "state_default", "users_default"
    )
    
    val currentEvents = content?.optJSONObject("events") ?: org.json.JSONObject()
    val previousEvents = prevContent?.optJSONObject("events") ?: org.json.JSONObject()
    
    val currentEventKeys = currentEvents.keys().asSequence().toSet()
    val previousEventKeys = previousEvents.keys().asSequence().toSet()
    
    val changedRoomSettings = mutableListOf<String>()
    
    for (key in roomPowerLevelKeys) {
        val currentValue = content?.optInt(key, -1)
        val previousValue = prevContent?.optInt(key, -1)
        if (currentValue != previousValue && (currentValue != -1 || previousValue != -1)) {
            changedRoomSettings.add(key)
        }
    }
    
    val changedEventKeys = (currentEventKeys + previousEventKeys).filter { eventKey ->
        val currentValue = currentEvents.optInt(eventKey, -1)
        val previousValue = previousEvents.optInt(eventKey, -1)
        currentValue != previousValue
    }
    
    val hasUserChanges = addedUsers.isNotEmpty() || removedUsers.isNotEmpty() || changedUsers.isNotEmpty()
    val hasRoomChanges = changedRoomSettings.isNotEmpty() || changedEventKeys.isNotEmpty()
    
    when {
        hasUserChanges && !hasRoomChanges -> {
            PowerLevelsUserChangesNarrator(
                displayName = displayName,
                changedUsers = changedUsers,
                removedUsers = removedUsers,
                addedUsers = addedUsers,
                currentUsers = currentUsers,
                appViewModel = appViewModel,
                roomId = roomId
            )
        }
        hasRoomChanges && !hasUserChanges -> {
            PowerLevelsRoomChangesNarrator(
                displayName = displayName,
                changedEventKeys = changedEventKeys,
                changedRoomSettings = changedRoomSettings,
                currentEvents = currentEvents,
                content = content
            )
        }
        hasUserChanges && hasRoomChanges -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed power levels")
                }
            )
        }
        prevContent == null -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set room power levels")
                }
            )
        }
        else -> {
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" updated power levels")
                }
            )
        }
    }
}

@Composable
private fun PowerLevelsUserChangesNarrator(
    displayName: String,
    changedUsers: List<String>,
    removedUsers: Set<String>,
    addedUsers: Set<String>,
    currentUsers: org.json.JSONObject,
    appViewModel: AppViewModel?,
    roomId: String
) {
    when {
        changedUsers.size == 1 && addedUsers.isEmpty() && removedUsers.isEmpty() -> {
            val userId = changedUsers.first()
            val newLevel = currentUsers.optInt(userId)
            
            if (appViewModel != null) {
                appViewModel.requestUserProfile(userId, roomId)
            }
            
            val userDisplayName = appViewModel?.getUserProfile(userId, roomId)?.displayName ?: userId
            
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set user ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(userDisplayName)
                    }
                    append(" power level to $newLevel")
                }
            )
        }
        removedUsers.size == 1 && addedUsers.isEmpty() && changedUsers.isEmpty() -> {
            val userId = removedUsers.first()
            
            if (appViewModel != null) {
                appViewModel.requestUserProfile(userId, roomId)
            }
            
            val userDisplayName = appViewModel?.getUserProfile(userId, roomId)?.displayName ?: userId
            
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set user ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(userDisplayName)
                    }
                    append(" power level to the default")
                }
            )
        }
        addedUsers.size == 1 && removedUsers.isEmpty() && changedUsers.isEmpty() -> {
            val userId = addedUsers.first()
            val newLevel = currentUsers.optInt(userId)
            
            if (appViewModel != null) {
                appViewModel.requestUserProfile(userId, roomId)
            }
            
            val userDisplayName = appViewModel?.getUserProfile(userId, roomId)?.displayName ?: userId
            
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set user ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(userDisplayName)
                    }
                    append(" power level to $newLevel")
                }
            )
        }
        else -> {
            val totalChanges = changedUsers.size + addedUsers.size + removedUsers.size
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed $totalChanges user power level${if (totalChanges == 1) "" else "s"}")
                }
            )
        }
    }
}

@Composable
private fun PowerLevelsRoomChangesNarrator(
    displayName: String,
    changedEventKeys: List<String>,
    changedRoomSettings: List<String>,
    currentEvents: org.json.JSONObject,
    content: JSONObject?
) {
    when {
        changedEventKeys.size == 1 && changedRoomSettings.isEmpty() -> {
            val eventKey = changedEventKeys.first()
            val newLevel = currentEvents.optInt(eventKey)
            
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set room ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(eventKey)
                    }
                    append(" powerlevel to $newLevel")
                }
            )
        }
        changedRoomSettings.size == 1 && changedEventKeys.isEmpty() -> {
            val settingKey = changedRoomSettings.first()
            val newLevel = content?.optInt(settingKey) ?: 0
            
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" set room ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(settingKey)
                    }
                    append(" powerlevel to $newLevel")
                }
            )
        }
        else -> {
            val totalChanges = changedRoomSettings.size + changedEventKeys.size
            NarratorText(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(displayName)
                    }
                    append(" changed room power levels")
                }
            )
        }
    }
}
