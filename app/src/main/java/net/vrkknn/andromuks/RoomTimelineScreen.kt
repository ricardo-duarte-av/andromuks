package net.vrkknn.andromuks

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import net.vrkknn.andromuks.utils.BlurHashUtils
import net.vrkknn.andromuks.utils.MediaUtils
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.SystemEventNarrator
import net.vrkknn.andromuks.utils.NarratorText
import net.vrkknn.andromuks.utils.InlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.TypingNotificationArea
import net.vrkknn.andromuks.utils.ReactionBadges
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.ReplyPreview
import net.vrkknn.andromuks.utils.RichMessageText
import net.vrkknn.andromuks.utils.MessageTextWithMentions
import net.vrkknn.andromuks.utils.SmartMessageText
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.paddingFrom
import androidx.compose.material3.TextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomTimelineScreen(
    roomId: String,
    roomName: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences = remember(context) { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val authToken = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val myUserId = appViewModel.currentUserId
    val homeserverUrl = appViewModel.homeserverUrl
    Log.d("Andromuks", "RoomTimelineScreen: appViewModel instance: $appViewModel")
    val timelineEvents = appViewModel.timelineEvents
    val isLoading = appViewModel.isTimelineLoading

    // Build user profile cache from m.room.member events
    val userProfileCache = remember(timelineEvents) {
        val map = mutableMapOf<String, Pair<String?, String?>>() // userId -> (displayName, avatarUrl)
        for (event in timelineEvents) {
            if (event.type == "m.room.member") {
                val userId = event.stateKey ?: event.sender
                val content = event.content
                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                map[userId] = Pair(displayName, avatarUrl)
            }
        }
        map
    }

    // Define allowed event types (whitelist approach)
    // Note: m.room.redaction events are explicitly excluded as they should not appear in timeline
    val allowedEventTypes = setOf(
        "m.room.message",
        "m.room.encrypted", 
        "m.room.member",
        "m.room.name",
        "m.room.topic",
        "m.room.avatar",
        "m.reaction"
        // m.room.redaction is intentionally excluded - redaction events should not appear in timeline
    )
    
    // Sort events so newer messages are at the bottom, and filter unprocessed events if setting is disabled
    val sortedEvents = remember(timelineEvents, appViewModel.showUnprocessedEvents) {
        val filteredEvents = if (appViewModel.showUnprocessedEvents) {
            // Show all events when unprocessed events are enabled, but always exclude redaction events
            timelineEvents.filter { event ->
                event.type != "m.room.redaction"
            }
        } else {
            // Only show allowed events when unprocessed events are disabled
            timelineEvents.filter { event ->
                allowedEventTypes.contains(event.type)
            }
        }
        
        // Filter out superseded events (original messages that have been edited)
        // Edit events create new timeline entries, so we hide the original messages they replace
        val eventsWithoutSuperseded = filteredEvents.filter { event ->
            if (event.type == "m.room.message") {
                // Check if this message has been superseded by an edit
                val hasBeenEdited = filteredEvents.any { otherEvent ->
                    otherEvent.type == "m.room.message" &&
                    otherEvent.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace" &&
                    otherEvent.content?.optJSONObject("m.relates_to")?.optString("event_id") == event.eventId
                }
                !hasBeenEdited // Keep the event if it hasn't been edited
            } else {
                true // Keep non-message events
            }
        }
        
        eventsWithoutSuperseded.sortedBy { it.timestamp }
    }

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()
    LaunchedEffect(sortedEvents.size, isLoading) {
        if (!isLoading && sortedEvents.isNotEmpty()) {
            listState.scrollToItem(sortedEvents.lastIndex)
        }
    }
    
    LaunchedEffect(roomId) {
        Log.d("Andromuks", "RoomTimelineScreen: Loading timeline for room: $roomId")
        // Request room state first, then timeline
        appViewModel.requestRoomState(roomId)
        appViewModel.requestRoomTimeline(roomId)
    }
    
    // Validate and request missing user profiles when timeline events change
    // This ensures all users in the timeline have complete profile data (display name, avatar)
    // Missing profiles are automatically requested from the server
    LaunchedEffect(sortedEvents) {
        if (sortedEvents.isNotEmpty()) {
            Log.d("Andromuks", "RoomTimelineScreen: Validating user profiles for ${sortedEvents.size} events")
            appViewModel.validateAndRequestMissingProfiles(roomId, sortedEvents)
        }
    }
    
    // Save updated profiles to disk when member cache changes
    // This persists user profile data (display names, avatars) to disk for future app sessions
    // Profiles are saved whenever the updateCounter changes (indicating profile updates)
    LaunchedEffect(appViewModel.updateCounter) {
        // Save all updated profiles to disk for persistence
        val memberMap = appViewModel.getMemberMap(roomId)
        for ((userId, profile) in memberMap) {
            appViewModel.saveProfileToDisk(context, userId, profile)
        }
    }
    
    // Handle Android back key
    BackHandler {
        navController.popBackStack()
    }
    
    // Use imePadding for keyboard handling
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Choose IME if present, otherwise navigation bar padding
    val bottomInset = if (imeBottom > 0.dp) imeBottom else navBarBottom
    
    AndromuksTheme {
        Surface {
            Column(
                modifier = modifier.fillMaxSize()
            ) {
                // 1. Room Header (always visible at the top, below status bar)
                RoomHeader(
                    roomState = appViewModel.currentRoomState,
                    fallbackName = roomName,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = appViewModel.authToken
                )
                
                // 2. Timeline (compressible, scrollable content)
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading timeline...")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                            state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp
                            )
                        ) {
                            items(sortedEvents) { event ->
                                val isMine = myUserId != null && event.sender == myUserId
                                TimelineEventItem(
                                    event = event,
                                    timelineEvents = timelineEvents,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    userProfileCache = appViewModel.getMemberMap(roomId),
                                    isMine = isMine,
                                    appViewModel = appViewModel,
                                    onScrollToMessage = { eventId ->
                                        // Find the index of the message to scroll to
                                        val index = sortedEvents.indexOfFirst { it.eventId == eventId }
                                        if (index >= 0) {
                                            coroutineScope.launch {
                                                listState.animateScrollToItem(index)
                                            }
                                        } else {
                                            // Show toast if message not found
                                            android.widget.Toast.makeText(
                                                context,
                                                "Cannot find message",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                        }
                    }
                }
            
                
                // Typing notification area (exclusive space above text box)
                TypingNotificationArea(
                    typingUsers = appViewModel.typingUsers,
                    roomId = roomId,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    userProfileCache = appViewModel.getMemberMap(roomId)
                )
                
                // 3. Text box (always at the bottom, above keyboard/nav bar)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                        //.imePadding() // ensures it's above the keyboard
                        .padding(bottom = bottomInset)
                    ) {
                        var draft by remember { mutableStateOf("") }
                        var lastTypingTime by remember { mutableStateOf(0L) }
                        
                        // Typing detection with debouncing
                        LaunchedEffect(draft) {
                            if (draft.isNotBlank()) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastTypingTime > 1000) { // Send typing every 1 second
                                    appViewModel.sendTyping(roomId)
                                    lastTypingTime = currentTime
                                }
                            }
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pill-shaped text input
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                shape = RoundedCornerShape(50.dp), // Pill shape with semicircular ends
                                tonalElevation = 1.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp) // Increased height for better text visibility
                            ) {
                                TextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    placeholder = { Text("Type a message...") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp), // Increased height to match Surface
                                    singleLine = true,
                                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Circular send button
                            Button(
                                onClick = { 
                                    if (draft.isNotBlank()) {
                                        appViewModel.sendMessage(roomId, draft)
                                        draft = "" // Clear the input after sending
                                    }
                                },
                                enabled = draft.isNotBlank(),
                                shape = CircleShape, // Perfect circle
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary 
                                                   else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier
                                    .size(56.dp), // Same height as pill
                                contentPadding = PaddingValues(0.dp) // No padding for perfect circle
                            ) {
                                @Suppress("DEPRECATION")
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary 
                                          else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }   
                        }
                    }
            }
        }
    }
}



