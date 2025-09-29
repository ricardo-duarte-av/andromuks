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

    // Sort events so newer messages are at the bottom
    val sortedEvents = remember(timelineEvents) {
        timelineEvents.sortedBy { it.timestamp }
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
            val imageUrl = remember(mediaMessage.url) {
                MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
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
                        Log.d("Andromuks", "Image loaded successfully: $imageUrl")
                    },
                    onError = { state ->
                        Log.e("Andromuks", "Image load failed: $imageUrl")
                        Log.e("Andromuks", "Error state: $state")
                        if (state is coil.request.ErrorResult) {
                            Log.e("Andromuks", "Error result: ${state.throwable}")
                        }
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
                    text = formatTimestamp(event.timestamp),
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
                "m.room.message" -> {
                    val content = event.content
                    val body = content?.optString("body", "") ?: ""
                    val msgType = content?.optString("msgtype", "") ?: ""
                    
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
                                        Text(
                                            text = body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textColor
                                        )
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
                                    Text(
                                        text = body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
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
                    val decryptedType = event.decryptedType
                    val decrypted = event.decrypted
                    if (decryptedType == "m.room.message") {
                        val body = decrypted?.optString("body", "") ?: ""
                        val msgType = decrypted?.optString("msgtype", "") ?: ""
                        
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
                            val url = decrypted?.optString("url", "") ?: decrypted?.optJSONObject("file")?.optString("url", "") ?: ""
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
                                            Text(
                                                text = body,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = textColor
                                            )
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
                                        Text(
                                            text = body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textColor,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
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
                "m.room.member" -> {
                    val content = event.content
                    val membership = content?.optString("membership", "")
                    val displayname = content?.optString("displayname", "")
                    
                    Text(
                        text = when (membership) {
                            "join" -> "$displayname joined"
                            "leave" -> "$displayname left"
                            "invite" -> "$displayname was invited"
                            else -> "Membership change"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
