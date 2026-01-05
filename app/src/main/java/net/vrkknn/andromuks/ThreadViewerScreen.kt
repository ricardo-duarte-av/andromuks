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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Mood
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import net.vrkknn.andromuks.utils.MediaPreviewDialog
import net.vrkknn.andromuks.utils.UploadingDialog
import net.vrkknn.andromuks.utils.MediaUploadUtils
import net.vrkknn.andromuks.utils.VideoUploadUtils
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.CustomBubbleTextField
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.EmojiShortcodes
import net.vrkknn.andromuks.utils.EmojiSuggestionList
import net.vrkknn.andromuks.utils.StickerSelectionDialog
import net.vrkknn.andromuks.utils.ReplyPreviewInput
import net.vrkknn.andromuks.utils.navigateToUserInfo
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.TypingNotificationArea
import net.vrkknn.andromuks.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


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
    
    // Track thread messages reactively so new sends/deletes render without reopening
    var threadMessages by remember(roomId) { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    LaunchedEffect(roomId) {
        threadMessages = appViewModel.getThreadMessages(roomId, threadRootEventId)
    }
    LaunchedEffect(appViewModel.timelineUpdateCounter, roomId, threadRootEventId) {
        threadMessages = appViewModel.getThreadMessages(roomId, threadRootEventId)
    }
    // Ensure the ViewModel treats this room as current so timeline updates and send_complete are applied
    LaunchedEffect(roomId) {
        appViewModel.setCurrentRoomIdForTimeline(roomId)
        // Ensure timeline is fresh when opening the thread viewer
        appViewModel.requestRoomTimeline(roomId)
    }
    // Events are in-memory cache only - no DB observation needed
    // Timeline updates come from sync_complete and pagination

    // Attachment/media state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var selectedMediaUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedAudioUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedMediaIsVideo by remember { mutableStateOf(false) }
    var showMediaPreview by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var cameraPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var cameraVideoUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Media pickers
    val mediaPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
            uri?.let {
                val mime = context.contentResolver.getType(it) ?: ""
                selectedMediaIsVideo = mime.startsWith("video/")
                selectedMediaUri = it
                selectedAudioUri = null
                selectedFileUri = null
                showMediaPreview = true
            }
        }
    val audioPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
            uri?.let {
                selectedAudioUri = it
                selectedMediaUri = null
                selectedFileUri = null
                selectedMediaIsVideo = false
                showMediaPreview = true
            }
        }
    val filePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
            uri?.let {
                selectedFileUri = it
                selectedAudioUri = null
                selectedMediaUri = null
                val mime = context.contentResolver.getType(it) ?: ""
                selectedMediaIsVideo = mime.startsWith("video/")
                showMediaPreview = true
            }
        }
    val cameraPhotoLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraPhotoUri != null) {
                selectedMediaUri = cameraPhotoUri
                selectedMediaIsVideo = false
                selectedAudioUri = null
                selectedFileUri = null
                showMediaPreview = true
            }
            cameraPhotoUri = null
        }
    val cameraVideoLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.CaptureVideo()) { success ->
            if (success && cameraVideoUri != null) {
                selectedMediaUri = cameraVideoUri
                selectedMediaIsVideo = true
                selectedAudioUri = null
                selectedFileUri = null
                showMediaPreview = true
            }
            cameraVideoUri = null
        }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(context, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    fun createCameraFileUri(isVideo: Boolean): android.net.Uri? {
        return try {
            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = if (isVideo) "VID_${timeStamp}.mp4" else "IMG_${timeStamp}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES
                    )
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
    // Reply/Edit state
    var replyingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    var editingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Emoji selection state
    var showEmojiSelection by remember { mutableStateOf(false) }
    var reactingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }

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
    var showEmojiPickerForText by remember { mutableStateOf(false) }
    var showStickerPickerForText by remember { mutableStateOf(false) }
    
    // Track websocket connection state
    var websocketConnected by remember { mutableStateOf(appViewModel.isWebSocketConnected()) }
    LaunchedEffect(Unit) {
        while (true) {
            websocketConnected = appViewModel.isWebSocketConnected()
            kotlinx.coroutines.delay(500) // Poll every 500ms
        }
    }
    
    // Combined input enabled state (threads allow all members to reply, so only check websocket)
    val isInputEnabled = websocketConnected
    
    // Text input state (moved here to be accessible by mention handler)
    var draft by remember { mutableStateOf("") }
    var lastTypingTime by remember { mutableStateOf(0L) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    // Track text field height to size the send button
    var textFieldHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val buttonHeight = remember(textFieldHeight, density) {
        if (textFieldHeight > 0) {
            with(density) { textFieldHeight.toDp() }
        } else 40.dp
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

    // Define allowed event types (whitelist approach)
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
        )

    // PERFORMANCE: Use background processing for heavy filtering and sorting operations
    var sortedEvents by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }

    // Process thread events in background when dependencies change
    LaunchedEffect(threadMessages, appViewModel.showUnprocessedEvents, appViewModel.timelineUpdateCounter) {
        sortedEvents = processTimelineEvents(
            timelineEvents = threadMessages,
            showUnprocessedEvents = appViewModel.showUnprocessedEvents,
            allowedEventTypes = allowedEventTypes
        )
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
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter, sortedEvents) {
        appViewModel.getMemberMapWithFallback(roomId, sortedEvents)
    }

    // Show mention list when full member list finishes loading
    LaunchedEffect(appViewModel.memberUpdateCounter, isWaitingForFullMemberList) {
        if (isWaitingForFullMemberList && appViewModel.memberUpdateCounter > lastMemberUpdateCounterBeforeMention) {
            val memberMapNow = appViewModel.getMemberMap(roomId)
            if (memberMapNow.isNotEmpty()) {
                showMentionList = true
                isWaitingForFullMemberList = false
            }
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

            // Trigger only if deleting inside the markdown
            if (cursor >= markdownStart && cursor < markdownEnd && deletedLength == 1) {
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

    // Replace completed :shortcode: with emoji or custom emoji markdown
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

    // Create timeline items with date dividers
    val timelineItems =
        remember(sortedEvents) {
            val items = mutableListOf<TimelineItem>()
            var lastDate: String? = null
            var previousEvent: TimelineEvent? = null

            for (event in sortedEvents) {
                val eventDate = formatDate(event.timestamp)

                // Add date divider if this is a new date
                if (lastDate == null || eventDate != lastDate) {
                    items.add(TimelineItem.DateDivider(eventDate))
                    lastDate = eventDate
                }

                val hasPerMessageProfile =
                    event.content?.has("com.beeper.per_message_profile") == true ||
                        event.decrypted?.has("com.beeper.per_message_profile") == true

                val isConsecutive =
                    !hasPerMessageProfile && previousEvent?.sender == event.sender

                // Add the event
                items.add(
                    TimelineItem.Event(
                        event = event,
                        isConsecutive = isConsecutive,
                        hasPerMessageProfile = hasPerMessageProfile
                    )
                )
                previousEvent = event
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
                                    val isMine = event.sender == myUserId

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
                                            val index = timelineItems.indexOfFirst { item ->
                                                (item as? TimelineItem.Event)?.event?.eventId == eventId
                                                }
                                            if (index >= 0) {
                                                coroutineScope.launch {
                                                    listState.scrollToItem(index)
                                                }
                                            }
                                        },
                                        onReply = { event ->
                                            replyingToEvent = event
                                            // Scroll near the bottom to keep context like main timeline
                                            coroutineScope.launch {
                                                val lastIndex = timelineItems.lastIndex
                                                if (lastIndex >= 0) {
                                                    listState.scrollToItem(maxOf(lastIndex - 2, 0))
                                                }
                                            }
                                        },
                                        onReact = { event ->
                                            reactingToEvent = event
                                            showEmojiSelection = true
                                        },
                                        onEdit = { event ->
                                            editingEvent = event
                                        },
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
                            // Main attach button (outside the text field)
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

                            // Pill-shaped text input
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column {
                                    // Reply preview (thread reply target)
                                    if (replyingToEvent != null) {
                                        ReplyPreviewInput(
                                            event = replyingToEvent!!,
                                            userProfileCache =
                                                memberMap.mapValues { (_, profile) ->
                                                    MemberProfile(profile.displayName, profile.avatarUrl)
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

                                    // Text input field with mention + emoji shortcode support
                                    val mentionAndEmojiTransformation = remember(colorScheme, appViewModel.customEmojiPacks) {
                                        VisualTransformation { text ->
                                            val mentionRegex = Regex("""\[((?:[^\[\]\\]|\\.)*)\]\(https://matrix\.to/#/([^)]+)\)""")
                                            val customEmojiRegex = Regex("""!\[:([^:]+):\]\((mxc://[^)]+)\s+"[^"]*"\)""")

                                            val annotatedString = buildAnnotatedString {
                                                var lastIndex = 0

                                                val allMatches = mutableListOf<Pair<Int, MatchResult>>()
                                                mentionRegex.findAll(text.text).forEach { allMatches.add(Pair(0, it)) }
                                                customEmojiRegex.findAll(text.text).forEach { allMatches.add(Pair(1, it)) }
                                                allMatches.sortBy { it.second.range.first }

                                                for ((type, match) in allMatches) {
                                                    if (match.range.first > lastIndex) {
                                                        append(text.text.substring(lastIndex, match.range.first))
                                                    }
                                                    if (type == 0) {
                                                        val escapedDisplayName = match.groupValues[1]
                                                        val displayName = escapedDisplayName.replace("\\[", "[").replace("\\]", "]")
                                                        withStyle(
                                                            style = SpanStyle(
                                                                color = colorScheme.onPrimaryContainer,
                                                                background = colorScheme.primaryContainer
                                                            )
                                                        ) { append(" $displayName ") }
                                                    } else {
                                                        val emojiName = match.groupValues[1]
                                                        append(":$emojiName:")
                                                    }
                                                    lastIndex = match.range.last + 1
                                                }
                                                if (lastIndex < text.text.length) {
                                                    append(text.text.substring(lastIndex))
                                                }
                                            }

                                            val offsetMapping = object : OffsetMapping {
                                                override fun originalToTransformed(offset: Int): Int {
                                                    val clampedOffset = offset.coerceIn(0, text.text.length)
                                                    var transformedOffset = 0
                                                    var originalOffset = 0
                                                    val allMatches = mutableListOf<Pair<Int, MatchResult>>()
                                                    mentionRegex.findAll(text.text).forEach { allMatches.add(Pair(0, it)) }
                                                    customEmojiRegex.findAll(text.text).forEach { allMatches.add(Pair(1, it)) }
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
                                                            val escapedDisplayName = match.groupValues[1]
                                                            val displayName = escapedDisplayName.replace("\\[", "[").replace("\\]", "]")
                                                            " $displayName ".length
                                                        } else {
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

                                                    val result = transformedOffset + (clampedOffset - originalOffset)
                                                    return result.coerceIn(0, annotatedString.length)
                                                }

                                                override fun transformedToOriginal(offset: Int): Int {
                                                    val clampedOffset = offset.coerceIn(0, annotatedString.length)
                                                    var transformedOffset = 0
                                                    var originalOffset = 0
                                                    val allMatches = mutableListOf<Pair<Int, MatchResult>>()
                                                    mentionRegex.findAll(text.text).forEach { allMatches.add(Pair(0, it)) }
                                                    customEmojiRegex.findAll(text.text).forEach { allMatches.add(Pair(1, it)) }
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
                                                            val escapedDisplayName = match.groupValues[1]
                                                            val displayName = escapedDisplayName.replace("\\[", "[").replace("\\]", "]")
                                                            " $displayName ".length
                                                        } else {
                                                            val emojiName = match.groupValues[1]
                                                            ":$emojiName:".length
                                                        }

                                                        if (clampedOffset <= transformedOffset + transformedLength) {
                                                            return match.range.last + 1
                                                        }

                                                        transformedOffset += transformedLength
                                                        originalOffset = match.range.last + 1
                                                    }

                                                    val result = originalOffset + (clampedOffset - transformedOffset)
                                                    return result.coerceIn(0, text.text.length)
                                                }
                                            }

                                            TransformedText(annotatedString, offsetMapping)
                                        }
                                    }

                                    CustomBubbleTextField(
                                        value = textFieldValue,
                                        enabled = isInputEnabled,
                                        onValueChange = { newValue ->
                                            // Custom emoji backspace handling
                                            val afterDeletion = handleCustomEmojiDeletion(textFieldValue, newValue)
                                            val replacedValue = applyCompletedEmojiShortcode(afterDeletion)
                                            textFieldValue = replacedValue
                                            draft = replacedValue.text
                                            
                                            // Detect mentions
                                            val mentionResult = detectMention(replacedValue.text, replacedValue.selection.start)
                                            if (mentionResult != null) {
                                                val (query, startIndex) = mentionResult
                                                mentionQuery = query
                                                mentionStartIndex = startIndex

                                                if (!isWaitingForFullMemberList && !showMentionList) {
                                                    val memberMapCurrent = appViewModel.getMemberMap(roomId)
                                                    if (memberMapCurrent.isEmpty() || memberMapCurrent.size < 10) {
                                                        // Profiles are loaded opportunistically when rendering events
                                                        // Request full member list to populate cache
                                                        isWaitingForFullMemberList = true
                                                        lastMemberUpdateCounterBeforeMention = appViewModel.memberUpdateCounter
                                                        appViewModel.requestFullMemberList(roomId)
                                                    } else {
                                                        showMentionList = true
                                                    }
                                                }
                                            } else {
                                                showMentionList = false
                                                isWaitingForFullMemberList = false
                                            }

                                            // Detect emoji shortcodes
                                            val emojiResult = detectEmojiShortcode(replacedValue.text, replacedValue.selection.start)
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
                                                text = if (websocketConnected) "Reply in thread..." else "Waiting for connection...",
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
                                            imeAction = ImeAction.Send
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onSend = {
                                                if (draft.isNotBlank()) {
                                                    when {
                                                        editingEvent != null -> {
                                                            appViewModel.sendEdit(roomId, draft, editingEvent!!)
                                                            editingEvent = null
                                                        }
                                                        replyingToEvent != null -> {
                                                    appViewModel.sendThreadReply(
                                                        roomId = roomId,
                                                        text = draft,
                                                        threadRootEventId = threadRootEventId,
                                                                fallbackReplyToEventId = replyingToEvent!!.eventId
                                                            )
                                                            replyingToEvent = null
                                                        }
                                                        else -> {
                                                            val lastMessage = sortedEvents.lastOrNull()
                                                            appViewModel.sendThreadReply(
                                                                roomId = roomId,
                                                                text = draft,
                                                                threadRootEventId = threadRootEventId,
                                                    fallbackReplyToEventId = null
                                                            )
                                                        }
                                                    }
                                                    draft = ""
                                                }
                                            }
                                        ),
                                        visualTransformation = mentionAndEmojiTransformation
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
                                        when {
                                            editingEvent != null -> {
                                                appViewModel.sendEdit(roomId, draft, editingEvent!!)
                                                editingEvent = null
                                            }
                                            replyingToEvent != null -> {
                                        appViewModel.sendThreadReply(
                                            roomId = roomId,
                                            text = draft,
                                            threadRootEventId = threadRootEventId,
                                                    fallbackReplyToEventId = replyingToEvent!!.eventId
                                                )
                                                replyingToEvent = null
                                            }
                                            else -> {
                                                val lastMessageForSend = sortedEvents.lastOrNull()
                                                appViewModel.sendThreadReply(
                                                    roomId = roomId,
                                                    text = draft,
                                                    threadRootEventId = threadRootEventId,
                                                fallbackReplyToEventId = null
                                                )
                                            }
                                        }
                                        draft = ""
                                    }
                                },
                                enabled = draft.isNotBlank() && isInputEnabled,
                                shape = CircleShape,
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                modifier = Modifier.size(buttonHeight),
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
            }

            // Attachment menu overlay (floating, does not push composer)
            if (showAttachmentMenu) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 72.dp)
                        .navigationBarsPadding()
                        .imePadding()
                        .zIndex(6f),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    showAttachmentMenu = false
                                    mediaPickerLauncher.launch("*/*")
                                },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = "Image/Video",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    showAttachmentMenu = false
                                    audioPickerLauncher.launch("audio/*")
                                },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AudioFile,
                                    contentDescription = "Audio",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    showAttachmentMenu = false
                                    filePickerLauncher.launch("*/*")
                                },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = "File",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    val hasCam = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (hasCam) {
                                        val uri = createCameraFileUri(false)
                                        if (uri != null) {
                                            cameraPhotoUri = uri
                                            cameraPhotoLauncher.launch(uri)
                                            showAttachmentMenu = false
                                        } else {
                                            Toast.makeText(context, "Error creating camera file", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = "Camera Photo",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    val hasCam = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (hasCam) {
                                        val uri = createCameraFileUri(true)
                                        if (uri != null) {
                                            cameraVideoUri = uri
                                            cameraVideoLauncher.launch(uri)
                                            showAttachmentMenu = false
                                        } else {
                                            Toast.makeText(context, "Error creating camera file", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Videocam,
                                    contentDescription = "Camera Video",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
           //}
                
                // Emoji shortcode suggestion list
                if (showEmojiSuggestionList) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 72.dp,
                                bottom = 80.dp
                            )
                            .navigationBarsPadding()
                            .imePadding()
                            .zIndex(9f),
                        contentAlignment = Alignment.BottomStart
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
                                            } else baseReplacement
                                        } else baseReplacement
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
                            .fillMaxSize()
                            .padding(
                                start = 72.dp, // Align with text input (attach button width + spacing)
                                bottom = 80.dp  // Above text input
                            )
                            .navigationBarsPadding()
                            .imePadding(),
                        contentAlignment = Alignment.BottomStart
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
                
                // Emoji selection dialog for text input
                if (showEmojiPickerForText) {
                    EmojiSelectionDialog(
                        recentEmojis = appViewModel.recentEmojis,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onEmojiSelected = { emoji ->
                            val currentText = textFieldValue.text
                            val cursorPosition = textFieldValue.selection.start
                            val newText = currentText.substring(0, cursorPosition) +
                                emoji +
                                currentText.substring(cursorPosition)
                            val newCursorPosition = cursorPosition + emoji.length

                            draft = newText
                            textFieldValue = TextFieldValue(
                                text = newText,
                                selection = TextRange(newCursorPosition)
                            )

                            val emojiForRecent = if (emoji.startsWith("![:") && emoji.contains("mxc://")) {
                                val mxcStart = emoji.indexOf("mxc://")
                                if (mxcStart >= 0) {
                                    val mxcEnd = emoji.indexOf("\"", mxcStart)
                                    if (mxcEnd > mxcStart) {
                                        emoji.substring(mxcStart, mxcEnd)
                                    } else {
                                        emoji.substring(mxcStart)
                                    }
                                } else emoji
                            } else emoji
                            appViewModel.updateRecentEmojis(emojiForRecent)
                        },
                        onDismiss = { showEmojiPickerForText = false },
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
                            val mimeType = sticker.info?.optString("mimetype") ?: "image/png"
                            val size = sticker.info?.optLong("size") ?: 0L
                            val width = sticker.info?.optInt("w", 0) ?: 0
                            val height = sticker.info?.optInt("h", 0) ?: 0
                            val body = sticker.body ?: sticker.name

                            val replyTarget = replyingToEvent?.eventId ?: sortedEvents.lastOrNull()?.eventId
                            val mentionIds = replyingToEvent?.sender?.let { listOf(it) } ?: emptyList()
                            val isFallback = replyTarget == null || replyingToEvent == null

                            appViewModel.sendStickerMessage(
                                roomId = roomId,
                                mxcUrl = sticker.mxcUrl,
                                body = body,
                                mimeType = mimeType,
                                size = size,
                                width = width,
                                height = height,
                                threadRootEventId = threadRootEventId,
                                replyToEventId = replyTarget,
                                isThreadFallback = isFallback,
                                mentions = mentionIds
                            )

                            showStickerPickerForText = false
                            replyingToEvent = null
                        },
                        onDismiss = { showStickerPickerForText = false },
                        stickerPacks = appViewModel.stickerPacks
                    )
                }

                // Media preview dialog
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
                            showMediaPreview = false
                            isUploading = true
                            coroutineScope.launch {
                                try {
                                    when {
                                        selectedMediaIsVideo -> {
                                            val videoResult = VideoUploadUtils.uploadVideo(
                                                context = context,
                                                uri = currentUri,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
                                            )
                                            if (videoResult != null) {
                                                val replyTarget = replyingToEvent?.eventId ?: sortedEvents.lastOrNull()?.eventId
                                                val mentions = replyingToEvent?.sender?.let { listOf(it) } ?: emptyList()
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
                                                    caption = caption.takeIf { it.isNotBlank() },
                                                    threadRootEventId = threadRootEventId,
                                                    replyToEventId = replyTarget,
                                                    isThreadFallback = replyingToEvent == null,
                                                    mentions = mentions
                                                )
                                            } else {
                                                Toast.makeText(context, "Failed to upload video", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        isAudio -> {
                                            val audioResult = MediaUploadUtils.uploadAudio(
                                                context = context,
                                                uri = currentUri,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
                                            )
                                            if (audioResult != null) {
                                                val replyTarget = replyingToEvent?.eventId ?: sortedEvents.lastOrNull()?.eventId
                                                val mentions = replyingToEvent?.sender?.let { listOf(it) } ?: emptyList()
                                                appViewModel.sendAudioMessage(
                                                    roomId = roomId,
                                                    mxcUrl = audioResult.mxcUrl,
                                                    filename = audioResult.filename,
                                                    duration = audioResult.duration,
                                                    size = audioResult.size,
                                                    mimeType = audioResult.mimeType,
                                                    caption = caption.takeIf { it.isNotBlank() },
                                                    threadRootEventId = threadRootEventId,
                                                    replyToEventId = replyTarget,
                                                    isThreadFallback = replyingToEvent == null,
                                                    mentions = mentions
                                                )
                                            } else {
                                                Toast.makeText(context, "Failed to upload audio", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        isFile -> {
                                            val fileResult = MediaUploadUtils.uploadFile(
                                                context = context,
                                                uri = currentUri,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
                                            )
                                            if (fileResult != null) {
                                                val replyTarget = replyingToEvent?.eventId ?: sortedEvents.lastOrNull()?.eventId
                                                val mentions = replyingToEvent?.sender?.let { listOf(it) } ?: emptyList()
                                                appViewModel.sendFileMessage(
                                                    roomId = roomId,
                                                    mxcUrl = fileResult.mxcUrl,
                                                    filename = fileResult.filename,
                                                    size = fileResult.size,
                                                    mimeType = fileResult.mimeType,
                                                    caption = caption.takeIf { it.isNotBlank() },
                                                    threadRootEventId = threadRootEventId,
                                                    replyToEventId = replyTarget,
                                                    isThreadFallback = replyingToEvent == null,
                                                    mentions = mentions
                                                )
                                            } else {
                                                Toast.makeText(context, "Failed to upload file", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        else -> {
                                            val uploadResult = MediaUploadUtils.uploadMedia(
                                                context = context,
                                                uri = currentUri,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isEncrypted = false
                                            )
                                            if (uploadResult != null) {
                                                val replyTarget = replyingToEvent?.eventId ?: sortedEvents.lastOrNull()?.eventId
                                                val mentions = replyingToEvent?.sender?.let { listOf(it) } ?: emptyList()
                                                appViewModel.sendImageMessage(
                                                    roomId = roomId,
                                                    mxcUrl = uploadResult.mxcUrl,
                                                    width = uploadResult.width,
                                                    height = uploadResult.height,
                                                    size = uploadResult.size,
                                                    mimeType = uploadResult.mimeType,
                                                    blurHash = uploadResult.blurHash,
                                                    caption = caption.takeIf { it.isNotBlank() },
                                                    threadRootEventId = threadRootEventId,
                                                    replyToEventId = replyTarget,
                                                    isThreadFallback = replyingToEvent == null,
                                                    mentions = mentions
                                                )
                                            } else {
                                                Toast.makeText(context, "Failed to upload image", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "ThreadViewerScreen: Upload error", e)
                                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isUploading = false
                                    selectedMediaUri = null
                                    selectedAudioUri = null
                                    selectedFileUri = null
                                    selectedMediaIsVideo = false
                                    replyingToEvent = null
                                }
                            }
                        }
                    )
                }

                if (isUploading) {
                    UploadingDialog(isVideo = selectedMediaIsVideo)
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

