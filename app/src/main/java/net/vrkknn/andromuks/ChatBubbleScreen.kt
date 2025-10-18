package net.vrkknn.andromuks

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.connectToWebsocket
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import net.vrkknn.andromuks.utils.MessageBubbleWithMenu
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.supportsHtmlRendering
import net.vrkknn.andromuks.utils.EmoteEventNarrator

/**
 * Loading screen for chat bubbles - handles authentication and navigation
 */
@Composable
fun ChatBubbleLoadingScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    onCloseBubble: () -> Unit = {}
) {
    val context = LocalContext.current
    val sharedPreferences = remember(context) { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val token = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", null) }
    val homeserverUrl = remember(sharedPreferences) { sharedPreferences.getString("homeserver_url", null) }
    
    // Handle back button press to minimize the bubble
    BackHandler {
        Log.d("Andromuks", "ChatBubbleLoadingScreen: Back button pressed - minimizing bubble")
        Log.d("Andromuks", "ChatBubbleLoadingScreen: BackHandler triggered - calling onCloseBubble")
        onCloseBubble()
        Log.d("Andromuks", "ChatBubbleLoadingScreen: BackHandler - onCloseBubble call completed")
    }
    
    LaunchedEffect(Unit) {
        Log.d("Andromuks", "ChatBubbleLoadingScreen: ═══ BUBBLE LOADING STARTED ═══")
        
        if (token != null && homeserverUrl != null) {
            Log.d("Andromuks", "ChatBubbleLoadingScreen: Token and server URL found")
            appViewModel.initializeFCM(context, homeserverUrl, token)
            appViewModel.updateHomeserverUrl(homeserverUrl)
            appViewModel.updateAuthToken(token)
            
            // Get room ID from intent first
            val roomId = (context as? ComponentActivity)?.intent?.getStringExtra("room_id")
            Log.d("Andromuks", "ChatBubbleLoadingScreen: Room ID from intent: $roomId")
            
            if (roomId == null) {
                Log.e("Andromuks", "ChatBubbleLoadingScreen: ✗ No room ID found in intent!")
                return@LaunchedEffect
            }
            
            // Check if WebSocket is connected, if not, connect it
            val isWebSocketConnected = appViewModel.isWebSocketConnected()
            Log.d("Andromuks", "ChatBubbleLoadingScreen: WebSocket connected: $isWebSocketConnected")
            
            if (!isWebSocketConnected) {
                Log.d("Andromuks", "ChatBubbleLoadingScreen: WebSocket not connected, establishing connection...")
                val client = OkHttpClient()
                connectToWebsocket(homeserverUrl, client, token, appViewModel)
                
                // Wait for WebSocket to connect (max 3 seconds)
                var waitTime = 0
                while (!appViewModel.isWebSocketConnected() && waitTime < 3000) {
                    kotlinx.coroutines.delay(100)
                    waitTime += 100
                }
                
                if (appViewModel.isWebSocketConnected()) {
                    Log.d("Andromuks", "ChatBubbleLoadingScreen: ✓ WebSocket connected after ${waitTime}ms")
                } else {
                    Log.e("Andromuks", "ChatBubbleLoadingScreen: ✗ WebSocket failed to connect after ${waitTime}ms")
                    // Continue anyway - cache might work
                }
            } else {
                Log.d("Andromuks", "ChatBubbleLoadingScreen: ✓ Using existing WebSocket service connection")
            }
            
            Log.d("Andromuks", "ChatBubbleLoadingScreen: Navigating to chat bubble: $roomId")
            navController.navigate("chat_bubble/$roomId")
            Log.d("Andromuks", "ChatBubbleLoadingScreen: ✓ Navigation completed")
        } else {
            Log.d("Andromuks", "ChatBubbleLoadingScreen: No token or server URL, navigating to login")
            navController.navigate("login")
        }
    }
    
    AndromuksTheme {
        Surface {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading chat bubble...")
            }
        }
    }
}

