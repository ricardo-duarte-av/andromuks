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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Launch
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
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.CustomBubbleTextField
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.EmoteEventNarrator
import net.vrkknn.andromuks.utils.HtmlMessageText
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
        override val stableKey: String get() = event.eventId
    }

    data class DateDivider(val date: String) : BubbleTimelineItem() {
        override val stableKey: String get() = "date_$date"
    }
}

/** PERFORMANCE: Helper function to process timeline events in background */
suspend fun bubbleProcessTimelineEvents(
    timelineEvents: List<TimelineEvent>,
    showUnprocessedEvents: Boolean,
    allowedEventTypes: Set<String>
): List<TimelineEvent> = withContext(Dispatchers.Default) {
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "BubbleTimelineScreen: Background processing ${timelineEvents.size} timeline events, showUnprocessedEvents: $showUnprocessedEvents"
    )

    // Debug: Log event types in timeline
    val eventTypes = timelineEvents.groupBy { it.type }
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "BubbleTimelineScreen: Event types in timeline: ${eventTypes.map { "${it.key}: ${it.value.size}" }.joinToString(", ")}"
    )

    val filteredEvents = if (showUnprocessedEvents) {
        // Show all events when unprocessed events are enabled, but always exclude redaction events
        val filtered = timelineEvents.filter { event -> event.type != "m.room.redaction" }
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: After redaction filtering: ${filtered.size} events")
        filtered
    } else {
        // Only show allowed events when unprocessed events are disabled
        val filtered = timelineEvents.filter { event -> allowedEventTypes.contains(event.type) }
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: After type filtering: ${filtered.size} events")
        filtered
    }

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleTimelineScreen(
    roomId: String,
    roomName: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    onCloseBubble: () -> Unit = {},
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
    DisposableEffect(roomId) {
        BubbleTracker.onBubbleOpened(roomId)
        // When the screen is composed, the bubble is visible
        BubbleTracker.onBubbleVisible(roomId)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Tracked bubble opened and visible for room: $roomId")
        onDispose {
            // When the screen is disposed, the bubble is no longer visible
            BubbleTracker.onBubbleInvisible(roomId)
            BubbleTracker.onBubbleClosed(roomId)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Tracked bubble invisible and closed for room: $roomId")
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

    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "BubbleTimelineScreen: Timeline events count: ${timelineEvents.size}, isLoading: $isLoading"
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

    // Media picker state
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMediaIsVideo by remember { mutableStateOf(false) }
    var showMediaPreview by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    
    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    
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
                    val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
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
    LaunchedEffect(timelineEvents, appViewModel.showUnprocessedEvents, appViewModel.timelineUpdateCounter) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "BubbleTimelineScreen: Processing timelineEvents update - size=${timelineEvents.size}, updateCounter=${appViewModel.timelineUpdateCounter}, roomId=$roomId"
        )
        sortedEvents = bubbleProcessTimelineEvents(
            timelineEvents = timelineEvents,
            showUnprocessedEvents = appViewModel.showUnprocessedEvents,
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

    // Get member map that observes memberUpdateCounter and includes global cache fallback for TimelineEventItem profile updates
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter, sortedEvents) {
        appViewModel.getMemberMapWithFallback(roomId, sortedEvents)
    }

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()

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
    
    // Track scroll position using event ID anchor (more robust than index)
    var anchorEventIdForRestore by remember { mutableStateOf<String?>(null) }
    var anchorScrollOffsetForRestore by remember { mutableStateOf(0) }
    var pendingScrollRestoration by remember { mutableStateOf(false) }

    // Monitor scroll position to detect if user is at bottom or has detached
    // Also trigger automatic pagination when near the top
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

    // Detect when pagination completes and trigger scroll restoration
    LaunchedEffect(timelineItems.size) {
        // When pagination finishes and we have an anchor event to restore to
        if (!appViewModel.isPaginating && pendingScrollRestoration && anchorEventIdForRestore != null) {
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
                Log.w("Andromuks", "BubbleTimelineScreen: ⚠️ Could not find anchor event $anchorEventIdForRestore in new timeline")
            }
            
            // Clear restoration state
            pendingScrollRestoration = false
            anchorEventIdForRestore = null
            isLoadingMore = false
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
    LaunchedEffect(timelineItems.size, isLoading, appViewModel.isPaginating, appViewModel.timelineUpdateCounter, appViewModel.bubbleAnimationCompletionCounter) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "BubbleTimelineScreen: LaunchedEffect - timelineItems.size: ${timelineItems.size}, isLoading: $isLoading, isPaginating: ${appViewModel.isPaginating}, timelineUpdateCounter: ${appViewModel.timelineUpdateCounter}, hasInitialSnapCompleted: $hasInitialSnapCompleted"
        )

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
                    appViewModel.enableAnimationsForRoom(roomId)
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

        val allAnimationsCompleted = !appViewModel.isBubbleAnimationRunning()

        if (
            hasNewItems &&
                isAttachedToBottom &&
                lastEventId != null &&
                lastEventId != lastKnownTimelineEventId &&
                allAnimationsCompleted
        ) {
            coroutineScope.launch {
                listState.animateScrollToItem(timelineItems.lastIndex)
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
    LaunchedEffect(appViewModel.bubbleAnimationCompletionCounter, isAttachedToBottom) {
        if (isAttachedToBottom && timelineItems.isNotEmpty() && !isLoading && !pendingScrollRestoration) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Individual bubble animation completed, auto-scrolling to bottom")
            coroutineScope.launch {
                listState.animateScrollToItem(timelineItems.lastIndex)
            }
        }
    }

    LaunchedEffect(roomId) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Loading timeline for room: $roomId")
        // Reset state for new room
        isLoadingMore = false
        pendingScrollRestoration = false
        anchorEventIdForRestore = null
        hasLoadedInitialBatch = false
        isAttachedToBottom = true
        isInitialLoad = true
        hasInitialSnapCompleted = false
        appViewModel.ensureTimelineCacheIsFresh(roomId)
        // Request room state first, then timeline
        appViewModel.requestRoomState(roomId)
        appViewModel.requestRoomTimeline(roomId)
    }
    
    // Refresh timeline when app resumes (to show new events received while suspended)
    LaunchedEffect(appViewModel.timelineRefreshTrigger) {
        if (appViewModel.timelineRefreshTrigger > 0 && appViewModel.currentRoomId == roomId) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: App resumed, refreshing timeline for room: $roomId")
            // Don't reset state flags - this is just a refresh, not a new room load
            appViewModel.requestRoomTimeline(roomId)
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
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Timeline events changed - timelineEvents.size: ${timelineEvents.size}, currentRoomId: ${appViewModel.currentRoomId}, roomId: $roomId")
        
        // Only react to changes for the current room
        if (appViewModel.currentRoomId == roomId) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Detected timeline update for current room $roomId with ${timelineEvents.size} events")
            
            // Force recomposition when timeline events change
            // This ensures the UI updates even when battery optimization might skip updates
        }
    }

    // Handle Android back key
    BackHandler { onCloseBubble() }

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
                    BubbleRoomHeader(
                        roomState = appViewModel.currentRoomState,
                        fallbackName = displayRoomName,
                        fallbackAvatarUrl = displayAvatarUrl,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        roomId = roomId,
                        bridgeInfo = appViewModel.getBridgeInfo(roomId),
                        onHeaderClick = {
                            navController.navigate("room_info/$roomId")
                        },
                        onOpenInApp = onOpenInApp,
                        onCloseBubble = onCloseBubble,
                        onRefreshClick = {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Full refresh button clicked for room $roomId")
                            isRefreshing = true
                            appViewModel.setAutoPaginationEnabled(false, "bubble_manual_refresh_ui_$roomId")
                            appViewModel.fullRefreshRoomTimeline(roomId)
                        }
                    )

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
                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Loading timeline...")
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = listState,
                            // PERFORMANCE: Optimize for timeline rendering with proper padding and settings
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
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
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
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
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Already joined, navigating to ${roomLink.roomIdOrAlias}")
                                                    val encodedRoomId = java.net.URLEncoder.encode(roomLink.roomIdOrAlias, "UTF-8")
                                                    navController.navigate("room_timeline/$encodedRoomId")
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
                                            onNewBubbleAnimationStart = { messageSoundPlayer.play() }
                                        )
                                    }
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
                                onClick = { showAttachmentMenu = !showAttachmentMenu },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Attach",
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
                                CustomBubbleTextField(
                                    value = textFieldValue,
                                    onValueChange = { newValue: TextFieldValue ->
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
                                        IconButton(
                                            onClick = { showEmojiPickerForText = true },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Mood,
                                                contentDescription = "Emoji",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        keyboardType = KeyboardType.Text,
                                        autoCorrect = true,
                                        imeAction = ImeAction.Default // Enter always creates newline, send button always sends
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
                                    visualTransformation = mentionTransformation
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
                            modifier = Modifier.size(buttonHeight), // Fixed height matching single-line text field
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
                                    bottom = 16.dp
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
                
                // Attachment menu overlay
                if (showAttachmentMenu) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 16.dp, // Same as text input padding
                                bottom = 80.dp // Above text input area
                            )
                            .navigationBarsPadding()
                            .zIndex(5f) // Ensure it's above other content
                    ) {
                        AnimatedVisibility(
                            visible = showAttachmentMenu,
                            enter = expandVertically(animationSpec = tween(200)),
                            exit = shrinkVertically(animationSpec = tween(200))
                        ) {
                            Column {
                                // Files option (top)
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.width(48.dp).height(56.dp)
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
                                
                                // Audio option (middle)
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.width(48.dp).height(56.dp)
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
                                
                                // Image/Video option (bottom, closest to main button)
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.width(48.dp).height(56.dp)
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
                        }
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
                            appViewModel.updateRecentEmojis(emoji)
                            
                            // Don't close the picker - user might want to add more emojis
                        },
                        onDismiss = {
                            showEmojiPickerForText = false
                        }
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
                        onSend = { caption ->
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
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Starting image upload")
                                            val uploadResult = MediaUploadUtils.uploadMedia(
                                                context = context,
                                                uri = selectedMediaUri!!,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
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
                                                    caption = caption.takeIf { it.isNotBlank() }
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
    bridgeInfo: BridgeInfo? = null,
    onHeaderClick: () -> Unit = {},
    onOpenInApp: () -> Unit = {},
    onCloseBubble: () -> Unit = {},
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
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenInApp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Launch,
                        contentDescription = "Open in app",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefreshClick) {
                    if (bridgeInfo != null && bridgeInfo.protocol.avatarUrl != null) {
                        AvatarImage(
                            mxcUrl = bridgeInfo.protocol.avatarUrl,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            fallbackText = bridgeInfo.protocol.displayname.take(1),
                            size = 24.dp,
                            userId = bridgeInfo.protocol.id,
                            displayName = bridgeInfo.protocol.displayname
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh timeline",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onCloseBubble) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close bubble",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
