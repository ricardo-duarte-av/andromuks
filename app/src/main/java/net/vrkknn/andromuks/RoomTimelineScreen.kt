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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.paddingFrom
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeContent
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
    
    AndromuksTheme {
        Surface {
            Column(modifier = modifier.fillMaxSize()) {
                // Custom room header
                RoomHeader(
                    roomState = appViewModel.currentRoomState,
                    fallbackName = roomName,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = appViewModel.authToken
                )
                
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading timeline...")
                    }
                } else {
                    // Timeline list takes remaining height - this will compress when keyboard opens
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .imePadding(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 16.dp
                        )
                    ) {
                            items(sortedEvents) { event ->
                                val isMine = myUserId != null && event.sender == myUserId
                                TimelineEventItem(
                                    event = event,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    userProfileCache = appViewModel.getMemberMap(roomId),
                                    isMine = isMine
                                )
                            }
                        }
                    }
                }
                
                // Static message input at bottom
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
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
}

@Composable
private fun BottomInputBar() {
    var draft by remember { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // Inner rounded horizontal bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                singleLine = true
            )
        }
    }
}

@Composable
fun TimelineEventItem(
    event: TimelineEvent,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    isMine: Boolean
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
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = (displayName ?: event.sender).take(1),
            size = 48.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        } else {
            Spacer(modifier = Modifier.width(56.dp))
        }
        
        // Event content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
            ) {
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
            }
            
            when (event.type) {
                "m.room.message" -> {
                    val content = event.content
                    val body = content?.optString("body", "") ?: ""
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
                "m.room.encrypted" -> {
                    val decryptedType = event.decryptedType
                    val decrypted = event.decrypted
                    if (decryptedType == "m.room.message") {
                        val body = decrypted?.optString("body", "") ?: ""
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