/**
 * Simplified Chat Bubble Screen for Android Chat Bubbles
 * This is a minimal version of RoomTimelineScreen focused on basic messaging
 */
@Composable
fun ChatBubbleScreen(
    roomId: String,
    roomName: String,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    onCloseBubble: () -> Unit = {}
) {
    // Debug logging for bubble lifecycle
    LaunchedEffect(Unit) {
        Log.d("Andromuks", "ChatBubbleScreen: Launched - roomId: $roomId")
    }
    
    val context = LocalContext.current
    
    // Handle back button press to minimize the bubble
    BackHandler {
        Log.d("Andromuks", "ChatBubbleScreen: Back button pressed - minimizing bubble")
        Log.d("Andromuks", "ChatBubbleScreen: BackHandler triggered - calling onCloseBubble")
        onCloseBubble()
        Log.d("Andromuks", "ChatBubbleScreen: BackHandler - onCloseBubble call completed")
    }
    
    DisposableEffect(Unit) {
        Log.d("Andromuks", "ChatBubbleScreen: Disposed - roomId: $roomId")
        onDispose {
            Log.d("Andromuks", "ChatBubbleScreen: onDispose - roomId: $roomId")
            Log.d("Andromuks", "ChatBubbleScreen: onDispose - Stack trace:")
            Thread.currentThread().stackTrace.take(15).forEach {
                Log.d("Andromuks", "ChatBubbleScreen: onDispose -   at $it")
            }
            Log.d("Andromuks", "ChatBubbleScreen: onDispose - Activity state check:")
            Log.d("Andromuks", "ChatBubbleScreen: onDispose - isFinishing: ${(context as? ComponentActivity)?.isFinishing}")
            Log.d("Andromuks", "ChatBubbleScreen: onDispose - isDestroyed: ${(context as? ComponentActivity)?.isDestroyed}")
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences = remember(context) { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val authToken = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val myUserId = appViewModel.currentUserId
    val homeserverUrl = appViewModel.homeserverUrl
    val timelineEvents = appViewModel.timelineEvents
    val isLoading = appViewModel.isTimelineLoading
    
    Log.d("Andromuks", "ChatBubbleScreen: Loading timeline for room: $roomId")
    
    // Get the room item to check if it's a DM and get proper display name
    val roomItem = appViewModel.getRoomById(roomId)
    val isDirectMessage = roomItem?.isDirectMessage ?: false
    
    // For DM rooms, calculate the display name from member profiles
    val displayRoomName = if (isDirectMessage && roomItem != null) {
        val memberMap = appViewModel.getMemberMap(roomId)
        val otherParticipant = memberMap.keys.find { it != myUserId }
        val otherProfile = otherParticipant?.let { memberMap[it] }
        otherProfile?.displayName ?: otherParticipant ?: roomName
    } else {
        roomName
    }
    
    // For DM rooms, get the avatar from the other participant
    val displayAvatarUrl = if (isDirectMessage && roomItem != null) {
        val memberMap = appViewModel.getMemberMap(roomId)
        val otherParticipant = memberMap.keys.find { it != myUserId }
        val otherProfile = otherParticipant?.let { memberMap[it] }
        otherProfile?.avatarUrl
    } else {
        appViewModel.currentRoomState?.avatarUrl
    }
    
    // Define allowed event types (simplified for chat bubbles)
    val allowedEventTypes = setOf(
        "m.room.message",
        "m.room.encrypted"
    )
    
    // Sort events so newer messages are at the bottom, and filter to only show messages
    val sortedEvents = remember(timelineEvents) {
        Log.d("Andromuks", "ChatBubbleScreen: Processing ${timelineEvents.size} timeline events")
        
        val filteredEvents = timelineEvents.filter { event ->
            allowedEventTypes.contains(event.type) && event.type != "m.room.redaction"
        }
        
        // Filter out superseded events (original messages that have been edited)
        val eventsWithoutSuperseded = filteredEvents.filter { event ->
            if (event.type == "m.room.message") {
                val hasBeenEdited = filteredEvents.any { otherEvent ->
                    otherEvent.type == "m.room.message" &&
                    otherEvent.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace" &&
                    otherEvent.content?.optJSONObject("m.relates_to")?.optString("event_id") == event.eventId
                }
                !hasBeenEdited
            } else {
                true
            }
        }
        
        val sorted = eventsWithoutSuperseded.sortedBy { it.timestamp }
        Log.d("Andromuks", "ChatBubbleScreen: Final sorted events: ${sorted.size} events")
        sorted
    }

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()
    LaunchedEffect(sortedEvents.size, isLoading) {
        Log.d("Andromuks", "ChatBubbleScreen: LaunchedEffect - sortedEvents.size: ${sortedEvents.size}, isLoading: $isLoading")
        if (!isLoading && sortedEvents.isNotEmpty()) {
            Log.d("Andromuks", "ChatBubbleScreen: Auto-scrolling to bottom")
            listState.scrollToItem(sortedEvents.lastIndex)
        } else if (!isLoading && sortedEvents.isEmpty()) {
            Log.d("Andromuks", "ChatBubbleScreen: No events to display, staying at top")
        }
    }
    
    // Track if we've already triggered pagination for this scroll position
    var lastPaginationTrigger by remember(sortedEvents.size, roomId) { mutableStateOf(Pair(-1, -1)) }
    
    // Monitor scroll position for pagination
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 10 && !isLoading) {
            val currentPosition = Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            if (currentPosition != lastPaginationTrigger) {
                lastPaginationTrigger = currentPosition
                appViewModel.loadOlderMessages(roomId)
            }
        }
    }
    
    LaunchedEffect(roomId) {
        Log.d("Andromuks", "ChatBubbleScreen: ═══ LOADING TIMELINE ═══")
        Log.d("Andromuks", "ChatBubbleScreen:   Room ID: $roomId")
        Log.d("Andromuks", "ChatBubbleScreen:   Current events: ${timelineEvents.size}")
        Log.d("Andromuks", "ChatBubbleScreen:   Is loading: $isLoading")
        Log.d("Andromuks", "ChatBubbleScreen:   WebSocket: ${appViewModel.isWebSocketConnected()}")
        
        // Request room state and timeline (will use cache if available)
        appViewModel.requestRoomState(roomId)
        appViewModel.requestRoomTimeline(roomId)
        
        // Wait a bit and log the result
        kotlinx.coroutines.delay(500)
        Log.d("Andromuks", "ChatBubbleScreen: After requestRoomTimeline:")
        Log.d("Andromuks", "ChatBubbleScreen:   Events: ${timelineEvents.size}")
        Log.d("Andromuks", "ChatBubbleScreen:   Is loading: $isLoading")
        
        // If still loading after 5 seconds, log warning
        kotlinx.coroutines.delay(4500)
        if (isLoading) {
            Log.e("Andromuks", "ChatBubbleScreen: ✗ STILL LOADING after 5 seconds!")
            Log.e("Andromuks", "ChatBubbleScreen:   This indicates cache miss + paginate not completing")
            Log.e("Andromuks", "ChatBubbleScreen:   WebSocket connected: ${appViewModel.isWebSocketConnected()}")
        } else {
            Log.d("Andromuks", "ChatBubbleScreen: ✓ Timeline loaded successfully with ${timelineEvents.size} events")
        }
    }
    
    // Use imePadding for keyboard handling
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (imeBottom > 0.dp) imeBottom else navBarBottom
    
    AndromuksTheme {
        Surface {
            Column(
                modifier = modifier.fillMaxSize()
            ) {
                // Room Header (compact for bubbles)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Room avatar (compact size)
                        AvatarImage(
                            mxcUrl = displayAvatarUrl,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            fallbackText = displayRoomName,
                            size = 36.dp,
                            userId = roomId,
                            displayName = displayRoomName
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // Room name (slightly smaller)
                        Text(
                            text = displayRoomName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                // Timeline (compressible, scrollable content)
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading...")
                    }
                } else if (sortedEvents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No messages yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Start the conversation!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(
                            horizontal = 12.dp,
                            vertical = 8.dp
                        )
                    ) {
                        itemsIndexed(sortedEvents) { index, event ->
                            // Check if this is a consecutive message from the same sender
                            val previousEvent = if (index > 0) sortedEvents[index - 1] else null
                            
                            // Check if current event has per-message profile (from bridges like Beeper)
                            // These should always show avatar/name even if from same Matrix user
                            val hasPerMessageProfile =
                                event.content?.has("com.beeper.per_message_profile") == true ||
                                event.decrypted?.has("com.beeper.per_message_profile") == true
                            
                            val isConsecutive =
                                !hasPerMessageProfile &&
                                previousEvent?.sender == event.sender
                            
                            ChatBubbleEventItem(
                                event = event,
                                timelineEvents = timelineEvents,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                userProfileCache = appViewModel.getMemberMap(roomId),
                                isMine = myUserId != null && event.sender == myUserId,
                                myUserId = myUserId,
                                isConsecutive = isConsecutive,
                                appViewModel = appViewModel
                            )
                        }
                    }
                }
                
                // Text input field (compact for bubbles)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = bottomInset)
                ) {
                    var draft by remember { mutableStateOf("") }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Text input field (compact)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            TextField(
                                value = draft,
                                onValueChange = { draft = it },
                                placeholder = { 
                                    Text(
                                        "Type a message...",
                                        style = MaterialTheme.typography.bodyMedium
                                    ) 
                                },
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        // Send button (compact)
                        Button(
                            onClick = { 
                                if (draft.isNotBlank()) {
                                    appViewModel.sendMessage(roomId, draft)
                                    draft = ""
                                }
                            },
                            enabled = draft.isNotBlank(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary 
                                               else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
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

/**
 * Format timestamp to time string (HH:mm) for chat bubbles
 */
private fun formatChatBubbleTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(date)
}

/**
 * Inline timestamp displayed inside bubble for consecutive messages
 */
@Composable
fun InlineBubbleTimestamp(
    timestamp: Long,
    isMine: Boolean,
    isConsecutive: Boolean
) {
    // Only show timestamp inside bubble for consecutive messages
    if (isConsecutive) {
        Text(
            text = " ${formatChatBubbleTimestamp(timestamp)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * Simplified event item for chat bubbles
 */
@Composable
fun ChatBubbleEventItem(
    event: TimelineEvent,
    timelineEvents: List<TimelineEvent>,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    isMine: Boolean,
    myUserId: String?,
    isConsecutive: Boolean = false,
    appViewModel: AppViewModel? = null
) {
    val context = LocalContext.current
    
    // Check for per-message profile (e.g., from Beeper bridge)
    val perMessageProfile = event.content?.optJSONObject("com.beeper.per_message_profile")
    val encryptedPerMessageProfile = event.decrypted?.optJSONObject("com.beeper.per_message_profile")
    val hasPerMessageProfile = perMessageProfile != null
    val hasEncryptedPerMessageProfile = encryptedPerMessageProfile != null
    
    // Use per-message profile if available, otherwise fall back to regular profile cache
    val actualProfile = when {
        hasEncryptedPerMessageProfile -> {
            val encryptedPerMessageDisplayName = encryptedPerMessageProfile?.optString("displayname")?.takeIf { it.isNotBlank() }
            val encryptedPerMessageAvatarUrl = encryptedPerMessageProfile?.optString("avatar_url")?.takeIf { it.isNotBlank() }
            MemberProfile(encryptedPerMessageDisplayName, encryptedPerMessageAvatarUrl)
        }
        hasPerMessageProfile -> {
            val perMessageDisplayName = perMessageProfile?.optString("displayname")?.takeIf { it.isNotBlank() }
            val perMessageAvatarUrl = perMessageProfile?.optString("avatar_url")?.takeIf { it.isNotBlank() }
            MemberProfile(perMessageDisplayName, perMessageAvatarUrl)
        }
        else -> userProfileCache[event.sender]
    }
    
    val displayName = actualProfile?.displayName
    val avatarUrl = actualProfile?.avatarUrl
    
    // For per-message profiles, check if the message is "mine" based on the per-message profile user ID
    val actualIsMine = if (hasPerMessageProfile || hasEncryptedPerMessageProfile) {
        val perMessageUserId = if (hasEncryptedPerMessageProfile) {
            encryptedPerMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
        } else {
            perMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
        }
        myUserId != null && perMessageUserId == myUserId
    } else {
        isMine
    }
    
    // Early return for edit events (m.replace relationships)
    val isEditEvent = (event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") ||
                     (event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace")
    if (isEditEvent) {
        return
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Show avatar only for non-consecutive messages
        if (!actualIsMine && !isConsecutive) {
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = (displayName ?: event.sender).take(1),
                size = 24.dp,
                userId = event.sender,
                displayName = displayName
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (!actualIsMine && isConsecutive) {
            // Add spacer to maintain alignment for consecutive messages
            Spacer(modifier = Modifier.width(32.dp)) // 24dp avatar + 8dp spacer
        }
        
        // Event content
        Column(modifier = Modifier.weight(1f)) {
            // Show sender name and timestamp only for non-consecutive messages
            if (!actualIsMine && !isConsecutive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = displayName ?: event.sender,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatChatBubbleTimestamp(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            when (event.type) {
                "m.room.message" -> {
                    // Check if this is an edit event (m.replace relationship)
                    val isEditEvent = event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
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
                    
                    // Handle m.emote messages with narrator rendering
                    if (msgType == "m.emote") {
                        EmoteEventNarrator(
                            event = event,
                            displayName = displayName ?: event.sender,
                            avatarUrl = avatarUrl,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onReply = { /* No reply in chat bubbles */ },
                            onReact = { /* No reactions in chat bubbles */ },
                            onEdit = { /* No edit in chat bubbles */ },
                            onDelete = { /* No delete in chat bubbles */ }
                        )
                        return
                    }
                    
                    // OPTIMIZED: Check if this message has been redacted using O(1) lookup
                    val isRedacted = event.redactedBy != null
                    val redactionEvent = if (isRedacted && appViewModel != null) {
                        appViewModel.getRedactionEvent(event.eventId)  // O(1) lookup!
                    } else null
                    
                    // Show deletion message if redacted, otherwise show the message content
                    val finalBody = if (isRedacted) {
                        net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(redactionEvent, userProfileCache)
                    } else {
                        body
                    }
                    
                    // Check if it's a media message
                    if (msgType == "m.image" || msgType == "m.video") {
                        val url = content?.optString("url", "") ?: ""
                        val filename = content?.optString("filename", "") ?: ""
                        val info = content?.optJSONObject("info")
                        
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
                            
                            // Display media message
                            MediaMessage(
                                mediaMessage = mediaMessage,
                                homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                authToken = authToken,
                                isMine = actualIsMine,
                                event = event,
                                onReply = { /* No reply in chat bubbles */ },
                                onReact = { /* No reactions in chat bubbles */ },
                                onEdit = { /* No edit in chat bubbles */ },
                                onDelete = { /* No delete in chat bubbles */ }
                            )
                        } else {
                            // Fallback to text message if media parsing fails
                            val bubbleShape = if (actualIsMine) {
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
                            val bubbleColor = if (actualIsMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = bubbleColor,
                                    shape = bubbleShape,
                                    tonalElevation = 2.dp,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = finalBody,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // Regular text message
                        val bubbleShape = if (actualIsMine) {
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
                        val bubbleColor = if (actualIsMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
                        ) {
                            // Simple message bubble (no menu for chat bubbles)
                            Surface(
                                color = bubbleColor,
                                shape = bubbleShape,
                                tonalElevation = 2.dp,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    // Text with extra padding at the end for timestamp (if consecutive)
                                    AdaptiveMessageText(
                                        event = event,
                                        body = finalBody,
                                        format = format,
                                        userProfileCache = userProfileCache,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        appViewModel = appViewModel,
                                        roomId = event.roomId,
                                        textColor = if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier
                                    )
                                    // Timestamp positioned at bottom-end for consecutive messages
                                    Box(
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                    ) {
                                        InlineBubbleTimestamp(
                                            timestamp = event.timestamp,
                                            isMine = actualIsMine,
                                            isConsecutive = isConsecutive
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "m.room.encrypted" -> {
                    // Check if this is an edit event (m.replace relationship)
                    val isEditEvent = event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    if (isEditEvent) {
                        return
                    }
                    
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
                        
                        // Handle encrypted m.emote messages with narrator rendering
                        if (msgType == "m.emote") {
                            EmoteEventNarrator(
                                event = event,
                                displayName = displayName ?: event.sender,
                                avatarUrl = avatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                onReply = { /* No reply in chat bubbles */ },
                                onReact = { /* No reactions in chat bubbles */ },
                                onEdit = { /* No edit in chat bubbles */ },
                                onDelete = { /* No delete in chat bubbles */ }
                            )
                            return
                        }
                        
                        // OPTIMIZED: Check if this message has been redacted using O(1) lookup
                        val isRedacted = event.redactedBy != null
                        val redactionEvent = if (isRedacted && appViewModel != null) {
                            appViewModel.getRedactionEvent(event.eventId)  // O(1) lookup!
                        } else null
                        
                        val finalBody = if (isRedacted) {
                            net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(redactionEvent, userProfileCache)
                        } else {
                            body
                        }
                        
                        // Check if it's a media message
                        if (msgType == "m.image" || msgType == "m.video") {
                            // For encrypted messages, URL might be in file.url
                            val directUrl = decrypted?.optString("url", "") ?: ""
                            val fileObj = decrypted?.optJSONObject("file")
                            val fileUrl = fileObj?.optString("url", "") ?: ""
                            val url = directUrl.takeIf { it.isNotBlank() } ?: fileUrl
                            
                            val filename = decrypted?.optString("filename", "") ?: ""
                            val info = decrypted?.optJSONObject("info")
                            
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
                                
                                // Display encrypted media message
                                MediaMessage(
                                    mediaMessage = mediaMessage,
                                    homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                    authToken = authToken,
                                    isMine = actualIsMine,
                                    isEncrypted = true,
                                    event = event,
                                    onReply = { /* No reply in chat bubbles */ },
                                    onReact = { /* No reactions in chat bubbles */ },
                                    onEdit = { /* No edit in chat bubbles */ },
                                    onDelete = { /* No delete in chat bubbles */ }
                                )
                            } else {
                                // Fallback to text message if encrypted media parsing fails
                                val bubbleShape = if (actualIsMine) {
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
                                val bubbleColor = if (actualIsMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        color = bubbleColor,
                                        shape = bubbleShape,
                                        tonalElevation = 2.dp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Text(
                                            text = finalBody,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Regular encrypted text message
                            val bubbleShape = if (actualIsMine) {
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
                            val bubbleColor = if (actualIsMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = bubbleColor,
                                    shape = bubbleShape,
                                    tonalElevation = 2.dp,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        // Text with extra padding at the end for timestamp (if consecutive)
                                        AdaptiveMessageText(
                                            event = event,
                                            body = finalBody,
                                            format = format,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            appViewModel = appViewModel,
                                            roomId = event.roomId,
                                            textColor = if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier
                                        )
                                        // Timestamp positioned at bottom-end for consecutive messages
                                        Box(
                                            modifier = Modifier.align(Alignment.BottomEnd)
                                        ) {
                                            InlineBubbleTimestamp(
                                                timestamp = event.timestamp,
                                                isMine = actualIsMine,
                                                isConsecutive = isConsecutive
                                            )
                                        }
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
                else -> {
                    Text(
                        text = "Event type: ${event.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        if (actualIsMine) {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