@Composable
fun TimelineEventItem(
    event: TimelineEvent,
    timelineEvents: List<TimelineEvent>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    modifier: Modifier = Modifier
) {
    // This is a placeholder - the actual implementation would be quite complex
    // For now, just show a simple text representation
    Text(
        text = "Event: ${event.type}",
        modifier = modifier.padding(8.dp)
    )
}

fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(date)
}



@Composable
fun TimelineEventItem(
    event: TimelineEvent,
    timelineEvents: List<TimelineEvent>,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    isMine: Boolean,
    appViewModel: AppViewModel? = null,
    onScrollToMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // Lookup display name and avatar from cache
    val profile = userProfileCache[event.sender]
    val displayName = profile?.displayName
    val avatarUrl = profile?.avatarUrl
    
    // Check if this is a narrator event (system event)
    val isNarratorEvent = event.type in setOf("m.room.member", "m.room.name", "m.room.topic", "m.room.avatar")
    
    // Check if this message is being edited by another event (moved to function start)
    val editedBy = timelineEvents.find { 
        (it.content?.optJSONObject("m.relates_to")?.optString("event_id") == event.eventId &&
         it.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") ||
        (it.decrypted?.optJSONObject("m.relates_to")?.optString("event_id") == event.eventId &&
         it.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace")
    }
    
    if (isNarratorEvent) {
        // For narrator events, show only the small narrator content
        SystemEventNarrator(
            event = event,
            displayName = displayName ?: event.sender,
            avatarUrl = avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            appViewModel = appViewModel,
            roomId = event.roomId
        )
        return
    }
    
    // Note: Edit events are now allowed to be displayed since we have proper superseding logic
    // that ensures only the latest version of each message is shown
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (!isMine) {
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                authToken = authToken,
                fallbackText = (displayName ?: event.sender).take(1),
                size = 48.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        
        // Event content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
            ) {
                // For my messages, show read receipts first, then name and time
                if (isMine && appViewModel != null) {
                    val receipts = appViewModel.getReadReceipts(event.eventId)
                    if (receipts.isNotEmpty()) {
                        InlineReadReceiptAvatars(
                            receipts = receipts,
                            userProfileCache = userProfileCache,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            appViewModel = appViewModel,
                            messageSender = event.sender
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                
                Text(
                    text = displayName ?: event.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (editedBy != null) {
                        "${formatTimestamp(event.timestamp)} (edited at ${formatTimestamp(editedBy.timestamp)})"
                    } else {
                        formatTimestamp(event.timestamp)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // For others' messages, show name and time first, then read receipts
                if (!isMine && appViewModel != null) {
                    val receipts = appViewModel.getReadReceipts(event.eventId)
                    if (receipts.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        InlineReadReceiptAvatars(
                            receipts = receipts,
                            userProfileCache = userProfileCache,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            appViewModel = appViewModel,
                            messageSender = event.sender
                        )
                    }
                }
            }
            
            when (event.type) {
                "m.room.redaction" -> {
                    // Handle redaction events - these should not be displayed as regular messages
                    // The redaction logic will be handled by modifying the original message
                    // When a message is redacted, it gets a redactedBy field pointing to the redaction event
                    // We use this to display deletion messages instead of the original content
                    return
                }
                "m.room.message" -> {
                    // Check if this is an edit event (m.replace relationship)
                    val isEditEvent = event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    // For edit events, get content from m.new_content; for regular messages, use content directly
                    // This ensures edit events display the new content instead of the edit metadata
                    val content = if (isEditEvent) {
                        event.content?.optJSONObject("m.new_content")
                    } else {
                        event.content
                    }
                    val format = content?.optString("format", "")
                    val body = if (format == "org.matrix.custom.html") {
                        content?.optString("formatted_body", "") ?: ""
                    } else {
                        content?.optString("body", "") ?: ""
                    }
                    val msgType = content?.optString("msgtype", "") ?: ""
                    
                    // Check if this message has been redacted
                    val isRedacted = event.redactedBy != null
                    val redactionEvent = if (isRedacted) {
                        // Find the latest redaction event to get the reason and sender
                        net.vrkknn.andromuks.utils.RedactionUtils.findLatestRedactionEvent(event.eventId, timelineEvents)
                    } else null
                    val redactionReason = redactionEvent?.content?.optString("reason", "")?.takeIf { it.isNotBlank() }
                    val redactionSender = redactionEvent?.sender
                    
                    // Show deletion message if redacted, otherwise show the message content
                    val finalBody = if (isRedacted) {
                        // Create deletion message based on reason and sender using latest redaction
                        net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessage(redactionSender, redactionReason, redactionEvent?.timestamp, userProfileCache)
                    } else {
                        body // Show the message content (for edit events, this is already the new content)
                    }
                    
                    // Check if this is a reply message
                    val replyInfo = event.getReplyInfo()
                    val originalEvent = replyInfo?.let { reply ->
                        timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
                    }
                    
                    // Check if it's a media message
                    if (msgType == "m.image" || msgType == "m.video") {
                        Log.d("Andromuks", "TimelineEventItem: Found media message - msgType=$msgType, body=$body")
                        
                        // If media message is redacted, show deletion message instead of media
                        if (isRedacted) {
                            // Display deletion message for media
                            val deletionMessage = net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessage(redactionSender, redactionReason, redactionEvent?.timestamp, userProfileCache)
                            
                            val bubbleShape = if (isMine) {
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 2.dp,
                                    bottomEnd = 16.dp,
                                    bottomStart = 16.dp
                                )
                            } else {
                                RoundedCornerShape(
                                    topStart = 2.dp,
                                    topEnd = 16.dp,
                                    bottomEnd = 16.dp,
                                    bottomStart = 16.dp
                                )
                            }
                            
                            val bubbleColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = bubbleColor,
                                    shape = bubbleShape,
                                    tonalElevation = 2.dp,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = deletionMessage,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                        fontStyle = FontStyle.Italic, // Make deletion messages italic
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            return // Exit early for redacted media messages
                        }
                        
                        val url = content?.optString("url", "") ?: ""
                        val filename = content?.optString("filename", "") ?: ""
                        val info = content?.optJSONObject("info")
                        
                        Log.d("Andromuks", "TimelineEventItem: Media data - url=$url, filename=$filename, info=${info != null}")
                        
                        if (url.isNotBlank() && info != null) {
                            val width = info.optInt("w", 0)
                            val height = info.optInt("h", 0)
                            val size = info.optLong("size", 0)
                            val mimeType = info.optString("mimetype", "")
                            val blurHash = info.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
                            
                            val caption = if (body != filename && body.isNotBlank()) body else null
                            
                            val mediaInfo = MediaInfo(
                                width = width,
                                height = height,
                                size = size,
                                mimeType = mimeType,
                                blurHash = blurHash
                            )
                            
                            val mediaMessage = MediaMessage(
                                url = url,
                                filename = filename,
                                caption = caption,
                                info = mediaInfo,
                                msgType = msgType
                            )
                            
                            Log.d("Andromuks", "TimelineEventItem: Created MediaMessage - url=${mediaMessage.url}, blurHash=${mediaMessage.info.blurHash}")
                            
                            // Display media message with nested reply structure
                            if (replyInfo != null && originalEvent != null) {
                                Column {
                                    ReplyPreview(
                                        replyInfo = replyInfo,
                                        originalEvent = originalEvent,
                                        userProfileCache = userProfileCache,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        isMine = isMine,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                        onOriginalMessageClick = {
                                                onScrollToMessage(replyInfo.eventId)
                                        },
                                        timelineEvents = timelineEvents
                                    )
                                    MediaMessage(
                                        mediaMessage = mediaMessage,
                                        homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                        authToken = authToken,
                                        isMine = isMine
                                    )
                                }
                            } else {
                                MediaMessage(
                                    mediaMessage = mediaMessage,
                                    homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                    authToken = authToken,
                                    isMine = isMine
                                )
                            }
                            
                            // Add reaction badges for media messages
                            if (appViewModel != null) {
                                val reactions = appViewModel.messageReactions[event.eventId] ?: emptyList()
                                if (reactions.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                                    ) {
                                        ReactionBadges(
                                            eventId = event.eventId,
                                            reactions = reactions
                                        )
                                    }
                                }
                            }
                        } else {
                            // Fallback to text message if media parsing fails
                    val bubbleShape = if (isMine) {
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 2.dp,
                            bottomEnd = 8.dp,
                            bottomStart = 16.dp
                        )
                    } else {
                        RoundedCornerShape(
                            topStart = 2.dp,
                            topEnd = 16.dp,
                            bottomEnd = 16.dp,
                            bottomStart = 8.dp
                        )
                    }
                    val bubbleColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = bubbleColor,
                            shape = bubbleShape,
                            tonalElevation = 2.dp,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                                }
                            }
                        }
                    } else {
                        // Regular text message
                        val bubbleShape = if (isMine) {
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 2.dp,
                                bottomEnd = 16.dp,
                                bottomStart = 16.dp
                            )
                        } else {
                            RoundedCornerShape(
                                topStart = 2.dp,
                                topEnd = 16.dp,
                                bottomEnd = 16.dp,
                                bottomStart = 16.dp
                            )
                        }
                        val bubbleColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                        ) {
                            // Display reply with nested structure if this is a reply
                            if (replyInfo != null && originalEvent != null) {
                                Surface(
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = bubbleColor,
                                    shape = bubbleShape,
                                    tonalElevation = 2.dp
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        // Reply preview (clickable original message)
                                        ReplyPreview(
                                            replyInfo = replyInfo,
                                            originalEvent = originalEvent,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            isMine = isMine,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                            onOriginalMessageClick = {
                                                onScrollToMessage(replyInfo.eventId)
                                            },
                                            timelineEvents = timelineEvents
                                        )
                                        
                                        // Reply message content (directly in the outer bubble, no separate bubble)
                                        SmartMessageText(
                                            body = finalBody,
                                            format = format,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            appViewModel = appViewModel,
                                            roomId = event.roomId,
                                            modifier = Modifier
                                        )
                                        
                                        // Show redaction indicators for reply (using our new deletion message function)
                                        if (isRedacted) {
                                            Text(
                                                text = net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessage(redactionSender, redactionReason, redactionEvent?.timestamp, userProfileCache),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontStyle = FontStyle.Italic,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Regular message bubble
                                Surface(
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = bubbleColor,
                                    shape = bubbleShape,
                                    tonalElevation = 2.dp
                                ) {
                                    SmartMessageText(
                                        body = finalBody,
                                        format = format,
                                        userProfileCache = userProfileCache,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        appViewModel = appViewModel,
                                        roomId = event.roomId,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                    
                                    // Show redaction indicators (using our new deletion message function)
                                    if (isRedacted) {
                                        Text(
                                            text = net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessage(redactionSender, redactionReason, redactionEvent?.timestamp, userProfileCache),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = FontStyle.Italic,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Add reaction badges for this message
                        if (appViewModel != null) {
                            val reactions = appViewModel.messageReactions[event.eventId] ?: emptyList()
                            if (reactions.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                                ) {
                                    ReactionBadges(
                                        eventId = event.eventId,
                                        reactions = reactions
                                    )
                                }
                            }
                        }
                    }
                }
                "m.room.encrypted" -> {
                    // Note: Edit events are now allowed to be displayed since we have proper superseding logic
                    
                    val decryptedType = event.decryptedType
                    val decrypted = event.decrypted
                    if (decryptedType == "m.room.message") {
                        val format = decrypted?.optString("format", "")
                        val body = if (format == "org.matrix.custom.html") {
                            decrypted?.optString("formatted_body", "") ?: ""
                        } else {
                            decrypted?.optString("body", "") ?: ""
                        }
                        val msgType = decrypted?.optString("msgtype", "") ?: ""
                        
                        // Check if this message has been redacted
                        val isRedacted = event.redactedBy != null
                        val redactionEvent = if (isRedacted) {
                            // Find the latest redaction event to get the reason and sender
                            net.vrkknn.andromuks.utils.RedactionUtils.findLatestRedactionEvent(event.eventId, timelineEvents)
                        } else null
                        val redactionReason = redactionEvent?.content?.optString("reason", "")?.takeIf { it.isNotBlank() }
                        val redactionSender = redactionEvent?.sender
                        
                        // Check if this is an edit (m.replace relationship)
                        val isEdit = decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                        val editContent = if (isEdit) {
                            decrypted?.optJSONObject("m.new_content")
                        } else null
                        
                        
                        // Use edit content if this message is being edited, or hide content if redacted
                        val finalBody = if (isRedacted) {
                            "" // Hide original content for redacted messages
                        } else if (editedBy != null && editedBy.decrypted != null) {
                            val newContent = editedBy.decrypted?.optJSONObject("m.new_content")
                            val editFormat = newContent?.optString("format", "")
                            if (editFormat == "org.matrix.custom.html") {
                                newContent?.optString("formatted_body", "") ?: ""
                            } else {
                                newContent?.optString("body", "") ?: ""
                            }
                        } else if (editedBy != null && editedBy.content != null) {
                            val newContent = editedBy.content?.optJSONObject("m.new_content")
                            val editFormat = newContent?.optString("format", "")
                            if (editFormat == "org.matrix.custom.html") {
                                newContent?.optString("formatted_body", "") ?: ""
                            } else {
                                newContent?.optString("body", "") ?: ""
                            }
                        } else {
                            body
                        }
                        
                        // Check if this is a reply message
                        val replyInfo = event.getReplyInfo()
                        val originalEvent = replyInfo?.let { reply ->
                            timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
                        }
                        
                        // Check if it's a media message
                        if (msgType == "m.image" || msgType == "m.video") {
                            Log.d("Andromuks", "TimelineEventItem: Found encrypted media message - msgType=$msgType, body=$body")
                            
                            // Debug: Check what's in the decrypted object
                            Log.d("Andromuks", "TimelineEventItem: Direct url field: ${decrypted?.optString("url", "NOT_FOUND")}")
                            Log.d("Andromuks", "TimelineEventItem: File object exists: ${decrypted?.has("file")}")
                            if (decrypted?.has("file") == true) {
                                val fileObj = decrypted.optJSONObject("file")
                                Log.d("Andromuks", "TimelineEventItem: File url field: ${fileObj?.optString("url", "NOT_FOUND")}")
                            }
                            
                            // For encrypted messages, URL might be in file.url
                            val directUrl = decrypted?.optString("url", "") ?: ""
                            val fileObj = decrypted?.optJSONObject("file")
                            val fileUrl = fileObj?.optString("url", "") ?: ""
                            val url = directUrl.takeIf { it.isNotBlank() } ?: fileUrl
                            
                            Log.d("Andromuks", "TimelineEventItem: URL extraction - directUrl='$directUrl', fileObj=${fileObj != null}, fileUrl='$fileUrl', finalUrl='$url'")
                            
                            val filename = decrypted?.optString("filename", "") ?: ""
                            val info = decrypted?.optJSONObject("info")
                            
                            Log.d("Andromuks", "TimelineEventItem: Encrypted media data - url=$url, filename=$filename, info=${info != null}")
                            
                            if (url.isNotBlank() && info != null) {
                                val width = info.optInt("w", 0)
                                val height = info.optInt("h", 0)
                                val size = info.optLong("size", 0)
                                val mimeType = info.optString("mimetype", "")
                                val blurHash = info.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
                                
                                val caption = if (body != filename && body.isNotBlank()) body else null
                                
                                val mediaInfo = MediaInfo(
                                    width = width,
                                    height = height,
                                    size = size,
                                    mimeType = mimeType,
                                    blurHash = blurHash
                                )
                                
                                val mediaMessage = MediaMessage(
                                    url = url,
                                    filename = filename,
                                    caption = caption,
                                    info = mediaInfo,
                                    msgType = msgType
                                )
                                
                                Log.d("Andromuks", "TimelineEventItem: Created encrypted MediaMessage - url=${mediaMessage.url}, blurHash=${mediaMessage.info.blurHash}")
                                
                                // Display encrypted media message with nested reply structure
                                if (replyInfo != null && originalEvent != null) {
                                    Column {
                                        ReplyPreview(
                                            replyInfo = replyInfo,
                                            originalEvent = originalEvent,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            isMine = isMine,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                            onOriginalMessageClick = {
                                                onScrollToMessage(replyInfo.eventId)
                                            },
                                            timelineEvents = timelineEvents
                                        )
                                        MediaMessage(
                                            mediaMessage = mediaMessage,
                                            homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                            authToken = authToken,
                                            isMine = isMine,
                                            isEncrypted = true
                                        )
                                    }
                                } else {
                                    MediaMessage(
                                        mediaMessage = mediaMessage,
                                        homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                        authToken = authToken,
                                        isMine = isMine,
                                        isEncrypted = true
                                    )
                                }
                                
                                // Add reaction badges for encrypted media messages
                                if (appViewModel != null) {
                                    val reactions = appViewModel.messageReactions[event.eventId] ?: emptyList()
                                    if (reactions.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp),
                                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                                        ) {
                                            ReactionBadges(
                                                eventId = event.eventId,
                                                reactions = reactions
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Fallback to text message if encrypted media parsing fails
                                val bubbleShape = if (isMine) {
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 2.dp,
                                        bottomEnd = 8.dp,
                                        bottomStart = 16.dp
                                    )
                                } else {
                                    RoundedCornerShape(
                                        topStart = 2.dp,
                                        topEnd = 16.dp,
                                        bottomEnd = 16.dp,
                                        bottomStart = 8.dp
                                    )
                                }
                                val bubbleColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        color = bubbleColor,
                                        shape = bubbleShape,
                                        tonalElevation = 2.dp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Text(
                                            text = body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textColor,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                                
                                // Add reaction badges for encrypted text message
                                if (appViewModel != null) {
                                    val reactions = appViewModel.messageReactions[event.eventId] ?: emptyList()
                                    if (reactions.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp),
                                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                                        ) {
                                            ReactionBadges(
                                                eventId = event.eventId,
                                                reactions = reactions
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Regular text message
                            val bubbleShape = if (isMine) {
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 2.dp,
                                    bottomEnd = 8.dp,
                                    bottomStart = 16.dp
                                )
                            } else {
                                RoundedCornerShape(
                                    topStart = 2.dp,
                                    topEnd = 16.dp,
                                    bottomEnd = 16.dp,
                                    bottomStart = 8.dp
                                )
                            }
                            val bubbleColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                            ) {
                                // Display encrypted text message with nested reply structure
                                if (replyInfo != null && originalEvent != null) {
                                    Surface(
                                        modifier = Modifier.padding(top = 4.dp),
                                        color = bubbleColor,
                                        shape = bubbleShape,
                                        tonalElevation = 2.dp
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            // Reply preview (clickable original message)
                                            ReplyPreview(
                                                replyInfo = replyInfo,
                                                originalEvent = originalEvent,
                                                userProfileCache = userProfileCache,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isMine = isMine,
                                                modifier = Modifier.padding(bottom = 8.dp),
                                                onOriginalMessageClick = {
                                                onScrollToMessage(replyInfo.eventId)
                                                },
                                                timelineEvents = timelineEvents
                                            )
                                            
                                            // Reply message content (directly in the outer bubble, no separate bubble)
                                            SmartMessageText(
                                                body = finalBody,
                                                format = format,
                                                userProfileCache = userProfileCache,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                appViewModel = appViewModel,
                                                roomId = event.roomId,
                                                modifier = Modifier
                                            )
                                            
                                            // Show redaction indicators for encrypted reply
                                            if (isRedacted) {
                                                Text(
                                                    text = net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessage(redactionSender, redactionReason, redactionEvent?.timestamp, userProfileCache),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontStyle = FontStyle.Italic,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Regular encrypted message bubble
                                    Surface(
                                        modifier = Modifier.padding(top = 4.dp),
                                        color = bubbleColor,
                                        shape = bubbleShape,
                                        tonalElevation = 2.dp
                                    ) {
                                        SmartMessageText(
                                            body = finalBody,
                                            format = format,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            appViewModel = appViewModel,
                                            roomId = event.roomId,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                        
                                        // Show redaction indicators for encrypted message
                                        if (isRedacted) {
                                            Text(
                                                text = net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessage(redactionSender, redactionReason, redactionEvent?.timestamp, userProfileCache),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontStyle = FontStyle.Italic,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Add reaction badges for encrypted text message
                            if (appViewModel != null) {
                                val reactions = appViewModel.messageReactions[event.eventId] ?: emptyList()
                                if (reactions.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                                    ) {
                                        ReactionBadges(
                                            eventId = event.eventId,
                                            reactions = reactions
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Encrypted message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                "m.reaction" -> {
                    // Reactions are handled as badges below messages, not as separate timeline items
                    // This case should rarely be hit since reactions are usually processed differently
                }
                else -> {
                    Text(
                        text = "Event type: ${event.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        if (isMine) {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}


@Composable
fun RoomHeader(
    roomState: RoomState?,
    fallbackName: String,
    homeserverUrl: String,
    authToken: String
) {
    // Debug logging
    android.util.Log.d("Andromuks", "RoomHeader: roomState = $roomState")
    android.util.Log.d("Andromuks", "RoomHeader: fallbackName = $fallbackName")
    android.util.Log.d("Andromuks", "RoomHeader: homeserverUrl = $homeserverUrl")
    android.util.Log.d("Andromuks", "RoomHeader: authToken = ${authToken.take(10)}...")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Room avatar
            AvatarImage(
                mxcUrl = roomState?.avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = roomState?.name ?: fallbackName,
                size = 48.dp
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Room info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Canonical alias or room ID (above room name)
                val aliasOrId = roomState?.canonicalAlias 
                    ?: roomState?.roomId?.let { "!$it" }
                
                aliasOrId?.let { alias ->
                    Text(
                        text = alias,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Room name (prefer room state name, fallback to fallback name)
                val displayName = roomState?.name ?: fallbackName
                
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (aliasOrId != null) 2.dp else 0.dp)
                )
                
                // Room topic (if available)
                roomState?.topic?.let { topic ->
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}







