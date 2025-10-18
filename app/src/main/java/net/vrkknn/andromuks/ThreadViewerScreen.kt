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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.TypingNotificationArea

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
    // React to updateCounter so reactions trigger recomposition
    val threadMessages = remember(appViewModel.updateCounter) {
        appViewModel.getThreadMessages(roomId, threadRootEventId)
    }
    
    // Get the thread root event
    val threadRootEvent = threadMessages.firstOrNull { it.eventId == threadRootEventId }
    
    // Get room name for header
    val roomItem = appViewModel.getRoomById(roomId)
    val roomName = roomItem?.name ?: "Thread"
    
    Log.d(
        "Andromuks",
        "ThreadViewerScreen: Thread root: $threadRootEventId, messages count: ${threadMessages.size}"
    )

    // Delete state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Emoji selection state
    var showEmojiSelection by remember { mutableStateOf(false) }
    var reactingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }

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
                                        userProfileCache = appViewModel.getMemberMap(roomId),
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
                                            navController.navigate(
                                                "user_info/${java.net.URLEncoder.encode(userId, "UTF-8")}"
                                            )
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
                        var draft by remember { mutableStateOf("") }
                        var lastTypingTime by remember { mutableStateOf(0L) }

                        // Typing detection with debouncing
                        LaunchedEffect(draft) {
                            if (draft.isNotBlank()) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastTypingTime > 1000) {
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
                                    // Text input field
                                    TextField(
                                        value = draft,
                                        onValueChange = { draft = it },
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
                        }
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

