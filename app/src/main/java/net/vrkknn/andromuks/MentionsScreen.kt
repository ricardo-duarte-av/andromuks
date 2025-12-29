package net.vrkknn.andromuks

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateTopPadding
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import net.vrkknn.andromuks.AdaptiveMessageText
import net.vrkknn.andromuks.utils.ReplyPreview
import net.vrkknn.andromuks.utils.BubbleColors
import net.vrkknn.andromuks.utils.BubblePalette

// Data classes are defined in AppViewModel.kt

// formatDate is already defined in RoomTimelineScreen.kt

/** Format timestamp to time string (HH:mm) */
private fun formatTime(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(date)
}

/** Sealed class for mention timeline items (events and date dividers) */
sealed class MentionTimelineItem {
    abstract val stableKey: String
    
    data class Event(
        val mentionEvent: MentionEvent
    ) : MentionTimelineItem() {
        override val stableKey: String
            get() = mentionEvent.event.eventId
    }

    data class DateDivider(val date: String) : MentionTimelineItem() {
        override val stableKey: String get() = "date_$date"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun MentionsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val homeserverUrl = appViewModel.homeserverUrl
    val authToken = appViewModel.authToken
    val myUserId = appViewModel.currentUserId
    
    // Get mentions state from AppViewModel
    val mentionEvents = appViewModel.mentionEvents
    val isLoading = appViewModel.isMentionsLoading
    var isRefreshing by remember { mutableStateOf(false) }
    
