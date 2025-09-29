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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.inline.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import coil.request.CachePolicy
import net.vrkknn.andromuks.utils.BlurHashUtils
import net.vrkknn.andromuks.utils.MediaUtils
import net.vrkknn.andromuks.utils.AvatarUtils
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
    val allowedEventTypes = setOf(
        "m.room.message",
        "m.room.encrypted", 
        "m.room.member",
        "m.room.name",
        "m.room.topic",
        "m.room.avatar",
        "m.reaction"
    )
    
    // Sort events so newer messages are at the bottom, and filter unprocessed events if setting is disabled
    val sortedEvents = remember(timelineEvents, appViewModel.showUnprocessedEvents) {
        val filteredEvents = if (appViewModel.showUnprocessedEvents) {
            // Show all events when unprocessed events are enabled
            timelineEvents
        } else {
            // Only show allowed events when unprocessed events are disabled
            timelineEvents.filter { event ->
                allowedEventTypes.contains(event.type)
            }
        }
        filteredEvents.sortedBy { it.timestamp }
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
private fun TypingNotificationArea(
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
                        modifier = Modifier.size(12.dp) // Mini avatar size
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

@Composable
private fun MediaMessage(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    isEncrypted: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        // Media container with aspect ratio
        val aspectRatio = if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
            mediaMessage.info.width.toFloat() / mediaMessage.info.height.toFloat()
        } else {
            16f / 9f // Default aspect ratio
        }
        
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(0.8f) // Max 80% width
        ) {
            val calculatedHeight = if (aspectRatio > 0) {
                (maxWidth / aspectRatio).coerceAtMost(300.dp) // Max height of 300dp
            } else {
                200.dp // Default height
            }
            
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(calculatedHeight)
            ) {
            val context = LocalContext.current
            val imageUrl = remember(mediaMessage.url, isEncrypted) {
                val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
                // For encrypted media, add ?encrypted=true parameter
                if (isEncrypted) {
                    val encryptedUrl = "$httpUrl?encrypted=true"
                    Log.d("Andromuks", "MediaMessage: Added encrypted=true to URL: $encryptedUrl")
                    encryptedUrl
                } else {
                    httpUrl
                }
            }
            
            if (mediaMessage.msgType == "m.image") {
                // Debug logging
                Log.d("Andromuks", "MediaMessage: URL=$imageUrl, BlurHash=${mediaMessage.info.blurHash}, AuthToken=$authToken")
                
                val blurHashPainter = remember(mediaMessage.info.blurHash) {
                    mediaMessage.info.blurHash?.let { blurHash ->
                        Log.d("Andromuks", "Decoding BlurHash: $blurHash")
                        val bitmap = BlurHashUtils.decodeBlurHash(blurHash, 32, 32)
                        Log.d("Andromuks", "BlurHash decoded: ${bitmap != null}")
                        if (bitmap != null) {
                            val imageBitmap = bitmap.asImageBitmap()
                            Log.d("Andromuks", "BlurHash converted to ImageBitmap: ${imageBitmap.width}x${imageBitmap.height}")
                            Log.d("Andromuks", "BlurHash bitmap info: config=${bitmap.config}, hasAlpha=${bitmap.hasAlpha()}")
                            BitmapPainter(imageBitmap)
                        } else {
                            Log.w("Andromuks", "BlurHash decode failed, using fallback")
                            BitmapPainter(
                                BlurHashUtils.createPlaceholderBitmap(
                                    32, 32, 
                                    androidx.compose.ui.graphics.Color.Gray
                                )
                            )
                        }
                    } ?: run {
                        // Simple fallback placeholder without MaterialTheme
                        Log.d("Andromuks", "No BlurHash available, using simple fallback")
                        BitmapPainter(
                            BlurHashUtils.createPlaceholderBitmap(
                                32, 32, 
                                androidx.compose.ui.graphics.Color.Gray
                            )
                        )
                    }
                }
                
                Log.d("Andromuks", "BlurHash painter created: ${blurHashPainter != null}")
                
                Log.d("Andromuks", "AsyncImage: Starting image load for $imageUrl")
                
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .addHeader("Cookie", "gomuks_auth=$authToken")
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = mediaMessage.filename,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = blurHashPainter,
                    error = blurHashPainter, // Use BlurHash as error fallback too
                    onSuccess = { 
                        Log.d("Andromuks", "✅ Image loaded successfully: $imageUrl")
                    },
                    onError = { state ->
                        Log.e("Andromuks", "❌ Image load failed: $imageUrl")
                        Log.e("Andromuks", "Error state: $state")
                        if (state is coil.request.ErrorResult) {
                            Log.e("Andromuks", "Error result: ${state.throwable}")
                            Log.e("Andromuks", "Error message: ${state.throwable.message}")
                        }
                    },
                    onLoading = { state ->
                        Log.d("Andromuks", "⏳ Image loading: $imageUrl, state: $state")
                    }
                )
            } else {
                // Video placeholder
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "🎥",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = mediaMessage.filename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
                            Text(
                                text = "${mediaMessage.info.width}×${mediaMessage.info.height}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        }
        
        // Caption if different from filename
        if (!mediaMessage.caption.isNullOrBlank()) {
            Text(
                text = mediaMessage.caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ReplyPreview(
    replyInfo: ReplyInfo,
    originalEvent: TimelineEvent?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    modifier: Modifier = Modifier,
    onOriginalMessageClick: () -> Unit = {}
) {
    val originalSender = originalEvent?.sender ?: replyInfo.sender
    val originalBody = originalEvent?.let { event ->
        when {
            event.type == "m.room.message" -> event.content?.optString("body", "")
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optString("body", "")
            else -> null
        }
    } ?: "Message not found"
    
    val memberProfile = userProfileCache[originalSender]
    val senderName = memberProfile?.displayName ?: originalSender.substringAfterLast(":")
    
    // Outer container for the reply
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Reply indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                ) {}
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "Replying to $senderName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Nested bubble for original message (clickable)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOriginalMessageClick() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Sender name
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    // Original message content
                    Text(
                        text = originalBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 0.9
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionBadges(
    eventId: String,
    reactions: List<MessageReaction>,
    modifier: Modifier = Modifier
) {
    if (reactions.isNotEmpty()) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            reactions.forEach { reaction ->
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
                        Text(
                            text = reaction.emoji,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.8f
                            )
                        )
                        if (reaction.count > 1) {
                            Text(
                                text = reaction.count.toString(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.7f
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    return
                }
                "m.room.message" -> {
                    // Check if this is an edit event (m.replace relationship) - don't display edit events
                    val isEditEvent = event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    if (isEditEvent) {
                        return // Don't display edit events as separate timeline items
                    }
                    val content = event.content
                    val format = content?.optString("format", "")
                    val body = if (format == "org.matrix.custom.html") {
                        content?.optString("formatted_body", "") ?: ""
                    } else {
                        content?.optString("body", "") ?: ""
                    }
                    val msgType = content?.optString("msgtype", "") ?: ""
                    
                    // Check if this message has been redacted
                    val isRedacted = event.redactedBy != null
                    val redactionReason = if (isRedacted) {
                        // Find the redaction event to get the reason
                        timelineEvents.find { it.eventId == event.redactedBy }?.content?.optString("reason", "")
                    } else null
                    
                    // Check if this is an edit (m.replace relationship)
                    val isEdit = content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    val editContent = if (isEdit) {
                        content?.optJSONObject("m.new_content")
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
                        Log.d("Andromuks", "TimelineEventItem: Found media message - msgType=$msgType, body=$body")
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
                                        }
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
                            topEnd = 16.dp,
                            bottomEnd = 8.dp,
                            bottomStart = 16.dp
                        )
                    } else {
                        RoundedCornerShape(
                            topStart = 16.dp,
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
                                topEnd = 16.dp,
                                bottomEnd = 8.dp,
                                bottomStart = 16.dp
                            )
                        } else {
                            RoundedCornerShape(
                                topStart = 16.dp,
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
                                            }
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
                                            isEncrypted = false,
                                            modifier = Modifier
                                        )
                                        
                                        // Show redaction indicators for reply
                                        if (isRedacted) {
                                            Text(
                                                text = "Removed by ${displayName ?: event.sender}${redactionReason?.let { " for $it" } ?: ""}",
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
                                        isEncrypted = false,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                    
                                    // Show redaction indicators
                                    if (isRedacted) {
                                        Text(
                                            text = "Removed by ${displayName ?: event.sender}${redactionReason?.let { " for $it" } ?: ""}",
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
                    // Check if this is an edit event (m.replace relationship) - don't display edit events
                    val isEditEvent = event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    if (isEditEvent) {
                        return // Don't display edit events as separate timeline items
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
                        
                        // Check if this message has been redacted
                        val isRedacted = event.redactedBy != null
                        val redactionReason = if (isRedacted) {
                            // Find the redaction event to get the reason
                            timelineEvents.find { it.eventId == event.redactedBy }?.content?.optString("reason", "")
                        } else null
                        
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
                                            }
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
                                        topEnd = 16.dp,
                                        bottomEnd = 8.dp,
                                        bottomStart = 16.dp
                                    )
                                } else {
                                    RoundedCornerShape(
                                        topStart = 16.dp,
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
                                    topEnd = 16.dp,
                                    bottomEnd = 8.dp,
                                    bottomStart = 16.dp
                                )
                            } else {
                                RoundedCornerShape(
                                    topStart = 16.dp,
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
                                                }
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
                                                isEncrypted = true,
                                                modifier = Modifier
                                            )
                                            
                                            // Show redaction indicators for encrypted reply
                                            if (isRedacted) {
                                                Text(
                                                    text = "Removed by ${displayName ?: event.sender}${redactionReason?.let { " for $it" } ?: ""}",
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
                                            isEncrypted = true,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                        
                                        // Show redaction indicators for encrypted message
                                        if (isRedacted) {
                                            Text(
                                                text = "Removed by ${displayName ?: event.sender}${redactionReason?.let { " for $it" } ?: ""}",
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
private fun InlineReadReceiptAvatars(
    receipts: List<ReadReceipt>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel,
    messageSender: String
) {
    // Filter out receipts from the current user, from the message sender, and limit to 3 avatars
    val otherUsersReceipts = receipts
        .filter { it.userId != appViewModel.currentUserId && it.userId != messageSender }
        .distinctBy { it.userId }
        .take(3)
    
    if (otherUsersReceipts.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(-4.dp), // Overlap avatars slightly
            verticalAlignment = Alignment.CenterVertically
        ) {
            otherUsersReceipts.forEach { receipt ->
                val profile = userProfileCache[receipt.userId]
                val displayName = profile?.displayName
                val avatarUrl = profile?.avatarUrl
                
                AvatarImage(
                    mxcUrl = avatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = (displayName ?: receipt.userId.substringAfter("@").substringBefore(":")).take(1),
                    size = 16.dp,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        )
                )
            }
            
            // Show count if there are more than 3 receipts
            if (receipts.size > 3) {
                Text(
                    text = "+${receipts.size - 3}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7f
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(date)
}

@Composable
private fun RoomHeader(
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

@Composable
private fun SystemEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel? = null,
    roomId: String
) {
    val content = event.content
    val eventType = event.type
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side - centered content
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Small avatar for the actor
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = displayName,
                size = 20.dp
            )
        
        // Narrator text
        when (eventType) {
            "m.room.member" -> {
                val membership = content?.optString("membership", "")
                val targetDisplayName = content?.optString("displayname", "")
                val reason = content?.optString("reason", "")
                
                when (membership) {
                    "join" -> {
                        // Check if this is a profile change vs actual join
                        val unsigned = event.unsigned
                        val prevContent = unsigned?.optJSONObject("prev_content")
                        val prevDisplayName = prevContent?.optString("displayname")
                        val prevAvatarUrl = prevContent?.optString("avatar_url")
                        val currentDisplayName = content?.optString("displayname")
                        val currentAvatarUrl = content?.optString("avatar_url")
                        
                        val isProfileChange = prevContent != null && 
                            (prevDisplayName != currentDisplayName || prevAvatarUrl != currentAvatarUrl)
                        
                        if (isProfileChange) {
                            // Profile change
                            val changes = mutableListOf<String>()
                            if (prevDisplayName != currentDisplayName) {
                                changes.add("name to \"$currentDisplayName\"")
                            }
                            if (prevAvatarUrl != currentAvatarUrl) {
                                changes.add("avatar")
                            }
                            
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" changed their ${changes.joinToString(" and ")}")
                                }
                            )
                        } else {
                            // Actual join
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" joined the room")
                                    if (!reason.isNullOrBlank()) {
                                        append(" (")
                                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                            append(reason)
                                        }
                                        append(")")
                                    }
                                }
                            )
                        }
                    }
                    "leave" -> {
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" left the room")
                                if (!reason.isNullOrBlank()) {
                                    append(" (")
                                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                        append(reason)
                                    }
                                    append(")")
                                }
                            }
                        )
                    }
                    "invite" -> {
                        // For invites, we need to show who invited whom
                        val invitedUserId = event.stateKey ?: ""
                        
                        // Get profile info from AppViewModel's member cache
                        val invitedProfile = appViewModel?.getMemberMap(roomId)?.get(invitedUserId)
                        val invitedDisplayName = invitedProfile?.displayName ?: invitedUserId.substringAfter("@").substringBefore(":")
                        val invitedAvatarUrl = invitedProfile?.avatarUrl
                        
                        // Request profile for invited user if we don't have their info
                        if (invitedProfile == null && invitedUserId.isNotBlank() && appViewModel != null) {
                            appViewModel.requestUserProfile(invitedUserId)
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                        append(displayName)
                                    }
                                    append(" invited ")
                                }
                            )
                            
                            // Small avatar for the invited user
                            AvatarImage(
                                mxcUrl = invitedAvatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                fallbackText = invitedDisplayName,
                                size = 16.dp
                            )
                            
                            NarratorText(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                        append(invitedDisplayName)
                                    }
                                    if (!reason.isNullOrBlank()) {
                                        append(" for ")
                                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                            append(reason)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    else -> {
                        NarratorText(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                                    append(displayName)
                                }
                                append(" changed membership")
                            }
                        )
                    }
                }
            }
            "m.room.name" -> {
                val newName = content?.optString("name", "")
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room name to ")
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                            append("\"$newName\"")
                        }
                    }
                )
            }
            "m.room.topic" -> {
                val newTopic = content?.optString("topic", "")
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room topic to ")
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                            append("\"$newTopic\"")
                        }
                    }
                )
            }
            "m.room.avatar" -> {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" changed the room avatar")
                    }
                )
            }
            else -> {
                NarratorText(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                            append(displayName)
                        }
                        append(" performed an action")
                    }
                )
            }
        }
        }
        
        // Time on the right
        Text(
            text = formatTimestamp(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
    }
}

