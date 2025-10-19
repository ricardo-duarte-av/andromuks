package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.EmoteEventNarrator
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.InlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.AnimatedInlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.MediaPreviewDialog
import net.vrkknn.andromuks.utils.MediaUploadUtils
import net.vrkknn.andromuks.utils.MessageBubbleWithMenu
import net.vrkknn.andromuks.utils.ReactionBadges
import net.vrkknn.andromuks.utils.ReplyPreview
import net.vrkknn.andromuks.utils.ReplyPreviewInput
import net.vrkknn.andromuks.utils.RoomJoinerScreen
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.SmartMessageText
import net.vrkknn.andromuks.utils.StickerMessage
import net.vrkknn.andromuks.utils.SystemEventNarrator
import net.vrkknn.andromuks.utils.TypingNotificationArea
import net.vrkknn.andromuks.utils.UploadingDialog
import net.vrkknn.andromuks.utils.VideoUploadUtils
import net.vrkknn.andromuks.utils.extractStickerFromEvent
import net.vrkknn.andromuks.utils.supportsHtmlRendering

/** Sealed class for timeline items (events and date dividers) */
sealed class TimelineItem {
    data class Event(val event: TimelineEvent) : TimelineItem()

    data class DateDivider(val date: String) : TimelineItem()
}

/** Format timestamp to date string (dd / MM / yyyy) */
internal fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("dd / MM / yyyy", Locale.getDefault())
    return formatter.format(date)
}

/** Check if a message mentions a specific user */
private fun isMentioningUser(event: TimelineEvent, userId: String?): Boolean {
    if (userId == null) return false
    
    // Check in content (for unencrypted messages)
    val contentMentions = event.content?.optJSONObject("m.mentions")
    if (contentMentions != null) {
        val userIds = contentMentions.optJSONArray("user_ids")
        if (userIds != null) {
            for (i in 0 until userIds.length()) {
                if (userIds.optString(i) == userId) {
                    Log.d("Andromuks", "isMentioningUser: Found mention of $userId in event ${event.eventId}")
                    return true
                }
            }
        }
    }
    
    // Check in decrypted content (for encrypted messages)
    val decryptedMentions = event.decrypted?.optJSONObject("m.mentions")
    if (decryptedMentions != null) {
        val userIds = decryptedMentions.optJSONArray("user_ids")
        if (userIds != null) {
            for (i in 0 until userIds.length()) {
                if (userIds.optString(i) == userId) {
                    Log.d("Andromuks", "isMentioningUser: Found mention of $userId in encrypted event ${event.eventId}")
                    return true
                }
            }
        }
    }
    
    return false
}

