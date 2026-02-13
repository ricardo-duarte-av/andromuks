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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
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
import net.vrkknn.andromuks.ScrollHighlightState
import net.vrkknn.andromuks.LocalScrollHighlightState
import net.vrkknn.andromuks.ui.components.AvatarImage
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.key
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import net.vrkknn.andromuks.ui.components.BridgeBackgroundLayer
import net.vrkknn.andromuks.ui.components.BridgeNetworkBadge
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ContainedExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ExpressiveStatusRow
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
sealed class TimelineItem {
    // PERFORMANCE: Stable key for LazyColumn items
    abstract val stableKey: String
    
    data class Event(
        val event: TimelineEvent,
        val isConsecutive: Boolean = false,
        val hasPerMessageProfile: Boolean = false
    ) : TimelineItem() {
        override val stableKey: String
            get() = event.transactionId
                ?: event.localContent?.optString("transaction_id")?.takeIf { it.isNotBlank() }
                ?: event.unsigned?.optString("transaction_id")?.takeIf { it.isNotBlank() }
                ?: event.eventId
    }

    data class DateDivider(val date: String) : TimelineItem() {
        override val stableKey: String get() = "date_$date"
    }
}

/** Format timestamp to date string (dd / MM / yyyy) */
internal fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("dd / MM / yyyy", Locale.getDefault())
    return formatter.format(date)
}

/** PERFORMANCE: Helper function to process timeline events in background */
suspend fun processTimelineEvents(
    timelineEvents: List<TimelineEvent>,
    allowedEventTypes: Set<String>
): List<TimelineEvent> = withContext(Dispatchers.Default) {
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "RoomTimelineScreen: Background processing ${timelineEvents.size} timeline events"
    )

    // Debug: Log event types in timeline
    val eventTypes = timelineEvents.groupBy { it.type }
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "RoomTimelineScreen: Event types in timeline: ${eventTypes.map { "${it.key}: ${it.value.size}" }.joinToString(", ")}"
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
    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: After type filtering: ${filteredEvents.size} events")

    // PERFORMANCE: Remove edit events (m.replace) but keep the original messages in the list.
    val eventsWithoutEdits = filteredEvents.filter { event ->
        when {
            event.type == "m.room.message" -> {
                val relatesTo = event.content?.optJSONObject("m.relates_to")
                val relType = relatesTo?.optString("rel_type")
                val isEditEvent = relType == "m.replace"
                if (isEditEvent) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Filtering out edit event (m.replace) ${event.eventId}")
                }
                !isEditEvent
            }
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
                val relatesTo = event.decrypted?.optJSONObject("m.relates_to")
                val relType = relatesTo?.optString("rel_type")
                val isEditEvent = relType == "m.replace"
                if (isEditEvent) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Filtering out encrypted edit event ${event.eventId}")
                }
                !isEditEvent
            }
            else -> true
        }
    }

    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: After edit filtering: ${eventsWithoutEdits.size} events")

    val sorted = eventsWithoutEdits.sortedBy { it.timestamp }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Final sorted events: ${sorted.size} events")

    sorted
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