@Composable
private fun NarratorText(
    text: AnnotatedString,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic,
        modifier = modifier
    )
}

@Composable
private fun MessageTextWithMentions(
    text: String,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    modifier: Modifier = Modifier
) {
    // Regex to find Matrix user IDs (@user:server.com)
    val matrixIdRegex = Regex("@([^:]+):([^\\s]+)")
    val matches = matrixIdRegex.findAll(text)
    
    if (matches.none()) {
        // No Matrix IDs found, render as plain text
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
    } else {
        // Build annotated string with mentions
        val annotatedText = buildAnnotatedString {
            var lastIndex = 0
            
            matches.forEach { match ->
                val fullMatch = match.value
                val userId = fullMatch
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                
                // Add text before the mention
                if (startIndex > lastIndex) {
                    append(text.substring(lastIndex, startIndex))
                }
                
                // Get profile for the mentioned user
                val profile = userProfileCache[userId] ?: appViewModel?.getMemberMap(roomId)?.get(userId)
                val displayName = profile?.displayName
                
                // Request profile if not found
                if (profile == null && appViewModel != null) {
                    appViewModel.requestUserProfile(userId)
                }
                
                // Create mention pill
                pushStyle(
                    SpanStyle(
                        background = MaterialTheme.colorScheme.primaryContainer,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                )
                append(displayName ?: userId.substringAfter("@").substringBefore(":"))
                pop()
                
                lastIndex = endIndex
            }
            
            // Add remaining text
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
        
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
    }
}

@Composable
private fun SmartMessageText(
    body: String,
    format: String?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    isEncrypted: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (format == "org.matrix.custom.html") {
        // Use rich text renderer for HTML messages
        RichMessageText(
            formattedBody = body,
            userProfileCache = userProfileCache,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            appViewModel = appViewModel,
            roomId = roomId,
            isEncrypted = isEncrypted,
            modifier = modifier
        )
    } else {
        // Use plain text renderer with mention detection for regular messages
        MessageTextWithMentions(
            text = body,
            userProfileCache = userProfileCache,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            appViewModel = appViewModel,
            roomId = roomId,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun RichMessageText(
    formattedBody: String,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    isEncrypted: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Get MaterialTheme colors outside of remember block
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    
    // Parse HTML and convert to AnnotatedString with inline content
    val (annotatedText: AnnotatedString, inlineContent: Map<String, InlineTextContent>) = remember(formattedBody, userProfileCache, primaryContainer, onPrimaryContainer, homeserverUrl, authToken, isEncrypted) {
        val inlineContentMap = mutableMapOf<String, InlineTextContent>()
        var imageCounter = 0
        
        val annotatedString = buildAnnotatedString {
            var currentIndex = 0
            val text = formattedBody
            
            // Regex to find Matrix user links: <a href="https://matrix.to/#/@user:server.com">DisplayName</a>
            val matrixUserLinkRegex = Regex("""<a\s+href="https://matrix\.to/#/([^"]+)"[^>]*>([^<]+)</a>""")
            // Regex to find img tags: <img src="..." alt="..." ...>
            val imgTagRegex = Regex("""<img[^>]*src="([^"]*)"[^>]*(?:alt="([^"]*)")?[^>]*>""")
            
            // Combine all matches and sort by position
            val allMatches = mutableListOf<Pair<Int, MatchResult>>()
            
            matrixUserLinkRegex.findAll(text).forEach { match ->
                allMatches.add(Pair(0, match)) // 0 = user link
            }
            
            imgTagRegex.findAll(text).forEach { match ->
                allMatches.add(Pair(1, match)) // 1 = img tag
            }
            
            // Sort by position in text
            allMatches.sortBy { it.second.range.first }
            
            allMatches.forEach { (type: Int, match: MatchResult) ->
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                
                // Add text before the match
                if (startIndex > currentIndex) {
                    append(text.substring(currentIndex, startIndex))
                }
                
                when (type) {
                    0 -> {
                        // Handle Matrix user links
                        val matrixId = match.groupValues[1] // The @user:server.com part
                        val displayName = match.groupValues[2] // The display name
                        
                        // Decode URL-encoded Matrix ID
                        val decodedMatrixId = matrixId.replace("%40", "@").replace("%3A", ":")
                        
                        // Get profile for the mentioned user
                        val profile = userProfileCache[decodedMatrixId] ?: appViewModel?.getMemberMap(roomId)?.get(decodedMatrixId)
                        
                        // Request profile if not found
                        if (profile == null && appViewModel != null) {
                            appViewModel.requestUserProfile(decodedMatrixId)
                        }
                        
                        // Create mention pill with the display name from the HTML
                        pushStyle(
                            SpanStyle(
                                background = primaryContainer,
                                color = onPrimaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        append(displayName)
                        pop()
                    }
                    1 -> {
                        // Handle img tags
                        val src = match.groupValues[1]
                        val alt = match.groupValues.getOrNull(2) ?: "image"
                        
                        // Check if it's an mxc:// URL
                        if (src.startsWith("mxc://")) {
                            // Create inline content for the image
                            val placeholderId = "image_${imageCounter++}"
                            
                            // Convert MXC URL to HTTP URL using the same pattern as MediaMessage
                            val httpUrl = MediaUtils.mxcToHttpUrl(src, homeserverUrl)
                            
                            // For encrypted media, add ?encrypted=true parameter (same as MediaMessage)
                            val finalUrl = if (isEncrypted && httpUrl != null) {
                                val encryptedUrl = "$httpUrl?encrypted=true"
                                android.util.Log.d("Andromuks", "RichMessageText: Added encrypted=true to URL: $encryptedUrl")
                                encryptedUrl
                            } else {
                                httpUrl
                            }
                            
                            if (finalUrl != null) {
                                inlineContentMap[placeholderId] = InlineTextContent(
                                    placeholder = Placeholder(
                                        width = 32.sp,
                                        height = 32.sp,
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                                    )
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(finalUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = alt,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                
                                // Insert placeholder character
                                append("\uFFFC") // Object replacement character
                                pushStringAnnotation(
                                    tag = "image",
                                    annotation = placeholderId
                                )
                                pop()
                            } else {
                                // Fallback to text if URL conversion fails
                                append("[$alt]")
                            }
                        } else {
                            // For other images, just show placeholder
                            append("[image]")
                        }
                    }
                }
                
                currentIndex = endIndex
            }
            
            // Add remaining text
            if (currentIndex < text.length) {
                append(text.substring(currentIndex))
            }
        }
        
        Pair(annotatedString, inlineContentMap)
    }
    
    Text(
        text = annotatedText,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

