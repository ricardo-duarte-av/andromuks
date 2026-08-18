package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.LocalScrollHighlightState
import net.vrkknn.andromuks.ScrollHighlightState
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.BridgeNetworkBadge
import net.vrkknn.andromuks.ui.components.ContainedExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ExpressiveStatusRow
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.ui.theme.scaledColumnEnter
import net.vrkknn.andromuks.ui.theme.scaledColumnExit
import net.vrkknn.andromuks.ui.theme.scaledTweenMs
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.BotCommandSendOutcome
import net.vrkknn.andromuks.utils.CodeViewer
import net.vrkknn.andromuks.utils.CommandSuggestionList
import net.vrkknn.andromuks.utils.ComposerBotCommandOverlays
import net.vrkknn.andromuks.utils.CustomBubbleTextField
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.EmojiShortcodes
import net.vrkknn.andromuks.utils.EmojiSuggestionList
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.LocalActiveMessageMenuEventId
import net.vrkknn.andromuks.utils.MediaPreviewDialog
import net.vrkknn.andromuks.utils.MediaUploadUtils
import net.vrkknn.andromuks.utils.MessageMenuBar
import net.vrkknn.andromuks.utils.MessageMenuConfig
import net.vrkknn.andromuks.utils.MessageSoundPlayer
import net.vrkknn.andromuks.utils.POLL_START_TYPES
import net.vrkknn.andromuks.utils.PerMessageProfileChip
import net.vrkknn.andromuks.utils.PerMessageProfileDefaultChip
import net.vrkknn.andromuks.utils.PerMessageProfileEntry
import net.vrkknn.andromuks.utils.ReplyPreviewInput
import net.vrkknn.andromuks.utils.RoomJoinerScreen
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.RoomPermissions
import net.vrkknn.andromuks.utils.RoomStateStore
import net.vrkknn.andromuks.utils.StickerSelectionDialog
import net.vrkknn.andromuks.utils.TypingNotificationArea
import net.vrkknn.andromuks.utils.UrlPreviewCompositionBar
import net.vrkknn.andromuks.utils.UrlPreviewController
import net.vrkknn.andromuks.utils.VideoUploadUtils
import net.vrkknn.andromuks.utils.detectCommandQuery
import net.vrkknn.andromuks.utils.estimatedMenuBarHeight
import net.vrkknn.andromuks.utils.isBarePerMessageProfileCommand
import net.vrkknn.andromuks.utils.isBarePollCommand
import net.vrkknn.andromuks.utils.isReactionEvent
import net.vrkknn.andromuks.utils.rememberComposerCommandState
import net.vrkknn.andromuks.utils.rememberTimelineMenuInset
import net.vrkknn.andromuks.utils.resolveDefaultPerMessageProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Sealed class for timeline items (events and date dividers) */
sealed class BubbleTimelineItem {
    // PERFORMANCE: Stable key for LazyColumn items
    abstract val stableKey: String

    // absorbedReceiptEventIds: non-rendered event IDs whose read receipts flatten onto this event
    // (see ReceiptFunctions.gatherFlattenedReceipts and TimelineItem.Event in RoomTimelineScreen).
    data class Event(
        val event: TimelineEvent,
        val isConsecutive: Boolean = false,
        val hasPerMessageProfile: Boolean = false,
        val absorbedReceiptEventIds: List<String> = emptyList(),
    ) : BubbleTimelineItem() {
        override val stableKey: String
            get() = event.eventId
    }

    // anchorEventId disambiguates the LazyColumn key: the same calendar date can appear in two
    // non-adjacent segments after gap-safe merges, and a bare "date_$date" key would collide and
    // crash the list. See TimelineItem.DateDivider in RoomTimelineScreen.kt.
    data class DateDivider(val date: String, val anchorEventId: String) : BubbleTimelineItem() {
        override val stableKey: String get() = "date_${date}_$anchorEventId"
    }
}

/**
 * Snapshot of the values the auto-paginate effect reacts to. pendingScrollRestoration is
 * included so the refill chain re-checks the instant a round's scroll restoration completes:
 * restoration clears that flag in a separate effect, and a bare boolean read there would not
 * otherwise re-emit this snapshotFlow, stalling the chain. Mirror of
 * PaginateSnapshot in RoomTimelineScreen.kt — including the rule that every gate the effect
 * consults lives in here rather than being read directly inside collect{}: an unobserved read
 * stalls the chain for good on a timeline with zero renderable items, since nothing else in the
 * snapshot ever changes again to restart it.
 */
private data class BubblePaginateSnapshot(
    val total: Int,
    val lastVisible: Int,
    val isPaginating: Boolean,
    val pendingScrollRestoration: Boolean,
    val initialLoadSettled: Boolean,
    val isTimelineLoading: Boolean,
    val hasMore: Boolean,
)

