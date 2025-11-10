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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.zIndex
import okhttp3.OkHttpClient
import kotlin.math.min
import net.vrkknn.andromuks.utils.connectToWebsocket
import net.vrkknn.andromuks.utils.waitForBackendHealth
import net.vrkknn.andromuks.utils.MessageBubbleWithMenu
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.supportsHtmlRendering
import net.vrkknn.andromuks.utils.EmoteEventNarrator

/** Floating member list for mentions */
@Composable
private fun ChatBubbleMentionMemberList(
    members: Map<String, MemberProfile>,
    query: String,
    onMemberSelect: (String, String?) -> Unit,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier
) {
    val filteredMembers = remember(members, query) {
        members.filter { (userId, profile) ->
            val displayName = profile.displayName
            val username = userId.removePrefix("@").substringBefore(":")
            query.isBlank() || 
            displayName?.contains(query, ignoreCase = true) == true ||
            username.contains(query, ignoreCase = true) ||
            userId.contains(query, ignoreCase = true)
        }.entries.sortedBy { (userId, profile) -> 
            profile.displayName ?: userId 
        }
    }

    if (filteredMembers.isEmpty()) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 250.dp)
                .height(200.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            items(filteredMembers.size) { index ->
                val (userId, profile) = filteredMembers.toList()[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMemberSelect(userId, profile.displayName) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AvatarImage(
                        mxcUrl = profile.avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = (profile.displayName ?: userId).take(1),
                        size = 32.dp,
                        userId = userId,
                        displayName = profile.displayName
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName ?: userId.removePrefix("@"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (profile.displayName != null) {
                            Text(
                                text = userId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

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
    val httpClient = remember { OkHttpClient.Builder().build() }
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
            appViewModel.loadSettings(context)
            
            val cachedState = appViewModel.loadStateFromStorage(context)
            Log.d("Andromuks", "ChatBubbleLoadingScreen: Cached state loaded: $cachedState")
            
            // Get room ID from intent first
            val roomId = (context as? ComponentActivity)?.intent?.getStringExtra("room_id")
            Log.d("Andromuks", "ChatBubbleLoadingScreen: Room ID from intent: $roomId")
            
            if (roomId == null) {
                Log.e("Andromuks", "ChatBubbleLoadingScreen: ✗ No room ID found in intent!")
                return@LaunchedEffect
            }
            
            appViewModel.setPendingBubbleNavigation(roomId)
            
            // Check WebSocket connection status (should be already connected from main app flow)
            val isWebSocketConnected = appViewModel.isWebSocketConnected()
            Log.d("Andromuks", "ChatBubbleLoadingScreen: WebSocket connected: $isWebSocketConnected")
            if (!isWebSocketConnected) {
                Log.w("Andromuks", "ChatBubbleLoadingScreen: WebSocket not connected - starting foreground service and connecting")
                appViewModel.startWebSocketService()
                WebSocketService.setAppVisibility(true)
                waitForBackendHealth(homeserverUrl, loggerTag = "ChatBubbleLoading")
                connectToWebsocket(homeserverUrl, httpClient, token, appViewModel, reason = "chat_bubble_launch")
            } else {
                // Ensure service stays awake for bubble interaction
                WebSocketService.setAppVisibility(true)
            }
            
            // Prepare navigation callback to defer until initial sync completes
            appViewModel.setNavigationCallback {
                val pendingBubbleId = appViewModel.getPendingBubbleNavigation() ?: roomId
                if (pendingBubbleId != null) {
                    Log.d("Andromuks", "ChatBubbleLoadingScreen: Navigation callback - opening bubble $pendingBubbleId")
                    appViewModel.clearPendingBubbleNavigation()
                    navController.navigate("chat_bubble/$pendingBubbleId") {
                        popUpTo("chat_bubble_loading") { inclusive = true }
                    }
                }
            }
            
            // If spaces already loaded (e.g., app already running), navigate immediately
            if (appViewModel.spacesLoaded) {
                val pendingBubbleId = appViewModel.getPendingBubbleNavigation() ?: roomId
                if (pendingBubbleId != null) {
                    Log.d("Andromuks", "ChatBubbleLoadingScreen: Spaces already loaded - opening bubble immediately for $pendingBubbleId")
                    appViewModel.clearPendingBubbleNavigation()
                    navController.navigate("chat_bubble/$pendingBubbleId") {
                        popUpTo("chat_bubble_loading") { inclusive = true }
                    }
                }
            }
        } else {
            Log.d("Andromuks", "ChatBubbleLoadingScreen: No token or server URL, navigating to login")
            navController.navigate("login")
        }
    }

    val spacesLoaded = appViewModel.spacesLoaded
    var hasNavigated by remember { mutableStateOf(false) }

    LaunchedEffect(spacesLoaded, hasNavigated) {
        if (!hasNavigated && spacesLoaded) {
            val contextRoomId = (context as? ComponentActivity)?.intent?.getStringExtra("room_id")
            val pendingBubbleId = appViewModel.getPendingBubbleNavigation() ?: contextRoomId
            if (!pendingBubbleId.isNullOrBlank()) {
                Log.d("Andromuks", "ChatBubbleLoadingScreen: Spaces became loaded - navigating to $pendingBubbleId")
                appViewModel.clearPendingBubbleNavigation()
                hasNavigated = true
                navController.navigate("chat_bubble/$pendingBubbleId") {
                    popUpTo("chat_bubble_loading") { inclusive = true }
                }
            }
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
    
    // OPTIMIZED: Sort events and filter - no O(n²) operations
    // The AppViewModel already handles edit filtering via buildTimelineFromChain()
    val sortedEvents = remember(timelineEvents) {
        Log.d("Andromuks", "ChatBubbleScreen: Processing ${timelineEvents.size} timeline events")
        
        // Simple O(n) filter - just filter by type
        // Edit filtering is already done by AppViewModel's buildTimelineFromChain()
        val filteredEvents = timelineEvents.filter { event ->
            allowedEventTypes.contains(event.type) && event.type != "m.room.redaction"
        }
        
        val sorted = filteredEvents.sortedBy { it.timestamp }
        Log.d("Andromuks", "ChatBubbleScreen: Final sorted events: ${sorted.size} events")
        sorted
    }
    
    // Cache member map outside of items for better performance
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId)
    }

    // Mention state
    var showMentionList by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    
    // Text input state (moved here to be accessible by mention handler)
    var draft by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    
    // Sync draft with TextFieldValue
    LaunchedEffect(draft) {
        if (textFieldValue.text != draft) {
            textFieldValue = textFieldValue.copy(text = draft, selection = TextRange(draft.length))
        }
    }

    // Get current room members for mention list (exclude current user and filter out invalid entries)
    val roomMembers = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId).filter { (userId, profile) ->
            // Exclude current user
            userId != myUserId &&
            // Ensure userId is a valid Matrix user ID format (@user:domain)
            userId.startsWith("@") && 
            userId.contains(":") &&
            // Ensure userId is not empty or malformed
            userId.length > 3
        }
    }

    // Mention detection and handling functions
    fun detectMention(text: String, cursorPosition: Int): Pair<String, Int>? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null
        
        // Look for @ at or before cursor position
        var atIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            if (i < text.length && text[i] == '@') {
                atIndex = i
                break
            }
            // Stop if we hit a space or newline before finding @
            if (i < text.length && (text[i] == ' ' || text[i] == '\n')) {
                break
            }
        }
        
        // Also check if cursor is right after @ at the beginning or after space
        if (atIndex == -1 && cursorPosition > 0 && cursorPosition <= text.length) {
            if (text[cursorPosition - 1] == '@') {
                // Check if @ is at beginning or preceded by space/newline
                if (cursorPosition == 1 || (cursorPosition > 1 && (text[cursorPosition - 2] == ' ' || text[cursorPosition - 2] == '\n'))) {
                    atIndex = cursorPosition - 1
                }
            }
        }
        
        if (atIndex == -1) return null
        
        // Extract the query after @
        val queryStart = atIndex + 1
        var queryEnd = cursorPosition
        
        // Look for space after cursor position to find end of mention
        if (cursorPosition < text.length) {
            for (i in cursorPosition until text.length) {
                if (text[i] == ' ' || text[i] == '\n') {
                    queryEnd = i
                    break
                }
                queryEnd = i + 1
            }
        }
        
        // Allow showing mention list even if we just typed @ (empty query)
        if (queryStart <= cursorPosition) {
            val query = if (queryStart < min(queryEnd, text.length)) {
                text.substring(queryStart, min(queryEnd, text.length))
            } else {
                "" // Empty query when just @ is typed
            }
            return Pair(query, atIndex)
        }
        
        return null
    }

    fun handleMentionSelection(userId: String, displayName: String?, originalText: String, startIndex: Int, endIndex: Int): String {
        // Escape square brackets in display name to prevent regex issues
        val escapedDisplayName = (displayName ?: userId.removePrefix("@"))
            .replace("[", "\\[")
            .replace("]", "\\]")
        val mentionText = "[$escapedDisplayName](https://matrix.to/#/$userId)"
        return originalText.substring(0, startIndex) + mentionText + originalText.substring(endIndex)
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
    
    // Request updated profile information for users in the room
    // OPPORTUNISTIC PROFILE LOADING: Only request profiles when actually needed for rendering
    // This prevents loading 15,000+ profiles upfront for large rooms
    LaunchedEffect(sortedEvents, roomId) {
        Log.d(
            "Andromuks",
            "ChatBubbleScreen: Using opportunistic profile loading for $roomId (no bulk loading)"
        )
        
        // Only request profiles for users that are actually visible in the timeline
        // This dramatically reduces memory usage for large rooms
        if (sortedEvents.isNotEmpty()) {
            val visibleUsers = sortedEvents.take(20) // Only first 20 events for bubble
                .map { it.sender }
                .distinct()
                .filter { it != appViewModel.currentUserId }
            
            Log.d(
                "Andromuks",
                "ChatBubbleScreen: Requesting profiles on-demand for ${visibleUsers.size} visible users (instead of all ${sortedEvents.size} events)"
            )
            
            // Request profiles one by one as needed
            visibleUsers.forEach { userId ->
                appViewModel.requestUserProfileOnDemand(userId, roomId)
            }
        }
    }
    
    // Use imePadding for keyboard handling
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (imeBottom > 0.dp) imeBottom else navBarBottom
    
    AndromuksTheme {
        Surface {
            Box(modifier = modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize()
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
                        itemsIndexed(
                            items = sortedEvents,
                            key = { _, event -> event.eventId }  // CRITICAL: Enables efficient recomposition
                        ) { index, event ->
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
                                userProfileCache = memberMap,  // Use cached member map
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
                            // Create mention transformation for TextField with proper caching
                            val colorScheme = MaterialTheme.colorScheme
                            val mentionTransformation = remember(colorScheme) {
                                VisualTransformation { text ->
                                    val mentionRegex = Regex("""\[((?:[^\[\]\\]|\\.)*)\]\(https://matrix\.to/#/([^)]+)\)""")
                                    val annotatedString = buildAnnotatedString {
                                        var lastIndex = 0
                                        
                                        for (match in mentionRegex.findAll(text.text)) {
                                            // Add text before mention
                                            if (match.range.first > lastIndex) {
                                                append(text.text.substring(lastIndex, match.range.first))
                                            }
                                            
                                            // Add mention as styled text (pill-like appearance)
                                            // Unescape the display name for display (remove backslashes before brackets)
                                            val escapedDisplayName = match.groupValues[1]
                                            val displayName = escapedDisplayName
                                                .replace("\\[", "[")
                                                .replace("\\]", "]")
                                            withStyle(
                                                style = SpanStyle(
                                                    color = colorScheme.onPrimaryContainer,
                                                    background = colorScheme.primaryContainer
                                                )
                                            ) {
                                                append(" $displayName ")
                                            }
                                            
                                            lastIndex = match.range.last + 1
                                        }
                                        
                                        // Add remaining text
                                        if (lastIndex < text.text.length) {
                                            append(text.text.substring(lastIndex))
                                        }
                                    }
                                    
                                    // Create proper offset mapping to handle the text length changes
                                    val offsetMapping = object : OffsetMapping {
                                        override fun originalToTransformed(offset: Int): Int {
                                            // Clamp offset to valid range
                                            val clampedOffset = offset.coerceIn(0, text.text.length)
                                            var transformedOffset = 0
                                            var originalOffset = 0
                                            
                                            for (match in mentionRegex.findAll(text.text)) {
                                                // Add text before mention
                                                val beforeLength = match.range.first - originalOffset
                                                if (clampedOffset <= match.range.first) {
                                                    val result = transformedOffset + (clampedOffset - originalOffset)
                                                    return result.coerceIn(0, annotatedString.length)
                                                }
                                                transformedOffset += beforeLength
                                                originalOffset = match.range.first
                                                
                                                // Handle mention transformation
                                                val escapedDisplayName = match.groupValues[1]
                                                val displayName = escapedDisplayName
                                                    .replace("\\[", "[")
                                                    .replace("\\]", "]")
                                                val transformedMentionLength = " $displayName ".length
                                                
                                                if (clampedOffset <= match.range.last + 1) {
                                                    val result = transformedOffset + transformedMentionLength
                                                    return result.coerceIn(0, annotatedString.length)
                                                }
                                                
                                                transformedOffset += transformedMentionLength
                                                originalOffset = match.range.last + 1
                                            }
                                            
                                            // Handle remaining text
                                            val result = transformedOffset + (clampedOffset - originalOffset)
                                            return result.coerceIn(0, annotatedString.length)
                                        }
                                        
                                        override fun transformedToOriginal(offset: Int): Int {
                                            // Clamp offset to valid range
                                            val clampedOffset = offset.coerceIn(0, annotatedString.length)
                                            var transformedOffset = 0
                                            var originalOffset = 0
                                            
                                            for (match in mentionRegex.findAll(text.text)) {
                                                val beforeLength = match.range.first - originalOffset
                                                if (clampedOffset <= transformedOffset + beforeLength) {
                                                    val result = originalOffset + (clampedOffset - transformedOffset)
                                                    return result.coerceIn(0, text.text.length)
                                                }
                                                transformedOffset += beforeLength
                                                originalOffset = match.range.first
                                                
                                                val escapedDisplayName = match.groupValues[1]
                                                val displayName = escapedDisplayName
                                                    .replace("\\[", "[")
                                                    .replace("\\]", "]")
                                                val transformedMentionLength = " $displayName ".length
                                                
                                                if (clampedOffset <= transformedOffset + transformedMentionLength) {
                                                    return match.range.last + 1
                                                }
                                                
                                                transformedOffset += transformedMentionLength
                                                originalOffset = match.range.last + 1
                                            }
                                            
                                            val result = originalOffset + (clampedOffset - transformedOffset)
                                            return result.coerceIn(0, text.text.length)
                                        }
                                    }
                                    
                                    TransformedText(
                                        annotatedString,
                                        offsetMapping
                                    )
                                }
                            }

                            // Text input field with mention support
                            TextField(
                                value = textFieldValue,
                                onValueChange = { newValue ->
                                    textFieldValue = newValue
                                    draft = newValue.text
                                    
                                    // Detect mentions
                                    val mentionResult = detectMention(newValue.text, newValue.selection.start)
                                    if (mentionResult != null) {
                                        val (query, startIndex) = mentionResult
                                        mentionQuery = query
                                        mentionStartIndex = startIndex
                                        showMentionList = true
                                    } else {
                                        showMentionList = false
                                    }
                                },
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
                                visualTransformation = mentionTransformation,
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
            
            // Floating member list for mentions - placed outside Column but inside main Box
            if (showMentionList) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = 72.dp, // Align with text input (attach button width + spacing)
                            bottom = 80.dp  // Above text input
                        )
                        .navigationBarsPadding()
                        .imePadding()
                        .zIndex(10f)
                ) {
                    ChatBubbleMentionMemberList(
                        members = roomMembers,
                        query = mentionQuery,
                        onMemberSelect = { userId: String, displayName: String? ->
                            // Replace the mention text with the selected user
                            val mentionEndIndex = mentionStartIndex + 1 + mentionQuery.length
                            val newText = handleMentionSelection(userId, displayName, draft, mentionStartIndex, mentionEndIndex)
                            
                            // Calculate the new cursor position after the inserted mention
                            // The cursor should be positioned right after the inserted mention text
                            val escapedDisplayName = (displayName ?: userId.removePrefix("@"))
                                .replace("[", "\\[")
                                .replace("]", "\\]")
                            val mentionText = "[$escapedDisplayName](https://matrix.to/#/$userId)"
                            val newCursorPosition = mentionStartIndex + mentionText.length
                            
                            draft = newText
                            textFieldValue = TextFieldValue(
                                text = newText,
                                selection = TextRange(newCursorPosition)
                            )
                            
                            // Hide the mention list
                            showMentionList = false
                            mentionQuery = ""
                        },
                        homeserverUrl = homeserverUrl,
                        authToken = authToken
                    )
                }
            }
        }
    }
    }
}

