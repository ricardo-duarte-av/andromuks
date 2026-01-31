package net.vrkknn.andromuks

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.LocalScrollHighlightState
import net.vrkknn.andromuks.ScrollHighlightState
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.BridgeBackgroundLayer
import net.vrkknn.andromuks.ui.components.BridgeNetworkBadge
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ContainedExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ExpressiveStatusRow
import net.vrkknn.andromuks.ui.components.ContainedExpressiveLoadingIndicator
import net.vrkknn.andromuks.utils.CustomBubbleTextField
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.StickerSelectionDialog
import net.vrkknn.andromuks.utils.EmoteEventNarrator
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.CodeViewer
import net.vrkknn.andromuks.utils.InlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.AnimatedInlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.navigateToUserInfo
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.MediaPreviewDialog
import net.vrkknn.andromuks.utils.MediaUploadUtils
import net.vrkknn.andromuks.utils.MessageBubbleWithMenu
import net.vrkknn.andromuks.utils.MessageSoundPlayer
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
import net.vrkknn.andromuks.utils.EmojiShortcodes
import net.vrkknn.andromuks.utils.EmojiSuggestionList
import net.vrkknn.andromuks.BuildConfig


/** Sealed class for timeline items (events and date dividers) */
sealed class BubbleTimelineItem {
    // PERFORMANCE: Stable key for LazyColumn items
    abstract val stableKey: String
    
    data class Event(
        val event: TimelineEvent,
        val isConsecutive: Boolean = false,
        val hasPerMessageProfile: Boolean = false
    ) : BubbleTimelineItem() {
        override val stableKey: String
            get() = event.transactionId
                ?: event.localContent?.optString("transaction_id")?.takeIf { it.isNotBlank() }
                ?: event.unsigned?.optString("transaction_id")?.takeIf { it.isNotBlank() }
                ?: event.eventId
    }

    data class DateDivider(val date: String) : BubbleTimelineItem() {
        override val stableKey: String get() = "date_$date"
    }
}

/** PERFORMANCE: Helper function to process timeline events in background */
suspend fun bubbleProcessTimelineEvents(
    timelineEvents: List<TimelineEvent>,
    allowedEventTypes: Set<String>
): List<TimelineEvent> = withContext(Dispatchers.Default) {
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "BubbleTimelineScreen: Background processing ${timelineEvents.size} timeline events"
    )

    // Debug: Log event types in timeline
    val eventTypes = timelineEvents.groupBy { it.type }
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "BubbleTimelineScreen: Event types in timeline: ${eventTypes.map { "${it.key}: ${it.value.size}" }.joinToString(", ")}"
    )

    val filteredEvents = timelineEvents.filter { event ->
        // Filter out redaction events
        if (event.type == "m.room.redaction") return@filter false
        // Filter out org.matrix.msc4075.* events (call notifications - should be hidden)
        if (event.type.startsWith("org.matrix.msc4075.") || 
            event.decryptedType?.startsWith("org.matrix.msc4075.") == true) {
            return@filter false
        }
        // Only allow events in the whitelist
        allowedEventTypes.contains(event.type)
    }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: After type filtering: ${filteredEvents.size} events")

    // PERFORMANCE: Optimize edit filtering by creating a lookup set
    val editedEventIds = filteredEvents.filter { event ->
        event.type == "m.room.message" &&
        event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
    }.mapNotNull { event ->
        event.content?.optJSONObject("m.relates_to")?.optString("event_id")?.takeIf { it.isNotBlank() }
    }.toSet()

    // Filter out superseded events using the lookup set for O(1) performance
    val eventsWithoutSuperseded = filteredEvents.filter { event ->
        if (event.type == "m.room.message") {
            val isSuperseded = editedEventIds.contains(event.eventId)
            if (isSuperseded) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Filtering out edited event: ${event.eventId}")
            }
            !isSuperseded
        } else {
            true // Keep non-message events
        }
    }

    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: After edit filtering: ${eventsWithoutSuperseded.size} events")

    val sorted = eventsWithoutSuperseded.sortedBy { it.timestamp }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Final sorted events: ${sorted.size} events")

    sorted
}

/** Floating member list for mentions */
@Composable
fun BubbleMentionMemberList(
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
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp), // Rounder corners
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