    // Pull-to-refresh state
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            appViewModel.requestMentionsList()
        }
    )
    
    val listState = rememberLazyListState()
    
    // Load mentions when screen opens or when returning to this screen
    LaunchedEffect(Unit) {
        if (mentionEvents.isEmpty() && !isLoading) {
            appViewModel.requestMentionsList()
        }
    }
    
    // Reset refreshing state when loading completes
    LaunchedEffect(isLoading) {
        if (!isLoading && isRefreshing) {
            isRefreshing = false
        }
    }
    
    // Build timeline items with date dividers
    val timelineItems = remember(mentionEvents) {
        val items = mutableListOf<MentionTimelineItem>()
        var lastDate: String? = null
        
        val sortedEvents = mentionEvents.sortedByDescending { it.event.timestamp }
        
        for (mentionEvent in sortedEvents) {
            val date = net.vrkknn.andromuks.formatDate(mentionEvent.event.timestamp)
            
            if (lastDate == null || date != lastDate) {
                items.add(MentionTimelineItem.DateDivider(date))
                lastDate = date
            }
            
            items.add(MentionTimelineItem.Event(mentionEvent))
        }
        
        items
    }
    
    AndromuksTheme {
        Surface {
            Box(modifier = modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Mentions",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    // Content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pullRefresh(pullRefreshState)
                    ) {
                        if (isLoading && mentionEvents.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    ExpressiveLoadingIndicator(modifier = Modifier.size(96.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Loading mentions...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (timelineItems.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No mentions found",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 0.dp,
                                    top = 8.dp,
                                    bottom = 8.dp
                                )
                            ) {
                                items(
                                    items = timelineItems,
                                    key = { item -> item.stableKey }
                                ) { item ->
                                    when (item) {
                                        is MentionTimelineItem.DateDivider -> {
                                            DateDivider(item.date)
                                        }
                                        is MentionTimelineItem.Event -> {
                                            MentionItem(
                                                mentionEvent = item.mentionEvent,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                myUserId = myUserId,
                                                appViewModel = appViewModel,
                                                navController = navController,
                                                onMentionClick = { mentionEvent ->
                                                    // Set pending highlight event to scroll to this message
                                                    appViewModel.setPendingHighlightEvent(
                                                        mentionEvent.mentionEntry.roomId,
                                                        mentionEvent.mentionEntry.eventId
                                                    )
                                                    // Navigate to room timeline - it will scroll to the event
                                                    val encodedRoomId = java.net.URLEncoder.encode(mentionEvent.mentionEntry.roomId, "UTF-8")
                                                    navController.navigate("room_timeline/$encodedRoomId")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Pull-to-refresh indicator
                        PullRefreshIndicator(
                            refreshing = isRefreshing,
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
    
    // Handle back button
    BackHandler {
        navController.popBackStack()
    }
}

/** Component for rendering a single mention */
@Composable
fun MentionItem(
    mentionEvent: MentionEvent,
    homeserverUrl: String,
    authToken: String,
    myUserId: String?,
    appViewModel: AppViewModel,
    navController: NavController,
    onMentionClick: (MentionEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val event = mentionEvent.event
    val roomName = mentionEvent.roomName ?: mentionEvent.mentionEntry.roomId
    val roomAvatarUrl = mentionEvent.roomAvatarUrl
    val roomId = mentionEvent.mentionEntry.roomId
    
    // OPPORTUNISTIC PROFILE LOADING: Request profile on-demand if missing
    LaunchedEffect(event.sender, roomId) {
        val existingProfile = appViewModel.getUserProfile(event.sender, roomId)
        if (existingProfile == null || existingProfile.displayName.isNullOrBlank()) {
            appViewModel.requestUserProfileOnDemand(event.sender, roomId)
        }
    }
    
    // Get sender profile - prefers room-specific profile, falls back to global
    val senderProfile = appViewModel.getUserProfile(event.sender, roomId)
    val senderName = senderProfile?.displayName ?: event.sender.removePrefix("@").substringBefore(":")
    val senderAvatarUrl = senderProfile?.avatarUrl
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onMentionClick(mentionEvent) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Room context
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarImage(
                    mxcUrl = roomAvatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = roomName.take(1),
                    size = 24.dp,
                    userId = mentionEvent.mentionEntry.roomId,
                    displayName = roomName
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = roomName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Sender and message
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                AvatarImage(
                    mxcUrl = senderAvatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = senderName.take(1),
                    size = 32.dp,
                    userId = event.sender,
                    displayName = senderName
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatTime(event.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Render reply preview if this is a reply
                    val replyInfo = event.getReplyInfo()
                    val replyToEvent = mentionEvent.replyToEvent
                    if (replyInfo != null && replyToEvent != null) {
                        // Request profile for reply sender if missing
                        LaunchedEffect(replyToEvent.sender, roomId) {
                            val existingProfile = appViewModel.getUserProfile(replyToEvent.sender, roomId)
                            if (existingProfile == null || existingProfile.displayName.isNullOrBlank()) {
                                appViewModel.requestUserProfileOnDemand(replyToEvent.sender, roomId)
                            }
                        }
                        
                        // Get user profile cache for reply preview - make it reactive to member updates
                        // Ensure reply sender profile is included even if not in member map yet
                        val userProfileCache = remember(roomId, appViewModel.memberUpdateCounter, replyToEvent.sender) {
                            val memberMap = appViewModel.getMemberMap(roomId).toMutableMap()
                            // Ensure reply sender profile is included
                            val replySenderProfile = appViewModel.getUserProfile(replyToEvent.sender, roomId)
                            if (replySenderProfile != null) {
                                memberMap[replyToEvent.sender] = replySenderProfile
                            }
                            memberMap
                        }
                        
                        // Create simple bubble colors for reply preview
                        val colorScheme = MaterialTheme.colorScheme
                        val replyPreviewColors = remember(replyInfo.eventId, myUserId, colorScheme) {
                            val replySenderIsMine = replyInfo.sender == myUserId
                            BubblePalette.colors(
                                colorScheme = colorScheme,
                                isMine = replySenderIsMine,
                                mentionsMe = false,
                                isThreadMessage = false,
                                hasSpoiler = false,
                                isRedacted = false,
                                isEdited = false
                            )
                        }
                        
                        ReplyPreview(
                            replyInfo = replyInfo,
                            originalEvent = replyToEvent,
                            userProfileCache = userProfileCache,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            previewColors = replyPreviewColors,
                            modifier = Modifier.padding(bottom = 4.dp),
                            onOriginalMessageClick = {
                                // Set pending highlight event to scroll to the original message
                                appViewModel.setPendingHighlightEvent(roomId, replyInfo.eventId)
                                // Navigate to room timeline - it will scroll to the event
                                val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                navController.navigate("room_timeline/$encodedRoomId")
                            },
                            timelineEvents = emptyList(), // Not needed for mentions screen
                            onMatrixUserClick = { /* No-op for mentions */ },
                            appViewModel = appViewModel
                        )
                    }
                    
                    // Render message content
                    MessageContentPreview(
                        event = event,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        appViewModel = appViewModel,
                        roomId = mentionEvent.mentionEntry.roomId
                    )
                }
            }
        }
    }
}

/** Component to render message content preview for mentions */
@Composable
private fun MessageContentPreview(
    event: TimelineEvent,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel,
    roomId: String
) {
    // Get body text - prefer decrypted content for encrypted events
    val content = event.decrypted ?: event.content
    val format = content?.optString("format", "")
    val body = if (format == "org.matrix.custom.html") {
        content?.optString("formatted_body", "") ?: content?.optString("body", "") ?: ""
    } else {
        content?.optString("body", "") ?: ""
    }
    
    // Get user profile cache for this room
    val userProfileCache = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId)
    }
    
    // Render message content using AdaptiveMessageText
    AdaptiveMessageText(
        event = event,
        body = body,
        format = format,
        userProfileCache = userProfileCache,
        homeserverUrl = homeserverUrl,
        authToken = authToken,
        appViewModel = appViewModel,
        roomId = roomId,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
    )
}