/**
 * Format timestamp to time string (HH:mm) for chat bubbles
 */
fun formatChatBubbleTimestamp(timestamp: Long): String {
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
    
    // PERFORMANCE: Cache profile extraction to avoid recalculation on every scroll frame
    val profileData = remember(event.eventId, event.content, event.decrypted) {
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
        
        Triple(actualProfile, actualIsMine, isEditEvent)
    }
    
    val actualProfile = profileData.first
    val actualIsMine = profileData.second
    val isEditEvent = profileData.third
    
    // Early return for edit events
    if (isEditEvent) {
        return
    }
    
    val displayName = actualProfile?.displayName
    val avatarUrl = actualProfile?.avatarUrl
    
    // Early check for emote message (to skip header since narrator includes it)
    val isEmoteMessage = when {
        event.type == "m.room.message" -> {
            val isEdit = event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            val content = if (isEdit) event.content?.optJSONObject("m.new_content") else event.content
            content?.optString("msgtype", "") == "m.emote"
        }
        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
            event.decrypted?.optString("msgtype", "") == "m.emote"
        }
        else -> false
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Show avatar only for non-consecutive messages (and not for emotes)
        if (!actualIsMine && !isConsecutive && !isEmoteMessage) {
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
        } else if (!actualIsMine && isConsecutive && !isEmoteMessage) {
            // Add spacer to maintain alignment for consecutive messages
            Spacer(modifier = Modifier.width(32.dp)) // 24dp avatar + 8dp spacer
        }
        
        // Event content
        Column(modifier = Modifier.weight(1f)) {
            // Show sender name and timestamp for non-consecutive messages (including our own, but not for emotes)
            if (!isConsecutive && !isEmoteMessage) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    Text(
                        text = displayName ?: event.sender,
                        style = MaterialTheme.typography.labelMedium,
                        color = net.vrkknn.andromuks.utils.UserColorUtils.getUserColor(event.sender)
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
                            onDelete = { /* No delete in chat bubbles */ },
                            onUserClick = { /* No user navigation in chat bubbles */ }
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
                    if (msgType == "m.image" || msgType == "m.video" || msgType == "m.audio" || msgType == "m.file") {
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
                                onDelete = { /* No delete in chat bubbles */ },
                                onUserClick = { /* No user navigation in chat bubbles */ }
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
                        if (msgType == "m.image" || msgType == "m.video" || msgType == "m.audio" || msgType == "m.file") {
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