/** Date divider component for timeline events */
@Composable
fun BubbleDateDivider(date: String) {
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

// NOTE: Keep this screen in sync with `RoomTimelineScreen`. Any structural or data-flow changes
// should be mirrored in both places. See `docs/BUBBLE_IMPLEMENTATION.md` for architectural details.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun BubbleTimelineScreen(
    roomId: String,
    roomName: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    onCloseBubble: () -> Unit = {},
    onMinimizeBubble: () -> Unit = {},
    onOpenInApp: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val messageSoundPlayer =
        remember(appContext) {
            MessageSoundPlayer(appContext)
        }
    DisposableEffect(messageSoundPlayer) {
        onDispose { messageSoundPlayer.release() }
    }
    
    // Track bubble lifecycle for notification dismissal logic
    // NOTE: This is redundant with Activity-level tracking but provides early detection
    // Activity-level tracking in ChatBubbleActivity.onDestroy() is the authoritative source
    DisposableEffect(roomId) {
        // Only track if not already tracked (Activity might have already tracked it)
        if (!BubbleTracker.isBubbleOpen(roomId)) {
            BubbleTracker.onBubbleOpened(roomId)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Tracked bubble opened for room: $roomId (Composable level)")
        }
        // When the screen is composed, the bubble is visible
        BubbleTracker.onBubbleVisible(roomId)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Tracked bubble visible for room: $roomId")
        onDispose {
            // When the screen is disposed, the bubble is no longer visible
            // NOTE: Activity.onDestroy() will also close it, but this provides early detection
            // However, Activity-level tracking is authoritative for FCMService checks
            BubbleTracker.onBubbleInvisible(roomId)
            // Only close if Activity hasn't already closed it (check before closing)
            if (BubbleTracker.isBubbleOpen(roomId)) {
                BubbleTracker.onBubbleClosed(roomId)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Tracked bubble closed for room: $roomId (Composable disposal)")
            } else {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Bubble already closed (likely by Activity) for room: $roomId")
            }
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences = remember(context) {
        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    }
    val authToken = remember(sharedPreferences) {
        sharedPreferences.getString("gomuks_auth_token", "") ?: ""
    }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val storedUserId = remember(sharedPreferences) {
        sharedPreferences.getString("current_user_id", "") ?: ""
    }
    val myUserId = appViewModel.currentUserId.ifBlank { storedUserId }
    val homeserverUrl = appViewModel.homeserverUrl
    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: appViewModel instance: $appViewModel")
    // PERFORMANCE FIX: Use timelineEvents directly instead of pre-rendered flow.
    // Pre-rendering on every sync was causing heavy CPU load with 580+ rooms.
    // Timeline is now rendered lazily when room is opened via processCachedEvents().
    val timelineEvents = appViewModel.timelineEvents
    val isLoading = appViewModel.isTimelineLoading
    var readinessCheckComplete by remember { mutableStateOf(false) }

    // Get the room item to check if it's a DM and get proper display name
    val roomItem = appViewModel.getRoomById(roomId)
    val isDirectMessage = roomItem?.isDirectMessage ?: false

    // For DM rooms, calculate the display name from member profiles
    // Note: isDirectMessage can only be true if roomItem is not null, so roomItem != null check is redundant
    val displayRoomName =
        if (isDirectMessage) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val otherParticipant = memberMap.keys.find { it != myUserId }
            val otherProfile = otherParticipant?.let { memberMap[it] }
            otherProfile?.displayName ?: otherParticipant ?: roomName
        } else {
            roomName
        }

    // For DM rooms, get the avatar from the other participant
    // CRITICAL FIX: Use roomItem.avatarUrl as fallback (like RoomListScreen does)
    // This ensures avatars show even if member map isn't populated yet
    // Note: isDirectMessage can only be true if roomItem is not null, so roomItem != null check is redundant
    val displayAvatarUrl =
        if (isDirectMessage) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val otherParticipant = memberMap.keys.find { it != myUserId }
            val otherProfile = otherParticipant?.let { memberMap[it] }
            // Use member profile avatar, fallback to roomItem avatar, then room state avatar
            otherProfile?.avatarUrl ?: roomItem.avatarUrl ?: appViewModel.currentRoomState?.avatarUrl
        } else {
            // For group rooms, use roomItem avatar as fallback (like RoomListScreen)
            roomItem?.avatarUrl ?: appViewModel.currentRoomState?.avatarUrl
        }

    // Permission to send messages based on power levels
    val canSendMessage = remember(appViewModel.currentRoomState, myUserId) {
        val pl = appViewModel.currentRoomState?.powerLevels ?: return@remember true
        val me = myUserId
        if (me.isNullOrBlank()) return@remember true
        val myPl = pl.users[me] ?: pl.usersDefault
        val required = pl.events["m.room.message"] ?: pl.eventsDefault
        myPl >= required
    }
    
    // Track websocket connection state
    var websocketConnected by remember { mutableStateOf(appViewModel.isWebSocketConnected()) }
    LaunchedEffect(Unit) {
        while (true) {
            websocketConnected = appViewModel.isWebSocketConnected()
            kotlinx.coroutines.delay(500) // Poll every 500ms
        }
    }
    
    // Combined input enabled state (permission AND websocket connection)
    val isInputEnabled = canSendMessage && websocketConnected

    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "BubbleTimelineScreen: Timeline events count: ${timelineEvents.size}, isLoading: $isLoading, websocketConnected: $websocketConnected"
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
    
    // Emoji selection state for text input
    var showEmojiPickerForText by remember { mutableStateOf(false) }
    
    // Sticker selection state for text input
    var showStickerPickerForText by remember { mutableStateOf(false) }
    
    // Code viewer state
    var showCodeViewer by remember { mutableStateOf(false) }
    var codeViewerContent by remember { mutableStateOf("") }

    // Media picker state
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMediaIsVideo by remember { mutableStateOf(false) }
    var showMediaPreview by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    
    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    
    // Camera state
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var cameraVideoUri by remember { mutableStateOf<Uri?>(null) }
    
    // Room joiner state
    var showRoomJoiner by remember { mutableStateOf(false) }
    var roomLinkToJoin by remember { mutableStateOf<RoomLink?>(null) }
    
    // Mention state
    var showMentionList by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var isWaitingForFullMemberList by remember { mutableStateOf(false) }
    var lastMemberUpdateCounterBeforeMention by remember { mutableStateOf(appViewModel.memberUpdateCounter) }
    
    // Emoji shortcode ( :shortname: ) state
    var showEmojiSuggestionList by remember { mutableStateOf(false) }
    var emojiQuery by remember { mutableStateOf("") }
    var emojiStartIndex by remember { mutableStateOf(-1) }

    // Scroll highlight state for jump-to-message interactions
    var highlightedEventId by remember(roomId) { mutableStateOf<String?>(null) }
    var highlightRequestId by remember(roomId) { mutableStateOf(0) }
    var pendingNotificationJumpEventId by remember(roomId) {
        mutableStateOf(appViewModel.consumePendingHighlightEvent(roomId))
    }

    LaunchedEffect(highlightRequestId, highlightedEventId) {
        val currentRequest = highlightRequestId
        if (highlightedEventId != null && currentRequest > 0) {
            kotlinx.coroutines.delay(1600)
            if (highlightRequestId == currentRequest) {
                highlightedEventId = null
            }
        }
    }
    
    // Text input state (moved here to be accessible by mention handler)
    var draft by remember { mutableStateOf("") }
    var lastTypingTime by remember { mutableStateOf(0L) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    
    // Track text field height to match button heights
    var textFieldHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val buttonHeight = remember(textFieldHeight) {
        if (textFieldHeight > 0) {
            with(density) { textFieldHeight.toDp() }
        } else {
            40.dp // Fallback height (will be updated when text field is measured)
        }
    }
    
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
            isWaitingForFullMemberList = false
        }
    }
    
    // Show mention list when full member list is loaded
    LaunchedEffect(appViewModel.memberUpdateCounter, isWaitingForFullMemberList) {
        if (isWaitingForFullMemberList && appViewModel.memberUpdateCounter > lastMemberUpdateCounterBeforeMention) {
            // Full member list has been loaded, now show the mention list
            val memberMap = appViewModel.getMemberMap(roomId)
            if (memberMap.isNotEmpty()) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Full member list loaded (${memberMap.size} members), showing mention list")
                showMentionList = true
                isWaitingForFullMemberList = false
            }
        }
    }
    
    // Hide attachment menu when editing or replying starts
    LaunchedEffect(editingEvent, replyingToEvent) {
        if (editingEvent != null || replyingToEvent != null) {
            showAttachmentMenu = false
        }
    }

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

    // Helper function to check if we need to request media permissions
    fun needsMediaPermissions(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    // Helper function to check if we have the necessary permissions for a specific picker type
    fun hasRequiredMediaPermissions(pickerType: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (pickerType) {
                "image" -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                "audio" -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                "file" -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                else -> false
            }
        } else {
            true // No need for these permissions on older Android versions
        }
    }

    // State to track which picker we're trying to launch after permission request
    var pendingMediaPickerType by remember { mutableStateOf("") }

    // Media picker launcher - accepts both images and videos
    val mediaPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
            uri: Uri? ->
            uri?.let {
                // Check if this is an image or video file
                val mimeType = context.contentResolver.getType(it)
                val isImageOrVideo = mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true
                
                if (isImageOrVideo) {
                    selectedMediaUri = it
                    // Detect if this is a video or image
                    selectedMediaIsVideo = mimeType?.startsWith("video/") == true
                    showMediaPreview = true
                } else {
                    // Show error message for non-image/video files
                    android.widget.Toast.makeText(
                        context,
                        "Please select an image or video file",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    
    // Audio picker launcher
    val audioPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
            uri: Uri? ->
            uri?.let {
                selectedAudioUri = it
                showMediaPreview = true
            }
        }
    
    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
            uri: Uri? ->
            uri?.let {
                selectedFileUri = it
                // Detect if this is a video file
                val mimeType = context.contentResolver.getType(it)
                selectedMediaIsVideo = mimeType?.startsWith("video/") == true
                showMediaPreview = true
            }
        }
    
    // Camera photo launcher
    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraPhotoUri != null) {
            cameraPhotoUri?.let { uri ->
                selectedMediaUri = uri
                selectedMediaIsVideo = false
                showMediaPreview = true
            }
        }
        cameraPhotoUri = null
    }
    
    // Camera video launcher
    val cameraVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success && cameraVideoUri != null) {
            cameraVideoUri?.let { uri ->
                selectedMediaUri = uri
                selectedMediaIsVideo = true
                showMediaPreview = true
            }
        }
        cameraVideoUri = null
    }
    
    // Helper function to create camera file URI
    fun createCameraFileUri(isVideo: Boolean): Uri? {
        return try {
            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = if (isVideo) "VID_${timeStamp}.mp4" else "IMG_${timeStamp}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES)
                }
            }
            context.contentResolver.insert(
                if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
        } catch (e: Exception) {
            Log.e("Andromuks", "Error creating camera file URI", e)
            null
        }
    }
    
    // Camera permission launcher for photo
    val cameraPhotoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createCameraFileUri(false) // Photo
            if (uri != null) {
                cameraPhotoUri = uri
                cameraPhotoLauncher.launch(uri)
                showAttachmentMenu = false
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Error creating camera file",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.widget.Toast.makeText(
                context,
                "Camera permission is required to take photos",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // Camera permission launcher for video
    val cameraVideoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createCameraFileUri(true) // Video
            if (uri != null) {
                cameraVideoUri = uri
                cameraVideoLauncher.launch(uri)
                showAttachmentMenu = false
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Error creating camera file",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.widget.Toast.makeText(
                context,
                "Camera permission is required to record videos",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // Helper function to launch camera
    fun launchCamera(isVideo: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val uri = createCameraFileUri(isVideo)
            if (uri != null) {
                if (isVideo) {
                    cameraVideoUri = uri
                    cameraVideoLauncher.launch(uri)
                } else {
                    cameraPhotoUri = uri
                    cameraPhotoLauncher.launch(uri)
                }
                showAttachmentMenu = false
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Error creating camera file",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            if (isVideo) {
                cameraVideoPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                cameraPhotoPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Permission request launcher for media permissions
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasImagesPermission = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        val hasVideoPermission = permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        val hasAudioPermission = permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false
        
        // Check if we have the necessary permissions for the requested picker type
        val hasRequiredPermissions = when (pendingMediaPickerType) {
            "image" -> hasImagesPermission && hasVideoPermission
            "audio" -> hasAudioPermission
            "file" -> hasImagesPermission && hasVideoPermission && hasAudioPermission
            else -> false
        }
        
        // If permissions are granted, launch the appropriate picker
        if (hasRequiredPermissions) {
            when (pendingMediaPickerType) {
                "image" -> mediaPickerLauncher.launch("image/*,video/*")
                "audio" -> audioPickerLauncher.launch("audio/*")
                "file" -> filePickerLauncher.launch("*/*")
            }
        }
    }

    // Helper function to launch picker with permission check
    fun launchPickerWithPermission(pickerType: String, mimeType: String) {
        if (needsMediaPermissions() && !hasRequiredMediaPermissions(pickerType)) {
            // Need to request permissions first
            pendingMediaPickerType = pickerType
            when (pickerType) {
                "image" -> {
                    mediaPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ))
                }
                "audio" -> {
                    // For audio, we only need to request audio permission
                    mediaPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_AUDIO
                    ))
                }
                "file" -> {
                    // For files, request all media permissions as files can be anything
                    mediaPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                    ))
                }
            }
        } else {
            // Permissions already granted, launch directly
            when (pickerType) {
                "image" -> mediaPickerLauncher.launch(mimeType)
                "audio" -> audioPickerLauncher.launch(mimeType)
                "file" -> filePickerLauncher.launch(mimeType)
            }
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
                    val rawDisplay = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                    val fallbackHandle = userId.removePrefix("@").substringBefore(":")
                    val displayName = rawDisplay ?: fallbackHandle
                    val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                    map[userId] = Pair(displayName, avatarUrl)
                }
            }
            map
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

    // Emoji shortcode detection function (for ':' based autocomplete)
    fun detectEmojiShortcode(text: String, cursorPosition: Int): Pair<String, Int>? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null
        
        // Look for ':' at or before cursor position
        var colonIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            val c = text[i]
            if (c == ':') {
                colonIndex = i
                break
            }
            // Stop if we hit a delimiter before finding ':'
            if (c == ' ' || c == '\n' || c == '\t') {
                break
            }
        }
        
        if (colonIndex == -1) return null
        
        // Ensure ':' is at start of text or preceded by whitespace/newline
        if (colonIndex > 0) {
            val prev = text[colonIndex - 1]
            if (prev != ' ' && prev != '\n' && prev != '\t') {
                return null
            }
        }
        
        val queryStart = colonIndex + 1
        var queryEnd = cursorPosition
        
        // Stop query at next delimiter or second ':'
        if (cursorPosition < text.length) {
            for (i in cursorPosition until text.length) {
                val c = text[i]
                if (c == ' ' || c == '\n' || c == '\t' || c == ':') {
                    break
                }
                queryEnd = i + 1
            }
        }
        
        if (queryStart <= cursorPosition) {
            val safeEnd = min(queryEnd, text.length)
            val query =
                if (queryStart < safeEnd) text.substring(queryStart, safeEnd) else ""
            return Pair(query, colonIndex)
        }
        
        return null
    }

    // Handle backspace deletion of custom emoji markdown
    fun handleCustomEmojiDeletion(
        oldValue: TextFieldValue,
        newValue: TextFieldValue
    ): TextFieldValue {
        // Check if text was deleted (backspace was pressed)
        if (newValue.text.length >= oldValue.text.length) return newValue
        
        val oldText = oldValue.text
        val newText = newValue.text
        val cursor = newValue.selection.start
        val deletedLength = oldText.length - newText.length
        
        // Regex for custom emoji markdown: ![:name:](mxc://url "Emoji: :name:")
        val customEmojiRegex = Regex("""!\[:([^:]+):\]\((mxc://[^)]+)\s+"[^"]*"\)""")
        
        // Find all custom emoji markdowns in the old text
        val matches = customEmojiRegex.findAll(oldText).toList()
        
        // Check if cursor is within or right after a custom emoji markdown
        for (match in matches) {
            val markdownStart = match.range.first
            val markdownEnd = match.range.last + 1
            
            // Check if cursor is strictly within the markdown range (user is deleting from within the markdown)
            // Only trigger if cursor is inside the markdown, not at the boundary
            if (cursor >= markdownStart && cursor < markdownEnd && deletedLength == 1) {
                // User is deleting the custom emoji, remove the entire markdown
                val beforeMarkdown = oldText.substring(0, markdownStart)
                val afterMarkdown = oldText.substring(markdownEnd)
                val finalText = beforeMarkdown + afterMarkdown
                val finalCursor = markdownStart
                
                return TextFieldValue(
                    text = finalText,
                    selection = TextRange(finalCursor)
                )
            }
        }
        
        return newValue
    }

    // Replace completed :shortcode: with its emoji/custom emoji representation
    fun applyCompletedEmojiShortcode(
        value: TextFieldValue
    ): TextFieldValue {
        val text = value.text
        val cursor = value.selection.start
        if (cursor <= 0 || cursor > text.length) return value
        if (text[cursor - 1] != ':') return value
        
        // Find matching opening ':'
        var start = cursor - 2
        while (start >= 0) {
            val c = text[start]
            if (c == ':') {
                break
            }
            if (c == ' ' || c == '\n' || c == '\t') {
                return value
            }
            start--
        }
        
        if (start < 0 || text[start] != ':') return value
        
        val nameStart = start + 1
        val nameEnd = cursor - 1
        if (nameEnd <= nameStart) return value
        
        val shortcode = text.substring(nameStart, nameEnd)
        val suggestion =
            EmojiShortcodes.findByShortcode(shortcode, appViewModel.customEmojiPacks)
                ?: return value
        
        val replacement =
            suggestion.emoji
                ?: suggestion.customEmoji?.let { custom ->
                    "![:${custom.name}:](${custom.mxcUrl} \"Emoji: :${custom.name}:\")"
                }
                ?: return value
        
        val newText =
            text.substring(0, start) + replacement + text.substring(cursor)
        val newCursorPos = start + replacement.length
        
        return TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos)
        )
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

    // PERFORMANCE: Use background processing for heavy filtering and sorting operations
    var sortedEvents by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    
    // Process timeline events in background when dependencies change
    LaunchedEffect(timelineEvents, appViewModel.timelineUpdateCounter) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "BubbleTimelineScreen: Processing timelineEvents update - size=${timelineEvents.size}, updateCounter=${appViewModel.timelineUpdateCounter}, roomId=$roomId"
        )
        sortedEvents = bubbleProcessTimelineEvents(
            timelineEvents = timelineEvents,
            allowedEventTypes = allowedEventTypes
        )
    }

    // PERFORMANCE: Create timeline items with date dividers and pre-compute consecutive flags
    val timelineItems =
        remember(sortedEvents, appViewModel.timelineUpdateCounter) {
            val items = mutableListOf<BubbleTimelineItem>()
            var lastDate: String? = null
            var previousEvent: TimelineEvent? = null

            for (event in sortedEvents) {
                if (event.type == "m.reaction") {
                    // Reactions mutate their target event and should not render as standalone timeline items
                    continue
                }

                // Format date inline to avoid @Composable context issue
                val date = Date(event.timestamp)
                val formatter = SimpleDateFormat("dd / MM / yyyy", Locale.getDefault())
                val eventDate = formatter.format(date)

                // Add date divider if this is a new date
                if (lastDate == null || eventDate != lastDate) {
                    items.add(BubbleTimelineItem.DateDivider(eventDate))
                    lastDate = eventDate
                    // Date divider breaks consecutive grouping
                    previousEvent = null
                }

                // Check if this event has per-message profile (from bridges like Beeper)
                val hasPerMessageProfile = 
                    event.content?.has("com.beeper.per_message_profile") == true ||
                    event.decrypted?.has("com.beeper.per_message_profile") == true

                // Check if this is a consecutive message from the same sender
                val isConsecutive = !hasPerMessageProfile && 
                    previousEvent?.sender == event.sender

                // Add the event with pre-computed flags
                items.add(BubbleTimelineItem.Event(
                    event = event,
                    isConsecutive = isConsecutive,
                    hasPerMessageProfile = hasPerMessageProfile
                ))

                previousEvent = event
            }
            items
        }
    var lastInitialScrollSize by remember(roomId) { mutableStateOf(0) }

    // Get member map that observes memberUpdateCounter and includes global cache fallback for TimelineEventItem profile updates
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter, sortedEvents) {
        appViewModel.getMemberMapWithFallback(roomId, sortedEvents)
    }

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()
    
    // Track scroll position using event ID anchor (more robust than index)
    var anchorEventIdForRestore by remember { mutableStateOf<String?>(null) }
    var anchorScrollOffsetForRestore by remember { mutableStateOf(0) }
    var pendingScrollRestoration by remember { mutableStateOf(false) }
    
    // Pull-to-refresh state
    var isRefreshingPull by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingPull,
        onRefresh = {
            // Capture current scroll position before pagination
            // This ensures new events appear above the current viewport
            val firstVisibleInfo = listState.layoutInfo.visibleItemsInfo
                .sortedBy { it.index }
                .firstOrNull { info ->
                    val item = timelineItems.getOrNull(info.index)
                    item is BubbleTimelineItem.Event
                }
            val eventItem = firstVisibleInfo?.let { info ->
                timelineItems.getOrNull(info.index) as? BubbleTimelineItem.Event
            }
            
            if (eventItem != null) {
                // Set up scroll restoration to maintain viewport position
                anchorEventIdForRestore = eventItem.event.eventId
                anchorScrollOffsetForRestore = firstVisibleInfo?.offset ?: listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Pull-to-refresh triggered, capturing anchor event: ${anchorEventIdForRestore} at offset ${anchorScrollOffsetForRestore}"
                )
            } else {
                // Fallback: use first visible item index if no event found
                anchorEventIdForRestore = null
                anchorScrollOffsetForRestore = listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Pull-to-refresh triggered, no anchor event found, using scroll offset: ${anchorScrollOffsetForRestore}"
                )
            }
            
            // Use the oldest event from cache, not the oldest rendered event
            // The cache may have events that aren't currently rendered, so we need to use
            // the absolute oldest event to avoid requesting duplicates
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Pull-to-refresh triggered, requesting pagination with oldest cached event")
            isRefreshingPull = true
            appViewModel.requestPaginationWithSmallestRowId(roomId, limit = 100)
        }
    )
    
    // Monitor pagination state to stop refresh indicator
    // Note: Refresh indicator is now cleared in scroll restoration LaunchedEffect
    // This is kept as a fallback in case scroll restoration doesn't trigger
    LaunchedEffect(appViewModel.isPaginating) {
        if (!appViewModel.isPaginating && isRefreshingPull && !pendingScrollRestoration) {
            isRefreshingPull = false
        }
    }

    // Track if user is "attached" to the bottom (sticky scroll)
    var isAttachedToBottom by remember { mutableStateOf(true) }

    // Track if this is the first load (to avoid animation on initial room open)
    var isInitialLoad by remember { mutableStateOf(true) }
    
    // Track if we're refreshing (to scroll to bottom after refresh)
    var isRefreshing by remember { mutableStateOf(false) }

    // Track loading more state
    var isLoadingMore by remember { mutableStateOf(false) }
    var previousItemCount by remember { mutableStateOf(timelineItems.size) }
    var hasLoadedInitialBatch by remember { mutableStateOf(false) }
    var hasInitialSnapCompleted by remember { mutableStateOf(false) }
    var lastKnownTimelineEventId by remember { mutableStateOf<String?>(null) }
    var hasCompletedInitialLayout by remember { mutableStateOf(false) }
    var pendingInitialScroll by remember { mutableStateOf(true) }
    var hasMarkedAsRead by remember(roomId) { mutableStateOf(false) }

    LaunchedEffect(
        pendingNotificationJumpEventId,
        timelineItems.size,
        readinessCheckComplete,
        appViewModel.timelineUpdateCounter
    ) {
        val targetEventId = pendingNotificationJumpEventId ?: return@LaunchedEffect
        if (!readinessCheckComplete || timelineItems.isEmpty()) {
            return@LaunchedEffect
        }
        val targetIndex = timelineItems.indexOfFirst { item ->
            (item as? BubbleTimelineItem.Event)?.event?.eventId == targetEventId
        }
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            isAttachedToBottom = targetIndex >= timelineItems.lastIndex - 1
            hasInitialSnapCompleted = true
            pendingInitialScroll = false
            highlightedEventId = targetEventId
            highlightRequestId++
            pendingNotificationJumpEventId = null
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Jumped to notification target event=$targetEventId at index=$targetIndex"
            )
        } else if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Pending notification target $targetEventId not yet in timeline (size=${timelineItems.size})"
            )
        }
    }

    // Monitor scroll position to detect if user is at bottom or has detached
    // Monitor scroll position to detect if user is at bottom or has detached
    // REMOVED: Automatic pagination when near top - pagination now only happens via pull-to-refresh
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size) {
        // Don't trigger pagination until initial scroll to bottom is complete
        if (!hasInitialSnapCompleted || !hasLoadedInitialBatch) {
            return@LaunchedEffect
        }
        if (sortedEvents.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
            // Check if we're at the very bottom (last item is visible)
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastTimelineItemIndex = timelineItems.lastIndex
            val isAtBottom = lastVisibleIndex >= lastTimelineItemIndex - 1 // Within last item

            if (!hasCompletedInitialLayout) {
                hasCompletedInitialLayout = true
            }

            // Update attachment state based on current position
            if (isAtBottom && !isAttachedToBottom) {
                // User scrolled back to bottom, re-attach
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: User reached bottom, re-attaching")
                isAttachedToBottom = true
                if (!hasInitialSnapCompleted) {
                    hasInitialSnapCompleted = true
                }
            } else if (
                !isAtBottom && isAttachedToBottom && listState.firstVisibleItemIndex > 0
            ) {
                // User scrolled up from bottom, detach
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: User scrolled up, detaching from bottom")
                isAttachedToBottom = false
            }
            
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val totalItems = timelineItems.size
            
            if (firstVisibleIndex <= 5) {
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Near top (index=$firstVisibleIndex/$totalItems). Auto-pagination disabled; waiting for manual refresh."
                )
            }
        }
    }

    // Track previous pagination state to detect when pagination finishes
    var previousIsPaginating by remember { mutableStateOf(appViewModel.isPaginating) }
    
    // Detect when pagination completes and trigger scroll restoration
    // CRITICAL FIX: Only depend on isPaginating, not timelineItems.size
    // This prevents scroll restoration from triggering when new messages arrive
    LaunchedEffect(appViewModel.isPaginating) {
        val paginationJustFinished = previousIsPaginating && !appViewModel.isPaginating
        previousIsPaginating = appViewModel.isPaginating
        
        // When pagination finishes and we have scroll restoration pending
        if (paginationJustFinished && pendingScrollRestoration) {
            if (anchorEventIdForRestore != null) {
                // Restore to anchor event (preferred method - more accurate)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Pagination completed, restoring scroll to anchor event: $anchorEventIdForRestore")
            
            // Find the index of the anchor event in the new list
            val anchorIndex = timelineItems.indexOfFirst { item ->
                (item as? BubbleTimelineItem.Event)?.event?.eventId == anchorEventIdForRestore
            }
            
            if (anchorIndex >= 0) {
                val targetIndex = anchorIndex
                
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Found anchor event at index $targetIndex, " +
                    "restoring with offset $anchorScrollOffsetForRestore"
                )
                
                // Scroll immediately (we're in a LaunchedEffect coroutine context)
                listState.scrollToItem(targetIndex, anchorScrollOffsetForRestore)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: ✅ Scroll position restored to event at index $targetIndex")
            } else {
                    Log.w("Andromuks", "BubbleTimelineScreen: ⚠️ Could not find anchor event $anchorEventIdForRestore in new timeline, falling back to scroll offset")
                    // Fallback: try to maintain scroll position using offset
                    val currentFirstIndex = listState.firstVisibleItemIndex
                    if (currentFirstIndex >= 0 && currentFirstIndex < timelineItems.size) {
                        listState.scrollToItem(currentFirstIndex, anchorScrollOffsetForRestore)
                    }
                }
            } else {
                // Fallback: restore using scroll offset (when no anchor event was captured)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Pagination completed, restoring scroll using offset: $anchorScrollOffsetForRestore")
                val currentFirstIndex = listState.firstVisibleItemIndex
                if (currentFirstIndex >= 0 && currentFirstIndex < timelineItems.size) {
                    listState.scrollToItem(currentFirstIndex, anchorScrollOffsetForRestore)
                }
            }
            
            // Clear restoration state
            pendingScrollRestoration = false
            anchorEventIdForRestore = null
            anchorScrollOffsetForRestore = 0
            isLoadingMore = false
            isRefreshingPull = false
        }
    }

    LaunchedEffect(isRefreshing, timelineItems.size) {
        if (isRefreshing && timelineItems.isNotEmpty() && !appViewModel.hasPendingTimelineRequest(roomId)) {
            val lastIndex = timelineItems.lastIndex
            if (lastIndex >= 0) {
                listState.scrollToItem(lastIndex, 0)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Manual refresh loaded ${timelineItems.size} items - scrolled to bottom")
            }
            isAttachedToBottom = true
            hasInitialSnapCompleted = true
            hasCompletedInitialLayout = true
            pendingScrollRestoration = false
            isLoadingMore = false
            isRefreshing = false
        }
    }
    
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            kotlinx.coroutines.delay(2000)
            if (isRefreshing && !appViewModel.hasPendingTimelineRequest(roomId)) {
                Log.w("Andromuks", "BubbleTimelineScreen: Manual refresh timeout - marking refresh as complete (no pending requests)")
                isRefreshing = false
            }
        }
    }

    // Track last known timeline update counter to detect when timeline has been built
    var lastKnownTimelineUpdateCounter by remember { mutableStateOf(appViewModel.timelineUpdateCounter) }
    
    // Auto-scroll to bottom only when attached (initial load or new messages while at bottom)
    LaunchedEffect(
        timelineItems.size,
        isLoading,
        appViewModel.isPaginating,
        appViewModel.timelineUpdateCounter,
        pendingNotificationJumpEventId
    ) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "BubbleTimelineScreen: LaunchedEffect - timelineItems.size: ${timelineItems.size}, isLoading: $isLoading, isPaginating: ${appViewModel.isPaginating}, timelineUpdateCounter: ${appViewModel.timelineUpdateCounter}, hasInitialSnapCompleted: $hasInitialSnapCompleted"
        )

        if (pendingNotificationJumpEventId != null) {
            return@LaunchedEffect
        }

        if (isLoading || timelineItems.isEmpty()) {
            return@LaunchedEffect
        }

        val lastEventId = (timelineItems.lastOrNull() as? BubbleTimelineItem.Event)?.event?.eventId

        if (!hasInitialSnapCompleted) {
            coroutineScope.launch {
                // OPTIMIZATION: For initial load, scroll immediately when events are available
                // Don't wait for stability - events from cache/DB are already stable
                // Only wait a brief moment to ensure timeline has been built at least once
                
                // Quick check: wait for timeline to be built (update counter > 0) OR wait max 200ms
                var waitCount = 0
                val maxWaitAttempts = 4 // Max 200ms (4 * 50ms)
                
                while (waitCount < maxWaitAttempts) {
                    val currentUpdateCounter = appViewModel.timelineUpdateCounter
                    val stillLoading = isLoading || appViewModel.isPaginating
                    val hasEvents = timelineItems.isNotEmpty()
                    
                    // Check if timeline has been built at least once (update counter changed from initial)
                    val timelineHasBeenBuilt = currentUpdateCounter != lastKnownTimelineUpdateCounter || currentUpdateCounter > 0
                    
                    // If we have events, timeline is built, and not loading - scroll immediately
                    if (hasEvents && timelineHasBeenBuilt && !stillLoading) {
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Timeline ready for immediate scroll (${timelineItems.size} items, updateCounter: $currentUpdateCounter) after ${waitCount * 50}ms")
                        lastKnownTimelineUpdateCounter = currentUpdateCounter
                        break
                    }
                    
                    // If still loading, wait a bit more
                    if (stillLoading) {
                        kotlinx.coroutines.delay(50)
                        waitCount++
                        continue
                    }
                    
                    // If no events yet but timeline counter changed, wait one more check
                    if (!hasEvents && timelineHasBeenBuilt) {
                        kotlinx.coroutines.delay(50)
                        waitCount++
                        continue
                    }
                    
                    // Otherwise, wait and check again
                    kotlinx.coroutines.delay(50)
                    waitCount++
                }
                
                // Final check before scrolling
                if (timelineItems.isEmpty() || (isLoading && waitCount >= maxWaitAttempts)) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Timeline not ready for scroll (empty: ${timelineItems.isEmpty()}, loading: $isLoading, paginating: ${appViewModel.isPaginating})")
                    // Still mark as completed to avoid infinite loop
                    hasInitialSnapCompleted = true
                    return@launch
                }
                
                // Use the EXACT same method as the FAB button - instant scroll (no animation)
                val targetIndex = timelineItems.lastIndex
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                    isAttachedToBottom = true
                    hasInitialSnapCompleted = true
                    hasLoadedInitialBatch = true
                    previousItemCount = timelineItems.size
                    lastKnownTimelineEventId = lastEventId
                    lastKnownTimelineUpdateCounter = appViewModel.timelineUpdateCounter
                    
                    // CRITICAL: Enable animations AFTER initial load and scroll complete
                    // Animations should only occur for NEW messages when room is already open
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: ✅ Scrolled to bottom on initial load (${timelineItems.size} items, index $targetIndex, updateCounter: ${appViewModel.timelineUpdateCounter}) - immediate scroll, animations enabled")
                } else {
                    hasInitialSnapCompleted = true
                    Log.w("Andromuks", "BubbleTimelineScreen: Invalid target index for scroll")
                }
            }
            return@LaunchedEffect
        }

        val hasNewItems = previousItemCount < timelineItems.size

        // Skip handling new items if we're waiting for scroll restoration after pagination
        if (pendingScrollRestoration) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Skipping new items handling - pending scroll restoration")
            return@LaunchedEffect
        }

        if (
            hasNewItems &&
                isAttachedToBottom &&
                lastEventId != null &&
                lastEventId != lastKnownTimelineEventId
        ) {
            coroutineScope.launch {
                listState.scrollToItem(timelineItems.lastIndex)
            }
            lastKnownTimelineEventId = lastEventId
        }

        if (hasNewItems && lastEventId != null) {
            lastKnownTimelineEventId = lastEventId
        }

        if (!pendingScrollRestoration) {
            previousItemCount = timelineItems.size
        }
    }

    // Auto-scroll after each individual message bubble animation completes
    // This ensures we scroll after each message is rendered, not just when all animations finish
    
    // Mark room as read when initial load completes and last message is rendered
    // CRITICAL: Only depend on hasInitialSnapCompleted and roomId - NOT timelineItems.size
    // Including timelineItems.size causes the effect to restart on every timeline change,
    // leading to hundreds of duplicate mark_read commands
    LaunchedEffect(hasInitialSnapCompleted, roomId) {
        if (hasInitialSnapCompleted && !hasMarkedAsRead && timelineItems.isNotEmpty()) {
            // Get the last event ID from the timeline (find last Event, not DateDivider)
            val lastEvent = timelineItems.lastOrNull() as? BubbleTimelineItem.Event
                ?: timelineItems.reversed().firstOrNull { it is BubbleTimelineItem.Event } as? BubbleTimelineItem.Event
            val lastEventId = lastEvent?.event?.eventId
            
            if (lastEventId != null) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Marking room $roomId as read with last event: $lastEventId")
                appViewModel.markRoomAsRead(roomId, lastEventId)
                hasMarkedAsRead = true
            }
        }
    }
    
    LaunchedEffect(timelineItems.size, readinessCheckComplete, pendingInitialScroll) {
        if (pendingInitialScroll && readinessCheckComplete && timelineItems.isNotEmpty() &&
            timelineItems.size != lastInitialScrollSize) {
            coroutineScope.launch {
                listState.scrollToItem(timelineItems.lastIndex)
                isAttachedToBottom = true
                hasInitialSnapCompleted = true
                pendingInitialScroll = false
                lastInitialScrollSize = timelineItems.size
            }
        }
    }

    LaunchedEffect(roomId) {
        readinessCheckComplete = false
        pendingInitialScroll = true
        lastInitialScrollSize = 0
        highlightedEventId = null
        highlightRequestId = 0
        hasMarkedAsRead = false // Reset mark as read state when room changes
        appViewModel.promoteToPrimaryIfNeeded("bubble_timeline_$roomId")
        appViewModel.navigateToRoomWithCache(roomId)
        
        // CRITICAL: Add room to opened rooms (exempt from cache clearing on WebSocket reconnect)
        RoomTimelineCache.addOpenedRoom(roomId)
        
        val requireInitComplete = !appViewModel.isWebSocketConnected()
        val readinessResult = appViewModel.awaitRoomDataReadiness(requireInitComplete = requireInitComplete, roomId = roomId)
        readinessCheckComplete = true
        if (!readinessResult && BuildConfig.DEBUG) {
            Log.w("Andromuks", "BubbleTimelineScreen: Readiness timeout while opening $roomId - continuing with partial data")
        }
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Loading timeline for room: $roomId")
        // Reset state for new room
        isLoadingMore = false
        pendingScrollRestoration = false
        anchorEventIdForRestore = null
        hasLoadedInitialBatch = false
        isAttachedToBottom = true
        isInitialLoad = true
        hasInitialSnapCompleted = false
        
        // Request room state
        // NOTE: navigateToRoomWithCache() already calls requestRoomTimeline() if cache is empty,
        // so we don't need to call it again here to avoid duplicate paginate requests
        // BubbleTimelineScreen: Don't use LRU cache since bubbles manage their own state independently
        appViewModel.requestRoomState(roomId)
    }
    
    // CRITICAL: Remove room from opened rooms when bubble closes
    DisposableEffect(roomId) {
        onDispose {
            RoomTimelineCache.removeOpenedRoom(roomId)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Removed room $roomId from opened rooms (bubble closed)")
        }
    }
    
    // Track last known refresh trigger to detect when app resumes
    var lastKnownRefreshTrigger by remember { mutableStateOf(appViewModel.timelineRefreshTrigger) }
    var isInitialLoadComplete by remember(roomId) { mutableStateOf(false) }
    
    // Mark initial load as complete after a short delay to distinguish from app resume
    LaunchedEffect(roomId) {
        kotlinx.coroutines.delay(500) // Wait 500ms after room opens
        isInitialLoadComplete = true
    }
    
    // Refresh timeline when app resumes (to show new events received while suspended)
    // Only refresh if initial load is complete (not during initial room opening)
    LaunchedEffect(appViewModel.timelineRefreshTrigger) {
        if (appViewModel.timelineRefreshTrigger > 0 && 
            appViewModel.currentRoomId == roomId && 
            isInitialLoadComplete &&
            appViewModel.timelineRefreshTrigger != lastKnownRefreshTrigger) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: App resumed, refreshing timeline for room: $roomId")
            // Don't reset state flags - this is just a refresh, not a new room load
            appViewModel.requestRoomTimeline(roomId, useLruCache = false)
            lastKnownRefreshTrigger = appViewModel.timelineRefreshTrigger
        }
    }

    // Listen for foreground refresh broadcast to refresh timeline when app comes to foreground
    DisposableEffect(Unit) {
        val foregroundRefreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "net.vrkknn.andromuks.FOREGROUND_REFRESH") {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Received FOREGROUND_REFRESH broadcast, refreshing timeline UI from cache for room: $roomId")
                    // Lightweight timeline refresh from cached data (no network requests)
                    appViewModel.refreshTimelineUI()
                }
            }
        }
        
        val filter = IntentFilter("net.vrkknn.andromuks.FOREGROUND_REFRESH")
        context.registerReceiver(foregroundRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Registered FOREGROUND_REFRESH broadcast receiver")
        
        onDispose {
            try {
                context.unregisterReceiver(foregroundRefreshReceiver)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Unregistered FOREGROUND_REFRESH broadcast receiver")
            } catch (e: Exception) {
                Log.w("Andromuks", "BubbleTimelineScreen: Error unregistering foreground refresh receiver", e)
            }
        }
    }

    // After initial batch loads, automatically load second batch in background
    // LaunchedEffect(hasLoadedInitialBatch) {
    //    if (hasLoadedInitialBatch && sortedEvents.isNotEmpty()) {
    //        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Initial batch loaded, automatically loading second batch")
    //        kotlinx.coroutines.delay(500) // Small delay to let UI settle
    //        appViewModel.loadOlderMessages(roomId)
    //    }
    // }

    // Validate and request missing user profiles when timeline events change
    // This ensures all users in the timeline have complete profile data (display name, avatar)
    // Missing profiles are automatically requested from the server
    LaunchedEffect(sortedEvents) {
        if (sortedEvents.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Validating user profiles for ${sortedEvents.size} events"
            )
            appViewModel.validateAndRequestMissingProfiles(roomId, sortedEvents)
        }
    }

    // OPPORTUNISTIC PROFILE LOADING: Only request profiles when actually needed for rendering
    // This prevents loading 15,000+ profiles upfront for large rooms
    LaunchedEffect(sortedEvents, roomId) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "BubbleTimelineScreen: Using opportunistic profile loading for $roomId (no bulk loading)"
        )
        
        // Only request profiles for users that are actually visible in the timeline
        // This dramatically reduces memory usage for large rooms
        if (sortedEvents.isNotEmpty()) {
            val visibleUsers = sortedEvents.take(50) // Only first 50 events to avoid overwhelming
                .map { it.sender }
                .distinct()
            
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Requesting profiles on-demand for ${visibleUsers.size} visible users (instead of all ${sortedEvents.size} events)"
            )
            
            // Request profiles one by one as needed (including current user if missing)
            visibleUsers.forEach { userId ->
                // Check if profile is missing (including for current user)
                val existingProfile = appViewModel.getUserProfile(userId, roomId)
                if (existingProfile == null || existingProfile.displayName.isNullOrBlank()) {
                    appViewModel.requestUserProfileOnDemand(userId, roomId)
                }
            }
        }
    }

    // Save updated profiles to disk when member cache changes
    // This persists user profile data (display names, avatars) to disk for future app sessions
    // Only save profiles for users involved in the events being processed to avoid performance
    // issues
    LaunchedEffect(appViewModel.memberUpdateCounter) {
        // Only save profiles for users who are actually involved in the current timeline events
        val usersInTimeline = sortedEvents.map { it.sender }.distinct().toSet()
        if (usersInTimeline.isNotEmpty()) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val profilesToSave = usersInTimeline.filter { memberMap.containsKey(it) }
            if (profilesToSave.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Saving ${profilesToSave.size} profiles to disk for users in timeline"
                )
                // Profiles are cached in-memory only - no DB persistence needed
            }
        }
    }

    // Ensure timeline reactively updates when new events arrive from sync
    // OPTIMIZED: Only track timelineEvents changes directly, updateCounter is handled by receipt updates
    LaunchedEffect(timelineEvents) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Timeline events changed - timelineEvents.size: ${timelineEvents.size}, currentRoomId: ${appViewModel.currentRoomId}, roomId: $roomId")
        
        // Only react to changes for the current room
        if (appViewModel.currentRoomId == roomId) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Detected timeline update for current room $roomId with ${timelineEvents.size} events")
            
            // Force recomposition when timeline events change
            // This ensures the UI updates even when battery optimization might skip updates
        }
    }
    
    // CRITICAL FIX: Observe timeline changes reactively using state flows
    // This detects new events that were persisted to DB but might not have triggered timeline updates
    // (e.g., due to race conditions, timing issues, or if events weren't in sync batch)
    // This is event-driven (no polling) and only triggers when DB actually changes
    LaunchedEffect(roomId, appViewModel.currentRoomId) {
        // Only observe when this room is open and not loading
        if (appViewModel.currentRoomId != roomId || isLoading) {
            return@LaunchedEffect
        }
        
        // Events are in-memory cache only - no DB observation needed
        // Timeline updates come from sync_complete and pagination
    }

    // Handle Android back key
    BackHandler {
        if (showAttachmentMenu) {
            // Close attachment menu if open
            showAttachmentMenu = false
        } else {
            onCloseBubble()
        }
    }

    CompositionLocalProvider(
        LocalScrollHighlightState provides ScrollHighlightState(
            eventId = highlightedEventId,
            requestId = highlightRequestId
        )
    ) {
        AndromuksTheme {
            Surface {
                Box(modifier = modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            // CRITICAL FIX: Don't add imePadding to Column - let the window shrink naturally
                            // The weight(1f) timeline will compress when message box takes space with imePadding
                            .then(
                                if (showDeleteDialog) {
                                    Modifier.blur(10.dp)
                                } else {
                                    Modifier
                                }
                            )
                ) {
                    // 1. Room Header (always visible at the top, below status bar)
                    BubbleRoomHeader(
                        roomState = appViewModel.currentRoomState,
                        fallbackName = displayRoomName,
                        fallbackAvatarUrl = displayAvatarUrl,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        roomId = roomId,
                        onHeaderClick = {
                            // CRITICAL FIX: Disable header click in chat bubbles - room_info route doesn't exist
                            // in the bubble navigation graph. Users can open the full app to access room info.
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Header click disabled - room info not available in bubble navigation")
                        },
                        onOpenInApp = onOpenInApp,
                        onCloseBubble = onCloseBubble,
                        onMinimizeBubble = onMinimizeBubble,
                        onRefreshClick = {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Full refresh button clicked for room $roomId")
                            isRefreshing = true
                            appViewModel.setAutoPaginationEnabled(false, "bubble_manual_refresh_ui_$roomId")
                            appViewModel.fullRefreshRoomTimeline(roomId)
                        }
                    )

                    if (appViewModel.notificationActionInProgress) {
                        ExpressiveStatusRow(
                            text = "Completing notification action...",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                        )
                    }

                    // 2. Timeline (compressible, scrollable content)
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .then(
                                if (showAttachmentMenu) {
                                    Modifier.clickable {
                                        // Close attachment menu when tapping outside
                                        showAttachmentMenu = false
                                    }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        BridgeBackgroundLayer(
                            bridgeInfo = appViewModel.currentRoomState?.bridgeInfo,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        if (!readinessCheckComplete || isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    ExpressiveLoadingIndicator(modifier = Modifier.size(80.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Loading timeline...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pullRefresh(pullRefreshState),
                                state = listState,
                            // PERFORMANCE: Optimize for timeline rendering with proper padding and settings
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 8.dp,
                                end = 0.dp,
                                top = 8.dp,
                                bottom = 120.dp // Extra padding at bottom for better scroll performance
                            ),
                            // PERFORMANCE: Enable smooth scrolling optimizations
                            userScrollEnabled = true
                        ) {
                            // Show loading indicator at the top when paginating
                            if (appViewModel.isPaginating) {
                                item(key = "loading_indicator") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ExpressiveLoadingIndicator(
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            // PERFORMANCE: Use stable keys and pre-computed consecutive flags
                            items(
                                items = timelineItems,
                                key = { item -> item.stableKey }
                            ) { item ->
                                when (item) {
                                    is BubbleTimelineItem.DateDivider -> {
                                        BubbleDateDivider(item.date)
                                    }
                                    is BubbleTimelineItem.Event -> {
                                        val event = item.event
                                        // PERFORMANCE: Removed logging from item rendering to improve scroll performance
                                        val isMine = myUserId.isNotBlank() && event.sender == myUserId

                                        // PERFORMANCE: Use pre-computed consecutive flag instead of index-based lookup
                                        val isConsecutive = item.isConsecutive

                                        TimelineEventItem(
                                            event = event,
                                            timelineEvents = timelineEvents,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            userProfileCache = memberMap,
                                            isMine = isMine,
                                            myUserId = myUserId,
                                            isConsecutive = isConsecutive,
                                            appViewModel = appViewModel,
                                            onScrollToMessage = { eventId ->
                                                // PERFORMANCE: Find the index in timelineItems instead of sortedEvents
                                                val index = timelineItems.indexOfFirst { item ->
                                                    when (item) {
                                                        is BubbleTimelineItem.Event -> item.event.eventId == eventId
                                                        is BubbleTimelineItem.DateDivider -> false
                                                    }
                                                }
                                                if (index >= 0) {
                                                    coroutineScope.launch {
                                                        listState.scrollToItem(index)
                                                        highlightedEventId = eventId
                                                        highlightRequestId++
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
                                                navController.navigateToUserInfo(userId, roomId)
                                            },
                                            onRoomLinkClick = { roomLink ->
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Room link clicked: ${roomLink.roomIdOrAlias}")
                                                
                                                // If it's a room ID, check if we're already joined
                                                val existingRoom = if (roomLink.roomIdOrAlias.startsWith("!")) {
                                                    val room = appViewModel.getRoomById(roomLink.roomIdOrAlias)
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Checked for existing room ${roomLink.roomIdOrAlias}, found: ${room != null}")
                                                    room
                                                } else {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Room link is an alias, showing joiner")
                                                    null
                                                }
                                                
                                                if (existingRoom != null) {
                                                    // Already joined, navigate directly
                                                    val targetRoomId = roomLink.roomIdOrAlias
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Already joined, navigating to $targetRoomId")
                                                    // CRITICAL: When navigating from one room_timeline to another, use setDirectRoomNavigation
                                                    // and navigate via room_list, letting RoomListScreen handle the final navigation.
                                                    // This matches the pattern used by notifications/shortcuts and ensures proper state management.
                                                    appViewModel.setCurrentRoomIdForTimeline(targetRoomId)
                                                    appViewModel.setDirectRoomNavigation(targetRoomId)
                                                    navController.navigate("room_list")
                                                } else {
                                                    // For aliases or non-joined rooms, show room joiner
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Not joined, showing room joiner")
                                                    roomLinkToJoin = roomLink
                                                    showRoomJoiner = true
                                                }
                                            },
                                            onThreadClick = { threadEvent ->
                                                // Navigate to thread viewer
                                                val threadInfo = threadEvent.getThreadInfo()
                                                if (threadInfo != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Thread message clicked, opening thread for root: ${threadInfo.threadRootEventId}")
                                                    val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                                    val encodedThreadRoot = java.net.URLEncoder.encode(threadInfo.threadRootEventId, "UTF-8")
                                                    navController.navigate("thread_viewer/$encodedRoomId/$encodedThreadRoot")
                                                }
                                            },
                                            onCodeBlockClick = { code ->
                                                codeViewerContent = code
                                                showCodeViewer = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                            
                            // Pull-to-refresh indicator (outside LazyColumn, inside Box)
                            PullRefreshIndicator(
                                refreshing = isRefreshingPull,
                                state = pullRefreshState,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    }
                    }
                    
                    // 3. Typing notification area (stacks naturally above text box)
                    TypingNotificationArea(
                        typingUsers = appViewModel.getTypingUsersForRoom(roomId),
                        roomId = roomId,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        userProfileCache = appViewModel.getMemberMap(roomId),
                        appViewModel = appViewModel
                    )

                    // 4. Text box (always at the bottom, above keyboard/nav bar)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                modifier =
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding() // CRITICAL FIX: Keep message box above keyboard
                    ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Main attach button
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.width(48.dp).height(buttonHeight)
                        ) {
                            IconButton(
                                enabled = isInputEnabled,
                                onClick = { if (isInputEnabled) showAttachmentMenu = !showAttachmentMenu },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Attach",
                                    tint = if (isInputEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
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
                                        userProfileCache = memberMap, // Use reactive memberMap instead of static userProfileCache
                                        onCancel = { replyingToEvent = null },
                                        appViewModel = appViewModel,
                                        roomId = roomId
                                    )
                                }

                                // Create combined transformation for mentions and custom emojis
                                val colorScheme = MaterialTheme.colorScheme
                                val customEmojiPacks = appViewModel.customEmojiPacks
                                val mentionAndEmojiTransformation = remember(colorScheme, customEmojiPacks) {
                                    VisualTransformation { text ->
                                        val mentionRegex = Regex("""\[((?:[^\[\]\\]|\\.)*)\]\(https://matrix\.to/#/([^)]+)\)""")
                                        // Regex for custom emoji markdown: ![:name:](mxc://url "Emoji: :name:")
                                        val customEmojiRegex = Regex("""!\[:([^:]+):\]\((mxc://[^)]+)\s+"[^"]*"\)""")
                                        
                                        val annotatedString = buildAnnotatedString {
                                            var lastIndex = 0
                                            
                                            // Collect all matches (mentions and custom emojis) and sort by position
                                            val allMatches = mutableListOf<Pair<Int, MatchResult>>()
                                            mentionRegex.findAll(text.text).forEach { 
                                                allMatches.add(Pair(0, it)) // 0 = mention
                                            }
                                            customEmojiRegex.findAll(text.text).forEach { 
                                                allMatches.add(Pair(1, it)) // 1 = custom emoji
                                            }
                                            allMatches.sortBy { it.second.range.first }
                                            
                                            for ((type, match) in allMatches) {
                                                // Add text before match
                                                if (match.range.first > lastIndex) {
                                                    append(text.text.substring(lastIndex, match.range.first))
                                                }
                                                
                                                if (type == 0) {
                                                    // Handle mention
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
                                                } else {
                                                    // Handle custom emoji - replace markdown with just the emoji name
                                                    val emojiName = match.groupValues[1]
                                                    append(":$emojiName:")
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
                                                
                                                // Collect all matches (mentions and custom emojis) and sort by position
                                                val allMatches = mutableListOf<Pair<Int, MatchResult>>()
                                                mentionRegex.findAll(text.text).forEach { 
                                                    allMatches.add(Pair(0, it)) // 0 = mention
                                                }
                                                customEmojiRegex.findAll(text.text).forEach { 
                                                    allMatches.add(Pair(1, it)) // 1 = custom emoji
                                                }
                                                allMatches.sortBy { it.second.range.first }
                                                
                                                for ((type, match) in allMatches) {
                                                    val beforeLength = match.range.first - originalOffset
                                                    if (clampedOffset <= match.range.first) {
                                                        val result = transformedOffset + (clampedOffset - originalOffset)
                                                        return result.coerceIn(0, annotatedString.length)
                                                    }
                                                    transformedOffset += beforeLength
                                                    originalOffset = match.range.first
                                                    
                                                    val transformedLength = if (type == 0) {
                                                        // Mention
                                                        val escapedDisplayName = match.groupValues[1]
                                                        val displayName = escapedDisplayName
                                                            .replace("\\[", "[")
                                                            .replace("\\]", "]")
                                                        " $displayName ".length
                                                    } else {
                                                        // Custom emoji
                                                        val emojiName = match.groupValues[1]
                                                        ":$emojiName:".length
                                                    }
                                                    
                                                    if (clampedOffset <= match.range.last + 1) {
                                                        val result = transformedOffset + transformedLength
                                                        return result.coerceIn(0, annotatedString.length)
                                                    }
                                                    
                                                    transformedOffset += transformedLength
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
                                                
                                                // Collect all matches (mentions and custom emojis) and sort by position
                                                val allMatches = mutableListOf<Pair<Int, MatchResult>>()
                                                mentionRegex.findAll(text.text).forEach { 
                                                    allMatches.add(Pair(0, it)) // 0 = mention
                                                }
                                                customEmojiRegex.findAll(text.text).forEach { 
                                                    allMatches.add(Pair(1, it)) // 1 = custom emoji
                                                }
                                                allMatches.sortBy { it.second.range.first }
                                                
                                                for ((type, match) in allMatches) {
                                                    val beforeLength = match.range.first - originalOffset
                                                    if (clampedOffset <= transformedOffset + beforeLength) {
                                                        val result = originalOffset + (clampedOffset - transformedOffset)
                                                        return result.coerceIn(0, text.text.length)
                                                    }
                                                    transformedOffset += beforeLength
                                                    originalOffset = match.range.first
                                                    
                                                    val transformedLength = if (type == 0) {
                                                        // Mention
                                                        val escapedDisplayName = match.groupValues[1]
                                                        val displayName = escapedDisplayName
                                                            .replace("\\[", "[")
                                                            .replace("\\]", "]")
                                                        " $displayName ".length
                                                    } else {
                                                        // Custom emoji
                                                        val emojiName = match.groupValues[1]
                                                        ":$emojiName:".length
                                                    }
                                                    
                                                    if (clampedOffset <= transformedOffset + transformedLength) {
                                                        return match.range.last + 1
                                                    }
                                                    
                                                    transformedOffset += transformedLength
                                                    originalOffset = match.range.last + 1
                                                }
                                                
                                                // Handle remaining text
                                                val result = originalOffset + (clampedOffset - transformedOffset)
                                                return result.coerceIn(0, text.text.length)
                                            }
                                        }
                                        
                                        TransformedText(annotatedString, offsetMapping)
                                    }
                                }

                                // Text input field with mention + emoji shortcode support
                                CustomBubbleTextField(
                                    value = textFieldValue,
                                    enabled = isInputEnabled,
                                    onValueChange = { newValue: TextFieldValue ->
                                        if (!isInputEnabled) return@CustomBubbleTextField
                                        // First, handle custom emoji deletion (backspace on :name:)
                                        val afterDeletion = handleCustomEmojiDeletion(textFieldValue, newValue)
                                        
                                        // Then, apply any completed :shortcode: replacement
                                        val replacedValue = applyCompletedEmojiShortcode(afterDeletion)
                                        textFieldValue = replacedValue
                                        draft = replacedValue.text
                                        
                                        // Detect mentions
                                        val mentionResult = detectMention(
                                            replacedValue.text,
                                            replacedValue.selection.start
                                        )
                                        if (mentionResult != null) {
                                            val (query, startIndex) = mentionResult
                                            mentionQuery = query
                                            mentionStartIndex = startIndex
                                            
                                            // CRITICAL FIX: Load cached members immediately, then request fresh data
                                            if (!isWaitingForFullMemberList && !showMentionList) {
                                                // Check if we already have members in memory cache
                                                val memberMap = appViewModel.getMemberMap(roomId)
                                                if (memberMap.isEmpty() || memberMap.size < 10) {
                                                    // Profiles are loaded opportunistically when rendering events
                                                    // Request full member list to populate cache
                                                    // Request fresh data from server (will update when it arrives)
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: @ detected, requesting fresh member list for room $roomId")
                                                    isWaitingForFullMemberList = true
                                                    lastMemberUpdateCounterBeforeMention = appViewModel.memberUpdateCounter
                                                    appViewModel.requestFullMemberList(roomId)
                                                } else {
                                                    // We already have members in memory, show list immediately
                                                    showMentionList = true
                                                }
                                            }
                                        } else {
                                            showMentionList = false
                                            isWaitingForFullMemberList = false
                                        }

                                        // Detect emoji shortcodes ( :shortname )
                                        val emojiResult = detectEmojiShortcode(
                                            replacedValue.text,
                                            replacedValue.selection.start
                                        )
                                        if (emojiResult != null) {
                                            val (query, startIndex) = emojiResult
                                            emojiQuery = query
                                            emojiStartIndex = startIndex
                                            showEmojiSuggestionList = true
                                        } else {
                                            showEmojiSuggestionList = false
                                        }
                                    },
                                    placeholder = {
                                        Text(
                                            text = when {
                                                !canSendMessage -> "You don't have permission to send messages"
                                                !websocketConnected -> "Waiting for connection..."
                                                else -> {
                                                    val networkName = appViewModel.currentRoomState?.bridgeInfo?.displayName
                                                    if (networkName != null && networkName.isNotBlank()) {
                                                        "Type a $networkName message..."
                                                    } else {
                                                        "Type a message..."
                                                    }
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontStyle = FontStyle.Italic,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 1,
                                    maxLines = 5,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    onHeightChanged = { height ->
                                        // Only update if text is empty or single-line (to get the minimum height)
                                        val lineCount = draft.lines().size.coerceAtLeast(1)
                                        if (lineCount == 1 && (textFieldHeight == 0 || height < textFieldHeight)) {
                                            textFieldHeight = height
                                        }
                                    },
                                    trailingIcon = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Sticker button
                                            IconButton(
                                                enabled = isInputEnabled,
                                                onClick = { if (isInputEnabled) showStickerPickerForText = true },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                @Suppress("DEPRECATION")
                                                Icon(
                                                    imageVector = Icons.Outlined.StickyNote2,
                                                    contentDescription = "Stickers",
                                                    tint = if (isInputEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                                )
                                            }
                                            // Emoji button
                                            IconButton(
                                                enabled = isInputEnabled,
                                                onClick = { if (isInputEnabled) showEmojiPickerForText = true },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Mood,
                                                    contentDescription = "Emoji",
                                                    tint = if (isInputEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                                )
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        keyboardType = KeyboardType.Text,
                                        autoCorrectEnabled = true,
                                        imeAction = ImeAction.Default // Enter always creates newline, send button always sends
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (!isInputEnabled) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (!websocketConnected) "Waiting for connection..." else "You don't have permission to send messages",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                return@KeyboardActions
                                            }
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
                                                    messageSoundPlayer.play() // Play sound when sending reply
                                                }
                                                // Otherwise send regular message
                                                else {
                                                    appViewModel.sendMessage(roomId, draft)
                                                    messageSoundPlayer.play() // Play sound when sending message
                                                }
                                                draft = "" // Clear the input after sending
                                            }
                                        }
                                    ),
                                    visualTransformation = mentionAndEmojiTransformation
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Expressive indicator for uploads/sends
                        val isSending = appViewModel.pendingSendCount > 0
                        val showSendIndicator = isSending || isUploading
                        
                        Button(
                            onClick = {
                                if (!isInputEnabled) {
                                    android.widget.Toast.makeText(
                                        context,
                                        if (!websocketConnected) "Waiting for connection..." else "You don't have permission to send messages",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }
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
                                        messageSoundPlayer.play() // Play sound when sending reply
                                    }
                                    // Otherwise send regular message
                                    else {
                                        appViewModel.sendMessage(roomId, draft)
                                        messageSoundPlayer.play() // Play sound when sending message
                                    }
                                    draft = "" // Clear the input after sending
                                }
                            },
                            enabled = draft.isNotBlank() && isInputEnabled,
                            shape = CircleShape, // Perfect circle
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            modifier = Modifier.size(buttonHeight), // Fixed height matching single-line text field
                            contentPadding = PaddingValues(0.dp) // No padding for perfect circle
                        ) {
                            if (showSendIndicator) {
                                ContainedExpressiveLoadingIndicator(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    shape = CircleShape,
                                    containerColor =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    indicatorColor =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    contentPadding = 4.dp
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = "Send",
                                    tint =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                                // Scroll to bottom and re-attach (instant, no animation)
                                listState.scrollToItem(timelineItems.lastIndex)
                                isAttachedToBottom = true
                                if (BuildConfig.DEBUG) Log.d(
                                    "Andromuks",
                                    "BubbleTimelineScreen: FAB clicked, scrolling to bottom and re-attaching"
                                )
                            }
                        },
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(
                                    end = 16.dp,
                                    bottom = 60.dp // Closer to text input
                                )
                                .navigationBarsPadding(),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom"
                        )
                    }
                }
                
                // Emoji shortcode suggestion list
                if (showEmojiSuggestionList) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 72.dp, // Align with text input (attach button width + spacing)
                                bottom = 60.dp  // Closer to text input
                            )
                            .navigationBarsPadding()
                            .zIndex(9f)
                    ) {
                        EmojiSuggestionList(
                            query = emojiQuery,
                            customEmojiPacks = appViewModel.customEmojiPacks,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onSuggestionSelected = { suggestion ->
                                val currentText = draft
                                val cursorPos = textFieldValue.selection.start
                                val endIndex = cursorPos
                                
                                val baseReplacement =
                                    suggestion.emoji
                                        ?: suggestion.customEmoji?.let { custom ->
                                            "![:${custom.name}:](${custom.mxcUrl} \"Emoji: :${custom.name}:\")"
                                        }
                                        ?: ""
                                
                                if (baseReplacement.isNotEmpty() && emojiStartIndex >= 0 && emojiStartIndex < endIndex) {
                                    val newText =
                                        currentText.substring(0, emojiStartIndex) +
                                            baseReplacement +
                                            currentText.substring(endIndex)
                                    val newCursor = emojiStartIndex + baseReplacement.length
                                    
                                    draft = newText
                                    textFieldValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursor)
                                    )
                                    
                                    // Update recent emojis (same logic as main screen)
                                    val emojiForRecent =
                                        if (baseReplacement.startsWith("![:") && baseReplacement.contains("mxc://")) {
                                            val mxcStart = baseReplacement.indexOf("mxc://")
                                            if (mxcStart >= 0) {
                                                val mxcEnd = baseReplacement.indexOf("\"", mxcStart)
                                                if (mxcEnd > mxcStart) {
                                                    baseReplacement.substring(mxcStart, mxcEnd)
                                                } else {
                                                    baseReplacement.substring(mxcStart)
                                                }
                                            } else {
                                                baseReplacement
                                            }
                                        } else {
                                            baseReplacement
                                        }
                                    appViewModel.updateRecentEmojis(emojiForRecent)
                                }
                                
                                showEmojiSuggestionList = false
                                emojiQuery = ""
                            },
                            modifier = Modifier.zIndex(10f)
                        )
                    }
                }
                
                // Attachment menu overlay - horizontal floating action bar above footer
                if (showAttachmentMenu) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                        .graphicsLayer {
                            // Position menu right above footer (footer height = buttonHeight + 24.dp padding)
                            translationY = -with(density) { (buttonHeight + 24.dp).toPx() }
                        }
                        .navigationBarsPadding()
                        .imePadding()
                        .zIndex(5f) // Ensure it's above other content
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Files option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            launchPickerWithPermission("file", "*/*")
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Folder,
                                            contentDescription = "Files",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "File",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Audio option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            launchPickerWithPermission("audio", "audio/*")
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AudioFile,
                                            contentDescription = "Audio",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Audio",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Image/Video option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            launchPickerWithPermission("image", "*/*")
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Image,
                                            contentDescription = "Images & Videos",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Image/Video",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            
                            // Photo option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                launchCamera(false) // Photo
                                            } else {
                                                cameraPhotoPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CameraAlt,
                                            contentDescription = "Photo",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Photo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Video option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                launchCamera(true) // Video
                                            } else {
                                                cameraVideoPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Videocam,
                                            contentDescription = "Video",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Video",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                                bottom = 60.dp  // Closer to text input
                            )
                            .navigationBarsPadding()
                    ) {
                        BubbleMentionMemberList(
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
                        },
                        customEmojiPacks = appViewModel.customEmojiPacks
                    )
                }
                
                // Emoji selection dialog for text input
                if (showEmojiPickerForText) {
                    EmojiSelectionDialog(
                        recentEmojis = appViewModel.recentEmojis,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onEmojiSelected = { emoji ->
                            // Insert emoji at cursor position
                            val currentText = textFieldValue.text
                            val cursorPosition = textFieldValue.selection.start
                            val newText = currentText.substring(0, cursorPosition) + 
                                         emoji + 
                                         currentText.substring(cursorPosition)
                            val newCursorPosition = cursorPosition + emoji.length
                            
                            // Update both draft and textFieldValue
                            draft = newText
                            textFieldValue = TextFieldValue(
                                text = newText,
                                selection = TextRange(newCursorPosition)
                            )
                            
                            // Update recent emojis (updates in-memory state and sends to backend)
                            // This will persist via account_data and update the recent emoji tab
                            // For custom emojis, extract MXC URL from formatted string
                            val emojiForRecent = if (emoji.startsWith("![:") && emoji.contains("mxc://")) {
                                // Extract MXC URL from format: ![:name:](mxc://url "Emoji: :name:")
                                val mxcStart = emoji.indexOf("mxc://")
                                if (mxcStart >= 0) {
                                    val mxcEnd = emoji.indexOf("\"", mxcStart)
                                    if (mxcEnd > mxcStart) {
                                        emoji.substring(mxcStart, mxcEnd)
                                    } else {
                                        emoji.substring(mxcStart)
                                    }
                                } else {
                                    emoji
                                }
                            } else {
                                emoji
                            }
                            appViewModel.updateRecentEmojis(emojiForRecent)
                            
                            // Don't close the picker - user might want to add more emojis
                        },
                        onDismiss = {
                            showEmojiPickerForText = false
                        },
                        customEmojiPacks = appViewModel.customEmojiPacks,
                        allowCustomReactions = false
                    )
                }
                
                // Sticker selection dialog for text input
                if (showStickerPickerForText) {
                    StickerSelectionDialog(
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onStickerSelected = { sticker ->
                            // Send sticker message
                            val mimeType = sticker.info?.optString("mimetype") ?: "image/png"
                            val size = sticker.info?.optLong("size") ?: 0L
                            val width = sticker.info?.optInt("w", 0) ?: 0
                            val height = sticker.info?.optInt("h", 0) ?: 0
                            val body = sticker.body ?: sticker.name
                            
                            appViewModel.sendStickerMessage(
                                roomId = roomId,
                                mxcUrl = sticker.mxcUrl,
                                body = body,
                                mimeType = mimeType,
                                size = size,
                                width = width,
                                height = height
                            )
                            
                            showStickerPickerForText = false
                        },
                        onDismiss = {
                            showStickerPickerForText = false
                        },
                        stickerPacks = appViewModel.stickerPacks
                    )
                }
                
                // Media preview dialog (shows selected media with caption input)
                if (showMediaPreview && (selectedMediaUri != null || selectedAudioUri != null || selectedFileUri != null)) {
                    val currentUri = selectedMediaUri ?: selectedAudioUri ?: selectedFileUri!!
                    val isAudio = selectedAudioUri != null
                    val isFile = selectedFileUri != null
                    
                    MediaPreviewDialog(
                        uri = currentUri,
                        isVideo = selectedMediaIsVideo,
                        isAudio = isAudio,
                        isFile = isFile,
                        onDismiss = {
                            showMediaPreview = false
                            selectedMediaUri = null
                            selectedAudioUri = null
                            selectedFileUri = null
                            selectedMediaIsVideo = false
                        },
                        onSend = { caption, compressOriginal ->
                            // Start upload
                            showMediaPreview = false
                            isUploading = true
                            
                            // Upload and send in background
                            coroutineScope.launch {
                                try {
                                    when {
                                        selectedMediaIsVideo -> {
                                            // Upload video with thumbnail
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Starting video upload")
                                            val videoResult = VideoUploadUtils.uploadVideo(
                                                context = context,
                                                uri = currentUri,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
                                            )
                                            
                                            if (videoResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Video upload successful, sending message")
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
                                                selectedFileUri = null
                                                selectedMediaIsVideo = false
                                                isUploading = false
                                            } else {
                                                Log.e("Andromuks", "BubbleTimelineScreen: Video upload failed")
                                                isUploading = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload video",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        isAudio -> {
                                            // Upload audio
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Starting audio upload")
                                            val audioResult = MediaUploadUtils.uploadAudio(
                                                context = context,
                                                uri = selectedAudioUri!!,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
                                            )
                                            
                                            if (audioResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Audio upload successful, sending message")
                                                // Send audio message with metadata
                                                appViewModel.sendAudioMessage(
                                                    roomId = roomId,
                                                    mxcUrl = audioResult.mxcUrl,
                                                    filename = audioResult.filename,
                                                    duration = audioResult.duration,
                                                    size = audioResult.size,
                                                    mimeType = audioResult.mimeType,
                                                    caption = caption.takeIf { it.isNotBlank() }
                                                )
                                                
                                                // Clear state
                                                selectedAudioUri = null
                                                isUploading = false
                                            } else {
                                                Log.e("Andromuks", "BubbleTimelineScreen: Audio upload failed")
                                                isUploading = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload audio",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        isFile -> {
                                            // Upload file
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Starting file upload")
                                            val fileResult = MediaUploadUtils.uploadFile(
                                                context = context,
                                                uri = selectedFileUri!!,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
                                            )
                                            
                                            if (fileResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: File upload successful, sending message")
                                                // Send file message with metadata
                                                appViewModel.sendFileMessage(
                                                    roomId = roomId,
                                                    mxcUrl = fileResult.mxcUrl,
                                                    filename = fileResult.filename,
                                                    size = fileResult.size,
                                                    mimeType = fileResult.mimeType,
                                                    caption = caption.takeIf { it.isNotBlank() }
                                                )
                                                
                                                // Clear state
                                                selectedFileUri = null
                                                isUploading = false
                                            } else {
                                                Log.e("Andromuks", "BubbleTimelineScreen: File upload failed")
                                                isUploading = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload file",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        else -> {
                                            // Upload image
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Starting image upload (compressOriginal: $compressOriginal)")
                                            val uploadResult = MediaUploadUtils.uploadMedia(
                                                context = context,
                                                uri = selectedMediaUri!!,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false,
                                                compressOriginal = compressOriginal
                                            )
                                            
                                            if (uploadResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Image upload successful, sending message")
                                                // Send image message with metadata
                                                appViewModel.sendImageMessage(
                                                    roomId = roomId,
                                                    mxcUrl = uploadResult.mxcUrl,
                                                    width = uploadResult.width,
                                                    height = uploadResult.height,
                                                    size = uploadResult.size,
                                                    mimeType = uploadResult.mimeType,
                                                    blurHash = uploadResult.blurHash,
                                                    caption = caption.takeIf { it.isNotBlank() },
                                                    thumbnailUrl = uploadResult.thumbnailUrl,
                                                    thumbnailWidth = uploadResult.thumbnailWidth,
                                                    thumbnailHeight = uploadResult.thumbnailHeight,
                                                    thumbnailMimeType = uploadResult.thumbnailMimeType,
                                                    thumbnailSize = uploadResult.thumbnailSize
                                                )
                                                
                                                // Clear state
                                                selectedMediaUri = null
                                                isUploading = false
                                            } else {
                                                Log.e("Andromuks", "BubbleTimelineScreen: Image upload failed")
                                                isUploading = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload image",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "BubbleTimelineScreen: Upload error", e)
                                    isUploading = false
                                    selectedMediaUri = null
                                    selectedAudioUri = null
                                    selectedFileUri = null
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
                
                // Code viewer dialog
                if (showCodeViewer) {
                    CodeViewer(
                        code = codeViewerContent,
                        onDismiss = {
                            showCodeViewer = false
                            codeViewerContent = ""
                        }
                    )
                }
                }
            }
        }
    }
}







@Composable
fun BubbleRoomHeader(
    roomState: RoomState?,
    fallbackName: String,
    fallbackAvatarUrl: String? = null,
    homeserverUrl: String,
    authToken: String,
    roomId: String? = null,
    onHeaderClick: () -> Unit = {},
    onOpenInApp: () -> Unit = {},
    onCloseBubble: () -> Unit = {},
    onMinimizeBubble: () -> Unit = {},
    onRefreshClick: () -> Unit = {}
) {
    // Debug logging
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: roomState = $roomState")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: fallbackName = $fallbackName")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: homeserverUrl = $homeserverUrl")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: authToken = ${authToken.take(10)}...")
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
                    displayName = roomState?.name ?: fallbackName,
                    isVisible = true // Always visible for room header
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
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onOpenInApp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Launch,
                        contentDescription = "Open in app",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val bridgeInfo = roomState?.bridgeInfo
                if (bridgeInfo != null && bridgeInfo.hasRenderableIcon) {
                    BridgeNetworkBadge(
                        bridgeInfo = bridgeInfo,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onClick = onRefreshClick
                    )
                } else {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh timeline",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleRoomHeader: X button clicked - calling onMinimizeBubble")
                    onMinimizeBubble()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Minimize bubble",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
