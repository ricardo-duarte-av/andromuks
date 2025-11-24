package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
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
import kotlin.math.min
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.navigateToUserInfo
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.TypingNotificationArea
import net.vrkknn.andromuks.BuildConfig


/** Floating member list for mentions */
@Composable
private fun ThreadMentionMemberList(
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
        tonalElevation = 8.dp  // Use tonalElevation for dark mode visibility
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadViewerScreen(
    roomId: String,
    threadRootEventId: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences =
        remember(context) {
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        }
    val authToken =
        remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val myUserId = appViewModel.currentUserId
    val homeserverUrl = appViewModel.homeserverUrl
    
    // Get thread messages (thread root + all messages with relatesTo == threadRootEventId)
    // React to timeline update counter so timeline changes trigger recomposition
    val threadMessages = remember(appViewModel.timelineUpdateCounter) {
        appViewModel.getThreadMessages(roomId, threadRootEventId)
    }
    
    // Get the thread root event
    val threadRootEvent = threadMessages.firstOrNull { it.eventId == threadRootEventId }
    
    // Get room name for header
    val roomItem = appViewModel.getRoomById(roomId)
    val roomName = roomItem?.name ?: "Thread"
    
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "ThreadViewerScreen: Thread root: $threadRootEventId, messages count: ${threadMessages.size}"
    )

    // Delete state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Emoji selection state
    var showEmojiSelection by remember { mutableStateOf(false) }
    var reactingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Mention state
    var showMentionList by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    
    // Text input state (moved here to be accessible by mention handler)
    var draft by remember { mutableStateOf("") }
    var lastTypingTime by remember { mutableStateOf(0L) }
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

    // Get member map that observes memberUpdateCounter for TimelineEventItem profile updates
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId)
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

    // Create timeline items with date dividers
    val timelineItems =
        remember(threadMessages) {
            val items = mutableListOf<TimelineItem>()
            var lastDate: String? = null

            for (event in threadMessages) {
                val eventDate = formatDate(event.timestamp)

                // Add date divider if this is a new date
                if (lastDate == null || eventDate != lastDate) {
                    items.add(TimelineItem.DateDivider(eventDate))
                    lastDate = eventDate
                }

                // Add the event
                items.add(TimelineItem.Event(event))
            }
            items
        }

    // List state
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on initial load
    LaunchedEffect(timelineItems.size) {
        if (timelineItems.isNotEmpty()) {
            listState.scrollToItem(timelineItems.lastIndex)
        }
    }

    // OPPORTUNISTIC PROFILE LOADING: Only request profiles when actually needed for rendering
    // This prevents loading 15,000+ profiles upfront for large rooms
    LaunchedEffect(threadMessages, roomId) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "ThreadViewerScreen: Using opportunistic profile loading for $roomId (no bulk loading)"
        )
        
        // Only request profiles for users that are actually visible in the thread
        // This dramatically reduces memory usage for large rooms
        if (threadMessages.isNotEmpty()) {
            val visibleUsers = threadMessages.take(30) // Only first 30 events for thread
                .map { it.sender }
                .distinct()
                .filter { it != appViewModel.currentUserId }
            
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "ThreadViewerScreen: Requesting profiles on-demand for ${visibleUsers.size} visible users (instead of all ${threadMessages.size} events)"
            )
            
            // Request profiles one by one as needed
            visibleUsers.forEach { userId ->
                appViewModel.requestUserProfileOnDemand(userId, roomId)
            }
        }
    }

    // Handle Android back key
    BackHandler { navController.popBackStack() }

    // IME padding
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (imeBottom > 0.dp) imeBottom else navBarBottom

    AndromuksTheme {
        Surface {
            Box(modifier = modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .then(
                                if (showDeleteDialog) {
                                    Modifier.blur(10.dp)
                                } else {
                                    Modifier
                                }
                            )
                ) {
                    // 1. Thread Header
                    ThreadHeader(
                        roomName = roomName,
                        threadRootEvent = threadRootEvent,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        userProfileCache = appViewModel.getMemberMap(roomId),
                        onBackClick = { navController.popBackStack() }
                    )

                    // 2. Thread Timeline
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 8.dp
                            )
                    ) {
                        itemsIndexed(timelineItems) { index, item ->
                            when (item) {
                                is TimelineItem.DateDivider -> {
                                    DateDivider(item.date)
                                }
                                is TimelineItem.Event -> {
                                    val event = item.event
                                    val isMine = myUserId != null && event.sender == myUserId

                                    // Check if this is a consecutive message from the same sender
                                    var previousEvent: TimelineEvent? = null
                                    var prevIndex = index - 1
                                    while (prevIndex >= 0 && previousEvent == null) {
                                        when (val prevItem = timelineItems[prevIndex]) {
                                            is TimelineItem.Event ->
                                                previousEvent = prevItem.event
                                            is TimelineItem.DateDivider ->
                                                break
                                        }
                                        prevIndex--
                                    }

                                    val hasPerMessageProfile =
                                        event.content?.has("com.beeper.per_message_profile") ==
                                            true ||
                                            event.decrypted?.has(
                                                "com.beeper.per_message_profile"
                                            ) == true

                                    val isConsecutive =
                                        !hasPerMessageProfile &&
                                            previousEvent?.sender == event.sender

                                    TimelineEventItem(
                                        event = event,
                                        timelineEvents = threadMessages,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        userProfileCache = memberMap,
                                        isMine = isMine,
                                        myUserId = myUserId,
                                        isConsecutive = isConsecutive,
                                        appViewModel = appViewModel,
                                        onScrollToMessage = { eventId ->
                                            val index =
                                                threadMessages.indexOfFirst {
                                                    it.eventId == eventId
                                                }
                                            if (index >= 0) {
                                                coroutineScope.launch {
                                                    listState.animateScrollToItem(index)
                                                }
                                            }
                                        },
                                        onReply = { }, // No-op in thread viewer - all replies are thread replies
                                        onReact = { event ->
                                            reactingToEvent = event
                                            showEmojiSelection = true
                                        },
                                        onEdit = { }, // TODO: Implement edit in threads
                                        onDelete = { event ->
                                            deletingEvent = event
                                            showDeleteDialog = true
                                        },
                                        onUserClick = { userId ->
                                            navController.navigateToUserInfo(userId, roomId)
                                        },
                                        onRoomLinkClick = { roomLink ->
                                            // Handle room links same as main timeline
                                            val existingRoom = if (roomLink.roomIdOrAlias.startsWith("!")) {
                                                appViewModel.getRoomById(roomLink.roomIdOrAlias)
                                            } else {
                                                null
                                            }
                                            
                                            if (existingRoom != null) {
                                                val encodedRoomId = java.net.URLEncoder.encode(roomLink.roomIdOrAlias, "UTF-8")
                                                navController.navigate("room_timeline/$encodedRoomId")
                                            }
                                        },
                                        onThreadClick = { } // No nested threads
                                    )
                                }
                            }
                        }
                    }
                    
                    // 3. Typing notification area
                    TypingNotificationArea(
                        typingUsers = appViewModel.typingUsers,
                        roomId = roomId,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        userProfileCache = appViewModel.getMemberMap(roomId)
                    )

                    // 4. Text box (always sends thread replies)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier =
                            Modifier.fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                    ) {

                        // PERFORMANCE: Typing detection with debouncing - UI level rate limiting removed 
                        // since AppViewModel.sendTyping() now handles rate limiting internally (3 seconds)
                        LaunchedEffect(draft) {
                            if (draft.isNotBlank()) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastTypingTime > 3000) { // Reduced frequency: every 3 seconds
                                    appViewModel.sendTyping(roomId)
                                    lastTypingTime = currentTime
                                }
                            }
                        }

                        Row(
                            modifier =
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pill-shaped text input
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column {
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
                                        placeholder = { Text("Reply in thread...") },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Sentences,
                                            keyboardType = KeyboardType.Text,
                                            autoCorrect = true,
                                            imeAction = ImeAction.Send
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onSend = {
                                                if (draft.isNotBlank()) {
                                                    // Send thread reply
                                                    val lastMessage = threadMessages.lastOrNull()
                                                    appViewModel.sendThreadReply(
                                                        roomId = roomId,
                                                        text = draft,
                                                        threadRootEventId = threadRootEventId,
                                                        fallbackReplyToEventId = lastMessage?.eventId
                                                    )
                                                    draft = ""
                                                }
                                            }
                                        ),
                                        visualTransformation = mentionTransformation,
                                        colors =
                                            androidx.compose.material3.TextFieldDefaults.colors(
                                                focusedIndicatorColor =
                                                    androidx.compose.ui.graphics.Color.Transparent,
                                                unfocusedIndicatorColor =
                                                    androidx.compose.ui.graphics.Color.Transparent,
                                                disabledIndicatorColor =
                                                    androidx.compose.ui.graphics.Color.Transparent
                                            )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            // Send button
                            val isSending = appViewModel.pendingSendCount > 0
                            val infiniteTransition = rememberInfiniteTransition(label = "send_rotation")
                            val rotation by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = if (isSending) 360f else 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "rotation"
                            )
                            
                            Button(
                                onClick = {
                                    if (draft.isNotBlank()) {
                                        // Send thread reply
                                        val lastMessage = threadMessages.lastOrNull()
                                        appViewModel.sendThreadReply(
                                            roomId = roomId,
                                            text = draft,
                                            threadRootEventId = threadRootEventId,
                                            fallbackReplyToEventId = lastMessage?.eventId
                                        )
                                        draft = ""
                                    }
                                },
                                enabled = draft.isNotBlank(),
                                shape = CircleShape,
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                modifier = Modifier.size(56.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                @Suppress("DEPRECATION")
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = "Send",
                                    tint =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = if (isSending) Modifier.rotate(rotation) else Modifier
                                )
                            }
                        }
                    }
                }
                
                // Floating member list for mentions
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
                    ) {
                        ThreadMentionMemberList(
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
                            authToken = authToken,
                            modifier = Modifier.zIndex(10f)
                        )
                    }
                }
                
                // Delete confirmation dialog
                if (showDeleteDialog && deletingEvent != null) {
                    DeleteMessageDialog(
                        onDismiss = {
                            showDeleteDialog = false
                            deletingEvent = null
                        },
                        onConfirm = { reason ->
                            appViewModel.sendDelete(roomId, deletingEvent!!, reason)
                            showDeleteDialog = false
                            deletingEvent = null
                        }
                    )
                }
                
                // Emoji selection dialog for reactions
                if (showEmojiSelection && reactingToEvent != null) {
                    EmojiSelectionDialog(
                        recentEmojis = appViewModel.recentEmojis,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onEmojiSelected = { emoji ->
                            appViewModel.sendReaction(roomId, reactingToEvent!!.eventId, emoji)
                            showEmojiSelection = false
                            reactingToEvent = null
                        },
                        onDismiss = {
                            showEmojiSelection = false
                            reactingToEvent = null
                        },
                        customEmojiPacks = appViewModel.customEmojiPacks
                    )
                }
            }
        }
    }
}

@Composable
fun ThreadHeader(
    roomName: String,
    threadRootEvent: TimelineEvent?,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    onBackClick: () -> Unit = {}
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Top row with room name (no back button - use system back gesture/key)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thread in $roomName",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Show thread root preview
                    if (threadRootEvent != null) {
                        val senderProfile = userProfileCache[threadRootEvent.sender]
                        val senderName = senderProfile?.displayName ?: threadRootEvent.sender
                        val body = when {
                            threadRootEvent.type == "m.room.message" -> 
                                threadRootEvent.content?.optString("body", "")
                            threadRootEvent.type == "m.room.encrypted" && threadRootEvent.decryptedType == "m.room.message" -> 
                                threadRootEvent.decrypted?.optString("body", "")
                            else -> ""
                        } ?: ""
                        
                        Text(
                            text = "$senderName: ${body.take(50)}${if (body.length > 50) "..." else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