// NOTE: Keep this screen in sync with `BubbleTimelineScreen`. Any structural or data-flow changes
// should be mirrored between both implementations. Refer to `docs/BUBBLE_IMPLEMENTATION.md`.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RoomTimelineScreen(
    roomId: String,
    roomName: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
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
    //Log.d("Andromuks", "RoomTimelineScreen: appViewModel instance: $appViewModel")
    // PERFORMANCE FIX: Use timelineEvents directly instead of pre-rendered flow.
    // Pre-rendering on every sync was causing heavy CPU load with 580+ rooms.
    // Timeline is now rendered lazily when room is opened via processCachedEvents().
    val timelineEvents = appViewModel.timelineEvents
    val isLoading = appViewModel.isTimelineLoading
    val currentRoomState = appViewModel.currentRoomState
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
    val canSendMessage = remember(currentRoomState, myUserId) {
        val pl = currentRoomState?.powerLevels ?: return@remember true
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

    // Log timeline events count only when it actually changes (not on every recomposition)
    // This prevents excessive logging during scroll
    // Use remember to track previous values and only log when they actually change
    var previousSize by remember { mutableStateOf(-1) }
    var previousIsLoading by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(timelineEvents.size, isLoading) {
        val currentSize = timelineEvents.size
        val currentIsLoading = isLoading
        if (currentSize != previousSize || currentIsLoading != previousIsLoading) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Timeline events count: $currentSize, isLoading: $currentIsLoading"
            )
            previousSize = currentSize
            previousIsLoading = currentIsLoading
        }
    }

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

    // Scroll highlight state for jump-to-message interactions
    var highlightedEventId by remember(roomId) { mutableStateOf<String?>(null) }
    var highlightRequestId by remember(roomId) { mutableStateOf(0) }
    var pendingNotificationJumpEventId by remember(roomId) {
        mutableStateOf(appViewModel.consumePendingHighlightEvent(roomId))
    }

    // Auto-clear highlight after a short duration
    LaunchedEffect(highlightRequestId, highlightedEventId) {
        val currentRequest = highlightRequestId
        if (highlightedEventId != null && currentRequest > 0) {
            kotlinx.coroutines.delay(1600)
            if (highlightRequestId == currentRequest) {
                highlightedEventId = null
            }
        }
    }

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

    // Text input state (moved here to be accessible by mention handler and share intake)
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

    LaunchedEffect(appViewModel.pendingShareUpdateCounter) {
        val sharePayload = appViewModel.consumePendingShareForRoom(roomId)
        if (sharePayload != null) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Received pending share for room $roomId with ${sharePayload.items.size} items"
            )
            if (!sharePayload.text.isNullOrBlank() && draft.isBlank()) {
                draft = sharePayload.text
            }

            val shareItem = sharePayload.items.firstOrNull()
            if (shareItem != null) {
                val uri = shareItem.uri
                val resolvedMime =
                    shareItem.mimeType ?: context.contentResolver.getType(uri) ?: ""

                // Reset previous selections
                selectedMediaUri = null
                selectedAudioUri = null
                selectedFileUri = null
                selectedMediaIsVideo = false

                when {
                    resolvedMime.startsWith("image/") -> {
                        selectedMediaUri = uri
                        selectedMediaIsVideo = false
                        showMediaPreview = true
                    }
                    resolvedMime.startsWith("video/") -> {
                        selectedMediaUri = uri
                        selectedMediaIsVideo = true
                        showMediaPreview = true
                    }
                    resolvedMime.startsWith("audio/") -> {
                        selectedAudioUri = uri
                        showMediaPreview = true
                    }
                    else -> {
                        selectedFileUri = uri
                        selectedMediaIsVideo = resolvedMime.startsWith("video/")
                        showMediaPreview = true
                    }
                }

                showAttachmentMenu = false
            }
        }
    }
    
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
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Full member list loaded (${memberMap.size} members), showing mention list")
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

    // (removed userProfileCache building loop - it was unused and caused main thread jank)

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
    
    // Process timeline events in background when this room's timeline changes.
    // IMPORTANT: Do NOT key this effect on global counters (like timelineUpdateCounter),
    // otherwise updates in other rooms would trigger unnecessary work here.
    // PERFORMANCE: Gate logging on app visibility and current room, but still process events
    // (needed for when app comes back to foreground)
    LaunchedEffect(timelineEvents) {
        val shouldLog = appViewModel.isAppVisible && appViewModel.currentRoomId == roomId
        if (shouldLog && BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Processing timelineEvents update - size=${timelineEvents.size}, updateCounter=${appViewModel.timelineUpdateCounter}, roomId=$roomId"
            )
        }
        sortedEvents = processTimelineEvents(
            timelineEvents = timelineEvents,
            allowedEventTypes = allowedEventTypes
        )
    }

    // PERFORMANCE: Pre-load all user profiles when timeline loads
    LaunchedEffect(timelineEvents) {
        if (appViewModel.isAppVisible && appViewModel.currentRoomId == roomId) {
            val uniqueSenders = timelineEvents.map { it.sender }.toSet()
            
            uniqueSenders.forEach { sender ->
                val existingProfile = appViewModel.getUserProfile(sender, roomId)
                if (existingProfile == null || existingProfile.displayName.isNullOrBlank()) {
                    appViewModel.requestUserProfileOnDemand(sender, roomId)
                }
            }
        }
    }

    // PERFORMANCE: Create timeline items with date dividers and pre-compute consecutive flags.
    // Only depend on this room's sortedEvents; do NOT depend on global counters so that
    // events in other rooms don't cause recomputation here.
    // PERFORMANCE: Create timeline items with date dividers and pre-compute consecutive flags.
    // Use produceState to offload this heavy computation (iterating thousands of events) to a background thread.
    // The main thread should NEVER iterate the full event list.
    val timelineItems by produceState<List<TimelineItem>>(initialValue = emptyList(), sortedEvents) {
        value = withContext(Dispatchers.Default) {
            val items = mutableListOf<TimelineItem>()
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
                    items.add(TimelineItem.DateDivider(eventDate))
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
                items.add(TimelineItem.Event(
                    event = event,
                    isConsecutive = isConsecutive,
                    hasPerMessageProfile = hasPerMessageProfile
                ))

                previousEvent = event
            }
            items
        }
    }
    var lastInitialScrollSize by remember(roomId) { mutableStateOf(0) }

    // Get base member map that observes memberUpdateCounter
    // CRITICAL FIX: Don't depend on sortedEvents directly to avoid infinite recomposition loop
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId)
    }
    
    // CRITICAL FIX: Use simple size-based key to avoid expensive operations during composition
    // Processing all senders with map/distinct/sorted can block UI thread and cause ANR
    // Using just size is sufficient - if size changes, we need to recompute anyway
    val sortedEventsSize = sortedEvents.size
    
    // CRITICAL FIX: Ensure current user profile is included in memberMapWithFallback
    // The current user's profile might not be in the room's member map if there's no m.room.member event for them
    // This fixes the issue where own messages show username instead of display name/avatar
    val memberMapWithFallback = remember(memberMap, appViewModel.currentUserProfile, myUserId) {
        val enhancedMap = memberMap.toMutableMap()
        
        // If current user is not in member map but we have currentUserProfile, add it
        if (myUserId.isNotBlank() && !enhancedMap.containsKey(myUserId)) {
            val currentProfile = appViewModel.currentUserProfile
            if (currentProfile != null) {
                enhancedMap[myUserId] = MemberProfile(
                    displayName = currentProfile.displayName,
                    avatarUrl = currentProfile.avatarUrl
                )
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Added current user profile to memberMapWithFallback - userId: $myUserId, displayName: ${currentProfile.displayName}"
                )
            }
        }
        
        enhancedMap
    }

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()
    
    // Track scroll position using event ID anchor (more robust than index)
    var anchorEventIdForRestore by remember { mutableStateOf<String?>(null) }
    var anchorScrollOffsetForRestore by remember { mutableStateOf(0) }
    var pendingScrollRestoration by remember { mutableStateOf(false) }
    var expectedTimelineSizeAfterLoad by remember { mutableStateOf<Int?>(null) }
    
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
                    item is TimelineItem.Event
                }
            val eventItem = firstVisibleInfo?.let { info ->
                timelineItems.getOrNull(info.index) as? TimelineItem.Event
            }
            
            if (eventItem != null) {
                // Set up scroll restoration to maintain viewport position
                anchorEventIdForRestore = eventItem.event.eventId
                anchorScrollOffsetForRestore = firstVisibleInfo?.offset ?: listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                expectedTimelineSizeAfterLoad = timelineItems.size
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Pull-to-refresh triggered, capturing anchor event: ${anchorEventIdForRestore} at offset ${anchorScrollOffsetForRestore}"
                )
            } else {
                // Fallback: use first visible item index if no event found
                anchorEventIdForRestore = null
                anchorScrollOffsetForRestore = listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                expectedTimelineSizeAfterLoad = timelineItems.size
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Pull-to-refresh triggered, no anchor event found, using scroll offset: ${anchorScrollOffsetForRestore}"
                )
            }
            
            // Use the oldest event from cache, not the oldest rendered event
            // The cache may have events that aren't currently rendered, so we need to use
            // the absolute oldest event to avoid requesting duplicates
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Pull-to-refresh triggered, requesting pagination with oldest cached event")
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
    
    // Use imePadding for keyboard handling (defined early so it's accessible to all LaunchedEffects)
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    var previousItemCount by remember { mutableStateOf(timelineItems.size) }
    var hasLoadedInitialBatch by remember { mutableStateOf(false) }
    var hasInitialSnapCompleted by remember { mutableStateOf(false) }
    var lastKnownTimelineEventId by remember { mutableStateOf<String?>(null) }
    var hasCompletedInitialLayout by remember { mutableStateOf(false) }
    var pendingInitialScroll by remember { mutableStateOf(true) }

    // PERFORMANCE: Use derivedStateOf to compute isAtBottom only when scroll position actually changes
    // This prevents recomposition on every scroll event - only recomposes when isAtBottom value changes
    val isAtBottom by remember(listState, timelineItems) {
        derivedStateOf {
            if (timelineItems.isEmpty() || listState.layoutInfo.totalItemsCount == 0) {
                false
            } else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val lastTimelineItemIndex = timelineItems.lastIndex
                lastVisibleIndex >= lastTimelineItemIndex - 1 // Within last item
            }
        }
    }

    // PERFORMANCE: Monitor scroll position changes - only triggers when isAtBottom value actually changes
    // Since isAtBottom is a derivedStateOf, it only recomposes when the boolean value changes
    LaunchedEffect(isAtBottom, timelineItems.size) {
        if (!hasInitialSnapCompleted || !hasLoadedInitialBatch) {
            return@LaunchedEffect
        }
        
        if (sortedEvents.isEmpty() || listState.layoutInfo.totalItemsCount == 0) {
            return@LaunchedEffect
        }

        if (!hasCompletedInitialLayout) {
            hasCompletedInitialLayout = true
        }

        // Update attachment state based on current position
        if (isAtBottom && !isAttachedToBottom) {
            // User scrolled back to bottom, re-attach
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: User reached bottom, re-attaching")
            isAttachedToBottom = true
            if (!hasInitialSnapCompleted) {
                hasInitialSnapCompleted = true
            }
        } else if (!isAtBottom && isAttachedToBottom && listState.firstVisibleItemIndex > 0) {
            // User scrolled up from bottom, detach
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: User scrolled up, detaching from bottom")
            isAttachedToBottom = false
        }
    }
    
    // PERFORMANCE: Separate LaunchedEffect for auto-scroll when attached to bottom
    // This only triggers when timelineItems.size changes (new messages), not on every scroll
    // CRITICAL: Skip auto-scroll during keyboard transitions to prevent scroll position loss
    LaunchedEffect(timelineItems.size, isAttachedToBottom, imeBottom) {
        if (!hasInitialSnapCompleted || !hasLoadedInitialBatch) {
            return@LaunchedEffect
        }
        
        // CRITICAL: When keyboard is open, DON'T check isAtBottom - it's unreliable due to compressed viewport
        // If we're attached to bottom and keyboard is open, just scroll to bottom when new items arrive
        val isKeyboardOpen = imeBottom > 0.dp
        
        if (isKeyboardOpen && isAttachedToBottom && timelineItems.isNotEmpty()) {
            // Keyboard is open and we're attached - just scroll to bottom for new messages
            // Don't check isAtBottom as it's unreliable with compressed viewport
            val lastTimelineItemIndex = timelineItems.lastIndex
            if (lastTimelineItemIndex >= 0) {
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Keyboard open, attached to bottom, new message arrived. Scrolling to bottom (lastItem=$lastTimelineItemIndex)"
                )
                coroutineScope.launch {
                    // Small delay to let message render
                    kotlinx.coroutines.delay(50)
                    listState.scrollToItem(lastTimelineItemIndex)
                }
            }
            return@LaunchedEffect // Skip the isAtBottom check below
        }
        
        // CRITICAL: Only check isAtBottom when keyboard is CLOSED
        // When keyboard is closed, we can reliably calculate scroll position
        if (!isKeyboardOpen && isAttachedToBottom && !isAtBottom && timelineItems.isNotEmpty()) {
            val lastTimelineItemIndex = timelineItems.lastIndex
            if (lastTimelineItemIndex >= 0) {
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Keyboard closed, attached to bottom but not at bottom (lastItem=$lastTimelineItemIndex). Auto-scrolling to show new items."
                )
                coroutineScope.launch {
                    listState.scrollToItem(lastTimelineItemIndex)
                }
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
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Pagination completed, restoring scroll to anchor event: $anchorEventIdForRestore")
            
            // Find the index of the anchor event in the new list
            val anchorIndex = timelineItems.indexOfFirst { item ->
                (item as? TimelineItem.Event)?.event?.eventId == anchorEventIdForRestore
            }
            
            if (anchorIndex >= 0) {
                val targetIndex = anchorIndex
                
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Found anchor event at index $targetIndex, " +
                    "restoring with offset $anchorScrollOffsetForRestore"
                )
                
                // Scroll immediately (we're in a LaunchedEffect coroutine context)
                listState.scrollToItem(targetIndex, anchorScrollOffsetForRestore)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: ✅ Scroll position restored to event at index $targetIndex")
            } else {
                    Log.w("Andromuks", "RoomTimelineScreen: ⚠️ Could not find anchor event $anchorEventIdForRestore in new timeline, falling back to scroll offset")
                    // Fallback: try to maintain scroll position using offset
                    val currentFirstIndex = listState.firstVisibleItemIndex
                    if (currentFirstIndex >= 0 && currentFirstIndex < timelineItems.size) {
                        listState.scrollToItem(currentFirstIndex, anchorScrollOffsetForRestore)
                    }
                }
            } else {
                // Fallback: restore using scroll offset (when no anchor event was captured)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Pagination completed, restoring scroll using offset: $anchorScrollOffsetForRestore")
                val currentFirstIndex = listState.firstVisibleItemIndex
                if (currentFirstIndex >= 0 && currentFirstIndex < timelineItems.size) {
                    listState.scrollToItem(currentFirstIndex, anchorScrollOffsetForRestore)
                }
            }
            
            // Clear restoration state
            pendingScrollRestoration = false
            anchorEventIdForRestore = null
            anchorScrollOffsetForRestore = 0
            expectedTimelineSizeAfterLoad = null
            isRefreshingPull = false
        }
    }
    
    LaunchedEffect(appViewModel.isPaginating, pendingScrollRestoration, timelineItems.size) {
        if (
            !appViewModel.isPaginating &&
            pendingScrollRestoration &&
            expectedTimelineSizeAfterLoad != null &&
            expectedTimelineSizeAfterLoad == timelineItems.size
        ) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Load older finished with no new events; resetting scroll restoration state."
            )
            pendingScrollRestoration = false
            anchorEventIdForRestore = null
            expectedTimelineSizeAfterLoad = null
        }
    }

    // When a manual refresh completes, snap back to bottom and re-enable auto-pagination
    LaunchedEffect(isRefreshing, timelineItems.size) {
        if (isRefreshing && timelineItems.isNotEmpty() && !appViewModel.hasPendingTimelineRequest(roomId)) {
            val lastIndex = timelineItems.lastIndex
            if (lastIndex >= 0) {
                listState.scrollToItem(lastIndex, 0)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Manual refresh loaded ${timelineItems.size} items - scrolled to bottom")
            }
            isAttachedToBottom = true
            hasInitialSnapCompleted = true
            hasCompletedInitialLayout = true
            pendingScrollRestoration = false
            isRefreshing = false
        }
    }
    
    // Safety fallback: if refresh takes too long, re-enable auto-pagination to avoid being stuck
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            kotlinx.coroutines.delay(2000)
            if (isRefreshing && !appViewModel.hasPendingTimelineRequest(roomId)) {
                Log.w("Andromuks", "RoomTimelineScreen: Manual refresh timeout - marking refresh as complete (no pending requests)")
                isRefreshing = false
            }
        }
    }

    // Track last known timeline update counter to detect when timeline has been built.
    // NOTE: This is read for heuristics inside effects but we avoid keying effects on it,
    // so global timeline updates in other rooms don't force recomposition here.
    var lastKnownTimelineUpdateCounter by remember { mutableStateOf(appViewModel.timelineUpdateCounter) }
    
    // Auto-scroll to bottom only when attached (initial load or new messages while at bottom)
    LaunchedEffect(
        timelineItems.size,
        isLoading,
        appViewModel.isPaginating,
        pendingNotificationJumpEventId
    ) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "RoomTimelineScreen: LaunchedEffect - timelineItems.size: ${timelineItems.size}, isLoading: $isLoading, isPaginating: ${appViewModel.isPaginating}, timelineUpdateCounter: ${appViewModel.timelineUpdateCounter}, hasInitialSnapCompleted: $hasInitialSnapCompleted"
        )

        if (pendingNotificationJumpEventId != null) {
            return@LaunchedEffect
        }

        if (isLoading || timelineItems.isEmpty()) {
            return@LaunchedEffect
        }

        val lastEventId = (timelineItems.lastOrNull() as? TimelineItem.Event)?.event?.eventId

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
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Timeline ready for immediate scroll (${timelineItems.size} items, updateCounter: $currentUpdateCounter) after ${waitCount * 50}ms")
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
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Timeline not ready for scroll (empty: ${timelineItems.isEmpty()}, loading: $isLoading, paginating: ${appViewModel.isPaginating})")
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
                    
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: ✅ Scrolled to bottom on initial load (${timelineItems.size} items, index $targetIndex, updateCounter: ${appViewModel.timelineUpdateCounter})")
                } else {
                    hasInitialSnapCompleted = true
                    Log.w("Andromuks", "RoomTimelineScreen: Invalid target index for scroll")
                }
            }
            return@LaunchedEffect
        }

        val hasNewItems = previousItemCount < timelineItems.size

        // Skip handling new items if we're waiting for scroll restoration after pagination
        if (pendingScrollRestoration) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Skipping new items handling - pending scroll restoration")
            return@LaunchedEffect
        }

        // CRITICAL FIX: Only auto-scroll for new messages if attached to bottom
        // BUT skip this if keyboard is open - let the other LaunchedEffect handle it
        // This prevents double-scrolling and incorrect scroll position calculations
        if (
            hasNewItems &&
                isAttachedToBottom &&
                lastEventId != null &&
                lastEventId != lastKnownTimelineEventId &&
                imeBottom == 0.dp // ONLY handle when keyboard is CLOSED
        ) {
            coroutineScope.launch {
                val lastIndex = timelineItems.lastIndex
                if (lastIndex >= 0) {
                    listState.scrollToItem(lastIndex)
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: New message arrived (keyboard closed), scrolled to bottom (attached=$isAttachedToBottom)")
                }
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

    
    // NOTE: markRoomAsRead is handled by navigateToRoomWithCache, so we don't need to call it here
    // This prevents duplicate mark_read calls and race conditions

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
        appViewModel.promoteToPrimaryIfNeeded("room_timeline_$roomId")
        appViewModel.navigateToRoomWithCache(roomId)
        val requireInitComplete = !appViewModel.isWebSocketConnected()
        val readinessResult = appViewModel.awaitRoomDataReadiness(requireInitComplete = requireInitComplete, roomId = roomId)
        readinessCheckComplete = true
        if (!readinessResult && BuildConfig.DEBUG) {
            Log.w("Andromuks", "RoomTimelineScreen: Readiness timeout while opening $roomId - continuing with partial data")
        }
        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Loading timeline for room: $roomId")
        // CRITICAL FIX: Set currentRoomId immediately when screen opens (for all navigation paths)
        // This ensures state is consistent whether opening from RoomListScreen, notification, or shortcut
        appViewModel.setCurrentRoomIdForTimeline(roomId)
        // CRITICAL: Add room to opened rooms (exempt from cache clearing on WebSocket reconnect)
        // This is also done in setCurrentRoomIdForTimeline, but we ensure it here too
        RoomTimelineCache.addOpenedRoom(roomId)
        
        // Reset state for new room
        pendingScrollRestoration = false
        anchorEventIdForRestore = null
        hasLoadedInitialBatch = false
        isAttachedToBottom = true
        isInitialLoad = true
        hasInitialSnapCompleted = false
        
        // CRITICAL FIX: Ensure loading state is set early when opening a new room
        // This ensures "Room loading..." shows immediately while room data is being loaded/processed
        if (appViewModel.currentRoomId != roomId || appViewModel.timelineEvents.isEmpty()) {
            // Only set loading if we're opening a different room or timeline is empty
            // This prevents showing loading when resuming the same room
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Setting loading state for room: $roomId")
        }
        
        // Request room state
        // NOTE: navigateToRoomWithCache() already calls requestRoomTimeline() if cache is empty,
        // so we don't need to call it again here to avoid duplicate paginate requests
        appViewModel.requestRoomState(roomId)
    }
    
    // CRITICAL FIX: Clear currentRoomId when leaving the room (back navigation or room change)
    // This ensures notifications resume when user navigates away
    // Using roomId as key ensures this disposes when:
    // 1. User navigates back (composable removed)
    // 2. User switches to a different room (roomId changes, old room's effect disposes)
    DisposableEffect(roomId) {
        // CRITICAL: Add room to opened rooms when screen opens (exempt from cache clearing on reconnect)
        RoomTimelineCache.addOpenedRoom(roomId)
        
        onDispose {
            // CRITICAL: Remove room from opened rooms when screen closes
            RoomTimelineCache.removeOpenedRoom(roomId)
            // Only clear if this room is still the current room (user navigated away)
            // If user switched to a different room, the new room's LaunchedEffect will have already set currentRoomId
            if (appViewModel.currentRoomId == roomId) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Disposing - clearing currentRoomId for room: $roomId")
                appViewModel.clearCurrentRoomId()
            } else {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Disposing - not clearing currentRoomId (current: ${appViewModel.currentRoomId}, this room: $roomId)")
            }
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
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: App resumed, refreshing timeline for room: $roomId")
            // Don't reset state flags - this is just a refresh, not a new room load
            appViewModel.requestRoomTimeline(roomId)
            lastKnownRefreshTrigger = appViewModel.timelineRefreshTrigger
        }
    }
    
    // When timeline updates after app resume or when items change, verify scroll position if attached to bottom
    // This ensures we stay at bottom even if new messages arrived while device was in standby
    LaunchedEffect(timelineItems.size, appViewModel.timelineRefreshTrigger, hasInitialSnapCompleted, isAttachedToBottom) {
        // Only check if initial snap is complete and we're attached to bottom
        if (!hasInitialSnapCompleted || !isAttachedToBottom || isLoading || pendingScrollRestoration) {
            return@LaunchedEffect
        }
        
        if (timelineItems.isEmpty() || listState.layoutInfo.totalItemsCount == 0) {
            return@LaunchedEffect
        }
        
        // Wait a moment for layout to settle (especially after resume)
        kotlinx.coroutines.delay(150)
        
        // Re-check conditions after delay
        if (listState.layoutInfo.totalItemsCount > 0 && timelineItems.isNotEmpty() && isAttachedToBottom) {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastTimelineItemIndex = timelineItems.lastIndex
            val isAtBottom = lastVisibleIndex >= lastTimelineItemIndex - 1
            
            // If we're attached to bottom but not actually at bottom, scroll to restore position
            // This handles both: new items appearing below viewport AND app resume scenarios
            if (!isAtBottom && lastTimelineItemIndex >= 0) {
                val refreshTriggerChanged = appViewModel.timelineRefreshTrigger != lastKnownRefreshTrigger
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Attached to bottom but not at bottom (lastVisible=$lastVisibleIndex, lastItem=$lastTimelineItemIndex, refreshTriggerChanged=$refreshTriggerChanged). Restoring scroll position."
                )
                coroutineScope.launch {
                    listState.scrollToItem(lastTimelineItemIndex)
                }
            }
        }
        
        // Update last known refresh trigger if it changed
        if (appViewModel.timelineRefreshTrigger != lastKnownRefreshTrigger) {
            lastKnownRefreshTrigger = appViewModel.timelineRefreshTrigger
        }
    }

    // Listen for foreground refresh broadcast to refresh timeline when app comes to foreground
    DisposableEffect(Unit) {
        val foregroundRefreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "net.vrkknn.andromuks.FOREGROUND_REFRESH") {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Received FOREGROUND_REFRESH broadcast, refreshing timeline UI from cache for room: $roomId")
                    // Lightweight timeline refresh from cached data (no network requests)
                    appViewModel.refreshTimelineUI()
                }
            }
        }
        
        val filter = IntentFilter("net.vrkknn.andromuks.FOREGROUND_REFRESH")
        context.registerReceiver(foregroundRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Registered FOREGROUND_REFRESH broadcast receiver")
        
        onDispose {
            try {
                context.unregisterReceiver(foregroundRefreshReceiver)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Unregistered FOREGROUND_REFRESH broadcast receiver")
            } catch (e: Exception) {
                Log.w("Andromuks", "RoomTimelineScreen: Error unregistering foreground refresh receiver", e)
            }
        }
    }

    // After initial batch loads, automatically load second batch in background
    // LaunchedEffect(hasLoadedInitialBatch) {
    //    if (hasLoadedInitialBatch && sortedEvents.isNotEmpty()) {
    //        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Initial batch loaded, automatically loading second batch")
    //        kotlinx.coroutines.delay(500) // Small delay to let UI settle
    //        appViewModel.loadOlderMessages(roomId)
    //    }
    // }

    // Validate and request missing user profiles when timeline events change
    // This ensures all users in the timeline have complete profile data (display name, avatar)
    // Missing profiles are automatically requested from the server
    // PERFORMANCE: Only run when app is visible and this is the current room
    LaunchedEffect(sortedEvents, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        if (!appViewModel.isAppVisible || appViewModel.currentRoomId != roomId) {
            return@LaunchedEffect
        }
        
        if (sortedEvents.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Validating user profiles for ${sortedEvents.size} events"
            )
            appViewModel.validateAndRequestMissingProfiles(roomId, sortedEvents)
        }
    }

    // OPPORTUNISTIC PROFILE LOADING: Only request profiles when actually needed for rendering
    // This prevents loading 15,000+ profiles upfront for large rooms
    // PERFORMANCE: Only run when app is visible and this is the current room
    LaunchedEffect(sortedEvents, roomId, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        if (!appViewModel.isAppVisible || appViewModel.currentRoomId != roomId) {
            return@LaunchedEffect
        }
        
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "RoomTimelineScreen: Using opportunistic profile loading for $roomId (no bulk loading)"
        )
        
        // Only request profiles for users that are actually visible in the timeline
        // This dramatically reduces memory usage for large rooms
        if (sortedEvents.isNotEmpty()) {
            val visibleUsers = sortedEvents.take(50) // Only first 50 events to avoid overwhelming
                .map { it.sender }
                .distinct()
            
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Requesting profiles on-demand for ${visibleUsers.size} visible users (instead of all ${sortedEvents.size} events)"
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
    // PERFORMANCE: Only run when app is visible and this is the current room
    LaunchedEffect(appViewModel.memberUpdateCounter, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        if (!appViewModel.isAppVisible || appViewModel.currentRoomId != roomId) {
            return@LaunchedEffect
        }
        
        // Only save profiles for users who are actually involved in the current timeline events
        val usersInTimeline = sortedEvents.map { it.sender }.distinct().toSet()
        if (usersInTimeline.isNotEmpty()) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val profilesToSave = usersInTimeline.filter { memberMap.containsKey(it) }
            if (profilesToSave.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Saving ${profilesToSave.size} profiles to disk for users in timeline"
                )
                // Profiles are cached in-memory only - no DB persistence needed
            }
        }
    }

    // Ensure timeline reactively updates when new events arrive from sync
    // OPTIMIZED: Only track timelineEvents changes directly, updateCounter is handled by receipt updates
    // PERFORMANCE: Only log when app is visible and this is the current room
    LaunchedEffect(timelineEvents, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        // Only react to changes for the current room and when app is visible
        if (appViewModel.currentRoomId == roomId && appViewModel.isAppVisible) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Timeline events changed - timelineEvents.size: ${timelineEvents.size}, currentRoomId: ${appViewModel.currentRoomId}, roomId: $roomId")
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Detected timeline update for current room $roomId with ${timelineEvents.size} events")
            
            // Force recomposition when timeline events change
            // This ensures the UI updates even when battery optimization might skip updates
        }
    }
    
    // CRITICAL FIX: Observe timeline changes reactively using state flows
    // This detects new events that were persisted to DB but might not have triggered timeline updates
    // (e.g., due to race conditions, timing issues, or if events weren't in sync batch)
    // This is event-driven (no polling) and only triggers when DB actually changes
    // Events are in-memory cache only - no DB observation needed
    // Timeline updates come from sync_complete and pagination

    // Handle Android back key
    BackHandler {
        if (showAttachmentMenu) {
            // Close attachment menu if open
            showAttachmentMenu = false
        } else {
            // CRITICAL FIX: Clear currentRoomId when navigating back to ensure notifications resume
            if (appViewModel.currentRoomId == roomId) {
                appViewModel.clearCurrentRoomId()
            }
            
            // CRITICAL FIX: Check if room_list is in the back stack
            // If not (e.g., opened from notification), navigate to room_list properly
            // This ensures room_list is properly initialized instead of just popping to auth_check
            val popped = navController.popBackStack("room_list", inclusive = false)
            if (!popped) {
                // room_list is not in the back stack - navigate to it properly
                // This will go through the normal flow (auth_check -> room_list) which ensures proper initialization
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: room_list not in back stack, navigating to room_list")
                navController.navigate("room_list") {
                    // Pop everything up to and including the current destination (room_timeline)
                    popUpTo(navController.graph.startDestinationId) {
                        inclusive = false
                        saveState = false
                    }
                    // Restore state when navigating back to room_list
                    restoreState = false
                }
            }
        }
    }

    // Navigation bar padding (imeBottom already defined above)
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Choose IME if present, otherwise navigation bar padding
    val bottomInset = if (imeBottom > 0.dp) imeBottom else navBarBottom
    
    // Track scroll position before keyboard opens to preserve it
    var scrollPositionBeforeKeyboard by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (firstVisibleIndex, scrollOffset)
    var wasAttachedBeforeKeyboard by remember { mutableStateOf(false) }
    var previousImeBottom by remember { mutableStateOf(0.dp) }
    
    LaunchedEffect(
        pendingNotificationJumpEventId,
        timelineItems.size,
        readinessCheckComplete,
        appViewModel.timelineUpdateCounter,
        pendingScrollRestoration
    ) {
        val targetEventId = pendingNotificationJumpEventId ?: return@LaunchedEffect
        if (!readinessCheckComplete || timelineItems.isEmpty() || pendingScrollRestoration) {
            return@LaunchedEffect
        }
        val targetIndex = timelineItems.indexOfFirst { item ->
            (item as? TimelineItem.Event)?.event?.eventId == targetEventId
        }
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            isAttachedToBottom = targetIndex >= timelineItems.lastIndex - 1
            wasAttachedBeforeKeyboard = isAttachedToBottom
            hasInitialSnapCompleted = true
            hasLoadedInitialBatch = true
            pendingInitialScroll = false
            highlightedEventId = targetEventId
            highlightRequestId++
            pendingNotificationJumpEventId = null
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Jumped to notification target event=$targetEventId at index=$targetIndex"
            )
        } else if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Pending notification target $targetEventId not yet in timeline (size=${timelineItems.size})"
            )
        }
    }
    
    // CRITICAL FIX: Handle keyboard state changes without losing scroll position
    LaunchedEffect(imeBottom) {
        if (timelineItems.isEmpty() || listState.layoutInfo.totalItemsCount == 0) {
            previousImeBottom = imeBottom
            return@LaunchedEffect
        }
        
        val keyboardWasOpen = previousImeBottom > 0.dp
        val keyboardIsOpen = imeBottom > 0.dp
        val keyboardJustOpened = !keyboardWasOpen && keyboardIsOpen
        val keyboardJustClosed = keyboardWasOpen && !keyboardIsOpen
        
        // CRITICAL: Capture scroll position BEFORE keyboard opens
        if (keyboardJustOpened) {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val scrollOffset = listState.firstVisibleItemScrollOffset
            scrollPositionBeforeKeyboard = Pair(firstVisibleIndex, scrollOffset)
            wasAttachedBeforeKeyboard = isAttachedToBottom
            
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Keyboard opening - captured scroll position: index=$firstVisibleIndex, offset=$scrollOffset, attached=$isAttachedToBottom"
            )
            
            // ONLY scroll to bottom if we were attached to bottom
            if (isAttachedToBottom) {
                val lastIndex = timelineItems.lastIndex
                if (lastIndex >= 0) {
                    // Small delay to let keyboard animation start
                    kotlinx.coroutines.delay(50)
                    listState.scrollToItem(lastIndex, scrollOffset = 0)
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard opened, scrolled to bottom (was attached)")
                }
            } else {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard opened, preserving scroll position (not attached to bottom)")
            }
        }
        
        // CRITICAL: Restore scroll position when keyboard closes
        if (keyboardJustClosed) {
            // Wait for layout to settle after keyboard closes
            kotlinx.coroutines.delay(100)
            
            if (timelineItems.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
                if (wasAttachedBeforeKeyboard) {
                    // We were attached to bottom, scroll to show new messages
                    val lastIndex = timelineItems.lastIndex
                    if (lastIndex >= 0) {
                        listState.scrollToItem(lastIndex)
                        isAttachedToBottom = true
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard closed, scrolled to bottom (was attached before)")
                    }
                } else if (scrollPositionBeforeKeyboard != null) {
                    // We were NOT attached to bottom, restore exact scroll position
                    val (savedIndex, savedOffset) = scrollPositionBeforeKeyboard!!
                    // Clamp index to valid range
                    val validIndex = savedIndex.coerceIn(0, timelineItems.lastIndex.coerceAtLeast(0))
                    if (validIndex >= 0) {
                        listState.scrollToItem(validIndex, savedOffset)
                        isAttachedToBottom = false // Keep detached state
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard closed, restored scroll position: index=$validIndex, offset=$savedOffset (was NOT attached)")
                    }
                    scrollPositionBeforeKeyboard = null
                }
            }
        }
        
        previousImeBottom = imeBottom
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
                        roomState = currentRoomState,
                        fallbackName = displayRoomName,
                        fallbackAvatarUrl = displayAvatarUrl,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        roomId = roomId,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onHeaderClick = {
                            // Navigate to room info screen
                            navController.navigate("room_info/$roomId")
                        },
                        onCallClick = {
                            navController.navigate("element_call/$roomId")
                        },
                        onRefreshClick = {
                            // Full refresh: drop all on-disk and in-RAM data, then fetch 100 events
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Full refresh button clicked for room $roomId")
                            isRefreshing = true
                            appViewModel.setAutoPaginationEnabled(false, "manual_refresh_ui_$roomId")
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
                    
                    // Show upload status when uploads are in progress
                    if (appViewModel.hasUploadInProgress(roomId)) {
                        val uploadType = appViewModel.getUploadType(roomId)
                        val statusText = when (uploadType) {
                            "video" -> "Uploading video..."
                            "audio" -> "Uploading audio..."
                            "file" -> "Uploading file..."
                            "image" -> "Uploading image..."
                            else -> "Uploading media..."
                        }
                        ExpressiveStatusRow(
                            text = statusText,
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
                            bridgeInfo = currentRoomState?.bridgeInfo,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // CRITICAL FIX: Show "Room loading..." while room is being loaded/processed
                        // This ensures the UI doesn't show incomplete state when navigating to a room
                        // Show loading when: isLoading is true OR timeline is empty and we're waiting for initial load
        val shouldShowLoading = !readinessCheckComplete || isLoading || (timelineItems.isEmpty() && !hasInitialSnapCompleted)
                        
                        if (shouldShowLoading) {
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
                                        text = "Room loading...",
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
                                    is TimelineItem.DateDivider -> {
                                        DateDivider(item.date)
                                    }
                                    is TimelineItem.Event -> {
                                        val event = item.event
                                        // PERFORMANCE: Removed logging from item rendering to improve scroll performance
                                        // Note: myUserId is non-null String, so myUserId != null check is redundant
                                        val isMine = event.sender == myUserId

                                        // PERFORMANCE: Use pre-computed consecutive flag instead of index-based lookup
                                        val isConsecutive = item.isConsecutive

                                        val threadInfo = event.getThreadInfo()
                                        if (threadInfo != null) {
                                            if (BuildConfig.DEBUG) Log.d(
                                                "Andromuks",
                                                "RoomTimelineScreen: Rendering thread event ${event.eventId} -> root=${threadInfo.threadRootEventId}, fallbackReply=${threadInfo.fallbackReplyToEventId ?: "<none>"}"
                                            )
                                        } else if (
                                            event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.thread" ||
                                            event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.thread"
                                        ) {
                                            val relationType = event.relationType
                                            val relatesToId = event.relatesTo
                                            val rawRelType = event.content?.optJSONObject("m.relates_to")?.optString("rel_type")
                                            val decryptedRelType = event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type")
                                            val rawEventId = event.content?.optJSONObject("m.relates_to")?.optString("event_id")
                                            val decryptedEventId = event.decrypted?.optJSONObject("m.relates_to")?.optString("event_id")
                                            Log.w(
                                                "Andromuks",
                                                "RoomTimelineScreen: Event ${event.eventId} has thread relates_to but getThreadInfo() returned null (relationType=$relationType, relatesTo=$relatesToId, rawRelType=$rawRelType, decryptedRelType=$decryptedRelType, rawEventId=$rawEventId, decryptedEventId=$decryptedEventId)"
                                            )
                                        }

                                        val threadRootIdFromRelates = event.content?.optJSONObject("m.relates_to")?.optString("event_id")
                                            ?: event.decrypted?.optJSONObject("m.relates_to")?.optString("event_id")
                                        if (threadRootIdFromRelates != null && threadInfo == null) {
                                            if (BuildConfig.DEBUG) Log.d(
                                                "Andromuks",
                                                "RoomTimelineScreen: Event ${event.eventId} relates_to thread root $threadRootIdFromRelates but threadInfo is null"
                                            )
                                        }

                                        TimelineEventItem(
                                            event = event,
                                            timelineEvents = timelineEvents,
                                            homeserverUrl = homeserverUrl,
                                            authToken = authToken,
                                            userProfileCache = memberMapWithFallback,
                                            isMine = isMine,
                                            myUserId = myUserId,
                                            isConsecutive = isConsecutive,
                                            appViewModel = appViewModel,
                                            sharedTransitionScope = sharedTransitionScope,  // ← ADD THIS
                                            animatedVisibilityScope = animatedVisibilityScope,  // ← ADD THIS
                                            onScrollToMessage = { eventId ->
                                                // PERFORMANCE: Find the index in timelineItems instead of sortedEvents
                                                val index = timelineItems.indexOfFirst { item ->
                                                    when (item) {
                                                        is TimelineItem.Event -> item.event.eventId == eventId
                                                        is TimelineItem.DateDivider -> false
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
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Room link clicked: ${roomLink.roomIdOrAlias}")
                                                
                                                // If it's a room ID, check if we're already joined
                                                val existingRoom = if (roomLink.roomIdOrAlias.startsWith("!")) {
                                                    val room = appViewModel.getRoomById(roomLink.roomIdOrAlias)
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Checked for existing room ${roomLink.roomIdOrAlias}, found: ${room != null}")
                                                    room
                                                } else {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Room link is an alias, showing joiner")
                                                    null
                                                }
                                                
                                                if (existingRoom != null) {
                                                    // Already joined, navigate directly
                                                    val targetRoomId = roomLink.roomIdOrAlias
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Already joined, navigating to $targetRoomId")
                                                    // CRITICAL: When navigating from one room_timeline to another, use setDirectRoomNavigation
                                                    // and navigate via room_list, letting RoomListScreen handle the final navigation.
                                                    // This matches the pattern used by notifications/shortcuts and ensures proper state management.
                                                    appViewModel.setCurrentRoomIdForTimeline(targetRoomId)
                                                    appViewModel.setDirectRoomNavigation(targetRoomId)
                                                    navController.navigate("room_list")
                                                } else {
                                                    // For aliases or non-joined rooms, show room joiner
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Not joined, showing room joiner")
                                                    roomLinkToJoin = roomLink
                                                    showRoomJoiner = true
                                                }
                                            },
                                            onThreadClick = { threadEvent ->
                                                // Navigate to thread viewer
                                                val threadInfo = threadEvent.getThreadInfo()
                                                if (threadInfo != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Thread message clicked, opening thread for root: ${threadInfo.threadRootEventId}")
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
                                .imePadding()
                    ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                                        userProfileCache = memberMapWithFallback, // Use reactive memberMap with fallback profiles
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
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: @ detected, requesting fresh member list for room $roomId")
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
                                                    val networkName = currentRoomState?.bridgeInfo?.displayName
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
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                        // Show expressive indicator when uploads or sends are in progress
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
                                    "RoomTimelineScreen: FAB clicked, scrolling to bottom and re-attaching"
                                )
                            }
                        },
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(
                                    end = 16.dp,
                                    bottom = 60.dp // Closer to text input
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
                
                // Floating emoji shortcode suggestion list
                if (showEmojiSuggestionList) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 72.dp, // Align with text input (attach button width + spacing)
                                bottom = 60.dp  // Closer to text input
                            )
                            .navigationBarsPadding()
                            .imePadding()
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
                                    
                                    // Update recent emojis (reuse logic from EmojiSelectionDialog for custom emojis)
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
                            // Close dialog immediately - upload will continue in background
                            showMediaPreview = false
                            
                            // Clear media selection state immediately so user can select new media
                            val mediaUriToUpload = selectedMediaUri
                            val audioUriToUpload = selectedAudioUri
                            val fileUriToUpload = selectedFileUri
                            val isVideoToUpload = selectedMediaIsVideo
                            
                            // Clear state immediately
                            selectedMediaUri = null
                            selectedAudioUri = null
                            selectedFileUri = null
                            selectedMediaIsVideo = false
                            
                            // Upload and send in background
                            coroutineScope.launch {
                                try {
                                    when {
                                        isVideoToUpload && mediaUriToUpload != null -> {
                                            // Upload video with thumbnail
                                            appViewModel.beginUpload(roomId, "video")
                                            try {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Starting video upload")
                                                val videoResult = VideoUploadUtils.uploadVideo(
                                                    context = context,
                                                    uri = mediaUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false
                                                )
                                            
                                                if (videoResult != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Video upload successful, sending message")
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
                                                } else {
                                                    Log.e("Andromuks", "RoomTimelineScreen: Video upload failed")
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload video",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } finally {
                                                appViewModel.endUpload(roomId, "video")
                                            }
                                        }
                                        audioUriToUpload != null -> {
                                            // Upload audio
                                            appViewModel.beginUpload(roomId, "audio")
                                            try {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Starting audio upload")
                                                val audioResult = MediaUploadUtils.uploadAudio(
                                                    context = context,
                                                    uri = audioUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false
                                                )
                                                
                                                if (audioResult != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Audio upload successful, sending message")
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
                                                } else {
                                                    Log.e("Andromuks", "RoomTimelineScreen: Audio upload failed")
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload audio",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } finally {
                                                appViewModel.endUpload(roomId, "audio")
                                            }
                                        }
                                        fileUriToUpload != null -> {
                                            // Upload file
                                            appViewModel.beginUpload(roomId, "file")
                                            try {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Starting file upload")
                                                val fileResult = MediaUploadUtils.uploadFile(
                                                    context = context,
                                                    uri = fileUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false
                                                )
                                                
                                                if (fileResult != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: File upload successful, sending message")
                                                    // Send file message with metadata
                                                    appViewModel.sendFileMessage(
                                                        roomId = roomId,
                                                        mxcUrl = fileResult.mxcUrl,
                                                        filename = fileResult.filename,
                                                        size = fileResult.size,
                                                        mimeType = fileResult.mimeType,
                                                        caption = caption.takeIf { it.isNotBlank() }
                                                    )
                                                } else {
                                                    Log.e("Andromuks", "RoomTimelineScreen: File upload failed")
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload file",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } finally {
                                                appViewModel.endUpload(roomId, "file")
                                            }
                                        }
                                        mediaUriToUpload != null -> {
                                            // Upload image
                                            appViewModel.beginUpload(roomId, "image")
                                            try {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Starting image upload (compressOriginal: $compressOriginal)")
                                                val uploadResult = MediaUploadUtils.uploadMedia(
                                                    context = context,
                                                    uri = mediaUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false,
                                                    compressOriginal = compressOriginal
                                                )
                                                
                                                if (uploadResult != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Image upload successful, sending message")
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
                                                } else {
                                                    Log.e("Andromuks", "RoomTimelineScreen: Image upload failed")
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload image",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } finally {
                                                appViewModel.endUpload(roomId, "image")
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "RoomTimelineScreen: Upload error", e)
                                    // Try to clean up upload state - determine type from what was being uploaded
                                    when {
                                        isVideoToUpload && mediaUriToUpload != null -> appViewModel.endUpload(roomId, "video")
                                        audioUriToUpload != null -> appViewModel.endUpload(roomId, "audio")
                                        fileUriToUpload != null -> appViewModel.endUpload(roomId, "file")
                                        mediaUriToUpload != null -> appViewModel.endUpload(roomId, "image")
                                        else -> appViewModel.endUpload(roomId, "image")
                                    }
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
                
                // Uploading dialog removed - uploads now happen in background with status row indicator
                
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







@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RoomHeader(
    roomState: RoomState?,
    fallbackName: String,
    fallbackAvatarUrl: String? = null,
    homeserverUrl: String,
    authToken: String,
    roomId: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    onHeaderClick: () -> Unit = {},
    onCallClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {}
) {
    // Debug logging
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: roomState = $roomState")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: fallbackName = $fallbackName")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: homeserverUrl = $homeserverUrl")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: authToken = ${authToken.take(10)}...")
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
            Box(
                modifier = Modifier.clickable(onClick = onHeaderClick)
            ) {
                if (sharedTransitionScope != null && animatedVisibilityScope != null && roomId != null) {
                    val sharedKey = "avatar-${roomId}"
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: sharedKey = $sharedKey")
                    with(sharedTransitionScope) {
                        AvatarImage(
                            mxcUrl = roomState?.avatarUrl ?: fallbackAvatarUrl,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            fallbackText = roomState?.name ?: fallbackName,
                            size = 48.dp,
                            userId = roomId,
                            displayName = roomState?.name ?: fallbackName,
                            isVisible = true, // Always visible for room header
                            modifier = Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = sharedKey),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    },
                                    renderInOverlayDuringTransition = true,
                                    zIndexInOverlay = 1f
                                )
                                .clip(CircleShape)
                        )
                    }
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: failed, we loaded the ELSE block")
                    AvatarImage(
                        mxcUrl = roomState?.avatarUrl ?: fallbackAvatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = roomState?.name ?: fallbackName,
                        size = 48.dp,
                        userId = roomId ?: roomState?.roomId,
                        displayName = roomState?.name ?: fallbackName,
                        isVisible = true, // Always visible for room header
                        modifier = Modifier.clip(CircleShape)
                    )
                }
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
            
            val bridgeInfo = roomState?.bridgeInfo
            IconButton(onClick = onCallClick) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Videocam,
                    contentDescription = "Start call",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                        imageVector = androidx.compose.material.icons.Icons.Filled.Refresh,
                        contentDescription = "Refresh timeline",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