/** Floating member list for mentions */
@Composable
fun MentionMemberList(
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

/** Date divider component for timeline events */
@Composable
fun DateDivider(date: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Spacer(
            modifier =
                Modifier.weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        )

        Text(
            text = date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        androidx.compose.foundation.layout.Spacer(
            modifier =
                Modifier.weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        )
    }
}

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
    val sharedPreferences =
        remember(context) {
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        }
    val authToken =
        remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val myUserId = appViewModel.currentUserId
    val homeserverUrl = appViewModel.homeserverUrl
    Log.d("Andromuks", "RoomTimelineScreen: appViewModel instance: $appViewModel")
    val timelineEvents = appViewModel.timelineEvents
    val isLoading = appViewModel.isTimelineLoading

    // Get the room item to check if it's a DM and get proper display name
    val roomItem = appViewModel.getRoomById(roomId)
    val isDirectMessage = roomItem?.isDirectMessage ?: false

    // For DM rooms, calculate the display name from member profiles
    val displayRoomName =
        if (isDirectMessage && roomItem != null) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val otherParticipant = memberMap.keys.find { it != myUserId }
            val otherProfile = otherParticipant?.let { memberMap[it] }
            otherProfile?.displayName ?: otherParticipant ?: roomName
        } else {
            roomName
        }

    // For DM rooms, get the avatar from the other participant
    val displayAvatarUrl =
        if (isDirectMessage && roomItem != null) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val otherParticipant = memberMap.keys.find { it != myUserId }
            val otherProfile = otherParticipant?.let { memberMap[it] }
            otherProfile?.avatarUrl
        } else {
            appViewModel.currentRoomState?.avatarUrl
        }

    Log.d(
        "Andromuks",
        "RoomTimelineScreen: Timeline events count: ${timelineEvents.size}, isLoading: $isLoading"
    )

    // Reply state
    var replyingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Edit state
    var editingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Delete state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Emoji selection state
    var showEmojiSelection by remember { mutableStateOf(false) }
    var reactingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Media picker state
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMediaIsVideo by remember { mutableStateOf(false) }
    var showMediaPreview by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    
    // Room joiner state
    var showRoomJoiner by remember { mutableStateOf(false) }
    var roomLinkToJoin by remember { mutableStateOf<RoomLink?>(null) }
    
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
    
    // Pre-fill draft when editing starts
    LaunchedEffect(editingEvent) {
        if (editingEvent != null) {
            val content = editingEvent!!.content ?: editingEvent!!.decrypted
            val msgType = content?.optString("msgtype", "")
            
            // For emote messages, use edit_source which includes the /me prefix
            val body = if (msgType == "m.emote") {
                val localContent = editingEvent!!.localContent
                val editSource = localContent?.optString("edit_source")?.takeIf { it.isNotBlank() }
                editSource ?: content?.optString("body", "") ?: ""
            } else {
                content?.optString("body", "") ?: ""
            }
            draft = body
            
            // Hide mention list when editing
            showMentionList = false
        }
    }
    
    // Hide mention list when replying starts
    LaunchedEffect(replyingToEvent) {
        if (replyingToEvent != null) {
            showMentionList = false
        }
    }

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

    // Media picker launcher - accepts both images and videos
    val mediaPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
            uri: Uri? ->
            uri?.let {
                selectedMediaUri = it
                // Detect if this is a video or image
                val mimeType = context.contentResolver.getType(it)
                selectedMediaIsVideo = mimeType?.startsWith("video/") == true
                showMediaPreview = true
            }
        }

    // Build user profile cache from m.room.member events
    val userProfileCache =
        remember(timelineEvents) {
            val map =
                mutableMapOf<String, Pair<String?, String?>>() // userId -> (displayName, avatarUrl)
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

    // Get current room members for mention list (exclude current user and filter out invalid entries)
    val roomMembers = remember(roomId, appViewModel.updateCounter) {
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

    // Define allowed event types (whitelist approach)
    // Note: m.room.redaction events are explicitly excluded as they should not appear in timeline
    val allowedEventTypes =
        setOf(
            "m.room.message",
            "m.room.encrypted",
            "m.room.member",
            "m.room.name",
            "m.room.topic",
            "m.room.avatar",
            "m.room.pinned_events",
            "m.reaction",
            "m.sticker"
            // m.room.redaction is intentionally excluded - redaction events should not appear in
            // timeline
        )

    // Sort events so newer messages are at the bottom, and filter unprocessed events if setting is
    // disabled
    val sortedEvents =
        remember(timelineEvents, appViewModel.showUnprocessedEvents, appViewModel.updateCounter) {
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Processing ${timelineEvents.size} timeline events, showUnprocessedEvents: ${appViewModel.showUnprocessedEvents}"
            )

            // Debug: Log event types in timeline
            val eventTypes = timelineEvents.groupBy { it.type }
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Event types in timeline: ${eventTypes.map { "${it.key}: ${it.value.size}" }.joinToString(", ")}"
            )

            val filteredEvents =
                if (appViewModel.showUnprocessedEvents) {
                    // Show all events when unprocessed events are enabled, but always exclude
                    // redaction events
                    val filtered =
                        timelineEvents.filter { event -> event.type != "m.room.redaction" }
                    Log.d(
                        "Andromuks",
                        "RoomTimelineScreen: After redaction filtering: ${filtered.size} events"
                    )
                    filtered
                } else {
                    // Only show allowed events when unprocessed events are disabled
                    val filtered =
                        timelineEvents.filter { event -> allowedEventTypes.contains(event.type) }
                    Log.d(
                        "Andromuks",
                        "RoomTimelineScreen: After type filtering: ${filtered.size} events"
                    )
                    filtered
                }

            // Filter out superseded events (original messages that have been edited)
            // Edit events create new timeline entries, so we hide the original messages they
            // replace
            val eventsWithoutSuperseded =
                filteredEvents.filter { event ->
                    if (event.type == "m.room.message") {
                        // Check if this message has been superseded by an edit
                        val hasBeenEdited =
                            filteredEvents.any { otherEvent ->
                                otherEvent.type == "m.room.message" &&
                                    otherEvent.content
                                        ?.optJSONObject("m.relates_to")
                                        ?.optString("rel_type") == "m.replace" &&
                                    otherEvent.content
                                        ?.optJSONObject("m.relates_to")
                                        ?.optString("event_id") == event.eventId
                            }
                        if (hasBeenEdited) {
                            Log.d(
                                "Andromuks",
                                "RoomTimelineScreen: Filtering out edited event: ${event.eventId}"
                            )
                        }
                        !hasBeenEdited // Keep the event if it hasn't been edited
                    } else {
                        true // Keep non-message events
                    }
                }

            Log.d(
                "Andromuks",
                "RoomTimelineScreen: After edit filtering: ${eventsWithoutSuperseded.size} events"
            )

            val sorted = eventsWithoutSuperseded.sortedBy { it.timestamp }
            Log.d("Andromuks", "RoomTimelineScreen: Final sorted events: ${sorted.size} events")

            sorted
        }

    // Create timeline items with date dividers
    val timelineItems =
        remember(sortedEvents, appViewModel.updateCounter) {
            val items = mutableListOf<TimelineItem>()
            var lastDate: String? = null

            for (event in sortedEvents) {
                // Format date inline to avoid @Composable context issue
                val date = Date(event.timestamp)
                val formatter = SimpleDateFormat("dd / MM / yyyy", Locale.getDefault())
                val eventDate = formatter.format(date)

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

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()

    // Track if user is "attached" to the bottom (sticky scroll)
    var isAttachedToBottom by remember { mutableStateOf(true) }

    // Track if this is the first load (to avoid animation on initial room open)
    var isInitialLoad by remember { mutableStateOf(true) }

    // Track loading more state
    var isLoadingMore by remember { mutableStateOf(false) }
    var previousItemCount by remember { mutableStateOf(timelineItems.size) }
    var hasLoadedInitialBatch by remember { mutableStateOf(false) }
    var hasInitialSnapCompleted by remember { mutableStateOf(false) }
    var lastKnownTimelineEventId by remember { mutableStateOf<String?>(null) }
    var hasCompletedInitialLayout by remember { mutableStateOf(false) }
    var firstVisibleItemIndexBeforeLoad by remember { mutableStateOf(0) }
    var firstVisibleItemScrollOffsetBeforeLoad by remember { mutableStateOf(0) }

    // Monitor scroll position to detect if user is at bottom or has detached
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size) {
        if (sortedEvents.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
            // Check if Load More button is present (affects index calculations)
            val hasLoadMoreButton = appViewModel.hasMoreMessages
            val buttonOffset = if (hasLoadMoreButton) 1 else 0

            // Check if we're at the very bottom (last item is visible)
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastTimelineItemIndex = timelineItems.lastIndex + buttonOffset
            val isAtBottom = lastVisibleIndex >= lastTimelineItemIndex - 1 // Within last item

            if (!hasCompletedInitialLayout) {
                hasCompletedInitialLayout = true
            }

            // Update attachment state based on current position
            if (isAtBottom && !isAttachedToBottom) {
                // User scrolled back to bottom, re-attach
                Log.d("Andromuks", "RoomTimelineScreen: User reached bottom, re-attaching")
                isAttachedToBottom = true
                if (!hasInitialSnapCompleted) {
                    hasInitialSnapCompleted = true
                }
            } else if (
                !isAtBottom && isAttachedToBottom && listState.firstVisibleItemIndex > buttonOffset
            ) {
                // User scrolled up from bottom, detach
                Log.d("Andromuks", "RoomTimelineScreen: User scrolled up, detaching from bottom")
                isAttachedToBottom = false
            }
        }
    }

    // Auto-scroll to bottom only when attached (initial load or new messages while at bottom)
    LaunchedEffect(timelineItems.size, isLoading) {
        Log.d(
            "Andromuks",
            "RoomTimelineScreen: LaunchedEffect - timelineItems.size: ${timelineItems.size}, isLoading: $isLoading, hasInitialSnapCompleted: $hasInitialSnapCompleted"
        )

        if (isLoading || timelineItems.isEmpty()) {
            return@LaunchedEffect
        }

        val hasLoadMoreButton = appViewModel.hasMoreMessages
        val buttonOffset = if (hasLoadMoreButton) 1 else 0
        val lastEventId = (timelineItems.lastOrNull() as? TimelineItem.Event)?.event?.eventId

        if (!hasInitialSnapCompleted) {
            coroutineScope.launch {
                listState.scrollToItem(timelineItems.lastIndex + buttonOffset)
                hasInitialSnapCompleted = true
                hasLoadedInitialBatch = true
                isAttachedToBottom = true
                previousItemCount = timelineItems.size
                lastKnownTimelineEventId = lastEventId
            }
            return@LaunchedEffect
        }

        val hasNewItems = previousItemCount < timelineItems.size

        if (isLoadingMore && hasNewItems) {
            // Calculate how many new items were added
            val newItemsCount = timelineItems.size - previousItemCount
            
            // Calculate the new index to maintain scroll position
            // The item that was at firstVisibleItemIndexBeforeLoad is now at
            // firstVisibleItemIndexBeforeLoad + newItemsCount
            val targetIndex = firstVisibleItemIndexBeforeLoad + newItemsCount
            
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Restoring scroll position after load - " +
                "previousCount: $previousItemCount, newCount: ${timelineItems.size}, " +
                "newItemsAdded: $newItemsCount, " +
                "oldIndex: $firstVisibleItemIndexBeforeLoad, newIndex: $targetIndex, " +
                "offset: $firstVisibleItemScrollOffsetBeforeLoad"
            )
            
            coroutineScope.launch {
                // Scroll to maintain the user's viewing position
                listState.scrollToItem(targetIndex, firstVisibleItemScrollOffsetBeforeLoad)
            }
            isLoadingMore = false
            previousItemCount = timelineItems.size
            lastKnownTimelineEventId = lastEventId
            return@LaunchedEffect
        }

        if (
            hasNewItems &&
                isAttachedToBottom &&
                lastEventId != null &&
                lastEventId != lastKnownTimelineEventId
        ) {
            coroutineScope.launch {
                listState.animateScrollToItem(timelineItems.lastIndex + buttonOffset)
            }
            lastKnownTimelineEventId = lastEventId
        }

        if (hasNewItems && lastEventId != null) {
            lastKnownTimelineEventId = lastEventId
        }

        previousItemCount = timelineItems.size
    }

    LaunchedEffect(roomId) {
        Log.d("Andromuks", "RoomTimelineScreen: Loading timeline for room: $roomId")
        // Reset state for new room
        isLoadingMore = false
        hasLoadedInitialBatch = false
        isAttachedToBottom = true
        isInitialLoad = true
        hasInitialSnapCompleted = false
        // Request room state first, then timeline
        appViewModel.requestRoomState(roomId)
        appViewModel.requestRoomTimeline(roomId)
    }
    
    // Refresh timeline when app resumes (to show new events received while suspended)
    LaunchedEffect(appViewModel.timelineRefreshTrigger) {
        if (appViewModel.timelineRefreshTrigger > 0 && appViewModel.currentRoomId == roomId) {
            Log.d("Andromuks", "RoomTimelineScreen: App resumed, refreshing timeline for room: $roomId")
            // Don't reset state flags - this is just a refresh, not a new room load
            appViewModel.requestRoomTimeline(roomId)
        }
    }

    // Listen for foreground refresh broadcast to refresh timeline when app comes to foreground
    DisposableEffect(Unit) {
        val foregroundRefreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "net.vrkknn.andromuks.FOREGROUND_REFRESH") {
                    Log.d("Andromuks", "RoomTimelineScreen: Received FOREGROUND_REFRESH broadcast, refreshing timeline UI from cache for room: $roomId")
                    // Lightweight timeline refresh from cached data (no network requests)
                    appViewModel.refreshTimelineUI()
                }
            }
        }
        
        val filter = IntentFilter("net.vrkknn.andromuks.FOREGROUND_REFRESH")
        context.registerReceiver(foregroundRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d("Andromuks", "RoomTimelineScreen: Registered FOREGROUND_REFRESH broadcast receiver")
        
        onDispose {
            try {
                context.unregisterReceiver(foregroundRefreshReceiver)
                Log.d("Andromuks", "RoomTimelineScreen: Unregistered FOREGROUND_REFRESH broadcast receiver")
            } catch (e: Exception) {
                Log.w("Andromuks", "RoomTimelineScreen: Error unregistering foreground refresh receiver", e)
            }
        }
    }

    // After initial batch loads, automatically load second batch in background
    // LaunchedEffect(hasLoadedInitialBatch) {
    //    if (hasLoadedInitialBatch && sortedEvents.isNotEmpty()) {
    //        Log.d("Andromuks", "RoomTimelineScreen: Initial batch loaded, automatically loading
    // second batch")
    //        kotlinx.coroutines.delay(500) // Small delay to let UI settle
    //        appViewModel.loadOlderMessages(roomId)
    //    }
    // }

    // Validate and request missing user profiles when timeline events change
    // This ensures all users in the timeline have complete profile data (display name, avatar)
    // Missing profiles are automatically requested from the server
    LaunchedEffect(sortedEvents) {
        if (sortedEvents.isNotEmpty()) {
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Validating user profiles for ${sortedEvents.size} events"
            )
            appViewModel.validateAndRequestMissingProfiles(roomId, sortedEvents)
        }
    }

    // Request updated profile information for users in the room
    // This happens after the timeline loads to refresh potentially stale profile data
    LaunchedEffect(sortedEvents, roomId) {
        // Always request fresh member list when opening a room to ensure accurate membership status
        // This cleans up any stale invite members or other invalid entries in the cache
        Log.d(
            "Andromuks",
            "RoomTimelineScreen: Requesting fresh member list for $roomId to ensure accurate membership"
        )
        appViewModel.requestFullMemberList(roomId)
        
        if (sortedEvents.isNotEmpty()) {
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Requesting updated profiles for ${sortedEvents.size} timeline events"
            )
            // Request updated profile information from the server for all users in the timeline
            // This will not block rendering - it happens in the background and updates UI as data arrives
            appViewModel.requestUpdatedRoomProfiles(roomId, sortedEvents)
        }
    }

    // Save updated profiles to disk when member cache changes
    // This persists user profile data (display names, avatars) to disk for future app sessions
    // Only save profiles for users involved in the events being processed to avoid performance
    // issues
    LaunchedEffect(appViewModel.updateCounter) {
        // Only save profiles for users who are actually involved in the current timeline events
        val usersInTimeline = sortedEvents.map { it.sender }.distinct().toSet()
        if (usersInTimeline.isNotEmpty()) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val profilesToSave = usersInTimeline.filter { memberMap.containsKey(it) }
            if (profilesToSave.isNotEmpty()) {
                android.util.Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Saving ${profilesToSave.size} profiles to disk for users in timeline"
                )
                for (userId in profilesToSave) {
                    val profile = memberMap[userId]
                    if (profile != null) {
                        appViewModel.saveProfileToDisk(context, userId, profile)
                    }
                }
            }
        }
    }

    // Ensure timeline reactively updates when new events arrive from sync
    // OPTIMIZED: Only track timelineEvents changes directly, updateCounter is handled by receipt updates
    LaunchedEffect(timelineEvents) {
        Log.d("Andromuks", "RoomTimelineScreen: Timeline events changed - timelineEvents.size: ${timelineEvents.size}, currentRoomId: ${appViewModel.currentRoomId}, roomId: $roomId")
        
        // Only react to changes for the current room
        if (appViewModel.currentRoomId == roomId) {
            Log.d("Andromuks", "RoomTimelineScreen: Detected timeline update for current room $roomId with ${timelineEvents.size} events")
            
            // Force recomposition when timeline events change
            // This ensures the UI updates even when battery optimization might skip updates
        }
    }

    // Handle Android back key
    BackHandler { navController.popBackStack() }

    // Use imePadding for keyboard handling
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Choose IME if present, otherwise navigation bar padding
    val bottomInset = if (imeBottom > 0.dp) imeBottom else navBarBottom
    
    // Track when keyboard opens to maintain scroll position at bottom
    var wasAtBottomBeforeKeyboard by remember { mutableStateOf(true) }
    
    // Smoothly animate scroll to bottom when keyboard opens
    LaunchedEffect(imeBottom) {
        // When IME (keyboard) height changes
        if (timelineItems.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
            val isKeyboardOpening = imeBottom > 0.dp
            
            // If keyboard is opening and user was at bottom, maintain bottom position
            if (isKeyboardOpening && wasAtBottomBeforeKeyboard) {
                // Animate scroll to ensure last item is fully visible as keyboard opens
                val hasLoadMoreButton = appViewModel.hasMoreMessages
                val buttonOffset = if (hasLoadMoreButton) 1 else 0
                val lastIndex = timelineItems.lastIndex + buttonOffset
                
                if (lastIndex >= 0) {
                    // Use animateScrollToItem for smooth scrolling that matches keyboard animation
                    listState.animateScrollToItem(lastIndex, scrollOffset = 0)
                    isAttachedToBottom = true // Re-attach to bottom
                    Log.d("Andromuks", "RoomTimelineScreen: IME opened (${imeBottom}), animating to bottom")
                }
            }
            
            // Update tracked state when keyboard closes
            if (!isKeyboardOpening) {
                wasAtBottomBeforeKeyboard = isAttachedToBottom
            }
        }
    }

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
                    // 1. Room Header (always visible at the top, below status bar)
                    RoomHeader(
                        roomState = appViewModel.currentRoomState,
                        fallbackName = displayRoomName,
                        fallbackAvatarUrl = displayAvatarUrl,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        roomId = roomId,
                        onHeaderClick = {
                            // Navigate to room info screen
                            navController.navigate("room_info/$roomId")
                        },
                        onRefreshClick = {
                            // Refresh room timeline from server
                            Log.d("Andromuks", "RoomTimelineScreen: Refresh button clicked for room $roomId")
                            appViewModel.refreshRoomTimeline(roomId)
                        }
                    )

                    // 2. Timeline (compressible, scrollable content)
                    if (isLoading) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Loading timeline...")
                        }
                    } else {
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
                            // Load More button at the top - only show if there are more messages
                            if (appViewModel.hasMoreMessages) {
                                item(key = "load_more_button") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isLoadingMore) {
                                            // Show loading indicator
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(32.dp)
                                            )
                                        } else {
                                            // Show "Load More" button
                                            Button(
                                                onClick = {
                                                    Log.d(
                                                        "Andromuks",
                                                        "RoomTimelineScreen: Load More button clicked"
                                                    )
                                                    // Save current scroll position before loading
                                                    firstVisibleItemIndexBeforeLoad = listState.firstVisibleItemIndex
                                                    firstVisibleItemScrollOffsetBeforeLoad = listState.firstVisibleItemScrollOffset
                                                    Log.d(
                                                        "Andromuks",
                                                        "RoomTimelineScreen: Saved scroll position - index: $firstVisibleItemIndexBeforeLoad, offset: $firstVisibleItemScrollOffsetBeforeLoad"
                                                    )
                                                    isLoadingMore = true
                                                    appViewModel.loadOlderMessages(roomId)
                                                },
                                                colors =
                                                    ButtonDefaults.buttonColors(
                                                        containerColor =
                                                            MaterialTheme.colorScheme
                                                                .secondaryContainer,
                                                        contentColor =
                                                            MaterialTheme.colorScheme
                                                                .onSecondaryContainer
                                                    )
                                            ) {
                                                Text("Load More")
                                            }
                                        }
                                    }
                                }
                            }

                            itemsIndexed(timelineItems) { index, item ->
                                when (item) {
                                    is TimelineItem.DateDivider -> {
                                        DateDivider(item.date)
                                    }
                                    is TimelineItem.Event -> {
                                        val event = item.event
                                        Log.d(
                                            "Andromuks",
                                            "RoomTimelineScreen: Processing timeline event: ${event.eventId}, type: ${event.type}, sender: ${event.sender}"
                                        )
                                        val isMine = myUserId != null && event.sender == myUserId

                                        // Check if this is a consecutive message from the same
                                        // sender
                                        // Look at previous item (skip date dividers)
                                        var previousEvent: TimelineEvent? = null
                                        var prevIndex = index - 1
                                        while (prevIndex >= 0 && previousEvent == null) {
                                            when (val prevItem = timelineItems[prevIndex]) {
                                                is TimelineItem.Event ->
                                                    previousEvent = prevItem.event
                                                is TimelineItem.DateDivider ->
                                                    break // Date divider breaks grouping
                                            }
                                            prevIndex--
                                        }

                                        // Check if current event has per-message profile (from
                                        // bridges like Beeper)
                                        // These should always show avatar/name even if from same
                                        // Matrix user
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
                                            timelineEvents = timelineEvents,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            userProfileCache = appViewModel.getMemberMap(roomId),
                                            isMine = isMine,
                                            myUserId = myUserId,
                                            isConsecutive = isConsecutive,
                                            appViewModel = appViewModel,
                                            onScrollToMessage = { eventId ->
                                                // Find the index of the message to scroll to
                                                val index =
                                                    sortedEvents.indexOfFirst {
                                                        it.eventId == eventId
                                                    }
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
                                                        )
                                                        .show()
                                                }
                                            },
                                            onReply = { event -> replyingToEvent = event },
                                            onReact = { event ->
                                                reactingToEvent = event
                                                showEmojiSelection = true
                                            },
                                            onEdit = { event -> editingEvent = event },
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
                                                Log.d("Andromuks", "RoomTimelineScreen: Room link clicked: ${roomLink.roomIdOrAlias}")
                                                
                                                // If it's a room ID, check if we're already joined
                                                val existingRoom = if (roomLink.roomIdOrAlias.startsWith("!")) {
                                                    val room = appViewModel.getRoomById(roomLink.roomIdOrAlias)
                                                    Log.d("Andromuks", "RoomTimelineScreen: Checked for existing room ${roomLink.roomIdOrAlias}, found: ${room != null}")
                                                    room
                                                } else {
                                                    Log.d("Andromuks", "RoomTimelineScreen: Room link is an alias, showing joiner")
                                                    null
                                                }
                                                
                                                if (existingRoom != null) {
                                                    // Already joined, navigate directly
                                                    Log.d("Andromuks", "RoomTimelineScreen: Already joined, navigating to ${roomLink.roomIdOrAlias}")
                                                    val encodedRoomId = java.net.URLEncoder.encode(roomLink.roomIdOrAlias, "UTF-8")
                                                    navController.navigate("room_timeline/$encodedRoomId")
                                                } else {
                                                    // For aliases or non-joined rooms, show room joiner
                                                    Log.d("Andromuks", "RoomTimelineScreen: Not joined, showing room joiner")
                                                    roomLinkToJoin = roomLink
                                                    showRoomJoiner = true
                                                }
                                            },
                                            onThreadClick = { threadEvent ->
                                                // Navigate to thread viewer
                                                val threadInfo = threadEvent.getThreadInfo()
                                                if (threadInfo != null) {
                                                    Log.d("Andromuks", "RoomTimelineScreen: Thread message clicked, opening thread for root: ${threadInfo.threadRootEventId}")
                                                    val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                                    val encodedThreadRoot = java.net.URLEncoder.encode(threadInfo.threadRootEventId, "UTF-8")
                                                    navController.navigate("thread_viewer/$encodedRoomId/$encodedThreadRoot")
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // 3. Typing notification area (stacks naturally above text box)
                    TypingNotificationArea(
                        typingUsers = appViewModel.typingUsers,
                        roomId = roomId,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        userProfileCache = appViewModel.getMemberMap(roomId)
                    )

                    // 4. Text box (always at the bottom, above keyboard/nav bar)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier =
                            Modifier.fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                    ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Attach button in its own frame
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.width(48.dp).height(56.dp)
                        ) {
                            IconButton(
                                onClick = { mediaPickerLauncher.launch("*/*") }, // Accept both images and videos
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Attach media",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Pill-shaped text input with optional reply preview inside
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape =
                                RoundedCornerShape(
                                    16.dp
                                ), // Rounded rectangle that works both as pill and expanded
                            tonalElevation = 1.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column {
                                // Edit preview inside the text input (if editing)
                                if (editingEvent != null) {
                                    EditPreviewInput(
                                        event = editingEvent!!,
                                        onCancel = {
                                            editingEvent = null
                                            draft = "" // Clear draft when canceling edit
                                        }
                                    )
                                }

                                // Reply preview inside the text input (if replying)
                                if (replyingToEvent != null) {
                                    ReplyPreviewInput(
                                        event = replyingToEvent!!,
                                        userProfileCache =
                                            userProfileCache.mapValues { (_, pair) ->
                                                MemberProfile(pair.first, pair.second)
                                            },
                                        onCancel = { replyingToEvent = null },
                                        appViewModel = appViewModel,
                                        roomId = roomId
                                    )
                                }

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
                                    placeholder = { Text("Type a message...") },
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
                                                // Send edit if editing a message
                                                if (editingEvent != null) {
                                                    appViewModel.sendEdit(roomId, draft, editingEvent!!)
                                                    editingEvent = null // Clear edit state
                                                }
                                                // Send reply if replying to a message
                                                else if (replyingToEvent != null) {
                                                    // Check if replying to a thread message
                                                    val threadInfo = replyingToEvent!!.getThreadInfo()
                                                    if (threadInfo != null) {
                                                        // Send thread reply
                                                        appViewModel.sendThreadReply(
                                                            roomId = roomId,
                                                            text = draft,
                                                            threadRootEventId = threadInfo.threadRootEventId,
                                                            fallbackReplyToEventId = replyingToEvent!!.eventId
                                                        )
                                                    } else {
                                                        // Send normal reply
                                                        appViewModel.sendReply(roomId, draft, replyingToEvent!!)
                                                    }
                                                    replyingToEvent = null // Clear reply state
                                                }
                                                // Otherwise send regular message
                                                else {
                                                    appViewModel.sendMessage(roomId, draft)
                                                }
                                                draft = "" // Clear the input after sending
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
                        // Circular send button with rotation animation when sending
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
                                    // Send edit if editing a message
                                    if (editingEvent != null) {
                                        appViewModel.sendEdit(roomId, draft, editingEvent!!)
                                        editingEvent = null // Clear edit state
                                    }
                                    // Send reply if replying to a message
                                    else if (replyingToEvent != null) {
                                        // Check if replying to a thread message
                                        val threadInfo = replyingToEvent!!.getThreadInfo()
                                        if (threadInfo != null) {
                                            // Send thread reply
                                            appViewModel.sendThreadReply(
                                                roomId = roomId,
                                                text = draft,
                                                threadRootEventId = threadInfo.threadRootEventId,
                                                fallbackReplyToEventId = replyingToEvent!!.eventId
                                            )
                                        } else {
                                            // Send normal reply
                                            appViewModel.sendReply(roomId, draft, replyingToEvent!!)
                                        }
                                        replyingToEvent = null // Clear reply state
                                    }
                                    // Otherwise send regular message
                                    else {
                                        appViewModel.sendMessage(roomId, draft)
                                    }
                                    draft = "" // Clear the input after sending
                                }
                            },
                            enabled = draft.isNotBlank(),
                            shape = CircleShape, // Perfect circle
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            modifier = Modifier.size(56.dp), // Same height as pill
                            contentPadding = PaddingValues(0.dp) // No padding for perfect circle
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
                
                // Floating action button to scroll to bottom (only shown when detached)
                // Keep this in the Box so it can overlay the content
                if (!isAttachedToBottom) {
                    FloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                // Scroll to bottom and re-attach
                                // Account for Load More button if present
                                val buttonOffset = if (appViewModel.hasMoreMessages) 1 else 0
                                listState.animateScrollToItem(
                                    timelineItems.lastIndex + buttonOffset
                                )
                                isAttachedToBottom = true
                                Log.d(
                                    "Andromuks",
                                    "RoomTimelineScreen: FAB clicked, scrolling to bottom and re-attaching"
                                )
                            }
                        },
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(
                                    end = 16.dp,
                                    bottom = 16.dp
                                )
                                .navigationBarsPadding()
                                .imePadding(), // Above text input and keyboard
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom"
                        )
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
                        MentionMemberList(
                            members = roomMembers,
                            query = mentionQuery,
                            onMemberSelect = { userId, displayName ->
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
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = authToken,
                            modifier = Modifier.zIndex(10f)
                        )
                    }
                }
                
                // Delete confirmation dialog (with optional reason)
                if (showDeleteDialog && deletingEvent != null) {
                    DeleteMessageDialog(
                        onDismiss = {
                            showDeleteDialog = false
                            deletingEvent = null
                        },
                        onConfirm = { reason ->
                            // Send delete request with optional reason
                            appViewModel.sendDelete(roomId, deletingEvent!!, reason)
                            showDeleteDialog = false
                            deletingEvent = null
                        }
                    )
                }
                
                // Room joiner screen
                if (showRoomJoiner && roomLinkToJoin != null) {
                    RoomJoinerScreen(
                        roomLink = roomLinkToJoin!!,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        appViewModel = appViewModel,
                        onDismiss = {
                            showRoomJoiner = false
                            roomLinkToJoin = null
                        },
                        onJoinSuccess = { joinedRoomId ->
                            showRoomJoiner = false
                            roomLinkToJoin = null
                            // Navigate to the joined room
                            appViewModel.joinRoomAndNavigate(joinedRoomId, navController)
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
                            // Send reaction
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
                
                // Media preview dialog (shows selected media with caption input)
                if (showMediaPreview && selectedMediaUri != null) {
                    MediaPreviewDialog(
                        uri = selectedMediaUri!!,
                        isVideo = selectedMediaIsVideo,
                        onDismiss = {
                            showMediaPreview = false
                            selectedMediaUri = null
                            selectedMediaIsVideo = false
                        },
                        onSend = { caption ->
                            // Start upload
                            showMediaPreview = false
                            isUploading = true
                            
                            // Upload and send in background
                            coroutineScope.launch {
                                try {
                                    if (selectedMediaIsVideo) {
                                        // Upload video with thumbnail
                                        Log.d("Andromuks", "RoomTimelineScreen: Starting video upload")
                                        val videoResult = VideoUploadUtils.uploadVideo(
                                            context = context,
                                            uri = selectedMediaUri!!,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            isEncrypted = false
                                        )
                                        
                                        if (videoResult != null) {
                                            Log.d("Andromuks", "RoomTimelineScreen: Video upload successful, sending message")
                                            // Send video message with metadata
                                            appViewModel.sendVideoMessage(
                                                roomId = roomId,
                                                videoMxcUrl = videoResult.videoMxcUrl,
                                                thumbnailMxcUrl = videoResult.thumbnailMxcUrl,
                                                width = videoResult.width,
                                                height = videoResult.height,
                                                duration = videoResult.duration,
                                                size = videoResult.size,
                                                mimeType = videoResult.mimeType,
                                                thumbnailBlurHash = videoResult.thumbnailBlurHash,
                                                thumbnailWidth = videoResult.thumbnailWidth,
                                                thumbnailHeight = videoResult.thumbnailHeight,
                                                thumbnailSize = videoResult.thumbnailSize,
                                                caption = caption.takeIf { it.isNotBlank() }
                                            )
                                            
                                            // Clear state
                                            selectedMediaUri = null
                                            selectedMediaIsVideo = false
                                            isUploading = false
                                        } else {
                                            Log.e("Andromuks", "RoomTimelineScreen: Video upload failed")
                                            isUploading = false
                                            android.widget.Toast.makeText(
                                                context,
                                                "Failed to upload video",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        // Upload image
                                        Log.d("Andromuks", "RoomTimelineScreen: Starting image upload")
                                        val uploadResult = MediaUploadUtils.uploadMedia(
                                            context = context,
                                            uri = selectedMediaUri!!,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            isEncrypted = false
                                        )
                                        
                                        if (uploadResult != null) {
                                            Log.d("Andromuks", "RoomTimelineScreen: Image upload successful, sending message")
                                            // Send image message with metadata
                                            appViewModel.sendImageMessage(
                                                roomId = roomId,
                                                mxcUrl = uploadResult.mxcUrl,
                                                width = uploadResult.width,
                                                height = uploadResult.height,
                                                size = uploadResult.size,
                                                mimeType = uploadResult.mimeType,
                                                blurHash = uploadResult.blurHash,
                                                caption = caption.takeIf { it.isNotBlank() }
                                            )
                                            
                                            // Clear state
                                            selectedMediaUri = null
                                            isUploading = false
                                        } else {
                                            Log.e("Andromuks", "RoomTimelineScreen: Image upload failed")
                                            isUploading = false
                                            android.widget.Toast.makeText(
                                                context,
                                                "Failed to upload image",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "RoomTimelineScreen: Upload error", e)
                                    isUploading = false
                                    selectedMediaUri = null
                                    selectedMediaIsVideo = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "Error uploading media: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
                
                // Uploading dialog (shows progress during upload)
                if (isUploading) {
                    UploadingDialog(isVideo = selectedMediaIsVideo)
                }
            }
        }
    }
}

fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(date)
}

@Composable
fun InlineBubbleTimestamp(
    timestamp: Long,
    editedBy: TimelineEvent? = null,
    isMine: Boolean,
    isConsecutive: Boolean
) {
    // Only show timestamp inside bubble for consecutive messages
    if (isConsecutive) {
        Text(
            text =
                if (editedBy != null) {
                    " ${formatTimestamp(timestamp)} (edited)"
                } else {
                    " ${formatTimestamp(timestamp)}"
                },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * Smart message text renderer that automatically chooses between HTML and plain text based on
 * what's available in the event
 */
@Composable
fun AdaptiveMessageText(
    event: TimelineEvent,
    body: String,
    format: String?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    onUserClick: (String) -> Unit = {},
    onMatrixUserClick: (String) -> Unit = onUserClick,
    onRoomLinkClick: (RoomLink) -> Unit = {}
) {
    // For redacted messages, always use plain text to show the deletion message
    val isRedacted = event.redactedBy != null
    
    // Check if HTML rendering is supported and available (and not redacted)
    if (!isRedacted && supportsHtmlRendering(event)) {
        Log.d("Andromuks", "AdaptiveMessageText: Using HTML rendering for event ${event.eventId}")
        HtmlMessageText(
            event = event,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            color = textColor,
            modifier = modifier,
            onMatrixUserClick = onMatrixUserClick,
            onRoomLinkClick = onRoomLinkClick
        )
    } else {
        // Fallback to plain text for redacted messages or when HTML is not available
        if (isRedacted) {
            Log.d("Andromuks", "AdaptiveMessageText: Using plain text for redacted event ${event.eventId}")
        } else {
            Log.d("Andromuks", "AdaptiveMessageText: Using SmartMessageText for event ${event.eventId}")
        }
        
        // Show deletion message (in body parameter) or regular text
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontStyle = if (isRedacted) FontStyle.Italic else FontStyle.Normal,
            modifier = modifier
        )
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
    myUserId: String?,
    isConsecutive: Boolean = false,
    appViewModel: AppViewModel? = null,
    onScrollToMessage: (String) -> Unit = {},
    onReply: (TimelineEvent) -> Unit = {},
    onReact: (TimelineEvent) -> Unit = {},
    onEdit: (TimelineEvent) -> Unit = {},
    onDelete: (TimelineEvent) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onRoomLinkClick: (RoomLink) -> Unit = {},
    onThreadClick: (TimelineEvent) -> Unit = {}
) {
    val context = LocalContext.current

    // Check for per-message profile (e.g., from Beeper bridge)
    // This can be in either the content (for regular messages) or decrypted content (for encrypted
    // messages)
    val perMessageProfile = event.content?.optJSONObject("com.beeper.per_message_profile")
    val encryptedPerMessageProfile =
        event.decrypted?.optJSONObject("com.beeper.per_message_profile")
    val hasPerMessageProfile = perMessageProfile != null
    val hasEncryptedPerMessageProfile = encryptedPerMessageProfile != null

    // Use per-message profile if available (prioritize encrypted over regular), otherwise fall back
    // to regular profile cache
    val actualProfile =
        when {
            hasEncryptedPerMessageProfile -> {
                val encryptedPerMessageDisplayName =
                    encryptedPerMessageProfile?.optString("displayname")?.takeIf { it.isNotBlank() }
                val encryptedPerMessageAvatarUrl =
                    encryptedPerMessageProfile?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                val encryptedPerMessageUserId =
                    encryptedPerMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }

                // Create a temporary profile object for encrypted per-message profile
                MemberProfile(encryptedPerMessageDisplayName, encryptedPerMessageAvatarUrl)
            }
            hasPerMessageProfile -> {
                val perMessageDisplayName =
                    perMessageProfile?.optString("displayname")?.takeIf { it.isNotBlank() }
                val perMessageAvatarUrl =
                    perMessageProfile?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                val perMessageUserId =
                    perMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }

                // Create a temporary profile object for per-message profile
                MemberProfile(perMessageDisplayName, perMessageAvatarUrl)
            }
            else -> userProfileCache[event.sender]
        }

    val displayName = actualProfile?.displayName
    val avatarUrl = actualProfile?.avatarUrl

    // For per-message profiles, we also need to track the bridge sender
    val bridgeSender =
        if (hasPerMessageProfile || hasEncryptedPerMessageProfile) event.sender else null

    // For per-message profiles, check if the message is "mine" based on the per-message profile
    // user ID
    val actualIsMine =
        if (hasPerMessageProfile || hasEncryptedPerMessageProfile) {
            val perMessageUserId =
                if (hasEncryptedPerMessageProfile) {
                    encryptedPerMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
                } else {
                    perMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
                }
            myUserId != null && perMessageUserId == myUserId
        } else {
            isMine
        }

    // Check if the current user is mentioned in this message (calculated once for reuse)
    val mentionsMe = !actualIsMine && isMentioningUser(event, myUserId)

    // Check if this is a narrator event (system event)
    val isNarratorEvent =
        event.type in
            setOf(
                "m.room.member",
                "m.room.name",
                "m.room.topic",
                "m.room.avatar",
                "m.room.pinned_events"
            )

    // Check if this message is being edited by another event (moved to function start)
    val editedBy =
        timelineEvents.find {
            (it.content?.optJSONObject("m.relates_to")?.optString("event_id") == event.eventId &&
                it.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") ||
                (it.decrypted?.optJSONObject("m.relates_to")?.optString("event_id") ==
                    event.eventId &&
                    it.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
                        "m.replace")
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

    // Early return for edit events (m.replace relationships) - they should not be displayed as
    // separate timeline items
    val isEditEvent =
        (event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") ||
            (event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace")
    if (isEditEvent) {
        return
    }

    // Calculate read receipts and recalculate when receipts are updated
    // OPTIMIZED: Use separate readReceiptsUpdateCounter to avoid unnecessary recomposition of timeline
    val readReceipts =
        remember(event.eventId, appViewModel?.readReceiptsUpdateCounter) {
            if (appViewModel != null) {
                net.vrkknn.andromuks.utils.ReceiptFunctions.getReadReceipts(
                    event.eventId,
                    appViewModel.getReadReceiptsMap()
                )
            } else {
                emptyList()
            }
        }

    // Early check for emote message (before rendering layout)
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
    
    // Check if this message should animate in (new message with slide-up effect)
    val newMessageAnimations = appViewModel?.getNewMessageAnimations() ?: emptyMap()
    val animationTrigger = appViewModel?.newMessageAnimationTrigger ?: 0L
    val shouldAnimate = newMessageAnimations.containsKey(event.eventId)
    var shouldShowAnimation by remember(event.eventId, animationTrigger) { 
        mutableStateOf(!shouldAnimate) // Start as visible for non-animating messages
    }
    
    // Launch animation when this message is marked for animation
    LaunchedEffect(event.eventId, animationTrigger) {
        if (shouldAnimate && !shouldShowAnimation) {
            kotlinx.coroutines.delay(500) // 500ms delay for receipt processing
            shouldShowAnimation = true
        }
    }
    
    // Animate slide-up effect for new messages
    val animatedOffsetY by animateFloatAsState(
        targetValue = if (shouldShowAnimation || !shouldAnimate) 0f else 120f, // Start 120dp below for new messages
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        ),
        label = "slide_up_${event.eventId}_$animationTrigger"
    )
    
    // Animate alpha for smooth fade-in
    val animatedAlpha by animateFloatAsState(
        targetValue = if (shouldShowAnimation || !shouldAnimate) 1f else 0.3f, // Start semi-transparent for new messages
        animationSpec = tween(durationMillis = 400),
        label = "fade_in_${event.eventId}_$animationTrigger"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .graphicsLayer(
                translationY = animatedOffsetY,
                alpha = animatedAlpha
            ),
        verticalAlignment = Alignment.Top
    ) {
        // Show avatar only for non-consecutive messages (and not for emotes, they have their own)
        if (!actualIsMine && !isConsecutive && !isEmoteMessage) {
            Box(modifier = Modifier.clickable { onUserClick(event.sender) }) {
                AvatarImage(
                    mxcUrl = avatarUrl,
                    homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                    authToken = authToken,
                    fallbackText = (displayName ?: event.sender).take(1),
                    size = 24.dp,
                    userId = event.sender,
                    displayName = displayName
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        } else if (!actualIsMine && isConsecutive && !isEmoteMessage) {
            // Add spacer to maintain alignment for consecutive messages (but not for emotes)
            Spacer(modifier = Modifier.width(32.dp)) // 24dp avatar + 8dp spacer
        }

        // Event content
        Column(modifier = Modifier.weight(1f)) {
            // Show name and timestamp header only for non-consecutive messages (and not for emotes)
            if (!isConsecutive && !isEmoteMessage) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    // Show per-message profile name and bridge sender info
                    val headerText =
                        if (
                            (hasPerMessageProfile || hasEncryptedPerMessageProfile) &&
                                bridgeSender != null
                        ) {
                            // Get bridge sender display name for better readability
                            val bridgeProfile = userProfileCache[bridgeSender]
                            val bridgeDisplayName = bridgeProfile?.displayName ?: bridgeSender
                            "${displayName ?: "Unknown"}, sent by ${bridgeDisplayName}"
                        } else {
                            displayName ?: event.sender
                        }

                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onUserClick(event.sender) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text =
                            if (editedBy != null) {
                                "${formatTimestamp(event.timestamp)} (edited at ${formatTimestamp(editedBy.timestamp)})"
                            } else {
                                formatTimestamp(event.timestamp)
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (event.type) {
                "m.room.redaction" -> {
                    // Handle redaction events - these should not be displayed as regular messages
                    // The redaction logic will be handled by modifying the original message
                    // When a message is redacted, it gets a redactedBy field pointing to the
                    // redaction event
                    // We use this to display deletion messages instead of the original content
                    return
                }
                "m.room.message" -> {
                    // Check if this is an edit event (m.replace relationship)
                    val isEditEvent =
                        event.content?.optJSONObject("m.relates_to")?.optString("rel_type") ==
                            "m.replace"
                    // For edit events, get content from m.new_content; for regular messages, use
                    // content directly
                    // This ensures edit events display the new content instead of the edit metadata
                    val content =
                        if (isEditEvent) {
                            event.content?.optJSONObject("m.new_content")
                        } else {
                            event.content
                        }
                    val format = content?.optString("format", "")
                    val body =
                        if (format == "org.matrix.custom.html") {
                            content?.optString("formatted_body", "") ?: ""
                        } else {
                            content?.optString("body", "") ?: ""
                        }
                    val msgType = content?.optString("msgtype", "") ?: ""
                    
                    // Handle m.emote messages with narrator rendering
                    if (msgType == "m.emote") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            EmoteEventNarrator(
                                event = event,
                                displayName = displayName ?: event.sender,
                                avatarUrl = avatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                onReply = { onReply(event) },
                                onReact = { onReact(event) },
                                onEdit = { onEdit(event) },
                                onDelete = { onDelete(event) }
                            )
                            
                            // Add reaction badges for emote messages
                            if (appViewModel != null) {
                                val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                                }
                                if (reactions.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 28.dp),
                                        horizontalArrangement = Arrangement.Start
                                    ) {
                                        ReactionBadges(
                                            eventId = event.eventId,
                                            reactions = reactions,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken
                                        )
                                    }
                                }
                            }
                        }
                        return
                    }

                    // OPTIMIZED: Check if this message has been redacted using O(1) lookup
                    val isRedacted = event.redactedBy != null
                    val redactionEvent = if (isRedacted && appViewModel != null) {
                        appViewModel.getRedactionEvent(event.eventId)  // O(1) lookup!
                    } else null
                    
                    val redactionSender = redactionEvent?.sender

                    // Request profile if redaction sender is missing from cache
                    if (isRedacted && redactionSender != null && appViewModel != null) {
                        if (!userProfileCache.containsKey(redactionSender)) {
                            android.util.Log.d(
                                "Andromuks",
                                "RoomTimelineScreen: Requesting profile for redaction sender: $redactionSender in room ${event.roomId}"
                            )
                            appViewModel.requestUserProfile(redactionSender, event.roomId)
                        }
                    }

                    // Show deletion message if redacted, otherwise show the message content
                    val finalBody =
                        if (isRedacted) {
                            // OPTIMIZED: Create deletion message using O(1) cached redaction event
                            net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                                redactionEvent,
                                userProfileCache
                            )
                        } else {
                            body // Show the message content (for edit events, this is already the
                                 // new content)
                        }

                    // Check if this is a reply message
                    val replyInfo = event.getReplyInfo()
                    val originalEvent =
                        replyInfo?.let { reply ->
                            timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
                        }

                    // Check if it's a media message
                    if (msgType == "m.image" || msgType == "m.video") {
                        Log.d(
                            "Andromuks",
                            "TimelineEventItem: Found media message - msgType=$msgType, body=$body"
                        )

                        // If media message is redacted, show deletion message instead of media
                        if (isRedacted) {
                            // OPTIMIZED: Display deletion message for media using cached redaction event
                            val deletionMessage =
                                net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                                    redactionEvent,
                                    userProfileCache
                                )

                            val bubbleShape =
                                if (actualIsMine) {
                                    RoundedCornerShape(
                                        topStart = 12.dp,
                                        topEnd = 2.dp,
                                        bottomEnd = 12.dp,
                                        bottomStart = 12.dp
                                    )
                                } else {
                                    RoundedCornerShape(
                                        topStart = 2.dp,
                                        topEnd = 12.dp,
                                        bottomEnd = 12.dp,
                                        bottomStart = 12.dp
                                    )
                                }

                            val bubbleColor =
                                if (actualIsMine) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            val textColor =
                                if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    if (actualIsMine) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = bubbleColor,
                                    shape = bubbleShape,
                                    shadowElevation = 3.dp,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = deletionMessage,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                        fontStyle =
                                            FontStyle.Italic, // Make deletion messages italic
                                        modifier =
                                            Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            return // Exit early for redacted media messages
                        }

                        // Check if media is encrypted (has file object) or unencrypted (has url
                        // field)
                        val fileObj = content?.optJSONObject("file")
                        val hasEncryptedFile = fileObj != null
                        val directUrl = content?.optString("url", "") ?: ""
                        val fileUrl = fileObj?.optString("url", "") ?: ""
                        val url = directUrl.takeIf { it.isNotBlank() } ?: fileUrl

                        val filename = content?.optString("filename", "") ?: ""
                        val info = content?.optJSONObject("info")

                        Log.d(
                            "Andromuks",
                            "TimelineEventItem: Media data - url=$url, filename=$filename, info=${info != null}, hasEncryptedFile=$hasEncryptedFile"
                        )

                        if (url.isNotBlank() && info != null) {
                            val width = info.optInt("w", 0)
                            val height = info.optInt("h", 0)
                            val size = info.optLong("size", 0)
                            val mimeType = info.optString("mimetype", "")
                            val blurHash =
                                info.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }

                            // Extract thumbnail info for videos
                            // Check for both encrypted (thumbnail_file) and unencrypted (thumbnail_url) thumbnails
                            var thumbnailIsEncrypted = false
                            val thumbnailUrl = if (msgType == "m.video") {
                                val thumbnailFile = info.optJSONObject("thumbnail_file")
                                if (thumbnailFile != null) {
                                    // Encrypted thumbnail
                                    thumbnailIsEncrypted = true
                                    thumbnailFile.optString("url", "")?.takeIf { it.isNotBlank() }
                                } else {
                                    // Unencrypted thumbnail
                                    info.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
                                }
                            } else null
                            
                            val thumbnailInfo = if (msgType == "m.video") {
                                info.optJSONObject("thumbnail_info")
                            } else null
                            
                            val thumbnailBlurHash = thumbnailInfo?.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
                            val thumbnailWidth = thumbnailInfo?.optInt("w", 0)
                            val thumbnailHeight = thumbnailInfo?.optInt("h", 0)
                            val duration = if (msgType == "m.video") {
                                info.optInt("duration", 0).takeIf { it > 0 }
                            } else null

                            // Extract caption: use sanitized_html if available, otherwise body (only if different from filename)
                            val caption = if (body != filename && body.isNotBlank()) {
                                val localContent = event.localContent
                                val sanitizedHtml = localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
                                // Use sanitized_html if available and different from filename, otherwise use body
                                if (sanitizedHtml != null && sanitizedHtml != filename) {
                                    sanitizedHtml
                                } else {
                                    body
                                }
                            } else null

                            val mediaInfo =
                                MediaInfo(
                                    width = width,
                                    height = height,
                                    size = size,
                                    mimeType = mimeType,
                                    blurHash = blurHash,
                                    thumbnailUrl = thumbnailUrl,
                                    thumbnailBlurHash = thumbnailBlurHash,
                                    thumbnailWidth = thumbnailWidth,
                                    thumbnailHeight = thumbnailHeight,
                                    duration = duration,
                                    thumbnailIsEncrypted = thumbnailIsEncrypted
                                )

                            val mediaMessage =
                                MediaMessage(
                                    url = url,
                                    filename = filename,
                                    caption = caption,
                                    info = mediaInfo,
                                    msgType = msgType
                                )

                            Log.d(
                                "Andromuks",
                                "TimelineEventItem: Created MediaMessage - url=${mediaMessage.url}, blurHash=${mediaMessage.info.blurHash}"
                            )

                            // Display media message with nested reply structure
                            MediaMessageItem(
                                mediaMessage = mediaMessage,
                                replyInfo = replyInfo,
                                originalEvent = originalEvent,
                                userProfileCache = userProfileCache,
                                homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                authToken = authToken,
                                isMine = actualIsMine,
                                hasEncryptedFile = hasEncryptedFile,
                                event = event,
                                isConsecutive = isConsecutive,
                                editedBy = editedBy,
                                timelineEvents = timelineEvents,
                                onScrollToMessage = onScrollToMessage,
                                onReply = { onReply(event) },
                                onReact = { onReact(event) },
                                onEdit = { onEdit(event) },
                                onDelete = { onDelete(event) },
                                onUserClick = onUserClick,
                                appViewModel = appViewModel,
                                myUserId = myUserId,
                                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                onBubbleClick = if (event.isThreadMessage()) {
                                    { onThreadClick(event) }
                                } else {
                                    null
                                }
                            )

                            // Add reaction badges for media messages
                            if (appViewModel != null) {
                                val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                                }
                                if (reactions.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement =
                                            if (actualIsMine) Arrangement.End else Arrangement.Start
                                    ) {
                                        ReactionBadges(
                                            eventId = event.eventId,
                                            reactions = reactions,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken
                                        )
                                    }
                                }
                            }
                        } else {
                            // Fallback to text message if media parsing fails
                            val bubbleShape =
                                if (actualIsMine) {
                                    RoundedCornerShape(
                                        topStart = 12.dp,
                                        topEnd = 2.dp,
                                        bottomEnd = 8.dp,
                                        bottomStart = 12.dp
                                    )
                                } else {
                                    RoundedCornerShape(
                                        topStart = 2.dp,
                                        topEnd = 12.dp,
                                        bottomEnd = 12.dp,
                                        bottomStart = 8.dp
                                    )
                                }
                            
                            val bubbleColor =
                                if (actualIsMine) MaterialTheme.colorScheme.primaryContainer
                                else if (mentionsMe) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            val textColor =
                                if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer
                                else if (mentionsMe) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    if (actualIsMine) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = bubbleColor,
                                    shape = bubbleShape,
                                    shadowElevation = 3.dp,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                        modifier =
                                            Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // Regular text message
                        val bubbleShape =
                            if (actualIsMine) {
                                RoundedCornerShape(
                                    topStart = 12.dp,
                                    topEnd = 2.dp,
                                    bottomEnd = 12.dp,
                                    bottomStart = 12.dp
                                )
                            } else {
                                RoundedCornerShape(
                                    topStart = 2.dp,
                                    topEnd = 12.dp,
                                    bottomEnd = 12.dp,
                                    bottomStart = 12.dp
                                )
                            }
                        
                        // Check if message has been edited (O(1) lookup)
                        val hasBeenEdited = remember(event.eventId, appViewModel?.updateCounter) {
                            appViewModel?.isMessageEdited(event.eventId) ?: false
                        }
                        
                        // Check if this is a thread message
                        val isThreadMessage = event.isThreadMessage()
                        
                        val bubbleColor =
                            if (isThreadMessage) {
                                // Thread messages use Material3 tertiary colors (typically purple/violet)
                                // Own messages: fuller opacity for emphasis
                                // Others' messages: lighter for distinction
                                if (actualIsMine) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            } else if (actualIsMine) {
                                if (hasBeenEdited) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            } else if (mentionsMe) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                if (hasBeenEdited) MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.surfaceVariant
                            }
                        val textColor =
                            if (isThreadMessage) {
                                // Thread messages use tertiary color for text
                                MaterialTheme.colorScheme.tertiary
                            } else if (actualIsMine) {
                                if (hasBeenEdited) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer
                            } else if (mentionsMe) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                if (actualIsMine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            // For my messages, show read receipts on the left of the bubble
                            if (actualIsMine && readReceipts.isNotEmpty()) {
                                AnimatedInlineReadReceiptAvatars(
                                    receipts = readReceipts,
                                    userProfileCache = userProfileCache,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    appViewModel = appViewModel,
                                    messageSender = event.sender,
                                    eventId = event.eventId,
                                    onUserClick = onUserClick
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            // Display reply with nested structure if this is a reply (but NOT a thread message)
                            // Thread messages are rendered as normal bubbles with different color
                            if (replyInfo != null && originalEvent != null && !isThreadMessage) {
                                MessageBubbleWithMenu(
                                    event = event,
                                    bubbleColor = bubbleColor,
                                    bubbleShape = bubbleShape,
                                    modifier = Modifier.widthIn(max = 300.dp),
                                    isMine = actualIsMine,
                                    myUserId = myUserId,
                                    powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                    onReply = { onReply(event) },
                                    onReact = { onReact(event) },
                                    onEdit = { onEdit(event) },
                                    onDelete = { onDelete(event) },
                                    appViewModel = appViewModel,
                                    onBubbleClick = if (isThreadMessage) {
                                        { onThreadClick(event) }
                                    } else {
                                        null
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        // Reply preview (clickable original message)
                                        ReplyPreview(
                                            replyInfo = replyInfo,
                                            originalEvent = originalEvent,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            isMine = actualIsMine,
                                            modifier =
                                                Modifier.padding(bottom = 6.dp)
                                                    .align(Alignment.Start),
                                            onOriginalMessageClick = {
                                                onScrollToMessage(replyInfo.eventId)
                                            },
                                            timelineEvents = timelineEvents,
                                            onMatrixUserClick = onUserClick,
                                            appViewModel = appViewModel
                                        )

                                        // Reply message content with inline timestamp
                                        Box {
                                            // Text with extra padding at the end for timestamp
                                            AdaptiveMessageText(
                                                event = event,
                                                body = finalBody,
                                                format = format,
                                                userProfileCache = userProfileCache,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                appViewModel = appViewModel,
                                                roomId = event.roomId,
                                                textColor = textColor,
                                                modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                                                onUserClick = onUserClick,
                                                onMatrixUserClick = onUserClick,
                                                onRoomLinkClick = onRoomLinkClick
                                            )
                                            // Timestamp positioned at bottom-end
                                            Box(
                                                modifier = Modifier.align(Alignment.BottomEnd)
                                            ) {
                                                InlineBubbleTimestamp(
                                                    timestamp = event.timestamp,
                                                    editedBy = editedBy,
                                                    isMine = actualIsMine,
                                                    isConsecutive = isConsecutive
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Regular message bubble with popup menu
                                MessageBubbleWithMenu(
                                    event = event,
                                    bubbleColor = bubbleColor,
                                    bubbleShape = bubbleShape,
                                    modifier = Modifier.widthIn(max = 300.dp),
                                    isMine = actualIsMine,
                                    myUserId = myUserId,
                                    powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                    onReply = { onReply(event) },
                                    onReact = { onReact(event) },
                                    onEdit = { onEdit(event) },
                                    onDelete = { onDelete(event) },
                                    appViewModel = appViewModel,
                                    onBubbleClick = if (isThreadMessage) {
                                        { onThreadClick(event) }
                                    } else {
                                        null
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        // Text with extra padding at the end for timestamp
                                        AdaptiveMessageText(
                                            event = event,
                                            body = finalBody,
                                            format = format,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            appViewModel = appViewModel,
                                            roomId = event.roomId,
                                            textColor = textColor,
                                            modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                                            onUserClick = onUserClick,
                                            onMatrixUserClick = onUserClick,
                                            onRoomLinkClick = onRoomLinkClick
                                        )
                                        // Timestamp positioned at bottom-end
                                        Box(
                                            modifier = Modifier.align(Alignment.BottomEnd)
                                        ) {
                                            InlineBubbleTimestamp(
                                                timestamp = event.timestamp,
                                                editedBy = editedBy,
                                                isMine = actualIsMine,
                                                isConsecutive = isConsecutive
                                            )
                                        }
                                    }
                                }
                            }

                                // For others' messages, show read receipts on the right of the bubble
                                if (!actualIsMine && readReceipts.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    AnimatedInlineReadReceiptAvatars(
                                        receipts = readReceipts,
                                        userProfileCache = userProfileCache,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        appViewModel = appViewModel,
                                        messageSender = event.sender,
                                        eventId = event.eventId,
                                        onUserClick = onUserClick
                                    )
                                }
                            }

                        // Add reaction badges for this message
                        if (appViewModel != null) {
                            val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                appViewModel.messageReactions[event.eventId] ?: emptyList()
                            }
                            if (reactions.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement =
                                        if (actualIsMine) Arrangement.End else Arrangement.Start
                                ) {
                                    ReactionBadges(
                                        eventId = event.eventId,
                                        reactions = reactions,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken
                                    )
                                }
                            }
                        }
                    }
                }
                "m.room.encrypted" -> {
                    // Check if this is an edit event (m.replace relationship) - don't display edit
                    // events
                    val isEditEvent =
                        event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
                            "m.replace"
                    if (isEditEvent) {
                        android.util.Log.d(
                            "Andromuks",
                            "RoomTimelineScreen: Filtering out edit event ${event.eventId}"
                        )
                        return // Don't display edit events as separate timeline items
                    }

                    val decryptedType = event.decryptedType
                    val decrypted = event.decrypted
                    if (decryptedType == "m.room.message") {
                        val format = decrypted?.optString("format", "")
                        val body =
                            if (format == "org.matrix.custom.html") {
                                decrypted?.optString("formatted_body", "") ?: ""
                            } else {
                                decrypted?.optString("body", "") ?: ""
                            }
                        android.util.Log.d(
                            "Andromuks",
                            "RoomTimelineScreen: Displaying event ${event.eventId} with body: '$body'"
                        )
                        val msgType = decrypted?.optString("msgtype", "") ?: ""
                        
                        // Handle encrypted m.emote messages with narrator rendering
                        if (msgType == "m.emote") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                EmoteEventNarrator(
                                    event = event,
                                    displayName = displayName ?: event.sender,
                                    avatarUrl = avatarUrl,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    onReply = { onReply(event) },
                                    onReact = { onReact(event) },
                                    onEdit = { onEdit(event) },
                                    onDelete = { onDelete(event) }
                                )
                                
                                // Add reaction badges for encrypted emote messages
                                if (appViewModel != null) {
                                    val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                        appViewModel.messageReactions[event.eventId] ?: emptyList()
                                    }
                                    if (reactions.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 28.dp),
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            ReactionBadges(
                                                eventId = event.eventId,
                                                reactions = reactions,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken
                                            )
                                        }
                                    }
                                }
                            }
                            return
                        }

                        // OPTIMIZED: Check if this message has been redacted using O(1) lookup
                        val isRedacted = event.redactedBy != null
                        val redactionEvent = if (isRedacted && appViewModel != null) {
                            appViewModel.getRedactionEvent(event.eventId)  // O(1) lookup!
                        } else null
                        
                        val redactionSender = redactionEvent?.sender

                        // Request profile if redaction sender is missing from cache
                        if (isRedacted && redactionSender != null && appViewModel != null) {
                            if (!userProfileCache.containsKey(redactionSender)) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "RoomTimelineScreen: Requesting profile for encrypted message redaction sender: $redactionSender in room ${event.roomId}"
                                )
                                appViewModel.requestUserProfile(redactionSender, event.roomId)
                            }
                        }

                        // Check if this is an edit (m.replace relationship)
                        val isEdit =
                            decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
                                "m.replace"
                        val editContent =
                            if (isEdit) {
                                decrypted?.optJSONObject("m.new_content")
                            } else null

                        // Use edit content if this message is being edited, or show deletion message if redacted
                        val finalBody =
                            if (isRedacted) {
                                // OPTIMIZED: Create deletion message using O(1) cached redaction event
                                net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                                    redactionEvent,
                                    userProfileCache
                                )
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
                        val originalEvent =
                            replyInfo?.let { reply ->
                                timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
                            }

                        // Check if it's a media message
                        if (msgType == "m.image" || msgType == "m.video") {
                            Log.d(
                                "Andromuks",
                                "TimelineEventItem: Found encrypted media message - msgType=$msgType, body=$body"
                            )

                            // Debug: Check what's in the decrypted object
                            Log.d(
                                "Andromuks",
                                "TimelineEventItem: Direct url field: ${decrypted?.optString("url", "NOT_FOUND")}"
                            )
                            Log.d(
                                "Andromuks",
                                "TimelineEventItem: File object exists: ${decrypted?.has("file")}"
                            )
                            if (decrypted?.has("file") == true) {
                                val fileObj = decrypted.optJSONObject("file")
                                Log.d(
                                    "Andromuks",
                                    "TimelineEventItem: File url field: ${fileObj?.optString("url", "NOT_FOUND")}"
                                )
                            }

                            // For encrypted messages, URL might be in file.url
                            // Check if media is encrypted (has file object) or just the event is
                            // encrypted (has url field)
                            val fileObj = decrypted?.optJSONObject("file")
                            val hasEncryptedFile = fileObj != null
                            val directUrl = decrypted?.optString("url", "") ?: ""
                            val fileUrl = fileObj?.optString("url", "") ?: ""
                            val url = directUrl.takeIf { it.isNotBlank() } ?: fileUrl

                            Log.d(
                                "Andromuks",
                                "TimelineEventItem: URL extraction - directUrl='$directUrl', fileObj=${fileObj != null}, fileUrl='$fileUrl', finalUrl='$url', hasEncryptedFile=$hasEncryptedFile"
                            )

                            val filename = decrypted?.optString("filename", "") ?: ""
                            val info = decrypted?.optJSONObject("info")

                            Log.d(
                                "Andromuks",
                                "TimelineEventItem: Encrypted media data - url=$url, filename=$filename, info=${info != null}"
                            )

                            if (url.isNotBlank() && info != null) {
                                val width = info.optInt("w", 0)
                                val height = info.optInt("h", 0)
                                val size = info.optLong("size", 0)
                                val mimeType = info.optString("mimetype", "")
                                val blurHash =
                                    info.optString("xyz.amorgan.blurhash")?.takeIf {
                                        it.isNotBlank()
                                    }

                                // Extract thumbnail info for encrypted videos
                                // For encrypted media, thumbnail is in thumbnail_file.url, not thumbnail_url
                                var thumbnailIsEncrypted = false
                                val thumbnailUrl = if (msgType == "m.video") {
                                    val thumbnailFile = info.optJSONObject("thumbnail_file")
                                    if (thumbnailFile != null) {
                                        // Encrypted thumbnail
                                        thumbnailIsEncrypted = true
                                        thumbnailFile.optString("url", "")?.takeIf { it.isNotBlank() }
                                    } else {
                                        // Unencrypted thumbnail (fallback, though unlikely for encrypted messages)
                                        info.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
                                    }
                                } else null
                                
                                val thumbnailInfo = if (msgType == "m.video") {
                                    info.optJSONObject("thumbnail_info")
                                } else null
                                
                                val thumbnailBlurHash = thumbnailInfo?.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
                                val thumbnailWidth = thumbnailInfo?.optInt("w", 0)
                                val thumbnailHeight = thumbnailInfo?.optInt("h", 0)
                                val duration = if (msgType == "m.video") {
                                    info.optInt("duration", 0).takeIf { it > 0 }
                                } else null

                                // Extract caption: use sanitized_html if available, otherwise body (only if different from filename)
                                val caption = if (body != filename && body.isNotBlank()) {
                                    val localContent = event.localContent
                                    val sanitizedHtml = localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
                                    // Use sanitized_html if available and different from filename, otherwise use body
                                    if (sanitizedHtml != null && sanitizedHtml != filename) {
                                        sanitizedHtml
                                    } else {
                                        body
                                    }
                                } else null

                                val mediaInfo =
                                    MediaInfo(
                                        width = width,
                                        height = height,
                                        size = size,
                                        mimeType = mimeType,
                                        blurHash = blurHash,
                                        thumbnailUrl = thumbnailUrl,
                                        thumbnailBlurHash = thumbnailBlurHash,
                                        thumbnailWidth = thumbnailWidth,
                                        thumbnailHeight = thumbnailHeight,
                                        duration = duration,
                                        thumbnailIsEncrypted = thumbnailIsEncrypted
                                    )

                                val mediaMessage =
                                    MediaMessage(
                                        url = url,
                                        filename = filename,
                                        caption = caption,
                                        info = mediaInfo,
                                        msgType = msgType
                                    )

                                Log.d(
                                    "Andromuks",
                                    "TimelineEventItem: Created encrypted MediaMessage - url=${mediaMessage.url}, blurHash=${mediaMessage.info.blurHash}"
                                )

                                // Display encrypted media message with nested reply structure
                                MediaMessageItem(
                                    mediaMessage = mediaMessage,
                                    replyInfo = replyInfo,
                                    originalEvent = originalEvent,
                                    userProfileCache = userProfileCache,
                                    homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                    authToken = authToken,
                                    isMine = actualIsMine,
                                    hasEncryptedFile = hasEncryptedFile,
                                    event = event,
                                    isConsecutive = isConsecutive,
                                    editedBy = editedBy,
                                    timelineEvents = timelineEvents,
                                    onScrollToMessage = onScrollToMessage,
                                    onReply = { onReply(event) },
                                    onReact = { onReact(event) },
                                    onEdit = { onEdit(event) },
                                    onDelete = { onDelete(event) },
                                    onUserClick = onUserClick,
                                    appViewModel = appViewModel,
                                    myUserId = myUserId,
                                    powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                    onBubbleClick = if (event.isThreadMessage()) {
                                        { onThreadClick(event) }
                                    } else {
                                        null
                                    }
                                )

                                // Add reaction badges for encrypted media messages
                                if (appViewModel != null) {
                                    val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                        appViewModel.messageReactions[event.eventId] ?: emptyList()
                                    }
                                    if (reactions.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement =
                                                if (actualIsMine) Arrangement.End
                                                else Arrangement.Start
                                        ) {
                                            ReactionBadges(
                                                eventId = event.eventId,
                                                reactions = reactions,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Fallback to text message if encrypted media parsing fails
                                val bubbleShape =
                                    if (actualIsMine) {
                                        RoundedCornerShape(
                                            topStart = 12.dp,
                                            topEnd = 2.dp,
                                            bottomEnd = 8.dp,
                                            bottomStart = 12.dp
                                        )
                                    } else {
                                        RoundedCornerShape(
                                            topStart = 2.dp,
                                            topEnd = 12.dp,
                                            bottomEnd = 12.dp,
                                            bottomStart = 8.dp
                                        )
                                    }
                                
                                val bubbleColor =
                                    if (actualIsMine) MaterialTheme.colorScheme.primaryContainer
                                    else if (mentionsMe) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                val textColor =
                                    if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer
                                    else if (mentionsMe) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        if (actualIsMine) Arrangement.End else Arrangement.Start
                                ) {
                                    MessageBubbleWithMenu(
                                        event = event,
                                        bubbleColor = bubbleColor,
                                        bubbleShape = bubbleShape,
                                        modifier = Modifier.widthIn(max = 300.dp),
                                        isMine = actualIsMine,
                                        myUserId = myUserId,
                                        powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                        onReply = { onReply(event) },
                                        onReact = { onReact(event) },
                                        onEdit = { onEdit(event) },
                                        onDelete = { onDelete(event) },
                                        appViewModel = appViewModel,
                                        onBubbleClick = null
                                    ) {
                                        Text(
                                            text = body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textColor,
                                            modifier =
                                                Modifier.padding(
                                                    horizontal = 8.dp,
                                                    vertical = 6.dp
                                                )
                                        )
                                    }
                                }

                                // Add reaction badges for encrypted text message
                                if (appViewModel != null) {
                                    val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                        appViewModel.messageReactions[event.eventId] ?: emptyList()
                                    }
                                    if (reactions.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement =
                                                if (actualIsMine) Arrangement.End
                                                else Arrangement.Start
                                        ) {
                                            ReactionBadges(
                                                eventId = event.eventId,
                                                reactions = reactions,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Regular encrypted text message
                            val bubbleShape =
                                if (actualIsMine) {
                                    RoundedCornerShape(
                                        topStart = 12.dp,
                                        topEnd = 2.dp,
                                        bottomEnd = 8.dp,
                                        bottomStart = 12.dp
                                    )
                                } else {
                                    RoundedCornerShape(
                                        topStart = 2.dp,
                                        topEnd = 12.dp,
                                        bottomEnd = 12.dp,
                                        bottomStart = 8.dp
                                    )
                                }
                            
                            // Check if message has been edited (O(1) lookup)
                            val hasBeenEdited = remember(event.eventId, appViewModel?.updateCounter) {
                                appViewModel?.isMessageEdited(event.eventId) ?: false
                            }
                            
                            // Check if this is a thread message
                            val isThreadMessage = event.isThreadMessage()
                            
                            val bubbleColor =
                                if (isThreadMessage) {
                                    // Thread messages use Material3 tertiary colors (typically purple/violet)
                                    // Own messages: fuller opacity for emphasis
                                    // Others' messages: lighter for distinction
                                    if (actualIsMine) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                } else if (actualIsMine) {
                                    if (hasBeenEdited) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.primaryContainer
                                } else if (mentionsMe) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    if (hasBeenEdited) MaterialTheme.colorScheme.surfaceContainerHighest
                                    else MaterialTheme.colorScheme.surfaceVariant
                                }
                            val textColor =
                                if (isThreadMessage) {
                                    // Thread messages use tertiary color for text
                                    MaterialTheme.colorScheme.tertiary
                                } else if (actualIsMine) {
                                    if (hasBeenEdited) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onPrimaryContainer
                                } else if (mentionsMe) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    if (actualIsMine) Arrangement.End else Arrangement.Start,
                                verticalAlignment = Alignment.Top
                            ) {
                                // For my messages, show read receipts on the left of the bubble
                                if (actualIsMine && readReceipts.isNotEmpty()) {
                                    AnimatedInlineReadReceiptAvatars(
                                        receipts = readReceipts,
                                        userProfileCache = userProfileCache,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        appViewModel = appViewModel,
                                        messageSender = event.sender,
                                        eventId = event.eventId,
                                        onUserClick = onUserClick
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                // Display encrypted text message with nested reply structure (but NOT for thread messages)
                                // Thread messages are rendered as normal bubbles with different color
                                if (replyInfo != null && originalEvent != null && !isThreadMessage) {
                                    MessageBubbleWithMenu(
                                        event = event,
                                        bubbleColor = bubbleColor,
                                        bubbleShape = bubbleShape,
                                        modifier = Modifier.widthIn(max = 300.dp),
                                        isMine = actualIsMine,
                                        myUserId = myUserId,
                                        powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                        onReply = { onReply(event) },
                                        onReact = { onReact(event) },
                                        onEdit = { onEdit(event) },
                                        onDelete = { onDelete(event) },
                                        appViewModel = appViewModel,
                                        onBubbleClick = if (isThreadMessage) {
                                            { onThreadClick(event) }
                                        } else {
                                            null
                                        }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalAlignment = Alignment.Start
                                        ) {
                                            // Reply preview (clickable original message)
                                            ReplyPreview(
                                                replyInfo = replyInfo,
                                                originalEvent = originalEvent,
                                                userProfileCache = userProfileCache,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isMine = actualIsMine,
                                                modifier =
                                                    Modifier.padding(bottom = 6.dp)
                                                        .align(Alignment.Start),
                                                onOriginalMessageClick = {
                                                    onScrollToMessage(replyInfo.eventId)
                                                },
                                                timelineEvents = timelineEvents,
                                                onMatrixUserClick = onUserClick,
                                                appViewModel = appViewModel
                                            )

                                            // Reply message content with inline timestamp
                                            Box {
                                                // finalBody already contains deletion message if redacted
                                                AdaptiveMessageText(
                                                    event = event,
                                                    body = finalBody,
                                                    format = format,
                                                    userProfileCache = userProfileCache,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    appViewModel = appViewModel,
                                                    roomId = event.roomId,
                                                    textColor = textColor,
                                                    modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                                                    onUserClick = onUserClick,
                                                    onMatrixUserClick = onUserClick,
                                                    onRoomLinkClick = onRoomLinkClick
                                                )
                                                // Timestamp positioned at bottom-end
                                                Box(
                                                    modifier = Modifier.align(Alignment.BottomEnd)
                                                ) {
                                                    InlineBubbleTimestamp(
                                                        timestamp = event.timestamp,
                                                        editedBy = editedBy,
                                                        isMine = actualIsMine,
                                                        isConsecutive = isConsecutive
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Regular encrypted message bubble
                                    MessageBubbleWithMenu(
                                        event = event,
                                        bubbleColor = bubbleColor,
                                        bubbleShape = bubbleShape,
                                        modifier = Modifier.widthIn(max = 300.dp),
                                        isMine = actualIsMine,
                                        myUserId = myUserId,
                                        powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                        onReply = { onReply(event) },
                                        onReact = { onReact(event) },
                                        onEdit = { onEdit(event) },
                                        onDelete = { onDelete(event) },
                                        appViewModel = appViewModel,
                                        onBubbleClick = if (isThreadMessage) {
                                            { onThreadClick(event) }
                                        } else {
                                            null
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            // finalBody already contains deletion message if redacted
                                            AdaptiveMessageText(
                                                event = event,
                                                body = finalBody,
                                                format = format,
                                                userProfileCache = userProfileCache,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                appViewModel = appViewModel,
                                                roomId = event.roomId,
                                                textColor = textColor,
                                                modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                                                onUserClick = onUserClick,
                                                onMatrixUserClick = onUserClick,
                                                onRoomLinkClick = onRoomLinkClick
                                            )
                                            // Timestamp positioned at bottom-end
                                            Box(
                                                modifier = Modifier.align(Alignment.BottomEnd)
                                            ) {
                                                InlineBubbleTimestamp(
                                                    timestamp = event.timestamp,
                                                    editedBy = editedBy,
                                                    isMine = actualIsMine,
                                                    isConsecutive = isConsecutive
                                                )
                                            }
                                        }
                                    }
                                }

                                    // For others' messages, show read receipts on the right of the bubble
                                    if (!actualIsMine && readReceipts.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        AnimatedInlineReadReceiptAvatars(
                                            receipts = readReceipts,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            appViewModel = appViewModel,
                                            messageSender = event.sender,
                                            eventId = event.eventId,
                                            onUserClick = onUserClick
                                        )
                                }
                            }

                            // Add reaction badges for encrypted text message
                            if (appViewModel != null) {
                                val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                                }
                                if (reactions.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement =
                                            if (actualIsMine) Arrangement.End else Arrangement.Start
                                    ) {
                                        ReactionBadges(
                                            eventId = event.eventId,
                                            reactions = reactions,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken
                                        )
                                    }
                                }
                            }
                        }
                    } else if (decryptedType == "m.sticker") {
                        // Handle encrypted stickers
                        val stickerMessage = extractStickerFromEvent(event)

                        if (stickerMessage != null) {
                            Log.d(
                                "Andromuks",
                                "TimelineEventItem: Found encrypted sticker - url=${stickerMessage.url}, body=${stickerMessage.body}, dimensions=${stickerMessage.width}x${stickerMessage.height}"
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    if (actualIsMine) Arrangement.End else Arrangement.Start,
                                verticalAlignment = Alignment.Top
                            ) {
                                // For my messages, show read receipts on the left of the bubble
                                if (actualIsMine && readReceipts.isNotEmpty()) {
                                    AnimatedInlineReadReceiptAvatars(
                                        receipts = readReceipts,
                                        userProfileCache = userProfileCache,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken,
                                        appViewModel = appViewModel,
                                        messageSender = event.sender,
                                        eventId = event.eventId,
                                        onUserClick = onUserClick
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                StickerMessage(
                                        stickerMessage = stickerMessage,
                                        homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                        authToken = authToken,
                                        isMine = actualIsMine,
                                        isEncrypted =
                                            stickerMessage
                                                .hasEncryptedFile, // Use media encryption status
                                        event = event,
                                        timestamp = event.timestamp,
                                        isConsecutive = isConsecutive,
                                        onReply = { onReply(event) },
                                        onReact = { onReact(event) },
                                        onEdit = { onEdit(event) },
                                        onDelete = { onDelete(event) },
                                        myUserId = myUserId,
                                        powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                        appViewModel = appViewModel,
                                        onBubbleClick = if (event.isThreadMessage()) {
                                            { onThreadClick(event) }
                                        } else {
                                            null
                                        }
                                    )

                                    // For other users' messages, show read receipts on the right
                                    if (!actualIsMine && readReceipts.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        AnimatedInlineReadReceiptAvatars(
                                            receipts = readReceipts,
                                            userProfileCache = userProfileCache,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            appViewModel = appViewModel,
                                            messageSender = event.sender,
                                            eventId = event.eventId,
                                            onUserClick = onUserClick
                                        )
                                }
                            }

                            // Add reaction badges for encrypted stickers
                            if (appViewModel != null) {
                                val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                                }
                                if (reactions.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement =
                                            if (actualIsMine) Arrangement.End else Arrangement.Start
                                    ) {
                                        ReactionBadges(
                                            eventId = event.eventId,
                                            reactions = reactions,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken
                                        )
                                    }
                                }
                            }
                        } else {
                            Log.w(
                                "Andromuks",
                                "TimelineEventItem: Failed to extract encrypted sticker data from event ${event.eventId}"
                            )
                        }
                    } else {
                        Text(
                            text = "Encrypted message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                "m.sticker" -> {
                    // Handle unencrypted stickers
                    val stickerMessage = extractStickerFromEvent(event)

                    if (stickerMessage != null) {
                        Log.d(
                            "Andromuks",
                            "TimelineEventItem: Found unencrypted sticker - url=${stickerMessage.url}, body=${stickerMessage.body}, dimensions=${stickerMessage.width}x${stickerMessage.height}"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                if (actualIsMine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            // For my messages, show read receipts on the left of the bubble
                            if (actualIsMine && readReceipts.isNotEmpty()) {
                                AnimatedInlineReadReceiptAvatars(
                                    receipts = readReceipts,
                                    userProfileCache = userProfileCache,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    appViewModel = appViewModel,
                                    messageSender = event.sender,
                                    eventId = event.eventId,
                                    onUserClick = onUserClick
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            StickerMessage(
                                stickerMessage = stickerMessage,
                                homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                                authToken = authToken,
                                isMine = actualIsMine,
                                isEncrypted =
                                    stickerMessage.hasEncryptedFile, // Use media encryption status
                                event = event,
                                timestamp = event.timestamp,
                                isConsecutive = isConsecutive,
                                onReply = { onReply(event) },
                                onReact = { onReact(event) },
                                onEdit = { onEdit(event) },
                                onDelete = { onDelete(event) },
                                myUserId = myUserId,
                                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                                appViewModel = appViewModel,
                                onBubbleClick = if (event.isThreadMessage()) {
                                    { onThreadClick(event) }
                                } else {
                                    null
                                }
                            )

                            // For other users' messages, show read receipts on the right
                            if (!actualIsMine && readReceipts.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AnimatedInlineReadReceiptAvatars(
                                    receipts = readReceipts,
                                    userProfileCache = userProfileCache,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    appViewModel = appViewModel,
                                    messageSender = event.sender,
                                    eventId = event.eventId,
                                    onUserClick = onUserClick
                                )
                                }
                            }

                        // Add reaction badges for stickers
                        if (appViewModel != null) {
                            val reactions = remember(appViewModel.updateCounter, event.eventId) {
                                appViewModel.messageReactions[event.eventId] ?: emptyList()
                            }
                            if (reactions.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement =
                                        if (actualIsMine) Arrangement.End else Arrangement.Start
                                ) {
                                    ReactionBadges(
                                        eventId = event.eventId,
                                        reactions = reactions,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken
                                    )
                                }
                            }
                        }
                    } else {
                        Log.w(
                            "Andromuks",
                            "TimelineEventItem: Failed to extract sticker data from event ${event.eventId}"
                        )
                    }
                }
                "m.reaction" -> {
                    // Reaction events are processed by processReactionEvent and displayed as badges
                    // on messages
                    // No need to render them as separate timeline items
                    return
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

/**
 * Helper composable to render media messages with proper positioning.
 * Extracted to reduce complexity of TimelineEventItem function.
 */
@Composable
private fun MediaMessageItem(
    mediaMessage: MediaMessage,
    replyInfo: ReplyInfo?,
    originalEvent: TimelineEvent?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    hasEncryptedFile: Boolean,
    event: TimelineEvent,
    isConsecutive: Boolean,
    editedBy: TimelineEvent?,
    timelineEvents: List<TimelineEvent>,
    onScrollToMessage: (String) -> Unit,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUserClick: (String) -> Unit,
    appViewModel: AppViewModel? = null,
    myUserId: String? = null,
    powerLevels: PowerLevelsInfo? = null,
    onBubbleClick: (() -> Unit)? = null
) {
    // Check if this is a thread message
    val isThreadMessage = event.isThreadMessage()
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show nested reply preview only for non-thread messages
        // Thread messages are rendered without nested reply (different color instead)
        if (replyInfo != null && originalEvent != null && !isThreadMessage) {
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
                    timelineEvents = timelineEvents,
                    onMatrixUserClick = onUserClick,
                    appViewModel = appViewModel
                )
                MediaMessage(
                    mediaMessage = mediaMessage,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isMine = isMine,
                    isEncrypted = hasEncryptedFile,
                    event = event,
                    timestamp = event.timestamp,
                    isConsecutive = isConsecutive,
                    editedBy = editedBy,
                    onReply = onReply,
                    onReact = onReact,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onUserClick = onUserClick,
                    myUserId = myUserId,
                    powerLevels = powerLevels,
                    appViewModel = appViewModel,
                    onBubbleClick = onBubbleClick
                )
            }
        } else {
            MediaMessage(
                mediaMessage = mediaMessage,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isMine = isMine,
                isEncrypted = hasEncryptedFile,
                event = event,
                timestamp = event.timestamp,
                isConsecutive = isConsecutive,
                editedBy = editedBy,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                onUserClick = onUserClick,
                myUserId = myUserId,
                powerLevels = powerLevels,
                appViewModel = appViewModel,
                onBubbleClick = onBubbleClick
            )
        }
    }
}

@Composable
fun RoomHeader(
    roomState: RoomState?,
    fallbackName: String,
    fallbackAvatarUrl: String? = null,
    homeserverUrl: String,
    authToken: String,
    roomId: String? = null,
    onHeaderClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {}
) {
    // Debug logging
    android.util.Log.d("Andromuks", "RoomHeader: roomState = $roomState")
    android.util.Log.d("Andromuks", "RoomHeader: fallbackName = $fallbackName")
    android.util.Log.d("Andromuks", "RoomHeader: homeserverUrl = $homeserverUrl")
    android.util.Log.d("Andromuks", "RoomHeader: authToken = ${authToken.take(10)}...")
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Room avatar (clickable for room info)
            Box(modifier = Modifier.clickable(onClick = onHeaderClick)) {
                AvatarImage(
                    mxcUrl = roomState?.avatarUrl ?: fallbackAvatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = roomState?.name ?: fallbackName,
                    size = 48.dp,
                    userId = roomId ?: roomState?.roomId,
                    displayName = roomState?.name ?: fallbackName
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Room info (clickable for room info)
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onHeaderClick)
            ) {
                // Canonical alias or room ID (above room name)
                val aliasOrId = roomState?.canonicalAlias ?: roomState?.roomId?.let { "!$it" }

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
                    softWrap = false,
                    modifier = Modifier.padding(top = if (aliasOrId != null) 2.dp else 0.dp)
                )
            }
            
            // Refresh button on the far right
            IconButton(onClick = onRefreshClick) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Refresh,
                    contentDescription = "Refresh timeline",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