/** Floating room list for room mentions */
@Composable
fun BubbleRoomSuggestionList(
    rooms: List<Pair<RoomItem, String>>,
    query: String,
    onRoomSelect: (String, String) -> Unit, // (roomId, canonicalAlias)
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier,
) {
    val filteredRooms = remember(rooms, query) {
        rooms.filter { (room, alias) ->
            query.isBlank() ||
                room.name.contains(query, ignoreCase = true) ||
                alias.contains(query, ignoreCase = true)
        }.sortedBy { it.first.name }
    }

    if (filteredRooms.isEmpty()) return

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .height(200.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            items(filteredRooms.size) { index ->
                val (room, alias) = filteredRooms[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRoomSelect(room.id, alias) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarImage(
                        mxcUrl = room.avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = room.name.take(1),
                        size = 32.dp,
                        userId = room.id,
                        displayName = room.name,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = alias,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Floating member list for mentions */
@Composable
fun BubbleMentionMemberList(
    members: Map<String, MemberProfile>,
    query: String,
    onMemberSelect: (String, String?) -> Unit,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier,
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
            profile.displayName?.takeIf { it.isNotBlank() } ?: userId
        }
    }

    if (filteredMembers.isEmpty()) return

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            ),
        shape = RoundedCornerShape(16.dp), // Rounder corners
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp, // Use tonalElevation for dark mode visibility
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 250.dp)
                .height(200.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            items(filteredMembers.size) { index ->
                val (userId, profile) = filteredMembers[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMemberSelect(userId, profile.displayName) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarImage(
                        mxcUrl = profile.avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = (profile.displayName ?: userId).take(1),
                        size = 32.dp,
                        userId = userId,
                        displayName = profile.displayName,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName?.takeIf { it.isNotBlank() } ?: userId.removePrefix("@"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!profile.displayName.isNullOrBlank()) {
                            Text(
                                text = userId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Spacer(
            modifier =
            Modifier.weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
        )

        Text(
            text = date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        androidx.compose.foundation.layout.Spacer(
            modifier =
            Modifier.weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
        )
    }
}

// NOTE: Keep this screen in sync with `RoomTimelineScreen`. Any structural or data-flow changes
// should be mirrored in both places. See `docs/BUBBLE_IMPLEMENTATION.md` for architectural details.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, FlowPreview::class)
@Composable
fun BubbleTimelineScreen(
    roomId: String,
    roomName: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    // Must be passed from ChatBubbleNavigation — scoped per Activity + room key; no default viewModel().
    appViewModel: AppViewModel,
    onCloseBubble: () -> Unit = {},
    onMinimizeBubble: () -> Unit = {},
    onOpenInApp: () -> Unit = {},
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
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Tracked bubble opened for room: $roomId (Composable level)",
                )
            }
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
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Tracked bubble closed for room: $roomId (Composable disposal)",
                    )
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Bubble already closed (likely by Activity) for room: $roomId",
                    )
                }
            }
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences = remember(context) {
        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    }
    val authToken = remember(sharedPreferences) {
        net.vrkknn.andromuks.utils.CredentialStore.getAuthToken(sharedPreferences)
    }
    val storedUserId = remember(sharedPreferences) {
        sharedPreferences.getString("current_user_id", "") ?: ""
    }
    val myUserId = appViewModel.currentUserId.ifBlank { storedUserId }
    val homeserverUrlFromPrefs = remember(sharedPreferences) { sharedPreferences.getString("homeserver_url", "") ?: "" }
    val homeserverUrl = appViewModel.homeserverUrl.ifEmpty { homeserverUrlFromPrefs }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: appViewModel instance: $appViewModel")
    // PERFORMANCE FIX: Use timelineEvents directly instead of pre-rendered flow.
    // Pre-rendering on every sync was causing heavy CPU load with 580+ rooms.
    // Timeline is now rendered lazily when room is opened via processCachedEvents().
    val timelineEvents = appViewModel.timelineEvents
    val editEventsByTargetId: Map<String, TimelineEvent> = remember(timelineEvents) {
        val map = mutableMapOf<String, TimelineEvent>()
        for (event in timelineEvents) {
            val targetId =
                event.content?.optJSONObject("m.relates_to")
                    ?.takeIf { it.optString("rel_type") == "m.replace" }
                    ?.optString("event_id")?.takeIf { it.isNotBlank() }
                    ?: event.decrypted?.optJSONObject("m.relates_to")
                        ?.takeIf { it.optString("rel_type") == "m.replace" }
                        ?.optString("event_id")?.takeIf { it.isNotBlank() }
            if (targetId != null) {
                val existing = map[targetId]
                if (existing == null || event.timestamp > existing.timestamp) {
                    map[targetId] = event
                }
            }
        }
        map
    }
    val isLoading = appViewModel.isTimelineLoading
    var readinessCheckComplete by remember { mutableStateOf(false) }

    // Get the room item to check if it's a DM and get proper display name
    val roomItem = appViewModel.getRoomById(roomId)
    val isDirectMessage = roomItem?.isDirectMessage ?: false

    // m.heroes fallback: any room (DM or group) with no display name and no canonical alias
    // derives its name and avatar from the first non-self, non-service member (Matrix m.heroes /
    // MSC4171).
    val needsHeroesFallback = (roomName.isBlank() || roomName == roomId) &&
        roomItem?.canonicalAlias.isNullOrBlank()

    // Name: use heroes for any nameless room; otherwise use the room name as-is.
    val displayRoomName =
        if (needsHeroesFallback) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val serviceMembers = appViewModel.functionalMembersCache[roomId] ?: emptySet()
            val hero = memberMap.entries
                .filter { (userId, _) -> userId != myUserId && userId !in serviceMembers }
                .firstOrNull()
            val heroProfile = hero?.value
            val heroUserId = hero?.key
            heroProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: heroUserId?.removePrefix("@")?.substringBefore(":")
                ?: roomName
        } else {
            roomName
        }

    // Avatar: heroes avatar for nameless rooms; otherwise the room's own avatar.
    // CRITICAL FIX: Use roomItem.avatarUrl as fallback (like RoomListScreen does)
    // This ensures avatars show even if member map isn't populated yet
    val displayAvatarUrl =
        if (needsHeroesFallback) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val serviceMembers = appViewModel.functionalMembersCache[roomId] ?: emptySet()
            val hero = memberMap.entries
                .filter { (userId, _) -> userId != myUserId && userId !in serviceMembers }
                .firstOrNull()
            hero?.value?.avatarUrl ?: roomItem?.avatarUrl ?: appViewModel.currentRoomState?.avatarUrl
        } else {
            roomItem?.avatarUrl ?: appViewModel.currentRoomState?.avatarUrl
        }

    // Permission to send messages based on power levels
    val canSendMessage = remember(appViewModel.currentRoomState, myUserId) {
        // currentRoomState is a single-room slot; fall back to the per-room store so the creator
        // set and the encryption flag are both read from the same room's state.
        val state = appViewModel.currentRoomState ?: RoomStateStore.getParsed(roomId)
        RoomPermissions.canSendMessage(
            powerLevels = state?.powerLevels,
            creators = RoomPermissions.creatorsOf(state),
            userId = myUserId,
            isEncrypted = state?.isEncrypted,
        )
    }

    // Messages typed while the WebSocket is down are buffered and sent on reconnect,
    // so only gate the input on permission, not on connectivity.
    val isInputEnabled = canSendMessage

    if (BuildConfig.DEBUG) {
        Log.d(
            "Andromuks",
            "BubbleTimelineScreen: Timeline events count: ${timelineEvents.size}, isLoading: $isLoading",
        )
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

    // Message menu state (for bottom menu bar)
    var messageMenuConfig by remember { mutableStateOf<MessageMenuConfig?>(null) }
    var retainedMessageMenuConfig by remember { mutableStateOf<MessageMenuConfig?>(null) }
    var showReactionsDialog by remember { mutableStateOf(false) }
    var reactionsEventId by remember { mutableStateOf<String?>(null) }
    var showBridgeDeliveryDialog by remember { mutableStateOf(false) }
    var bridgeDeliveryEventId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messageMenuConfig) {
        if (messageMenuConfig != null) {
            retainedMessageMenuConfig = messageMenuConfig
        }
    }

    // Media picker state
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMediaIsVideo by remember { mutableStateOf(false) }
    var showMediaPreview by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }

    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showLocationPickerOverlay by remember { mutableStateOf(false) }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }

    // Room joiner state
    var showRoomJoiner by remember { mutableStateOf(false) }
    var roomLinkToJoin by remember { mutableStateOf<RoomLink?>(null) }

    // Mention state
    var showMentionList by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableIntStateOf(-1) }
    var isWaitingForFullMemberList by remember { mutableStateOf(false) }
    var lastMemberUpdateCounterBeforeMention by remember { mutableIntStateOf(appViewModel.memberUpdateCounter) }

    // Emoji shortcode ( :shortname: ) state
    var showEmojiSuggestionList by remember { mutableStateOf(false) }
    var emojiQuery by remember { mutableStateOf("") }
    var emojiStartIndex by remember { mutableIntStateOf(-1) }

    // Room mention ( #roomalias ) state
    var showRoomSuggestionList by remember { mutableStateOf(false) }
    var roomQuery by remember { mutableStateOf("") }
    var roomStartIndex by remember { mutableIntStateOf(-1) }

    // Command ( /command ) state
    var showCommandSuggestionList by remember { mutableStateOf(false) }
    var commandQuery by remember { mutableStateOf("") }
    var commandStartIndex by remember { mutableIntStateOf(-1) }

    // MSC4391 in-room bot commands: the room's advertised commands, the invocation being typed, and
    // the argument sheet. See docs/BOT_COMMANDS.md.
    val composerCommands = rememberComposerCommandState(appViewModel, roomId)

    // Per-message profile picker state
    var showPmpProfilePicker by remember { mutableStateOf(false) }

    // Set when the draft becomes a bare "/poll"; consumed by the effect below, which clears
    // the draft and opens the full-screen poll maker.
    var pendingPollMaker by remember { mutableStateOf(false) }
    // A picked profile stays armed until the next send and travels in base_content — gomuks no
    // longer understands /pmp (MSC4461 rev-2, gomuks 951bac5).
    var selectedPmpProfile by remember { mutableStateOf<PerMessageProfileEntry?>(null) }

    // Avatar command state (for commands that need image picker)
    var pendingAvatarCommand by remember {
        mutableStateOf<String?>(null)
    } // "myroomavatar", "globalavatar", or "roomavatar"

    // Scroll highlight state for jump-to-message interactions
    var highlightedEventId by remember(roomId) { mutableStateOf<String?>(null) }
    var highlightRequestId by remember(roomId) { mutableIntStateOf(0) }
    // Back-stack for reply jumps: each entry is (firstVisibleItemIndex, scrollOffset)
    val jumpBackStack = remember(roomId) { ArrayDeque<Pair<Int, Int>>() }
    // Scroll position to restore when returning from EventContextScreen
    var pendingEventContextScrollRestore by remember { mutableStateOf<Pair<Int, Int>?>(null) }
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
    val urlPreviewController = remember { UrlPreviewController() }
    var draft by remember { mutableStateOf("") }
    var lastTypingTime by remember { mutableLongStateOf(0L) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // A bare "/poll" opens the full-screen poll maker. The draft is cleared first so returning
    // from the maker doesn't leave the command text sitting in the composer.
    LaunchedEffect(pendingPollMaker) {
        if (pendingPollMaker) {
            pendingPollMaker = false
            draft = ""
            textFieldValue = TextFieldValue("")
            showCommandSuggestionList = false
            navController.navigate("poll_maker/$roomId")
        }
    }

    // Focus requester for text field (to focus when replying)
    val textFieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Track text field height to match button heights
    var textFieldHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val buttonHeight = remember(textFieldHeight) {
        if (textFieldHeight > 0) {
            with(density) { textFieldHeight.toDp() }
        } else {
            40.dp // Fallback height (will be updated when text field is measured)
        }
    }

    // Both menu bars are overlays that would otherwise cover the bottom of the timeline (including
    // the message just long-pressed). Reserve exactly their measured height at the bottom of the
    // timeline slot while one is open; reverseLayout keeps index 0 pinned to the new bottom edge,
    // so the last message slides up into view and stick-to-bottom is unaffected.
    var menuBarHeightPx by remember { mutableIntStateOf(0) }
    val fallbackMenuBarHeight = estimatedMenuBarHeight()
    val menuBarHeight = if (menuBarHeightPx > 0) with(density) { menuBarHeightPx.toDp() } else fallbackMenuBarHeight
    val isMenuOpen = showAttachmentMenu || messageMenuConfig != null
    val timelineMenuInset = rememberTimelineMenuInset(isMenuOpen, menuBarHeight)

    // PERFORMANCE FIX: Use derivedStateOf to only recompose when keyboard state (open/closed) changes
    // This reduces recomposition from ~60fps to 2 (open + close) by only updating when boolean changes
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val isKeyboardOpen by remember {
        derivedStateOf {
            imeBottom > 50.dp
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
            val body = appViewModel.getBodyTextForEdit(editingEvent!!)
            draft = body

            // Hide mention list when editing
            showMentionList = false
        }
    }

    // Hide mention list when replying starts and focus text field with keyboard
    LaunchedEffect(replyingToEvent) {
        if (replyingToEvent != null) {
            showMentionList = false
            isWaitingForFullMemberList = false
            // Focus text field and show keyboard
            kotlinx.coroutines.delay(100) // Small delay to ensure UI is ready
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Show mention list when full member list is loaded
    LaunchedEffect(appViewModel.memberUpdateCounter, isWaitingForFullMemberList) {
        if (isWaitingForFullMemberList && appViewModel.memberUpdateCounter > lastMemberUpdateCounterBeforeMention) {
            // Full member list has been loaded, now show the mention list
            val memberMap = appViewModel.getMemberMap(roomId)
            if (memberMap.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Full member list loaded (${memberMap.size} members), showing mention list",
                    )
                }
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

    // Avatar image picker launcher (for avatar commands). Android Photo Picker — no permission needed.
    val avatarImagePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            uri?.let {
                val mimeType = context.contentResolver.getType(it)
                if (mimeType?.startsWith("image/") == true) {
                    // Handle avatar upload
                    val command = pendingAvatarCommand
                    pendingAvatarCommand = null

                    if (command != null) {
                        coroutineScope.launch {
                            try {
                                // Upload the image
                                val uploadResult = MediaUploadUtils.uploadMedia(
                                    context = context,
                                    uri = it,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    isEncrypted = false,
                                    compressOriginal = false,
                                )

                                if (uploadResult != null) {
                                    // Set the avatar based on command type
                                    when (command) {
                                        "myroomavatar" -> {
                                            appViewModel.setRoomMemberAvatar(roomId, uploadResult.mxcUrl)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Room avatar updated",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }

                                        "globalavatar" -> {
                                            appViewModel.setGlobalAvatar(uploadResult.mxcUrl)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Global avatar updated",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }

                                        "roomavatar" -> {
                                            appViewModel.setRoomAvatar(roomId, uploadResult.mxcUrl)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Room avatar updated",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Failed to upload avatar",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Andromuks", "BubbleTimelineScreen: Avatar upload error", e)
                                android.widget.Toast.makeText(
                                    context,
                                    "Error uploading avatar: ${e.message}",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Please select an image file",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    pendingAvatarCommand = null
                }
            }
        }

    // Media picker launcher - images and videos via the Android Photo Picker (no permission needed).
    val mediaPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            uri?.let {
                // Check if this is an image or video file
                val mimeType = context.contentResolver.getType(it)
                val isImageOrVideo = mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true

                if (isImageOrVideo) {
                    selectedMediaUri = it
                    // Detect if this is a video or image
                    selectedMediaIsVideo = mimeType.startsWith("video/") == true
                    showMediaPreview = true
                } else {
                    // Show error message for non-image/video files
                    android.widget.Toast.makeText(
                        context,
                        "Please select an image or video file",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    // Audio picker launcher
    val audioPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedAudioUri = it
                showMediaPreview = true
            }
        }

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedFileUri = it
                // Detect if this is a video file
                val mimeType = context.contentResolver.getType(it)
                selectedMediaIsVideo = mimeType?.startsWith("video/") == true
                showMediaPreview = true
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

    // Get rooms with canonical aliases for room mentions
    val roomsWithAliases = remember(appViewModel.allRooms) {
        appViewModel.getRoomsWithCanonicalAliases()
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
                if (cursorPosition == 1 ||
                    (cursorPosition > 1 && (text[cursorPosition - 2] == ' ' || text[cursorPosition - 2] == '\n'))
                ) {
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

    // Room mention detection function (for '#' based autocomplete)
    fun detectRoomMention(text: String, cursorPosition: Int): Pair<String, Int>? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null

        // Look for '#' at or before cursor position
        var hashIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            if (i < text.length && text[i] == '#') {
                hashIndex = i
                break
            }
            // Stop if we hit a space or newline before finding #
            if (i < text.length && (text[i] == ' ' || text[i] == '\n')) {
                break
            }
        }

        // Also check if cursor is right after # at the beginning or after space
        if (hashIndex == -1 && cursorPosition > 0 && cursorPosition <= text.length) {
            if (text[cursorPosition - 1] == '#') {
                // Check if # is at beginning or preceded by space/newline
                if (cursorPosition == 1 ||
                    (cursorPosition > 1 && (text[cursorPosition - 2] == ' ' || text[cursorPosition - 2] == '\n'))
                ) {
                    hashIndex = cursorPosition - 1
                }
            }
        }

        if (hashIndex == -1) return null

        // Extract the query after #
        val queryStart = hashIndex + 1
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

        // Allow showing room list even if we just typed # (empty query)
        if (queryStart <= cursorPosition) {
            val query = if (queryStart < min(queryEnd, text.length)) {
                text.substring(queryStart, min(queryEnd, text.length))
            } else {
                "" // Empty query when just # is typed
            }
            return Pair(query, hashIndex)
        }

        return null
    }

    // Handle backspace deletion of custom emoji markdown
    fun handleCustomEmojiDeletion(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue {
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
                    selection = TextRange(finalCursor),
                )
            }
        }

        return newValue
    }

    // Replace completed :shortcode: with its emoji/custom emoji representation
    fun applyCompletedEmojiShortcode(value: TextFieldValue): TextFieldValue {
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
            selection = TextRange(newCursorPos),
        )
    }

    fun handleMentionSelection(userId: String, displayName: String?, originalText: String, startIndex: Int, endIndex: Int): String {
        // Escape square brackets in display name to prevent regex issues
        val escapedDisplayName = (
            displayName?.takeIf { it.isNotBlank() } ?: userId.removePrefix(
                "@",
            ).substringBefore(":")
            )
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
            "m.room.tombstone",
            "m.reaction",
            "m.sticker",
            // Poll (MSC3381) starts render as bubbles. Responses and ends are deliberately
            // absent — they only mutate the poll bubble's counts and must never be rows.
            *POLL_START_TYPES.toTypedArray(),
            // m.room.redaction is intentionally excluded - redaction events should not appear in
            // timeline
        )

    // PERFORMANCE: Use background processing for heavy filtering and sorting operations
    var sortedEvents by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    val showHiddenEvents = appViewModel.resolveShowHiddenEvents(roomId)
    val showMembershipEvents = appViewModel.resolveShowMembershipEvents(roomId)

    // Process timeline events in background when dependencies change.
    //
    // This deliberately calls RoomTimelineScreen's processTimelineEvents rather than carrying a
    // bubble-local copy. The copy had drifted into inverted edit semantics (it dropped the original
    // and kept the m.replace event, which TimelineEventItem refuses to render) and never learned
    // about show_membership_events. Since the receipt-flattening anchor set is derived from
    // sortedEvents, any filter divergence also silently misplaces read-receipt avatars — see
    // docs/RECEIPTS.md. One filter, one set of rules, for every timeline surface.
    LaunchedEffect(timelineEvents, showHiddenEvents, showMembershipEvents) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Processing timelineEvents update - size=${timelineEvents.size}, roomId=$roomId",
            )
        }
        sortedEvents = processTimelineEvents(
            timelineEvents = timelineEvents,
            allowedEventTypes = allowedEventTypes,
            showHiddenEvents = showHiddenEvents,
            showMembershipEvents = showMembershipEvents,
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
    // Use produceState to offload this heavy computation (iterating thousands of events) to a background thread.
    // reactionUpdateCounter keys this because the receipt-flattening walk below splices in cached
    // reaction events, and a new reaction changes neither sortedEvents nor anything else here.
    val timelineItems by produceState<List<BubbleTimelineItem>>(
        initialValue = emptyList(),
        sortedEvents,
        appViewModel.reactionUpdateCounter,
    ) {
        value = withContext(Dispatchers.Default) {
            val items = mutableListOf<BubbleTimelineItem>()
            var lastDate: String? = null
            var previousEvent: TimelineEvent? = null

            // Receipt flattening — mirrors RoomTimelineScreen. Non-rendered events (reactions,
            // redactions, edits, bridge status, hidden membership) collapse their read receipts
            // onto the nearest rendered event so the avatar never lands on an unrenderable row.
            val timelineOrder = compareBy<TimelineEvent>(
                { it.eventId.startsWith("~") },
                { it.timelineRowid },
                { it.timestamp },
                { it.eventId },
            )
            // Reactions are spliced back in: they are never in timelineEvents (see
            // docs/REACTIONS.md), so walking it alone never visited a reaction's event ID and the
            // reactor's receipt was dropped. Only reactions with a resolved timeline position can be
            // placed — see the fuller note on the same walk in RoomTimelineScreen.
            val renderedIds = HashSet<String>(sortedEvents.size)
            for (e in sortedEvents) if (!isReactionEvent(e)) renderedIds.add(e.eventId)
            val absorbedByAnchor = HashMap<String, MutableList<String>>()
            run {
                val positionedReactions = RoomTimelineCache.getCachedReactionEvents(roomId)
                    .filter { it.timelineRowid > 0L }
                val walkOrder = if (positionedReactions.isEmpty()) {
                    timelineEvents.sortedWith(timelineOrder)
                } else {
                    (timelineEvents + positionedReactions).sortedWith(timelineOrder)
                }
                var anchor: String? = null
                for (e in walkOrder) {
                    if (e.eventId in renderedIds) {
                        anchor = e.eventId
                    } else {
                        anchor?.let { absorbedByAnchor.getOrPut(it) { mutableListOf() }.add(e.eventId) }
                    }
                }
            }

            val formatter = SimpleDateFormat("dd / MM / yyyy", Locale.getDefault())
            for (event in sortedEvents) {
                if (isReactionEvent(event)) {
                    // Reactions mutate their target event and should not render as standalone timeline items
                    continue
                }

                // Format date inline to avoid @Composable context issue
                val eventDate = formatter.format(Date(event.timestamp))

                // Add date divider if this is a new date
                if (lastDate == null || eventDate != lastDate) {
                    items.add(BubbleTimelineItem.DateDivider(eventDate, event.eventId))
                    lastDate = eventDate
                    // Date divider breaks consecutive grouping
                    previousEvent = null
                }

                // Check if this event has per-message profile (from bridges like Beeper)
                val hasPerMessageProfile =
                    event.content?.has("com.beeper.per_message_profile") == true ||
                        event.decrypted?.has("com.beeper.per_message_profile") == true

                // Check if this is a consecutive message from the same sender
                val timeDifference = if (previousEvent != null) {
                    kotlin.math.abs(
                        event.timestamp - previousEvent.timestamp,
                    )
                } else {
                    0L
                }
                val isConsecutive = !hasPerMessageProfile &&
                    previousEvent?.sender == event.sender &&
                    timeDifference <= 5 * 60 * 1000

                // Add the event with pre-computed flags
                items.add(
                    BubbleTimelineItem.Event(
                        event = event,
                        isConsecutive = isConsecutive,
                        hasPerMessageProfile = hasPerMessageProfile,
                        absorbedReceiptEventIds = absorbedByAnchor[event.eventId] ?: emptyList(),
                    ),
                )

                previousEvent = event
            }
            items
        }
    }
    var lastInitialScrollSize by remember(roomId) { mutableIntStateOf(0) }

    // Get member map that observes memberUpdateCounter and includes global cache fallback for TimelineEventItem profile updates
    val baseMemberMap = remember(roomId, appViewModel.memberUpdateCounter, sortedEvents) {
        appViewModel.getMemberMapWithFallback(roomId, sortedEvents)
    }

    // CRITICAL FIX: Ensure current user profile is included in memberMap
    // The current user's profile might not be in the room's member map if there's no m.room.member event for them
    // This fixes the issue where own messages show username instead of display name/avatar
    val memberMap = remember(baseMemberMap, appViewModel.currentUserProfile, myUserId) {
        val enhancedMap = baseMemberMap.toMutableMap()

        // If current user is not in member map but we have currentUserProfile, add it
        if (myUserId.isNotBlank() && !enhancedMap.containsKey(myUserId)) {
            val currentProfile = appViewModel.currentUserProfile
            if (currentProfile != null) {
                enhancedMap[myUserId] = MemberProfile(
                    displayName = currentProfile.displayName,
                    avatarUrl = currentProfile.avatarUrl,
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Added current user profile to memberMap - userId: $myUserId, displayName: ${currentProfile.displayName}",
                    )
                }
            }
        }

        enhancedMap
    }

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()

    // True only during programmatic animated scrolls (FAB, keyboard, etc.).
    var isAnimatedScrolling by remember { mutableStateOf(false) }

    suspend fun animatedScrollTo(index: Int, offset: Int = 0) {
        isAnimatedScrolling = true
        try {
            listState.animateScrollToItem(index, offset)
        } finally {
            isAnimatedScrolling = false
        }
    }

    // Restore scroll position when returning from EventContextScreen
    LaunchedEffect(navController) {
        snapshotFlow { navController.currentBackStackEntry?.destination?.route }
            .distinctUntilChanged()
            .collect { route ->
                val restore = pendingEventContextScrollRestore
                if (restore != null && route?.startsWith("chat_bubble") == true) {
                    pendingEventContextScrollRestore = null
                    listState.scrollToItem(restore.first, restore.second)
                }
            }
    }

    // Prefetch guardband assets around the viewport (+50 above, +50 below).
    // Coil handles eviction naturally; we remove these keyed entries when bubble closes.
    val timelinePrefetchLoader = remember(context) { ImageLoaderSingleton.get(context) }
    val prefetchedTimelineMemoryKeys = remember(roomId) { mutableSetOf<String>() }
    val prefetchGuardband = 50

    fun enqueueTimelinePrefetch(mxcUrl: String?, keyPrefix: String, requestSize: Int) {
        if (mxcUrl.isNullOrBlank()) return
        val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl) ?: return
        val memoryKey = "bubble_timeline_prefetch:$roomId:$keyPrefix:${mxcUrl.hashCode()}"
        if (!prefetchedTimelineMemoryKeys.add(memoryKey)) return

        val request = ImageRequest.Builder(context)
            .data(httpUrl)
            .size(requestSize)
            .memoryCacheKey(memoryKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
        timelinePrefetchLoader.enqueue(request)
    }

    LaunchedEffect(listState, timelineItems, memberMap, homeserverUrl, authToken, roomId) {
        snapshotFlow {
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (visibleIndices.isEmpty()) {
                null
            } else {
                (visibleIndices.minOrNull() ?: 0) to (visibleIndices.maxOrNull() ?: 0)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (visibleStart, visibleEnd) ->
                // Snapshot the list ONCE. `timelineItems` is a produceState delegate, so every
                // mention of it is a fresh State read. This scan happens to run inline on the
                // collector's dispatcher, which makes bounds-then-index atomic today — but that
                // is an accident of scheduling, not a guarantee. Hoisting keeps it correct if the
                // scan is ever moved off Main (that hop is exactly what broke the room timeline).
                val items = timelineItems
                if (items.isEmpty()) return@collect
                val start = (visibleStart - prefetchGuardband).coerceAtLeast(0)
                val end = (visibleEnd + prefetchGuardband).coerceAtMost(items.lastIndex)

                for (index in start..end) {
                    val item = items[index] as? BubbleTimelineItem.Event ?: continue
                    val event = item.event

                    // Prefetch sender avatar
                    val avatarMxc = memberMap[event.sender]?.avatarUrl
                    enqueueTimelinePrefetch(
                        mxcUrl = avatarMxc,
                        keyPrefix = "avatar:${event.sender}",
                        requestSize = 256,
                    )

                    // Prefetch media thumbnail (or media URL fallback) for image/video/sticker events
                    val content = when {
                        event.type == "m.room.message" -> event.content
                        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted
                        event.type == "m.sticker" -> event.content ?: event.decrypted
                        else -> null
                    }
                    val msgType = when {
                        event.type == "m.sticker" -> "m.sticker"
                        else -> content?.optString("msgtype", "")
                    }
                    if (msgType == "m.image" || msgType == "m.video" || msgType == "m.sticker") {
                        val info = content?.optJSONObject("info")
                        val thumbnailMxc =
                            info?.optJSONObject("thumbnail_file")
                                ?.optString("url")
                                ?.takeIf { it.isNotBlank() }
                                ?: info?.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
                        val mediaMxc = content?.optString("url", "")?.takeIf { it.isNotBlank() }
                        enqueueTimelinePrefetch(
                            mxcUrl = thumbnailMxc ?: mediaMxc,
                            keyPrefix = "media:${event.eventId}",
                            requestSize = 512,
                        )
                    }
                }
            }
    }

    DisposableEffect(roomId) {
        onDispose {
            val cache = timelinePrefetchLoader.memoryCache
            prefetchedTimelineMemoryKeys.forEach { key ->
                cache?.remove(MemoryCache.Key(key))
            }
            prefetchedTimelineMemoryKeys.clear()
        }
    }

    // Track scroll position using event ID anchor (more robust than index)
    // Track scroll position for pagination restoration
    // With reverseLayout, we capture the highest visible index (oldest message at top)
    // After pagination adds older events, we scroll so that index is at the bottom of view
    var highestVisibleIndexBeforePagination by remember { mutableStateOf<Int?>(null) }
    var anchorScrollOffsetForRestore by remember { mutableIntStateOf(0) }
    var pendingScrollRestoration by remember { mutableStateOf(false) }
    var expectedTimelineSizeBeforePagination by remember { mutableStateOf<Int?>(null) }

    // Buffer-refill chaining state for auto-paginate. Keyed by roomId so a room switch resets
    // the burst. isRefillingBuffer latches a multi-round fetch (see the auto-paginate effect);
    // prevItemsAbove gives the falling-edge detection that prevents a capped burst from
    // immediately re-arming itself while still below the trigger threshold.
    var isRefillingBuffer by remember(roomId) { mutableStateOf(false) }
    var refillRoundCount by remember(roomId) { mutableIntStateOf(0) }
    var prevItemsAbove by remember(roomId) { mutableIntStateOf(Int.MAX_VALUE) }

    // Barren-round tracking for the escalating page size, mirroring RoomTimelineScreen: a round
    // that returns events but no new renderable rows (hidden membership events) widens the next
    // round's limit. refillStalled records a burst that stopped at the safety cap with history
    // still available, and suppresses self-re-arming until the buffer genuinely recovers.
    // True when the current burst was armed from an empty timeline, i.e. it is doing the initial
    // fill rather than topping up a buffer the user is scrolling through. Such a burst stops at
    // initialFillTarget instead of REFILL_TARGET.
    var armedFromEmpty by remember(roomId) { mutableStateOf(false) }
    var barrenRoundStreak by remember(roomId) { mutableIntStateOf(0) }
    var totalBeforeRound by remember(roomId) { mutableStateOf<Int?>(null) }
    var refillStalled by remember(roomId) { mutableStateOf(false) }

    // Pull-to-refresh state
    var isRefreshingPull by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingPull,
        onRefresh = {
            // Capture the highest visible index before pagination
            // With reverseLayout, highest index = oldest message at top of view
            // After pagination adds older events, we'll scroll so this index is at bottom of view
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            val highestVisibleIndex = visibleIndices.maxOrNull()

            if (highestVisibleIndex != null && timelineItems.isNotEmpty()) {
                highestVisibleIndexBeforePagination = highestVisibleIndex
                anchorScrollOffsetForRestore = listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                expectedTimelineSizeBeforePagination = timelineItems.size
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Pull-to-refresh triggered, capturing highest visible index: $highestVisibleIndex (out of ${timelineItems.size} items)",
                    )
                }
            } else {
                // Fallback: use first visible item index
                highestVisibleIndexBeforePagination = listState.firstVisibleItemIndex
                anchorScrollOffsetForRestore = listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                expectedTimelineSizeBeforePagination = timelineItems.size
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Pull-to-refresh triggered, no visible items, using first visible index: ${listState.firstVisibleItemIndex}",
                    )
                }
            }

            // Use the oldest event from cache, not the oldest rendered event
            // The cache may have events that aren't currently rendered, so we need to use
            // the absolute oldest event to avoid requesting duplicates
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Pull-to-refresh triggered, requesting pagination with oldest cached event",
                )
            }
            isRefreshingPull = true
            appViewModel.requestPaginationWithSmallestRowId(roomId, limit = 100)
        },
    )

    // Monitor pagination state to stop refresh indicator
    // Note: Refresh indicator is now cleared in scroll restoration LaunchedEffect
    // This is kept as a fallback in case scroll restoration doesn't trigger
    LaunchedEffect(appViewModel.isPaginating) {
        if (!appViewModel.isPaginating && isRefreshingPull && !pendingScrollRestoration) {
            isRefreshingPull = false
        }
    }

    // Safety fallback for a pull-to-refresh whose paginate never actually left the device — the
    // scroll-restoration effect below only fires on an isPaginating true→false edge, and
    // requestPaginationWithSmallestRowId early-returns without setting that flag when the socket is
    // down. See the matching effect in RoomTimelineScreen for the full rationale.
    LaunchedEffect(pendingScrollRestoration, isRefreshingPull) {
        if (!pendingScrollRestoration && !isRefreshingPull) return@LaunchedEffect
        kotlinx.coroutines.delay(2000)
        if (appViewModel.isPaginating || appViewModel.hasPendingTimelineRequest(roomId)) return@LaunchedEffect
        if (!pendingScrollRestoration && !isRefreshingPull) return@LaunchedEffect
        Log.w(
            "Andromuks",
            "BubbleTimelineScreen: pull-to-refresh never started a paginate - releasing scroll restoration for $roomId",
        )
        pendingScrollRestoration = false
        highestVisibleIndexBeforePagination = null
        expectedTimelineSizeBeforePagination = null
        isRefreshingPull = false
    }

    // Track if user is "attached" to the bottom (sticky scroll)
    var isAttachedToBottom by remember { mutableStateOf(true) }

    // Set by a finger on the list, cleared the next time we settle at the bottom. This is what
    // makes detaching a *user intent* rather than a scroll-position reading — see the attachment
    // snapshotFlow below for why the position alone is not trustworthy.
    var userDraggedSinceSettle by remember { mutableStateOf(false) }

    // Identity of the newest rendered item — index 0 in the reversed list, i.e. the visual bottom.
    // The re-anchor effect below keys on this rather than on timelineItems.size so it also fires
    // when a local echo is swapped for the real event: same size, different key, usually a
    // different height.
    val newestItemKey = timelineItems.lastOrNull()?.stableKey

    // Sending is an unconditional "take me to the bottom" intent — never a scroll-position question.
    // The scroll here only covers the case where you were scrolled up when you hit send; the message
    // itself does not exist yet, so landing on it is the job of the newestItemKey effect below.
    fun snapToBottomForOutgoing() {
        isAttachedToBottom = true
        userDraggedSinceSettle = false
        coroutineScope.launch { listState.scrollToItem(0) }
    }

    // Track previous app visibility state to detect background/foreground transitions
    var previousAppVisibleState by remember(roomId) { mutableStateOf(appViewModel.isAppVisible) }

    // CRITICAL FIX: Immediately set scroll position to bottom when timelineItems first becomes available
    // This LaunchedEffect runs as soon as items are computed, before the first render completes
    // Using a separate flag to ensure we only do this once per room
    var hasSetInitialScrollPosition by remember(roomId) { mutableStateOf(false) }
    LaunchedEffect(timelineItems.size, roomId) {
        if (timelineItems.isNotEmpty() && !hasSetInitialScrollPosition) {
            val lastIndex = timelineItems.lastIndex
            // With reverseLayout, index 0 is the bottom (newest message)
            listState.scrollToItem(0)
            isAttachedToBottom = true
            hasSetInitialScrollPosition = true
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Set initial scroll position to bottom (index=0, items=${timelineItems.size}) - reverseLayout anchors at bottom",
                )
            }
        }
    }

    // Re-anchor to the visual bottom whenever the newest item changes while attached.
    //
    // This must scroll *unconditionally* — reading listState.firstVisibleItemIndex here and skipping
    // when it is already 0 is what used to make sent messages land below the fold. LaunchedEffect
    // bodies are dispatched around the frame that applies the insertion, so whether this reads the
    // pre-insert anchor (still 0) or the post-insert one (remapped to 1, because item keys are
    // stable and LazyList preserves the anchored item across a prepend) is a frame-timing coin
    // flip. Losing that flip left the new message one row below the viewport with nothing to
    // correct it. scrollToItem(0) is correct at either point in the frame and is a no-op when
    // already there: it forgets the last-known anchor key, so the following measure pass takes
    // index 0 literally. See the twin effect in RoomTimelineScreen.
    //
    // CRITICAL: Skip during scroll restoration (pagination) to avoid jumping to bottom
    LaunchedEffect(newestItemKey, isAttachedToBottom) {
        if (pendingScrollRestoration) {
            return@LaunchedEffect // Don't scroll during pagination scroll restoration
        }
        if (timelineItems.isNotEmpty() && isAttachedToBottom && hasSetInitialScrollPosition) {
            listState.scrollToItem(0)
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Re-anchored to bottom for newest item $newestItemKey (items=${timelineItems.size})",
                )
            }
        }
    }

    // Track if this is the first load (to avoid animation on initial room open)
    var isInitialLoad by remember { mutableStateOf(true) }

    // Track if we're refreshing (to scroll to bottom after refresh)
    var isRefreshing by remember { mutableStateOf(false) }

    // Track loading more state
    var isLoadingMore by remember { mutableStateOf(false) }
    var previousItemCount by remember { mutableIntStateOf(timelineItems.size) }
    var hasLoadedInitialBatch by remember { mutableStateOf(false) }
    var hasInitialSnapCompleted by remember { mutableStateOf(false) }
    var lastKnownTimelineEventId by remember { mutableStateOf<String?>(null) }
    var hasCompletedInitialLayout by remember { mutableStateOf(false) }
    var pendingInitialScroll by remember { mutableStateOf(true) }

    // UNIFIED OPEN PATH (mirrors RoomTimelineScreen): the notification's target event is a passive
    // highlight, NOT a scroll target. The normal bottom-scroll effect owns landing at the bottom for
    // every open. This used to scroll, and did it wrongly: `targetIndex` indexes `timelineItems`
    // (oldest-first) but listState renders `reversedBubbleItems`, so without the `lastIndex - index`
    // conversion every jump landed at the mirror-image position — and `isAttachedToBottom` was
    // derived from the same unconverted index, so attachment was set backwards too.
    //
    // Keying on `timelineItems` (reference) re-checks on each rebuild, so a later paginate merge
    // that brings the event into the window still highlights it.
    LaunchedEffect(
        pendingNotificationJumpEventId,
        timelineItems,
        readinessCheckComplete,
        appViewModel.isContentVisible,
    ) {
        val targetEventId = pendingNotificationJumpEventId ?: return@LaunchedEffect
        // Defer the highlight (and its auto-clear timer) until the content is actually visible.
        // Opened from a notification under the biometric lock, the timeline composes beneath the
        // lock overlay; firing the pulse now would burn most of it before the user unlocks.
        // pendingNotificationJumpEventId is left set, so this re-runs once unlocked.
        if (!readinessCheckComplete || timelineItems.isEmpty() || !appViewModel.isContentVisible) {
            return@LaunchedEffect
        }
        val isLoaded = timelineItems.any { item ->
            (item as? BubbleTimelineItem.Event)?.event?.eventId == targetEventId
        }
        if (isLoaded) {
            highlightedEventId = targetEventId
            highlightRequestId++
            pendingNotificationJumpEventId = null
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Highlighting notification target event=$targetEventId in place (no scroll)",
                )
            }
        }
        // Not (yet) loaded: leave pendingNotificationJumpEventId set so a subsequent timeline
        // rebuild can still highlight it. remember(roomId) resets it on room change.
    }

    // A finger on the list is the only thing that expresses "I want to leave the bottom" — see the
    // long note on the same effect in RoomTimelineScreen for why position alone can't decide this.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                userDraggedSinceSettle = true
            }
        }
    }

    // Bottom attachment: single snapshotFlow over (index, offset) — same as RoomTimelineScreen.
    // Replaces fragile lastVisibleIndex heuristics + separate frame reads that disagreed during IME.
    // With reverseLayout=true, bottom == first item; offset < 100 avoids FAB flicker on tiny scrolls.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .debounce(50L)
            .collect { (index, offset) ->
                // Match previous behavior: mark layout complete once we have items (independent of keyboard)
                if (sortedEvents.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0 &&
                    !hasCompletedInitialLayout
                ) {
                    hasCompletedInitialLayout = true
                }
                if (!hasInitialSnapCompleted || !hasLoadedInitialBatch || pendingScrollRestoration) {
                    return@collect
                }
                val atBottom = index == 0 && offset < 100
                // Only a drag detaches; settling at the bottom always re-attaches, IME or not. The
                // previous IME-gated rule latched a transient prepend index shift into a permanent
                // detach for our own sends (keyboard up) but not for incoming ones (keyboard down).
                when {
                    atBottom -> {
                        userDraggedSinceSettle = false
                        if (!isAttachedToBottom) {
                            isAttachedToBottom = true
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    "Andromuks",
                                    "BubbleTimelineScreen: Settled at bottom — re-attached (index=$index, offset=$offset)",
                                )
                            }
                        }
                    }

                    userDraggedSinceSettle -> {
                        if (isAttachedToBottom) {
                            isAttachedToBottom = false
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    "Andromuks",
                                    "BubbleTimelineScreen: User dragged away from bottom — detached (index=$index, offset=$offset) so FAB can show",
                                )
                            }
                        }
                    }
                }
            }
    }

    // Auto-paginate with buffer-refill chaining: when fewer than REFILL_TRIGGER rendered
    // events remain above the viewport and more history is available, enter a "refill" burst
    // that keeps fetching older events — one round at a time, gated by isPaginating — until at
    // least REFILL_TARGET items sit above the viewport (or history is exhausted, or a safety
    // round cap is hit). It reuses the same anchor-capture and scroll-restoration path as
    // pull-to-refresh. Mirror of the auto-paginate effect in RoomTimelineScreen.kt.
    //
    // Why a target (hysteresis) instead of a single "<= 60" fetch: a duplicate-heavy paginate
    // response (most events already cached) adds only a handful of *renderable* items, which
    // would nudge itemsAbove just past 60 and end the chain after one low-yield round. The user
    // can then out-scroll the pager and hit the literal top, where each subsequent round still
    // yields little. Refilling to a deeper target rebuilds a real buffer underneath the user.
    //
    // isPaginating and pendingScrollRestoration are in the snapshot so the chain re-checks both
    // when a paginate batch finishes (isPaginating: true→false) and when its scroll restoration
    // settles — even if totalItemsCount didn't change (e.g. a whole batch filtered out).
    // distinctUntilChanged would otherwise suppress the re-emission and stall the chain.
    // The total > 0 guard is dropped for the same reason: total == 0 with hasMoreMessages == true
    // is exactly the case we need to paginate through.
    val REFILL_TRIGGER = 60 // start refilling when this few items remain above the viewport
    val REFILL_TARGET = 180 // keep fetching until at least this many sit above the viewport
    val MAX_REFILL_ROUNDS = 20 // safety valve against a backend that keeps advancing with no real yield
    // A burst armed from an empty timeline is filling the screen, not building a scroll buffer.
    // Chasing REFILL_TARGET there costs ~10 extra round-trips on a sparse room and holds the
    // progress bar up long after the user can read the messages. Stop at roughly one screen of
    // content; if the user then scrolls up, the falling edge arms a normal REFILL_TARGET burst.
    val initialFillTarget = 15
    // A round that yields fewer than this many renderable items counts as unproductive and widens
    // the next round. Zero-yield alone is too strict: a barren stretch that dribbles out two or
    // three messages per 100 events would never escalate.
    val lowYieldRound = 5
    LaunchedEffect(listState, roomId) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            BubblePaginateSnapshot(
                total = info.totalItemsCount,
                lastVisible = lastVisible,
                isPaginating = appViewModel.isPaginating,
                pendingScrollRestoration = pendingScrollRestoration,
                initialLoadSettled = hasLoadedInitialBatch && hasInitialSnapCompleted,
                isTimelineLoading = appViewModel.isTimelineLoading,
                hasMore = appViewModel.hasMoreMessages,
            )
        }
            .distinctUntilChanged()
            .debounce(50L)
            .collect { snap ->
                val total = snap.total
                val itemsAbove = total - 1 - snap.lastVisible

                // A round finished: did it yield anything renderable? If not, widen the next one.
                if (!snap.isPaginating) {
                    totalBeforeRound?.let { before ->
                        if (total - before >= lowYieldRound) barrenRoundStreak = 0 else barrenRoundStreak++
                        totalBeforeRound = null
                    }
                }

                // Falling-edge entry: arm a refill burst when the buffer crosses down through
                // REFILL_TRIGGER. Edge-detection (vs. a level check) stops a burst that hit
                // MAX_REFILL_ROUNDS from instantly re-arming while still below the trigger — the
                // buffer must first recover above the trigger and then fall again. total == 0 and
                // "parked at the top of what we have" are armed explicitly: neither can ever
                // produce a falling edge, and the first is exactly the heavily-filtered room that
                // needs digging. Both are suppressed while refillStalled so a capped burst can't
                // re-arm off its own isPaginating edge and loop forever.
                if (itemsAbove > REFILL_TRIGGER) refillStalled = false
                val atTopOfLoaded = total > 0 && snap.lastVisible >= total - 1
                val armedByEdge = prevItemsAbove > REFILL_TRIGGER && itemsAbove <= REFILL_TRIGGER
                if (!isRefillingBuffer && !refillStalled && snap.hasMore &&
                    (armedByEdge || total == 0 || atTopOfLoaded)
                ) {
                    isRefillingBuffer = true
                    refillRoundCount = 0
                    armedFromEmpty = total == 0
                }
                // Exit conditions: target reached, history exhausted, or safety cap hit.
                val activeTarget = if (armedFromEmpty) initialFillTarget else REFILL_TARGET
                if (itemsAbove >= activeTarget || !snap.hasMore ||
                    refillRoundCount >= MAX_REFILL_ROUNDS
                ) {
                    if (isRefillingBuffer) {
                        refillStalled = snap.hasMore && itemsAbove < activeTarget
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "Andromuks",
                                "BubbleTimelineScreen: Refill burst ended ($itemsAbove above viewport, rounds=$refillRoundCount, target=$activeTarget, hasMore=${snap.hasMore}, stalled=$refillStalled)",
                            )
                        }
                    }
                    isRefillingBuffer = false
                }
                prevItemsAbove = itemsAbove

                if (isRefillingBuffer &&
                    snap.initialLoadSettled &&
                    !snap.pendingScrollRestoration &&
                    !snap.isPaginating &&
                    !snap.isTimelineLoading &&
                    snap.hasMore
                ) {
                    val roundLimit = when {
                        barrenRoundStreak >= 2 -> 500
                        barrenRoundStreak >= 1 -> 250
                        else -> 100
                    }
                    totalBeforeRound = total
                    val atBottom = listState.firstVisibleItemIndex == 0
                    if (!atBottom && total > 0) {
                        val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
                        val highestVisible = visibleIndices.maxOrNull() ?: snap.lastVisible
                        highestVisibleIndexBeforePagination = highestVisible
                        anchorScrollOffsetForRestore = listState.firstVisibleItemScrollOffset
                        pendingScrollRestoration = true
                        expectedTimelineSizeBeforePagination = timelineItems.size
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "Andromuks",
                                "BubbleTimelineScreen: Auto-paginate round ${refillRoundCount + 1} ($itemsAbove above viewport, target=$activeTarget, highestVisible=$highestVisible)",
                            )
                        }
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "Andromuks",
                                "BubbleTimelineScreen: Auto-paginate round ${refillRoundCount + 1} at bottom or empty ($itemsAbove above viewport, total=$total, limit=$roundLimit, barren=$barrenRoundStreak) — skipping scroll restoration",
                            )
                        }
                    }
                    refillRoundCount++
                    appViewModel.requestPaginationWithSmallestRowId(roomId, limit = roundLimit)
                }
            }
    }

    // CRITICAL FIX: Track app visibility changes to handle background/foreground transitions
    // When app foregrounds, if we were attached to bottom, verify we're still at bottom and scroll if needed
    LaunchedEffect(appViewModel.isAppVisible, roomId) {
        val appJustBecameVisible = !previousAppVisibleState && appViewModel.isAppVisible

        // Note: the entrance-animation cutover is advanced in AppViewModel.setBubbleVisible()
        // (the authoritative bubble resume funnel), before the timeline refresh rebuilds this
        // timeline — doing it here would race that rebuild and let backgrounded messages animate.

        if (appJustBecameVisible && isAttachedToBottom) {
            // App was foregrounded AND we're marked as attached - animate scroll to bottom smoothly
            // With reverseLayout, index 0 is bottom
            if (timelineItems.isNotEmpty()) {
                coroutineScope.launch {
                    animatedScrollTo(0)
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Andromuks",
                            "BubbleTimelineScreen: App resumed, animating scroll to bottom (index=0, items=${timelineItems.size}) - reverseLayout",
                        )
                    }
                }
            }

            // Also set up a delayed check in case more items arrive during batch processing
            kotlinx.coroutines.delay(150)

            // Re-check after a brief delay to catch any items added during batch processing
            if (timelineItems.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
                val currentFirstVisible = listState.firstVisibleItemIndex
                val currentOffset = listState.firstVisibleItemScrollOffset
                val actuallyAtBottom = currentFirstVisible == 0 && currentOffset < 100

                if (!actuallyAtBottom) {
                    // Still not at bottom after batch processing - animate scroll again
                    coroutineScope.launch {
                        animatedScrollTo(0)
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "Andromuks",
                                "BubbleTimelineScreen: App resumed, adjusted animated scroll after batch (was at index=$currentFirstVisible, scrolled to 0)",
                            )
                        }
                    }
                }
            }
        }

        previousAppVisibleState = appViewModel.isAppVisible
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
            val highestIndex = highestVisibleIndexBeforePagination
            val oldSize = expectedTimelineSizeBeforePagination

            // CRITICAL: Check timelineEvents.size (from ViewModel) not timelineItems.size (from UI)
            // timelineItems.size may not be updated yet when this LaunchedEffect fires
            val newSize = appViewModel.timelineEvents.size

            if (highestIndex != null && oldSize != null && newSize > oldSize) {
                // With reverseLayout, new events are added at higher indices (older messages at top)
                // We want to scroll so that the old highest index is at the bottom of the view
                // Since new events were added, the old highest index is still valid
                // We scroll to it so it appears at the bottom of the viewport

                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Pagination completed, restoring scroll. " +
                            "Old highest visible index: $highestIndex, old size: $oldSize, new size: $newSize",
                    )
                }

                // Wait for timelineItems to be rebuilt from timelineEvents
                // We need to wait for the UI to process the new events
                var waitCount = 0
                while (timelineItems.size <= oldSize && waitCount < 20) {
                    kotlinx.coroutines.delay(50)
                    waitCount++
                }

                // Wait for layout to settle after new items are added
                kotlinx.coroutines.delay(100)

                // Scroll so that the old highest index is at the TOP of the viewport
                // With reverseLayout, higher indices are at the top, so scrolling to this index
                // with offset 0 will place it at the top (where it was when pull-to-refresh was triggered)
                val targetIndex = highestIndex.coerceIn(0, timelineItems.lastIndex)

                // Use animateScrollToItem with smooth animation for smooth UX
                // scrollOffset = 0 ensures the item is at the top of the viewport
                // The animation duration is controlled by Compose's default animation
                animatedScrollTo(targetIndex, 0)

                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: ✅ Scroll position restored to index $targetIndex (old highest visible index) at top of viewport",
                    )
                }

                // Wait for animation to complete (default is ~300-500ms) plus a bit more for layout to settle
                kotlinx.coroutines.delay(600)
            } else {
                // Fallback: maintain current scroll position
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Pagination completed, but no valid index captured or no new events. " +
                            "highestIndex=$highestIndex, oldSize=$oldSize, newSize=$newSize",
                    )
                }
                kotlinx.coroutines.delay(100)
                val currentFirstIndex = listState.firstVisibleItemIndex
                if (currentFirstIndex >= 0 && currentFirstIndex < timelineItems.size) {
                    listState.scrollToItem(currentFirstIndex, anchorScrollOffsetForRestore)
                }
                kotlinx.coroutines.delay(300)
            }

            // Clear restoration state AFTER scroll has completed and timeline has settled
            pendingScrollRestoration = false
            highestVisibleIndexBeforePagination = null
            anchorScrollOffsetForRestore = 0
            expectedTimelineSizeBeforePagination = null
            isLoadingMore = false
            isRefreshingPull = false
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            kotlinx.coroutines.delay(2000)
            if (isRefreshing && !appViewModel.hasPendingTimelineRequest(roomId)) {
                Log.w(
                    "Andromuks",
                    "BubbleTimelineScreen: Manual refresh timeout - marking refresh as complete (no pending requests)",
                )
                isRefreshing = false
            }
        }
    }

    // Auto-scroll to bottom only when attached (initial load or new messages while at bottom).
    // This owns landing at the bottom for EVERY open, including notification taps — the highlight
    // effect above no longer scrolls, so there is nothing here to suppress or race against.
    LaunchedEffect(
        timelineItems,
        isLoading,
        appViewModel.isPaginating,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "BubbleTimelineScreen: LaunchedEffect - timelineItems.size: ${timelineItems.size}, isLoading: $isLoading, isPaginating: ${appViewModel.isPaginating}, hasInitialSnapCompleted: $hasInitialSnapCompleted",
            )
        }

        if (isLoading || timelineItems.isEmpty()) {
            return@LaunchedEffect
        }

        val lastEventId = (timelineItems.lastOrNull() as? BubbleTimelineItem.Event)?.event?.eventId

        if (!hasInitialSnapCompleted) {
            coroutineScope.launch {
                // Wait briefly if still loading (e.g. paginating) so the first visible state is stable.
                var waitCount = 0
                val maxWaitAttempts = 4 // Max 200ms (4 * 50ms)
                while (waitCount < maxWaitAttempts && (isLoading || appViewModel.isPaginating)) {
                    kotlinx.coroutines.delay(50)
                    waitCount++
                }

                // Final check before scrolling
                if (timelineItems.isEmpty()) {
                    hasInitialSnapCompleted = true
                    return@launch
                }

                // With reverseLayout, index 0 is bottom (newest message)
                listState.scrollToItem(0)
                isAttachedToBottom = true
                hasInitialSnapCompleted = true
                hasLoadedInitialBatch = true
                previousItemCount = timelineItems.size
                lastKnownTimelineEventId = lastEventId

                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: ✅ Scrolled to bottom on initial load (${timelineItems.size} items) - reverseLayout",
                    )
                }
            }
            return@LaunchedEffect
        }

        val hasNewItems = previousItemCount < timelineItems.size

        // Skip handling new items if we're waiting for scroll restoration after pagination
        if (pendingScrollRestoration) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Skipping new items handling - pending scroll restoration",
                )
            }
            return@LaunchedEffect
        }

        // Settle pass: the effect above already re-anchored in the insertion frame; this catches
        // height that only materialises afterwards (media, previews, decrypted bodies resolving).
        // Like that effect it scrolls unconditionally rather than re-reading firstVisibleItemIndex
        // — the read is the unreliable part, and skipping on a stale 0 is what left sent messages
        // below the fold. isAttachedToBottom is an effect key, so a finger arriving during the
        // delay cancels this body before it can fight the user's drag.
        if (hasNewItems && isAttachedToBottom && lastEventId != null && lastEventId != lastKnownTimelineEventId) {
            // Wait a moment for layout to settle after new items are added
            kotlinx.coroutines.delay(100)

            if (listState.layoutInfo.totalItemsCount > 0 && timelineItems.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Settle re-anchor to bottom (newest=$lastEventId).",
                    )
                }
                coroutineScope.launch {
                    // New-message scroll is a short hop — don't suppress images for it.
                    listState.animateScrollToItem(0)
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

    // CRITICAL FIX: Scroll to bottom when keyboard opens (so latest message is visible above keyboard)
    // In chat bubbles, users are typically at the bottom, so we always scroll when keyboard opens
    // PERFORMANCE: Use isKeyboardOpen derived state instead of imeBottom to reduce recomposition
    var previousKeyboardOpen by remember { mutableStateOf(isKeyboardOpen) }
    LaunchedEffect(isKeyboardOpen) {
        if (timelineItems.isEmpty() || listState.layoutInfo.totalItemsCount == 0) {
            previousKeyboardOpen = isKeyboardOpen
            return@LaunchedEffect
        }

        val keyboardJustOpened = !previousKeyboardOpen && isKeyboardOpen

        // When keyboard opens, always scroll to bottom in bubbles (users are typically at bottom)
        if (keyboardJustOpened && hasInitialSnapCompleted) {
            // Check actual scroll position for debugging
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastTimelineItemIndex = timelineItems.lastIndex

            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Keyboard opening - lastVisibleIndex=$lastVisibleIndex, lastIndex=$lastTimelineItemIndex, isAttachedToBottom=$isAttachedToBottom",
                )
            }

            // With reverseLayout, bottom anchor stays fixed automatically when keyboard opens
            // But we can explicitly scroll to 0 to ensure we're at bottom
            // Wait for layout to actually adjust (viewport shrinks due to keyboard)
            var layoutSettled = false
            val initialLayoutHeight = listState.layoutInfo.viewportSize.height
            var attempts = 0
            while (!layoutSettled && attempts < 10) {
                kotlinx.coroutines.delay(50)
                val currentLayoutHeight = listState.layoutInfo.viewportSize.height
                // Layout has changed (viewport shrunk due to keyboard)
                if (currentLayoutHeight < initialLayoutHeight - 50) {
                    layoutSettled = true
                }
                attempts++
            }
            // Additional small delay to ensure layout is fully settled
            kotlinx.coroutines.delay(50)

            // Animate scroll to bottom for smooth transition
            coroutineScope.launch {
                animatedScrollTo(0)
                isAttachedToBottom = true // Update state
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Keyboard opened, animated scroll to bottom (index=0) after layout settled - reverseLayout",
                    )
                }
            }
        }

        previousKeyboardOpen = isKeyboardOpen
    }

    // Auto-scroll after each individual message bubble animation completes
    // This ensures we scroll after each message is rendered, not just when all animations finish

    // NOTE: markRoomAsRead is handled by navigateToRoomWithCache (and, for later arrivals, by the
    // sync ingest path in AppViewModel), so this screen must not call it. This used to mark read
    // here with the last *rendered* item, which is the anti-pattern those paths explicitly avoid:
    // they target latestRowidEventId, because a rendered-last target can sit behind genuinely newer
    // non-rendered events (e.g. com.beeper.message_send_status) and leave the room unread
    // server-side — or regress m.fully_read below where the coordinator just put it.

    LaunchedEffect(timelineItems.size, readinessCheckComplete, pendingInitialScroll, isLoading) {
        if (pendingInitialScroll && readinessCheckComplete && timelineItems.isNotEmpty() &&
            timelineItems.size != lastInitialScrollSize
        ) {
            coroutineScope.launch {
                // With reverseLayout, index 0 is bottom
                listState.scrollToItem(0)
                isAttachedToBottom = true
                hasInitialSnapCompleted = true
                hasLoadedInitialBatch = true
                pendingInitialScroll = false
                lastInitialScrollSize = timelineItems.size
            }
        } else if (readinessCheckComplete && !isLoading && timelineItems.isEmpty() && !hasInitialSnapCompleted) {
            // Not gated on pendingInitialScroll — see the matching branch in RoomTimelineScreen:
            // a warm re-open leaves that flag false, so these would otherwise never settle for a
            // room whose whole window is hidden membership events.
            // Loaded, but nothing renderable (archived group room whose window is all hidden
            // membership events). Nothing to scroll to, but the load is done — mark it so the
            // auto-paginate effect, which requires these flags, can dig past the barren window.
            hasInitialSnapCompleted = true
            hasLoadedInitialBatch = true
            pendingInitialScroll = false
            lastInitialScrollSize = 0
        }
    }

    LaunchedEffect(roomId) {
        readinessCheckComplete = false
        pendingInitialScroll = true
        lastInitialScrollSize = 0
        highlightedEventId = null
        highlightRequestId = 0
        appViewModel.promoteToPrimaryIfNeeded("bubble_timeline_$roomId")

        // PERFORMANCE FIX: Only call navigateToRoomWithCache if room isn't already loaded
        // RoomListScreen already calls it when user clicks, so we skip duplicate processing
        val isAlreadyLoaded = appViewModel.currentRoomId == roomId && appViewModel.timelineEvents.isNotEmpty()
        if (!isAlreadyLoaded) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Room $roomId not yet loaded, calling navigateToRoomWithCache",
                )
            }
            appViewModel.navigateToRoomWithCache(roomId)
        } else {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Room $roomId already loaded (${appViewModel.timelineEvents.size} events), skipping navigateToRoomWithCache",
                )
            }
        }

        // CRITICAL: Add room to opened rooms (exempt from cache clearing on WebSocket reconnect)
        RoomTimelineCache.addOpenedRoom(roomId)

        val requireInitComplete = !appViewModel.isWebSocketConnected()
        val readinessResult = appViewModel.awaitRoomDataReadiness(
            requireInitComplete = requireInitComplete,
            roomId = roomId,
        )
        readinessCheckComplete = true
        if (!readinessResult && BuildConfig.DEBUG) {
            Log.w(
                "Andromuks",
                "BubbleTimelineScreen: Readiness timeout while opening $roomId - continuing with partial data",
            )
        }
        if (BuildConfig.DEBUG) Log.d("Andromuks", "BubbleTimelineScreen: Loading timeline for room: $roomId")
        if (appViewModel.isAppVisible) {
            appViewModel.markTimelineForeground(roomId)
        }
        // Reset state for new room
        isLoadingMore = false
        pendingScrollRestoration = false
        highestVisibleIndexBeforePagination = null
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
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Removed room $roomId from opened rooms (bubble closed)",
                )
            }
        }
    }

    // Track last known refresh trigger to detect when app resumes
    var lastKnownRefreshTrigger by remember { mutableIntStateOf(appViewModel.timelineRefreshTrigger) }
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
            appViewModel.timelineRefreshTrigger != lastKnownRefreshTrigger
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: App resumed, refreshing timeline for room: $roomId",
                )
            }
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
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Andromuks",
                            "BubbleTimelineScreen: Received FOREGROUND_REFRESH broadcast, refreshing timeline UI from cache for room: $roomId",
                        )
                    }
                    // Lightweight timeline refresh from cached data (no network requests)
                    appViewModel.refreshTimelineUI()
                }
            }
        }

        val filter = IntentFilter("net.vrkknn.andromuks.FOREGROUND_REFRESH")
        ContextCompat.registerReceiver(context, foregroundRefreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Registered FOREGROUND_REFRESH broadcast receiver",
            )
        }

        onDispose {
            try {
                context.unregisterReceiver(foregroundRefreshReceiver)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Unregistered FOREGROUND_REFRESH broadcast receiver",
                    )
                }
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
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Validating user profiles for ${sortedEvents.size} events",
                )
            }
            appViewModel.validateAndRequestMissingProfiles(roomId, sortedEvents)
        }
    }

    // OPPORTUNISTIC PROFILE LOADING: Only request profiles when actually needed for rendering
    // This prevents loading 15,000+ profiles upfront for large rooms
    LaunchedEffect(sortedEvents, roomId) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Using opportunistic profile loading for $roomId (no bulk loading)",
            )
        }

        // Only request profiles for users that are actually visible in the timeline
        // This dramatically reduces memory usage for large rooms
        if (sortedEvents.isNotEmpty()) {
            val visibleUsers = buildSet {
                sortedEvents.take(50).forEach { add(it.sender) }
                sortedEvents.takeLast(50).forEach { add(it.sender) }
            }

            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Requesting profiles on-demand for ${visibleUsers.size} visible users (instead of all ${sortedEvents.size} events)",
                )
            }

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
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "BubbleTimelineScreen: Saving ${profilesToSave.size} profiles to disk for users in timeline",
                    )
                }
                // Profiles are cached in-memory only - no DB persistence needed
            }
        }
    }

    // Ensure timeline reactively updates when new events arrive from sync
    // OPTIMIZED: Only track timelineEvents changes directly, updateCounter is handled by receipt updates
    LaunchedEffect(timelineEvents) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "BubbleTimelineScreen: Timeline events changed - timelineEvents.size: ${timelineEvents.size}, currentRoomId: ${appViewModel.currentRoomId}, roomId: $roomId",
            )
        }

        // Only react to changes for the current room
        if (appViewModel.currentRoomId == roomId) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "BubbleTimelineScreen: Detected timeline update for current room $roomId with ${timelineEvents.size} events",
                )
            }

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
        if (messageMenuConfig != null) {
            // Close message menu if open
            messageMenuConfig = null
        } else if (showAttachmentMenu) {
            // Close attachment menu if open
            showAttachmentMenu = false
        } else if (jumpBackStack.isNotEmpty()) {
            // Return to the position we were at before the last reply jump
            val (index, offset) = jumpBackStack.removeLast()
            coroutineScope.launch { listState.scrollToItem(index, offset) }
        } else {
            onCloseBubble()
        }
    }

    CompositionLocalProvider(
        LocalScrollHighlightState provides ScrollHighlightState(
            eventId = highlightedEventId,
            requestId = highlightRequestId,
        ),
        LocalActiveMessageMenuEventId provides messageMenuConfig?.event?.eventId,
        net.vrkknn.andromuks.ui.components.LocalIsScrollingFast provides isAnimatedScrolling,
    ) {
        AndromuksTheme {
            Surface {
                Box(modifier = modifier.fillMaxSize()) {
                    Column(
                        modifier =
                        Modifier.fillMaxSize()
                            .imePadding() // Handle keyboard padding at Column level
                            .then(
                                if (showDeleteDialog) {
                                    Modifier.blur(10.dp)
                                } else {
                                    Modifier
                                },
                            ),
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
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "Andromuks",
                                        "BubbleTimelineScreen: Header click disabled - room info not available in bubble navigation",
                                    )
                                }
                            },
                            onOpenInApp = onOpenInApp,
                            onCloseBubble = onCloseBubble,
                            onMinimizeBubble = onMinimizeBubble,
                            onRefreshClick = {
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "Andromuks",
                                        "BubbleTimelineScreen: Full refresh button clicked for room $roomId",
                                    )
                                }
                                isRefreshing = true
                                appViewModel.setAutoPaginationEnabled(false, "bubble_manual_refresh_ui_$roomId")
                                appViewModel.fullRefreshRoomTimeline(roomId)
                            },
                        )

                        if (appViewModel.notificationActionInProgress) {
                            ExpressiveStatusRow(
                                text = "Completing notification action...",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                            )
                        }

                        // Show upload status when uploads are in progress
                        if (appViewModel.hasUploadInProgress(roomId)) {
                            val uploadType = appViewModel.getUploadType(roomId)
                            val retryCount = appViewModel.getUploadRetryCount(roomId)
                            val retrySuffix = if (retryCount > 0) " (Retrying $retryCount/3)" else ""
                            val statusText = when (uploadType) {
                                "video" -> "Uploading video$retrySuffix..."
                                "audio" -> "Uploading audio$retrySuffix..."
                                "file" -> "Uploading file$retrySuffix..."
                                "image" -> "Uploading image$retrySuffix..."
                                else -> "Uploading media$retrySuffix..."
                            }
                            val uploadProgress = appViewModel.getUploadProgress(roomId)
                            ExpressiveStatusRow(
                                text = statusText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                                progress = uploadProgress,
                            )
                        }

                        // Pagination indicator: visible while older messages are being fetched/merged
                        AnimatedVisibility(
                            visible = appViewModel.isPaginating,
                            enter = scaledColumnEnter(),
                            exit = scaledColumnExit(),
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        // 2. Timeline (compressible, scrollable content)
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                // Shrink by the open menu bar's height so it doesn't cover the last message
                                .padding(bottom = timelineMenuInset)
                                .then(
                                    if (isMenuOpen) {
                                        Modifier.clickable {
                                            // Close attachment menu or message menu when tapping outside
                                            showAttachmentMenu = false
                                            messageMenuConfig = null
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            if (!readinessCheckComplete || isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        ExpressiveLoadingIndicator(modifier = Modifier.size(80.dp))
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = if (appViewModel.postJoinLoadingRooms.contains(
                                                    roomId,
                                                )
                                            ) {
                                                "Waiting for room data"
                                            } else {
                                                "Loading timeline..."
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                DisposableEffect(listState, coroutineScope) {
                                    TimelineMediaLayoutCallback.callback = {
                                        coroutineScope.launch {
                                            if (pendingScrollRestoration) return@launch
                                            if (listState.firstVisibleItemIndex == 0 &&
                                                listState.firstVisibleItemScrollOffset < 100
                                            ) {
                                                animatedScrollTo(0)
                                            }
                                        }
                                    }
                                    onDispose { TimelineMediaLayoutCallback.callback = null }
                                }
                                // clipToBounds ensures the date pill slides from behind the header rather than over it
                                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                                    // Oldest visible item is at the highest index in the reversed list (top of screen)
                                    val reversedBubbleItems = remember(timelineItems) { timelineItems.reversed() }
                                    val oldestVisibleDateBubble by remember(reversedBubbleItems) {
                                        derivedStateOf {
                                            val highestIdx = listState.layoutInfo.visibleItemsInfo
                                                .maxOfOrNull { it.index } ?: return@derivedStateOf null
                                            when (val item = reversedBubbleItems.getOrNull(highestIdx)) {
                                                // Shared formatter: this derivedStateOf re-evaluates on every
                                                // scroll frame, and it used to build a SimpleDateFormat each time.
                                                is BubbleTimelineItem.Event -> formatDate(item.event.timestamp)

                                                is BubbleTimelineItem.DateDivider -> item.date

                                                else -> null
                                            }
                                        }
                                    }
                                    val scrollKeyBubble by remember { derivedStateOf { listState.firstVisibleItemIndex } }

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pullRefresh(pullRefreshState),
                                        state = listState,
                                        // CRITICAL: Use reverseLayout to anchor list at bottom (like WhatsApp/Google Messages)
                                        // This makes keyboard handling automatic - viewport shrinks but bottom anchor stays fixed
                                        reverseLayout = true,
                                        // PERFORMANCE: Optimize for timeline rendering with proper padding and settings
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            start = 8.dp,
                                            end = 0.dp,
                                            top = 8.dp,
                                            // reverseLayout ⇒ this is the gap between the newest
                                            // bubble and the typing area. It used to be 120.dp,
                                            // which read as dead space no message ever occupied.
                                            bottom = 8.dp,
                                        ),
                                        // PERFORMANCE: Enable smooth scrolling optimizations
                                        userScrollEnabled = true,
                                    ) {
                                        // PERFORMANCE: Use stable keys and pre-computed consecutive flags
                                        // CRITICAL: Reverse items list since reverseLayout flips rendering order but not data order
                                        itemsIndexed(
                                            // Use the memoized reversal above. This used to call
                                            // timelineItems.reversed() inline, allocating a full copy of the
                                            // list on every recomposition of this Box — which, before the
                                            // sticky-pill scroll reads were deferred, meant every scroll frame.
                                            items = reversedBubbleItems,
                                            key = { _, item -> item.stableKey },
                                            // Heterogeneous items: without contentType, Lazy layout cannot
                                            // recycle subcompositions between them and pays a fresh
                                            // composition for every item entering the viewport.
                                            contentType = { _, item ->
                                                when (item) {
                                                    is BubbleTimelineItem.DateDivider -> "date"
                                                    is BubbleTimelineItem.Event -> "event"
                                                }
                                            },
                                        ) { index, item ->
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

                                                    // Add a little extra spacing before non-consecutive messages
                                                    // (only when the previous timeline item is also an event).
                                                    // The visually-previous (older) item sits at the HIGHER
                                                    // reversed index, so it's reversedBubbleItems[index + 1].
                                                    // This previously indexed timelineItems — the NON-reversed
                                                    // list — with a reversed index, so it read an unrelated
                                                    // item. Read from the same list the lambda iterates, and
                                                    // use getOrNull: a retained/prefetched item can recompose
                                                    // at a stale index while the backing list is mid-swap.
                                                    // (Mirrors RoomTimelineScreen, which already fixed this.)
                                                    val previousItem = reversedBubbleItems.getOrNull(index + 1)
                                                    val addTopSpacing =
                                                        previousItem is BubbleTimelineItem.Event && !isConsecutive

                                                    Column {
                                                        if (addTopSpacing) {
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                        }

                                                        TimelineEventItem(
                                                            event = event,
                                                            timelineEvents = timelineEvents,
                                                            editsByTargetId = editEventsByTargetId,
                                                            homeserverUrl = homeserverUrl,
                                                            authToken = authToken,
                                                            userProfileCache = memberMap,
                                                            isMine = isMine,
                                                            myUserId = myUserId,
                                                            isConsecutive = isConsecutive,
                                                            absorbedReceiptEventIds = item.absorbedReceiptEventIds,
                                                            appViewModel = appViewModel,
                                                            onScrollToMessage = { eventId ->
                                                                // PERFORMANCE: Find the index in timelineItems instead of sortedEvents
                                                                val indexInOriginal = timelineItems.indexOfFirst { item ->
                                                                    when (item) {
                                                                        is BubbleTimelineItem.Event -> item.event.eventId == eventId
                                                                        is BubbleTimelineItem.DateDivider -> false
                                                                    }
                                                                }
                                                                if (indexInOriginal >= 0) {
                                                                    // Convert to reversed index: if item is at index N in original, it's at (lastIndex - N) in reversed
                                                                    val lastIndex = timelineItems.lastIndex
                                                                    val reversedIndex = lastIndex - indexInOriginal
                                                                    // Save current position so Back can return here
                                                                    jumpBackStack.addLast(
                                                                        listState.firstVisibleItemIndex to
                                                                            listState.firstVisibleItemScrollOffset,
                                                                    )
                                                                    coroutineScope.launch {
                                                                        listState.scrollToItem(reversedIndex)
                                                                        highlightedEventId = eventId
                                                                        highlightRequestId++
                                                                    }
                                                                } else {
                                                                    val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                                                    val encodedEventId = java.net.URLEncoder.encode(eventId, "UTF-8")
                                                                    pendingEventContextScrollRestore =
                                                                        listState.firstVisibleItemIndex to
                                                                        listState.firstVisibleItemScrollOffset
                                                                    navController.navigate(
                                                                        "event_context/$encodedRoomId/$encodedEventId",
                                                                    )
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
                                                                // CRITICAL FIX: Disable user info navigation in chat bubbles - user_info route doesn't exist
                                                                // in the bubble navigation graph. Users can open the full app to access user info.
                                                                if (BuildConfig.DEBUG) {
                                                                    Log.d(
                                                                        "Andromuks",
                                                                        "BubbleTimelineScreen: User click disabled - user info not available in bubble navigation",
                                                                    )
                                                                }
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    "Open in full app to view user profile",
                                                                    android.widget.Toast.LENGTH_SHORT,
                                                                ).show()
                                                            },
                                                            onRoomLinkClick = { roomLink ->
                                                                if (BuildConfig.DEBUG) {
                                                                    Log.d(
                                                                        "Andromuks",
                                                                        "BubbleTimelineScreen: Room link clicked: ${roomLink.roomIdOrAlias}",
                                                                    )
                                                                }

                                                                // Extract server from message sender (format: @user:server.com)
                                                                val senderServer = try {
                                                                    if (event.sender.contains(":")) {
                                                                        event.sender.substringAfter(":")
                                                                    } else {
                                                                        null
                                                                    }
                                                                } catch (e: Exception) {
                                                                    null
                                                                }

                                                                // Add sender's server to viaServers if available
                                                                val enhancedViaServers = if (senderServer != null &&
                                                                    !roomLink.viaServers.contains(
                                                                        senderServer,
                                                                    )
                                                                ) {
                                                                    roomLink.viaServers + senderServer
                                                                } else {
                                                                    roomLink.viaServers
                                                                }

                                                                val enhancedRoomLink = roomLink.copy(viaServers = enhancedViaServers)

                                                                // If it's a room ID, check if we're already joined
                                                                val existingRoom = if (enhancedRoomLink.roomIdOrAlias.startsWith("!")) {
                                                                    val room = appViewModel.getRoomById(enhancedRoomLink.roomIdOrAlias)
                                                                    if (BuildConfig.DEBUG) {
                                                                        Log.d(
                                                                            "Andromuks",
                                                                            "BubbleTimelineScreen: Checked for existing room ${enhancedRoomLink.roomIdOrAlias}, found: ${room != null}",
                                                                        )
                                                                    }
                                                                    room
                                                                } else {
                                                                    if (BuildConfig.DEBUG) {
                                                                        Log.d(
                                                                            "Andromuks",
                                                                            "BubbleTimelineScreen: Room link is an alias, showing joiner",
                                                                        )
                                                                    }
                                                                    null
                                                                }

                                                                if (existingRoom != null) {
                                                                    // Already joined, navigate directly
                                                                    val targetRoomId = enhancedRoomLink.roomIdOrAlias
                                                                    if (BuildConfig.DEBUG) {
                                                                        Log.d(
                                                                            "Andromuks",
                                                                            "BubbleTimelineScreen: Already joined, navigating to $targetRoomId",
                                                                        )
                                                                    }
                                                                    // If this is an event permalink, stash the jump so the opened room lands on it.
                                                                    enhancedRoomLink.eventId?.let {
                                                                        appViewModel.setPendingInterRoomJump(
                                                                            targetRoomId,
                                                                            it,
                                                                        )
                                                                    }
                                                                    // CRITICAL: When navigating from one room_timeline to another, use setDirectRoomNavigation
                                                                    // and navigate via room_list, letting RoomListScreen handle the final navigation.
                                                                    // This matches the pattern used by notifications/shortcuts and ensures proper state management.
                                                                    appViewModel.setCurrentRoomIdForTimeline(targetRoomId)
                                                                    appViewModel.setDirectRoomNavigation(targetRoomId)
                                                                    navController.navigate("room_list")
                                                                } else {
                                                                    // For aliases or non-joined rooms, show room joiner
                                                                    if (BuildConfig.DEBUG) {
                                                                        Log.d(
                                                                            "Andromuks",
                                                                            "BubbleTimelineScreen: Not joined, showing room joiner with via servers: $enhancedViaServers",
                                                                        )
                                                                    }
                                                                    roomLinkToJoin = enhancedRoomLink
                                                                    showRoomJoiner = true
                                                                }
                                                            },
                                                            onThreadClick = { threadEvent ->
                                                                // Navigate to thread viewer
                                                                val threadInfo = threadEvent.getThreadInfo()
                                                                if (threadInfo != null) {
                                                                    if (BuildConfig.DEBUG) {
                                                                        Log.d(
                                                                            "Andromuks",
                                                                            "BubbleTimelineScreen: Thread message clicked, opening thread for root: ${threadInfo.threadRootEventId}",
                                                                        )
                                                                    }
                                                                    val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                                                    val encodedThreadRoot = java.net.URLEncoder.encode(
                                                                        threadInfo.threadRootEventId,
                                                                        "UTF-8",
                                                                    )
                                                                    navController.navigate(
                                                                        "thread_viewer/$encodedRoomId/$encodedThreadRoot",
                                                                    )
                                                                }
                                                            },
                                                            onCodeBlockClick = { code ->
                                                                codeViewerContent = code
                                                                showCodeViewer = true
                                                            },
                                                            onShowMenu = { menuConfig ->
                                                                // Close attach menu if open
                                                                showAttachmentMenu = false
                                                                messageMenuConfig = menuConfig.copy(
                                                                    onViewSource = { code ->
                                                                        codeViewerContent = code
                                                                        showCodeViewer = true
                                                                    },
                                                                    onViewRenderedText = { text ->
                                                                        codeViewerContent = text
                                                                        showCodeViewer = true
                                                                    },
                                                                    onShowReactions = {
                                                                        reactionsEventId = menuConfig.event.eventId
                                                                        showReactionsDialog = true
                                                                    },
                                                                    onViewInThread = if (menuConfig.event.isThreadMessage()) {
                                                                        {
                                                                            val threadInfo = menuConfig.event.getThreadInfo()
                                                                            if (threadInfo != null) {
                                                                                val encodedRoomId = java.net.URLEncoder.encode(
                                                                                    roomId,
                                                                                    "UTF-8",
                                                                                )
                                                                                val encodedThreadRoot = java.net.URLEncoder.encode(
                                                                                    threadInfo.threadRootEventId,
                                                                                    "UTF-8",
                                                                                )
                                                                                navController.navigate(
                                                                                    "thread_viewer/$encodedRoomId/$encodedThreadRoot",
                                                                                )
                                                                            }
                                                                        }
                                                                    } else {
                                                                        null
                                                                    },
                                                                    onShowBridgeDeliveryInfo = if (appViewModel.messageBridgeSendStatus.containsKey(
                                                                            menuConfig.event.eventId,
                                                                        )
                                                                    ) {
                                                                        {
                                                                            bridgeDeliveryEventId = menuConfig.event.eventId
                                                                            showBridgeDeliveryDialog = true
                                                                        }
                                                                    } else {
                                                                        null
                                                                    },
                                                                )
                                                            },
                                                            onShowReactions = {
                                                                reactionsEventId = event.eventId
                                                                showReactionsDialog = true
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Sticky date pill — shows date of oldest visible event while scrolling up
                                    net.vrkknn.andromuks.utils.StickyDateIndicator(
                                        oldestVisibleDate = { oldestVisibleDateBubble },
                                        scrollPositionKey = { scrollKeyBubble },
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 8.dp)
                                            .zIndex(1f),
                                    )

                                    // Pull-to-refresh indicator (outside LazyColumn, inside Box)
                                    PullRefreshIndicator(
                                        refreshing = isRefreshingPull,
                                        state = pullRefreshState,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                }
                            }
                        }

                        // 4. Typing notification area (stacks naturally above text box)
                        TypingNotificationArea(
                            typingUsers = appViewModel.getTypingUsersForRoom(roomId),
                            roomId = roomId,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            userProfileCache = appViewModel.getMemberMap(roomId),
                            appViewModel = appViewModel,
                        )

                        // 5. Text box (always at the bottom, above keyboard/nav bar)
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                            modifier =
                            Modifier.fillMaxWidth()
                                .navigationBarsPadding(),
                            // .imePadding() removed - Column handles it now
                        ) {
                            Row(
                                modifier =
                                Modifier.fillMaxWidth().padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                // Main attach button
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.width(48.dp).height(buttonHeight),
                                ) {
                                    IconButton(
                                        enabled = isInputEnabled,
                                        onClick = {
                                            if (isInputEnabled) {
                                                // Close message menu if open
                                                messageMenuConfig = null
                                                showAttachmentMenu = !showAttachmentMenu
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AttachFile,
                                            contentDescription = "Attach",
                                            tint = if (isInputEnabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.38f,
                                                )
                                            },
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Pill-shaped text input with optional reply preview inside
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape =
                                    RoundedCornerShape(
                                        16.dp,
                                    ), // Rounded rectangle that works both as pill and expanded
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column {
                                        // Armed per-message profile (MSC4461) — travels in base_content on send
                                        val armedPmpProfile = selectedPmpProfile
                                        val roomDefaultPmpProfile = remember(roomId, appViewModel.timelineRefreshTrigger) {
                                            resolveDefaultPerMessageProfile(roomId)
                                        }
                                        if (armedPmpProfile != null) {
                                            PerMessageProfileChip(
                                                profile = armedPmpProfile,
                                                homeserverUrl = appViewModel.homeserverUrl,
                                                authToken = appViewModel.authToken,
                                                onClick = { showPmpProfilePicker = true },
                                                onClear = { selectedPmpProfile = null },
                                            )
                                        } else if (roomDefaultPmpProfile != null) {
                                            // MSC4461 rev-3 default_profile_id. gomuks applies this itself when no
                                            // trigger matches, so the chip is informational and must not arm
                                            // base_content — that would override the user's own trigger prefixes.
                                            PerMessageProfileDefaultChip(
                                                profile = roomDefaultPmpProfile,
                                                homeserverUrl = appViewModel.homeserverUrl,
                                                authToken = appViewModel.authToken,
                                                onClick = { showPmpProfilePicker = true },
                                            )
                                        }

                                        // Edit preview inside the text input (if editing)
                                        if (editingEvent != null) {
                                            EditPreviewInput(
                                                event = editingEvent!!,
                                                onCancel = {
                                                    editingEvent = null
                                                    draft = "" // Clear draft when canceling edit
                                                },
                                            )
                                        }

                                        // Reply preview inside the text input (if replying)
                                        if (replyingToEvent != null) {
                                            ReplyPreviewInput(
                                                event = replyingToEvent!!,
                                                userProfileCache = memberMap, // Use reactive memberMap instead of static userProfileCache
                                                onCancel = { replyingToEvent = null },
                                                appViewModel = appViewModel,
                                                roomId = roomId,
                                            )
                                        }

                                        // URL preview composition bar
                                        if (appViewModel.resolveSendBundledUrlPreviews(roomId)) {
                                            UrlPreviewCompositionBar(
                                                text = draft,
                                                controller = urlPreviewController,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                isRoomEncrypted = appViewModel.currentRoomState?.isEncrypted
                                                    ?: RoomStateStore.getParsed(roomId)?.isEncrypted ?: false,
                                            )
                                        }

                                        // Create combined transformation for mentions and custom emojis
                                        val colorScheme = MaterialTheme.colorScheme
                                        val customEmojiPacks = appViewModel.customEmojiPacks
                                        val mentionAndEmojiTransformation = remember(colorScheme, customEmojiPacks) {
                                            VisualTransformation { text ->
                                                val mentionRegex = Regex(
                                                    """\[((?:[^\[\]\\]|\\.)*)\]\(https://matrix\.to/#/([^)]+)\)""",
                                                )
                                                // Regex for custom emoji markdown: ![:name:](mxc://url "Emoji: :name:")
                                                val customEmojiRegex =
                                                    Regex("""!\[:([^:]+):\]\((mxc://[^)]+)\s+"[^"]*"\)""")

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
                                                                    background = colorScheme.primaryContainer,
                                                                ),
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
                                                                val result =
                                                                    transformedOffset + (clampedOffset - originalOffset)
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
                                                                val result =
                                                                    originalOffset + (clampedOffset - transformedOffset)
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

                                                // MSC4391: keep the bot-command invocation (and the signature strip)
                                                // in step with the draft before deciding what to suggest.
                                                composerCommands.onDraftChanged(
                                                    replacedValue.text,
                                                    replacedValue.selection.start,
                                                )
                                                // Detect commands first ( /command ) - check before everything else
                                                val commandResult = detectCommandQuery(
                                                    replacedValue.text,
                                                    replacedValue.selection.start,
                                                    composerCommands.multiWordPrefixes,
                                                )
                                                // Detect /pmp picker: draft is exactly "/pmp" or "/profile", nothing after
                                                showPmpProfilePicker = isBarePerMessageProfileCommand(replacedValue.text)
                                                // Detect /poll: navigation is deferred to a
                                                // LaunchedEffect so we never navigate from inside
                                                // onValueChange while the field is committing.
                                                if (isBarePollCommand(replacedValue.text)) {
                                                    pendingPollMaker = true
                                                }

                                                if (commandResult != null) {
                                                    val (query, startIndex) = commandResult
                                                    commandQuery = query
                                                    commandStartIndex = startIndex
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "BubbleTimelineScreen: / detected, query='$query'",
                                                        )
                                                    }
                                                    showCommandSuggestionList = !showPmpProfilePicker
                                                    // Hide other suggestion lists when command is active
                                                    showMentionList = false
                                                    showEmojiSuggestionList = false
                                                    showRoomSuggestionList = false
                                                } else {
                                                    showCommandSuggestionList = false

                                                    // Strict else-chain, mirroring RoomTimelineScreen:
                                                    // command → room → mention → emoji, at most one
                                                    // list visible. These used to run as three
                                                    // independent ifs, so `@ali :smi` or a `#alias`
                                                    // typed inside a mention stacked two or three
                                                    // popups on the same bottom-start anchor.

                                                    // Detect room mentions ( #roomalias ) - check before mentions/emojis
                                                    val roomResult = detectRoomMention(
                                                        replacedValue.text,
                                                        replacedValue.selection.start,
                                                    )
                                                    if (roomResult != null) {
                                                        val (query, startIndex) = roomResult
                                                        roomQuery = query
                                                        roomStartIndex = startIndex
                                                        showRoomSuggestionList = true
                                                        // Hide other suggestion lists when room mention is active
                                                        showMentionList = false
                                                        showEmojiSuggestionList = false
                                                    } else {
                                                        showRoomSuggestionList = false

                                                        // Detect mentions
                                                        val mentionResult = detectMention(
                                                            replacedValue.text,
                                                            replacedValue.selection.start,
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
                                                                    if (BuildConfig.DEBUG) {
                                                                        Log.d(
                                                                            "Andromuks",
                                                                            "BubbleTimelineScreen: @ detected, requesting fresh member list for room $roomId",
                                                                        )
                                                                    }
                                                                    isWaitingForFullMemberList = true
                                                                    lastMemberUpdateCounterBeforeMention =
                                                                        appViewModel.memberUpdateCounter
                                                                    appViewModel.requestFullMemberList(roomId)
                                                                } else {
                                                                    // We already have members in memory, show list immediately
                                                                    showMentionList = true
                                                                }
                                                            }
                                                            // Hide other suggestion lists when mention is active
                                                            showEmojiSuggestionList = false
                                                        } else {
                                                            showMentionList = false
                                                            isWaitingForFullMemberList = false

                                                            // Detect emoji shortcodes ( :shortname )
                                                            val emojiResult = detectEmojiShortcode(
                                                                replacedValue.text,
                                                                replacedValue.selection.start,
                                                            )
                                                            if (emojiResult != null) {
                                                                val (query, startIndex) = emojiResult
                                                                emojiQuery = query
                                                                emojiStartIndex = startIndex
                                                                showEmojiSuggestionList = true
                                                            } else {
                                                                showEmojiSuggestionList = false
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            placeholder = {
                                                Text(
                                                    text = when {
                                                        !canSendMessage -> "You don't have permission to send messages"

                                                        else -> {
                                                            val networkName = appViewModel.currentRoomState?.bridgeInfo?.displayName
                                                            if (networkName != null && networkName.isNotBlank()) {
                                                                "Type a $networkName message..."
                                                            } else {
                                                                "Type a message..."
                                                            }
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontStyle = FontStyle.Italic,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(textFieldFocusRequester),
                                            minLines = 1,
                                            maxLines = 5,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                            onHeightChanged = { height ->
                                                // Only update if text is empty or single-line (to get the minimum height)
                                                val lineCount = draft.lines().size.coerceAtLeast(1)
                                                if (lineCount == 1 && (textFieldHeight == 0 || height < textFieldHeight)) {
                                                    textFieldHeight = height
                                                }
                                            },
                                            trailingIcon = {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    // Sticker button
                                                    IconButton(
                                                        enabled = isInputEnabled,
                                                        onClick = { if (isInputEnabled) showStickerPickerForText = true },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        @Suppress("DEPRECATION")
                                                        Icon(
                                                            imageVector = Icons.Outlined.StickyNote2,
                                                            contentDescription = "Stickers",
                                                            tint = if (isInputEnabled) {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                                    alpha = 0.38f,
                                                                )
                                                            },
                                                        )
                                                    }
                                                    // Emoji button
                                                    IconButton(
                                                        enabled = isInputEnabled,
                                                        onClick = { if (isInputEnabled) showEmojiPickerForText = true },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Mood,
                                                            contentDescription = "Emoji",
                                                            tint = if (isInputEnabled) {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                                    alpha = 0.38f,
                                                                )
                                                            },
                                                        )
                                                    }
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(
                                                capitalization = KeyboardCapitalization.Sentences,
                                                keyboardType = KeyboardType.Text,
                                                autoCorrectEnabled = true,
                                                imeAction = ImeAction.Default, // Enter always creates newline, send button always sends
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onSend = {
                                                    if (!isInputEnabled) {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "You don't have permission to send messages",
                                                            android.widget.Toast.LENGTH_SHORT,
                                                        ).show()
                                                        return@KeyboardActions
                                                    }
                                                    if (draft.isNotBlank()) {
                                                        // Check if this is a command first
                                                        val isCommand = appViewModel.executeCommand(
                                                            roomId,
                                                            draft,
                                                            context,
                                                            navController,
                                                        )
                                                        if (isCommand) {
                                                            // Command was executed, clear draft
                                                            draft = ""
                                                            textFieldValue = TextFieldValue("")
                                                            return@KeyboardActions
                                                        }
                                                        // MSC4391 bot commands. Deliberately after executeCommand above: the MSC requires this
                                                        // client's built-in commands to take precedence over anything a bot advertises.
                                                        when (composerCommands.consumeSend(draft, null, replyingToEvent?.eventId)) {
                                                            BotCommandSendOutcome.SENT -> {
                                                                draft = ""
                                                                textFieldValue = TextFieldValue("")
                                                                replyingToEvent = null
                                                                return@KeyboardActions
                                                            }

                                                            // The argument sheet is now open; the draft stays put until it is submitted.
                                                            BotCommandSendOutcome.OPENED_SHEET -> return@KeyboardActions

                                                            BotCommandSendOutcome.NOT_A_BOT_COMMAND -> Unit
                                                        }
                                                        if (draft.trim().startsWith("/")) {
                                                            // Check if it's an avatar command that needs image picker
                                                            val command = draft.trim().lowercase()
                                                            when {
                                                                command == "/myroomavatar" || command == "/myroomavatar " -> {
                                                                    pendingAvatarCommand = "myroomavatar"
                                                                    avatarImagePickerLauncher.launch(
                                                                        PickVisualMediaRequest(
                                                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                                        ),
                                                                    )
                                                                    draft = ""
                                                                    textFieldValue = TextFieldValue("")
                                                                    return@KeyboardActions
                                                                }

                                                                command == "/globalavatar" || command == "/globalavatar " -> {
                                                                    pendingAvatarCommand = "globalavatar"
                                                                    avatarImagePickerLauncher.launch(
                                                                        PickVisualMediaRequest(
                                                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                                        ),
                                                                    )
                                                                    draft = ""
                                                                    textFieldValue = TextFieldValue("")
                                                                    return@KeyboardActions
                                                                }

                                                                command == "/roomavatar" || command == "/roomavatar " -> {
                                                                    pendingAvatarCommand = "roomavatar"
                                                                    avatarImagePickerLauncher.launch(
                                                                        PickVisualMediaRequest(
                                                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                                        ),
                                                                    )
                                                                    draft = ""
                                                                    textFieldValue = TextFieldValue("")
                                                                    return@KeyboardActions
                                                                }
                                                            }
                                                        }

                                                        // Sending always means "put me at the bottom" — except an edit, which
                                                        // rewrites a message that may be far up the timeline and must stay put.
                                                        if (editingEvent == null) {
                                                            snapToBottomForOutgoing()
                                                        }

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
                                                                    fallbackReplyToEventId = replyingToEvent!!.eventId,
                                                                    perMessageProfile = selectedPmpProfile?.toContentMap(),
                                                                )
                                                            } else {
                                                                // Send normal reply
                                                                appViewModel.sendReply(
                                                                    roomId,
                                                                    draft,
                                                                    replyingToEvent!!,
                                                                    selectedPmpProfile?.toContentMap(),
                                                                )
                                                            }
                                                            replyingToEvent = null // Clear reply state
                                                            messageSoundPlayer.play() // Play sound when sending reply
                                                        }
                                                        // Otherwise send regular message
                                                        else {
                                                            appViewModel.sendMessage(
                                                                roomId,
                                                                draft,
                                                                urlPreviewController.getReadyPreviews(),
                                                                selectedPmpProfile?.toContentMap(),
                                                            )
                                                            messageSoundPlayer.play() // Play sound when sending message
                                                        }
                                                        urlPreviewController.clearAll()
                                                        selectedPmpProfile = null // Armed for one message only
                                                        draft = "" // Clear the input after sending
                                                    }
                                                },
                                            ),
                                            visualTransformation = mentionAndEmojiTransformation,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // Show expressive indicator only when an upload is in progress.
                                // Message sends use local echo in the timeline instead of a button spinner.
                                val showSendIndicator = isUploading

                                Button(
                                    onClick = {
                                        if (!isInputEnabled) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "You don't have permission to send messages",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                            return@Button
                                        }
                                        if (draft.isNotBlank()) {
                                            // Check if this is a command first
                                            val isCommand = appViewModel.executeCommand(roomId, draft, context, navController)
                                            if (isCommand) {
                                                // Command was executed, clear draft
                                                draft = ""
                                                textFieldValue = TextFieldValue("")
                                                return@Button
                                            }
                                            // MSC4391 bot commands. Deliberately after executeCommand above: the MSC requires this
                                            // client's built-in commands to take precedence over anything a bot advertises.
                                            when (composerCommands.consumeSend(draft, null, replyingToEvent?.eventId)) {
                                                BotCommandSendOutcome.SENT -> {
                                                    draft = ""
                                                    textFieldValue = TextFieldValue("")
                                                    replyingToEvent = null
                                                    return@Button
                                                }

                                                // The argument sheet is now open; the draft stays put until it is submitted.
                                                BotCommandSendOutcome.OPENED_SHEET -> return@Button

                                                BotCommandSendOutcome.NOT_A_BOT_COMMAND -> Unit
                                            }
                                            if (draft.trim().startsWith("/")) {
                                                // Check if it's an avatar command that needs image picker
                                                val command = draft.trim().lowercase()
                                                when {
                                                    command == "/myroomavatar" || command == "/myroomavatar " -> {
                                                        pendingAvatarCommand = "myroomavatar"
                                                        avatarImagePickerLauncher.launch(
                                                            PickVisualMediaRequest(
                                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                            ),
                                                        )
                                                        draft = ""
                                                        textFieldValue = TextFieldValue("")
                                                        return@Button
                                                    }

                                                    command == "/globalavatar" || command == "/globalavatar " -> {
                                                        pendingAvatarCommand = "globalavatar"
                                                        avatarImagePickerLauncher.launch(
                                                            PickVisualMediaRequest(
                                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                            ),
                                                        )
                                                        draft = ""
                                                        textFieldValue = TextFieldValue("")
                                                        return@Button
                                                    }

                                                    command == "/roomavatar" || command == "/roomavatar " -> {
                                                        pendingAvatarCommand = "roomavatar"
                                                        avatarImagePickerLauncher.launch(
                                                            PickVisualMediaRequest(
                                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                            ),
                                                        )
                                                        draft = ""
                                                        textFieldValue = TextFieldValue("")
                                                        return@Button
                                                    }
                                                }
                                            }

                                            // Sending always means "put me at the bottom" — except an edit, which
                                            // rewrites a message that may be far up the timeline and must stay put.
                                            if (editingEvent == null) {
                                                snapToBottomForOutgoing()
                                            }

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
                                                        fallbackReplyToEventId = replyingToEvent!!.eventId,
                                                        perMessageProfile = selectedPmpProfile?.toContentMap(),
                                                    )
                                                } else {
                                                    // Send normal reply
                                                    appViewModel.sendReply(roomId, draft, replyingToEvent!!, selectedPmpProfile?.toContentMap())
                                                }
                                                replyingToEvent = null // Clear reply state
                                                messageSoundPlayer.play() // Play sound when sending reply
                                            }
                                            // Otherwise send regular message
                                            else {
                                                appViewModel.sendMessage(
                                                    roomId,
                                                    draft,
                                                    urlPreviewController.getReadyPreviews(),
                                                    selectedPmpProfile?.toContentMap(),
                                                )
                                                messageSoundPlayer.play() // Play sound when sending message
                                            }
                                            urlPreviewController.clearAll()
                                            selectedPmpProfile = null // Armed for one message only
                                            draft = "" // Clear the input after sending
                                        }
                                    },
                                    enabled = draft.isNotBlank() && isInputEnabled,
                                    shape = CircleShape, // Perfect circle
                                    colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                        if (draft.isNotBlank()) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ),
                                    modifier = Modifier.size(buttonHeight), // Fixed height matching single-line text field
                                    contentPadding = PaddingValues(0.dp), // No padding for perfect circle
                                ) {
                                    if (showSendIndicator) {
                                        ContainedExpressiveLoadingIndicator(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(6.dp),
                                            shape = CircleShape,
                                            containerColor =
                                            if (draft.isNotBlank()) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            indicatorColor =
                                            if (draft.isNotBlank()) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            contentPadding = 4.dp,
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            tint =
                                            if (draft.isNotBlank()) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            // The paper-plane glyph's mass sits at its tail (centroid
                                            // ~x=9 of a 24 viewport), so geometric centring reads as
                                            // shifted left inside the circle. Nudge 2.dp toward the
                                            // tip; Modifier.offset is direction-aware, so this still
                                            // holds for the auto-mirrored RTL variant.
                                            modifier = Modifier.size(28.dp).offset(x = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Floating action button to scroll to bottom (only shown when detached)
                    // Keep this in the Box so it can overlay the content
                    if (!isAttachedToBottom) {
                        // Push FAB up by the open menu bar's real (animated) height so it never clips
                        val fabBottomPadding = 60.dp + timelineMenuInset
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    listState.scrollToItem(0)
                                    isAttachedToBottom = true
                                }
                            },
                            modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(
                                    end = 16.dp,
                                    bottom = fabBottomPadding,
                                )
                                .navigationBarsPadding(),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
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
                                    bottom = 60.dp, // Closer to text input
                                )
                                .navigationBarsPadding()
                                .zIndex(9f),
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
                                            selection = TextRange(newCursor),
                                        )

                                        // Update recent emojis (same logic as main screen)
                                        val emojiForRecent =
                                            if (baseReplacement.startsWith("![:") && baseReplacement.contains("mxc://")) {
                                                val mxcStart = baseReplacement.indexOf("mxc://")
                                                if (mxcStart >= 0) {
                                                    val mxcEnd = baseReplacement.indexOf("\"", mxcStart)
                                                    if (mxcEnd > mxcStart) {
                                                        baseReplacement.substring(mxcStart, mxcEnd).trimEnd()
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
                                modifier = Modifier.zIndex(10f),
                            )
                        }
                    }

                    // Floating per-message profile picker
                    if (showPmpProfilePicker) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 72.dp, bottom = 60.dp)
                                .navigationBarsPadding()
                                .imePadding()
                                .zIndex(9f),
                        ) {
                            net.vrkknn.andromuks.utils.PerMessageProfilePicker(
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                                roomId = roomId,
                                onProfileSelected = { profile ->
                                    selectedPmpProfile = profile
                                    // The profile now rides in base_content, so the command text is dead weight.
                                    if (isBarePerMessageProfileCommand(draft)) {
                                        draft = ""
                                        textFieldValue = TextFieldValue("")
                                    }
                                    showPmpProfilePicker = false
                                },
                                modifier = Modifier.zIndex(10f),
                            )
                        }
                    }

                    // Floating room suggestion list for room mentions
                    // MSC4391: the signature strip for the command being typed, or its argument
                    // sheet once one is open. Anchored like the other composer overlays.
                    ComposerBotCommandOverlays(
                        state = composerCommands,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        threadRootEventId = null,
                        replyToEventId = replyingToEvent?.eventId,
                        onSend = {
                            snapToBottomForOutgoing()
                            draft = ""
                            textFieldValue = TextFieldValue("")
                            replyingToEvent = null
                        },
                    )

                    // Floating command suggestion list
                    if (showCommandSuggestionList) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(
                                    start = 72.dp, // Align with text input (attach button width + spacing)
                                    bottom = 60.dp, // Closer to text input
                                )
                                .navigationBarsPadding()
                                .imePadding()
                                .zIndex(9f),
                        ) {
                            CommandSuggestionList(
                                query = commandQuery,
                                onCommandSelected = { command ->
                                    // Replace the command text with the selected command
                                    val commandEndIndex = commandStartIndex + 1 + commandQuery.length
                                    val newText = draft.substring(
                                        0,
                                        commandStartIndex,
                                    ) + command.command + " " + draft.substring(commandEndIndex)
                                    val newCursorPosition = commandStartIndex + command.command.length + 1

                                    draft = newText
                                    textFieldValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursorPosition),
                                    )

                                    // Hide the command suggestion list
                                    showCommandSuggestionList = false
                                    commandQuery = ""
                                },
                                modifier = Modifier.zIndex(10f),
                                // MSC4391: the room's bot commands, below the built-ins that shadow them.
                                botCommands = composerCommands.botCommands,
                                onBotCommandSelect = { botCommand ->
                                    textFieldValue = composerCommands.onBotCommandSelect(botCommand)
                                    draft = textFieldValue.text
                                    showCommandSuggestionList = false
                                    commandQuery = ""
                                },
                                botProfileFor = { sender -> composerCommands.botProfile(sender) },
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                            )
                        }
                    }

                    if (showRoomSuggestionList) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(
                                    start = 72.dp, // Align with text input (attach button width + spacing)
                                    bottom = 60.dp, // Closer to text input
                                )
                                .navigationBarsPadding()
                                .zIndex(9f),
                        ) {
                            BubbleRoomSuggestionList(
                                rooms = roomsWithAliases,
                                query = roomQuery,
                                onRoomSelect = { selectedRoomId, canonicalAlias ->
                                    // Replace the room mention text with a markdown link
                                    // Format: [#room:server.com](https://matrix.to/#/%23room%3Aserver.com)
                                    val roomEndIndex = roomStartIndex + 1 + roomQuery.length
                                    val encodedAlias = java.net.URLEncoder.encode(canonicalAlias, "UTF-8")
                                    val roomMentionText = "[$canonicalAlias](https://matrix.to/#/$encodedAlias) "
                                    val newText = draft.substring(
                                        0,
                                        roomStartIndex,
                                    ) + roomMentionText + draft.substring(roomEndIndex)
                                    val newCursorPosition = roomStartIndex + roomMentionText.length

                                    draft = newText
                                    textFieldValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursorPosition),
                                    )

                                    // Hide the room suggestion list
                                    showRoomSuggestionList = false
                                    roomQuery = ""
                                },
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = authToken,
                                modifier = Modifier.zIndex(10f),
                            )
                        }
                    }

                    // Message menu bar (slides from bottom, same position as attach menu)
                    AnimatedVisibility(
                        visible = messageMenuConfig != null,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                        enter = fadeIn(initialAlpha = 1f, animationSpec = tween(durationMillis = scaledTweenMs(120))),
                        exit = fadeOut(targetAlpha = 1f, animationSpec = tween(durationMillis = scaledTweenMs(120))),
                    ) {
                        val messageBarSlideOffsetPx = transition.animateFloat(
                            transitionSpec = {
                                if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                    // ENTER: slide in first
                                    tween(durationMillis = scaledTweenMs(120))
                                } else {
                                    // EXIT: wait for buttons to fade out, then slide down
                                    tween(durationMillis = scaledTweenMs(120), delayMillis = scaledTweenMs(500))
                                }
                            },
                            label = "messageBarSlideOffset",
                        ) { state ->
                            if (state == EnterExitState.Visible) 0f else with(density) { 56.dp.toPx() }
                        }
                        val messageButtonsAlpha = transition.animateFloat(
                            transitionSpec = {
                                if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                    // ENTER: buttons fade in after bar has slid in
                                    tween(durationMillis = scaledTweenMs(500), delayMillis = scaledTweenMs(120))
                                } else {
                                    // EXIT: buttons fade out immediately
                                    tween(durationMillis = scaledTweenMs(500))
                                }
                            },
                            label = "messageButtonsAlpha",
                        ) { state ->
                            if (state == EnterExitState.Visible) 1f else 0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    // Position menu right above footer (same as attach menu)
                                    // Footer height = buttonHeight + 24.dp padding
                                    translationY = -with(
                                        density,
                                    ) { (buttonHeight + 24.dp).toPx() } + messageBarSlideOffsetPx.value
                                }
                                .navigationBarsPadding()
                                .imePadding()
                                .onSizeChanged { menuBarHeightPx = it.height }
                                .zIndex(5f), // Ensure it's above other content
                        ) {
                            net.vrkknn.andromuks.utils.MessageMenuBar(
                                menuConfig = messageMenuConfig ?: retainedMessageMenuConfig,
                                onDismiss = { messageMenuConfig = null },
                                buttonsAlpha = messageButtonsAlpha.value,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // Attachment menu overlay - horizontal floating action bar above footer
                    AnimatedVisibility(
                        visible = showAttachmentMenu,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                        enter = fadeIn(initialAlpha = 1f, animationSpec = tween(durationMillis = scaledTweenMs(120))),
                        exit = fadeOut(targetAlpha = 1f, animationSpec = tween(durationMillis = scaledTweenMs(120))),
                    ) {
                        val attachmentBarSlideOffsetPx = transition.animateFloat(
                            transitionSpec = {
                                if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                    // ENTER: slide in first
                                    tween(durationMillis = scaledTweenMs(120))
                                } else {
                                    // EXIT: wait for buttons to fade out, then slide down
                                    tween(durationMillis = scaledTweenMs(120), delayMillis = scaledTweenMs(500))
                                }
                            },
                            label = "attachmentBarSlideOffset",
                        ) { state ->
                            if (state == EnterExitState.Visible) 0f else with(density) { 56.dp.toPx() }
                        }
                        val attachmentButtonsAlpha = transition.animateFloat(
                            transitionSpec = {
                                if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                    // ENTER: buttons fade in after bar has slid in
                                    tween(durationMillis = scaledTweenMs(500), delayMillis = scaledTweenMs(120))
                                } else {
                                    // EXIT: buttons fade out immediately
                                    tween(durationMillis = scaledTweenMs(500))
                                }
                            },
                            label = "attachmentButtonsAlpha",
                        ) { state ->
                            if (state == EnterExitState.Visible) 1f else 0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    // Position menu right above footer (footer height = buttonHeight + 24.dp padding)
                                    translationY = -with(
                                        density,
                                    ) { (buttonHeight + 24.dp).toPx() } + attachmentBarSlideOffsetPx.value
                                }
                                .navigationBarsPadding()
                                .imePadding()
                                .onSizeChanged { menuBarHeightPx = it.height }
                                .zIndex(5f), // Ensure it's above other content
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Files option
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 1.dp,
                                            modifier = Modifier
                                                .size(56.dp),
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    showAttachmentMenu = false
                                                    // SAF document picker — per-URI read grant, no permission needed.
                                                    filePickerLauncher.launch("*/*")
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Folder,
                                                    contentDescription = "Files",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.alpha(attachmentButtonsAlpha.value),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "File",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Audio option
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 1.dp,
                                            modifier = Modifier
                                                .size(56.dp),
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    showAttachmentMenu = false
                                                    // SAF picker — per-URI read grant, no permission needed.
                                                    audioPickerLauncher.launch("audio/*")
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.AudioFile,
                                                    contentDescription = "Audio",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.alpha(attachmentButtonsAlpha.value),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Audio",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Image/Video option
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 1.dp,
                                            modifier = Modifier
                                                .size(56.dp),
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    showAttachmentMenu = false
                                                    // Android Photo Picker — no permission, consistent Photo/Album sheet.
                                                    mediaPickerLauncher.launch(
                                                        PickVisualMediaRequest(
                                                            ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                                        ),
                                                    )
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Image,
                                                    contentDescription = "Images & Videos",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.alpha(attachmentButtonsAlpha.value),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Image/Video",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }

                                    // Photo option
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 1.dp,
                                            modifier = Modifier
                                                .size(56.dp),
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    showAttachmentMenu = false
                                                    // Camera capture opens a fullscreen camera activity, which Android
                                                    // refuses to launch inside the constrained chat-bubble window
                                                    // ("Camera can't use split screen"). Button kept for menu parity
                                                    // with RoomTimelineScreen, but blocked here.
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Android cannot use camera in Chat Bubbles",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.CameraAlt,
                                                    contentDescription = "Photo",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.alpha(attachmentButtonsAlpha.value),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Photo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Video option
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 1.dp,
                                            modifier = Modifier
                                                .size(56.dp),
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    showAttachmentMenu = false
                                                    // See Photo button above — camera capture can't run in a bubble window.
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Android cannot use camera in Chat Bubbles",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Videocam,
                                                    contentDescription = "Video",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.alpha(attachmentButtonsAlpha.value),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Video",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Location option
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 1.dp,
                                            modifier = Modifier.size(56.dp),
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    showAttachmentMenu = false
                                                    showLocationPickerOverlay = true
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.LocationOn,
                                                    contentDescription = "Location",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.alpha(attachmentButtonsAlpha.value),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Location",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Location picker overlay
                    if (showLocationPickerOverlay) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LocationPickerOverlay(
                                onDismiss = { showLocationPickerOverlay = false },
                                onSendLocation = { lat, lon, caption ->
                                    snapToBottomForOutgoing()
                                    appViewModel.sendLocationMessage(roomId, lat, lon, description = caption)
                                    showLocationPickerOverlay = false
                                },
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
                                    bottom = 60.dp, // Closer to text input
                                )
                                .navigationBarsPadding(),
                        ) {
                            BubbleMentionMemberList(
                                members = roomMembers,
                                query = mentionQuery,
                                onMemberSelect = { userId, displayName ->
                                    // Replace the mention text with the selected user
                                    val mentionEndIndex = mentionStartIndex + 1 + mentionQuery.length
                                    val newText = handleMentionSelection(
                                        userId,
                                        displayName,
                                        draft,
                                        mentionStartIndex,
                                        mentionEndIndex,
                                    )

                                    // Calculate the new cursor position after the inserted mention
                                    // The cursor should be positioned right after the inserted mention text
                                    val escapedDisplayName = (
                                        displayName?.takeIf { it.isNotBlank() }
                                            ?: userId.removePrefix(
                                                "@",
                                            ).substringBefore(":")
                                        )
                                        .replace("[", "\\[")
                                        .replace("]", "\\]")
                                    val mentionText = "[$escapedDisplayName](https://matrix.to/#/$userId)"
                                    val newCursorPosition = mentionStartIndex + mentionText.length

                                    draft = newText
                                    textFieldValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursorPosition),
                                    )

                                    // Hide the mention list
                                    showMentionList = false
                                    mentionQuery = ""
                                },
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = authToken,
                                modifier = Modifier.zIndex(10f),
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
                            },
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
                            },
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
                            customEmojiPacks = appViewModel.customEmojiPacks,
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
                                    selection = TextRange(newCursorPosition),
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
                                            emoji.substring(mxcStart, mxcEnd).trimEnd()
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
                            allowCustomReactions = false,
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

                                snapToBottomForOutgoing()
                                appViewModel.sendStickerMessage(
                                    roomId = roomId,
                                    mxcUrl = sticker.mxcUrl,
                                    body = body,
                                    mimeType = mimeType,
                                    size = size,
                                    width = width,
                                    height = height,
                                )

                                showStickerPickerForText = false
                            },
                            onDismiss = {
                                showStickerPickerForText = false
                            },
                            stickerPacks = appViewModel.stickerPacks,
                        )
                    }

                    // Media preview dialog (shows selected media with caption input)
                    if (showMediaPreview &&
                        (selectedMediaUri != null || selectedAudioUri != null || selectedFileUri != null)
                    ) {
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
                                snapToBottomForOutgoing()

                                // Clear media selection state immediately so user can select new media
                                val mediaUriToUpload = selectedMediaUri
                                val audioUriToUpload = selectedAudioUri
                                val fileUriToUpload = selectedFileUri
                                val isVideoToUpload = selectedMediaIsVideo

                                // Capture the reply target (if any) so the uploaded media is sent as a
                                // reply to the selected message — mirrors the text-reply thread handling
                                // and RoomTimelineScreen. Without this the attachment went out as a
                                // standalone message and the reply preview stayed stuck open.
                                val replyTargetEvent = replyingToEvent
                                val replyThreadRootEventId = replyTargetEvent?.getThreadInfo()?.threadRootEventId
                                val replyToEventId = replyTargetEvent?.eventId

                                // Clear state immediately
                                selectedMediaUri = null
                                selectedAudioUri = null
                                selectedFileUri = null
                                selectedMediaIsVideo = false
                                replyingToEvent = null

                                // Upload and send in background
                                coroutineScope.launch {
                                    // Local helper for uploads with retry
                                    suspend fun <T> performUpload(type: String, uploadBlock: suspend () -> T?): T? {
                                        appViewModel.beginUpload(roomId, type)
                                        try {
                                            var result: T? = null
                                            for (attempt in 0..3) {
                                                if (attempt > 0) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "BubbleTimelineScreen: Retrying $type upload (attempt $attempt/3)",
                                                        )
                                                    }
                                                    appViewModel.setUploadRetryCount(roomId, attempt)
                                                    kotlinx.coroutines.delay(1000L * attempt)
                                                }
                                                result = uploadBlock()
                                                if (result != null) break
                                            }
                                            appViewModel.setUploadRetryCount(roomId, 0)
                                            return result
                                        } finally {
                                            appViewModel.endUpload(roomId, type)
                                        }
                                    }

                                    try {
                                        when {
                                            isVideoToUpload && mediaUriToUpload != null -> {
                                                // Upload video with thumbnail
                                                val videoResult = performUpload("video") {
                                                    VideoUploadUtils.uploadVideo(
                                                        context = context,
                                                        uri = mediaUriToUpload,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        },
                                                    )
                                                }

                                                if (videoResult != null) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "BubbleTimelineScreen: Video upload successful, sending message",
                                                        )
                                                    }
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
                                                        caption = caption.takeIf { it.isNotBlank() },
                                                        threadRootEventId = replyThreadRootEventId,
                                                        replyToEventId = replyToEventId,
                                                        // A real reply, not a thread anchor.
                                                        isThreadFallback = false,
                                                    )
                                                } else {
                                                    Log.e(
                                                        "Andromuks",
                                                        "BubbleTimelineScreen: Video upload failed after retries",
                                                    )
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload video after 3 attempts",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }

                                            audioUriToUpload != null -> {
                                                // Upload audio
                                                val audioResult = performUpload("audio") {
                                                    MediaUploadUtils.uploadAudio(
                                                        context = context,
                                                        uri = audioUriToUpload,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        },
                                                    )
                                                }

                                                if (audioResult != null) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "BubbleTimelineScreen: Audio upload successful, sending message",
                                                        )
                                                    }
                                                    // Send audio message with metadata
                                                    appViewModel.sendAudioMessage(
                                                        roomId = roomId,
                                                        mxcUrl = audioResult.mxcUrl,
                                                        filename = audioResult.filename,
                                                        duration = audioResult.duration,
                                                        size = audioResult.size,
                                                        mimeType = audioResult.mimeType,
                                                        caption = caption.takeIf { it.isNotBlank() },
                                                        threadRootEventId = replyThreadRootEventId,
                                                        replyToEventId = replyToEventId,
                                                        // A real reply, not a thread anchor.
                                                        isThreadFallback = false,
                                                    )
                                                } else {
                                                    Log.e(
                                                        "Andromuks",
                                                        "BubbleTimelineScreen: Audio upload failed after retries",
                                                    )
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload audio after 3 attempts",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }

                                            fileUriToUpload != null -> {
                                                // Upload file
                                                val fileResult = performUpload("file") {
                                                    MediaUploadUtils.uploadFile(
                                                        context = context,
                                                        uri = fileUriToUpload,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        },
                                                    )
                                                }

                                                if (fileResult != null) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "BubbleTimelineScreen: File upload successful, sending message",
                                                        )
                                                    }
                                                    // Send file message with metadata
                                                    appViewModel.sendFileMessage(
                                                        roomId = roomId,
                                                        mxcUrl = fileResult.mxcUrl,
                                                        filename = fileResult.filename,
                                                        size = fileResult.size,
                                                        mimeType = fileResult.mimeType,
                                                        caption = caption.takeIf { it.isNotBlank() },
                                                        threadRootEventId = replyThreadRootEventId,
                                                        replyToEventId = replyToEventId,
                                                        // A real reply, not a thread anchor.
                                                        isThreadFallback = false,
                                                    )
                                                } else {
                                                    Log.e(
                                                        "Andromuks",
                                                        "BubbleTimelineScreen: File upload failed after retries",
                                                    )
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload file after 3 attempts",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }

                                            mediaUriToUpload != null -> {
                                                // Upload image
                                                val uploadResult = performUpload("image") {
                                                    MediaUploadUtils.uploadMedia(
                                                        context = context,
                                                        uri = mediaUriToUpload,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        compressOriginal = compressOriginal,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        },
                                                    )
                                                }

                                                if (uploadResult != null) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "BubbleTimelineScreen: Image upload successful, sending message",
                                                        )
                                                    }
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
                                                        thumbnailSize = uploadResult.thumbnailSize,
                                                        threadRootEventId = replyThreadRootEventId,
                                                        replyToEventId = replyToEventId,
                                                        // A real reply, not a thread anchor.
                                                        isThreadFallback = false,
                                                    )
                                                } else {
                                                    Log.e(
                                                        "Andromuks",
                                                        "BubbleTimelineScreen: Image upload failed after retries",
                                                    )
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Failed to upload image after 3 attempts",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Andromuks", "BubbleTimelineScreen: Upload error", e)
                                        // Try to clean up upload state
                                        appViewModel.setUploadRetryCount(roomId, 0)
                                        android.widget.Toast.makeText(
                                            context,
                                            "Error uploading media: ${e.message}",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
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
                            },
                        )
                    }

                    if (showReactionsDialog && reactionsEventId != null) {
                        // messageReactions is not Compose state; reactionUpdateCounter is the
                        // repaint signal, so the dialog must key on it or it shows stale counts
                        // when a reaction lands while it is open.
                        val reactions = remember(reactionsEventId, appViewModel.reactionUpdateCounter) {
                            reactionsEventId?.let { appViewModel.messageReactions[it] } ?: emptyList()
                        }
                        net.vrkknn.andromuks.utils.ReactionDetailsDialog(
                            reactions = reactions,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onDismiss = { showReactionsDialog = false },
                            appViewModel = appViewModel,
                            roomId = roomId,
                        )
                    }

                    if (showBridgeDeliveryDialog && bridgeDeliveryEventId != null) {
                        val eventId = bridgeDeliveryEventId!!
                        val deliveryInfo =
                            appViewModel.messageBridgeDeliveryInfo[eventId] ?: net.vrkknn.andromuks.BridgeDeliveryInfo()
                        val deliveryStatus = appViewModel.messageBridgeSendStatus[eventId] ?: "sent"
                        val networkName = appViewModel.currentRoomState?.bridgeInfo?.displayName
                        net.vrkknn.andromuks.utils.BridgeDeliveryInfoDialog(
                            deliveryInfo = deliveryInfo,
                            status = deliveryStatus,
                            networkName = networkName,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onDismiss = { showBridgeDeliveryDialog = false },
                            appViewModel = appViewModel,
                            roomId = roomId,
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
    onRefreshClick: () -> Unit = {},
) {
    // Debug logging
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: roomState = $roomState")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: fallbackName = $fallbackName")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: homeserverUrl = $homeserverUrl")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BubbleRoomHeader: authToken = ${authToken.take(10)}...")
    val connectionState by SyncRepository.connectionState.collectAsState()
    Surface(
        modifier =
        Modifier.fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
                    isVisible = true, // Always visible for room header
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Room info (clickable for room info)
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onHeaderClick),
            ) {
                // Room name (prefer room state name, fallback to fallback name)
                val displayName = roomState?.name ?: fallbackName

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )

                // Room topic / encryption indicator (below display name)
                val roomTopic = roomState?.topic
                // Tri-state, mirroring RoomHeader in RoomTimelineScreen — see the note there.
                val isRoomEncrypted = roomState?.isEncrypted ?: roomId?.let { RoomStateStore.getParsed(it)?.isEncrypted }
                val iconSize = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.fontSize.toDp() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Icon(
                        imageVector = if (isRoomEncrypted == false) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        contentDescription = when (isRoomEncrypted) {
                            true -> "Encrypted room"
                            false -> "Unencrypted room"
                            null -> "Checking encryption"
                        },
                        modifier = Modifier.size(iconSize),
                        tint = when (isRoomEncrypted) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (roomTopic != null && roomTopic.isNotBlank()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = roomTopic,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimatedVisibility(
                    visible = !connectionState.isReady(),
                    enter = fadeIn(animationSpec = tween(scaledTweenMs(300))),
                    exit = fadeOut(animationSpec = tween(scaledTweenMs(300))),
                ) {
                    val offlinePulse = rememberInfiniteTransition(label = "offline_pulse")
                    val offlineAlpha by offlinePulse.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "offline_alpha",
                    )
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = "No server connection",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = offlineAlpha),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onOpenInApp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Launch,
                        contentDescription = "Open in app",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val bridgeInfo = roomState?.bridgeInfo
                if (bridgeInfo != null && bridgeInfo.hasRenderableIcon) {
                    BridgeNetworkBadge(
                        bridgeInfo = bridgeInfo,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onClick = onRefreshClick,
                    )
                } else {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh timeline",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Andromuks",
                            "BubbleRoomHeader: X button clicked - calling onMinimizeBubble",
                        )
                    }
                    onMinimizeBubble()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Minimize bubble",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
// With reverseLayout, new events are added at higher indices (older messages at top)
