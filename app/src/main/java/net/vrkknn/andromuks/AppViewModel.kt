package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.vrkknn.andromuks.SpaceItem
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.SpaceRoomParser
import net.vrkknn.andromuks.utils.ReceiptFunctions
import net.vrkknn.andromuks.utils.processReactionEvent
import okhttp3.WebSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.content.Context
import android.media.MediaPlayer
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.util.Collections
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.getUserAgent
import net.vrkknn.andromuks.utils.applyIncomingWebSocketMessageForViewModel
data class MemberProfile(
    val displayName: String?,
    val avatarUrl: String?
)

data class SharedMediaItem(
    val uri: Uri,
    val mimeType: String?
)

data class PendingSharePayload(
    val items: List<SharedMediaItem>,
    val text: String? = null
)

data class UserProfile(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?
)

data class MentionEntry(
    val roomId: String,
    val eventId: String
)

data class MentionEvent(
    val mentionEntry: MentionEntry,
    val event: TimelineEvent,
    val roomName: String? = null,
    val roomAvatarUrl: String? = null,
    val replyToEvent: TimelineEvent? = null
)

/**
 * Represents a single version of a message (original or edit)
 */
data class MessageVersion(
    val eventId: String,
    val event: TimelineEvent,
    val timestamp: Long,
    val isOriginal: Boolean = false
)

/**
 * Stores the complete edit history and state of a message
 */
data class VersionedMessage(
    val originalEventId: String,
    val originalEvent: TimelineEvent,
    val versions: List<MessageVersion>,  // Sorted by timestamp (newest first)
    val redactedBy: String? = null,
    val redactionEvent: TimelineEvent? = null
)

data class RoomListUiState(
    val currentUserProfile: UserProfile?,
    val currentUserId: String,
    val imageAuthToken: String,
    val isProcessingPendingItems: Boolean,
    val spacesLoaded: Boolean,
    val initialSyncComplete: Boolean,
    val roomListUpdateCounter: Int,
    val roomSummaryUpdateCounter: Int,
    val currentSpaceId: String?,
    val notificationActionInProgress: Boolean,
    val timestampUpdateCounter: Int,
    val pendingSyncCompleteCount: Int = 0,
    val processedSyncCompleteCount: Int = 0
)

/**
 * Result of WebSocket operations to handle connection issues gracefully
 */
enum class WebSocketResult {
    SUCCESS,
    NOT_CONNECTED,
    CONNECTION_ERROR
}

class AppViewModel : ViewModel() {
    // Track if app was opened from external app (like Contacts) - affects back navigation behavior
    private var _openedFromExternalApp by mutableStateOf(false)
    val openedFromExternalApp: Boolean
        get() = _openedFromExternalApp
    
    fun setOpenedFromExternalApp(value: Boolean) {
        _openedFromExternalApp = value
    }
    
    // Tracks which sender profiles have been processed per room to avoid duplicate fetches.
    // Used by RoomListScreen opportunistic profile loading.
    val processedSendersByRoom = mutableStateMapOf<String, MutableSet<String>>()

    companion object {
        // File name for user profile disk cache (used in SharedPreferences)
        private const val PROFILE_CACHE_FILE = "user_profiles_cache.json"
        
        // MEMORY MANAGEMENT: Constants for cache limits and cleanup
        private const val INITIAL_ROOM_LOAD_EVENTS = 100 // Events to load when opening a room
        internal const val MAX_MEMBER_CACHE_SIZE = 50000
        internal const val MAX_MESSAGE_VERSIONS_PER_EVENT = 50
        
        // PHASE 4: Counter for generating unique ViewModel IDs
        private var viewModelCounter = 0
        
        // PHASE 5.1: Constants for outgoing message queue (internal: [WebSocketCommandSender])
        internal const val MAX_QUEUE_SIZE = 800 // Maximum queue size (raised to cover bulk pagination hydrates)
        internal const val MAX_MESSAGE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        
        // Processed timeline state is now stored in RoomTimelineCache singleton (no size limit needed)
        
        // Initial paginate limit when opening a room to fetch events from server
        // Used when cache is empty or to fetch newer events when cache has data
        // Default: 50 events
        @JvmStatic
        var INITIAL_ROOM_PAGINATE_LIMIT = 50
        
        // FCM registration debounce window to prevent duplicate registrations
        internal const val FCM_REGISTRATION_DEBOUNCE_MS = 5000L // 5 seconds debounce window
        
        // Notification reply deduplication window to prevent duplicate sends
        private const val NOTIFICATION_REPLY_DEDUP_WINDOW_MS = 5000L // 5 seconds deduplication window
    }
    

    /**
     * Build a one-shot snapshot of the current section from in-memory data.
     * Room summaries are no longer persisted to DB - they're built in-memory from sync_complete.
     */
    suspend fun buildSectionSnapshot(): RoomSection {
        // Use in-memory data directly - no DB queries needed
        return getCurrentRoomSection()
    }

    // PHASE 4: Unique ID for this ViewModel instance (for WebSocket callback registration)
    internal val viewModelId: String = "AppViewModel_${viewModelCounter++}"
    
    init {
        SyncRepository.attachViewModel(viewModelId, this)
        viewModelScope.launch {
            SyncRepository.events.collect { event ->
                when (event) {
                    is SyncEvent.OfflineModeChanged -> {
                        if (event.isOffline) {
                            android.util.Log.w("Andromuks", "AppViewModel: Offline mode (SyncRepository): entering offline")
                            logActivity("Entering Offline Mode", null)
                            setOfflineMode(true)
                        } else {
                            android.util.Log.i("Andromuks", "AppViewModel: Offline mode (SyncRepository): online")
                            logActivity("Exiting Offline Mode", null)
                            setOfflineMode(false)
                            WebSocketService.resetReconnectionState()
                        }
                    }
                    is SyncEvent.ActivityLog -> {
                        logActivity(event.event, event.networkType)
                    }
                    is SyncEvent.ClearTimelineCachesRequested -> {
                        clearAllTimelineCaches()
                    }
                    is SyncEvent.RoomListSingletonReplicated -> {
                        if (viewModelId != event.processorId) {
                            // Secondary instances (bubbles) must reload state from replicated singleton caches
                            
                            // 1. Refresh room and space lists
                            populateRoomMapFromCache()
                            populateSpacesFromCache()
                            roomListUpdateCounter++
                            
                            // 2. Refresh read receipts and reactions
                            populateReadReceiptsFromCache()
                            populateMessageReactionsFromCache()
                            
                            // 3. IMPORTANT: Reload current room timeline if one is open.
                            // The singleton timeline cache was updated by the primary instance;
                            // we must sync our local snapshot to see new messages.
                            currentRoomId?.let { roomId ->
                                if (restoreFromLruCache(roomId)) {
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshed timeline for open room $roomId from replicated cache (vmId=$viewModelId)")
                                    timelineUpdateCounter++
                                }
                            }
                            
                            // 4. Profiles may have updated too
                            memberUpdateCounter++
                        }
                    }
                    is SyncEvent.IncomingWebSocketMessage -> {
                        try {
                            val json = JSONObject(event.jsonString)
                            applyIncomingWebSocketMessageForViewModel(json, this@AppViewModel, event.hint)
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "AppViewModel: IncomingWebSocketMessage apply failed", e)
                        }
                    }
                }
            }
        }
    }
    
    internal enum class InstanceRole {
        PRIMARY,
        BUBBLE,
        SECONDARY
    }

    internal var instanceRole: InstanceRole = InstanceRole.SECONDARY

    fun markAsPrimaryInstance() = viewModelLifecycleCoordinator.markAsPrimaryInstance()

    fun promoteToPrimaryIfNeeded(reason: String) =
        viewModelLifecycleCoordinator.promoteToPrimaryIfNeeded(reason)

    suspend fun awaitRoomDataReadiness(
        timeoutMs: Long = 15_000L,
        pollDelayMs: Long = 100L,
        requireInitComplete: Boolean = false,
        roomId: String? = null
    ): Boolean {
        android.util.Log.d("Andromuks", "🟣 awaitRoomDataReadiness: START - roomId=$roomId, timeoutMs=$timeoutMs, requireInitComplete=$requireInitComplete, currentRoomId=$currentRoomId")
        val startTime = System.currentTimeMillis()
        return withTimeoutOrNull(timeoutMs) {
            var pollCount = 0
            while (true) {
                pollCount++
                // REMOVED: profileReady check - profiles load in background, events render with fallback immediately
                // Events can render instantly with username/avatar fallback, profiles update when they arrive
                // REMOVED: spacesReady check - not needed, init_complete check suffices if websocket was not connected
                // If websocket was already connected, we don't need to check for anything, just attach and proceed
                val pendingReady = !isProcessingPendingItems
                val syncReady = initialSyncComplete
                val initReady = !requireInitComplete || initializationComplete
                
                // CRITICAL FIX: Also wait for timeline to finish loading if we're loading a specific room
                // This prevents the timeline from showing a spinner indefinitely when opened during sync processing
                val timelineReady = if (roomId != null && currentRoomId == roomId) {
                    // If we're loading this specific room, wait for loading to complete
                    // Timeline is ready if:
                    // 1. We're not loading (!isTimelineLoading) - either loaded or not started
                    // 2. OR we have events (timelineEvents.isNotEmpty()) - data is available even if still loading
                    // This ensures we don't wait forever if loading fails or is slow
                    !isTimelineLoading || timelineEvents.isNotEmpty()
                } else {
                    // Not loading a specific room, or room doesn't match - don't wait for timeline
                    true
                }
                
                if (pollCount % 10 == 0 || (!pendingReady || !syncReady || !initReady || !timelineReady)) {
                    // Log every 10 polls or when not ready
                    android.util.Log.d("Andromuks", "🟣 awaitRoomDataReadiness: Polling - roomId=$roomId, pollCount=$pollCount, pendingReady=$pendingReady, syncReady=$syncReady, initReady=$initReady, timelineReady=$timelineReady, isTimelineLoading=$isTimelineLoading, timelineEvents.size=${timelineEvents.size}, currentRoomId=$currentRoomId")
                }
                
                if (pendingReady && syncReady && initReady && timelineReady) {
                    val elapsed = System.currentTimeMillis() - startTime
                    android.util.Log.d("Andromuks", "🟣 awaitRoomDataReadiness: READY - roomId=$roomId, elapsed=${elapsed}ms, pollCount=$pollCount")
                    break
                }
                delay(pollDelayMs)
            }
            true
        } ?: run {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.w("Andromuks", "🟣 awaitRoomDataReadiness: TIMEOUT - roomId=$roomId, elapsed=${elapsed}ms, timeoutMs=$timeoutMs, isProcessingPendingItems=$isProcessingPendingItems, initialSyncComplete=$initialSyncComplete, isTimelineLoading=$isTimelineLoading, timelineEvents.size=${timelineEvents.size}, currentRoomId=$currentRoomId")
            false
        }
    }
    
    fun markAsBubbleInstance() = viewModelLifecycleCoordinator.markAsBubbleInstance()

    /**
     * STEP 2.3: Called by WebSocketService when this ViewModel is promoted to primary
     * This happens automatically when the original primary ViewModel is destroyed
     *
     * When promoted, the ViewModel:
     * 1. Registers as primary with the service
     * 2. Registers primary callbacks with service
     * 3. Takes over WebSocket management (attaches to existing WebSocket or ensures service is running)
     */
    fun onPromotedToPrimary() = viewModelLifecycleCoordinator.onPromotedToPrimary()

    /**
     * Check if this AppViewModel instance is the primary instance
     * Only the primary instance should create new WebSocket connections
     */
    fun isPrimaryInstance() = viewModelLifecycleCoordinator.isPrimaryInstance()

    var isLoading by mutableStateOf(false)
    var homeserverUrl by mutableStateOf("")
        private set
    var authToken by mutableStateOf("")
        private set
    var realMatrixHomeserverUrl by mutableStateOf("")
    var wellKnownElementCallBaseUrl by mutableStateOf("")
        internal set
    internal var appContext: Context? = null
    
    // Timeline cache for instant room opening (now singleton)
    // No need to instantiate - using object RoomTimelineCache

    // Auth/client state
    var currentUserId by mutableStateOf("")
        private set
    var deviceId by mutableStateOf("")
    internal var callActiveInternal by mutableStateOf(false)
    internal var callReadyForPipInternal by mutableStateOf(false)
    var imageAuthToken by mutableStateOf("")
        private set
    var currentUserProfile by mutableStateOf<UserProfile?>(null)
        internal set
    
    // State to track if pending items are being processed (prevents showing stale data in RoomListScreen)
    var isProcessingPendingItems by mutableStateOf(false)
        private set

    private var activeNotificationActionCount = 0
    var notificationActionInProgress by mutableStateOf(false)
        private set
    
    // Settings
    // BATTERY OPTIMIZATION: Compression disabled by default
    // Compression requires CPU-intensive decompression on every message (4-8 Hz)
    // This causes significant battery drain even when messages are buffered
    // Users can enable it in settings if they prefer bandwidth savings over battery
    var enableCompression by mutableStateOf(false)
        internal set
    var enterKeySendsMessage by mutableStateOf(true) // true = Enter sends, Shift+Enter newline; false = Enter newline, Shift+Enter sends
        internal set
    var loadThumbnailsIfAvailable by mutableStateOf(true)
        internal set
    var renderThumbnailsAlways by mutableStateOf(true)
        internal set
    // Room list bottom bar layout: false = compact (4 tabs), true = full (6 tabs: +Favs, +Bridges)
    var showAllRoomListTabs by mutableStateOf(false)
        internal set
    // Room timeline read receipts layout: false = inline next to bubble, true = moved to opposite screen edge
    var moveReadReceiptsToEdge by mutableStateOf(false)
        internal set
    // Trim long display names in timeline: if true, names longer than 40 chars are trimmed with "..."
    var trimLongDisplayNames by mutableStateOf(true)
        internal set
    var elementCallBaseUrl by mutableStateOf("")
        internal set

    // ── Background purge settings (exposed for SettingsScreen) ──────────────
    var backgroundPurgeIntervalMinutes by mutableStateOf(
        (SyncBatchProcessor.DEFAULT_BATCH_INTERVAL_MS / 60_000L).toInt()
    )
        internal set
    var backgroundPurgeMessageThreshold by mutableStateOf(
        SyncBatchProcessor.DEFAULT_MAX_BATCH_SIZE
    )
        internal set

    var pendingShare by mutableStateOf<PendingSharePayload?>(null)
        private set
    var pendingShareNavigationRequested by mutableStateOf(false)
        private set
    var pendingShareUpdateCounter by mutableStateOf(0)
        private set
    private var pendingShareTargetRoomId: String? = null

    fun setPendingShare(
        items: List<SharedMediaItem>,
        text: String?,
        autoSelectRoomId: String? = null
    ) {
        if (items.isEmpty() && text.isNullOrBlank()) {
            android.util.Log.w("Andromuks", "AppViewModel: Ignoring pending share with no content")
            return
        }
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Pending share set with ${items.size} items, hasText=${!text.isNullOrBlank()}, autoSelectRoom=$autoSelectRoomId"
        )
        pendingShare = PendingSharePayload(items, text)
        pendingShareTargetRoomId = null
        pendingShareNavigationRequested = autoSelectRoomId == null
        pendingShareUpdateCounter++
        if (!autoSelectRoomId.isNullOrBlank()) {
            pendingShareTargetRoomId = autoSelectRoomId
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Pending share auto-selected room: $autoSelectRoomId"
            )
        }
    }

    fun reportPersonShortcutUsed(userId: String) {
        if (userId.isBlank()) return
        personsApi?.reportShortcutUsed(userId)
    }

    fun clearPendingShare() {
        pendingShare = null
        pendingShareTargetRoomId = null
        pendingShareNavigationRequested = false
        pendingShareUpdateCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared pending share state")
    }

    fun markPendingShareNavigationHandled() {
        if (pendingShareNavigationRequested) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Pending share navigation marked as handled")
        }
        pendingShareNavigationRequested = false
    }

    fun selectPendingShareRoom(roomId: String) {
        pendingShareTargetRoomId = roomId
        pendingShareNavigationRequested = false
        pendingShareUpdateCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Pending share target room selected: $roomId")
    }

    fun consumePendingShareForRoom(roomId: String): PendingSharePayload? {
        val share = pendingShare
        return if (share != null && pendingShareTargetRoomId == roomId) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Consuming pending share for room $roomId")
            pendingShare = null
            pendingShareTargetRoomId = null
            pendingShareUpdateCounter++
            share
        } else {
            null
        }
    }


    // List of spaces, each with their rooms
    var spaceList by mutableStateOf(listOf<SpaceItem>())
        internal set
    
    // All rooms (for filtering into sections)
    var allRooms by mutableStateOf(listOf<RoomItem>())
        internal set
    
    // All spaces (for Spaces section)
    var allSpaces by mutableStateOf(listOf<SpaceItem>())
        internal set
    
    // Track known space room IDs (top-level and nested) so we can filter them from room lists.
    internal val knownSpaceIds = mutableSetOf<String>()
    
    // PERFORMANCE: Cached room sections to avoid expensive filtering on every recomposition
    internal var cachedDirectChatRooms by mutableStateOf<List<RoomItem>>(emptyList())
        internal set

    internal var cachedUnreadRooms by mutableStateOf<List<RoomItem>>(emptyList())
        internal set

    internal var cachedFavouriteRooms by mutableStateOf<List<RoomItem>>(emptyList())
        internal set

    // PERFORMANCE: Pre-computed badge counts (always computed for immediate tab bar display)
    internal var cachedDirectChatsUnreadCount by mutableStateOf(0)
        internal set
    internal var cachedDirectChatsHasHighlights by mutableStateOf(false)
        internal set
    internal var cachedUnreadCount by mutableStateOf(0)
        internal set
    internal var cachedFavouritesUnreadCount by mutableStateOf(0)
        internal set
    internal var cachedFavouritesHasHighlights by mutableStateOf(false)
        internal set
    
    // PERFORMANCE: Track which sections have been loaded (for lazy loading)
    internal val loadedSections = mutableSetOf<RoomSectionType>()
    
    // PERFORMANCE: Cheap monotonic version counter for cache invalidation.
    // Bumped by invalidateRoomSectionCache() whenever room data actually changes
    // (performRoomReorder, setSpaces, registerSpaceIds, etc.).
    // updateCachedRoomSections() compares against its own snapshot to skip work.
    internal var roomDataVersion: Long = 0L
    internal var lastCachedVersion: Long = -1L

    /**
     * Invalidate room section cache when allRooms data changes.
     * Cheap O(1) — just bumps a counter.
     */
    internal fun invalidateRoomSectionCache() = roomListUiCoordinator.invalidateRoomSectionCache()
    
    /**
     * Returns the set of known space room IDs.
     * Uses allSpaces when available, otherwise falls back to spaceList.
     */
    /**
     * Registers newly discovered space IDs (top-level or nested) for filtering.
     */
    fun registerSpaceIds(spaceIds: Collection<String>) = syncRoomsCoordinator.registerSpaceIds(spaceIds)
    
    // Current selected section
    var selectedSection by mutableStateOf(RoomSectionType.HOME)
        private set
    
    // Space navigation state
    var currentSpaceId by mutableStateOf<String?>(null)
        internal set
    
    // Bridge navigation state
    var currentBridgeId by mutableStateOf<String?>(null)
        private set
    
    // Store space edges data for later processing
    internal var storedSpaceEdges: JSONObject? = null
    
    // Room state data
    var currentRoomState by mutableStateOf<RoomState?>(null)
        private set
    
    // Typing indicators per room (roomId -> list of typing user IDs)
    internal val typingUsersMap = mutableMapOf<String, List<String>>()
    var typingUsers by mutableStateOf(listOf<String>())
        internal set
    
    
    // Message reactions: eventId -> list of reactions
    // Now using singleton MessageReactionsCache - synced for UI reactivity
    private var _messageReactions by mutableStateOf(mapOf<String, List<MessageReaction>>())
    var messageReactions: Map<String, List<MessageReaction>>
        get() = MessageReactionsCache.getAllReactions().also { 
            // Sync state with cache for UI reactivity
            if (_messageReactions != it) {
                _messageReactions = it
            }
        }
        internal set(value) {
            MessageReactionsCache.setAll(value)
            _messageReactions = value
        }
    
    // Track processed reaction events to prevent duplicate processing
    internal val processedReactions = mutableSetOf<String>()
    
    // Track pending message sends for send button animation
    var pendingSendCount by mutableStateOf(0)
        internal set
    
    // Flag to force the next connection to be a resume (true) or cold start (false). 
    // Null means follow the default logic (resume if cache exists).
    private var reconnectWithResume: Boolean? = null

    // Track uploads in progress per room (roomId -> count)
    internal val uploadInProgressCount = mutableStateMapOf<String, Int>()
    // Track upload types per room (roomId -> set of upload types: "image", "video", "audio", "file")
    internal val uploadTypesPerRoom = mutableStateMapOf<String, MutableSet<String>>()
    // Track upload retry count per room (roomId -> count)
    internal val uploadRetryCounts = mutableStateMapOf<String, Int>()
    // Track upload progress per room (roomId -> Map<String, Float>) where key is "original" or "thumbnail"
    internal val uploadProgressPerRoom = mutableStateMapOf<String, Map<String, Float>>()
    
    fun hasUploadInProgress(roomId: String): Boolean = uploadCoordinator.hasUploadInProgress(roomId)

    fun getUploadType(roomId: String): String = uploadCoordinator.getUploadType(roomId)

    fun getUploadRetryCount(roomId: String): Int = uploadCoordinator.getUploadRetryCount(roomId)

    fun setUploadRetryCount(roomId: String, count: Int) = uploadCoordinator.setUploadRetryCount(roomId, count)

    fun setUploadProgress(roomId: String, key: String, progress: Float) =
        uploadCoordinator.setUploadProgress(roomId, key, progress)

    fun getUploadProgress(roomId: String): Map<String, Float> = uploadCoordinator.getUploadProgress(roomId)

    fun beginUpload(roomId: String, uploadType: String = "image") = uploadCoordinator.beginUpload(roomId, uploadType)

    fun endUpload(roomId: String, uploadType: String = "image") = uploadCoordinator.endUpload(roomId, uploadType)
    
    // Recent emojis for reactions (stored as list of strings for UI)
    // Now using singleton RecentEmojisCache - synced for UI reactivity
    private var _recentEmojis by mutableStateOf(listOf<String>())
    var recentEmojis: List<String>
        get() = RecentEmojisCache.getAll().also {
            // Sync state with cache for UI reactivity
            if (_recentEmojis != it) {
                _recentEmojis = it
            }
        }
        internal set(value) {
            RecentEmojisCache.set(value)
            _recentEmojis = value
        }
    
    // Internal storage for emoji frequencies: list of [emoji, count] pairs
    internal var recentEmojiFrequencies = mutableListOf<Pair<String, Int>>()
    // Track whether we've loaded the full recent emoji list from the server
    // This prevents sending incomplete updates that would reset the server's full list
    internal var hasLoadedRecentEmojisFromServer = false
    
    // Mentions state - list of mention events with room info
    var mentionEvents by mutableStateOf<List<MentionEvent>>(emptyList())
        private set
    var isMentionsLoading by mutableStateOf(false)
        private set
    
    // Custom emoji packs from im.ponies.emote_rooms
    data class CustomEmoji(
        val name: String,
        val mxcUrl: String,
        val info: org.json.JSONObject?
    )
    
    data class Sticker(
        val name: String,
        val mxcUrl: String,
        val body: String?,
        val info: org.json.JSONObject?
    )
    
    data class EmojiPack(
        val packName: String,
        val displayName: String,
        val roomId: String,
        val emojis: List<CustomEmoji>
    )
    
    data class StickerPack(
        val packName: String,
        val displayName: String,
        val roomId: String,
        val stickers: List<Sticker>
    )
    
    // Custom emoji packs - now using singleton EmojiPacksCache
    var customEmojiPacks: List<EmojiPack>
        get() = EmojiPacksCache.getAll()
        private set(value) = EmojiPacksCache.setAll(value)
    
    // Sticker packs - now using singleton StickerPacksCache
    var stickerPacks: List<StickerPack>
        get() = StickerPacksCache.getAll()
        private set(value) = StickerPacksCache.setAll(value)
    
    // Track pending emoji pack requests: requestId -> (roomId, packName)
    private val emojiPackRequests = mutableMapOf<Int, Pair<String, String>>()
    
    // Queue for emoji pack requests that were deferred because WebSocket wasn't ready
    private val deferredEmojiPackRequests = mutableListOf<Pair<String, String>>() // (roomId, packName)
    
    // Cache for DM room IDs from m.direct account data
    internal var directMessageRoomIds by mutableStateOf(setOf<String>())
        internal set
    
    // Ignored users list from m.ignored_user_list account data
    internal var ignoredUsers by mutableStateOf(setOf<String>())
        internal set

    // Cache mapping of userId -> set of direct room IDs (from m.direct)
    internal var directMessageUserMap: Map<String, Set<String>> = emptyMap()
        internal set
    
    
    // Room state storage for future use
    private var roomStatesCache by mutableStateOf(mapOf<String, JSONArray>())
        private set
    
    /**
     * Check if a room is a direct message using m.direct account data
     * This is a secondary method to detect DMs more reliably
     */
    fun isDirectMessageFromAccountData(roomId: String): Boolean {
        return directMessageRoomIds.contains(roomId)
    }

    // Force recomposition counter - DEPRECATED: Use specific counters below instead
    var updateCounter by mutableStateOf(0)
        internal set
    
    // Granular update counters to reduce unnecessary recompositions
    var roomListUpdateCounter by mutableStateOf(0)
        internal set
    
    var timelineUpdateCounter by mutableStateOf(0)
        internal set
    
    var reactionUpdateCounter by mutableStateOf(0)
        internal set
    
    var memberUpdateCounter by mutableStateOf(0)
        internal set
    
    var roomStateUpdateCounter by mutableStateOf(0)
        private set
    
    // Room summary update counter - triggers RoomListScreen to refresh message previews/senders
    var roomSummaryUpdateCounter by mutableStateOf(0)
        internal set
    
    // SYNC OPTIMIZATION: Batched update mechanism
    private var pendingUIUpdates = mutableSetOf<String>() // Track which UI sections need updates
    private var batchUpdateJob: Job? = null // Job for batching UI updates
    
    // PERFORMANCE: Debounced room reordering to prevent frustrating "room jumping"
    internal var lastRoomReorderTime = 0L
    internal var roomReorderJob: Job? = null
    internal val ROOM_REORDER_DEBOUNCE_MS = 30000L // 30 seconds debounce - reduces visual jumping
    private var forceSortNextReorder = false // Flag to force immediate sort on next reorder
    
    // SYNC OPTIMIZATION: Diff-based update tracking
    internal var lastRoomStateHash: String = ""
    private var lastTimelineStateHash: String = ""
    internal var lastMemberStateHash: String = ""
    
    // SYNC OPTIMIZATION: Selective update flags
    internal var needsRoomListUpdate = false
    private var needsTimelineUpdate = false
    internal var needsMemberUpdate = false
    private var needsReactionUpdate = false
    
    // NAVIGATION PERFORMANCE: Prefetch and caching system
    private val prefetchedRooms = mutableSetOf<String>() // Track which rooms have been prefetched
    internal val navigationCache = mutableMapOf<String, RoomNavigationState>() // Cache room navigation state
    private var lastRoomListScrollPosition = 0 // Track scroll position for prefetching
    
    // Read receipts update counter - separate from main updateCounter to reduce unnecessary UI updates
    var readReceiptsUpdateCounter by mutableStateOf(0)
        internal set
    
    // Timestamp update counter for dynamic time displays
    var timestampUpdateCounter by mutableStateOf(0)
        private set
    
    
    // FCM notification manager
    internal var fcmNotificationManager: FCMNotificationManager? = null

    // Conversations API for shortcuts and enhanced notifications
    internal var conversationsApi: ConversationsApi? = null
    
    // Persons API for People/Share surfaces
    internal var personsApi: PersonsApi? = null
    
    // Web client push integration
    internal var webClientPushIntegration: WebClientPushIntegration? = null
    
    
    // Notification action tracking
    internal data class PendingNotificationAction(
        val type: String, // "send_message" or "mark_read"
        val roomId: String,
        val text: String? = null,
        val eventId: String? = null,
        val requestId: Int? = null,
        val onComplete: (() -> Unit)? = null
    )
    
    internal val pendingNotificationActions = mutableListOf<PendingNotificationAction>()
    
    // FIFO buffer for notification replies - allows duplicates, processes in order
    // Messages are added by notification replies and removed when sent to WebSocket
    class PendingNotificationMessage(
        val roomId: String,
        val text: String,
        val timestamp: Long,
        val onComplete: (() -> Unit)? = null
    )
    
    // FIFO queue: oldest messages first, removed when sent to WebSocket
    internal val pendingNotificationMessages = mutableListOf<PendingNotificationMessage>()
    internal val pendingNotificationMessagesLock = Any() // Lock for thread safety
    
    internal val notificationActionCompletionCallbacks = mutableMapOf<Int, () -> Unit>()
    internal fun beginNotificationAction() {
        activeNotificationActionCount++
        if (!notificationActionInProgress) {
            notificationActionInProgress = true
        }
    }
    
    internal fun endNotificationAction() {
        if (activeNotificationActionCount > 0) {
            activeNotificationActionCount--
        }
        if (activeNotificationActionCount == 0) {
            notificationActionInProgress = false
        }
    }
    

    // WebSocket pending operations for retry when connection is restored
    // PHASE 5.1: Enhanced PendingWebSocketOperation with persistence support
    internal data class PendingWebSocketOperation(
        val type: String, // "sendMessage", "sendReply", "markRoomAsRead", etc.
        val data: Map<String, Any>,
        val retryCount: Int = 0,
        val messageId: String = java.util.UUID.randomUUID().toString(), // PHASE 5.1: Unique identifier
        val timestamp: Long = System.currentTimeMillis(), // PHASE 5.1: When message was queued
        val acknowledged: Boolean = false, // PHASE 5.1: Whether response was received
        val acknowledgmentTimeout: Long = System.currentTimeMillis() + 30000L // PHASE 5.1: When to consider message failed (30s default)
    ) {
        // PHASE 5.1: Helper to convert to JSON-serializable format
        fun toJsonMap(): Map<String, Any> {
            return mapOf(
                "type" to type,
                "data" to data,
                "retryCount" to retryCount,
                "messageId" to messageId,
                "timestamp" to timestamp,
                "acknowledged" to acknowledged,
                "acknowledgmentTimeout" to acknowledgmentTimeout
            )
        }
        
        companion object {
            // PHASE 5.1: Helper to create from JSON-serializable format
            @Suppress("UNCHECKED_CAST")
            fun fromJsonMap(jsonMap: Map<String, Any>): PendingWebSocketOperation? {
                return try {
                    PendingWebSocketOperation(
                        type = jsonMap["type"] as? String ?: return null,
                        data = jsonMap["data"] as? Map<String, Any> ?: return null,
                        retryCount = (jsonMap["retryCount"] as? Number)?.toInt() ?: 0,
                        messageId = jsonMap["messageId"] as? String ?: java.util.UUID.randomUUID().toString(),
                        timestamp = (jsonMap["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                        acknowledged = jsonMap["acknowledged"] as? Boolean ?: false,
                        acknowledgmentTimeout = (jsonMap["acknowledgmentTimeout"] as? Number)?.toLong() ?: System.currentTimeMillis() + 30000L
                    )
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to parse PendingWebSocketOperation from JSON", e)
                    null
                }
            }
        }
    }
    
    // NAVIGATION PERFORMANCE: Room navigation state cache
    data class RoomNavigationState(
        val roomId: String,
        val essentialDataLoaded: Boolean = false,
        val memberDataLoaded: Boolean = false,
        val timelineDataLoaded: Boolean = false,
        val lastPrefetchTime: Long = System.currentTimeMillis()
    )
    
    internal val pendingWebSocketOperations = mutableListOf<PendingWebSocketOperation>()
    internal val pendingOperationsLock = Any() // Lock for synchronizing access to pendingWebSocketOperations
    internal val maxRetryAttempts = 3
    
    // Track last reconnection time for stabilization period
    internal var lastReconnectionTime = 0L
    
    // INFINITE LOOP FIX: Track restart state to prevent rapid-fire restarts
    private var isRestarting = false
    private var lastRestartTime = 0L
    private val RESTART_COOLDOWN_MS = 5000L // 5 seconds minimum between restarts

    var spacesLoaded by mutableStateOf(false)


    internal set
    
    // Track if init_complete has been received (distinguishes initialization from real-time updates)
    internal var initializationComplete = false
    
    // CRITICAL FIX: Track initial sync phase and queue sync_complete messages received before init_complete
    // This ensures we process all initial room data before showing UI
    internal var initialSyncPhase = false // Set to false when WebSocket connects, true when init_complete arrives
    internal val initialSyncCompleteQueue = mutableListOf<JSONObject>() // Queue for sync_complete messages before init_complete
    private val initialSyncProcessingMutex = Mutex() // Use Mutex for coroutine-safe locking
    var initialSyncProcessingComplete by mutableStateOf(false) // Set to true when all initial sync_complete messages are processed
        internal set
    var initialSyncComplete by mutableStateOf(false) // Public state for UI to observe
        internal set
    
    // CRITICAL FIX: Serialize sync_complete processing to prevent race conditions
    // Multiple sync_complete messages can arrive rapidly, and concurrent processing can cause messages to be missed
    private val syncCompleteProcessingMutex = Mutex() // Mutex to serialize sync_complete processing after init_complete
    
    // Track if shortcuts have been refreshed on startup (only refresh once per app session)
    private var shortcutsRefreshedOnStartup = false
    
    // Track sync_complete progress for UI display
    var pendingSyncCompleteCount by mutableStateOf(0)
        internal set
    var processedSyncCompleteCount by mutableStateOf(0)
        internal set
    
    // CRITICAL FIX: Track loading of all room states (for bridge badges) after init_complete
    // This must complete before allowing other commands and before navigating to RoomListScreen
    internal var allRoomStatesRequested = false
    internal var allRoomStatesLoaded = false
    private val pendingRoomStateResponses = mutableSetOf<String>() // Track which rooms we're waiting for
    private var totalRoomStateRequests = 0
    private var completedRoomStateRequests = 0
    
    // CRITICAL FIX: Block sending commands to backend until init_complete arrives and all initial sync_complete messages are processed
    // This prevents get_room_state commands from being sent before rooms are populated from sync_complete
    // Only applies on initial connection (not reconnections with last_received_event)
    internal var canSendCommandsToBackend = false
    internal val pendingCommandsQueue = mutableListOf<Triple<String, Int, Map<String, Any>>>() // Queue for commands blocked before init_complete

    // --- Coordinators (all lazy). [syncRoomsCoordinator] must be declared before [syncBatchProcessor] (batch handler calls into it). ---
    /** Outgoing WS command pipeline — see [WebSocketCommandSender]. */
    private val webSocketCommands by lazy { WebSocketCommandSender(this) }

    /** Reaction orchestration — see [ReactionCoordinator]. */
    internal val reactionCoordinator by lazy { ReactionCoordinator(this) }

    /** Outgoing messages (text, media, typing, notification FIFO) — see [MessageSendCoordinator]. */
    internal val messageSendCoordinator by lazy { MessageSendCoordinator(this) }

    /** Edit chains, merged bubbles, [MessageVersionsCache] — see [EditVersionCoordinator]. */
    internal val editVersionCoordinator by lazy { EditVersionCoordinator(this) }

    /** Timeline cache, LRU, paginate / prefetch handling — see [TimelineCacheCoordinator]. */
    private val timelineCacheCoordinator by lazy { TimelineCacheCoordinator(this) }

    /** Initial sync, room map, spaces, account_data from sync_complete — see [SyncRoomsCoordinator]. */
    private val syncRoomsCoordinator by lazy { SyncRoomsCoordinator(this) }

    // BATTERY OPTIMIZATION: Batch sync_complete messages when app is backgrounded
    // Reduces CPU wake-ups from 480/min (8 Hz) to 6/min (every 10s) = 98.75% reduction
    // Must be declared after [syncRoomsCoordinator]: [processSyncImmediately] calls into it.
    internal val syncBatchProcessor = SyncBatchProcessor(
        scope = viewModelScope,
        // 1. Lambda now accepts Boolean and returns SyncUpdateResult?
        processSyncImmediately = { syncJson, requestId, runId, applyRoomListNow ->
            syncRoomsCoordinator.processSyncCompleteAtomic(
                syncJson, requestId, runId,
                applyRoomListNow = applyRoomListNow,
                onComplete = null
            )
        },
        // 2. New lambda: called once after flush with the merged result
        onBatchRoomListApply = { mergedResult ->
            syncRoomsCoordinator.applyBatchedRoomListResult(mergedResult)
        },
        // 3. onBatchComplete unchanged
        onBatchComplete = {
            // BATTERY OPTIMIZATION: When background batch completes, trigger single UI update
            // This avoids unnecessary state updates during background processing (UI won't recompose anyway)
            // When foregrounded, updates happen per message for real-time responsiveness
            // Note: This callback is only called when !isAppVisible, so no need to check again
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: onBatchComplete callback STARTED (background batch)")
            try {
                withContext(Dispatchers.Main) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: onBatchComplete - on Main thread, needsRoomListUpdate=$needsRoomListUpdate")
                    if (needsRoomListUpdate) {
                        val oldCounter = roomListUpdateCounter
                        roomListUpdateCounter++
                        needsRoomListUpdate = false
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background batch complete - roomListUpdateCounter: $oldCounter -> $roomListUpdateCounter (battery optimization)")
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background batch complete - skipping roomListUpdateCounter (needsRoomListUpdate=false)")
                    }
                    // Always increment summary counter after batch (rooms may have had events)
                    val oldSummaryCounter = roomSummaryUpdateCounter
                    roomSummaryUpdateCounter++
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background batch complete - roomSummaryUpdateCounter: $oldSummaryCounter -> $roomSummaryUpdateCounter (battery optimization)")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error in onBatchComplete callback: ${e.message}", e)
            }
        }
    )

    /** ViewModel lifecycle: primary/visibility/onCleared — see [ViewModelLifecycleCoordinator]. */
    private val viewModelLifecycleCoordinator by lazy { ViewModelLifecycleCoordinator(this) }

    /** Invites / join / leave — see [RoomInvitesCoordinator]. */
    private val roomInvitesCoordinator by lazy { RoomInvitesCoordinator(this) }

    /** Typing + read receipts + mark read — see [ReadReceiptsTypingCoordinator]. */
    internal val readReceiptsTypingCoordinator by lazy { ReadReceiptsTypingCoordinator(this) }

    /** FCM / push registration — see [FcmPushCoordinator]. */
    private val fcmPushCoordinator by lazy { FcmPushCoordinator(this) }

    /** Room list sections, badges, DM helpers, debounced reorder — see [RoomListUiCoordinator]. */
    private val roomListUiCoordinator by lazy { RoomListUiCoordinator(this) }

    /** Pending/direct nav, highlights, cache-first room open — see [NavigationCoordinator]. */
    private val navigationCoordinator by lazy { NavigationCoordinator(this) }

    /** Member maps, profile storage, full member list responses — see [MemberProfilesCoordinator]. */
    private val memberProfilesCoordinator by lazy { MemberProfilesCoordinator(this) }

    /** Activity log + cache stats — see [DiagnosticsCoordinator]. */
    internal val diagnosticsCoordinator by lazy { DiagnosticsCoordinator(this) }

    /** Prefs, pending WS queue, save/load state — see [PersistenceCoordinator]. */
    private val persistenceCoordinator by lazy { PersistenceCoordinator(this) }

    /** Upload progress — see [UploadCoordinator]. */
    private val uploadCoordinator by lazy { UploadCoordinator(this) }

    /** Account data (m.direct, tags, ignore, recent emojis) — see [AccountDataCoordinator]. */
    internal val accountDataCoordinator by lazy { AccountDataCoordinator(this) }

    /** Slash commands — see [SlashCommandsCoordinator]. */
    private val slashCommandsCoordinator by lazy { SlashCommandsCoordinator(this) }

    /** UI prefs toggles — see [SettingsCoordinator]. */
    private val settingsCoordinator by lazy { SettingsCoordinator(this) }

    /** `to_device` normalization + widget bridge — see [ToDeviceCoordinator]. */
    private val toDeviceCoordinator by lazy { ToDeviceCoordinator(this) }

    /** Encryption / device info WS commands — see [UserEncryptionCoordinator]. */
    private val userEncryptionCoordinator by lazy { UserEncryptionCoordinator(this) }

    /** Element Call + widget commands — see [CallsWidgetsCoordinator]. */
    private val callsWidgetsCoordinator by lazy { CallsWidgetsCoordinator(this) }

    // CRASH FIX: Expose batch processing state to UI to prevent animations during flush
    val isProcessingSyncBatch = syncBatchProcessor.isProcessingBatch
    val processingBatchSize = syncBatchProcessor.processingBatchSize
    val processedInBatch = syncBatchProcessor.processedInBatch  
    // CRITICAL FIX: Expose flag to bypass timeline rebuilds during batch processing
    private val shouldSkipTimelineRebuild = syncBatchProcessor.shouldSkipTimelineRebuild
    
    // Startup progress messages for loading screen (last 10 messages, newest on top)
    private val _startupProgressMessages = mutableStateListOf<String>()
    val startupProgressMessages: List<String> get() = _startupProgressMessages
    
    // Track if startup is complete (ready to show room list)
    var isStartupComplete by mutableStateOf(false)
        private set
    
    /**
     * Add a progress message to the startup loading screen
     * Messages are added to the front (newest on top), keeping only the last 10
     */
    fun addStartupProgressMessage(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss:SSS", java.util.Locale.US).format(java.util.Date())
        val timestampedMessage = "[$timestamp] $message"
        synchronized(_startupProgressMessages) {
            _startupProgressMessages.add(0, timestampedMessage) // Add to front
            if (_startupProgressMessages.size > 10) {
                _startupProgressMessages.removeAt(_startupProgressMessages.size - 1) // Remove oldest
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟦 Startup Progress: $timestampedMessage")
    }
    
    /**
     * Clear all startup progress messages (e.g., on app restart)
     */
    fun clearStartupProgressMessages() {
        synchronized(_startupProgressMessages) {
            _startupProgressMessages.clear()
        }
    }
    
    /**
     * Check if startup is complete and room list is ready to be displayed
     * Startup is complete when:
     * 1. init_complete was received (initializationComplete = true)
     * 2. All initial sync_complete messages have been processed (initialSyncComplete = true)
     * 3. Spaces are loaded (spacesLoaded = true)
     * Note: Bridge avatars are loaded lazily in background, so we don't wait for them
     */
    fun checkStartupComplete() {
        val wasComplete = isStartupComplete
        // CRITICAL FIX: Check that ALL initial sync_complete messages are processed, not just queued
        // Also ensure profile is loaded (or at least requested) before showing room list
        // EDGE CASE: Allow zero rooms (new account) - only require rooms if we haven't received any sync_complete yet
        // If initialSyncProcessingComplete is true, we've processed all messages, so even 0 rooms is valid
        val hasRoomsOrProcessingComplete = roomMap.isNotEmpty() || initialSyncProcessingComplete
        
        val nowComplete = initializationComplete && 
                         initialSyncComplete && 
                         initialSyncProcessingComplete && // All queued messages must be processed
                         spacesLoaded &&
                         hasRoomsOrProcessingComplete && // Have rooms OR all processing is complete (handles zero rooms case)
                         (currentUserProfile != null || currentUserId.isBlank()) && // Profile loaded OR not logged in
                         allRoomStatesLoaded // CRITICAL: Wait for all room states to load (for bridge badges)
        
        // CRITICAL FIX: Always log when startup is blocked (not just on state change)
        // This helps debug intermittent race conditions
        if (BuildConfig.DEBUG) {
            if (nowComplete != wasComplete) {
                android.util.Log.d("Andromuks", "🟦 checkStartupComplete: nowComplete=$nowComplete (init=$initializationComplete, sync=$initialSyncComplete, processing=$initialSyncProcessingComplete, spaces=$spacesLoaded, rooms=${roomMap.size}, profile=${currentUserProfile != null}, allRoomStates=$allRoomStatesLoaded)")
            }
            if (!nowComplete) {
                // Log which condition is blocking startup (always log, not just on state change)
                val missing = mutableListOf<String>()
                if (!initializationComplete) missing.add("initializationComplete")
                if (!initialSyncComplete) missing.add("initialSyncComplete")
                if (!initialSyncProcessingComplete) missing.add("initialSyncProcessingComplete")
                if (!spacesLoaded) missing.add("spacesLoaded")
                if (!hasRoomsOrProcessingComplete) missing.add("rooms/processing")
                if (currentUserProfile == null && currentUserId.isNotBlank()) missing.add("profile")
                if (!allRoomStatesLoaded) missing.add("allRoomStatesLoaded")
                if (missing.isNotEmpty()) {
                    android.util.Log.d("Andromuks", "🟦 checkStartupComplete: BLOCKED - missing: ${missing.joinToString(", ")}")
                }
            }
        }
        
        if (nowComplete && !wasComplete) {
            isStartupComplete = true
            addStartupProgressMessage("Ready!")
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "🟦 Startup complete - Room list ready to display (all sync messages processed, profile loaded, ${roomMap.size} rooms)")
            }
        }
    }

    fun setSpaces(spaces: List<SpaceItem>, skipCounterUpdate: Boolean = false) =
        syncRoomsCoordinator.setSpaces(spaces, skipCounterUpdate)
    
    fun updateAllSpaces(spaces: List<SpaceItem>) {
        val previousSize = allSpaces.size
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: updateAllSpaces called - setting allSpaces from $previousSize to ${spaces.size} spaces")
        if (spaces.isNotEmpty() && BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: updateAllSpaces - space names: ${spaces.map { it.name }.joinToString(", ")}")
        }
        if (previousSize > 0 && spaces.isEmpty() && BuildConfig.DEBUG) {
            android.util.Log.w("Andromuks", "AppViewModel: ⚠️ WARNING - updateAllSpaces clearing spaces from $previousSize to 0! Stack trace:")
            Thread.dumpStack()
        }
        allSpaces = spaces
        // CRITICAL: Also update singleton cache so spaces persist across ViewModel instances
        SpaceListCache.updateSpaces(spaces)
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: allSpaces set to ${spaces.size} spaces (was $previousSize), cache updated")
    }
    
    fun changeSelectedSection(section: RoomSectionType) {
        selectedSection = section
        // Exit space/bridge when switching to a different section
        if (section != RoomSectionType.SPACES && currentSpaceId != null) {
            currentSpaceId = null
        }
        if (section != RoomSectionType.BRIDGES && currentBridgeId != null) {
            currentBridgeId = null
        }
        // Reset space navigation when switching tabs
        if (section != RoomSectionType.SPACES) {
            currentSpaceId = null
        }
        
        // PERFORMANCE: No forceRoomListSort() here — rooms are already sorted by
        // performRoomReorder(). Tab switching only changes the view, not the data.
        // The roomListUpdateCounter bump is sufficient to trigger the UI update.
        
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun enterSpace(spaceId: String) = syncRoomsCoordinator.enterSpace(spaceId)
    
    fun exitSpace() = syncRoomsCoordinator.exitSpace()
    
    fun enterBridge(bridgeId: String) {
        currentBridgeId = bridgeId
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun exitBridge() {
        currentBridgeId = null
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun incrementUpdateCounter() {
        updateCounter++
    }
    
    fun triggerTimestampUpdate() {
        timestampUpdateCounter++
    }
    
    
    fun restartWebSocketConnection(trigger: ReconnectTrigger = ReconnectTrigger.UserRequested) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restarting WebSocket connection - $trigger")
        logActivity("Manual Reconnection - ${trigger.toLogString()}", null)
        restartWebSocket(trigger)
    }
    /**
     * Performs a full refresh by resetting all state and reconnecting for a complete payload.
     * This is triggered by:
     * 1. User pull-to-refresh gesture
     * 2. Automatic stale state detection (cached data > 10 minutes old)
     * 
     * Steps:
     * 1. Drop WebSocket connection
     * 2. Clear all room data
     * 3. Reset requestIdCounter to 1
     * 4. Reset last_received_sync_id to 0
     * 5. Reconnect with run_id but WITHOUT last_received_id (full payload)
     */
    /**
     * Reconnects to the server with session resume (run_id + last_received_id).
     * This keeps all local caches (room list, timeline, etc.) and only fetches deltas.
     */
    fun performQuickRefresh() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Performing quick refresh (resume)")
        logActivity("Quick Refresh - Reconnecting with Resume", null)
        
        // Mark that the next connection should attempt to resume
        reconnectWithResume = true
        
        // RESET NAVIGATION STATE: Ensure navigation callback fires after reconnection
        navigationCallbackTriggered = false
        initialSyncComplete = false
        
        // Drop WebSocket connection to trigger reconnection
        clearWebSocket("Quick refresh")
        
        // We do NOT clear any state here. 
        // SyncIngestor will handle the resume payload and only update changed items.
    }

    /**
     * Performs a full refresh of all app data.
     * This is useful when the state is corrupted or the user wants to force a clean sync.
     * 
     * Steps:
     * 1. Drop WebSocket connection
     * 2. Clear all room data
     * 3. Reset requestIdCounter to 1
     * 4. Reset last_received_sync_id to 0
     * 5. Reconnect cold (no run_id/last_received_id) for full sync
     */
    fun performFullRefresh() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Performing full refresh - resetting state")
        logActivity("Full Refresh - Resetting State", null)
        
        // Mark that the next connection should NOT attempt to resume (cold start)
        reconnectWithResume = false
        
        // RESET NAVIGATION STATE: Ensure navigation callback fires after reconnection
        navigationCallbackTriggered = false
        initialSyncComplete = false
        
        // 1. Drop WebSocket connection
        clearWebSocket("Full refresh")
        
        // 2. Clear all room data
        roomMap.clear()
        allRooms = emptyList()
        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
        allSpaces = emptyList()
        spaceList = emptyList()
        knownSpaceIds.clear()
        storedSpaceEdges = null
        spacesLoaded = false
        personsApi?.clear()
        synchronized(readReceiptsLock) {
            readReceipts.clear()
        }
        roomsWithLoadedReceipts.clear()
        roomsWithLoadedReactions.clear()
        MessageReactionsCache.clear()
        messageReactions = emptyMap()
        readReceiptsUpdateCounter++
        
        // 3. Reset requestIdCounter to 1
        requestIdCounter = 1
        
        // 4. Clear timeline caches on full refresh (all caches are stale)
        RoomTimelineCache.clearAll()
        // Processed timeline state is cleared by RoomTimelineCache.clearAll()
        lastReceivedRequestId = 0
        // CRITICAL: Also clear lastReceivedRequestId from SharedPreferences so reconnect doesn't use it
        appContext?.let { context ->
            WebSocketService.clearLastReceivedRequestId(context)
        }
        
        val preservedRunId = currentRunId
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: State reset complete - run_id preserved: $preservedRunId")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: FORCE REFRESH - timeline caches cleared, will reconnect with run_id only (no last_received_id)")
        
        // Clear caches and wait for fresh sync payloads from websocket.
        // All room/space data comes from sync_complete on reconnect - no DB loading needed
        // This function is called after a full refresh, which triggers a reconnect with clear_state: true
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full refresh completed - room/space data will come from sync_complete on reconnect")
        
        // 5. Trigger reconnection (will use run_id but not last_received_id since it's 0)
        onRestartWebSocket?.invoke(ReconnectTrigger.UserRequested)
    }
    
    
    // Get current room section based on selected tab
    /**
     * Get the count of unread rooms for Unread tab
     * PERFORMANCE: Uses pre-computed cached count for O(1) access
     */
    fun getUnreadCount(): Int {
        return cachedUnreadCount
    }
    
    /**
     * Get the count of unread rooms for Direct Chats tab
     * PERFORMANCE: Uses pre-computed cached count for O(1) access
     */
    fun getDirectChatsUnreadCount(): Int {
        return cachedDirectChatsUnreadCount
    }
    
    /**
     * Check if Direct Chats has any room with highlights
     * PERFORMANCE: Uses pre-computed cached flag for O(1) access
     */
    fun hasDirectChatsHighlights(): Boolean {
        return cachedDirectChatsHasHighlights
    }
    
    /**
     * Get the most relevant direct-message room for a given user, if any.
     * Prefers rooms listed in account data; falls back to scanning direct rooms.
     */
    fun getDirectRoomIdForUser(userId: String): String? =
        roomListUiCoordinator.getDirectRoomIdForUser(userId)
    
    /**
     * Get all DM room IDs for a user from m.direct account data
     * Returns empty set if user is not in m.direct or has no DM rooms
     * CRITICAL FIX: Falls back to scanning rooms when account data is not available
     * (e.g., when opened from external apps like Contacts before account_data is received)
     */
    fun getDirectRoomIdsForUser(userId: String): Set<String> =
        roomListUiCoordinator.getDirectRoomIdsForUser(userId)

    /**
     * Get the count of unread rooms for Favourites tab
     * PERFORMANCE: Uses pre-computed cached count for O(1) access
     */
    fun getFavouritesUnreadCount(): Int {
        return cachedFavouritesUnreadCount
    }
    
    /**
     * Check if Favourites has any room with highlights
     * PERFORMANCE: Uses pre-computed cached flag for O(1) access
     */
    fun hasFavouritesHighlights(): Boolean {
        return cachedFavouritesHasHighlights
    }
    
    
    fun getPendingInvites(): List<RoomInvite> {
        return pendingInvites.values.toList()
    }
    /**
     * Returns a read-only copy of the read receipts map.
     * 
     * This map contains read receipts organized by event ID. Each event ID maps to a list
     * of ReadReceipt objects representing users who have read the room up to that event.
     * 
     * Note: Read receipts represent "User has read the room up to event X", so when a user
     * reads a newer message, their receipt should only appear on the latest message they've read.
     * This is handled automatically by the ReceiptFunctions.processReadReceipts() function.
     * 
     * @return Map where keys are event IDs and values are lists of read receipts for that event
     */
    fun getReadReceiptsMap(): Map<String, List<ReadReceipt>> {
        return synchronized(readReceiptsLock) {
            readReceipts.mapValues { it.value.toList() }
        }
    }
    
    /**
     * Get receipt movements for animation tracking
     * @return Map of userId -> (previousEventId, currentEventId, timestamp)
     */
    fun getReceiptMovements(): Map<String, Triple<String?, String, Long>> {
        // Clean up old movements (older than 2 seconds) to prevent memory leaks
        synchronized(readReceiptsLock) {
            val currentTime = System.currentTimeMillis()
            receiptMovements.entries.removeAll { (_, movement) ->
                currentTime - movement.third > 2000
            }
            return receiptMovements.toMap()
        }
    }
    
    /**
     * Get new message animations for slide-up effect
     * @return Map of eventId -> timestamp when animation should start
     */
    /**
     * Get new message IDs for sound notification triggering.
     * PERFORMANCE: Removed animations - this now only tracks new messages for sound notifications.
     * @return map of new message event IDs (eventId -> current timestamp)
     */
    fun getNewMessageAnimations(): Map<String, Long> = newMessageAnimations.toMap()

    /**
     * Milliseconds when the user opened this room (Matrix-style timestamp).
     * Used to animate only messages newer than open time, not paginated history.
     */
    fun getRoomOpenTimestamp(roomId: String): Long? = roomOpenTimestamps[roomId]

    /**
     * Last time the timeline UI (RoomTimelineScreen / BubbleTimelineScreen) was in the
     * foreground for this room. Used as cutover for bubble entrance + newMessageAnimations
     * so batched sync_completes after app resume do not animate every message — only
     * events with server timestamp after this (i.e. truly received while user is looking).
     */
    private var timelineForegroundTimestamps = mutableMapOf<String, Long>() // roomId -> wall clock ms

    fun getTimelineForegroundTimestamp(roomId: String): Long? = timelineForegroundTimestamps[roomId]

    /** Call when timeline screen is shown or app returns to foreground on that screen. */
    fun markTimelineForeground(roomId: String) {
        timelineForegroundTimestamps[roomId] = System.currentTimeMillis()
    }
    
    internal fun buildDirectPersonTargets(rooms: List<RoomItem>): List<PersonTarget> =
        roomListUiCoordinator.buildDirectPersonTargets(rooms)
    
    fun getCurrentRoomSection(): RoomSection = roomListUiCoordinator.getCurrentRoomSection()

    fun showLoading() {
        isLoading = true
    }

    fun hideLoading() {
        isLoading = false
    }
    
    /**
     * Updates the set of low priority room IDs in SharedPreferences.
     * This is used by FCMService to filter out notifications for low priority rooms.
     */
    // BATTERY OPTIMIZATION: Cache last low priority rooms hash to avoid unnecessary SharedPreferences writes
    private var lastLowPriorityRoomsHash: String? = null
    
    /**
     * Update low priority rooms set for notification filtering.
     * BATTERY OPTIMIZATION: Only writes to SharedPreferences when the set actually changes.
     * This avoids expensive SharedPreferences writes on every sync when low priority status hasn't changed.
     */
    internal fun updateLowPriorityRooms(rooms: List<RoomItem>) {
        val lowPriorityRoomIds = rooms.filter { it.isLowPriority }.map { it.id }.toSet()
        
        // BATTERY OPTIMIZATION: Only update SharedPreferences if low priority rooms actually changed
        // Generate hash of room IDs to detect changes
        val newHash = lowPriorityRoomIds.sorted().joinToString(",")
        if (newHash == lastLowPriorityRoomsHash) {
            // No change - skip expensive SharedPreferences write
            return
        }
        
        lastLowPriorityRoomsHash = newHash
        
        appContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit()
                .putStringSet("low_priority_rooms", lowPriorityRoomIds)
                .apply()
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated low priority rooms set: ${lowPriorityRoomIds.size} rooms (changed)")
        }
    }

    fun updateHomeserverUrl(url: String) {
        homeserverUrl = url
    }
    
    fun updateAuthToken(token: String) {
        authToken = token
    }
    
    /**
     * Initializes FCM and related notification components.
     * 
     * This function delegates to FCMNotificationManager.initializeComponents() to set up
     * all FCM-related functionality including push notifications, conversation shortcuts,
     * and web client push integration.
     * 
     * @param context Application context
     * @param homeserverUrl The Gomuks backend URL (optional, can be empty at initialization)
     * @param authToken Authentication token for the backend (optional, can be empty at initialization)
     */
    fun initializeFCM(context: Context, homeserverUrl: String = "", authToken: String = "", skipCacheClear: Boolean = false) {
        fcmPushCoordinator.initializeFCM(context, homeserverUrl, authToken, skipCacheClear)
    }
    
    // PHASE 5.2: Periodic acknowledgment timeout check job
    private var acknowledgmentTimeoutJob: Job? = null
    
    // PHASE 5.4: Periodic cleanup job for acknowledged messages
    private var acknowledgedMessagesCleanupJob: Job? = null
    
    /**
     * PHASE 5.2: Start periodic check for unacknowledged messages
     * Checks every 10 seconds for messages that have exceeded their acknowledgment timeout
     */
    internal fun startAcknowledgmentTimeoutCheck() {
        acknowledgmentTimeoutJob?.cancel()
        acknowledgmentTimeoutJob = viewModelScope.launch {
            while (isActive) {
                delay(10000L) // Check every 10 seconds
                persistenceCoordinator.checkAcknowledgmentTimeouts()
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Started acknowledgment timeout check job")
    }

    internal fun startAcknowledgedMessagesCleanup() {
        acknowledgedMessagesCleanupJob?.cancel()
        acknowledgedMessagesCleanupJob = viewModelScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // Every 5 minutes
                persistenceCoordinator.cleanupAcknowledgedMessages()
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Started acknowledged messages cleanup job")
    }
    
    
    /**
     * Registers FCM notifications with the Gomuks backend.
     * 
     * This function delegates to FCMNotificationManager.registerNotifications() to initiate
     * the FCM token registration process. When the token is ready, it triggers the
     * WebSocket-based registration with the Gomuks backend.
     */
    fun registerFCMNotifications() = fcmPushCoordinator.registerFCMNotifications()

    /**
     * Get FCM token for Gomuks Backend registration
     */
    fun getFCMTokenForGomuksBackend(): String? = fcmPushCoordinator.getFCMTokenForGomuksBackend()
    
    /**
     * Check if push registration should be performed (time-based)
     */
    fun shouldRegisterPush(): Boolean {
        val result = webClientPushIntegration?.shouldRegisterPush() ?: false
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: shouldRegisterPush() called, result=$result")
        return result
    }
    
    /**
     * Mark push registration as completed
     */
    fun markPushRegistrationCompleted() {
        webClientPushIntegration?.markPushRegistrationCompleted()
    }
    
    /**
     * Get device ID for push registration (backend's device_id, for reference only)
     */
    fun getDeviceID(): String? {
        return webClientPushIntegration?.getDeviceID()
    }
    
    /**
     * Get local device ID used for FCM registration.
     * CRITICAL: This is the unique per-device identifier that prevents one device from overwriting another's FCM registration.
     * 
     * @return A unique device identifier that persists across app restarts
     */
    fun getLocalDeviceID(): String? {
        return webClientPushIntegration?.getLocalDeviceID()
    }
    
    /**
     * Register FCM token with Gomuks Backend via WebSocket
     * 
     * DEBOUNCE FIX: lastFCMRegistrationTime is only updated when we actually SEND (SUCCESS),
     * so a call from onTokenReady before init_complete (which queues or drops) won't block the later onInitComplete() call.
     * 
     * @param forceRegistrationOnConnect If true, register on every connect/reconnect (skip time-based shouldRegisterPush check).
     * @param forceNow If true, skip debounce (e.g. user tapped "Re-register" in Settings).
     */
    fun registerFCMWithGomuksBackend(forceRegistrationOnConnect: Boolean = false, forceNow: Boolean = false) =
        fcmPushCoordinator.registerFCMWithGomuksBackend(forceRegistrationOnConnect, forceNow)

    /**
     * Handle FCM registration response from Gomuks Backend
     */
    fun handleFCMRegistrationResponse(requestId: Int, data: Any) =
        fcmPushCoordinator.handleFCMRegistrationResponse(requestId, data)
    
    fun updateTypingUsers(roomId: String, userIds: List<String>) =
        readReceiptsTypingCoordinator.updateTypingUsers(roomId, userIds)

    /**
     * Get typing users for a specific room
     */
    fun getTypingUsersForRoom(roomId: String): List<String> =
        readReceiptsTypingCoordinator.getTypingUsersForRoom(roomId)
    
    private fun normalizeTimestamp(primary: Long, vararg fallbacks: Long): Long {
        if (primary > 0) return primary
        for (candidate in fallbacks) {
            if (candidate > 0) return candidate
        }
        return System.currentTimeMillis()
    }
    
    fun processReactionEvent(reactionEvent: ReactionEvent, isHistorical: Boolean = false) =
        reactionCoordinator.processReactionEvent(reactionEvent, isHistorical)

    fun handleClientState(userId: String?, device: String?, homeserver: String?) {
        if (!userId.isNullOrBlank()) {
            currentUserId = userId
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set currentUserId: $userId")
            appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString("current_user_id", userId)
                ?.apply()
        }
        if (!device.isNullOrBlank()) {
            deviceId = device
            // Store device ID for FCM registration
            webClientPushIntegration?.storeDeviceId(device)
        }
        // Store the real Matrix homeserver URL for matrix: URI handling
        if (!homeserver.isNullOrBlank()) {
            realMatrixHomeserverUrl = homeserver
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set realMatrixHomeserverUrl: $homeserver")
            callsWidgetsCoordinator.refreshElementCallBaseUrlFromWellKnown()
        }
        // IMPORTANT: Do NOT override gomuks backend URL with Matrix homeserver URL from client_state
        // The backend URL is set via AuthCheck from SharedPreferences (e.g., https://webmuks.aguiarvieira.pt)
        // Optionally, fetch profile for current user
        if (!currentUserId.isNullOrBlank()) {
            requestUserProfile(currentUserId)
        }
    }

    fun updateImageAuthToken(token: String) {
        imageAuthToken = token
    }

    fun setCallActive(active: Boolean) = callsWidgetsCoordinator.setCallActive(active)

    fun isCallActive(): Boolean = callsWidgetsCoordinator.isCallActive()

    fun setCallReadyForPip(ready: Boolean) = callsWidgetsCoordinator.setCallReadyForPip(ready)

    fun isCallReadyForPip(): Boolean = callsWidgetsCoordinator.isCallReadyForPip()

    internal var widgetToDeviceHandler: ((Any?) -> Unit)? = null

    fun setWidgetToDeviceHandler(handler: ((Any?) -> Unit)?) = toDeviceCoordinator.setWidgetToDeviceHandler(handler)

    fun handleToDeviceMessage(data: Any?) = toDeviceCoordinator.handleToDeviceMessage(data)

    internal fun handleSyncToDeviceEvents(syncJson: JSONObject) = toDeviceCoordinator.handleSyncToDeviceEvents(syncJson)

    /**
     * Populate roomMap from singleton cache when it's suspiciously small (e.g., only 1 room after opening from notification)
     * This ensures RoomListScreen has access to all rooms even when opening from notification bypassed normal initialization
     */
    fun populateRoomMapFromCache() = syncRoomsCoordinator.populateRoomMapFromCache()
    
    /**
     * Populates allSpaces and storedSpaceEdges from singleton SpaceListCache.
     * This ensures spaces persist across ViewModel instances (e.g., when opening from notification).
     */
    fun populateSpacesFromCache() = syncRoomsCoordinator.populateSpacesFromCache()
    
    /**
     * Populate readReceipts from singleton cache
     * This ensures read receipts persist across AppViewModel instances
     */
    fun populateReadReceiptsFromCache() = readReceiptsTypingCoordinator.populateReadReceiptsFromCache()
    
    /**
     * Populate messageReactions from singleton cache
     * This ensures reactions persist across AppViewModel instances
     */
    fun populateMessageReactionsFromCache() = reactionCoordinator.populateMessageReactionsFromCache()
    
    /**
     * Populate recentEmojis from singleton cache
     * This ensures recent emojis persist across AppViewModel instances
     */
    fun populateRecentEmojisFromCache() {
        try {
            val cachedEmojis = RecentEmojisCache.getAll()
            if (cachedEmojis.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRecentEmojisFromCache - populated with ${cachedEmojis.size} emojis from cache")
                recentEmojis = cachedEmojis
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate recentEmojis from cache", e)
        }
    }
    
    /**
     * Populate pendingInvites from singleton cache
     * This ensures invites persist across AppViewModel instances
     */
    fun populatePendingInvitesFromCache() {
        try {
            val cachedInvites = PendingInvitesCache.getAllInvites()
            if (cachedInvites.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populatePendingInvitesFromCache - populated with ${cachedInvites.size} invites from cache")
                // Invites are accessed via getPendingInvites() which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate pendingInvites from cache", e)
        }
    }
    
    /**
     * Initialize member cache from singleton RoomMemberCache
     * This ensures member profiles persist across AppViewModel instances
     */
    fun populateRoomMemberCacheFromCache() {
        try {
            val cachedMembers = RoomMemberCache.getAllMembers()
            if (cachedMembers.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMemberCacheFromCache - populated with ${cachedMembers.size} rooms from cache")
                // Member cache is accessed via getMemberProfile() which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate member cache from RoomMemberCache", e)
        }
    }
    
    /**
     * Populate customEmojiPacks from singleton cache
     * This ensures emoji packs persist across AppViewModel instances
     */
    fun populateEmojiPacksFromCache() {
        try {
            val cachedPacks = EmojiPacksCache.getAll()
            if (cachedPacks.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateEmojiPacksFromCache - populated with ${cachedPacks.size} packs from cache")
                // Emoji packs are accessed via customEmojiPacks which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate emoji packs from cache", e)
        }
    }
    
    /**
     * Populate stickerPacks from singleton cache
     * This ensures sticker packs persist across AppViewModel instances
     */
    fun populateStickerPacksFromCache() {
        try {
            val cachedPacks = StickerPacksCache.getAll()
            if (cachedPacks.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateStickerPacksFromCache - populated with ${cachedPacks.size} packs from cache")
                // Sticker packs are accessed via stickerPacks which reads from cache
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate sticker packs from cache", e)
        }
    }

    // Use a thread-safe Map to avoid ConcurrentModificationException when snapshots are taken
    internal val roomMap = java.util.concurrent.ConcurrentHashMap<String, RoomItem>()
    internal var syncMessageCount = 0
    
    // Track newly joined rooms (rooms that appeared in sync_complete for the first time)
    // These should be sorted to the top of the room list
    internal val newlyJoinedRoomIds = mutableSetOf<String>()

    // MEMORY MANAGEMENT: Profile caches are now singletons (ProfileCache)
    // This ensures profiles are shared across all AppViewModel instances
    // No instance variables needed - all access goes through ProfileCache singleton
    
    // OPTIMIZED EDIT/REDACTION SYSTEM - O(1) lookups for all operations
    // Now using singleton MessageVersionsCache
    // These are computed properties that read from the singleton cache
    internal val messageVersions: Map<String, VersionedMessage>
        get() = MessageVersionsCache.getAllVersions()
    
    private val editToOriginal: Map<String, String>
        get() = MessageVersionsCache.getAllVersions().flatMap { (originalId, versioned) ->
            versioned.versions.filter { !it.isOriginal && it.eventId != originalId }
                .map { it.eventId to originalId }
        }.toMap()
    
    internal val redactionCache: Map<String, TimelineEvent>
        get() = MessageVersionsCache.getAllVersions()
            .filter { it.value.redactionEvent != null }
            .mapNotNull { (eventId, versioned) ->
                versioned.redactionEvent?.let { eventId to it }
            }.toMap()
    
    private fun clearMessageVersions() {
        MessageVersionsCache.clear()
    }
    
    private fun clearMessageVersionsForRoom(roomId: String) {
        MessageVersionsCache.clearForRoom(roomId)
    }

    fun getMemberProfile(roomId: String, userId: String): MemberProfile? {
        // MEMORY MANAGEMENT: Try room-specific cache first (only exists if profile differs from global)
        val flattenedProfile = ProfileCache.getFlattenedProfile(roomId, userId)
        if (flattenedProfile != null) {
            return flattenedProfile
        }
        
        // If no room-specific profile, check global cache
        val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
        if (globalProfile != null) {
            ProfileCache.updateGlobalProfileAccess(userId)
            return globalProfile
        }
        
        // Fallback to legacy cache (for compatibility during transition)
        return RoomMemberCache.getMember(roomId, userId)
    }

    fun getMemberMap(roomId: String): Map<String, MemberProfile> =
        memberProfilesCoordinator.getMemberMap(roomId)
    
    /**
     * Enhanced getMemberMap that includes global cache fallback for users in timeline events
     */
    fun getMemberMapWithFallback(roomId: String, timelineEvents: List<TimelineEvent>? = null): Map<String, MemberProfile> =
        memberProfilesCoordinator.getMemberMapWithFallback(roomId, timelineEvents)
    
    /**
     * MEMORY MANAGEMENT: Helper method to store member profile in both flattened and legacy caches.
     * Delegates to [MemberProfilesCoordinator].
     */
    private fun storeMemberProfile(roomId: String, userId: String, profile: MemberProfile) =
        memberProfilesCoordinator.storeMemberProfile(roomId, userId, profile)
    
    /**
     * Updates the global profile cache explicitly (e.g., from profile requests).
     * This should be called when we receive a canonical profile, not from room member events.
     * When global profile is updated, we should re-evaluate room-specific entries.
     */
    fun updateGlobalProfile(userId: String, profile: MemberProfile) {
        val existingGlobalProfileEntry = ProfileCache.getGlobalProfile(userId)
        val existingGlobalProfile = existingGlobalProfileEntry?.profile
        
        // Update global profile
        ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
        
        // If global profile changed, clean up room-specific entries that now match global
        if (existingGlobalProfile != null && 
            (existingGlobalProfile.displayName != profile.displayName ||
             existingGlobalProfile.avatarUrl != profile.avatarUrl)) {
            
            // Use ProfileCache cleanup method
            ProfileCache.cleanupMatchingRoomProfiles(userId, profile)
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated global profile for $userId and cleaned up matching room-specific entries")
        }
    }
    
    /**
     * Gets the complete version history for a message (O(1) lookup)
     * @param eventId Either the original event ID or an edit event ID
     * @return VersionedMessage containing all versions, or null if not found
     */
    fun getMessageVersions(eventId: String): VersionedMessage? {
        // Check if this is an edit event first
        val originalEventId = editToOriginal[eventId] ?: eventId
        return messageVersions[originalEventId]
    }
    
    
    /**
     * Gets the redaction event for a deleted message (O(1) lookup)
     * @param eventId The event ID that was redacted
     * @return The redaction event, or null if not redacted
     */
    fun getRedactionEvent(eventId: String): TimelineEvent? {
        return redactionCache[eventId]
    }
    
    /**
     * Checks if a message has been edited (O(1) lookup from memory)
     * Note: This checks the in-memory cache which is populated from RoomTimelineCache.
     */
    fun isMessageEdited(eventId: String): Boolean {
        val versioned = getMessageVersions(eventId)
        return versioned != null && versioned.versions.size > 1
    }

    /**
     * Text to pre-fill when opening the edit composer. Handles sync_complete edits where
     * the original event still has body from first send while the latest text lives in
     * m.new_content on the edit event(s) only.
     *
     * E2EE: backend exposes plaintext in [TimelineEvent.decrypted] with decrypted_type m.room.message;
     * [TimelineEvent.content] is ciphertext only—never use it for body/msgtype.
     */
    fun getBodyTextForEdit(event: TimelineEvent): String {
        val content = event.getMessagePayload() ?: return ""
        val msgType = content.optString("msgtype", "")
        if (msgType == "m.emote") {
            val editSource = event.localContent?.optString("edit_source")?.takeIf { it.isNotBlank() }
            if (editSource != null) return editSource
        }
        content.optJSONObject("m.new_content")?.optString("body")?.takeIf { it.isNotBlank() }?.let { return it }
        val versioned = getMessageVersions(event.eventId) ?: return content.optString("body", "")
        val latestEdit = versioned.versions.firstOrNull { !it.isOriginal }?.event ?: return content.optString("body", "")
        val editPayload = latestEdit.getMessagePayload()
        editPayload?.optJSONObject("m.new_content")?.optString("body")?.takeIf { it.isNotBlank() }?.let { return it }
        return editPayload?.optString("body", "")?.takeIf { it.isNotBlank() } ?: content.optString("body", "")
    }
    
    /**
     * Data class for room-specific profile entry
     */
    data class RoomProfileEntry(
        val roomId: String?,
        val userId: String,
        val profile: MemberProfile
    )
    
    /**
     * Gets all profiles from memory cache (ProfileCache singleton).
     * For memory cache, we preserve room-specific profiles since Matrix allows different
     * display names/avatars per room.
     * @return List of RoomProfileEntry with roomId and profile info, sorted by display name
     */
    suspend fun getAllMemoryCachedProfiles(): List<RoomProfileEntry> = withContext(Dispatchers.Default) {
        val profilesByKey = linkedMapOf<String, RoomProfileEntry>()
        
        // Collect from flattened member cache (room-specific profiles)
        // Keys are in format "roomId:userId". Since Matrix userIds include ':',
        // split on the ":@" boundary to preserve the full userId.
        for ((key, profile) in ProfileCache.getAllFlattenedProfiles()) {
            val separatorIndex = key.indexOf(":@")
            if (separatorIndex > 0 && separatorIndex < key.length - 2) {
                val roomId = key.substring(0, separatorIndex)
                val userId = key.substring(separatorIndex + 1) // keep '@'
                if (roomId.isNotBlank() && userId.startsWith("@") && userId.contains(":")) {
                    val profileEntry = RoomProfileEntry(
                        roomId = roomId,
                        userId = userId,
                        profile = profile
                    )
                    profilesByKey["$roomId|$userId"] = profileEntry
                }
            }
        }

        // Collect from RoomMemberCache as additional per-room source used by cache stats.
        // This keeps the gallery consistent with "User Profiles (Per-Room Memory)" counts.
        for ((roomId, members) in RoomMemberCache.getAllMembers()) {
            for ((userId, profile) in members) {
                if (roomId.isNotBlank() && userId.startsWith("@") && userId.contains(":")) {
                    val cacheKey = "$roomId|$userId"
                    if (!profilesByKey.containsKey(cacheKey)) {
                        profilesByKey[cacheKey] = RoomProfileEntry(
                            roomId = roomId,
                            userId = userId,
                            profile = profile
                        )
                    }
                }
            }
        }
        
        // Collect from global profile cache (room-agnostic profiles)
        // These are global (not room-specific), so roomId is null
        for ((userId, entry) in ProfileCache.getAllGlobalProfiles()) {
            val profile = entry.profile
                // Validate userId format: must be @name:domain
                if (userId.startsWith("@") && userId.contains(":") && userId.length > 2) {
                    val parts = userId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("@") && parts[0].length > 1 && parts[1].isNotEmpty()) {
                        profilesByKey["global|$userId"] = RoomProfileEntry(
                            roomId = null, // Global profile, not room-specific
                            userId = userId,
                            profile = profile
                        )
                }
            }
        }
        
        // Sort by display name (nulls last), then by userId, then by roomId
        profilesByKey.values.sortedWith(compareBy(
            { it.profile.displayName ?: "\uFFFF" }, // Put nulls at the end
            { it.userId },
            { it.roomId ?: "" }
        ))
    }
    
    /**
     * Gets all cached media from memory cache.
     * 
     * IMPORTANT: Coil's MemoryCache stores bitmaps in RAM and doesn't support enumeration.
     * This method shows media that COULD be in Coil's memory cache by checking Coil's disk cache,
     * since disk cache is what gets loaded into memory when accessed.
     * 
     * For true RAM enumeration, Coil's MemoryCache API doesn't provide this capability.
     */
    suspend fun getAllMemoryCachedMedia(context: Context): List<CachedMediaEntry> = withContext(Dispatchers.IO) {
        try {
            // IMPORTANT: Coil's MemoryCache stores bitmaps in RAM and CANNOT be enumerated.
            // The MemoryCache API doesn't provide a way to list what's cached.
            // 
            // Instead, we show Coil's DISK cache, which represents images that:
            // 1. Are stored on disk (persistent)
            // 2. Get loaded into RAM (MemoryCache) when accessed via AsyncImage
            // 
            // So "Memory" cache shows what COULD be in RAM, but we can't verify actual RAM presence.
            
            // Load URL mappings first for faster lookups
            net.vrkknn.andromuks.utils.CoilUrlMapper.loadMappings(context)
            
            val entries = mutableListOf<CachedMediaEntry>()
            
            // Show Coil's disk cache (these get loaded into RAM when accessed)
            val coilCacheDir = java.io.File(context.cacheDir, "image_cache")
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Checking Coil disk cache at: ${coilCacheDir.absolutePath}, exists: ${coilCacheDir.exists()}")
            
            if (coilCacheDir.exists() && coilCacheDir.isDirectory) {
                // Process files in batches to reduce memory pressure
                val files = coilCacheDir.walkTopDown().filter { it.isFile }.toList()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found ${files.size} files in Coil disk cache")
                
                // Process in smaller batches to avoid OOM
                val batchSize = 50
                for (i in files.indices step batchSize) {
                    val batch = files.subList(i, minOf(i + batchSize, files.size))
                    
                    for (file in batch) {
                        // Try to find MXC URL via mapper
                        val mxcUrl = findMxcUrlForCoilFile(context, file)
                        
                        entries.add(CachedMediaEntry(
                            mxcUrl = mxcUrl,
                            filePath = file.absolutePath,
                            fileSize = file.length(),
                            cacheType = "memory",
                            file = file
                        ))
                    }
                    
                    // Yield to other coroutines between batches to prevent blocking
                    kotlinx.coroutines.yield()
                }
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Coil disk cache directory does not exist or is not a directory")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Returning ${entries.size} memory cached media entries")
            entries.sortedByDescending { it.fileSize }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to get memory cached media", e)
            emptyList()
        }
    }
    /**
     * Find MXC URL for a Coil cached file by checking the URL mapper.
     */
    private suspend fun findMxcUrlForCoilFile(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        try {
            // First, try CoilUrlMapper (fastest, most reliable)
            val mappedUrl = net.vrkknn.andromuks.utils.CoilUrlMapper.findMxcUrlForCacheFile(context, file)
            if (mappedUrl != null) {
                return@withContext mappedUrl
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error finding MXC URL for Coil file ${file.name}", e)
            null
        }
    }
    
    /**
     * Gets all cached media from disk cache (IntelligentMediaCache + Coil disk cache).
     */
    suspend fun getAllDiskCachedMedia(context: Context): List<CachedMediaEntry> = withContext(Dispatchers.IO) {
        try {
            // Load URL mappings first for faster lookups
            net.vrkknn.andromuks.utils.CoilUrlMapper.loadMappings(context)
            
            val entries = mutableListOf<CachedMediaEntry>()
            
            // 1. Get entries from IntelligentMediaCache (has MXC URLs stored)
            val cacheDir = IntelligentMediaCache.getCacheDir(context)
            if (cacheDir.exists() && cacheDir.isDirectory) {
                val files: Array<File>? = cacheDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isFile) {
                            // Try to find MXC URL by matching cache key
                            val mxcUrl: String? = IntelligentMediaCache.getMxcUrlForFile(file.name)
                            if (mxcUrl != null) {
                            entries.add(CachedMediaEntry(
                                mxcUrl = mxcUrl,
                                filePath = file.absolutePath,
                                fileSize = file.length(),
                                cacheType = "disk",
                                file = file
                            ))
                            }
                        }
                    }
                }
            }
            
            // 2. Get entries from Coil's disk cache (process in batches to reduce memory pressure)
            val coilCacheDir = java.io.File(context.cacheDir, "image_cache")
            if (coilCacheDir.exists() && coilCacheDir.isDirectory) {
                val files = coilCacheDir.walkTopDown().filter { it.isFile }.toList()
                
                // Process in smaller batches to avoid OOM
                val batchSize = 50
                for (i in files.indices step batchSize) {
                    val batch = files.subList(i, minOf(i + batchSize, files.size))
                    
                    for (file in batch) {
                        // Try to find MXC URL via mapper
                        val mxcUrl = findMxcUrlForCoilFile(context, file)
                        
                        entries.add(CachedMediaEntry(
                            mxcUrl = mxcUrl, // May be null if not found
                            filePath = file.absolutePath,
                            fileSize = file.length(),
                            cacheType = "disk",
                            file = file
                        ))
                    }
                    
                    // Yield to other coroutines between batches to prevent blocking
                    kotlinx.coroutines.yield()
                }
            }
            
            entries.sortedByDescending { it.fileSize }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to get disk cached media", e)
            emptyList()
        }
    }
    
    /**
     * Data class for cached media entry
     */
    data class CachedMediaEntry(
        val mxcUrl: String?,
        val filePath: String,
        val fileSize: Long,
        val cacheType: String, // "memory" or "disk"
        val file: java.io.File
    )
    
    /**
     * Gets the latest version of a message (O(1) lookup)
     */
    fun getLatestMessageVersion(eventId: String): TimelineEvent? {
        val versioned = getMessageVersions(eventId)
        return versioned?.versions?.firstOrNull()?.event
    }
    
    /**
     * Gets user profile information for a given user ID (CACHE ONLY - NON-BLOCKING)
     * 
     * This function ONLY checks caches and returns immediately without triggering network requests.
     * This prevents UI stalls during rendering.
     * 
     * PERFORMANCE: Uses O(1) global cache lookup instead of scanning all room caches.
     * 
     * To request missing profiles, use requestUserProfileAsync() from a LaunchedEffect or background coroutine.
     * 
     * @param userId The Matrix user ID to look up
     * @param roomId Optional room ID to check room-specific member cache first
     * @return MemberProfile if found in cache, null otherwise (UI should show fallback)
     */
    fun getUserProfile(userId: String, roomId: String? = null): MemberProfile? {
        // IMPORTANT: Check room-specific cache FIRST when roomId is provided
        // Users can have different names in different rooms, so room-specific profiles take precedence
        if (roomId != null) {
            val flattenedProfile = ProfileCache.getFlattenedProfile(roomId, userId)
            if (flattenedProfile != null) {
                return flattenedProfile
            }
            
            // Fallback to legacy room member cache
            val roomMember = RoomMemberCache.getMember(roomId, userId)
            if (roomMember != null) {
                return roomMember
            }
        }
        
        // OPTIMIZED: Check if it's the current user (check both currentUserProfile and global cache)
        if (userId == currentUserId) {
            if (currentUserProfile != null) {
                return MemberProfile(
                    displayName = currentUserProfile!!.displayName,
                    avatarUrl = currentUserProfile!!.avatarUrl
                )
            }
            // Also check global cache for current user (in case profile was loaded but currentUserProfile not set yet)
            val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
            if (globalProfile != null) {
                ProfileCache.updateGlobalProfileAccess(userId)
                return globalProfile
            }
        }
        
        // OPTIMIZED: Check global profile cache (fallback for when no roomId or room-specific not found)
        val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
        if (globalProfile != null) {
            ProfileCache.updateGlobalProfileAccess(userId)
            return globalProfile
        }
        
        // NOT FOUND - Return null immediately (don't block UI)
        // UI will show fallback and can request profile asynchronously
        return null
    }
    
    /**
     * Request user profile asynchronously (non-blocking)
     * Call this from LaunchedEffect or background coroutine, NOT during composition
     */
    fun requestUserProfileAsync(userId: String, roomId: String? = null) {
        // Only request if it's a valid Matrix ID and not already in cache
        if (!userId.startsWith("@") || !userId.contains(":")) {
            return
        }
        
        // Check if already in cache (quick check)
        val profile = getUserProfile(userId, roomId)
        if (profile != null) {
            return // Already cached
        }
        
        // Not in cache - request it (async)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Async requesting profile for Matrix user: $userId")
        requestUserProfile(userId, roomId)
    }
    

    
    /**
     * Helper function to check if an event is an edit (m.replace relationship)
     */
    internal fun isEditEvent(event: TimelineEvent) = editVersionCoordinator.isEditEvent(event)

    /** Target event id for an edit (m.replace); supports sync_complete top-level relates_to only. */
    private fun editTargetEventId(event: TimelineEvent) = editVersionCoordinator.editTargetEventId(event)

    /**
     * Helper to merge message versions without duplicates and keep newest-first ordering.
     */
    private fun mergeVersionsDistinct(
        existing: List<MessageVersion>,
        extra: MessageVersion? = null
    ) = editVersionCoordinator.mergeVersionsDistinct(existing, extra)

    /**
     * OPTIMIZED: Process events to build version cache (O(n) where n = number of events)
     * This replaces the old chain-following approach with direct version storage
     */
    internal fun processVersionedMessages(events: List<TimelineEvent>) =
        editVersionCoordinator.processVersionedMessages(events)

    
    // PERFORMANCE: Track member processing state for incremental updates
    private var memberProcessingIndex = 0
    private val lastProcessedMembers = mutableSetOf<String>() // Track which rooms had member events
    
    /**
     * PERFORMANCE OPTIMIZATION: Incremental member cache processing
     * Only processes members for rooms that changed, and only every 3rd sync message
     * This prevents 100-300ms delays on every sync for large rooms
     */
    internal fun populateMemberCacheFromSync(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val roomsJson = data.optJSONObject("rooms") ?: return
        
        // BATTERY OPTIMIZATION: Only process rooms with member events
        // First pass: Quickly identify which rooms have member events (without processing them)
        val roomsWithMemberEvents = mutableListOf<Pair<String, JSONObject>>()
        
        val roomKeys = roomsJson.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomObj = roomsJson.optJSONObject(roomId) ?: continue
            val events = roomObj.optJSONArray("events") ?: continue
            
            // Quick check: Does this room have any member events?
            var hasMemberEvents = false
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventType = event.optString("type")
                if (eventType == "m.room.member") {
                    hasMemberEvents = true
                    break // Found one, no need to check further
                }
            }
            
            // Only process rooms that have member events
            if (hasMemberEvents) {
                roomsWithMemberEvents.add(roomId to roomObj)
            }
        }
        
        if (roomsWithMemberEvents.isEmpty()) {
            // No rooms with member events - nothing to process
            //if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: MEMBER PROCESSING - No rooms with member events in this sync")
            return
        }
        
        // Removed debug log to reduce log spam during initial sync
        
        // Track current sync's room IDs with member events
        val currentSyncRooms = mutableSetOf<String>()
        
        // Second pass: Process only rooms with member events
        for ((roomId, roomObj) in roomsWithMemberEvents) {
            val events = roomObj.optJSONArray("events") ?: continue
            // Get existing members from singleton cache or create empty map
            val existingMembers = RoomMemberCache.getRoomMembers(roomId)
            val memberMap = existingMembers.toMutableMap()
            
            // Process member events
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventType = event.optString("type")
                
                if (eventType == "m.room.member") {
                    val userId = event.optString("state_key") ?: event.optString("sender")
                    val content = event.optJSONObject("content")
                    val membership = content?.optString("membership")
                    val unsigned = event.optJSONObject("unsigned")
                    val prevContent = unsigned?.optJSONObject("prev_content")
                    val prevMembership = prevContent?.optString("membership")
                    
                    if (userId != null) {
                        when (membership) {
                            "join" -> {
                                // Add/update only joined members to room cache for mention lists
                                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                                
                                val profile = MemberProfile(displayName, avatarUrl)
                                val previousProfile = memberMap[userId]
                                
                                // Update singleton cache
                                RoomMemberCache.updateMember(roomId, userId, profile)
                                
                                // Check if this is actually a new join (not just a profile change)
                                val isNewJoin = previousProfile == null
                                
                                // Check if this is a profile change (join -> join with different profile data)
                                val isProfileChange = prevMembership == "join" && membership == "join" && !isNewJoin &&
                                    (previousProfile?.displayName != displayName || previousProfile?.avatarUrl != avatarUrl)
                                
                                // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                                storeMemberProfile(roomId, userId, profile)
                                
                                // COLD START FIX: Only trigger UI updates for actual real-time changes, not historical data
                                // During initial sync (before initialSyncComplete), all member events are historical and shouldn't trigger UI updates
                                // After initial sync is complete, only actual state transitions (invite→join, leave→join) should trigger updates
                                val isActualNewJoin = isNewJoin && (prevMembership == null || prevMembership == "invite" || prevMembership == "leave")
                                
                                // BATTERY OPTIMIZATION: Only trigger UI updates when foregrounded AND initial sync is complete
                                // Cache is still updated (for accuracy), but no recompositions during initial sync or when backgrounded
                                // CRITICAL: Check initialSyncComplete (not initializationComplete) because we're still processing
                                // historical data from queued sync_complete messages even after initializationComplete is set
                                if (isAppVisible && initialSyncComplete) {
                                    if (isActualNewJoin) {
                                        // New joins are critical - trigger member update immediately
                                        memberUpdateCounter++
                                    } else if (isProfileChange) {
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile change detected for $userId - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                                        // Trigger UI update for profile changes
                                        memberUpdateCounter++
                                    }
                                }
                                // Removed debug log for member changes during initial sync to reduce log spam
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached joined member '$userId' in room '$roomId' -> displayName: '$displayName'")
                            }
                            "invite" -> {
                                // Store invited members in global cache only (for profile lookups) but not in room member cache
                                // This prevents them from appearing in mention lists but allows profile display if they send messages
                                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                                
                                val profile = MemberProfile(displayName, avatarUrl)
                                ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached invited member '$userId' profile in global cache only -> displayName: '$displayName'")
                            }
                            "leave", "ban" -> {
                                // Remove members who left or were banned from room cache only
                                memberMap.remove(userId)
                                ProfileCache.removeFlattenedProfile(roomId, userId)
                                
                                // OPTIMIZED: Remove from indexed cache
                                ProfileCache.removeFromRoomIndex(roomId, userId)
                                
                                // COLD START FIX: Only trigger UI updates for actual real-time changes, not historical data
                                // BATTERY OPTIMIZATION: Only trigger UI updates when foregrounded AND initialization is complete
                                // Cache is still updated (for accuracy), but no recompositions during initialization or when backgrounded
                                if (isAppVisible && initializationComplete) {
                                    // Trigger member update for leaves (critical change)
                                    memberUpdateCounter++
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Member left/banned: $userId in room $roomId - triggering immediate UI update")
                                } else {
                                    val reason = when {
                                        !initializationComplete -> "initialization"
                                        !isAppVisible -> "app backgrounded"
                                        else -> "unknown"
                                    }
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Member left/banned cached ($reason) - $userId in room $roomId")
                                }
                                // Note: Don't remove from global cache as they might be in other rooms
                                // Note: Keep disk cache for potential future re-joining
                            }
                        }
                    }
                }
            }
            
            // Track rooms with member events in this sync
            currentSyncRooms.add(roomId)
        }
        
        // Update tracking set for next sync comparison
        lastProcessedMembers.clear()
        lastProcessedMembers.addAll(currentSyncRooms)
        
        // Removed debug log to reduce log spam during initial sync
    }
    

    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) = syncRoomsCoordinator.updateRoomsFromSyncJsonAsync(syncJson)

    /**
     * Single [sync_complete] pipeline from [SyncRepository] (not per-VM fan-out).
     */
    internal suspend fun applySyncCompleteFromRepository(syncJson: JSONObject) {
        syncRoomsCoordinator.updateRoomsFromSyncJsonAsyncBody(syncJson)
    }
    // SYNC OPTIMIZATION: Helper functions for diff-based and batched updates
    
    /**
     * Generate a hash for room state to detect actual changes
     */
    internal fun generateRoomStateHash(rooms: List<RoomItem>): String {
        return rooms.joinToString("|") { "${it.id}:${it.name}:${it.unreadCount}:${it.messagePreview}:${it.messageSender}:${it.sortingTimestamp}" }
    }
    
    /**
     * Generate a hash for timeline state to detect actual changes
     */
    private fun generateTimelineStateHash(events: List<TimelineEvent>): String {
        return events.takeLast(50).joinToString("|") { "${it.eventId}:${it.timestamp}:${it.content?.toString()}" }
    }
    
    /**
     * Generate a hash for member state to detect actual changes
     */
    internal fun generateMemberStateHash(): String {
        return ProfileCache.getAllFlattenedProfiles().entries.take(100).joinToString("|") { "${it.key}:${it.value.displayName}:${it.value.avatarUrl}" }
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Adaptive batched UI updates
     * This prevents excessive recompositions by batching rapid updates with context-aware delays
     * 
     * Strategy:
     * - Initial sync (spacesLoaded = false): 100ms delay to batch many rapid updates
     * - Normal operation: 16ms delay (~60fps) for responsive UI
     * - During rapid syncs: Auto-increases delay if updates keep coming
     * 
     * This reduces recompositions from 10-20 per second to ~1-2 during initial sync
     */
    internal fun scheduleUIUpdate(updateType: String) {
        pendingUIUpdates.add(updateType)
        
        // Cancel existing batch job if any
        batchUpdateJob?.cancel()
        
        // PERFORMANCE: Adaptive delay based on app state
        val delay = when {
            !spacesLoaded -> 100L  // Longer delay during initial sync to batch many rapid updates
            else -> 16L             // Normal operation - ~60fps for responsive UI
        }
        
        // Schedule new batch update with adaptive delay
        batchUpdateJob = viewModelScope.launch {
            delay(delay)
            
            if (pendingUIUpdates.isNotEmpty()) {
                performBatchedUIUpdates()
                pendingUIUpdates.clear()
            }
        }
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Debounced room reordering
     * Updates badges and timestamps immediately, but only reorders the list every 30 seconds
     * This prevents the frustrating "room jumping" effect when new messages arrive
     * Can be forced to sort immediately via forceImmediate parameter
     */
    internal fun scheduleRoomReorder(forceImmediate: Boolean = false) =
        roomListUiCoordinator.scheduleRoomReorder(forceImmediate)
    
    /**
     * Force immediate room list re-sorting
     * Called when switching tabs or returning to RoomListScreen
     */
    fun forceRoomListSort() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Force sorting room list (tab change or screen return)")
        scheduleRoomReorder(forceImmediate = true)
    }
    /**
     * Perform batched UI updates only for changed sections
     */
    private fun performBatchedUIUpdates() {
        // Only update counters for sections that actually need updates
        if (needsRoomListUpdate) {
            roomListUpdateCounter++
            needsRoomListUpdate = false
            
        }
        
        if (needsTimelineUpdate) {
            timelineUpdateCounter++
            needsTimelineUpdate = false

        }
        
        if (needsMemberUpdate) {
            memberUpdateCounter++
            needsMemberUpdate = false

        }
        
        if (needsReactionUpdate) {
            reactionUpdateCounter++
            needsReactionUpdate = false
 
        }
        
        // Keep for backward compatibility temporarily
        updateCounter++
        

    }

    // NAVIGATION PERFORMANCE: Helper functions for prefetching and navigation caching
    
    /**
     * Prefetch essential room data for rooms visible or near visible in the room list
     */
    fun prefetchRoomData(visibleRoomIds: List<String>, scrollPosition: Int = 0) {
        // DISABLED: prefetchRoomData() is no longer needed because:
        // 1. loadAllRoomStatesAfterInitComplete() already loads bridge info for all rooms
        // 2. Bridge info is cached in SharedPreferences and loaded from cache for new rooms
        // 3. Bridge badges are optional and non-essential for room list rendering
        // 4. This reduces unnecessary get_room_state requests during scrolling
        
        // Keep scroll position tracking for potential future use
        lastRoomListScrollPosition = scrollPosition
        
        // No-op: Room state is already loaded by loadAllRoomStatesAfterInitComplete()
        // and bridge info is loaded from cache when rooms are added
    }
    
    /**
     * Prefetch essential room data (basic state, timeline cache check)
     */
    private fun prefetchEssentialRoomData(roomId: String) {
        prefetchedRooms.add(roomId)
        
        // Initialize navigation state if not exists
        if (!navigationCache.containsKey(roomId)) {
            navigationCache[roomId] = RoomNavigationState(roomId)
        }
        
        // CRITICAL FIX: Defer bridge protocol avatar requests until after initial sync completes
        // During initial sync with 580+ rooms, sending hundreds of get_room_state requests overwhelms the system
        // Bridge avatars are non-essential and can be loaded lazily after UI is shown
        if (!initialSyncComplete) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Deferring room state request for $roomId until initial sync completes (bridge avatars are non-essential)")
            }
            // Update navigation state but don't request room state yet
            val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
            navigationCache[roomId] = currentState.copy(
                essentialDataLoaded = false, // Mark as not loaded yet
                lastPrefetchTime = System.currentTimeMillis()
            )
            return
        }
        
        // NAVIGATION PERFORMANCE: Load essential data first (room state without members)
        if (!pendingRoomStateRequests.contains(roomId)) {
            requestRoomState(roomId)
        }
        
        // Update navigation state
        val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
        navigationCache[roomId] = currentState.copy(
            essentialDataLoaded = true,
            lastPrefetchTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Load additional room details (members, full timeline) after essential data
     */
    internal fun loadRoomDetails(roomId: String, navigationState: RoomNavigationState) {
        // OPPORTUNISTIC PROFILE LOADING: Skip bulk member loading to prevent OOM
        // Profiles will be loaded on-demand when actually needed for rendering
        if (navigationState.essentialDataLoaded && !navigationState.memberDataLoaded) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SKIPPING bulk member loading for $roomId (using opportunistic loading)")
            navigationCache[roomId] = navigationState.copy(memberDataLoaded = true)
        }
        
        // Load additional timeline data if needed
        if (navigationState.essentialDataLoaded && !navigationState.timelineDataLoaded) {
            navigationCache[roomId] = navigationState.copy(timelineDataLoaded = true)
        }
    }
    
    /**
     * Get cached navigation state for a room
     */
    fun getRoomNavigationState(roomId: String): RoomNavigationState? {
        return navigationCache[roomId]
    }

    /**
     * Process a single initial sync_complete message (called after init_complete for queued messages)
     * This is the same logic as updateRoomsFromSyncJsonAsync but without the queue check
     * @param onComplete Callback invoked when processing actually completes (after DB work)
     * @return Job for the summary update, or null if no summary update is needed
     */
    // NOTE: Legacy name. This function is used for processing *any* sync_complete message,
    // including background-batched flushes (not just initial sync after init_complete).
    private fun processInitialSyncComplete(syncJson: JSONObject, onComplete: (suspend () -> Unit)? = null): Job? {
        return try {
            handleSyncToDeviceEvents(syncJson)
            
            // CRITICAL FIX: handleClearStateReset must be called BEFORE parsing for queued messages
            // This ensures state is cleared atomically before processing the message
            val data = syncJson.optJSONObject("data")
            val isClearState = data?.optBoolean("clear_state") == true
            if (isClearState) {
                val requestId = syncJson.optInt("request_id", 0)
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "🟣 processSyncCompleteMessage: clear_state=true - clearing state (request_id=$requestId)")
                }
                syncRoomsCoordinator.handleClearStateReset()
            }
            
            // Update last sync timestamp immediately (this is lightweight)
            lastSyncTimestamp = System.currentTimeMillis()
            
            // Update service with new sync timestamp
            WebSocketService.updateLastSyncTimestamp()
            
            // Extract request_id from sync_complete (no longer tracked for reconnection)
            val requestId = syncJson.optInt("request_id", 0)
        
        // Process sync_complete in memory (run in background)
        // CRITICAL FIX: Return the summary update job so we can wait for it to complete
        val summaryUpdateJob = appContext?.let { context ->
            ensureSyncIngestor()

            // Clone JSON before launching background jobs to avoid concurrent mutation issues
            val persistenceJson = try {
                org.json.JSONObject(syncJson.toString())
            } catch (e: Exception) {
                android.util.Log.e(
                    "Andromuks",
                    "AppViewModel: Failed to clone sync_complete JSON for persistence: ${e.message}",
                    e
                )
                null
            }
            
            viewModelScope.launch(Dispatchers.IO) {
                val jsonForPersistence = persistenceJson ?: try {
                    org.json.JSONObject(syncJson.toString())
                } catch (cloneException: Exception) {
                    android.util.Log.e(
                        "Andromuks",
                        "AppViewModel: Unable to clone sync_complete JSON on IO dispatcher: ${cloneException.message}",
                        cloneException
                    )
                    null
                }

                if (jsonForPersistence == null) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Skipping sync persistence because JSON clone failed"
                    )
                    // Still invoke callback even if we skip (to update counter)
                    onComplete?.invoke()
                    return@launch
                }

                try {
                    // Pass isAppVisible to SyncIngestor for battery optimizations
                    // Returns IngestResult with rooms that had events and parsed invites
                    val ingestResult = syncIngestor?.ingestSyncComplete(jsonForPersistence!!, requestId, currentRunId, isAppVisible)
                    val roomsWithEvents = ingestResult?.roomsWithEvents ?: emptySet()
                    val invites = ingestResult?.invites ?: emptyList()
                    
                    // CRITICAL: Update pendingInvites directly from parsed invites (in-memory only)
                    if (invites.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Update in-memory pendingInvites map
                            invites.forEach { invite ->
                                PendingInvitesCache.updateInvite(invite)
                            }
                            // BATTERY OPTIMIZATION: Only trigger UI update when foregrounded
                            // When backgrounded, batch processing will trigger a single update after batch completes
                            if (isAppVisible) {
                                // Trigger UI update to show invites
                                needsRoomListUpdate = true
                                roomListUpdateCounter++
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated ${invites.size} invites from sync_complete (in-memory only, total: ${pendingInvites.size})")
                            } else {
                                // Background: mark for batched update
                                needsRoomListUpdate = true
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated ${invites.size} invites (background - will update UI once per batch)")
                            }
                        }
                    }
                    
                    // CRITICAL: Notify RoomListScreen that room summaries may have been updated
                    // This ensures room list shows new message previews/senders immediately
                    // BATTERY OPTIMIZATION: Only increment counter when foregrounded
                    // When backgrounded, batch processing will trigger a single update after batch completes
                    if (roomsWithEvents.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (isAppVisible) {
                                roomSummaryUpdateCounter++
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room summaries updated for ${roomsWithEvents.size} rooms - triggering RoomListScreen refresh (roomSummaryUpdateCounter: $roomSummaryUpdateCounter, isAppVisible=$isAppVisible)")
                            } else {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room summaries updated for ${roomsWithEvents.size} rooms (background - will update UI once per batch)")
                            }
                        }
                    }
                    
                    // CRITICAL FIX: Don't invoke completion callback here - it will be invoked after account data processing completes
                    // The completion callback is now invoked in accountDataProcessingJob after processParsedSyncResult completes
                    // This ensures startup doesn't complete before account data is processed
                    
                    // Events are processed in-memory via processSyncEventsArray()
                    // No DB refresh needed - timeline is updated directly from sync_complete events
                    
                    // CRITICAL: Update last_received_request_id in RAM after sync_complete is processed successfully
                    // This is used for faster reconnections (only on reconnections, not initial connections)
                    // Note: request_id can be negative (and usually is), so check != 0 instead of > 0
                    val requestId = syncJson.optInt("request_id", 0)
                    if (requestId != 0 && appContext != null) {
                        WebSocketService.updateLastReceivedRequestId(requestId, appContext!!)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error persisting sync_complete: ${e.message}", e)
                    // Don't block UI updates if persistence fails
                    // Still invoke callback to update counter even on error
                    onComplete?.invoke()
                }
            }
        } ?: run {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Skipping sync persistence because appContext is null"
            )
            // CRITICAL FIX: Don't invoke callback here - it will be invoked in accountDataProcessingJob
            // The account data processing job runs regardless of appContext, so callback will be invoked there
            null
        }
        
            // CRITICAL FIX: Account data is processed in processParsedSyncResult (called from background job below)
            // Don't process it here to avoid duplicate processing
            // This ensures account data is processed once, in the correct order, after sync parsing completes
            
            // PERFORMANCE: Move heavy JSON parsing to background thread
            // CRITICAL FIX: Capture this job so we can wait for account data processing to complete
            val accountDataProcessingJob = viewModelScope.launch(Dispatchers.Default) {
                try {
                    // Parse sync data on background thread (200-500ms for large accounts)
                    // IMPORTANT: use snapshot to avoid ConcurrentModification when roomMap is cleared/reset concurrently.
                    val existingRoomsSnapshot = synchronized(roomMap) { HashMap(roomMap) }
                    // isClearState was already determined above (before handleClearStateReset)
                    val syncResult = SpaceRoomParser.parseSyncUpdate(
                        syncJson, 
                        RoomMemberCache.getAllMembers(), 
                        this@AppViewModel,
                        existingRooms = existingRoomsSnapshot, // Pass snapshot to avoid concurrent modification
                        isClearState = isClearState
                    )
                    
                    // SpaceRoomParser parses all room data including message previews and metadata
                    
                    // Switch back to main thread for UI updates only
                    withContext(Dispatchers.Main) {
                        try {
                            syncRoomsCoordinator.processParsedSyncResult(syncResult, syncJson)
                            // CRITICAL FIX: Invoke completion callback AFTER account data processing completes
                            // This ensures startup doesn't complete before account data is processed
                            onComplete?.invoke()
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "🟣 processInitialSyncComplete: CRASH in processParsedSyncResult - ${e.message}", e)
                            // Still invoke callback even on error to update counter
                            onComplete?.invoke()
                            // Don't rethrow - continue processing other messages
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "🟣 processInitialSyncComplete: CRASH in background parsing - ${e.message}", e)
                    // Still invoke callback even on error to update counter
                    onComplete?.invoke()
                    // Don't rethrow - continue processing other messages
                }
            }
            
            // CRITICAL FIX: Return the account data processing job instead of summary update job
            // This ensures we wait for account data processing to complete before marking startup as complete
            // The summary update job runs in parallel but doesn't block startup completion
            accountDataProcessingJob
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "🟣 processInitialSyncComplete: CRASH at start - ${e.message}", e)
            // Return null to indicate failure, but don't crash the app
            null
        }
    }

    /**
     * Process a sync_complete message atomically and in-order.
     *
     * Key goals:
     * - Avoid piling up background coroutines when many sync_complete messages arrive.
     * - Ensure per-message processing completes before the next message begins (FIFO semantics).
     * - Keep heavy parsing off the main thread; switch to main only for UI state updates.
     *
     * Note: handleSyncToDeviceEvents() is called in updateRoomsFromSyncJsonAsync() at receive time,
     * so we intentionally do NOT call it again here to avoid double-processing.
     */
    
    /**
     * Handles server-directed clear_state=true: purge in-memory derived state
     * while keeping events/profile/media intact.
     */
    
    /**
     * Clears in-memory derived room/space state so subsequent syncs repopulate from scratch.
     * Events, profile info, and media cache remain untouched.
     */

    
    
    
    fun onInitComplete() {
        android.util.Log.d("Andromuks", "🟣 onInitComplete: START - initialSyncComplete=$initialSyncComplete, spacesLoaded=$spacesLoaded, initialSyncCompleteQueue.size=${initialSyncCompleteQueue.size}")
        addStartupProgressMessage("Initialization complete - processing ${initialSyncCompleteQueue.size} sync messages")
        
        // CRITICAL FIX: Set initialSyncPhase = true to stop queueing and start processing queued messages
        initialSyncPhase = true
        android.util.Log.d("Andromuks", "🟣 onInitComplete: Set initialSyncPhase=true, will process ${initialSyncCompleteQueue.size} queued initial sync_complete messages")

        // Start WebSocket pinger immediately on init_complete so low-traffic accounts don't wait for first sync_complete
        net.vrkknn.andromuks.WebSocketService.startPingLoopOnInitComplete()
        
        // CRITICAL FIX: Set spacesLoaded immediately so UI doesn't wait unnecessarily
        // This is safe because init_complete means the backend is ready
        spacesLoaded = true
        
        // Process all queued initial sync_complete messages
        viewModelScope.launch(Dispatchers.Default) {
                    try {
                        initialSyncProcessingMutex.withLock {
                            val queuedMessages = synchronized(initialSyncCompleteQueue) {
                                val messages = initialSyncCompleteQueue.toList()
                                val totalCount = messages.size
                                initialSyncCompleteQueue.clear()
                                pendingSyncCompleteCount = totalCount // Set total pending count
                                processedSyncCompleteCount = 0 // Reset processed count
                                messages
                            }
                            
                            // CRITICAL FIX: Update WebSocketService state with actual queued count
                            // This ensures notification shows correct count (e.g., "Initializing (0/9)" instead of "Initializing (0/0)")
                            if (queuedMessages.isNotEmpty()) {
                                net.vrkknn.andromuks.WebSocketService.updateInitializingProgress(
                                    pendingCount = queuedMessages.size,
                                    processedCount = 0
                                )
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Updated WebSocketService state - Initializing (0/${queuedMessages.size})")
                                }
                            }
                            
                            if (queuedMessages.isNotEmpty()) {
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Processing ${queuedMessages.size} queued initial sync_complete messages")
                                }
                                
                                // PERFORMANCE FIX: Process queued messages in chunks with yields to prevent ANR
                                // This is critical for reconnect scenarios where hundreds of sync batches may queue up
                                val chunkSize = 10 // Process 10 sync batches before yielding
                                
                                // CRITICAL FIX: Optimize unblocking based on sync_complete message pattern for large accounts:
                                // Message 1: clear_state=true, ~100 rooms, space_edges, top_level_spaces (CRITICAL - has spaces info)
                                // Messages 2-7: ~100 rooms each
                                // Message 8: account_data
                                // Strategy: Unblock after message 1 (has spaces) OR after 2 messages (~200 rooms), whichever comes first
                                // This ensures UI shows immediately with spaces info while remaining rooms load in background
                                var hasUnblockedUI = false
                                
                                for ((index, syncJson) in queuedMessages.withIndex()) {
                                    val requestId = syncJson.optInt("request_id", 0)
                                    if (BuildConfig.DEBUG && (index % 50 == 0 || index == queuedMessages.size - 1)) {
                                        android.util.Log.d("Andromuks", "AppViewModel: Processing queued initial sync_complete message ${index + 1}/${queuedMessages.size} (request_id=$requestId) - FIFO sequential processing")
                                    }
                                    
                                    // Check if this is the first message with clear_state=true (has spaces info)
                                    val data = syncJson.optJSONObject("data")
                                    val isClearState = data?.optBoolean("clear_state") == true
                                    val isFirstMessage = index == 0
                                    val hasSpacesInfo = isFirstMessage && isClearState
                                    
                                    // Add progress message for processing sync_complete
                                    if (isClearState) {
                                        addStartupProgressMessage("Processing spaces and initial rooms...")
                                    } else {
                                        val roomsJson = data?.optJSONObject("rooms")
                                        val roomCount = roomsJson?.length() ?: 0
                                        if (roomCount > 0) {
                                            addStartupProgressMessage("Processing $roomCount rooms...")
                                        } else if (data?.optJSONObject("account_data") != null) {
                                            addStartupProgressMessage("Processing account data...")
                                        }
                                    }
                                    
                                    // CRITICAL FIX: Process messages SEQUENTIALLY to prevent race conditions
                                    // Each message must complete before the next one starts to ensure proper ordering
                                    // This prevents out-of-order processing (e.g., request_id=-894263 completing before request_id=0)
                                    val currentIndex = index // Capture index for lambda
                                    try {
                                        if (BuildConfig.DEBUG) {
                                            android.util.Log.d("Andromuks", "🟣 onInitComplete: About to process message ${currentIndex + 1}/${queuedMessages.size} (sequential processing)")
                                        }
                                        val msgRunId = data?.optString("run_id", "") ?: ""
                                        kotlinx.coroutines.withTimeoutOrNull(30000L) { // 30 second timeout per message
                                            syncRoomsCoordinator.processSyncCompleteAtomic(
                                                syncJson = syncJson,
                                                requestId = requestId,
                                                runId = msgRunId,
                                                onComplete = {
                                                    processedSyncCompleteCount = currentIndex + 1
                                                    // CRITICAL FIX: Update WebSocketService state with progress
                                                    // This ensures notification shows correct progress (e.g., "Initializing (3/9)")
                                                    net.vrkknn.andromuks.WebSocketService.updateInitializingProgress(
                                                        pendingCount = queuedMessages.size,
                                                        processedCount = currentIndex + 1
                                                    )
                                                    if (BuildConfig.DEBUG) {
                                                        android.util.Log.d("Andromuks", "🟣 onInitComplete: Message ${currentIndex + 1} processing complete callback invoked - Updated state: Initializing (${currentIndex + 1}/${queuedMessages.size})")
                                                    }
                                                }
                                            )
                                        } ?: run {
                                            android.util.Log.w("Andromuks", "🟣 onInitComplete: Message ${currentIndex + 1} timed out after 30 seconds - continuing to next message")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Andromuks", "🟣 onInitComplete: CRASH while processing message ${currentIndex + 1}/${queuedMessages.size} - ${e.message}", e)
                                        // Continue processing remaining messages even if one fails
                                    }
                                    
                                    // CRITICAL FIX: Unblock UI strategically based on message content
                                    // 1. If first message has clear_state=true (has spaces info), unblock immediately after processing it
                                    // 2. Otherwise, unblock after processing 2 messages (~200 rooms)
                                    // This ensures UI shows with spaces info ASAP while remaining rooms load in background
                                    if (!hasUnblockedUI) {
                                        val shouldUnblock = when {
                                            hasSpacesInfo -> {
                                                // First message with clear_state=true - has spaces info, unblock immediately
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: First message has clear_state=true with spaces info - will unblock after processing")
                                                true
                                            }
                                            (currentIndex + 1) >= 2 -> {
                                                // Processed 2 messages (~200 rooms) - enough to show UI
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: Processed 2 messages (~200 rooms) - will unblock UI")
                                                true
                                            }
                                            else -> false
                                        }
                                        
                                        if (shouldUnblock) {
                                            hasUnblockedUI = true
                                            // CRITICAL: Don't set initialSyncProcessingComplete=true here - wait for ALL messages to be processed
                                            // This allows UI to show early with partial data, but checkStartupComplete() will wait for all processing
                                            // CRITICAL: Ensure profile is loaded before early unblock
                                            ensureCurrentUserProfileLoaded()
                                            withContext(Dispatchers.Main) {
                                                val roomCount = roomMap.size
                                                val reason = if (hasSpacesInfo) "first message with clear_state=true (spaces info)" else "processed 2 messages (~200 rooms)"
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: EARLY UNBLOCK - $reason, have $roomCount rooms - Setting initialSyncComplete=true to unblock UI (processing continues in background)")
                                                initialSyncComplete = true
                                                spacesLoaded = true
                                                // CRITICAL FIX: Allow commands immediately on early unblock
                                                // Don't wait for all room states - bridge badges can load in background
                                                canSendCommandsToBackend = true
                                                flushPendingCommandsQueue()
                                                // Don't call checkStartupComplete() here - it will wait for initialSyncProcessingComplete
                                                // This ensures UI shows early but room list waits for all processing
                                                android.util.Log.d("Andromuks", "🟣 onInitComplete: EARLY UNBLOCK - UI can show early, but room list will wait for all ${queuedMessages.size} messages to process")
                                            }
                                        }
                                    }
                                    
                                    // PERFORMANCE FIX: Yield every chunkSize messages to prevent ANR
                                    // This allows the UI thread to remain responsive during heavy reconnect processing
                                    if ((index + 1) % chunkSize == 0) {
                                        kotlinx.coroutines.yield()
                                    }
                                }
                                
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Finished processing all ${queuedMessages.size} initial sync_complete messages")
                                }
                                
                                // CRITICAL FIX: Ensure initialSyncComplete is set even if early unblock didn't trigger (e.g., < 3 messages)
                                // This is a safety net to ensure UI is always unblocked
                                if (!hasUnblockedUI) {
                                    // CRITICAL: Ensure profile is loaded before marking processing complete
                                    ensureCurrentUserProfileLoaded()
                                    initialSyncProcessingComplete = true
                                    withContext(Dispatchers.Main) {
                                        android.util.Log.d("Andromuks", "🟣 onInitComplete: Finished processing ${queuedMessages.size} messages (early unblock didn't trigger) - Setting initialSyncComplete=true, spacesLoaded=true, processingComplete=true")
                                        initialSyncComplete = true
                                        spacesLoaded = true
                                        // CRITICAL FIX: Allow commands immediately after initial sync completes
                                        // Don't wait for all room states - bridge badges can load in background
                                        canSendCommandsToBackend = true
                                        flushPendingCommandsQueue()
                                        checkStartupComplete() // Check if startup is complete
                                        android.util.Log.d("Andromuks", "🟣 onInitComplete: COMPLETE - initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, spacesLoaded=$spacesLoaded, profile=${currentUserProfile != null} - UI can now be shown")
                                    }
                                }
                            } else {
                                // Queue was empty - set flags immediately
                                android.util.Log.d("Andromuks", "🟣 onInitComplete: No queued messages - setting initialSyncComplete immediately")
                                // CRITICAL: Ensure profile is loaded before marking processing complete
                                ensureCurrentUserProfileLoaded()
                                initialSyncProcessingComplete = true
                                withContext(Dispatchers.Main) {
                                    android.util.Log.d("Andromuks", "🟣 onInitComplete: Setting initialSyncComplete=true, spacesLoaded=true, processingComplete=true (empty queue)")
                                    initialSyncComplete = true
                                    spacesLoaded = true
                                    
                                    // CRITICAL FIX: Allow commands immediately after initial sync completes
                                    // Don't wait for all room states - bridge badges can load in background
                                    canSendCommandsToBackend = true
                                    flushPendingCommandsQueue()
                                    
                                    checkStartupComplete() // Check if startup is complete
                                    android.util.Log.d("Andromuks", "🟣 onInitComplete: COMPLETE - initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, spacesLoaded=$spacesLoaded, profile=${currentUserProfile != null}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error processing initial sync_complete messages: ${e.message}", e)
                    } finally {
                        // CRITICAL FIX: Mark initial sync processing as complete immediately after processing ALL messages
                        // Don't wait for summary update jobs - they run in background and update UI as they complete
                        // Room data is already parsed and in memory, so UI can be shown immediately
                        // CRITICAL: Ensure profile is loaded before marking processing complete
                        ensureCurrentUserProfileLoaded()
                        withContext(Dispatchers.Main) {
                        // CRITICAL FIX: Set all state flags on Main thread to ensure proper visibility
                        // Set initialSyncProcessingComplete FIRST on Main thread to ensure checkStartupComplete() sees it
                        initialSyncProcessingComplete = true
                        
                        // CRITICAL FIX: If early unblock didn't set initialSyncComplete, set it now
                        // If early unblock already set it, just ensure spacesLoaded is set
                        if (!initialSyncComplete) {
                            android.util.Log.d("Andromuks", "🟣 Initial sync processing: Setting initialSyncComplete=true, spacesLoaded=true, processingComplete=true")
                            initialSyncComplete = true
                            spacesLoaded = true
                        } else {
                            // Early unblock already set initialSyncComplete, just ensure spacesLoaded is set
                            if (!spacesLoaded) {
                                spacesLoaded = true
                            }
                            android.util.Log.d("Andromuks", "🟣 Initial sync processing: All messages processed - processingComplete=true (early unblock already set initialSyncComplete)")
                        }
                        
                        // CRITICAL FIX: Allow commands immediately after initial sync completes
                        // Don't wait for all room states - bridge badges can load in background
                        if (!canSendCommandsToBackend) {
                            canSendCommandsToBackend = true
                            flushPendingCommandsQueue()
                        }
                        // This ensures bridge badges are loaded before other commands can be sent
                        
                        // CRITICAL FIX: Mark initialization as complete - from now on, all sync_complete messages are real-time updates
                        // Set this on Main thread to ensure proper visibility
                        initializationComplete = true
                        
                        // CRITICAL FIX: Call checkStartupComplete() AFTER all state is set on Main thread
                        // This ensures proper visibility and prevents race conditions
                        checkStartupComplete()
                        android.util.Log.d("Andromuks", "🟣 Initial sync processing: COMPLETE - initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, spacesLoaded=$spacesLoaded, profile=${currentUserProfile != null}, isStartupComplete=$isStartupComplete - Room list can now be shown")
                        }
                    }
                    
                    // CRITICAL FIX: Load all room states AFTER all sync processing is complete
                    // Runs in this coroutine after try/catch/finally so roomMap is populated
                    // Request get_room_state for ALL rooms after sync_complete processing
                    // This ensures bridge badges are loaded
                    loadAllRoomStatesAfterInitComplete()
                }
        
        // CRITICAL FIX: Don't allow commands yet - wait for all room states to load first
        // canSendCommandsToBackend will be set after all room states are loaded in loadAllRoomStatesAfterInitComplete()
        
        // Note: initializationComplete is now set in the finally block on Main thread
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Initialization complete - future sync_complete messages will trigger UI updates")
        
        // Reset reconnection state now that init_complete has arrived
        // This is safe to call now because connection is confirmed healthy
        resetReconnectionState()
        
        // PHASE 4.2: Reset DNS failure count on successful connection
        resetDnsFailureCount()
        
        // PHASE 4.3: Reset TLS failure count on successful connection
        resetTlsFailureCount()
        
        // PHASE 4.3: Clear certificate error state on successful connection
        if (getCertificateErrorState()) {
            android.util.Log.i("Andromuks", "AppViewModel: Clearing certificate error state (connection succeeded)")
            setCertificateErrorState(false, null)
        }
        
        // Process all pending notification messages from FIFO buffer
        // WebSocket is now healthy (connected and init_complete received)
        messageSendCoordinator.processPendingNotificationMessages()
        
        // Now that all rooms are loaded, populate space edges
        addStartupProgressMessage("Processing space edges...")
        populateSpaceEdges()
        
        // Update ConversationsApi with the real homeserver URL and refresh shortcuts
        // This happens after init_complete when we have all the data we need
        if (realMatrixHomeserverUrl.isNotEmpty() && appContext != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updating ConversationsApi with real homeserver URL after init_complete")
            // Create new ConversationsApi instance with real homeserver URL
            conversationsApi = ConversationsApi(appContext!!, homeserverUrl, authToken, realMatrixHomeserverUrl)
            personsApi = PersonsApi(appContext!!, homeserverUrl, authToken, realMatrixHomeserverUrl)
            // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not on every sync_complete)
            // However, refresh shortcuts once on startup to ensure they're up-to-date with current room state
            if (!shortcutsRefreshedOnStartup) {
                refreshShortcutsOnStartup()
                shortcutsRefreshedOnStartup = true
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping shortcut update on init_complete - shortcuts already refreshed on startup")
            }
        }
        
        // FCM registration with Gomuks Backend will be triggered via callback when token is ready
        // This ensures we don't try to register before the FCM token is available
        
        // Execute any pending notification actions now that websocket is ready
        executePendingNotificationActions()
        
        // Register FCM on every WebSocket connection to ensure backend has current token
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: onInitComplete - registering FCM to ensure backend has current token")
        registerFCMWithGomuksBackend(forceRegistrationOnConnect = true)
        
        // QUEUE FLUSHING FIX: Flush pending queue after init_complete with stabilization delay
        // This ensures backend is ready and prevents triple-sending
        flushPendingQueueAfterReconnection()
        
        // CRITICAL FIX: Force refresh room list after reconnection to ensure data is up-to-date
        // This handles cases where initial sync queue was empty or rooms weren't properly updated
        viewModelScope.launch(Dispatchers.Default) {
            delay(500) // Small delay to let queued messages process
            
            // Force room list update
            forceRoomListSort()
            
            // Trigger UI update
            withContext(Dispatchers.Main) {
                roomListUpdateCounter++
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Forced room list refresh after init_complete")
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Calling navigation callback (callback is ${if (onNavigateToRoomList != null) "set" else "null"})")
        
        // Only trigger navigation callback once to prevent double navigation
        if (!navigationCallbackTriggered) {
            if (onNavigateToRoomList != null) {
                navigationCallbackTriggered = true
                onNavigateToRoomList?.invoke()
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
                pendingNavigation = true
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Navigation callback already triggered, skipping")
        }
    }
    
    /**
     * Executes any pending notification actions after init_complete
     * 
     * DEDUPLICATION: Removes duplicate actions before executing to prevent duplicate sends
     * when WebSocket was not ready and multiple broadcasts were queued.
     */
    private fun executePendingNotificationActions() {
        if (pendingNotificationActions.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending notification actions to execute")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Executing ${pendingNotificationActions.size} pending notification actions")
        
        // DEDUPLICATION: Remove duplicate actions before executing
        // For send_message actions, keep only the first occurrence of each unique (roomId, text) pair
        val deduplicatedActions = mutableListOf<PendingNotificationAction>()
        val seenSendMessages = mutableSetOf<String>() // "roomId|text"
        
        for (action in pendingNotificationActions) {
            when (action.type) {
                "send_message" -> {
                    if (action.text != null) {
                        val dedupKey = "${action.roomId}|${action.text}"
                        if (seenSendMessages.add(dedupKey)) {
                            // First occurrence - keep it
                            deduplicatedActions.add(action)
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Keeping queued send_message (roomId: ${action.roomId}, text: '${action.text}')")
                        } else {
                            // Duplicate - skip it
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping duplicate queued send_message (roomId: ${action.roomId}, text: '${action.text}')")
                            // Still call completion callback to prevent UI stalling
                            action.onComplete?.invoke()
                        }
                    }
                }
                else -> {
                    // Non-send_message actions - keep all (no deduplication needed)
                    deduplicatedActions.add(action)
                }
            }
        }
        
        val removedCount = pendingNotificationActions.size - deduplicatedActions.size
        if (removedCount > 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed $removedCount duplicate actions from queue")
        }
        
        pendingNotificationActions.clear()
        
        deduplicatedActions.forEach { action ->
            when (action.type) {
                "send_message" -> {
                    if (action.text != null) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Executing pending send_message for room ${action.roomId}")
                        // Note: sendMessageFromNotification will also check deduplication, but this prevents
                        // duplicate queue entries from being processed
                        sendMessageFromNotification(action.roomId, action.text, action.onComplete)
                    }
                }
                "mark_read" -> {
                    if (action.eventId != null) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Executing pending mark_read for room ${action.roomId}")
                        markRoomAsReadFromNotification(action.roomId, action.eventId, action.onComplete)
                    }
                }
            }
        }
    }
    
    /**
     * Stores space edges for later processing after init_complete
     */
    fun storeSpaceEdges(spaceEdges: JSONObject) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Storing space edges for later processing")
        storedSpaceEdges = spaceEdges
        // CRITICAL: Also update singleton cache so space_edges persist across ViewModel instances
        SpaceListCache.setSpaceEdges(spaceEdges)
        
        // Register edge keys as space IDs so filtering remains accurate even before processing edges.
        val edgeIds = mutableSetOf<String>()
        val edgeKeys = spaceEdges.keys()
        while (edgeKeys.hasNext()) {
            val id = edgeKeys.next()
            if (!id.isNullOrBlank()) {
                edgeIds.add(id)
            }
        }
        if (edgeIds.isNotEmpty()) {
            registerSpaceIds(edgeIds)
        }
        
        // Space edges are processed after roomMap is updated in processParsedSyncResult()
        // (or on init_complete in onInitComplete), so we don't run here with stale data.rooms.
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Populates space edges in background after init_complete
     * This prevents 50-100ms blocking during app initialization with nested spaces
     * 
     * Process:
     * 1. Create mock sync data on background thread (JSON operations are expensive)
     * 2. Process space edges in background (parsing and filtering)
     * 3. Update UI on main thread (minimal state change)
     */
    internal fun populateSpaceEdges() {
        if (storedSpaceEdges == null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No stored space edges to populate")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Starting background space edge processing for ${allSpaces.size} spaces")
        
        // PERFORMANCE: Move JSON creation and processing to background thread
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Create a mock sync data object with the stored space edges (background thread)
                val mockSyncData = JSONObject()
                
                // Create rooms object from allRooms data (expensive JSON operations)
                val roomsObject = JSONObject()
                
                // Add all rooms to the mock sync data
                for (room in allRooms) {
                    val roomData = JSONObject()
                    val meta = JSONObject()
                    meta.put("name", room.name)
                    if (room.avatarUrl != null) {
                        meta.put("avatar", room.avatarUrl)
                    }
                    if (room.unreadCount != null) {
                        meta.put("unread_messages", room.unreadCount)
                    }
                    roomData.put("meta", meta)
                    roomsObject.put(room.id, roomData)
                }
                
                mockSyncData.put("rooms", roomsObject)
                mockSyncData.put("space_edges", storedSpaceEdges)
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background space edge processing - Created mock data for ${allRooms.size} rooms")
                
                // Process space edges in background (parsing is expensive)
                net.vrkknn.andromuks.utils.SpaceRoomParser.updateExistingSpacesWithEdges(
                    storedSpaceEdges!!, 
                    mockSyncData, 
                    this@AppViewModel
                )
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background space edge processing - Completed processing")
                
                // Switch to main thread for UI update
                withContext(Dispatchers.Main) {
                    // Clear stored space edges on main thread (atomic state change)
                    storedSpaceEdges = null
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Space edge processing complete - UI updated")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error processing space edges in background", e)
                // Clear stored space edges even on error to prevent retry
                withContext(Dispatchers.Main) {
                    storedSpaceEdges = null
                }
            }
        }
    }
    
    // Navigation callback
    var onNavigateToRoomList: (() -> Unit)? = null
    private var pendingNavigation = false
    private var navigationCallbackTriggered = false // Prevent multiple triggers
    
    // Pending room navigation from shortcuts
    internal var pendingRoomNavigation: String? = null
    
    // Track if the pending navigation is from a notification (for optimized cache handling)
    internal var isPendingNavigationFromNotification: Boolean = false
    
    // OPTIMIZATION #1: Direct room navigation (bypasses pending state)
    internal var directRoomNavigation: String? = null
    internal var directRoomNavigationTimestamp: Long? = null
    // Observable trigger so RoomListScreen can react when onNewIntent sets a new
    // directRoomNavigation on an already-composed screen.
    var directRoomNavigationTrigger by mutableIntStateOf(0)
        internal set
    
    // User info navigation (for matrix:u/ URIs from contacts)
    private var pendingUserInfoNavigation: String? = null
    var pendingUserInfoNavigationTrigger by mutableIntStateOf(0)
        private set
    
    // Pending bubble navigation from chat bubbles
    private var pendingBubbleNavigation: String? = null

    // Pending highlight targets (e.g., notification taps)
    internal val pendingHighlightEvents = ConcurrentHashMap<String, String>()
    
    // Websocket restart callback
    var onRestartWebSocket: ((ReconnectTrigger) -> Unit)? = null
    
    // App lifecycle state
    var isAppVisible by mutableStateOf(true)
        internal set
    
    // Delayed shutdown job for when app becomes invisible
    internal var appInvisibleJob: Job? = null
    
    fun setNavigationCallback(callback: () -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Navigation callback set")
        onNavigateToRoomList = callback
        
        // If we have a pending navigation, trigger it now
        if (pendingNavigation) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Triggering pending navigation")
            pendingNavigation = false
            navigationCallbackTriggered = true
            callback()
        }
        // If spaces are already loaded (from cached state), DON'T trigger yet
        // Wait for WebSocket to connect and init_complete to trigger it
        else if (spacesLoaded && !navigationCallbackTriggered) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Spaces already loaded from cache, but waiting for WebSocket connection before navigating")
            // Don't trigger here - let onInitComplete() or WebSocket connection handle it
        }
    }
    
    fun setPendingRoomNavigation(roomId: String, fromNotification: Boolean = false) =
        navigationCoordinator.setPendingRoomNavigation(roomId, fromNotification)
    
    // OPTIMIZATION #1: Direct navigation method (bypasses pending state)
    fun setDirectRoomNavigation(
        roomId: String,
        notificationTimestamp: Long? = null,
        targetEventId: String? = null
    ) = navigationCoordinator.setDirectRoomNavigation(roomId, notificationTimestamp, targetEventId)
    
    fun getDirectRoomNavigation(): String? = navigationCoordinator.getDirectRoomNavigation()
    
    fun clearDirectRoomNavigation() = navigationCoordinator.clearDirectRoomNavigation()
    
    fun getDirectRoomNavigationTimestamp(): Long? = navigationCoordinator.getDirectRoomNavigationTimestamp()
    
    fun getPendingRoomNavigation(): String? = navigationCoordinator.getPendingRoomNavigation()
    
    fun clearPendingRoomNavigation() = navigationCoordinator.clearPendingRoomNavigation()
    
    fun setPendingUserInfoNavigation(userId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set pending user info navigation for: $userId")
        pendingUserInfoNavigation = userId
        pendingUserInfoNavigationTrigger++ // Notify observers
    }
    
    fun getPendingUserInfoNavigation(): String? {
        return pendingUserInfoNavigation
    }
    
    fun clearPendingUserInfoNavigation() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing pending user info navigation")
        pendingUserInfoNavigation = null
    }
    
    fun setPendingBubbleNavigation(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set pending bubble navigation to: $roomId")
        pendingBubbleNavigation = roomId
    }
    
    fun getPendingBubbleNavigation(): String? {
        val roomId = pendingBubbleNavigation
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Getting pending bubble navigation: $roomId")
        }
        return roomId
    }
    
    fun clearPendingBubbleNavigation() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing pending bubble navigation")
        pendingBubbleNavigation = null
    }

    fun setPendingHighlightEvent(roomId: String, eventId: String?) =
        navigationCoordinator.setPendingHighlightEvent(roomId, eventId)

    fun consumePendingHighlightEvent(roomId: String): String? =
        navigationCoordinator.consumePendingHighlightEvent(roomId)
    
    /**
     * Called when app becomes visible (foreground)
     */
    fun onAppBecameVisible() = viewModelLifecycleCoordinator.onAppBecameVisible()
    
    /**
     * Process pending items if any exist. This ensures RoomListScreen shows up-to-date data.
     * Called when app becomes visible or on startup.
     */
    internal fun processPendingItemsIfNeeded() {
        // CRITICAL: Only process if syncIngestor is initialized
        // Don't set flag to true if syncIngestor doesn't exist - no need to block UI
        val syncIngestorInstance = syncIngestor ?: run {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: syncIngestor not initialized, skipping pending items check")
            // Ensure flag is false if syncIngestor doesn't exist
            isProcessingPendingItems = false
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // DEPRECATED: hasPendingItems and rushProcessPendingItems are deprecated - all data is in-memory only
                // No pending items to process - UI can show immediately
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending items to process (deprecated - all data is in-memory only) - not blocking UI")
                isProcessingPendingItems = false
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error in pending items check: ${e.message}", e)
            } finally {
                // Clear processing flag after completion (or error) - CRITICAL: Always clear!
                isProcessingPendingItems = false
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared isProcessingPendingItems flag (finally block)")
            }
        }
    }
    
    /**
     * Ensure current user profile is loaded. Tries cache first, then requests from server.
     * Called when app becomes visible or on startup.
     * Made public so RoomListScreen can call it directly on cold start.
     */
    fun ensureCurrentUserProfileLoaded() {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: START - currentUserProfile=${currentUserProfile != null}, currentUserId=$currentUserId")
        }
        if (currentUserProfile == null && currentUserId.isNotBlank()) {
            // CRITICAL FIX: Check ProfileCache singleton first before requesting from server
            // This ensures currentUserProfile is populated even if profile was loaded in another AppViewModel instance
            val cachedProfile = ProfileCache.getGlobalProfileProfile(currentUserId)
            if (cachedProfile != null) {
                // Profile exists in singleton cache - populate currentUserProfile from cache
                currentUserProfile = UserProfile(
                    userId = currentUserId,
                    displayName = cachedProfile.displayName,
                    avatarUrl = cachedProfile.avatarUrl
                )
                persistCurrentUserAvatarMxcIfChanged(cachedProfile.avatarUrl)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: Populated from cache - userId: $currentUserId, displayName: ${cachedProfile.displayName}")
                }
                // Trigger checkStartupComplete since profile is now loaded
                checkStartupComplete()
                return
            }
            
            // Profile not in cache - request from server
            if (appContext != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: Profile missing from cache, requesting from server - userId: $currentUserId")
                }
                requestUserProfile(currentUserId)
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "🟣 ensureCurrentUserProfileLoaded: appContext is null, cannot request profile")
                }
            }
        } else {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "🟣 ensureCurrentUserProfileLoaded: Profile already loaded or userId is blank - currentUserProfile=${currentUserProfile != null}, currentUserId=$currentUserId")
            }
        }
    }
    
    /**
     * Lightweight version for chat bubbles - sets visibility without expensive UI refresh
     * Bubbles don't need to update shortcuts or refresh the room list
     */
    fun setBubbleVisible(visible: Boolean) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Bubble visibility set to $visible (lightweight)")
        
        if (visible) {
            if (currentRoomId.isEmpty()) {
                val roomToRestore = pendingRoomToRestore
                if (!roomToRestore.isNullOrEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restoring bubble room to $roomToRestore after becoming visible")
                    updateCurrentRoomIdInPrefs(roomToRestore)
                }
            }
            pendingRoomToRestore = null
            // Cancel any pending shutdown
            appInvisibleJob?.cancel()
            appInvisibleJob = null
            
            // If a room is currently open, trigger timeline refresh to show new events from cache
            if (currentRoomId.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room is open ($currentRoomId), triggering timeline refresh for bubble")
                timelineRefreshTrigger++
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Bubble hidden - notifications remain enabled for $currentRoomId"
            )
        }
        // Don't call refreshUIState() - bubbles don't need room list updates or shortcut updates
    }
    
    fun attachToExistingWebSocketIfAvailable() {
        val existingWebSocket = WebSocketService.getWebSocket()
        if (existingWebSocket != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Attaching $viewModelId to existing WebSocket")
            logActivity("Attached to Existing WebSocket", null)
            // REFACTORING: Service owns WebSocket - just register callbacks, no local storage needed
            WebSocketService.registerReceiveCallback(viewModelId, this)
            
            // STEP 2.2: Check if this ViewModel was promoted to primary while it wasn't attached
            val currentPrimaryId = WebSocketService.getPrimaryViewModelId()
            if (currentPrimaryId == viewModelId && instanceRole != InstanceRole.PRIMARY) {
                android.util.Log.i("Andromuks", "AppViewModel: STEP 2.2 - ViewModel $viewModelId was promoted to primary while detached - handling promotion now")
                onPromotedToPrimary()
            } else {
                // STEP 2.1: Register this ViewModel with service (as secondary, if not already primary)
                if (instanceRole != InstanceRole.PRIMARY) {
                    WebSocketService.registerViewModel(viewModelId, isPrimary = false)
                }
            }
            
            // CRITICAL FIX: If WebSocket is connected (CONNECTED state), it means init_complete was already received
            // Mark initial sync as complete immediately to prevent UI from waiting indefinitely
            // This fixes the "Loading rooms..." stall when opening from notification or app icon
            // when the app/foreground service has been running for a while
            if (isWebSocketConnected()) {
                // WebSocket is in CONNECTED state, which means init_complete was already received
                // (connectionState is only set to CONNECTED in onInitCompleteReceived)
                // Mark initialization and initial sync as complete - don't wait for these flags
                // because this might be a new AppViewModel instance that doesn't have these flags set
                // CRITICAL: When attaching to existing WebSocket, all processing is already complete
                // Ensure profile is loaded before marking processing complete
                ensureCurrentUserProfileLoaded()
                initialSyncPhase = true
                initialSyncProcessingComplete = true
                android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Setting initializationComplete=true, initialSyncComplete=true, processingComplete=true (already-initialized WebSocket)")
                initializationComplete = true  // CRITICAL: init_complete was already received by primary instance
                initialSyncComplete = true
                android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: initializationComplete=$initializationComplete, initialSyncComplete=$initialSyncComplete, processingComplete=$initialSyncProcessingComplete, profile=${currentUserProfile != null}")
                
                // CRITICAL FIX: Populate roomMap from cache when attaching to existing WebSocket
                // This ensures the new AppViewModel instance has room data from previous instances
                populateRoomMapFromCache()
                populateSpacesFromCache()
                
                // CRITICAL FIX: Set spacesLoaded if we have rooms (populateRoomMapFromCache already does this, but ensure it's set)
                if (roomMap.isNotEmpty() && !spacesLoaded) {
                    spacesLoaded = true
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Set spacesLoaded=true (have ${roomMap.size} rooms)")
                }
                
                // CRITICAL FIX: Load account_data from singleton cache when attaching to existing WebSocket
                // This ensures secondary instances (e.g., opened from Contacts) can access account_data
                // that was loaded by the primary instance
                val cachedAccountData = AccountDataCache.getAllAccountData()
                if (cachedAccountData.isNotEmpty()) {
                    val accountDataJson = JSONObject()
                    cachedAccountData.forEach { (key, value) ->
                        accountDataJson.put(key, value)
                    }
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: Loading ${cachedAccountData.size} account_data types from cache (m.direct=${AccountDataCache.hasAccountData("m.direct")})")
                    }
                    syncRoomsCoordinator.processAccountData(accountDataJson)
                } else {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: No account_data in cache - will use fallback room scanning for DM detection")
                    }
                }
                
                // CRITICAL FIX: When attaching to existing WebSocket, room states have already been loaded by the primary instance
                // Set allRoomStatesLoaded = true to allow startup completion
                if (!allRoomStatesLoaded) {
                    allRoomStatesLoaded = true
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Set allRoomStatesLoaded=true (room states already loaded by primary instance)")
                }
                
                // CRITICAL FIX: Allow commands to be sent when attaching to existing WebSocket
                // The WebSocket is already connected and initialized, so commands should be allowed
                if (!canSendCommandsToBackend) {
                    canSendCommandsToBackend = true
                    flushPendingCommandsQueue()
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Set canSendCommandsToBackend=true (WebSocket already initialized)")
                }
                
                // CRITICAL FIX: Ensure requestIdCounter is synchronized with the primary instance
                // The primary instance may have incremented requestIdCounter, so we should sync it
                // However, we can't easily get the current counter from the service, so we'll just ensure
                // we start from a reasonable value (the service manages the actual counter)
                // Note: requestIdCounter is per-ViewModel, so this is fine
                
                // CRITICAL FIX: Check if startup is complete now that we have room data
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: Checking startup complete - initializationComplete=$initializationComplete, initialSyncComplete=$initialSyncComplete, spacesLoaded=$spacesLoaded, allRoomStatesLoaded=$allRoomStatesLoaded, canSendCommands=$canSendCommandsToBackend, roomMap.size=${roomMap.size}")
                checkStartupComplete()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "🟣 Attaching to WebSocket: After checkStartupComplete - isStartupComplete=$isStartupComplete")
                
                // If spaces are loaded, trigger navigation immediately
                if (spacesLoaded && !navigationCallbackTriggered) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Spaces loaded and WebSocket connected - triggering navigation callback immediately")
                navigationCallbackTriggered = true
                onNavigateToRoomList?.invoke()
            }
            }
            // CRITICAL FIX: Don't set initialSyncComplete = true if WebSocket is not connected
            // Even if spacesLoaded is true, we should wait for the connection to be established
            // and receive init_complete. Setting it to true here would cause a flash:
            // rooms show briefly, then setWebSocket() resets it to false, then "Loading rooms..." appears
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No existing WebSocket to attach for $viewModelId")
            
            // STEP 2.1: Still register ViewModel even if WebSocket doesn't exist (for tracking)
            if (instanceRole != InstanceRole.PRIMARY) {
                WebSocketService.registerViewModel(viewModelId, isPrimary = false)
            }
            
            // CRITICAL FIX: Don't set initialSyncComplete = true here, even if spacesLoaded is true
            // On cold start, a new WebSocket will be created, which will reset initialSyncComplete to false
            // Setting it to true here would cause a flash: rooms show briefly, then "Loading rooms..." appears
            // We should only set initialSyncComplete = true when attaching to an existing, already-initialized WebSocket
            // For cold starts, wait for the new WebSocket to connect and receive init_complete
        }
    }
    
    /**
     * Check for pending items on app startup and process them.
     * Called from MainActivity.onCreate to ensure fresh data before showing RoomListScreen.
     */
    fun checkAndProcessPendingItemsOnStartup(context: Context) {
        // Initialize syncIngestor if not already initialized (with LRU cache listener)
        ensureSyncIngestor()
        // Process pending items if any exist (async - won't block)
        processPendingItemsIfNeeded()
    }
    
    /**
     * Refreshes UI state when app becomes visible
     * This updates the UI with any changes that happened while app was in background
     */
    internal fun refreshUIState() {
        // CRITICAL FIX: Don't rebuild allRooms from roomMap - it may have stale unread counts.
        // We now treat in-memory state as the single source of truth.
        
        val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing UI with ${sortedRooms.size} rooms")
        
        
        // PERFORMANCE: Use debounced reordering for UI refresh too
        scheduleRoomReorder()
        allRooms = sortedRooms
        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
        
        // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not in refreshUIState)
        // This drastically reduces shortcut updates. Removed shortcut updates from refreshUIState.
        
        // Always update persons API (needed for conversation bubbles)
        personsApi?.updatePersons(buildDirectPersonTargets(sortedRooms))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: UI refreshed, roomListUpdateCounter: $roomListUpdateCounter")
    }
    
    /**
     * Lightweight refresh of the UI from cached data without restarting WebSocket
     * This should be used when app comes to foreground to update the room list from
     * any cached sync events received while the app was in background
     */
    fun refreshUIFromCache() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing UI from cached data")
        refreshUIState()
    }
    
    /**
     * Lightweight timeline refresh that triggers UI update from cached timeline data
     * This should be used when app comes to foreground to refresh the timeline view
     * without making new network requests
     */
    fun refreshTimelineUI() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing timeline UI from cached data")
        timelineRefreshTrigger++
    }
    
    /**
     * Called when app becomes invisible (background/standby)
     */
    fun onAppBecameInvisible() = viewModelLifecycleCoordinator.onAppBecameInvisible()
    
    /**
     * Manually triggers app suspension (for back button from room list).
     * 
     * This function is called when the user presses the back button from the room list screen.
     * With the foreground service, we just save state but keep the WebSocket open.
     */
    fun suspendApp() = viewModelLifecycleCoordinator.suspendApp()
    
    override fun onCleared() {
        super.onCleared()
        viewModelLifecycleCoordinator.onCleared()
    }

    fun getRoomById(roomId: String): RoomItem? {
        return roomMap[roomId]
    }
    
    /**
     * Get all rooms that have canonical aliases (for room mentions with #)
     * Returns a list of pairs: (RoomItem, canonicalAlias)
     * Uses canonical alias from RoomItem (extracted from sync_complete meta) for efficiency
     */
    fun getRoomsWithCanonicalAliases(): List<Pair<RoomItem, String>> {
        val allRoomsList = allRooms.ifEmpty { roomMap.values.toList() }
        
        return allRoomsList
            .filter { it.canonicalAlias != null && it.canonicalAlias.isNotBlank() }
            .map { Pair(it, it.canonicalAlias!!) }
            .sortedBy { it.first.name }
    }
    
    // Room timeline state
    var currentRoomId by mutableStateOf("")
        private set
    internal var pendingRoomToRestore: String? = null
    /**
     * Helper function to set currentRoomId and save it to SharedPreferences.
     * This allows notification services to check if a room is currently open.
     */
    internal fun updateCurrentRoomIdInPrefs(roomId: String) {
        if (roomId.isNotEmpty()) {
            pendingRoomToRestore = null
        }
        
        // Update typing users for the new room when switching
        val previousRoomId = currentRoomId
        currentRoomId = roomId
        
        // Update typingUsers to show typing users for the new room
        typingUsers = getTypingUsersForRoom(roomId)

        val shouldPersistForNotifications = instanceRole != InstanceRole.BUBBLE
        if (!shouldPersistForNotifications) {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Skipping SharedPreferences current room update for bubble instance (roomId=$roomId)"
            )
            return
        }

        // Save to SharedPreferences so notification service can check if room is open
        // Use commit() instead of apply() to ensure immediate write (critical for notification suppression)
        appContext?.applicationContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = sharedPrefs.edit()
            if (roomId.isNotEmpty()) {
                editor.putString("current_open_room_id", roomId)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saving current open room ID to SharedPreferences: $roomId")
            } else {
                editor.remove("current_open_room_id")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing current open room ID from SharedPreferences")
            }
            // Use commit() for synchronous write - critical to prevent race condition with notifications
            val success = editor.commit()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SharedPreferences commit ${if (success) "succeeded" else "failed"} for room ID: $roomId")
        }
    }
    
    internal fun updateAppVisibilityInPrefs(visible: Boolean) {
        appContext?.applicationContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = sharedPrefs.edit()
            editor.putBoolean("app_is_visible", visible)
            // Use commit() for synchronous write - critical for FCM notification suppression
            val success = editor.commit()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: SharedPreferences commit ${if (success) "succeeded" else "failed"} for app visibility: $visible (currentRoomId=$currentRoomId)"
            )
            
            // Verify the write was successful
            val verifyValue = sharedPrefs.getBoolean("app_is_visible", !visible)
            if (verifyValue != visible) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: WARNING - App visibility write verification failed! Expected: $visible, Got: $verifyValue"
                )
            }
        } ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot update app visibility - appContext is null")
        }
    }
    
    /**
     * Sets the current room ID when a timeline screen opens.
     * This should be called by RoomTimelineScreen when it opens to ensure state is consistent
     * across all navigation paths (RoomListScreen, notifications, shortcuts).
     * Also tracks the room as opened (exempt from cache clearing on WebSocket reconnect).
     */
    fun setCurrentRoomIdForTimeline(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setCurrentRoomIdForTimeline called for room: $roomId (current: $currentRoomId)")
        if (currentRoomId != roomId) {
            updateCurrentRoomIdInPrefs(roomId)
            // Add to opened rooms (exempt from cache clearing on reconnect)
            RoomTimelineCache.addOpenedRoom(roomId)
        }
    }
    
    /**
     * Clears the current room ID when user navigates back to room list.
     * This allows notifications to resume for rooms that were previously open.
     * 
     * PERFORMANCE FIX: Also clears in-memory timeline cache to free RAM.
     * Timeline will be rebuilt from cache or server when room is opened again.
     */
    fun clearCurrentRoomId(shouldRestoreOnVisible: Boolean = false, saveToCacheForRoomTimeline: Boolean = true) {
        val previousRoomId = currentRoomId
        if (shouldRestoreOnVisible && currentRoomId.isNotEmpty()) {
            pendingRoomToRestore = currentRoomId
        } else if (!shouldRestoreOnVisible) {
            pendingRoomToRestore = null
            // LRU CACHE: Save current room to cache before clearing (for RoomTimelineScreen quick-switch)
            // BubbleTimelineScreen passes saveToCacheForRoomTimeline=false since it manages its own cache
            if (saveToCacheForRoomTimeline && currentRoomId.isNotEmpty()) {
                timelineCacheCoordinator.saveToLruCache(currentRoomId)
            }
            if (previousRoomId.isNotEmpty()) {
                timelineCacheCoordinator.clearTimelineCache()
            }
            // Remove from opened rooms (no longer exempt from cache clearing)
            if (previousRoomId.isNotEmpty()) {
                RoomTimelineCache.removeOpenedRoom(previousRoomId)
            }
            // CRITICAL: Clear currentRoomState to prevent stale avatar showing during shared element transitions
            // Without this, opening room B after room A briefly shows room A's avatar in the header
            currentRoomState = null
        }
        updateCurrentRoomIdInPrefs("")
    }
    
    /**
     * Clear all timeline caches and mark all rooms as needing pagination.
     * Called on WebSocket connect/reconnect to ensure all caches are stale.
     */
    fun clearAllTimelineCaches() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Clearing all timeline caches (WebSocket connect/reconnect)")
        
        // Clear RoomTimelineCache (includes actively cached rooms tracking)
        RoomTimelineCache.clearAll()

        // Clear tracked oldest rowIds since caches are cleared
        oldestRowIdPerRoom.clear()
        
        // Clear pending paginate tracking since caches are cleared
        roomsWithPendingPaginate.clear()
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: All timeline caches cleared - all rooms marked as needing pagination")
    }

    var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
        internal set
    var isTimelineLoading by mutableStateOf(false)
        internal set

    /**
     * Get the set of room IDs that are actively cached and should receive events from sync_complete.
     * Used by SyncIngestor to determine if incoming events should trigger cache updates.
     * Returns rooms that are actively cached (have been opened and paginated).
     */
    fun getCachedRoomIds(): Set<String> {
        return RoomTimelineCache.getActivelyCachedRoomIds()
    }

    /**
     * Append new events to a cached room's timeline (for simple message appends).
     * Called by SyncIngestor when new events arrive for a cached room.
     * Returns true if events were appended, false if room not cached or needs full re-render.
     */
    fun appendEventsToCachedRoom(roomId: String, newEvents: List<TimelineEvent>) =
        timelineCacheCoordinator.appendEventsToCachedRoom(roomId, newEvents)
    
    /**
     * Invalidate processed timeline state for a specific room (e.g., when edits/redactions arrive).
     */
    fun invalidateCachedRoom(roomId: String) {
        RoomTimelineCache.clearProcessedTimelineState(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Invalidated processed timeline state for room $roomId")
    }
    
    // Trigger for timeline refresh when app resumes (incremented when app becomes visible)
    var timelineRefreshTrigger by mutableStateOf(0)
        internal set
    
    // Edit chain tracking system
    data class EventChainEntry(
        val eventId: String,
        var ourBubble: TimelineEvent?,
        var replacedBy: String?,
        val originalTimestamp: Long
    )
    
    internal val eventChainMap = mutableMapOf<String, EventChainEntry>()
    internal val editEventsMap = mutableMapOf<String, TimelineEvent>() // Store edit events separately
    internal val roomsPaginatedOnce = Collections.synchronizedSet(mutableSetOf<String>())
    // Track rooms that need timeline rebuild during batch processing (defer rebuild until batch completes)
    internal val roomsNeedingRebuildDuringBatch = Collections.synchronizedSet(mutableSetOf<String>())

    internal fun hasInitialPaginate(roomId: String) = timelineCacheCoordinator.hasInitialPaginate(roomId)

    internal fun markInitialPaginate(roomId: String, reason: String) =
        timelineCacheCoordinator.markInitialPaginate(roomId, reason)

    internal fun logSkippedPaginate(roomId: String, reason: String) =
        timelineCacheCoordinator.logSkippedPaginate(roomId, reason)
    private val roomSnapshotAwaiters = ConcurrentHashMap<String, MutableList<CompletableDeferred<Unit>>>()
    
    // Made public to allow access for RoomJoiner WebSocket operations
    var requestIdCounter = 1
        internal set
    
    fun getAndIncrementRequestId(): Int {
        val id = requestIdCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Generated request ID: $id (counter now: $requestIdCounter)")
        return id
    }
    
    private fun registerRoomSnapshotAwaiter(roomId: String, deferred: CompletableDeferred<Unit>) {
        synchronized(roomSnapshotAwaiters) {
            val list = roomSnapshotAwaiters[roomId] ?: mutableListOf<CompletableDeferred<Unit>>().also {
                roomSnapshotAwaiters[roomId] = it
            }
            list.add(deferred)
        }
    }
    
    private fun unregisterRoomSnapshotAwaiter(roomId: String, deferred: CompletableDeferred<Unit>) {
        synchronized(roomSnapshotAwaiters) {
            val list = roomSnapshotAwaiters[roomId] ?: return
            list.remove(deferred)
            if (list.isEmpty()) {
                roomSnapshotAwaiters.remove(roomId)
            }
        }
    }
    
    internal fun signalRoomSnapshotReady(roomId: String) {
        val awaiters = synchronized(roomSnapshotAwaiters) {
            roomSnapshotAwaiters.remove(roomId)?.toList()
        } ?: return
        
        for (awaiter in awaiters) {
            if (!awaiter.isCompleted && !awaiter.isCancelled) {
                awaiter.complete(Unit)
            }
        }
    }
    internal val timelineRequests = mutableMapOf<Int, String>() // requestId -> roomId
    // Track rooms with pending initial paginate requests to prevent duplicates
    internal val roomsWithPendingPaginate = Collections.synchronizedSet(mutableSetOf<String>())
    internal val profileRequestRooms = mutableMapOf<Int, String>() // requestId -> roomId (for profile requests initiated from a specific room)
    internal val roomStateRequests = mutableMapOf<Int, String>() // requestId -> roomId
    internal val messageRequests = mutableMapOf<Int, String>() // requestId -> roomId
    
    // PERFORMANCE: Track pending room state requests to prevent duplicate WebSocket commands
    internal val pendingRoomStateRequests = mutableSetOf<String>() // roomId that have pending state requests
    internal val reactionRequests = mutableMapOf<Int, String>() // requestId -> roomId
    // Track requests for get_related_events used to backfill detailed reactions (user + timestamp) for a specific event.
    // Key: requestId, Value: Pair(roomId, targetEventId)
    internal val relatedEventsRequests = mutableMapOf<Int, Pair<String, String>>()
    internal val markReadRequests = mutableMapOf<Int, String>() // requestId -> roomId
    // Track last sent mark_read command per room to prevent duplicates
    // Key: roomId, Value: eventId that was last sent
    internal val lastMarkReadSent = mutableMapOf<String, String>() // roomId -> eventId
    internal val readReceipts = mutableMapOf<String, MutableList<ReadReceipt>>() // eventId -> list of read receipts
    internal val readReceiptsLock = Any() // Synchronization lock for readReceipts access
    internal val roomsWithLoadedReceipts = mutableSetOf<String>() // Track rooms with receipts loaded from cache
    internal val roomsWithLoadedReactions = mutableSetOf<String>() // Track rooms with reactions loaded from cache
    // Track receipt movements for animation - userId -> (previousEventId, currentEventId, timestamp)
    // THREAD SAFETY: Protected by readReceiptsLock since it's accessed from background threads
    internal val receiptMovements = mutableMapOf<String, Triple<String?, String, Long>>()
    var receiptAnimationTrigger by mutableStateOf(0L)
        internal set
    
        // PERFORMANCE: Track new messages for sound notifications only (animations removed)
    // Use ConcurrentHashMap for thread-safe access (modified from background threads, read from UI thread)
    private val newMessageAnimations = ConcurrentHashMap<String, Long>() // eventId -> timestamp

    // EventIds that already ran timeline bubble entrance animation (LazyColumn recomposition would replay without this)
    private val timelineEntrancePlayed = ConcurrentHashMap.newKeySet<String>()

    fun hasTimelineEntrancePlayed(eventId: String): Boolean = timelineEntrancePlayed.contains(eventId)

    fun markTimelineEntrancePlayed(eventId: String) {
        timelineEntrancePlayed.add(eventId)
    }

    // CRITICAL: Track when each room was opened (in milliseconds, Matrix timestamp format)
    // Only messages with timestamp NEWER than this will animate
    // This ensures paginated (old) messages don't animate, only truly new messages do
    internal var roomOpenTimestamps = mutableMapOf<String, Long>() // roomId -> openTimestamp
    // Now using singleton PendingInvitesCache
    private val pendingInvites: Map<String, RoomInvite>
        get() = PendingInvitesCache.getAllInvites()
    
    private fun removePendingInvite(roomId: String) {
        PendingInvitesCache.removeInvite(roomId)
    }
    
    private fun clearPendingInvites() {
        PendingInvitesCache.clear()
    }
    private val roomSummaryRequests = mutableMapOf<Int, String>() // requestId -> roomId
    internal val joinRoomRequests = mutableMapOf<Int, String>() // requestId -> roomId
    internal val leaveRoomRequests = mutableMapOf<Int, String>() // requestId -> roomId
    internal val outgoingRequests = mutableMapOf<Int, String>() // requestId -> roomId (for all outgoing requests)
    internal val fcmRegistrationRequests = mutableMapOf<Int, String>() // requestId -> "fcm_registration"
    internal var lastFCMRegistrationTime: Long = 0 // Track last registration to prevent duplicates
    private val eventRequests = mutableMapOf<Int, Pair<String, (TimelineEvent?) -> Unit>>() // requestId -> (roomId, callback)
    private val eventContextRequests = mutableMapOf<Int, Pair<String, (List<TimelineEvent>?) -> Unit>>() // requestId -> (roomId, callback)
    internal val paginateRequests = mutableMapOf<Int, String>() // requestId -> roomId (for pagination)
    internal val paginateRequestMaxTimelineIds = mutableMapOf<Int, Long>() // requestId -> max_timeline_id used in request (for progress detection)
    internal val backgroundPrefetchRequests = mutableMapOf<Int, String>() // requestId -> roomId (for background prefetch)
    private val freshnessCheckRequests = mutableMapOf<Int, String>() // requestId -> roomId (for single-event freshness checks)
    private val roomStateWithMembersRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.RoomStateInfo?, String?) -> Unit>() // requestId -> callback
    internal val userEncryptionInfoRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    private val mutualRoomsRequests = mutableMapOf<Int, (List<String>?, String?) -> Unit>() // requestId -> callback
    internal val basicProfileCallbacks = mutableMapOf<Int, (MemberProfile?) -> Unit>() // requestId -> callback for direct get_profile consumers
    internal val trackDevicesRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    private val resolveAliasRequests = mutableMapOf<Int, (Pair<String, List<String>>?) -> Unit>() // requestId -> callback
    private val getRoomSummaryRequests = mutableMapOf<Int, (Pair<net.vrkknn.andromuks.utils.RoomSummary?, String?>?) -> Unit>() // requestId -> callback
    internal val joinRoomCallbacks = mutableMapOf<Int, (Pair<String?, String?>?) -> Unit>() // requestId -> callback
    private val roomSpecificStateRequests = mutableMapOf<Int, String>() // requestId -> roomId (for get_specific_room_state requests)
    private val roomSpecificProfileCallbacks = mutableMapOf<Int, (String?, String?) -> Unit>() // requestId -> (displayName, avatarUrl) callback
    internal val fullMemberListRequests = mutableMapOf<Int, String>() // requestId -> roomId (for get_room_state with include_members requests)
    private val mentionsRequests = mutableMapOf<Int, Unit>() // requestId -> Unit (for get_mentions requests)
    private val mentionEventRequests = mutableMapOf<Int, Pair<String, String>>() // requestId -> (roomId, eventId) for fetching reply targets
    
    // PERFORMANCE: Track pending full member list requests to prevent duplicate WebSocket commands
    internal val pendingFullMemberListRequests = mutableSetOf<String>() // roomId that have pending full member list requests

    // Element Call widget command tracking (requestId -> deferred response)
    internal val widgetCommandRequests = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    
    // OPPORTUNISTIC PROFILE LOADING: Track pending on-demand profile requests
    internal val pendingProfileRequests = mutableSetOf<String>() // global userId and "roomId:userId" keys for pending profile requests
    internal val profileRequests = mutableMapOf<Int, String>() // requestId -> userId (get_profile) or routed keys
    
    // CRITICAL FIX: Track profile request metadata for timeout handling and cleanup
    private data class ProfileRequestMetadata(
        val requestId: Int,
        val timestamp: Long,
        val userId: String,
        val roomId: String
    )
    private val profileRequestMetadata = mutableMapOf<String, ProfileRequestMetadata>() // "roomId:userId" -> metadata

    // BATCH: Queue on-demand profile requests and send one get_specific_room_state per room with multiple keys
    private val pendingProfileBatch = mutableMapOf<String, MutableSet<String>>() // roomId -> set of userIds
    private val batchProfileRequestKeys = mutableMapOf<Int, List<String>>() // requestId -> list of "roomId:userId" for cleanup
    private var profileBatchFlushJob: kotlinx.coroutines.Job? = null
    private val PROFILE_BATCH_DELAY_MS = 80L
    
    // Local echoes removed: status/error helpers no longer used.

    // PERFORMANCE: Throttle profile requests to prevent animation-blocking bursts
    // Tracks recent profile request timestamps to skip rapid re-requests during animation window
    private val recentProfileRequestTimes = mutableMapOf<String, Long>() // "roomId:userId" -> timestamp
    private val PROFILE_REQUEST_THROTTLE_MS = 5000L // Skip if requested within last 5 seconds
    internal val REACTION_BACKFILL_ON_OPEN_ENABLED = false
    private val AUTO_PAGINATION_ENABLED = false
    
    // Pagination state
    internal var smallestRowId: Long = -1L // Smallest rowId from initial paginate
    // Track the oldest POSITIVE timelineRowId from each pagination response per room
    // The oldest timeline event will have the lowest (smallest) POSITIVE timelineRowId
    // CRITICAL: Only positive timelineRowid values are stored (negative values are for state events and cannot be used for pagination)
    // This is used for the next pull-to-refresh to know where to start paginating from
    // Matches Webmucks backend behavior: pagination uses positive timeline_rowid values only
    internal val oldestRowIdPerRoom = mutableMapOf<String, Long>()
    var isPaginating by mutableStateOf(false)
        internal set
    var hasMoreMessages by mutableStateOf(true) // Whether there are more messages to load
        internal set
    var autoPaginationEnabled by mutableStateOf(false)
        private set
    
    fun setAutoPaginationEnabled(enabled: Boolean, reason: String? = null) {
        if (autoPaginationEnabled != enabled) {
            val reasonText = reason?.let { " ($it)" } ?: ""
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Auto-pagination ${if (enabled) "ENABLED" else "DISABLED"}$reasonText"
            )
            autoPaginationEnabled = enabled
        }
    }
    
    fun hasPendingTimelineRequest(roomId: String): Boolean {
        return timelineRequests.values.any { it == roomId }
    }
    
    
    // REFACTORING: WebSocket is now owned by WebSocketService - no local storage needed
    // All WebSocket operations delegate to WebSocketService.getWebSocket()
    private var lastReceivedRequestId: Int = 0 // Tracks ANY incoming request_id (for pong detection)
    // NOTE: We no longer track last_received_id - all timeline caches are cleared on connect/reconnect
    internal var lastSyncTimestamp: Long = 0 // Timestamp of last sync_complete received
    internal var currentRunId: String = "" // Unique connection ID from gomuks backend
    internal var vapidKey: String = "" // VAPID key for push notifications
    private var hasHadInitialConnection = false // Track if we've had an initial connection to only vibrate on reconnections

    // NETWORK OPTIMIZATION: Offline caching and connection state
    // STEP 1.2: Made internal so callbacks can update state without capturing AppViewModel
    internal var isOfflineMode = false
        private set
    internal var lastNetworkState = true // true = online, false = offline
        private set
    
    // STEP 1.2: Helper methods to update offline state (used by callbacks)
    internal fun setOfflineMode(isOffline: Boolean) {
        isOfflineMode = isOffline
        lastNetworkState = !isOffline
    }
    private val offlineCacheExpiry = 24 * 60 * 60 * 1000L // 24 hours
    
    // WebSocket reconnection log
    data class ActivityLogEntry(
        val timestamp: Long,
        val event: String,
        val networkType: String? = null
    ) {
        fun toJson(): org.json.JSONObject {
            val json = org.json.JSONObject()
            json.put("timestamp", timestamp)
            json.put("event", event)
            networkType?.let { json.put("networkType", it) }
            return json
        }
        
        companion object {
            fun fromJson(json: org.json.JSONObject): ActivityLogEntry {
                return ActivityLogEntry(
                    timestamp = json.getLong("timestamp"),
                    event = json.getString("event"),
                    networkType = json.optString("networkType").takeIf { it.isNotEmpty() }
                )
            }
        }
    }
    
    internal val activityLog = mutableListOf<ActivityLogEntry>()
    internal val activityLogLock = Any()

    fun logActivity(event: String, networkType: String? = null) =
        diagnosticsCoordinator.logActivity(event, networkType)

    fun getActivityLog(): List<ActivityLogEntry> = diagnosticsCoordinator.getActivityLog()

    // Backwards compatibility - keep old reconnection log methods
    data class ReconnectionLogEntry(
        val timestamp: Long,
        val reason: String
    )
    
    private val reconnectionLog = mutableListOf<ReconnectionLogEntry>()
    
    fun setWebSocket(webSocket: WebSocket) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setWebSocket() called for $viewModelId")
        addStartupProgressMessage("Connected to WebSocket")
        
        // REFACTORING: Service now owns WebSocket - no need to store locally
        // The service already has the reference, we just need to set up callbacks
        
        // Reset requestIdCounter on WebSocket (re)connect to keep IDs manageable
        requestIdCounter = 1
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Reset requestIdCounter to 1 on WebSocket (re)connect")
        
        // CRITICAL FIX: Initialize initial sync phase when WebSocket connects (both initial connection and reconnection)
        // Set initialSyncPhase = false to start tracking sync_complete messages before init_complete
        // This ensures we queue all initial sync_complete messages and process them after init_complete
        // On reconnection, the backend sends new sync_complete messages before init_complete, so we need to queue them again
        initialSyncPhase = false
        synchronized(initialSyncCompleteQueue) {
            initialSyncCompleteQueue.clear()
        }
        initialSyncProcessingComplete = false
        initialSyncComplete = false // Reset so UI waits for initial sync to complete again
        // Reset sync progress counters
        pendingSyncCompleteCount = 0
        processedSyncCompleteCount = 0
        
        // CRITICAL FIX: Block commands on initial connection only (not reconnections with last_received_event)
        // On reconnections with last_received_event, backend doesn't send init_complete, so we can't block
        val isReconnecting = WebSocketService.isReconnectingWithLastReceivedEvent()
        canSendCommandsToBackend = isReconnecting // Allow commands immediately on reconnection
        synchronized(pendingCommandsQueue) {
            pendingCommandsQueue.clear()
        }
        
        // Reset room state loading state
        allRoomStatesRequested = false
        allRoomStatesLoaded = isReconnecting // On reconnection, mark as loaded (we skip loading)
        totalRoomStateRequests = 0
        completedRoomStateRequests = 0
        synchronized(pendingRoomStateResponses) {
            pendingRoomStateResponses.clear()
        }
        
        if (BuildConfig.DEBUG) {
            if (isReconnecting) {
                android.util.Log.d("Andromuks", "AppViewModel: Reconnection detected - allowing commands immediately (no init_complete will be sent)")
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: Initial connection - blocking commands until init_complete + all sync_complete + all room states processed")
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Initial sync phase started - will queue sync_complete messages until init_complete")
        
        // CRITICAL FIX: Process deferred emoji pack requests when WebSocket is set
        // This ensures custom emojis are loaded even if account_data was processed before WebSocket connected
        if (deferredEmojiPackRequests.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${deferredEmojiPackRequests.size} deferred emoji pack requests after WebSocket connection")
            val requests = deferredEmojiPackRequests.toList()
            deferredEmojiPackRequests.clear()
            for ((roomId, packName) in requests) {
                requestEmojiPackData(roomId, packName)
            }
        }
        
        // PHASE 4: Register this ViewModel to receive WebSocket messages
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Registering $viewModelId to receive WebSocket messages")
        WebSocketService.registerReceiveCallback(viewModelId, this)
        
        // Set up service callbacks for ping/pong (using deprecated method for now)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting up service callbacks")
        @Suppress("DEPRECATION")
        WebSocketService.setWebSocketSendCallback { command, requestId, data ->
            sendWebSocketCommand(command, requestId, data) == WebSocketResult.SUCCESS
        }
        
        // PHASE 1.4 FIX: Primary callbacks are now registered in markAsPrimaryInstance()
        // This ensures callbacks are available before WebSocket connection is established
        // If callbacks weren't registered yet (shouldn't happen), register them now as fallback
        if (instanceRole == InstanceRole.PRIMARY) {
            val currentPrimaryId = WebSocketService.getPrimaryViewModelId()
            if (currentPrimaryId != viewModelId) {
                // Callbacks weren't registered yet - register them now as fallback
                android.util.Log.w("Andromuks", "AppViewModel: Primary callbacks not registered yet in setWebSocket() - registering now as fallback")
                viewModelLifecycleCoordinator.registerPrimaryCallbacks()
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Primary callbacks already registered for $viewModelId")
            }
        }
        
        // Delegate WebSocket management to service
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Calling WebSocketService.setWebSocket()")
        WebSocketService.setWebSocket(webSocket)
        
        // FCM registration will happen in onInitComplete() after WebSocket is fully ready
        // This prevents duplicate registrations from setWebSocket() and onInitComplete()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: setWebSocket - FCM registration will happen in onInitComplete()")
        
        // Broadcast that socket connection is available
        android.util.Log.i("Andromuks", "AppViewModel: WebSocket connection established - ${pendingWebSocketOperations.size} pending operations will be flushed after init_complete")
        
        // Track if we've had an initial connection (no longer needed for vibration)
        if (!hasHadInitialConnection) {
            hasHadInitialConnection = true
        }
        
        // QUEUE FLUSHING FIX: Don't flush queue here - wait for init_complete
        // This prevents triple-sending and ensures backend is ready before retrying
    }
    
    /**
     * RECONNECTION: Reset reconnection state after successful connection
     */
    fun resetReconnectionState() {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Resetting reconnection state (successful connection)")
        // Delegate to service
        WebSocketService.resetReconnectionState()
    }
    
    /**
     * RECONNECTION: Schedule WebSocket reconnection with exponential backoff
     * 
     * This method implements exponential backoff to avoid overwhelming the server
     * during outages and to be more battery efficient.
     * 
     * @param trigger Typed reason for reconnection (for logging and routing)
     */
    fun scheduleReconnection(trigger: ReconnectTrigger) {
        // PHASE 4.3: Don't reconnect if there's a certificate error (security issue)
        if (getCertificateErrorState()) {
            android.util.Log.w("Andromuks", "AppViewModel: Blocking reconnection attempt - certificate error state is active")
            logActivity("Reconnection Blocked - Certificate Error", null)
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Delegating reconnection scheduling to service ($trigger)")
        // Delegate to service
        WebSocketService.scheduleReconnection(trigger)
    }
    
    fun isWebSocketConnected(): Boolean {
        return WebSocketService.isWebSocketConnected()
    }

    fun isInitializationComplete(): Boolean {
        return initializationComplete
    }

    fun clearWebSocket(reason: String = "Unknown", closeCode: Int? = null, closeReason: String? = null) {
        // Reset per-connection ViewModel state, then delegate the actual socket teardown to the service.
        onWebSocketCleared(reason)
        WebSocketService.clearWebSocket(reason, closeCode, closeReason)
    }

    /**
     * Called by [WebSocketService.clearWebSocket] (on Main) whenever the active WebSocket is
     * torn down — whether by a network-type change, ping timeout, or any other trigger.
     *
     * This is the single place where the ViewModel resets its per-connection sync state.
     * It must NOT call back into WebSocketService (no clearWebSocket, no scheduleReconnection)
     * to avoid reentrancy. All reconnection logic lives in the service.
     */
    fun onWebSocketCleared(reason: String) {
        // Reset initialization flag — will be set again when init_complete arrives.
        if (initializationComplete) {
            initializationComplete = false
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket cleared - resetting initializationComplete (reason: $reason)")
        }

        // Reset initial sync state so we queue sync_completes fresh on the next connection.
        initialSyncPhase = false
        synchronized(initialSyncCompleteQueue) {
            initialSyncCompleteQueue.clear()
        }
        initialSyncProcessingComplete = false
        pendingSyncCompleteCount = 0
        processedSyncCompleteCount = 0

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: onWebSocketCleared - sync state reset (reason: $reason)")
        }
    }

    
    /**
     * PHASE 4.2: Handle connection failure with error-specific strategies
     * 
     * @param errorType Type of error: "DNS_FAILURE", "NETWORK_UNREACHABLE", or "GENERIC_ERROR"
     * @param error The original exception
     * @param reason Human-readable failure reason
     */
    fun handleConnectionFailure(errorType: String, error: Throwable, reason: String) {
        android.util.Log.w("Andromuks", "AppViewModel: Connection failure - Type: $errorType, Reason: $reason")
        logActivity("Connection Failure - $errorType: ${error.message}", null)
        
        // CRITICAL FIX: Check if network is available before scheduling reconnection
        // This prevents reconnection attempts when network is lost (WiFi turned off, etc.)
        val networkType = WebSocketService.getCurrentNetworkType()
        if (networkType == net.vrkknn.andromuks.WebSocketService.NetworkType.NONE) {
            android.util.Log.w("Andromuks", "AppViewModel: Connection failure but no network available - not scheduling reconnection (reason: $reason)")
            logActivity("Connection Failure - No Network Available", null)
            return
        }
        
        when (errorType) {
            "DNS_FAILURE" -> {
                // DNS failures are often persistent - use exponential backoff with longer delays
                android.util.Log.w("Andromuks", "AppViewModel: DNS resolution failure detected - using exponential backoff")
                
                // Track DNS failure count for exponential backoff
                val dnsFailureCount = getDnsFailureCount()
                val nextDnsFailureCount = dnsFailureCount + 1
                setDnsFailureCount(nextDnsFailureCount)
                
                // Exponential backoff: 2s, 4s, 8s, 16s, 32s, 64s (max)
                val delayMs = minOf(2000L * (1L shl (nextDnsFailureCount - 1)), 64000L)
                
                android.util.Log.i("Andromuks", "AppViewModel: DNS failure #$nextDnsFailureCount - scheduling reconnection in ${delayMs}ms")
                logActivity("DNS Failure #$nextDnsFailureCount - Retry in ${delayMs}ms", null)
                
                viewModelScope.launch {
                    delay(delayMs)
                    // CRITICAL FIX: Check network again before executing reconnection
                    // Network might have been lost during the delay
                    val currentNetworkType = WebSocketService.getCurrentNetworkType()
                    if (currentNetworkType == net.vrkknn.andromuks.WebSocketService.NetworkType.NONE) {
                        android.util.Log.w("Andromuks", "AppViewModel: DNS retry delayed but network now unavailable - cancelling reconnection")
                        return@launch
                    }
                    // Reset DNS failure count on successful reconnection attempt
                    // (will be reset when connection succeeds)
                    scheduleReconnection(ReconnectTrigger.DnsFailure(nextDnsFailureCount))
                }
            }
            "NETWORK_UNREACHABLE" -> {
                // Network unreachable - wait for network availability before retrying
                // WebSocketService's NetworkMonitor will trigger reconnection when network becomes available
                android.util.Log.w("Andromuks", "AppViewModel: Network unreachable - waiting for network availability")
                logActivity("Network Unreachable - Waiting for Network", null)
                
                // Don't retry immediately - NetworkMonitor in WebSocketService will handle reconnection
                // when network becomes available. This prevents battery drain from rapid retries.
                // We still schedule a delayed reconnection as a fallback (in case NetworkMonitor misses the event)
                viewModelScope.launch {
                    // Wait 10 seconds - if network is still unavailable, NetworkMonitor will handle it
                    delay(10000L)
                    // CRITICAL FIX: Check network again before executing reconnection
                    val currentNetworkType = WebSocketService.getCurrentNetworkType()
                    if (currentNetworkType == net.vrkknn.andromuks.WebSocketService.NetworkType.NONE) {
                        android.util.Log.w("Andromuks", "AppViewModel: Network still unavailable after 10s - not scheduling fallback reconnection")
                        return@launch
                    }
                    // Only schedule if still disconnected (NetworkMonitor may have already reconnected)
                    if (!isWebSocketConnected()) {
                        android.util.Log.i("Andromuks", "AppViewModel: Network available after 10s - scheduling fallback reconnection")
                        scheduleReconnection(ReconnectTrigger.NetworkUnreachableFallback)
                    }
                }
            }
            else -> {
                // Generic error - use standard reconnection strategy
                android.util.Log.w("Andromuks", "AppViewModel: Generic connection error - using standard reconnection")
                scheduleReconnection(ReconnectTrigger.Unclassified(reason))
            }
        }
    }
    
    // PHASE 4.2: DNS failure tracking (stored in SharedPreferences)
    private fun getDnsFailureCount(): Int {
        return appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            ?.getInt("dns_failure_count", 0) ?: 0
    }
    
    private fun setDnsFailureCount(count: Int) {
        appContext?.let { context ->
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("dns_failure_count", count)
                .apply()
        }
    }
    
    /**
     * PHASE 4.2: Reset DNS failure count when connection succeeds
     * Should be called when WebSocket connection is successfully established
     */
    private fun resetDnsFailureCount() {
        val currentCount = getDnsFailureCount()
        if (currentCount > 0) {
            android.util.Log.i("Andromuks", "AppViewModel: Resetting DNS failure count (was: $currentCount)")
            appContext?.let { context ->
                context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("dns_failure_count", 0)
                    .apply()
            }
        }
    }
    
    /**
     * PHASE 4.3: Handle TLS/SSL errors with appropriate strategies
     * 
     * @param errorType Type of TLS error: "CERTIFICATE_ERROR" or "TLS_ERROR"
     * @param error The original exception
     * @param reason Human-readable failure reason
     */
    fun handleTlsError(errorType: String, error: Throwable, reason: String) {
        android.util.Log.e("Andromuks", "AppViewModel: TLS/SSL error - Type: $errorType, Reason: $reason")
        
        when (errorType) {
            "CERTIFICATE_ERROR" -> {
                // Certificate errors are security issues - don't reconnect automatically
                android.util.Log.e("Andromuks", "AppViewModel: Certificate validation error detected - NOT reconnecting automatically (security issue)")
                logActivity("Certificate Error - Connection Blocked", null)
                
                // Store certificate error state to prevent automatic reconnection
                setCertificateErrorState(true, reason)
                
                // Log error details (but not full certificate for security)
                val errorDetails = when {
                    error is java.security.cert.CertificateException -> error.message ?: "Certificate validation failed"
                    error.cause is java.security.cert.CertificateException -> (error.cause as java.security.cert.CertificateException).message ?: "Certificate validation failed"
                    else -> "Certificate error: ${error.message}"
                }
                android.util.Log.e("Andromuks", "AppViewModel: Certificate error details: $errorDetails")
                
                // TODO: In the future, we could show a user notification here
                // For now, we just prevent reconnection and log the error
                // The user will see "Disconnected" state and can manually retry
            }
            "TLS_ERROR" -> {
                // Other TLS errors (handshake failures, protocol errors) - reconnect with exponential backoff
                android.util.Log.w("Andromuks", "AppViewModel: TLS error detected (non-certificate) - reconnecting with exponential backoff")
                logActivity("TLS Error - Reconnecting", null)
                
                // Track TLS failure count for exponential backoff
                val tlsFailureCount = getTlsFailureCount()
                val nextTlsFailureCount = tlsFailureCount + 1
                setTlsFailureCount(nextTlsFailureCount)
                
                // Exponential backoff: 2s, 4s, 8s, 16s, 32s, 64s (max)
                val delayMs = minOf(2000L * (1L shl (nextTlsFailureCount - 1)), 64000L)
                
                android.util.Log.i("Andromuks", "AppViewModel: TLS failure #$nextTlsFailureCount - scheduling reconnection in ${delayMs}ms")
                logActivity("TLS Error #$nextTlsFailureCount - Retry in ${delayMs}ms", null)
                
                viewModelScope.launch {
                    delay(delayMs)
                    // Reset TLS failure count on successful reconnection attempt
                    scheduleReconnection(ReconnectTrigger.TlsFailure(nextTlsFailureCount))
                }
            }
            else -> {
                android.util.Log.w("Andromuks", "AppViewModel: Unknown TLS error type: $errorType - using standard reconnection")
                scheduleReconnection(ReconnectTrigger.Unclassified(reason))
            }
        }
    }
    
    // PHASE 4.3: Certificate error state tracking (stored in SharedPreferences)
    private fun setCertificateErrorState(hasError: Boolean, reason: String?) {
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("certificate_error_state", hasError)
                .putString("certificate_error_reason", reason)
                .putLong("certificate_error_timestamp", if (hasError) System.currentTimeMillis() else 0L)
                .apply()
        }
    }
    
    private fun getCertificateErrorState(): Boolean {
        return appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            ?.getBoolean("certificate_error_state", false) ?: false
    }
    
    // PHASE 4.3: TLS failure tracking (stored in SharedPreferences)
    private fun getTlsFailureCount(): Int {
        return appContext?.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            ?.getInt("tls_failure_count", 0) ?: 0
    }
    
    private fun setTlsFailureCount(count: Int) {
        appContext?.let { context ->
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("tls_failure_count", count)
                .apply()
        }
    }
    
    /**
     * PHASE 4.3: Reset TLS failure count when connection succeeds
     * Should be called when WebSocket connection is successfully established
     */
    private fun resetTlsFailureCount() {
        val currentCount = getTlsFailureCount()
        if (currentCount > 0) {
            android.util.Log.i("Andromuks", "AppViewModel: Resetting TLS failure count (was: $currentCount)")
            appContext?.let { context ->
                context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("tls_failure_count", 0)
                    .apply()
            }
        }
    }
    
    internal fun addPendingOperation(operation: PendingWebSocketOperation, saveToStorage: Boolean = false): Boolean =
        persistenceCoordinator.addPendingOperation(operation, saveToStorage)

    internal fun loadPendingOperationsFromStorage() = persistenceCoordinator.loadPendingOperationsFromStorage()

    internal fun savePendingOperationsToStorage() = persistenceCoordinator.savePendingOperationsToStorage()

    private fun flushPendingQueueAfterReconnection() = persistenceCoordinator.flushPendingQueueAfterReconnection()

    fun noteIncomingRequestId(requestId: Int) {
        if (requestId != 0) {
            // Track ALL incoming request_ids for general purposes (pong detection, etc.)
            lastReceivedRequestId = requestId
            // NOTE: We no longer track negative request_ids for reconnection - all caches are cleared on connect/reconnect
            // Pong handling is now done directly in NetworkUtils
        }
    }
    
    /**
     * Stores the run_id received from the gomuks backend.
     * RUSH TO HEALTHY: Store run_id in SharedPreferences immediately - always use same run_id for device
     * vapid_key is not used (we use FCM)
     */
    fun handleRunId(runId: String, vapidKey: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: handleRunId called with runId='$runId'")
        
        // Store run_id in SharedPreferences (persistent storage)
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                val existingRunId = prefs.getString("ws_run_id", "") ?: ""
                
                if (existingRunId.isEmpty()) {
                    // First time - store run_id permanently
                    prefs.edit().putString("ws_run_id", runId).apply()
                    android.util.Log.i("Andromuks", "AppViewModel: Stored run_id in SharedPreferences: $runId")
                } else if (existingRunId != runId) {
                    // Run ID changed - update it (shouldn't happen, but handle it)
                    android.util.Log.w("Andromuks", "AppViewModel: Run ID changed from '$existingRunId' to '$runId' - updating")
                    prefs.edit().putString("ws_run_id", runId).apply()
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Run ID matches existing value: $runId")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to store run_id in SharedPreferences", e)
            }
        }
        
        // Update in-memory cache for backward compatibility
        currentRunId = runId
        this.vapidKey = vapidKey // Keep for backward compatibility, but not used
        
        // NOTE: We no longer update last_received_sync_id - all caches are cleared on connect/reconnect
        
        // CRITICAL FIX: Process deferred emoji pack requests when WebSocket is ready
        // This ensures custom emojis are loaded even if account_data was processed before WebSocket connected
        if (deferredEmojiPackRequests.isNotEmpty() && isWebSocketConnected()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${deferredEmojiPackRequests.size} deferred emoji pack requests")
            val requests = deferredEmojiPackRequests.toList()
            deferredEmojiPackRequests.clear()
            for ((roomId, packName) in requests) {
                requestEmojiPackData(roomId, packName)
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Run ID stored and service updated")
    }
    
    /**
     * Gets the current run_id for reconnection
     * RUSH TO HEALTHY: Always read from SharedPreferences (single source of truth)
     */
    fun getCurrentRunId(): String {
        return appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                val runId = prefs.getString("ws_run_id", "") ?: ""
                // Update in-memory cache for backward compatibility
                if (runId.isNotEmpty()) {
                    currentRunId = runId
                }
                runId
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to read run_id from SharedPreferences", e)
                currentRunId // Fallback to in-memory cache
            }
        } ?: currentRunId // Fallback to in-memory cache if context is null
    }
    
    /**
     * Gets the current VAPID key for push notifications
     */
    fun getVapidKey(): String = vapidKey
    
    /**
     * Gets the current request ID counter (next ID to be used)
     */
    fun getCurrentRequestId(): Int = requestIdCounter
    
    /**
     * Gets the last received request ID from the server (for reconnection and Settings).
     * Prefers value persisted in WebSocketService/SharedPreferences so it stays in sync with sync_complete processing.
     */
    fun getLastReceivedRequestId(): Int {
        appContext?.let { return WebSocketService.getLastReceivedRequestId(it) }
        return lastReceivedRequestId
    }
    
    /**
     * Check if an event is pinned in the current room
     */
    
    fun saveStateToStorage(context: android.content.Context) = persistenceCoordinator.saveStateToStorage(context)

    /** Load minimal state from SharedPreferences (run_id and vapid_key only). */
    fun loadStateFromStorage(context: android.content.Context): Boolean =
        persistenceCoordinator.loadStateFromStorage(context)
    
    /**
     * Handles 401 Unauthorized error by clearing all credentials and triggering login navigation.
     * This should be called when WebSocket connection fails with 401 (invalid/expired token).
     */
    fun handleUnauthorizedError() {
        android.util.Log.e("Andromuks", "AppViewModel: Handling 401 Unauthorized error - clearing credentials and navigating to login")
        logActivity("401 Unauthorized - Clearing Credentials", null)
        
        val context = appContext ?: run {
            android.util.Log.e("Andromuks", "AppViewModel: Cannot handle 401 error - appContext is null")
            return
        }
        
        try {
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Clear auth token (this will cause AuthCheck to navigate to login)
            editor.remove("gomuks_auth_token")
            editor.remove("homeserver_url")
            
            // Clear run_id and related WebSocket state
            editor.remove("ws_run_id")
            editor.remove("ws_vapid_key")
            editor.remove("cached_rooms")
            editor.remove("state_saved_timestamp")
            
            // CRITICAL FIX: Use commit() instead of apply() to ensure credentials are cleared synchronously
            // This prevents credential loss if the app crashes/terminates right after 401 error
            // commit() blocks until the write completes, ensuring persistence even if app is killed
            val commitSuccess = editor.commit()
            if (!commitSuccess) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to commit credential clearing - retrying with apply()")
                // Fallback to apply() if commit() fails (shouldn't happen, but be defensive)
                editor.apply()
            }
            
            // Clear in-memory state
            currentRunId = ""
            vapidKey = ""
            navigationCallbackTriggered = false
            
            // Clear WebSocket connection
            clearWebSocket("401 Unauthorized - credentials cleared")
            WebSocketService.clearWebSocket("401 Unauthorized - credentials cleared")
            WebSocketService.clearReconnectionState()
            
            // Stop WebSocket service
            WebSocketService.stopService()
            
            android.util.Log.i("Andromuks", "AppViewModel: Credentials cleared due to 401 Unauthorized - app will navigate to login on next AuthCheck (commit success: $commitSuccess)")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to clear credentials on 401 error", e)
            logActivity("401 Error Handling Failed - ${e.message}", null)
        }
    }

    
    internal fun restartWebSocket(trigger: ReconnectTrigger = ReconnectTrigger.Unclassified("Unknown reason")) {
        // INFINITE LOOP FIX: Prevent rapid-fire restarts
        val currentTime = System.currentTimeMillis()
        val timeSinceLastRestart = currentTime - lastRestartTime
        val reasonLabel = trigger.toLogString()
        
        if (isRestarting) {
            android.util.Log.w("Andromuks", "AppViewModel: restartWebSocket already in progress - ignoring duplicate call (trigger: $trigger)")
            return
        }
        
        if (timeSinceLastRestart < RESTART_COOLDOWN_MS) {
            android.util.Log.w("Andromuks", "AppViewModel: restartWebSocket called too soon (${timeSinceLastRestart}ms ago, cooldown: ${RESTART_COOLDOWN_MS}ms) - ignoring (trigger: $trigger)")
            return
        }
        
        isRestarting = true
        lastRestartTime = currentTime
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: restartWebSocket invoked - $trigger")
        logActivity("Restarting WebSocket - $reasonLabel", null)
        
        // Only show toast for important reconnection reasons, not every attempt
        val shouldShowToast = when (trigger) {
            is ReconnectTrigger.UserRequested -> true
            is ReconnectTrigger.NetworkTypeChanged -> true
            is ReconnectTrigger.Unclassified -> {
                val d = trigger.detail
                d.contains("Full refresh", ignoreCase = true) || d.contains("Manual reconnection", ignoreCase = true)
            }
            else -> false
        }

        // Only show toasts in debug builds to avoid UX disruption in production
        if (shouldShowToast && BuildConfig.DEBUG) {
            appContext?.let { context ->
                viewModelScope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "WS: $reasonLabel",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val restartCallback = onRestartWebSocket
        
        if (restartCallback != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Using direct reconnect callback for reason: $reasonLabel")
            // Cancel any pending reconnection jobs in the service to avoid duplicate attempts
            WebSocketService.cancelReconnection()
            // Ensure the service state is reset before establishing a new connection
            WebSocketService.clearWebSocket(reasonLabel)
            
            // INFINITE LOOP FIX: Clear restart flag after a delay to allow connection to complete
            viewModelScope.launch {
                try {
                    restartCallback.invoke(trigger)
                    // Wait a bit before clearing the flag to prevent immediate re-triggers
                    delay(2000L)
                } finally {
                    isRestarting = false
                }
            }
            return
        }

        // FALLBACK PATH: onRestartWebSocket not set (e.g. compression toggle, manual restart)
        // In this case we still want to actively reconnect, but without creating a
        // service↔ViewModel callback loop.
        //
        // Strategy:
        // 1. Cancel any pending reconnections in the service
        // 2. Clear the current WebSocket state in the service
        // 3. Call initializeWebSocketConnection(homeserverUrl, authToken) directly
        //
        // This mirrors the normal app startup flow (AuthCheck → initializeWebSocketConnection),
        // but is driven by the primary AppViewModel. Because we are NOT in a service
        // reconnection callback here, this does not create an infinite loop.
        if (homeserverUrl.isNotBlank() && authToken.isNotBlank()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: restartWebSocket fallback - using initializeWebSocketConnection (trigger: $trigger)"
                )
            }
            // Make sure the service isn't trying to reconnect in parallel
            WebSocketService.cancelReconnection()
            // Clear any existing WebSocket connection before starting a fresh one
            WebSocketService.clearWebSocket(reasonLabel)
            
            // Delegate connection to the standard initialization path
            initializeWebSocketConnection(homeserverUrl, authToken)
            
            // Clear restart flag after a short delay so future restarts are allowed
            viewModelScope.launch {
                delay(2000L)
                isRestarting = false
            }
            return
        }

        // Service was restarted upstream; avoid callback loop
        if (trigger is ReconnectTrigger.ServiceRestarted) {
            android.util.Log.w("Andromuks", "AppViewModel: restartWebSocket(ServiceRestarted) — connecting WebSocket directly to avoid loop")
            WebSocketService.clearWebSocket(reasonLabel)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Service restart reconnection - connection should be handled by app startup flow")
            isRestarting = false
            return
        }
        
        // INFINITE LOOP FIX: Don't call WebSocketService.restartWebSocket() from reconnection callback
        // This creates an infinite loop because it calls the callback again.
        // At this point we also have no homeserver/auth token (or they are blank),
        // so we cannot safely call initializeWebSocketConnection either.
        // In this edge case we fall back to logging only and let the normal
        // app startup/AuthCheck flow handle connection establishment.
        android.util.Log.w("Andromuks", "AppViewModel: onRestartWebSocket callback not set and no credentials available - cannot restart without risking loop")
        android.util.Log.w("Andromuks", "AppViewModel: Connection should be handled by initializeWebSocketConnection() or app startup flow when credentials are available")
        
        // Clear restart flag after a delay
        viewModelScope.launch {
            delay(2000L)
            isRestarting = false
        }
    }

    fun requestUserProfile(userId: String, roomId: String? = null) =
        memberProfilesCoordinator.requestUserProfile(userId, roomId)

    /**
     * Request a basic global profile and invoke callback as soon as get_profile returns.
     * This is useful for surfaces (like pinned events) that need deterministic profile fetch.
     */
    fun requestBasicUserProfile(userId: String, callback: (MemberProfile?) -> Unit) =
        memberProfilesCoordinator.requestBasicUserProfile(userId, callback)
    
    /**
     * Validates and requests missing user profiles for a room.
     * 
     * This function checks all timeline events and identifies users with missing
     * display names or avatars. It then requests their profiles from the server
     * to ensure complete user information is available for the UI.
     * 
     * @param roomId The ID of the room to validate profiles for
     * @param timelineEvents List of timeline events to check for missing user data
     */
    fun validateAndRequestMissingProfiles(roomId: String, timelineEvents: List<TimelineEvent>) {
        val usersToRequest = mutableSetOf<String>()
        
        // Check each timeline event for missing user profile data
        for (event in timelineEvents) {
            val sender = event.sender
            
            // Use getUserProfile to check all caches (flattened, legacy room cache, global cache)
            val profile = getUserProfile(sender, roomId)
            
            // Check if we have incomplete profile data
            val hasDisplayName = !profile?.displayName.isNullOrBlank()
            val hasAvatar = !profile?.avatarUrl.isNullOrBlank()
            
            if (!hasDisplayName || !hasAvatar) {
                // UNIFIED PENDING CHECK: Check both global and room-specifc pending requests
                val isPending = pendingProfileRequests.contains(sender) || pendingProfileRequests.contains("$roomId:$sender")
                
                if (!isPending) {
                    usersToRequest.add(sender)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Missing profile data for $sender - displayName: $hasDisplayName, avatar: $hasAvatar")
                }
            }
        }
        
        // Request profiles for users with missing data — use room-specific member state so
        // display name and avatar match the room (same path as UserInfo / timeline rows).
        for (userId in usersToRequest) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting missing profile (on-demand) for $userId")
            requestUserProfileOnDemand(userId, roomId)
        }
    }
    
    // Track outgoing requests for timeline processing
    fun trackOutgoingRequest(requestId: Int, roomId: String) {
        outgoingRequests[requestId] = roomId
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracking outgoing request $requestId for room $roomId")
    }
    
    // Send a message and track the response
    fun sendMessage(roomId: String, text: String, mentions: List<String> = emptyList()) =
        messageSendCoordinator.sendMessage(roomId, text, mentions)

    
   
    /**
     * Helper function to process cached events and display them
     */
    internal fun processCachedEvents(
        roomId: String,
        cachedEvents: List<TimelineEvent>,
        openingFromNotification: Boolean,
        skipNetworkRequests: Boolean = false
    ) = timelineCacheCoordinator.processCachedEvents(roomId, cachedEvents, openingFromNotification, skipNetworkRequests)

    /**
     * Convert resolved TimelineEvents into render-ready rows and persist to the renderable_events table.
     * This avoids recomputing relations/edits/redactions at UI time.
     */
    internal fun persistRenderableEvents(roomId: String, events: List<TimelineEvent>) {
        if (events.isEmpty()) return
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping renderable event persistence (in-memory cache only)")
        }
    }

    /**
     * Insert a local echo into timeline/renderable cache for immediate UI display.
     */
    // Local echo rendering removed.

    /**
     * Load an original event (and minimal context) from on-disk DB for deleted-content preview.
     * Returns the event with redaction cleared so it renders as originally sent.
     */
    /**
     * Update room state (name/avatar) from timeline events if they're more recent than current state.
     * This ensures room state stays in sync when m.room.name or m.room.avatar events appear in the timeline.
     */
    internal fun updateRoomStateFromTimelineEvents(roomId: String, events: List<TimelineEvent>) {
        if (roomId.isEmpty() || events.isEmpty()) {
            return
        }
        
        val context = appContext ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot update room state for $roomId - appContext is null")
            return
        }
        
        // Find the most recent m.room.name and m.room.avatar events
        val nameEvent = events
            .filter { it.type == "m.room.name" && it.timestamp > 0 }
            .maxByOrNull { it.timestamp }
        
        val avatarEvent = events
            .filter { it.type == "m.room.avatar" && it.timestamp > 0 }
            .maxByOrNull { it.timestamp }
        
        if (nameEvent == null && avatarEvent == null) {
            // No room state events in this batch
            return
        }
        
        val eventName = nameEvent?.content?.optString("name")?.takeIf { it.isNotBlank() }
        val eventAvatarUrl = avatarEvent?.content?.optString("url")?.takeIf { it.isNotBlank() }

        var updated = false
        val currentRoomItem = roomMap[roomId]
        var updatedRoomItem = currentRoomItem
        if (currentRoomItem != null) {
            if (eventName != null && eventName != currentRoomItem.name) {
                updatedRoomItem = updatedRoomItem?.copy(name = eventName)
                updated = true
            }
            if (eventAvatarUrl != null && eventAvatarUrl != currentRoomItem.avatarUrl) {
                updatedRoomItem = updatedRoomItem?.copy(avatarUrl = eventAvatarUrl)
                updated = true
            }
            if (updated && updatedRoomItem != null) {
                roomMap[roomId] = updatedRoomItem
            }
        }

        if (currentRoomId == roomId && currentRoomState != null) {
            currentRoomState = currentRoomState?.copy(
                name = eventName ?: currentRoomState?.name,
                avatarUrl = eventAvatarUrl ?: currentRoomState?.avatarUrl
            )
        }

        if (updated) {
            scheduleRoomReorder()
            roomListUpdateCounter++
        }
    }
    
    /**
     * Update member profiles from timeline m.room.member events in a batch.
     * This ensures member profiles stay in sync when profile changes appear in the timeline.
     */
    internal fun updateMemberProfilesFromTimelineEvents(roomId: String, events: List<TimelineEvent>) {
        if (roomId.isEmpty() || events.isEmpty()) {
            return
        }
        
        // Find all m.room.member events with timelineRowid >= 0 (timeline events, not state events)
        val memberEvents = events.filter { 
            it.type == "m.room.member" && it.timelineRowid >= 0L 
        }
        
        if (memberEvents.isEmpty()) {
            return
        }
        
        // Process each member event
        for (event in memberEvents) {
            updateMemberProfileFromTimelineEvent(roomId, event)
        }
    }
    
    /**
     * Update member profile from a timeline m.room.member event if display name or avatar changed.
     * This ensures member profiles stay in sync when profile changes appear in the timeline.
     * Handles both additions/changes and removals (empty values).
     */
    private fun updateMemberProfileFromTimelineEvent(roomId: String, event: TimelineEvent) {
        if (roomId.isEmpty() || event.type != "m.room.member") {
            return
        }
        
        val userId = event.stateKey ?: event.sender
        if (userId.isEmpty()) {
            return
        }
        
        val content = event.content ?: return
        val membership = content.optString("membership")?.takeIf { it.isNotBlank() }
        
        // Only update profile for joined members (membership == "join")
        // Leave/ban events don't have profile info to extract
        if (membership != "join") {
            return
        }
        
        // Extract displayname and avatar_url - allow empty strings to handle removals
        // Empty string means the field was explicitly removed, null means it wasn't present
        val displayNameRaw = if (content.has("displayname")) content.optString("displayname") else null
        val displayName = displayNameRaw?.takeIf { it.isNotBlank() }
        val avatarUrlRaw = if (content.has("avatar_url")) content.optString("avatar_url") else null
        val avatarUrl = avatarUrlRaw?.takeIf { it.isNotBlank() }
        
        // Always update profile, even if both are null (removal case)
        // This ensures the profile cache reflects the current state
        val newProfile = MemberProfile(displayName, avatarUrl)
        
        // Check if this is actually a profile change (not just initial join)
        val existingProfile = getMemberProfile(roomId, userId)
        val isProfileChange = existingProfile != null && 
            (existingProfile.displayName != displayName || existingProfile.avatarUrl != avatarUrl)
        
        if (isProfileChange || existingProfile == null) {
            // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
            storeMemberProfile(roomId, userId, newProfile)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated member profile for $userId in $roomId from timeline event (displayName=${displayName != null}, avatarUrl=${avatarUrl != null})")
        }
    }
    
    internal fun loadReactionsForRoom(roomId: String, cachedEvents: List<TimelineEvent>, forceReload: Boolean = false) =
        reactionCoordinator.loadReactionsForRoom(roomId, cachedEvents, forceReload)

    internal fun applyAggregatedReactionsFromEvents(events: List<TimelineEvent>, source: String) =
        reactionCoordinator.applyAggregatedReactionsFromEvents(events, source)

    /**
     * Ensure timeline cache is fresh (cache-only approach, no DB loading)
     * If cache is empty or room is not actively cached, triggers paginate request
     */
    suspend fun ensureTimelineCacheIsFresh(roomId: String, limit: Int = INITIAL_ROOM_PAGINATE_LIMIT, isBackground: Boolean = false) {
        val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)
        val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)
        
        // If cache has events and room is actively cached, cache is fresh
        if (cachedEvents != null && cachedEvents.isNotEmpty() && isActivelyCached) {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: ensureTimelineCacheIsFresh($roomId) - cache is fresh (${cachedEvents.size} events, actively cached)"
            )
            return
        }
        
        // Cache is empty or room not actively cached - trigger paginate request
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: ensureTimelineCacheIsFresh($roomId) - cache empty or not actively cached, triggering paginate (background=$isBackground)"
        )
        
        // Issue paginate request to fill cache
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: ensureTimelineCacheIsFresh($roomId) - WebSocket not connected, cannot paginate")
            return
        }
        
        withContext(Dispatchers.Main) {
            val paginateRequestId = requestIdCounter++
            // CRITICAL FIX: Use backgroundPrefetchRequests for background pagination to prevent timeline rebuild
            // Background pagination should only update cache, not rebuild the timeline for the currently open room
            if (isBackground) {
                backgroundPrefetchRequests[paginateRequestId] = roomId
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ensureTimelineCacheIsFresh($roomId) - using backgroundPrefetchRequests for silent cache update"
                )
            } else {
                timelineRequests[paginateRequestId] = roomId
            }
            val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                "room_id" to roomId,
                "max_timeline_id" to 0, // Fetch latest events
                "limit" to limit,
                "reset" to false
            ))
            
            if (result == WebSocketResult.SUCCESS) {
                // Room is already marked as actively cached (either by triggerPreemptivePagination or by previous call)
                // Just confirm it's still marked
                if (!RoomTimelineCache.isRoomActivelyCached(roomId)) {
                    RoomTimelineCache.markRoomAsCached(roomId)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ensureTimelineCacheIsFresh($roomId) - room was not actively cached, marked it now")
                }
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ensureTimelineCacheIsFresh($roomId) - paginate request sent, room is actively cached (background=$isBackground)"
                )
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: ensureTimelineCacheIsFresh($roomId) - failed to send paginate request: $result")
            }
        }
    }
    
    /**
     * Trigger preemptive pagination for a room when a notification is generated.
     * This ensures the room timeline is cached before the user taps the notification.
     * Called from broadcast receiver when notification is generated.
     */
    fun triggerPreemptivePagination(roomId: String) {
        viewModelScope.launch {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: triggerPreemptivePagination called for room: $roomId")
            
            // Check if room is already in cache
            val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
            val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)
            
            if (cachedEventCount >= 50 && isActivelyCached) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room $roomId already in cache ($cachedEventCount events >= 50, actively cached), skipping preemptive pagination")
                return@launch
            }
            
            // CRITICAL FIX: Mark room as actively cached IMMEDIATELY when preemptive pagination is triggered
            // This ensures that any sync_complete messages that arrive after the notification will have
            // their events cached, even before the paginate request completes
            RoomTimelineCache.markRoomAsCached(roomId)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked room $roomId as actively cached immediately (will receive events from sync_complete while pagination is in progress)")
            
            // CRITICAL FIX: Use background pagination to prevent timeline rebuild for currently open room
            // Background pagination only updates cache, doesn't rebuild timeline
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Triggering preemptive pagination for room: $roomId (cached: $cachedEventCount events, actively cached: $isActivelyCached) - using background mode")
            ensureTimelineCacheIsFresh(roomId, limit = INITIAL_ROOM_PAGINATE_LIMIT, isBackground = true)
        }
    }

    /**
     * Restore timeline from singleton cache (for bubbles/secondary VMs)
     */
    fun restoreFromLruCache(roomId: String): Boolean = timelineCacheCoordinator.restoreFromLruCache(roomId)

    fun requestRoomTimeline(roomId: String, useLruCache: Boolean = true) =
        timelineCacheCoordinator.requestRoomTimeline(roomId, useLruCache)
    /**
     * Fully refreshes the room timeline by resetting in-memory state and fetching a clean snapshot.
     * Steps:
     * 1. Marks the room as the current timeline so downstream handlers know which room is active
     * 2. Clears all RAM caches and timeline bookkeeping for the room
     * 3. Resets pagination flags
     * 4. Requests fresh room state
     * 5. Sends a paginate command for up to INITIAL_ROOM_PAGINATE_LIMIT events (ingest pipeline updates cache)
     */
    fun fullRefreshRoomTimeline(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full refresh for room: $roomId (resetting caches and requesting fresh snapshot)")
        setAutoPaginationEnabled(false, "manual_refresh_$roomId")
        
        // For manual refresh, clear the pagination flag to allow pagination to proceed
        // Force removal to ensure we can always request fresh events
        roomsPaginatedOnce.remove(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared pagination flag for manual refresh of room: $roomId")
        
        // REMOVED: Skip check - manual refresh should always request fresh events from server
        // Even if the room was paginated before, we want to force a new paginate request
        
        // 1. Mark room as current so sync handlers and pagination know which timeline is active
        updateCurrentRoomIdInPrefs(roomId)
        
        // 2. Wipe in-memory cache/state for this room
        RoomTimelineCache.clearRoomCache(roomId)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleared timeline cache for room: $roomId")
        
        timelineEvents = emptyList()
        // DISABLED: wipe - keeping cache entries to preserve timeline data
        isTimelineLoading = true
        
        // 3. Reset pagination flags and bookkeeping
        smallestRowId = -1L
        isPaginating = false
        hasMoreMessages = true
        
        eventChainMap.clear()
        editEventsMap.clear()
        MessageVersionsCache.clear()
        // editToOriginal is computed from messageVersions, no need to clear separately
        // redactionCache is computed from messageVersions, no need to clear separately
        MessageReactionsCache.clear()
        messageReactions = emptyMap()
        
        // Clear new message tracking and room-open timestamp
        newMessageAnimations.clear()
        timelineEntrancePlayed.clear()
        roomOpenTimestamps.remove(roomId)
        timelineForegroundTimestamps.remove(roomId)
        
        // Reset member update counter to avoid stale diffs
        memberUpdateCounter = 0
        
        // 4. Request fresh room state
        requestRoomState(roomId)
        
        // 5. Request up to INITIAL_ROOM_PAGINATE_LIMIT events from the backend; ingest path will update the cache
        val paginateRequestId = requestIdCounter++
        timelineRequests[paginateRequestId] = roomId
        val result = sendWebSocketCommand(
            "paginate",
            paginateRequestId,
            mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to INITIAL_ROOM_PAGINATE_LIMIT,
            "reset" to false
            )
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent paginate request for room: $roomId (${INITIAL_ROOM_PAGINATE_LIMIT} events) - awaiting response to rebuild timeline")
        if (result == WebSocketResult.SUCCESS) {
            markInitialPaginate(roomId, "full_refresh")
        } else {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Failed to send full refresh paginate for $roomId (result=$result)"
            )
        }
    }
    
    suspend fun prefetchRoomSnapshot(roomId: String, limit: Int = INITIAL_ROOM_PAGINATE_LIMIT, timeoutMs: Long = 6000L): Boolean {
        if (!AUTO_PAGINATION_ENABLED) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Prefetch snapshot disabled (AUTO_PAGINATION_ENABLED=false) for $roomId")
            return false
        }
        if (hasInitialPaginate(roomId)) {
            logSkippedPaginate(roomId, "prefetch_snapshot")
            return true
        }

        val ws = WebSocketService.getWebSocket() ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot prefetch snapshot for $roomId - WebSocket not connected")
            return false
        }
        
        val deferred = CompletableDeferred<Unit>()
        registerRoomSnapshotAwaiter(roomId, deferred)
        
        val requestId = requestIdCounter++
        backgroundPrefetchRequests[requestId] = roomId
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "AppViewModel: Prefetching snapshot for room $roomId (requestId=$requestId, limit=$limit, timeout=${timeoutMs}ms)"
        )
        
        val commandResult = sendWebSocketCommand(
            "paginate",
            requestId,
            mapOf(
                "room_id" to roomId,
                "max_timeline_id" to 0,
                "limit" to limit,
                "reset" to false
            )
        )
        
        if (commandResult != WebSocketResult.SUCCESS) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Prefetch paginate for $roomId failed to send (result=$commandResult)"
            )
            backgroundPrefetchRequests.remove(requestId)
            unregisterRoomSnapshotAwaiter(roomId, deferred)
            return false
        }
        
        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Prefetch snapshot complete for room $roomId")
            markInitialPaginate(roomId, "prefetch_snapshot")
            true
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Prefetch snapshot timed out for room $roomId after ${timeoutMs}ms"
            )
            false
        } finally {
            unregisterRoomSnapshotAwaiter(roomId, deferred)
            backgroundPrefetchRequests.remove(requestId)
        }
    }
    
    /**
     * Flush any buffered sync_complete messages and wait for completion.
     * Must be called BEFORE navController.navigate() when opening from notification
     * to ensure the cache has the latest events.
     *
     * Also marks the room as actively cached so SyncIngestor adds events to it
     * during the flush.
     */
    suspend fun flushSyncBatchForRoom(roomId: String) = timelineCacheCoordinator.flushSyncBatchForRoom(roomId)

    // OPTIMIZATION #4: Cache-first navigation method
    fun navigateToRoomWithCache(roomId: String, notificationTimestamp: Long? = null) =
        navigationCoordinator.navigateToRoomWithCache(roomId, notificationTimestamp)

    internal fun requestHistoricalReactions(roomId: String, smallestCached: Long) =
        reactionCoordinator.requestHistoricalReactions(roomId, smallestCached)

    /**
     * Request related events for a specific message to backfill detailed reactions via get_related_events.
     *
     * This is used by the Reactions menu when opening the reaction details dialog so that
     * messageReactions[eventId] has per-user reaction data (including timestamps), even when the
     * room was loaded primarily via paginate responses.
     */
    fun requestReactionDetails(roomId: String, eventId: String) =
        reactionCoordinator.requestReactionDetails(roomId, eventId)
    
    fun requestRoomState(roomId: String) {
        // PERFORMANCE: Prevent duplicate room state requests for the same room
        if (pendingRoomStateRequests.contains(roomId)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room state request already pending for $roomId, skipping duplicate")
            return
        }
        
        val stateRequestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingRoomStateRequests.add(roomId)
        roomStateRequests[stateRequestId] = roomId
        
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to false,
            "fetch_members" to false,
            "refetch" to false
        ))
    }
    
    /**
     * Requests complete room state including member list
     * Used by the Room Info screen to display detailed room information
     */
    fun requestRoomStateWithMembers(roomId: String, callback: (net.vrkknn.andromuks.utils.RoomStateInfo?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting room state with members for room: $roomId")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with error, health monitor will handle reconnection")
            callback(null, "WebSocket not connected")
            return
        }
        
        val stateRequestId = requestIdCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_room_state with members: $stateRequestId")
        
        // Store the callback to handle the response
        roomStateWithMembersRequests[stateRequestId] = callback
        
        // IMPORTANT: Request room state with include_members: true to get the full member list
        // This ensures RoomInfo screen displays the actual member list from the server
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to true,  // CRITICAL: Include members in response for RoomInfo screen
            "fetch_members" to false,
            "refetch" to false
        ))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $stateRequestId (include_members=true)")
    }
    
    fun requestUserProfileOnDemand(userId: String, roomId: String) {
        // CRITICAL FIX: Check if we have a valid profile (not just non-null)
        // A profile with blank displayName should still be requested to get the actual name
        val existingProfile = getUserProfile(userId, roomId)
        if (existingProfile != null && !existingProfile.displayName.isNullOrBlank()) {
            // Profile exists and has a valid display name - skip request
            //android.util.Log.d("Andromuks", "AppViewModel: Profile already cached for $userId, skipping request")
            return
        }

        val requestKey = "$roomId:$userId"
        // FAST PATH: Many timeline rows for the same sender + scroll recycle would otherwise
        // repeat throttle/pending logic. If a request is already in flight, return immediately
        // so scrolling/recomposition stays cheap.
        if (pendingProfileRequests.contains(requestKey)) {
            return
        }
        
        fun enqueueNetworkRequest() {
            // Avoid duplicate network requests
        if (pendingProfileRequests.contains(requestKey)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile request already pending for $userId, skipping duplicate")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        val lastRequestTime = recentProfileRequestTimes[requestKey]
        // CRITICAL FIX: Only throttle if we have a recent successful request
        // If the last request failed (no response), don't throttle to allow retry
        // We check if the request is still pending - if it's not pending but was recent, it succeeded
        val wasRecentRequest = lastRequestTime != null && (currentTime - lastRequestTime) < PROFILE_REQUEST_THROTTLE_MS
        val isStillPending = pendingProfileRequests.contains(requestKey)
        if (wasRecentRequest && !isStillPending) {
            // Recent successful request - throttle
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile request throttled for $userId (requested ${currentTime - lastRequestTime}ms ago)")
            return
        }
        
        // Clean up old throttle entries (older than throttle window) to prevent memory leaks
        val cutoffTime = currentTime - PROFILE_REQUEST_THROTTLE_MS
        recentProfileRequestTimes.entries.removeAll { (_, timestamp) -> timestamp < cutoffTime }
        
        // CRITICAL FIX: Clean up stale pending requests (older than 30 seconds)
        // This handles cases where requests failed silently or responses were lost
        val staleCutoffTime = currentTime - 30000L // 30 seconds
        val staleRequests = pendingProfileRequests.filter { key ->
            val requestTime = recentProfileRequestTimes[key] ?: 0L
            requestTime > 0 && requestTime < staleCutoffTime
        }
        staleRequests.forEach { key ->
            pendingProfileRequests.remove(key)
            // Remove from batch so we don't send it on flush (key is "roomId:userId", userId starts with @)
            val sep = key.indexOf(":@")
            if (sep > 0) synchronized(pendingProfileBatch) {
                pendingProfileBatch[key.substring(0, sep)]?.remove(key.substring(sep + 1))
            }
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Cleaned up stale profile request for $key (older than 30s)")
        }
        
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting profile on-demand (network) for $userId in room $roomId")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping on-demand profile request")
            return
        }
        
        // BATCH: Queue (roomId, userId) and send one get_specific_room_state per room after a short delay
        pendingProfileRequests.add(requestKey)
        recentProfileRequestTimes[requestKey] = currentTime
        synchronized(pendingProfileBatch) {
            pendingProfileBatch.getOrPut(roomId) { mutableSetOf() }.add(userId)
        }
        scheduleProfileBatchFlush()
        }
        
        enqueueNetworkRequest()
    }

    /**
     * Sends one get_specific_room_state per room with all queued (roomId, userId) keys.
     * Called after PROFILE_BATCH_DELAY_MS so multiple on-demand requests are coalesced.
     */
    private fun scheduleProfileBatchFlush() {
        profileBatchFlushJob?.cancel()
        profileBatchFlushJob = viewModelScope.launch {
            kotlinx.coroutines.delay(PROFILE_BATCH_DELAY_MS)
            flushProfileBatch()
        }
    }

    private fun flushProfileBatch() {
        profileBatchFlushJob = null
        if (!isWebSocketConnected()) return
        val toSend: List<Pair<String, List<String>>> = synchronized(pendingProfileBatch) {
            val list = pendingProfileBatch.mapNotNull { (roomId, userIds) ->
                if (userIds.isEmpty()) null else Pair(roomId, userIds.toList()).also { userIds.clear() }
            }
            pendingProfileBatch.entries.removeAll { (_, set) -> set.isEmpty() }
            list
        }
        for ((roomId, userIds) in toSend) {
            if (userIds.isEmpty()) continue
            val keysList = userIds.map { userId ->
                mapOf(
                    "room_id" to roomId,
                    "type" to "m.room.member",
                    "state_key" to userId
                )
            }
            val requestId = requestIdCounter++
            roomSpecificStateRequests[requestId] = roomId
            batchProfileRequestKeys[requestId] = userIds.map { "$roomId:$it" }
            sendWebSocketCommand("get_specific_room_state", requestId, mapOf("keys" to keysList))
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent batched profile request with ID $requestId for $roomId (${userIds.size} users)")
        }
    }
    /**
     * Request room-specific user profile from backend using get_specific_room_state.
     * This fetches the most up-to-date room-specific display name and avatar for a user.
     * The response will automatically update the room member cache via handleRoomSpecificStateResponse.
     */
    fun requestRoomSpecificUserProfile(roomId: String, userId: String) {
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot request room-specific profile")
            return
        }
        
        // Check if we already have a recent request for this user in this room
        val requestKey = "$roomId:$userId"
        val currentTime = System.currentTimeMillis()
        val lastRequestTime = recentProfileRequestTimes[requestKey] ?: 0L
        // CRITICAL FIX: Only throttle if we have a recent successful request (not still pending)
        val wasRecentRequest = currentTime - lastRequestTime < PROFILE_REQUEST_THROTTLE_MS
        val isStillPending = pendingProfileRequests.contains(requestKey)
        if (wasRecentRequest && !isStillPending) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping room-specific profile request for $userId in $roomId (throttled)")
            return
        }
        
        // Check if request is already pending
        if (pendingProfileRequests.contains(requestKey)) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room-specific profile request already pending for $userId in $roomId")
            return
        }
        
        val requestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingProfileRequests.add(requestKey)
        recentProfileRequestTimes[requestKey] = currentTime
        roomSpecificStateRequests[requestId] = roomId
        
        // CRITICAL FIX: Store request metadata for timeout handling
        synchronized(profileRequestMetadata) {
            profileRequestMetadata[requestKey] = ProfileRequestMetadata(
                requestId = requestId,
                timestamp = currentTime,
                userId = userId,
                roomId = roomId
            )
        }
        
        // Request specific room state for this user
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf(
            "keys" to listOf(mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            ))
        ))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent room-specific profile request with ID $requestId for $userId in room $roomId")
    }
    
    /**
     * Request per-room member state for a user with callback.
     * This is used by UserInfoScreen to get room-specific display name and avatar.
     */
    fun requestPerRoomMemberState(
        roomId: String,
        userId: String,
        callback: (displayName: String?, avatarUrl: String?) -> Unit
    ) {
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot request per-room member state")
            callback(null, null)
            return
        }
        
        val requestId = requestIdCounter++
        roomSpecificStateRequests[requestId] = roomId
        roomSpecificProfileCallbacks[requestId] = callback
        
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf(
            "keys" to listOf(mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            ))
        ))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent per-room member state request with ID $requestId for $userId in room $roomId")
    }
    
    /**
     * Requests updated profile information for users in a room using a single get_specific_room_state.
     * Called after paginate response: gathers all senders, m.mentions.user_ids, and reply-target senders
     * from the timeline and sends one batched request so the backend returns all profiles in one response.
     */
    fun requestUpdatedRoomProfiles(roomId: String, timelineEvents: List<TimelineEvent>) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting updated room profiles for room: $roomId")
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping profile refresh")
            return
        }
        
        val userIds = mutableSetOf<String>()
        val eventById = timelineEvents.associateBy { it.eventId }
        
        for (event in timelineEvents) {
            if (!event.sender.isBlank() && event.sender != currentUserId) userIds.add(event.sender)
            val content = event.content ?: event.decrypted
            content?.optJSONObject("m.mentions")?.optJSONArray("user_ids")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let { if (it != currentUserId) userIds.add(it) }
                }
            }
            event.getReplyInfo()?.eventId?.let { repliedToId ->
                eventById[repliedToId]?.sender?.takeIf { it.isNotBlank() && it != currentUserId }?.let { userIds.add(it) }
            }
        }
        
        val userIdList = userIds.toList()
        if (userIdList.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No users found in timeline events, skipping profile refresh")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting profile updates for ${userIdList.size} users (senders + mentions + reply targets)")
        
        val keysList = userIdList.map { userId ->
            mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            )
        }
        val requestId = requestIdCounter++
        roomSpecificStateRequests[requestId] = roomId
        val batchKeys = userIdList.map { "$roomId:$it" }
        batchProfileRequestKeys[requestId] = batchKeys
        batchKeys.forEach { pendingProfileRequests.add(it) }
        synchronized(pendingProfileBatch) { pendingProfileBatch[roomId]?.removeAll(userIdList) }
        
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf("keys" to keysList))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent get_specific_room_state request with ID $requestId for ${userIdList.size} members")
    }
    
    /**
     * Request emoji pack data from a room using get_specific_room_state
     */
    internal fun requestEmojiPackData(roomId: String, packName: String) {
        if (!isWebSocketConnected()) {
            // CRITICAL FIX: Queue emoji pack requests when WebSocket isn't ready
            // They will be processed when WebSocket connects
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket not connected, queuing emoji pack request for $packName in $roomId")
            deferredEmojiPackRequests.add(Pair(roomId, packName))
            return
        }
        
        val requestId = requestIdCounter++
        emojiPackRequests[requestId] = Pair(roomId, packName)
        roomSpecificStateRequests[requestId] = roomId
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct ws.send()
        val keysList = listOf(mapOf(
            "room_id" to roomId,
            "type" to "im.ponies.room_emotes",
            "state_key" to packName
        ))
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf("keys" to keysList))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent emoji pack request with ID $requestId for pack $packName in room $roomId")
    }
    
    /**
     * Requests the full member list for a room using get_room_state with include_members=true.
     * This populates the complete room member cache, ensuring we have accurate member information.
     * Should be called when opening a room if the member cache is empty or incomplete.
     */
    fun requestFullMemberList(roomId: String) {
        // PERFORMANCE: Prevent duplicate full member list requests for the same room
        if (pendingFullMemberListRequests.contains(roomId)) {
            return
        }
        
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            return
        }
        
        val requestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingFullMemberListRequests.add(roomId)
        fullMemberListRequests[requestId] = roomId
        
        sendWebSocketCommand("get_room_state", requestId, mapOf(
            "room_id" to roomId,
            "include_members" to true,
            "fetch_members" to false,
            "refetch" to false
        ))
        
    }
    
    fun sendTyping(roomId: String) = messageSendCoordinator.sendTyping(roomId)

    fun sendMessage(roomId: String, text: String) = messageSendCoordinator.sendMessage(roomId, text)

    /**
     * Sends a message from a notification action.
     * This handles websocket connection state and schedules auto-shutdown if needed.
     *
     * DEDUPLICATION: Prevents duplicate sends from notification replies within a 5-second window.
     * This fixes the issue where ordered broadcasts can be received multiple times.
     */
    fun sendMessageFromNotification(roomId: String, text: String, onComplete: (() -> Unit)? = null) =
        messageSendCoordinator.sendMessageFromNotification(roomId, text, onComplete)

    /**
     * Check if WebSocket is healthy (connected and initialized)
     */
    internal fun isWebSocketHealthy(): Boolean {
        return isWebSocketConnected() && spacesLoaded && canSendCommandsToBackend
    }

    
    /**
     * Marks a room as read from a notification action.
     * Uses the always-connected WebSocket maintained by the foreground service.
     */
    fun markRoomAsReadFromNotification(roomId: String, eventId: String, onComplete: (() -> Unit)? = null) =
        readReceiptsTypingCoordinator.markRoomAsReadFromNotification(roomId, eventId, onComplete)
    
    fun sendReaction(roomId: String, eventId: String, emoji: String) =
        reactionCoordinator.sendReaction(roomId, eventId, emoji)
    
    fun updateRecentEmojis(emoji: String) = accountDataCoordinator.updateRecentEmojis(emoji)

    fun sendReply(roomId: String, text: String, originalEvent: TimelineEvent) =
        messageSendCoordinator.sendReply(roomId, text, originalEvent)

    fun sendEdit(roomId: String, text: String, originalEvent: TimelineEvent) =
        messageSendCoordinator.sendEdit(roomId, text, originalEvent)

    fun sendMediaMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        mimeType: String,
        width: Int,
        height: Int,
        size: Long,
        blurHash: String,
        caption: String = "",
        msgType: String = "m.image",
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList(),
        thumbnailUrl: String? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null,
        thumbnailMimeType: String? = null,
        thumbnailSize: Long? = null
    ) = messageSendCoordinator.sendMediaMessage(
        roomId, mxcUrl, filename, mimeType, width, height, size, blurHash, caption, msgType,
        threadRootEventId, replyToEventId, isThreadFallback, mentions,
        thumbnailUrl, thumbnailWidth, thumbnailHeight, thumbnailMimeType, thumbnailSize
    )

    /**
     * Convenience function to send an image message using upload result data
     */
    fun sendImageMessage(
        roomId: String,
        mxcUrl: String,
        width: Int,
        height: Int,
        size: Long,
        mimeType: String,
        blurHash: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList(),
        thumbnailUrl: String? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null,
        thumbnailMimeType: String? = null,
        thumbnailSize: Long? = null
    ) = messageSendCoordinator.sendImageMessage(
        roomId, mxcUrl, width, height, size, mimeType, blurHash, caption,
        threadRootEventId, replyToEventId, isThreadFallback, mentions,
        thumbnailUrl, thumbnailWidth, thumbnailHeight, thumbnailMimeType, thumbnailSize
    )

    /**
     * Send a sticker message
     */
    fun sendStickerMessage(
        roomId: String,
        mxcUrl: String,
        body: String,
        mimeType: String,
        size: Long,
        width: Int = 0,
        height: Int = 0,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) = messageSendCoordinator.sendStickerMessage(
        roomId, mxcUrl, body, mimeType, size, width, height,
        threadRootEventId, replyToEventId, isThreadFallback, mentions
    )

    /**
     * Send a video message with thumbnail
     */
    fun sendVideoMessage(
        roomId: String,
        videoMxcUrl: String,
        thumbnailMxcUrl: String,
        width: Int,
        height: Int,
        duration: Int,
        size: Long,
        mimeType: String,
        thumbnailBlurHash: String,
        thumbnailWidth: Int,
        thumbnailHeight: Int,
        thumbnailSize: Long,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) = messageSendCoordinator.sendVideoMessage(
        roomId, videoMxcUrl, thumbnailMxcUrl, width, height, duration, size, mimeType,
        thumbnailBlurHash, thumbnailWidth, thumbnailHeight, thumbnailSize, caption,
        threadRootEventId, replyToEventId, isThreadFallback, mentions
    )

    fun sendDelete(roomId: String, originalEvent: TimelineEvent, reason: String = "") =
        messageSendCoordinator.sendDelete(roomId, originalEvent, reason)

    /**
     * Send an audio message
     */
    fun sendAudioMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        duration: Int, // duration in milliseconds
        size: Long,
        mimeType: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) = messageSendCoordinator.sendAudioMessage(
        roomId, mxcUrl, filename, duration, size, mimeType, caption,
        threadRootEventId, replyToEventId, isThreadFallback, mentions
    )

    /**
     * Send a file message
     */
    fun sendFileMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        size: Long,
        mimeType: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) = messageSendCoordinator.sendFileMessage(
        roomId, mxcUrl, filename, size, mimeType, caption,
        threadRootEventId, replyToEventId, isThreadFallback, mentions
    )
    fun handleResponse(requestId: Int, data: Any) {
        // THREAD SAFETY: Create safe copies to avoid ConcurrentModificationException during logging
        // Use ArrayList constructor to create a proper copy, not just a view
        val roomStateKeysSnapshot = synchronized(roomStateRequests) { ArrayList(roomStateRequests.keys) }

        // PHASE 5.2/5.3: Acknowledge ALL commands with positive request_id when response arrives
        // Backend responds with same request_id, so we match by request_id and remove from queue
        // This ensures all commands are tracked and acknowledged properly
        var hasPendingOperation = false
        if (requestId > 0) {
            // Check if there's a pending operation for this request_id
            // If not, this might be a stale response or request_id collision
            val operation = synchronized(pendingOperationsLock) {
                pendingWebSocketOperations.find { op ->
                    (op.data["requestId"] as? Int) == requestId
                }
            }
            hasPendingOperation = operation != null
            handleMessageAcknowledgmentByRequestId(requestId)
        } else if (requestId == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - Received response with request_id=0 (no acknowledgment needed)")
        }
        
        // CRITICAL FIX: If no pending operation was found and request_id > 0, check if it's in request maps
        // If it's in a request map, it's a valid response (operation might have been cleaned up already)
        // Only treat as stale if it's NOT in any request map
        if (requestId > 0 && !hasPendingOperation) {
            // Element Call widget responses are handled separately
            widgetCommandRequests.remove(requestId)?.let { deferred ->
                deferred.complete(data)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Widget response received for requestId=$requestId")
                return
            }

            // Check if this request_id exists in any of our request maps - if so, it's valid (not stale)
            val isInRequestMap = profileRequests.containsKey(requestId) ||
                    timelineRequests.containsKey(requestId) ||
                    synchronized(roomStateRequests) { roomStateRequests.containsKey(requestId) } ||
                    messageRequests.containsKey(requestId) ||
                    reactionRequests.containsKey(requestId) ||
                    relatedEventsRequests.containsKey(requestId) ||
                    markReadRequests.containsKey(requestId) ||
                    roomSummaryRequests.containsKey(requestId) ||
                    joinRoomRequests.containsKey(requestId) ||
                    leaveRoomRequests.containsKey(requestId) ||
                    fcmRegistrationRequests.containsKey(requestId) ||
                    eventRequests.containsKey(requestId) ||
                    eventContextRequests.containsKey(requestId) ||
                    freshnessCheckRequests.containsKey(requestId) ||
                    backgroundPrefetchRequests.containsKey(requestId) ||
                    paginateRequests.containsKey(requestId) ||
                    roomStateWithMembersRequests.containsKey(requestId) ||
                    userEncryptionInfoRequests.containsKey(requestId) ||
                    mutualRoomsRequests.containsKey(requestId) ||
                    trackDevicesRequests.containsKey(requestId) ||
                    resolveAliasRequests.containsKey(requestId) ||
                    getRoomSummaryRequests.containsKey(requestId) ||
                    joinRoomCallbacks.containsKey(requestId) ||
                    roomSpecificStateRequests.containsKey(requestId) ||
                    fullMemberListRequests.containsKey(requestId) ||
                    outgoingRequests.containsKey(requestId) ||
                    mentionsRequests.containsKey(requestId) ||
                    widgetCommandRequests.containsKey(requestId)
            
            // If it's NOT in any request map, it's truly stale - ignore it
            if (!isInRequestMap) {
                if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Ignoring stale response with requestId=$requestId (not in any request map and no pending operation)")
                return
            }
            // If it IS in a request map, it's valid - continue processing even though operation was cleaned up
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing response with requestId=$requestId (found in request map, operation may have been cleaned up)")
        }
        
        widgetCommandRequests.remove(requestId)?.let { deferred ->
            deferred.complete(data)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Widget response received for requestId=$requestId")
            return
        }
        
        if (profileRequests.containsKey(requestId)) {
            if (BuildConfig.DEBUG) {
                val userId = profileRequests[requestId]
                android.util.Log.d("Andromuks", "AppViewModel: Received profile response for requestId=$requestId, userId=$userId")
            }
            memberProfilesCoordinator.handleProfileResponse(requestId, data)
        } else if (timelineRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (synchronized(roomStateRequests) { roomStateRequests.containsKey(requestId) }) {
            handleRoomStateResponse(requestId, data)
        } else if (messageRequests.containsKey(requestId)) {
            handleMessageResponse(requestId, data)
        } else if (reactionRequests.containsKey(requestId)) {
            reactionCoordinator.handleReactionResponse(requestId, data)
        } else if (relatedEventsRequests.containsKey(requestId)) {
            reactionCoordinator.handleRelatedEventsResponse(requestId, data)
        } else if (markReadRequests.containsKey(requestId)) {
            // Handle mark_read response - data should be a boolean
            val success = data as? Boolean ?: false
            handleMarkReadResponse(requestId, success)
        } else if (roomSummaryRequests.containsKey(requestId)) {
            handleRoomSummaryResponse(requestId, data)
        } else if (joinRoomRequests.containsKey(requestId)) {
            handleJoinRoomResponse(requestId, data)
        } else if (leaveRoomRequests.containsKey(requestId)) {
            handleLeaveRoomResponse(requestId, data)
        } else if (fcmRegistrationRequests.containsKey(requestId)) {
            handleFCMRegistrationResponse(requestId, data)
        } else if (eventRequests.containsKey(requestId)) {
            handleEventResponse(requestId, data)
        } else if (eventContextRequests.containsKey(requestId)) {
            handleEventContextResponse(requestId, data)
        } else if (freshnessCheckRequests.containsKey(requestId)) {
            handleFreshnessCheckResponse(requestId, data)
        } else if (backgroundPrefetchRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (paginateRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (roomStateWithMembersRequests.containsKey(requestId)) {
            handleRoomStateWithMembersResponse(requestId, data)
        } else if (userEncryptionInfoRequests.containsKey(requestId)) {
            userEncryptionCoordinator.handleUserEncryptionInfoResponse(requestId, data)
        } else if (mutualRoomsRequests.containsKey(requestId)) {
            handleMutualRoomsResponse(requestId, data)
        } else if (trackDevicesRequests.containsKey(requestId)) {
            userEncryptionCoordinator.handleTrackDevicesResponse(requestId, data)
        } else if (resolveAliasRequests.containsKey(requestId)) {
            handleResolveAliasResponse(requestId, data)
        } else if (getRoomSummaryRequests.containsKey(requestId)) {
            handleGetRoomSummaryResponse(requestId, data)
        } else if (joinRoomCallbacks.containsKey(requestId)) {
            handleJoinRoomCallbackResponse(requestId, data)
        } else if (roomSpecificStateRequests.containsKey(requestId)) {
            handleRoomSpecificStateResponse(requestId, data)
        } else if (fullMemberListRequests.containsKey(requestId)) {
            memberProfilesCoordinator.handleFullMemberListResponse(requestId, data)
        } else if (outgoingRequests.containsKey(requestId)) {
            handleOutgoingRequestResponse(requestId, data)
        } else if (mentionsRequests.containsKey(requestId)) {
            handleMentionsListResponse(requestId, data)
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unknown response requestId=$requestId")
        }
    }
    
    fun handleError(requestId: Int, errorMessage: String) {
        // PHASE 5.2/5.3: Acknowledge command even on error (remove from pending queue)
        // Error responses still mean the server received and processed the request
        // Backend responds with same request_id even for errors, so we acknowledge by request_id
        if (requestId > 0) {
            android.util.Log.w("Andromuks", "AppViewModel: PHASE 5.3 - Error response received for request_id=$requestId: $errorMessage (acknowledging command)")
            handleMessageAcknowledgmentByRequestId(requestId)
        } else if (requestId == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - Received error with request_id=0 (no acknowledgment needed)")
        }

        widgetCommandRequests.remove(requestId)?.let { deferred ->
            if (errorMessage.contains("M_NOT_FOUND", ignoreCase = true) &&
                errorMessage.contains("Delayed event not found", ignoreCase = true)
            ) {
                deferred.complete(org.json.JSONObject())
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Widget delayed event missing for requestId=$requestId, returning empty response"
                    )
                }
                return
            }
            deferred.completeExceptionally(IllegalStateException(errorMessage))
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Widget error received for requestId=$requestId")
            return
        }
        
        if (profileRequests.containsKey(requestId)) {
            memberProfilesCoordinator.handleProfileError(requestId, errorMessage)
        } else if (messageRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Message send error for requestId=$requestId: $errorMessage")
            val roomId = messageRequests.remove(requestId)
            if (pendingSendCount > 0) {
                pendingSendCount--
            }
            // Invoke completion callback to prevent UI stalling
            notificationActionCompletionCallbacks.remove(requestId)?.invoke()
        } else if (markReadRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Mark read error for requestId=$requestId: $errorMessage")
            // Remove the failed request from pending
            markReadRequests.remove(requestId)
            // Invoke completion callback to prevent UI stalling
            notificationActionCompletionCallbacks.remove(requestId)?.invoke()
        } else if (synchronized(roomStateRequests) { roomStateRequests.containsKey(requestId) }) {
            android.util.Log.w("Andromuks", "AppViewModel: Room state error for requestId=$requestId: $errorMessage")
            val roomId = synchronized(roomStateRequests) { roomStateRequests.remove(requestId) }
            // PERFORMANCE: Remove from pending requests set on error
            if (roomId != null) {
                pendingRoomStateRequests.remove(roomId)
            }
        } else if (roomSummaryRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Room summary error for requestId=$requestId: $errorMessage")
            roomSummaryRequests.remove(requestId)
        } else if (joinRoomRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Join room error for requestId=$requestId: $errorMessage")
            joinRoomRequests.remove(requestId)
        } else if (leaveRoomRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Leave room error for requestId=$requestId: $errorMessage")
            leaveRoomRequests.remove(requestId)
        } else if (outgoingRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Outgoing request error for requestId=$requestId: $errorMessage")
            outgoingRequests.remove(requestId)
        } else if (eventRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Event request error for requestId=$requestId: $errorMessage")
            val (_, callback) = eventRequests.remove(requestId) ?: return
            callback(null)
        } else if (eventContextRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Event context request error for requestId=$requestId: $errorMessage")
            val (_, callback) = eventContextRequests.remove(requestId) ?: return
            callback(null)
        } else if (mentionsRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Mentions request error for requestId=$requestId: $errorMessage")
            mentionsRequests.remove(requestId)
            isMentionsLoading = false
            mentionEvents = emptyList()
        } else if (mutualRoomsRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Mutual rooms error for requestId=$requestId: $errorMessage")
            val callback = mutualRoomsRequests.remove(requestId) ?: return
            // Call callback with empty list instead of error
            callback(emptyList(), null)
        } else if (userEncryptionInfoRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: User encryption info error for requestId=$requestId: $errorMessage")
            val callback = userEncryptionInfoRequests.remove(requestId) ?: return
            callback(null, errorMessage)
        } else if (trackDevicesRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Track devices error for requestId=$requestId: $errorMessage")
            val callback = trackDevicesRequests.remove(requestId) ?: return
            callback(null, errorMessage)
        } else if (resolveAliasRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Resolve alias error for requestId=$requestId: $errorMessage")
            val callback = resolveAliasRequests.remove(requestId) ?: return
            callback(null)
        } else if (getRoomSummaryRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Get room summary error for requestId=$requestId: $errorMessage")
            val callback = getRoomSummaryRequests.remove(requestId) ?: return
            callback(Pair(null, errorMessage))
        } else if (joinRoomCallbacks.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Join room error for requestId=$requestId: $errorMessage")
            val callback = joinRoomCallbacks.remove(requestId) ?: return
            callback(Pair(null, errorMessage))
        } else if (roomSpecificStateRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Room specific state error for requestId=$requestId: $errorMessage")
            roomSpecificStateRequests.remove(requestId)
        } else if (fullMemberListRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Full member list error for requestId=$requestId: $errorMessage")
            val roomId = fullMemberListRequests.remove(requestId)
            // PERFORMANCE: Remove from pending requests set on error
            if (roomId != null) {
                pendingFullMemberListRequests.remove(roomId)
            }
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Unknown error requestId=$requestId: $errorMessage")
        }
    }
    internal fun persistCurrentUserAvatarMxcIfChanged(avatarMxc: String?) {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val current = prefs.getString("current_user_avatar_mxc", null)
        if (current == avatarMxc) return
        val editor = prefs.edit()
        if (avatarMxc.isNullOrBlank()) {
            editor.remove("current_user_avatar_mxc")
        } else {
            editor.putString("current_user_avatar_mxc", avatarMxc)
        }
        editor.apply()
    }
    
    // Profile management - in-memory only, loaded opportunistically when rendering events
    private val pendingProfileSaves = mutableMapOf<String, MemberProfile>()
    private var profileSaveJob: kotlinx.coroutines.Job? = null
    internal var syncIngestor: net.vrkknn.andromuks.database.SyncIngestor? = null

    /**
     * Ensures SyncIngestor is initialized with the LRU cache listener.
     */
    internal fun ensureSyncIngestor(): net.vrkknn.andromuks.database.SyncIngestor? {
        val context = appContext ?: return null
        if (syncIngestor == null) {
            syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context).apply {
                cacheUpdateListener = object : net.vrkknn.andromuks.database.SyncIngestor.CacheUpdateListener {
                    override fun getCachedRoomIds(): Set<String> = this@AppViewModel.getCachedRoomIds()
                    
                    override fun onEventsForCachedRoom(roomId: String, events: List<TimelineEvent>, requiresFullRerender: Boolean): Boolean {
                        if (requiresFullRerender) {
                            // CRITICAL FIX: Always add events to the raw cache BEFORE invalidating.
                            // Previously, when requiresFullRerender=true (edit/redaction/reaction present
                            // in the same sync_complete), ALL events for this room were silently lost
                            // because invalidateCachedRoom only cleared processed state without adding
                            // the new events to RoomTimelineCache. This is the common case since
                            // sync_complete messages frequently include reactions/edits.
                            RoomTimelineCache.mergePaginatedEvents(roomId, events)
                            RoomTimelineCache.markRoomAccessed(roomId)
                            invalidateCachedRoom(roomId)
                            // Same as pagination: populate MessageVersionsCache / edit chains from merged cache
                            // so pencil + edit history work for edits that arrive via sync_complete only.
                            val merged = RoomTimelineCache.getCachedEvents(roomId)
                            if (!merged.isNullOrEmpty()) {
                                processVersionedMessages(merged)
                            }
                            // Rebuild timeline for the currently-open room so new events appear immediately
                            if (currentRoomId == roomId) {
                                val eventsForChain = RoomTimelineCache.getCachedEventsForTimeline(roomId)
                                if (eventsForChain.isNotEmpty()) {
                                    buildEditChainsFromEvents(eventsForChain, clearExisting = true)
                                    processEditRelationships()
                                    buildTimelineFromChain()
                                }
                            }
                            return false
                        } else {
                            return appendEventsToCachedRoom(roomId, events)
                        }
                    }
                    
                    override fun onNotificationExpected(roomId: String) {
                        // Mark room as actively cached immediately so events from sync_complete will be cached
                        RoomTimelineCache.markRoomAsCached(roomId)
                        // Trigger preemptive pagination so room has a good cache when FCM notification arrives
                        triggerPreemptivePagination(roomId)
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "AppViewModel: onNotificationExpected for $roomId - marked as cached and triggered preemptive pagination")
                        }
                    }
                }
            }
        }
        return syncIngestor
    }

    /**
     * Manages global profile cache size to prevent memory issues.
     */
    internal fun manageGlobalCacheSize() {
        if (ProfileCache.getGlobalCacheSize() > 1000) {
            // Use ProfileCache cleanup method
            ProfileCache.cleanupGlobalProfiles(500) // Keep only 500 most recent
            android.util.Log.w("Andromuks", "AppViewModel: Cleaned up global cache to prevent memory issues")
        }
    }
    
    /**
     * Manages room member cache size to prevent memory issues.
     */
    private fun manageRoomMemberCacheSize(roomId: String) =
        memberProfilesCoordinator.manageRoomMemberCacheSize(roomId)
    
    /**
     * Manages flattened member cache size to prevent memory issues.
     */
    internal fun manageFlattenedMemberCacheSize() {
        if (ProfileCache.getFlattenedCacheSize() > 2000) {
            // Clear oldest entries to make room
            ProfileCache.cleanupFlattenedProfiles(1000) // Keep only 1000 most recent
            android.util.Log.w("Andromuks", "AppViewModel: Cleaned up flattened cache to prevent memory issues")
        }
    }
    
    /**
     * Queues a profile for batch processing. This prevents blocking the main thread
     * when processing large member lists.
     */
    internal fun queueProfileForBatchSave(userId: String, profile: MemberProfile) {
        synchronized(pendingProfileSaves) {
            // Aggressive memory management: Much smaller batch size to prevent OOM
            if (pendingProfileSaves.size >= 50) {
                android.util.Log.w("Andromuks", "AppViewModel: Too many pending profile saves (${pendingProfileSaves.size}), forcing immediate save")
                viewModelScope.launch(Dispatchers.IO) {
                    performBatchProfileSave()
                }
            }
            
            pendingProfileSaves[userId] = profile
            
            // Start batch save job if not already running
            if (profileSaveJob?.isActive != true) {
                profileSaveJob = viewModelScope.launch(Dispatchers.IO) {
                    // Much shorter delay to save more frequently
                    delay(50)
                    performBatchProfileSave()
                }
            }
        }
    }
    
    /**
     * Performs batch processing of queued profiles (in-memory only).
     */
    private suspend fun performBatchProfileSave() {
        val profilesToSave = synchronized(pendingProfileSaves) {
            if (pendingProfileSaves.isEmpty()) return
            val profiles = pendingProfileSaves.toMap()
            pendingProfileSaves.clear()
            profiles
        }
        
        if (profilesToSave.isEmpty()) return
        
        val startTime = System.currentTimeMillis()
        
        // Profiles are cached in-memory only - no DB persistence needed
        // Profiles are loaded opportunistically when rendering events via requestUserProfileOnDemand()
        if (BuildConfig.DEBUG) {
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.d("Andromuks", "AppViewModel: Processed ${profilesToSave.size} profiles (in-memory only) in ${duration}ms")
        }
    }
    
    /**
     * Ensures current user profile is loaded if available.
     */
    fun loadCachedProfiles(context: android.content.Context) {
        try {
            // CRITICAL FIX: Ensure appContext is set before loading profiles
            if (appContext == null) {
                appContext = context.applicationContext
            }
            
            // Get current user ID from SharedPreferences if not already set
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val storedUserId = sharedPrefs.getString("current_user_id", "") ?: ""
            if (currentUserId.isBlank() && storedUserId.isNotBlank()) {
                currentUserId = storedUserId
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restored currentUserId from SharedPreferences: $currentUserId")
            }
            
            // Profiles are cached in-memory only - no DB loading needed
            // Profiles are loaded opportunistically when rendering events via requestUserProfileOnDemand()
            // If current user profile is needed, request it from the backend
            if (currentUserProfile == null && currentUserId.isNotBlank()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting current user profile from server - userId: $currentUserId")
                requestUserProfile(currentUserId)
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load cached profiles", e)
        }
    }
    
    private fun handleOutgoingRequestResponse(requestId: Int, data: Any) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: handleOutgoingRequestResponse called with requestId=$requestId")
        val roomId = outgoingRequests.remove(requestId)
        if (roomId == null && BuildConfig.DEBUG) {
            android.util.Log.w("Andromuks", "AppViewModel: No roomId found for outgoing request $requestId")
        }
    }
    
    /**
     * PHASE 5.3: Handle send_complete to track Matrix server delivery confirmation
     * send_complete has negative request_id (spontaneous from server)
     * Matches to original message by transaction_id stored in operation data
     */
    fun handleSendComplete(eventData: JSONObject, error: String?) {
        val transactionId = eventData.optString("transaction_id", "")
        if (transactionId.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - send_complete has no transaction_id, cannot match to original message")
            return
        }
        
        // Messages are already removed from buffer when sent, so no cleanup needed here
        
        // Find operation by transaction_id (stored when response was received)
        val operation = pendingWebSocketOperations.find { 
            val opTransactionId = it.data["transaction_id"] as? String
            opTransactionId == transactionId
        }
        
        if (operation != null) {
            val command = operation.data["command"] as? String ?: operation.type.removePrefix("command_")
            if (error != null && error.isNotEmpty()) {
                android.util.Log.e("Andromuks", "AppViewModel: PHASE 5.3 - Matrix server error for transaction_id=$transactionId, command=$command: $error")
                logActivity("Matrix Server Error - $command: $error", null)
            } else {
                android.util.Log.i("Andromuks", "AppViewModel: PHASE 5.3 - Matrix server confirmed delivery for transaction_id=$transactionId, command=$command")
                logActivity("Matrix Server Confirmed - $command", null)
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - No pending operation found for transaction_id=$transactionId (may have been already acknowledged)")
        }

        // Local echo handling removed; real events will arrive via sync.
    }

    fun processSendCompleteEvent(eventData: JSONObject) {
        android.util.Log.d("Andromuks", "AppViewModel: processSendCompleteEvent called")
        try {
            val event = TimelineEvent.fromJson(eventData)
            android.util.Log.d("Andromuks", "AppViewModel: Created timeline event from send_complete: ${event.eventId}, type=${event.type}, eventRoomId=${event.roomId}, sender=${event.sender}, currentRoomId=$currentRoomId, currentUserId=$currentUserId")
            
            // SHORTCUT OPTIMIZATION: Update shortcut for this room when user sends a message
            // This drastically reduces shortcut updates - only when user actively sends messages
            // Update shortcuts BEFORE room check so it works for any room, not just current room
            if ((event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") 
                && event.sender == currentUserId && event.roomId.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Message sent by user, preparing shortcut update for room ${event.roomId}")
                
                // Store conversationsApi in local variable to avoid smart cast issue
                val api = conversationsApi
                if (api == null) {
                    android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - conversationsApi is null, cannot update shortcut")
                } else {
                    // Get room from roomMap (should always exist when sending message)
                    var room = roomMap[event.roomId] ?: getRoomById(event.roomId)
                    
                    if (room != null) {
                        // Room found - update timestamp to current message timestamp
                        val updatedTimestamp = normalizeTimestamp(event.timestamp, event.unsigned?.optLong("age_ts") ?: 0L)
                        room = room.copy(sortingTimestamp = updatedTimestamp)
                        android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Room found, updating shortcut for ${room.name} with timestamp $updatedTimestamp")
                        
                        // Update shortcuts asynchronously
                        viewModelScope.launch(Dispatchers.Default) {
                            api.updateShortcutsFromSyncRooms(listOf(room))
                            android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Successfully updated shortcut for room ${room.name} after sending message")
                        }
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - Room ${event.roomId} not found in roomMap or getRoomById, cannot update shortcut")
                    }
                }
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Skipping shortcut update: type=${event.type}, sender=${event.sender}, currentUserId=$currentUserId, roomId=${event.roomId}")
            }
            
            // Only process timeline updates if it's for the current room
            if (event.roomId != currentRoomId) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: send_complete for different room (${event.roomId}), ignoring timeline update")
                return
            }
            
            if (event.type == "m.reaction") {
                // Process reaction events to update messageReactions instead of adding to timeline
                val relatesTo = event.content?.optJSONObject("m.relates_to")
                val emoji = relatesTo?.optString("key", "") ?: ""
                val relatesToEventId = relatesTo?.optString("event_id", "") ?: ""
                
                if (emoji.isNotBlank() && relatesToEventId.isNotBlank()) {
                    // Skip processing our own reactions from send_complete since sync_complete will handle them
                    // This prevents the double processing that causes the toggle behavior
                    if (event.sender == currentUserId) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping send_complete reaction from ourself (will be processed by sync_complete): $emoji from ${event.sender} to $relatesToEventId")
                    } else {
                        // Process reactions from other users in send_complete
                        val reactionEvent = ReactionEvent(
                            roomId = event.roomId,
                            eventId = event.eventId,
                            sender = event.sender,
                            emoji = emoji,
                            relatesToEventId = relatesToEventId,
                            timestamp = normalizeTimestamp(
                                event.timestamp,
                                event.unsigned?.optLong("age_ts") ?: 0L
                            )
                        )
                        processReactionEvent(reactionEvent)
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processed send_complete reaction from other user: $emoji from ${event.sender} to $relatesToEventId")
                    }
                }
            } else if (event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") {
                // Check if this is an edit event
                val isEditEvent = when {
                    event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    else -> false
                }
                
                if (isEditEvent) {
                    // Handle edit via chain system
                    handleEditEventInChain(event)
                    buildTimelineFromChain() // Rebuild to show edit
                } else {
                    // Add regular event to chain (with deduplication)
                    addNewEventToChain(event)
                    buildTimelineFromChain() // Rebuild timeline to include new event
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing send_complete event", e)
        }
    }
    
    fun addTimelineEvent(event: TimelineEvent) = timelineCacheCoordinator.addTimelineEvent(event)
    
    /**
     * Handle freshness check response (single-event paginate with limit=1)
     * This intercepts the response before SyncIngestor processes it
     * Compares the latest event's timeline_rowid with what we have in cache/DB
     * If newer, triggers a full paginate; otherwise continues normally
     */
    private fun handleFreshnessCheckResponse(requestId: Int, data: Any) {
        val roomId = freshnessCheckRequests.remove(requestId) ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Freshness check response for unknown requestId: $requestId")
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling freshness check response for room: $roomId, requestId: $requestId")
        
        try {
            // Parse the single event from response
            val eventsArray = when (data) {
                is org.json.JSONArray -> data
                is org.json.JSONObject -> {
                    val timeline = data.optJSONArray("timeline")
                    if (timeline != null) timeline else org.json.JSONArray().apply { put(data) }
                }
                else -> {
                    android.util.Log.w("Andromuks", "AppViewModel: Unexpected data type in freshness check response: ${data::class.java.simpleName}")
                    return
                }
            }
            
            if (eventsArray.length() == 0) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Freshness check returned no events for $roomId - treating as up to date")
                return
            }
            
            // Get the latest event from response (should be only one)
            val latestEventJson = eventsArray.optJSONObject(eventsArray.length() - 1) ?: run {
                android.util.Log.w("Andromuks", "AppViewModel: Could not parse latest event from freshness check response")
                return
            }
            
            val latestEvent = TimelineEvent.fromJson(latestEventJson)
            val serverLatestRowId = latestEvent.timelineRowid
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Freshness check - server latest event: ${latestEvent.eventId}, timeline_rowid: $serverLatestRowId")
            
            // Get our latest timeline_rowid from RAM cache
            val cachedMetadata = RoomTimelineCache.getLatestCachedEventMetadata(roomId)
            val cachedLatestRowId = cachedMetadata?.timelineRowId ?: 0L
            
            // Process asynchronously to check DB if needed
            viewModelScope.launch {
                val ourLatestRowId = cachedLatestRowId
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Freshness check - our latest timeline_rowid: $ourLatestRowId (RAM), server: $serverLatestRowId")
                
                // Compare: if server has newer events, trigger full paginate
                if (serverLatestRowId > ourLatestRowId) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Server has newer events (server: $serverLatestRowId > ours: $ourLatestRowId), triggering full paginate for $roomId")
                    
                    // Wait for WebSocket to be ready
                    var waitCount = 0
                    val maxWaitAttempts = 50 // Wait up to 5 seconds
                    while (!isWebSocketConnected() && waitCount < maxWaitAttempts) {
                        kotlinx.coroutines.delay(100)
                        waitCount++
                    }
                    
                    if (isWebSocketConnected()) {
                        // Trigger full paginate with max_timeline_id = our latest rowId
                        val paginateRequestId = requestIdCounter++
                        backgroundPrefetchRequests[paginateRequestId] = roomId
                        
                        val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                            "room_id" to roomId,
                            "max_timeline_id" to ourLatestRowId,
                            "limit" to INITIAL_ROOM_PAGINATE_LIMIT,
                            "reset" to false
                        ))
                        
                        if (result == WebSocketResult.SUCCESS) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sent full paginate request after freshness check for $roomId (max_timeline_id: $ourLatestRowId)")
                            markInitialPaginate(roomId, "freshness_check_newer")
                        } else {
                            android.util.Log.w("Andromuks", "AppViewModel: Failed to send paginate after freshness check for $roomId: $result")
                            backgroundPrefetchRequests.remove(paginateRequestId)
                        }
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot fetch fresh data for $roomId")
                    }
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: We are up to date (server: $serverLatestRowId <= ours: $ourLatestRowId), no paginate needed for $roomId")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error handling freshness check response for $roomId", e)
        }
    }
    
    fun handleTimelineResponse(requestId: Int, data: Any) =
        timelineCacheCoordinator.handleTimelineResponse(requestId, data)
    private fun handleRoomStateResponse(requestId: Int, data: Any) {
        val roomId = synchronized(roomStateRequests) { roomStateRequests.remove(requestId) } ?: return
        
        // PERFORMANCE: Remove from pending requests set
        pendingRoomStateRequests.remove(roomId)
        // CRITICAL FIX: Track completion of initial room state loading
        if (allRoomStatesRequested && !allRoomStatesLoaded) {
            var wasInPendingSet = false
            synchronized(pendingRoomStateResponses) {
                wasInPendingSet = pendingRoomStateResponses.remove(roomId)
            }
            
            // Only count rooms that were part of the initial load
            if (wasInPendingSet) {
                completedRoomStateRequests++
                
                // Update progress message
                val progress = if (totalRoomStateRequests > 0) {
                    "$completedRoomStateRequests / $totalRoomStateRequests"
                } else {
                    "$completedRoomStateRequests"
                }
                addStartupProgressMessage("Loading bridge info for all rooms... $progress")
                
                // Check if all room states are loaded
                val remaining = synchronized(pendingRoomStateResponses) {
                    pendingRoomStateResponses.size
                }
                
                if (remaining == 0) {
                    // All room states loaded!
                    allRoomStatesLoaded = true
                    addStartupProgressMessage("Bridge info loaded for all rooms")
                    
                    // NOW allow commands to be sent
                    canSendCommandsToBackend = true
                    flushPendingCommandsQueue()
                    
                    // Check if startup is complete (will now pass)
                    checkStartupComplete()
                    
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: All room states loaded ($completedRoomStateRequests/$totalRoomStateRequests) - commands now allowed, startup can complete")
                    }
                }
            }
        }
        
        // NAVIGATION PERFORMANCE: Update navigation state cache when essential data is loaded
        val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
        navigationCache[roomId] = currentState.copy(
            essentialDataLoaded = true,
            lastPrefetchTime = System.currentTimeMillis()
        )
        
        when (data) {
            is JSONArray -> {
                // Server returns events array directly
                parseRoomStateFromEvents(roomId, data)
            }
            is JSONObject -> {
                val events = data.optJSONArray("events")
                if (events != null) {
                    parseRoomStateFromEvents(roomId, events)
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No events array in room state response")
                }
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleRoomStateResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    /**
     * Get stored room state for a specific room
     */
    fun getRoomState(roomId: String): JSONArray? {
        return roomStatesCache[roomId]
    }
    
    /**
     * Parse room state from state events.
     * OPTIMIZED: Single pass with minimal JSON access; early exits in branches.
     */
    internal fun parseRoomStateFromEvents(roomId: String, events: JSONArray) {
        var name: String? = null
        var canonicalAlias: String? = null
        var topic: String? = null
        var avatarUrl: String? = null
        var isEncrypted = false
        var powerLevels: PowerLevelsInfo? = null
        val pinnedEventIds = mutableListOf<String>()
        var bridgeInfo: BridgeInfo? = null
        
        // OPTIMIZED: Process events in single pass with early exits
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            
            val eventType = event.optString("type")
            val content = event.optJSONObject("content") ?: continue
            
            when (eventType) {
                "m.room.name" -> {
                    name = content.optString("name")?.takeIf { it.isNotBlank() }
                }
                "m.room.canonical_alias" -> {
                    canonicalAlias = content.optString("alias")?.takeIf { it.isNotBlank() }
                }
                "m.room.topic" -> {
                    // OPTIMIZED: Cache parsed topic to avoid re-parsing
                    if (topic.isNullOrBlank()) {
                        topic = content.optString("topic")?.takeIf { it.isNotBlank() }
                        
                        // Fallback to structured format if simple topic not found
                        if (topic.isNullOrBlank()) {
                            val topicContent = content.optJSONObject("m.topic")
                            val textArray = topicContent?.optJSONArray("m.text")
                            if (textArray != null && textArray.length() > 0) {
                                val firstText = textArray.optJSONObject(0)
                                topic = firstText?.optString("body")?.takeIf { it.isNotBlank() }
                            }
                        }
                    }
                }
                "m.room.avatar" -> {
                    avatarUrl = content.optString("url")?.takeIf { it.isNotBlank() }
                }
                "m.room.encryption" -> {
                    // OPTIMIZED: Early exit once encryption detected
                    if (!isEncrypted && content.optString("algorithm")?.isNotBlank() == true) {
                        isEncrypted = true
                    }
                }
                "m.room.power_levels" -> {
                    // OPTIMIZED: Only parse if not already set (each event is definitive)
                    if (powerLevels == null) {
                        val usersObj = content.optJSONObject("users")
                        val usersMap = if (usersObj != null) {
                            mutableMapOf<String, Int>().apply {
                                usersObj.keys()?.forEach { userId ->
                                    put(userId, usersObj.optInt(userId, 0))
                                }
                            }
                        } else {
                            mutableMapOf()
                        }
                        
                        val eventsObj = content.optJSONObject("events")
                        val eventsMap = if (eventsObj != null) {
                            mutableMapOf<String, Int>().apply {
                                eventsObj.keys()?.forEach { ev ->
                                    put(ev, eventsObj.optInt(ev, 0))
                                }
                            }
                        } else {
                            mutableMapOf()
                        }
                        
                        powerLevels = PowerLevelsInfo(
                            users = usersMap,
                            usersDefault = content.optInt("users_default", 0),
                            redact = content.optInt("redact", 50),
                            kick = content.optInt("kick", 50),
                            ban = content.optInt("ban", 50),
                            events = eventsMap,
                            eventsDefault = content.optInt("events_default", 0)
                        )
                    }
                }
                "m.room.pinned_events" -> {
                    // OPTIMIZED: Clear and rebuild pinned events (latest wins)
                    pinnedEventIds.clear()
                    val pinnedArray = content.optJSONArray("pinned")
                    if (pinnedArray != null) {
                        for (j in 0 until pinnedArray.length()) {
                            val eventId = pinnedArray.optString(j)?.takeIf { it.isNotBlank() }
                            if (eventId != null) {
                                pinnedEventIds.add(eventId)
                            }
                        }
                    }
                }
                "m.bridge", "uk.half-shot.bridge" -> {
                    val parsedBridge = parseBridgeInfoEvent(event)
                    if (parsedBridge != null) {
                        bridgeInfo = parsedBridge
                    }
                }
            }
        }
        
        // Create room state object
        val roomState = RoomState(
            roomId = roomId,
            name = name,
            canonicalAlias = canonicalAlias,
            topic = topic,
            avatarUrl = avatarUrl,
            isEncrypted = isEncrypted,
            powerLevels = powerLevels,
            pinnedEventIds = pinnedEventIds,
            bridgeInfo = bridgeInfo
        )
        
        // Extract bridge protocol avatar URL for room list badge display
        val bridgeProtocolAvatarUrl = bridgeInfo?.protocol?.avatarUrl
        // Extract bridge protocol display name
        val bridgeDisplayName = bridgeInfo?.displayName
        
        // If bridge metadata says this is a DM, mark the room as direct (helps bridged DMs show in Direct tab).
        val bridgeSaysDm = bridgeInfo?.roomType?.equals("dm", ignoreCase = true) == true ||
            bridgeInfo?.roomTypeV2?.equals("dm", ignoreCase = true) == true
        
        // Update roomMap with bridge info (DM status and protocol avatar) if room exists
        val existing = roomMap[roomId]
        if (existing != null) {
            var updatedRoom = existing
            var needsUpdate = false
            
            // Update DM status if needed
            if (bridgeSaysDm && !existing.isDirectMessage) {
                updatedRoom = updatedRoom.copy(isDirectMessage = true)
                directMessageRoomIds = directMessageRoomIds + roomId
                needsUpdate = true
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked $roomId as DM via bridge room_type")
            }
            
            // WRITE-ONLY BRIDGE INFO: Once bridge info is set, it's never removed (even if response doesn't include it)
            // Bridge events are static/eternal - if a room is no longer bridged, it will be resolved on app restart
            // This prevents badges from disappearing due to incomplete get_room_state responses
            val finalBridgeProtocolAvatarUrl = bridgeProtocolAvatarUrl ?: existing.bridgeProtocolAvatarUrl
            
            if (finalBridgeProtocolAvatarUrl != existing.bridgeProtocolAvatarUrl) {
                // Bridge info changed (either set for first time, or updated)
                updatedRoom = updatedRoom.copy(bridgeProtocolAvatarUrl = finalBridgeProtocolAvatarUrl)
                needsUpdate = true
                if (BuildConfig.DEBUG) {
                    if (bridgeProtocolAvatarUrl != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Set/updated bridge protocol avatar for $roomId: $bridgeProtocolAvatarUrl")
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: Preserved existing bridge protocol avatar for $roomId: ${existing.bridgeProtocolAvatarUrl} (response didn't include m.bridge)")
                    }
                }
            } else if (finalBridgeProtocolAvatarUrl != null && existing.bridgeProtocolAvatarUrl != null) {
                // Bridge info unchanged but present - ensure it's explicitly set to trigger UI update if needed
                updatedRoom = updatedRoom.copy(bridgeProtocolAvatarUrl = finalBridgeProtocolAvatarUrl)
                // Only update if we haven't already set needsUpdate for other reasons
                if (!needsUpdate) {
                    needsUpdate = true
                }
            }
            
            if (needsUpdate) {
                roomMap[roomId] = updatedRoom
                allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                invalidateRoomSectionCache()
                // CRITICAL FIX: Increment roomListUpdateCounter to trigger UI recomposition
                // This ensures bridge badges appear when room state is updated
                roomListUpdateCounter++
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated roomMap with bridge info for $roomId, triggered UI update")
            }
            
            // CRITICAL FIX: Save bridge info to SharedPreferences cache for future initial connections
            // This allows us to skip get_room_state requests for rooms we already know about
            appContext?.let { context ->
                //val avatarUrlToSave = finalBridgeProtocolAvatarUrl ?: "" // old code, we're attempting to always update bridge info on every get_room_state 
                val avatarUrlToSave = bridgeProtocolAvatarUrl ?: ""
                net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeAvatarUrl(context, roomId, avatarUrlToSave)
                // Also save display name if available
                if (bridgeDisplayName != null) {
                    net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeDisplayName(context, roomId, bridgeDisplayName)
                }
            }
        } else {
            // Room doesn't exist in roomMap yet - store bridge info for later
            // This can happen if get_room_state arrives before sync_complete creates the room
            if (bridgeProtocolAvatarUrl != null || bridgeSaysDm) {
                // Store bridge protocol avatar in a temporary cache that will be applied when room is created
                // For now, we'll rely on the room being created from sync_complete and then updated
                // The bridge info will be applied on the next get_room_state or when room is opened
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Bridge info received for $roomId but room not in roomMap yet (will be applied when room is created)")
            }
            
            if (bridgeSaysDm) {
                // No room yet; remember as DM so when the room is added it will be treated as direct.
                directMessageRoomIds = directMessageRoomIds + roomId
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Recorded $roomId as DM via bridge room_type (no room object yet)")
            }
            
            // CRITICAL FIX: Save bridge info to SharedPreferences even if room doesn't exist yet
            // This ensures we cache the info for when the room is created
            appContext?.let { context ->
                val avatarUrlToSave = bridgeProtocolAvatarUrl ?: ""
                net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeAvatarUrl(context, roomId, avatarUrlToSave)
                // Also save display name if available
                val bridgeDisplayName = bridgeInfo?.displayName
                if (bridgeDisplayName != null) {
                    net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeDisplayName(context, roomId, bridgeDisplayName)
                }
            }
        }

        // Room state is in-memory only; no local persistence.
        
        // ✓ FIX: Only update currentRoomState if this is the currently open room
        // This prevents the room header from flashing through all rooms during shortcut loading
        if (roomId == currentRoomId) {
            currentRoomState = roomState
            roomStateUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✓ Updated current room state - Name: $name, Alias: $canonicalAlias, Topic: $topic, Avatar: $avatarUrl, Encrypted: $isEncrypted")
        }
    }
    
    /**
     * Get bridge display name from a room's cached state or SharedPreferences cache
     * Returns the protocol display name (e.g., "WhatsApp", "Telegram", "Discord") or null if not found
     */
    internal fun getBridgeDisplayNameFromRoomState(roomId: String): String? {
        // First try to get from SharedPreferences cache (fastest, always available if cached)
        val context = appContext
        if (context != null) {
            val cachedDisplayName = net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeDisplayName(context, roomId)
            if (cachedDisplayName != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - Found cached display name for room $roomId: $cachedDisplayName")
                return cachedDisplayName
            }
        }
        
        // Fallback: try to parse from room state cache
        val roomStateEvents = roomStatesCache[roomId]
        if (roomStateEvents == null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - No cached state for room $roomId")
            return null
        }
        
        // Find bridge event in room state
        for (i in 0 until roomStateEvents.length()) {
            val event = roomStateEvents.optJSONObject(i) ?: continue
            val eventType = event.optString("type")
            
            if (eventType == "m.bridge" || eventType == "uk.half-shot.bridge") {
                val bridgeInfo = parseBridgeInfoEvent(event)
                val displayName = bridgeInfo?.displayName
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - Found bridge for room $roomId: displayName=$displayName, protocol=${bridgeInfo?.protocol?.id}, protocolDisplayName=${bridgeInfo?.protocol?.displayName}")
                
                // Cache the display name for future use
                val contextForCache = appContext
                if (displayName != null && contextForCache != null) {
                    net.vrkknn.andromuks.utils.BridgeInfoCache.saveBridgeDisplayName(contextForCache, roomId, displayName)
                }
                
                return displayName
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getBridgeDisplayNameFromRoomState - No bridge event found in cached state for room $roomId (${roomStateEvents.length()} events)")
        return null
    }
    
    private fun parseBridgeInfoEvent(event: JSONObject): BridgeInfo? {
        val content = event.optJSONObject("content") ?: return null
        val stateKey = event.optString("state_key").takeIf { it.isNotBlank() }
        val bridgeBot = content.optString("bridgebot").takeIf { it.isNotBlank() }
        val creator = content.optString("creator").takeIf { it.isNotBlank() }
        val roomType = content.optString("com.beeper.room_type").takeIf { it.isNotBlank() }
        val roomTypeV2 = content.optString("com.beeper.room_type.v2").takeIf { it.isNotBlank() }
        
        val channelObj = content.optJSONObject("channel")
        val protocolObj = content.optJSONObject("protocol")
        
        val channelInfo = channelObj?.let {
            val id = it.optString("id").takeIf { value -> value.isNotBlank() }
            val displayName = it.optString("displayname").takeIf { value -> value.isNotBlank() }
            val avatarUrl = it.optString("avatar_url").takeIf { value -> value.isNotBlank() }
            val receiver = it.optString("fi.mau.receiver").takeIf { value -> value.isNotBlank() }
            
            if (id != null || displayName != null || avatarUrl != null || receiver != null) {
                BridgeChannelInfo(
                    id = id,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    receiver = receiver
                )
            } else {
                null
            }
        }
        
        val protocolInfo = protocolObj?.let {
            val id = it.optString("id").takeIf { value -> value.isNotBlank() }
            val displayName = it.optString("displayname").takeIf { value -> value.isNotBlank() }
            val avatarUrl = it.optString("avatar_url").takeIf { value -> value.isNotBlank() }
            val externalUrl = it.optString("external_url").takeIf { value -> value.isNotBlank() }
            
            if (id != null || displayName != null || avatarUrl != null || externalUrl != null) {
                BridgeProtocolInfo(
                    id = id,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    externalUrl = externalUrl
                )
            } else {
                null
            }
        }
        
        if (
            bridgeBot == null &&
            creator == null &&
            channelInfo == null &&
            protocolInfo == null
        ) {
            return null
        }
        
        return BridgeInfo(
            stateKey = stateKey,
            bridgeBot = bridgeBot,
            creator = creator,
            roomType = roomType,
            roomTypeV2 = roomTypeV2,
            channel = channelInfo,
            protocol = protocolInfo
        )
    }
    
    private fun handleMessageResponse(requestId: Int, data: Any) {
        val roomId = messageRequests.remove(requestId) ?: return
        if (pendingSendCount > 0) {
            pendingSendCount--
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling message response for room: $roomId, pendingSendCount=$pendingSendCount, data: $data")
        
        // PHASE 5.2: Acknowledgment is now handled in handleResponse() for all commands
        // No need to acknowledge here separately
        
        // PHASE 5.3: Extract transaction_id from response for send_complete matching
        // Response contains transaction_id that we'll use to match send_complete later
        if (data is JSONObject) {
            val transactionId = data.optString("transaction_id", "")
            if (transactionId.isNotEmpty()) {
                // Store transaction_id in the pending operation for send_complete matching
                val operation = pendingWebSocketOperations.find { 
                    val opRequestId = it.data["requestId"] as? Int
                    opRequestId == requestId
                }
                if (operation != null && operation.type.startsWith("command_send_message")) {
                    // Update operation with transaction_id for send_complete matching
                    val updatedData = operation.data.toMutableMap()
                    updatedData["transaction_id"] = transactionId
                    val updatedOperation = operation.copy(data = updatedData)
                    pendingWebSocketOperations.remove(operation)
                    pendingWebSocketOperations.add(updatedOperation)
                    savePendingOperationsToStorage()
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - Stored transaction_id=$transactionId for request_id=$requestId")
                }
            }
        }
        
        // Check if the response indicates an error
        var isError = false
        when (data) {
            is Boolean -> {
                if (!data) {
                    isError = true
                    android.util.Log.e("Andromuks", "AppViewModel: Message send failed - boolean false response")
                }
            }
            is JSONObject -> {
                // Check for error indicators in JSON response
                val error = data.optString("error")
                val success = data.optBoolean("success", true)
                if (error.isNotEmpty() || !success) {
                    isError = true
                    android.util.Log.e("Andromuks", "AppViewModel: Message send failed - error: $error, success: $success")
                }
            }
            is String -> {
                // String responses could indicate errors
                if (data.lowercase().contains("error") || data.lowercase().contains("fail")) {
                    isError = true
                    android.util.Log.e("Andromuks", "AppViewModel: Message send appears to be an error response: $data")
                }
            }
        }
        
        // NOTE: We receive send_complete for sent messages, so we don't need to process
        // the response here to avoid duplicates. send_complete will add the event to timeline.
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Message response received, waiting for send_complete for actual event")
        
        // SHORTCUT OPTIMIZATION: Update shortcut when message is successfully sent
        // Update shortcuts immediately on message response (when we know it was sent successfully)
        // rather than waiting for send_complete which may not arrive or may be delayed
        if (!isError && roomId.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Message successfully sent to room $roomId, updating shortcut")
            
            val api = conversationsApi
            if (api != null) {
                // Get room from roomMap and update shortcut
                val room = roomMap[roomId] ?: getRoomById(roomId)
                if (room != null) {
                    // Update timestamp to current time since message was just sent
                    val updatedRoom = room.copy(sortingTimestamp = System.currentTimeMillis())
                    viewModelScope.launch(Dispatchers.Default) {
                        api.updateShortcutsFromSyncRooms(listOf(updatedRoom))
                        android.util.Log.d("Andromuks", "AppViewModel: SHORTCUT - Successfully updated shortcut for room ${updatedRoom.name} after sending message")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - Room $roomId not found in roomMap, cannot update shortcut")
                }
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: SHORTCUT - conversationsApi is null, cannot update shortcut")
            }
        }
    }
    
    /**
     * Refresh shortcuts on app startup from current room list
     * This ensures shortcuts are up-to-date when the app starts, but doesn't update on every sync_complete
     * Only called once per app session after init_complete
     * 
     * Waits for initial sync processing to complete so rooms have their timestamps set
     */
    private fun refreshShortcutsOnStartup() {
        val api = conversationsApi
        if (api == null) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: refreshShortcutsOnStartup - conversationsApi is null, cannot refresh shortcuts")
            return
        }
        
        // Wait for initial sync processing to complete so rooms have their timestamps set
        // This ensures we have room data from sync_complete messages before refreshing shortcuts
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Wait for initial sync to complete (with timeout to avoid waiting forever)
                var waitCount = 0
                val maxWait = 50 // Wait up to 5 seconds (50 * 100ms)
                while (!initialSyncComplete && waitCount < maxWait) {
                    kotlinx.coroutines.delay(100)
                    waitCount++
                }
                
                if (!initialSyncComplete && waitCount >= maxWait) {
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Initial sync processing timeout, refreshing shortcuts anyway")
                }
                
                // Get top rooms from roomMap
                // Include rooms with timestamps OR unread counts OR any activity
                // This handles cases where rooms have events but timestamps aren't set yet
                val sortedRooms = roomMap.values
                    .filter { room ->
                        // Include rooms with timestamps, unread messages, or any activity
                        (room.sortingTimestamp != null && room.sortingTimestamp > 0) ||
                        (room.unreadCount != null && room.unreadCount > 0) ||
                        room.messagePreview != null // Has a message preview (indicates activity)
                    }
                    .sortedWith(compareByDescending<RoomItem> { room ->
                        // Primary sort: timestamp if available
                        room.sortingTimestamp ?: 0L
                    }.thenByDescending { room ->
                        // Secondary sort: unread count
                        room.unreadCount ?: 0
                    })
                
                if (sortedRooms.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Refreshing shortcuts on startup with ${sortedRooms.size} active rooms (top 4 will be used)")
                    // Use updateConversationShortcuts which handles the top 4 selection internally
                    api.updateConversationShortcuts(sortedRooms)
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No active rooms found (no timestamps, unread, or message previews), skipping shortcut refresh on startup")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error refreshing shortcuts on startup", e)
            }
        }
    }
    
    private fun handleRoomStateWithMembersResponse(requestId: Int, data: Any) {
        val callback = roomStateWithMembersRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling room state with members response for requestId: $requestId")
        
        try {
            // Parse the room state data using the utility function
            // This response includes members because include_members: true was sent
            val roomStateInfo = net.vrkknn.andromuks.utils.parseRoomStateResponse(data)
            
            if (roomStateInfo != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Successfully parsed room state with ${roomStateInfo.members.size} members (include_members=true response)")
                callback(roomStateInfo, null)
            } else {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to parse room state response")
                callback(null, "Failed to parse room state")
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error handling room state with members response", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun handleEventResponse(requestId: Int, data: Any) {
        val requestInfo = eventRequests.remove(requestId) ?: return
        val (roomId, callback) = requestInfo
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling event response for requestId: $requestId")
        
        when (data) {
            is JSONObject -> {
                // Create TimelineEvent from the response
                val event = TimelineEvent.fromJson(data)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrieved event: ${event.eventId}, type: ${event.type}, sender: ${event.sender}")
                callback(event)
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleEventResponse: ${data::class.java.simpleName}")
                callback(null)
            }
        }
    }
    
    private fun handleEventContextResponse(requestId: Int, data: Any) {
        val requestInfo = eventContextRequests.remove(requestId) ?: return
        val (roomId, callback) = requestInfo
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling event context response for requestId: $requestId")
        
        when (data) {
            is JSONObject -> {
                // Parse JSONObject response - contains "before", "event", "after"
                val events = mutableListOf<TimelineEvent>()
                
                // Parse "before" array
                val beforeArray = data.optJSONArray("before")
                if (beforeArray != null) {
                    for (i in 0 until beforeArray.length()) {
                        val eventJson = beforeArray.optJSONObject(i) ?: continue
                        try {
                            val event = TimelineEvent.fromJson(eventJson)
                            events.add(event)
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "AppViewModel: Error parsing before event at index $i: ${e.message}", e)
                        }
                    }
                }
                
                // Parse the target event
                val targetEventJson = data.optJSONObject("event")
                if (targetEventJson != null) {
                    try {
                        val event = TimelineEvent.fromJson(targetEventJson)
                        events.add(event)
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error parsing target event: ${e.message}", e)
                    }
                }
                
                // Parse "after" array
                val afterArray = data.optJSONArray("after")
                if (afterArray != null) {
                    for (i in 0 until afterArray.length()) {
                        val eventJson = afterArray.optJSONObject(i) ?: continue
                        try {
                            val event = TimelineEvent.fromJson(eventJson)
                            events.add(event)
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "AppViewModel: Error parsing after event at index $i: ${e.message}", e)
                        }
                    }
                }
                
                // Sort by timestamp to ensure chronological order
                val sortedEvents = events.sortedBy { it.timestamp }
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrieved ${sortedEvents.size} events in context (from JSONObject: ${beforeArray?.length() ?: 0} before, ${if (targetEventJson != null) 1 else 0} target, ${afterArray?.length() ?: 0} after)")
                callback(sortedEvents)
            }
            is JSONArray -> {
                // Parse array of events
                val events = mutableListOf<TimelineEvent>()
                for (i in 0 until data.length()) {
                    val eventJson = data.optJSONObject(i) ?: continue
                    try {
                        val event = TimelineEvent.fromJson(eventJson)
                        events.add(event)
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error parsing event context event at index $i: ${e.message}", e)
                    }
                }
                // Sort by timestamp to ensure chronological order
                val sortedEvents = events.sortedBy { it.timestamp }
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrieved ${sortedEvents.size} events in context")
                callback(sortedEvents)
            }
            is List<*> -> {
                // Handle list of events (already parsed)
                val events = data.mapNotNull { item ->
                    when (item) {
                        is JSONObject -> {
                            try {
                                TimelineEvent.fromJson(item)
                            } catch (e: Exception) {
                                android.util.Log.e("Andromuks", "AppViewModel: Error parsing event context event: ${e.message}", e)
                                null
                            }
                        }
                        is TimelineEvent -> item
                        else -> null
                    }
                }
                val sortedEvents = events.sortedBy { it.timestamp }
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrieved ${sortedEvents.size} events in context (from List)")
                callback(sortedEvents)
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleEventContextResponse: ${data::class.java.simpleName}")
                callback(null)
            }
        }
    }
    
    private fun handleRoomSpecificStateResponse(requestId: Int, data: Any) {
        val roomId = roomSpecificStateRequests.remove(requestId) ?: return
        // BATCH: Clean up all pending keys for this request (batched profile request)
        batchProfileRequestKeys.remove(requestId)?.let { keys ->
            keys.forEach { key ->
                pendingProfileRequests.remove(key)
                synchronized(profileRequestMetadata) { profileRequestMetadata.remove(key) }
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${keys.size} batched profile request keys for requestId=$requestId")
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling room specific state response for room: $roomId, requestId: $requestId")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room specific state response data type: ${data::class.java.simpleName}")
        
        // Check if this is an emoji pack request
        val emojiPackInfo = emojiPackRequests.remove(requestId)
        if (emojiPackInfo != null) {
            val (packRoomId, packName) = emojiPackInfo
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing emoji pack response for pack $packName in room $packRoomId")
            handleEmojiPackResponse(packRoomId, packName, data)
            return
        }
        
        // Check if this is a per-room profile callback request
        val profileCallback = roomSpecificProfileCallbacks.remove(requestId)
        if (profileCallback != null) {
            when (data) {
                is JSONArray -> {
                    if (data.length() > 0) {
                        val event = data.getJSONObject(0)
                        val content = event.optJSONObject("content")
                        if (content != null) {
                            val displayName = content.optString("displayname")?.takeIf { it.isNotBlank() }
                            val avatarUrl = content.optString("avatar_url")?.takeIf { it.isNotBlank() }
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Per-room profile callback - displayName: $displayName, avatarUrl: $avatarUrl")
                            profileCallback(displayName, avatarUrl)
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Per-room profile callback - no content in event")
                            profileCallback(null, null)
                        }
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Per-room profile callback - empty response")
                        profileCallback(null, null)
                    }
                }
                else -> {
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Per-room profile callback - unexpected data type: ${data::class.java.simpleName}")
                    profileCallback(null, null)
                }
            }
            return
        }
        
        // CRITICAL FIX: Find the user ID from request metadata to clean up pending requests
        // This ensures cleanup even if response is empty or doesn't contain member events
        // CRASH FIX: Create a snapshot of entries in a synchronized block to avoid ConcurrentModificationException
        // The map can be modified concurrently (e.g., by timeout handlers), so we need a synchronized snapshot
        val metadataForRequest = synchronized(profileRequestMetadata) {
            profileRequestMetadata.toList().find { (_, metadata) -> metadata.requestId == requestId }
        }
        val userIdFromRequest = metadataForRequest?.second?.userId
        
        when (data) {
            is JSONArray -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing JSONArray response with ${data.length()} items")
                // Parse member events from the response
                parseMemberEventsForProfileUpdate(roomId, data)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: parseMemberEventsForProfileUpdate completed")
                
                // CRITICAL FIX: Clean up pending request even if response was empty
                // This handles cases where user doesn't exist in room or response is empty
                if (data.length() == 0 && userIdFromRequest != null) {
                    val requestKey = "$roomId:$userIdFromRequest"
                    pendingProfileRequests.remove(requestKey)
                    synchronized(profileRequestMetadata) {
                        profileRequestMetadata.remove(requestKey)
                    }
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up pending profile request for empty response: $requestKey")
                }
            }
            is JSONObject -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room specific state response is JSONObject, expected JSONArray")
                // CRITICAL FIX: Clean up on unexpected response format
                if (userIdFromRequest != null) {
                    val requestKey = "$roomId:$userIdFromRequest"
                    pendingProfileRequests.remove(requestKey)
                    synchronized(profileRequestMetadata) {
                        profileRequestMetadata.remove(requestKey)
                    }
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Cleaned up pending profile request for unexpected response format: $requestKey")
                }
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleRoomSpecificStateResponse: ${data::class.java.simpleName}")
                // CRITICAL FIX: Clean up on unhandled response type
                if (userIdFromRequest != null) {
                    val requestKey = "$roomId:$userIdFromRequest"
                    pendingProfileRequests.remove(requestKey)
                    synchronized(profileRequestMetadata) {
                        profileRequestMetadata.remove(requestKey)
                    }
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Cleaned up pending profile request for unhandled response type: $requestKey")
                }
            }
        }
    }
    
    /**
     * Handle emoji pack response from get_specific_room_state
     */
    private fun handleEmojiPackResponse(roomId: String, packName: String, data: Any) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling emoji pack response for $packName in $roomId")
        
        when (data) {
            is JSONArray -> {
                if (data.length() > 0) {
                    val event = data.getJSONObject(0)
                    val content = event.optJSONObject("content")
                    if (content != null) {
                        val images = content.optJSONObject("images")
                        val packInfo = content.optJSONObject("pack")
                        val displayName = packInfo?.optString("display_name") ?: packName
                        
                        if (images != null) {
                            val emojis = mutableListOf<CustomEmoji>()
                            val stickers = mutableListOf<Sticker>()
                            val imageKeys = images.names()
                            if (imageKeys != null) {
                                for (i in 0 until imageKeys.length()) {
                                    val emojiName = imageKeys.optString(i)
                                    val emojiData = images.optJSONObject(emojiName)
                                    if (emojiData != null) {
                                        val usage = emojiData.optJSONArray("usage")
                                        val mxcUrl = emojiData.optString("url")
                                        val info = emojiData.optJSONObject("info")
                                        
                                        if (mxcUrl.isNotBlank() && mxcUrl.startsWith("mxc://")) {
                                            // Parse usage array to determine if this can be used as emoji, sticker, or both
                                            var hasSticker = false
                                            var hasEmoticon = false
                                            
                                            if (usage != null && usage.length() > 0) {
                                                for (j in 0 until usage.length()) {
                                                    val usageItem = usage.optString(j)
                                                    if (usageItem == "sticker") {
                                                        hasSticker = true
                                                    } else if (usageItem == "emoticon") {
                                                        hasEmoticon = true
                                                    }
                                                }
                                            } else {
                                                // No usage key or empty means it can be used as BOTH emoji and sticker
                                                hasEmoticon = true
                                                hasSticker = true
                                            }
                                            
                                            // Add to stickers if it has "sticker" usage (regardless of emoticon)
                                            if (hasSticker) {
                                                stickers.add(Sticker(
                                                    name = emojiName,
                                                    mxcUrl = mxcUrl,
                                                    body = emojiName, // Use name as body/caption
                                                    info = info
                                                ))
                                            }
                                            
                                            // Add to emojis if it has "emoticon" usage or no usage key
                                            // (entries can appear in both lists if they have both usage types)
                                            if (hasEmoticon) {
                                                emojis.add(CustomEmoji(
                                                    name = emojiName,
                                                    mxcUrl = mxcUrl,
                                                    info = info
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (emojis.isNotEmpty()) {
                                // Update or add emoji pack
                                val existingPacks = customEmojiPacks.toMutableList()
                                val existingIndex = existingPacks.indexOfFirst { it.roomId == roomId && it.packName == packName }
                                
                                val newPack = EmojiPack(
                                    packName = packName,
                                    displayName = displayName,
                                    roomId = roomId,
                                    emojis = emojis
                                )
                                
                                // Update singleton cache
                                EmojiPacksCache.updatePack(newPack)
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated emoji pack $packName with ${emojis.size} emojis")
                            }
                            
                            if (stickers.isNotEmpty()) {
                                // Update or add sticker pack
                                val existingStickerPacks = stickerPacks.toMutableList()
                                val existingStickerIndex = existingStickerPacks.indexOfFirst { it.roomId == roomId && it.packName == packName }
                                
                                val newStickerPack = StickerPack(
                                    packName = packName,
                                    displayName = displayName,
                                    roomId = roomId,
                                    stickers = stickers
                                )
                                
                                // Update singleton cache
                                StickerPacksCache.updatePack(newStickerPack)
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated sticker pack $packName with ${stickers.size} stickers")
                            }
                        }
                    }
                }
            }
            else -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Emoji pack response is not JSONArray: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun parseMemberEventsForProfileUpdate(roomId: String, events: JSONArray) {
        val startTime = System.currentTimeMillis()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsing ${events.length()} member events for profile update in room: $roomId")
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Member events data: ${events.toString()}")
        
        // Get existing members from singleton cache
        val existingMembers = RoomMemberCache.getRoomMembers(roomId)
        val memberMap = existingMembers.toMutableMap()
        var updatedProfiles = 0
        val processedUserIds = mutableSetOf<String>()
        
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val eventType = event.optString("type")
            
            if (eventType == "m.room.member") {
                val stateKey = event.optString("state_key")
                val content = event.optJSONObject("content")
                val membership = content?.optString("membership")
                
                if (stateKey.isNotEmpty()) {
                    // Track processed user IDs to clean up pending requests
                    processedUserIds.add(stateKey)
                    
                    if (membership == "join") {
                        // Process joined members - update their profile data
                        val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                        val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                        
                        val newProfile = MemberProfile(displayName, avatarUrl)
                        val previousProfile = memberMap[stateKey]
                        
                        // Only update if the profile data has actually changed
                        if (previousProfile == null || 
                            previousProfile.displayName != displayName || 
                            previousProfile.avatarUrl != avatarUrl) {
                            
                            memberMap[stateKey] = newProfile
                            RoomMemberCache.updateMember(roomId, stateKey, newProfile)
                            
                            // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                            storeMemberProfile(roomId, stateKey, newProfile)
                            
                            // MEMORY MANAGEMENT: Cleanup if needed
                            manageGlobalCacheSize()
                            manageRoomMemberCacheSize(roomId)
                            manageFlattenedMemberCacheSize()
                            
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated profile for $stateKey - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile update completed for $stateKey, triggering memberUpdateCounter")
                            
                            // CRITICAL FIX: If this is the current user, also update currentUserProfile
                            if (stateKey == currentUserId && currentUserProfile == null) {
                                currentUserProfile = UserProfile(
                                    userId = currentUserId,
                                    displayName = displayName,
                                    avatarUrl = avatarUrl
                                )
                                persistCurrentUserAvatarMxcIfChanged(avatarUrl)
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Set currentUserProfile from member event - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            }
                            
                            updatedProfiles++
                            
                            // For very large lists, clear caches aggressively to prevent OOM
                            if (updatedProfiles > 500) {
                                // Clear all caches to prevent OOM
                                ProfileCache.clear()
                                android.util.Log.w("Andromuks", "AppViewModel: Cleared all caches due to large profile update ($updatedProfiles profiles)")
                            } else {
                                // Queue for batch saving for smaller lists
                                queueProfileForBatchSave(stateKey, newProfile)
                            }
                        }
                    } else if (membership == "leave" || membership == "ban") {
                        // Remove members who left or were banned from room cache
                        val wasRemoved = memberMap.remove(stateKey) != null
                        val flattenedKey = "$roomId:$stateKey"
                        val wasRemovedFromFlattened = ProfileCache.hasFlattenedProfile(roomId, stateKey)
                        if (wasRemovedFromFlattened) {
                            ProfileCache.removeFlattenedProfile(roomId, stateKey)
                        }
                        
                        // OPTIMIZED: Remove from indexed cache
                        ProfileCache.removeFromRoomIndex(roomId, stateKey)
                        
                        if (wasRemoved || wasRemovedFromFlattened) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed $stateKey from room cache (membership: $membership)")
                            updatedProfiles++
                        }
                        // Note: Don't remove from global cache as they might be in other rooms
                    }
                }
            }
        }
        
        if (updatedProfiles > 0) {
            val duration = System.currentTimeMillis() - startTime
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedProfiles profiles in room $roomId in ${duration}ms")
            // Trigger UI update since member cache changed
            updateCounter++
            memberUpdateCounter++
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Triggered memberUpdateCounter++ for $updatedProfiles profile updates")
        }
        
        // Clean up pending profile requests for processed users
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("type") == "m.room.member") {
                val stateKey = event.optString("state_key")
                if (stateKey.isNotEmpty()) {
                    val requestKey = "$roomId:$stateKey"
                    pendingProfileRequests.remove(requestKey)
                    synchronized(profileRequestMetadata) {
                        profileRequestMetadata.remove(requestKey) // CRITICAL FIX: Also clean up metadata
                    }
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up pendingProfileRequests for $requestKey")
                }
            }
        }
    }
    
    /**
     * SYNC OPTIMIZATION: Check if current room needs timeline update with diff-based detection
     */
    internal fun checkAndUpdateCurrentRoomTimelineOptimized(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null && currentRoomId.isNotEmpty() && rooms.has(currentRoomId)) {
                // CRITICAL FIX: Check if sync_complete has timeline events BEFORE updating
                // This ensures we process events even if buildTimelineFromChain is async
                val roomData = rooms.optJSONObject(currentRoomId)
                val hasTimelineEvents = roomData?.optJSONArray("events")?.let { it.length() > 0 } ?: false
                
                // Update timeline data first
                updateTimelineFromSync(syncJson, currentRoomId)
                
                // CRITICAL FIX: Always schedule UI update if sync_complete had timeline events
                // buildTimelineFromChain is async, so hash check might happen before timeline updates
                // Always update UI if we processed timeline events to ensure they appear
                if (hasTimelineEvents) {
                    needsTimelineUpdate = true
                    scheduleUIUpdate("timeline")
                    // Update hash after a delay to allow async buildTimelineFromChain to complete
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(100) // Wait for buildTimelineFromChain to complete
                        lastTimelineStateHash = generateTimelineStateHash(timelineEvents)
                    }
                } else {
                    // No timeline events - use hash-based detection
                    val newTimelineStateHash = generateTimelineStateHash(timelineEvents)
                    val timelineStateChanged = newTimelineStateHash != lastTimelineStateHash
                    
                    if (timelineStateChanged) {
                        needsTimelineUpdate = true
                        scheduleUIUpdate("timeline")
                        lastTimelineStateHash = newTimelineStateHash
                    }
                }
            }
        }
    }
    
    private fun checkAndUpdateCurrentRoomTimeline(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null) {
                // Log all rooms in this sync_complete
                val roomKeys = rooms.keys()
                val roomsInSync = mutableListOf<String>()
                while (roomKeys.hasNext()) {
                    roomsInSync.add(roomKeys.next())
                }
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sync_complete contains events for ${roomsInSync.size} rooms: $roomsInSync")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: currentRoomId = $currentRoomId (${if (currentRoomId.isNotEmpty()) "ROOM OPEN" else "NO ROOM OPEN"})")
                
                // Only update timeline if room is currently open
                if (currentRoomId.isNotEmpty() && rooms.has(currentRoomId)) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✓ Processing sync_complete events for OPEN room: $currentRoomId")
                    updateTimelineFromSync(syncJson, currentRoomId)
                } else if (currentRoomId.isNotEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✗ Skipping sync_complete - current room $currentRoomId not in this sync batch")
                    // Events are in-memory only - timeline is updated directly from sync_complete
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ✗ Skipping sync_complete - no room currently open (events will be cached only)")
                }
            }
        }
    }
    
    /**
     * Cache timeline events from sync_complete for all rooms
     * This allows instant room opening if we have enough cached events
     */
    /**
     * Resolve timeline_rowid from room's timeline mapping for events that have timeline_rowid=0.
     * The sync_complete timeline array maps event_rowid -> timeline_rowid; events in the events
     * array may have timeline_rowid=0 and must be looked up by their rowid.
     */
    private fun resolveTimelineRowidsFromRoomData(roomData: JSONObject): JSONArray? {
        val events = roomData.optJSONArray("events") ?: return null
        val timeline = roomData.optJSONArray("timeline") ?: return events

        val mapping = mutableMapOf<Long, Long>()
        for (i in 0 until timeline.length()) {
            val entry = timeline.optJSONObject(i) ?: continue
            if (entry.has("event_rowid") && entry.has("timeline_rowid")) {
                val eventRowid = entry.optLong("event_rowid", -1)
                val timelineRowid = entry.optLong("timeline_rowid", -1)
                if (eventRowid != -1L && timelineRowid > 0) {
                    mapping[eventRowid] = timelineRowid
                }
            }
        }
        if (mapping.isEmpty()) return events

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val rowid = event.optLong("rowid", -1)
            val currentTimelineRowid = event.optLong("timeline_rowid", -1)
            if ((currentTimelineRowid == 0L || currentTimelineRowid == -1L) && rowid != -1L) {
                val resolved = mapping[rowid]
                if (resolved != null) {
                    event.put("timeline_rowid", resolved)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: Resolved timeline_rowid=$resolved for event rowid=$rowid (was $currentTimelineRowid)")
                    }
                }
            }
        }
        return events
    }

    /**
     * Cache timeline events from sync for all rooms.
     * OPTIMIZED: Processes current room immediately, defers others to background thread.
     */
    private fun cacheTimelineEventsFromSync(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val rooms = data.optJSONObject("rooms") ?: return
        
        val roomKeys = rooms.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomData = rooms.optJSONObject(roomId) ?: continue
            val events = resolveTimelineRowidsFromRoomData(roomData) ?: continue
            
            val memberMap = RoomMemberCache.getRoomMembers(roomId)
            RoomTimelineCache.addEventsFromSync(roomId, events, memberMap)
        }
    }
    private fun updateTimelineFromSync(syncJson: JSONObject, roomId: String) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null) {
                val roomData = rooms.optJSONObject(roomId)
                if (roomData != null) {
                    // Update room state if present
                    val meta = roomData.optJSONObject("meta")
                    if (meta != null) {
                        val name = meta.optString("name")?.takeIf { it.isNotBlank() }
                        val canonicalAlias = meta.optString("canonical_alias")?.takeIf { it.isNotBlank() }
                        val topic = meta.optString("topic")?.takeIf { it.isNotBlank() }
                        val avatarUrl = meta.optString("avatar")?.takeIf { it.isNotBlank() }
                        
                        if (name != null || canonicalAlias != null || topic != null || avatarUrl != null) {
                            val roomState = RoomState(
                                roomId = roomId,
                                name = name,
                                canonicalAlias = canonicalAlias,
                                topic = topic,
                                avatarUrl = avatarUrl,
                                powerLevels = currentRoomState?.powerLevels, // Preserve existing power levels
                                pinnedEventIds = currentRoomState?.pinnedEventIds ?: emptyList(), // Preserve existing pinned events
                                bridgeInfo = currentRoomState?.bridgeInfo
                            )
                            // ✓ Safety check: Only update if this is the currently open room
                            if (roomId == currentRoomId) {
                                currentRoomState = roomState
                            } 
                        }
                    }
                    
                    // Process new timeline events
                    // CRITICAL: Resolve timeline_rowid from room's timeline mapping - events may have
                    // timeline_rowid=0 and the actual value is in timeline array (event_rowid -> timeline_rowid)
                    val events = resolveTimelineRowidsFromRoomData(roomData)
                    if (events != null && events.length() > 0) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.length()} new timeline events for room: $roomId")
                        
                        // CRITICAL FIX: Cache events BEFORE processing them into timeline
                        // This ensures events are available in cache even if room is not currently open
                        // This is essential for notification navigation to work correctly
                        val memberMap = RoomMemberCache.getRoomMembers(roomId)
                        RoomTimelineCache.addEventsFromSync(roomId, events, memberMap)
                        
                        processSyncEventsArray(events, roomId)
                    }
                    
                    // NOTE: Read receipts are now processed for ALL rooms in processParsedSyncResult()
                    // This ensures receipts are updated even when rooms are not currently open
                    // No need to process receipts here - they're already handled globally
                }
            }
        }
    }
    private fun processSyncEventsArray(eventsArray: JSONArray, roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processSyncEventsArray called with ${eventsArray.length()} events")
        val memberMap = RoomMemberCache.getRoomMembers(roomId)
        
        // Process events in timestamp order for clean edit handling
        val events = mutableListOf<TimelineEvent>()
        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i)
            if (eventJson != null) {
                val event = TimelineEvent.fromJson(eventJson)
                events.add(event)
            }
        }
        
        // Sort events by timestamp to process in order
        // OPTIMIZED: Only sort if we have events to process
        if (events.isNotEmpty()) {
            events.sortBy { it.timestamp }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} events in timestamp order")
            
            // Count event types for debugging (lightweight operation, can defer if needed)
            val eventTypeCounts = events.groupBy { it.type }.mapValues { it.value.size }
            val ownMessageCount = events.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Event breakdown: $eventTypeCounts (including $ownMessageCount from YOU)")
        }
        
        // Skip processing if no events
        if (events.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No events to process")
            return
        }
        
        // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
        // Defer to background thread if we have many events
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} sync events for version tracking")
        if (events.size > 20) {
            viewModelScope.launch(Dispatchers.Default) {
                processVersionedMessages(events)
            }
        } else {
            processVersionedMessages(events)
        }
        
        for (event in events) {          
            if (event.type == "m.room.member" && event.timelineRowid == -1L) {
                // State member event; update cache only
                val userId = event.stateKey ?: event.sender
                val content = event.content
                if (content != null) {
                    val displayName = content.optString("displayname")?.takeIf { it.isNotBlank() }
                    val avatarUrl = content.optString("avatar_url")?.takeIf { it.isNotBlank() }
                    if (displayName != null || avatarUrl != null) {
                        val profile = MemberProfile(displayName, avatarUrl)
                        RoomMemberCache.updateMember(roomId, userId, profile)
                        // PERFORMANCE: Also add to global cache for O(1) lookups
                        ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
                    }
                }
            } else if (event.type == "m.room.member" && event.timelineRowid >= 0L) {
                // Timeline member event (join/leave that should show in timeline)
                // Also extract and update member profile if display name/avatar changed
                updateMemberProfileFromTimelineEvent(roomId, event)
                addNewEventToChain(event)
            } else if (event.type == "m.room.redaction" ||
                (event.type == "m.room.encrypted" && event.decryptedType == "m.room.redaction")) {
                
                // Add redaction event to timeline so findLatestRedactionEvent can find it
                addNewEventToChain(event)
                
                // Extract the event ID being redacted
                // For encrypted redactions, check decrypted content; for non-encrypted, check content
                val redactsEventId = when {
                    event.type == "m.room.encrypted" && event.decryptedType == "m.room.redaction" -> {
                        event.decrypted?.optString("redacts")?.takeIf { it.isNotBlank() }
                    }
                    else -> {
                        event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                    }
                }
                
                if (redactsEventId != null) {
                    
                    // Find and update the original message in the timeline
                    val currentEvents = timelineEvents.toMutableList()
                    val originalIndex = currentEvents.indexOfFirst { it.eventId == redactsEventId }
                    
                    if (originalIndex >= 0) {
                        val originalEvent = currentEvents[originalIndex]
                        // Create a copy with redactedBy set
                        val redactedEvent = originalEvent.copy(redactedBy = event.eventId)
                        currentEvents[originalIndex] = redactedEvent
                        timelineEvents = currentEvents
                        
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Marked event $redactsEventId as redacted by ${event.eventId} (type=${event.type}, decryptedType=${event.decryptedType})")
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Could not find event $redactsEventId to mark as redacted (might be in paginated history)")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Redaction event has no 'redacts' field (type=${event.type}, decryptedType=${event.decryptedType})")
                }
                
                // Request sender profile if missing from cache
                if (!memberMap.containsKey(event.sender)) {
                    requestUserProfile(event.sender, roomId)
                } 
                
                // OPTIMIZED: UI update will be triggered by buildTimelineFromChain() at the end of processSyncEventsArray
            } else if (event.type == "m.reaction") {
                // CRITICAL FIX: Add reaction events to cache so they can be restored when reopening room
                // Reaction events are processed in-memory AND cached for persistence
                RoomTimelineCache.mergePaginatedEvents(roomId, listOf(event))
                
                // Process reaction events (don't add to timeline)
                val content = event.content
                if (content != null) {
                    val relatesTo = content.optJSONObject("m.relates_to")
                    if (relatesTo != null) {
                        val relatesToEventId = relatesTo.optString("event_id")
                        val emoji = relatesTo.optString("key")
                        val relType = relatesTo.optString("rel_type")
                        
                        if (relatesToEventId.isNotBlank() && emoji.isNotBlank() && relType == "m.annotation") {
                            // Check if this reaction has been redacted
                            if (event.redactedBy != null) {
                                // Remove this reaction by processing it as if the user toggled it off
                                val reactionEvent = ReactionEvent(
                                    roomId = roomId,
                                    eventId = event.eventId,
                                    sender = event.sender,
                                    emoji = emoji,
                                    relatesToEventId = relatesToEventId,
                                    timestamp = normalizeTimestamp(
                                        event.timestamp,
                                        event.unsigned?.optLong("age_ts") ?: 0L
                                    )
                                )
                                // Process the reaction - it will be removed since the user is already in the list
                                processReactionEvent(reactionEvent)
                            } else {
                                // Normal reaction, add it                             
                                // Process all reactions normally - no special handling for our own reactions
                                val reactionEvent = ReactionEvent(
                                    roomId = roomId,
                                    eventId = event.eventId,
                                    sender = event.sender,
                                    emoji = emoji,
                                    relatesToEventId = relatesToEventId,
                                    timestamp = normalizeTimestamp(
                                        event.timestamp,
                                        event.unsigned?.optLong("age_ts") ?: 0L
                                    )
                                )
                                processReactionEvent(reactionEvent)
                            }
                        }
                    }
                }
            } else if (event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") {
                // Log if this is our own message from another client
                val isOwnMessage = event.sender == currentUserId
                if (isOwnMessage) {
                    val bodyPreview = when {
                        event.type == "m.room.message" -> event.content?.optString("body", "")?.take(50)
                        event.type == "m.room.encrypted" -> event.decrypted?.optString("body", "")?.take(50)
                        else -> ""
                    }
                }
                
                // Check if this is an edit event (m.replace relationship)
                val isEditEvent = when {
                    event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    else -> false
                }
                
                if (isEditEvent) {
                    // Handle edit event using edit chain system
                    handleEditEventInChain(event)
                } else {
                    // Add new timeline event to chain (works for messages from ANY client)
                    addNewEventToChain(event)
                }
            } else if (event.type == "m.room.pinned_events" || event.type == "m.room.name" || event.type == "m.room.topic" || event.type == "m.room.avatar") {
                // System events that should appear in timeline
                addNewEventToChain(event)
            } 
        }
        
        // Summary of what was processed
        val addedToTimeline = events.count { event ->
            (event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") ||
            (event.type == "m.room.member" && event.timelineRowid >= 0L) ||
            (event.type == "m.room.redaction") ||
            (event.type == "m.room.pinned_events" || event.type == "m.room.name" || event.type == "m.room.topic" || event.type == "m.room.avatar")
        }
        val memberStateUpdates = events.count { event ->
            event.type == "m.room.member" && event.timelineRowid == -1L
        }
        val reactions = events.count { it.type == "m.reaction" }
        val unhandled = events.size - addedToTimeline - memberStateUpdates - reactions
        
        // Update room state from new timeline events (name/avatar) if present
        // CRITICAL: Only update room state if this is the currently open room
        if (roomId == currentRoomId) {
            updateRoomStateFromTimelineEvents(currentRoomId, events)
        }
        
        // Only process edit relationships for new edit events
        val newEditEvents = events.filter { event ->
            val isEditEvent = when {
                event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                else -> false
            }
            isEditEvent
        }
        
        if (newEditEvents.isNotEmpty()) {
            processNewEditRelationships(newEditEvents)
        }
        
        // CRITICAL FIX: Only rebuild timeline if this is the currently open room
        // Otherwise we're rebuilding the timeline for the wrong room, causing flickering
        if (roomId == currentRoomId) {
            // Build timeline from chain
            val timelineCountBefore = timelineEvents.size
            buildTimelineFromChain()
            val timelineCountAfter = timelineEvents.size
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Rebuilt timeline after processing sync events for current room $roomId: before=$timelineCountBefore, after=$timelineCountAfter")
            
            // Timeline is updated directly from sync_complete events via processSyncEventsArray()
            // No DB persistence or refresh needed - all data is in-memory
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping timeline rebuild for room $roomId (not currently open, currentRoomId=$currentRoomId)")
        }
        
        // Mark room as read for the newest event only if room is actually visible (not just a minimized bubble)
        // Check if this is a bubble and if it's visible
        val shouldMarkAsRead = if (BubbleTracker.isBubbleOpen(roomId)) {
            // Bubble exists - only mark as read if it's visible/maximized
            BubbleTracker.isBubbleVisible(roomId)
        } else {
            // Not a bubble - mark as read (normal room view)
            true
        }
        
        if (shouldMarkAsRead) {
            val newestEvent = events.maxByOrNull { it.timestamp }
            if (newestEvent != null) {
                markRoomAsRead(roomId, newestEvent.eventId)
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping mark as read for room $roomId (bubble is minimized)")
        }
    }
    
    /**
     * Handles edit events using the edit chain tracking system.
     * This ensures clean edit handling by tracking the edit chain properly.
     */
    private fun handleEditEventInChain(editEvent: TimelineEvent) =
        editVersionCoordinator.handleEditEventInChain(editEvent)

    /**
     * Adds a new timeline event to the edit chain.
     * Includes deduplication to prevent the same event from being added multiple times.
     */
    private fun addNewEventToChain(event: TimelineEvent) =
        editVersionCoordinator.addNewEventToChain(event)

    /**
     * Processes edit relationships for new edit events only.
     */
    private fun processNewEditRelationships(newEditEvents: List<TimelineEvent>) =
        editVersionCoordinator.processNewEditRelationships(newEditEvents)

    /**
     * Processes edit relationships to build the complete edit chain.
     */
    internal fun processEditRelationships() = editVersionCoordinator.processEditRelationships()

    /**
     * Finds the end of an edit chain by following replacedBy links.
     * OPTIMIZED: Now uses memoization to avoid repeated traversals (O(n²) -> O(n))
     */
    private fun findChainEndOptimized(startEventId: String, cache: MutableMap<String, EventChainEntry?>) =
        editVersionCoordinator.findChainEndOptimized(startEventId, cache)

    /**
     * LEGACY: Finds the end of an edit chain by following replacedBy links.
     * Kept for backward compatibility but should use findChainEndOptimized instead.
     */
    private fun findChainEnd(startEventId: String) = editVersionCoordinator.findChainEnd(startEventId)

    
    /**
     * Builds the timeline from the edit chain mapping.
     * Heavy work (snapshot, chain walk, sort) runs on Dispatchers.Default; only the final
     * state update runs on Main to keep the UI responsive when opening a room from cache.
     * @param rebuildComplete If non-null, completed when the rebuild and state update are done (so callers can run post-rebuild steps).
     */
    internal fun buildTimelineFromChain(rebuildComplete: CompletableDeferred<Unit>? = null) {
        viewModelScope.launch(Dispatchers.Main) {
            val shouldSkip = shouldSkipTimelineRebuild.value
            if (shouldSkip) {
                val currentRoom = currentRoomId
                if (currentRoom != null) {
                    roomsNeedingRebuildDuringBatch.add(currentRoom)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "buildTimelineFromChain: Batch processing active (shouldSkipTimelineRebuild=true) - deferring rebuild for $currentRoom (will rebuild after batch completes)")
                    }
                }
                rebuildComplete?.complete(Unit)
                return@launch
            }
            viewModelScope.launch(Dispatchers.Default) {
                executeTimelineRebuild(rebuildComplete)
            }
        }
    }
    
    /**
     * Internal function that performs the actual timeline rebuild.
     * Separated from buildTimelineFromChain() to allow debouncing.
     * @param rebuildComplete If non-null, completed on Main after state is updated (so callers can run post-rebuild steps).
     */
    private suspend fun executeTimelineRebuild(rebuildComplete: CompletableDeferred<Unit>? = null) {
        // DIAGNOSTIC: Track when this is called
        val callStack = Thread.currentThread().stackTrace.take(5).joinToString(" -> ") { it.methodName }
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "buildTimelineFromChain: Called (eventChainMap.size=${eventChainMap.size}, currentRoomId=$currentRoomId, callStack=$callStack)"
        )
        
        try {
            val timelineEvents = mutableListOf<TimelineEvent>()
            val redactionMap = mutableMapOf<String, String>() // Map of target eventId -> redaction eventId
        
            // THREAD SAFETY: Create snapshot of map entries to prevent ConcurrentModificationException
            // This prevents crashes when the map is modified concurrently (e.g., by background coroutines)
            // Use synchronized block to safely create snapshot even if map is being modified
            val eventChainSnapshot = try {
                synchronized(eventChainMap) {
                    eventChainMap.toMap()
                }
            } catch (e: ConcurrentModificationException) {
                android.util.Log.w("Andromuks", "AppViewModel: ConcurrentModificationException while creating snapshot, retrying", e)
                // Retry once - create a new map with current entries
                synchronized(eventChainMap) {
                    eventChainMap.toMap()
                }
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "buildTimelineFromChain: Created snapshot with ${eventChainSnapshot.size} entries")
        
            // First collect all redactions so order doesn't matter
            // CRITICAL: Handle both encrypted and non-encrypted redaction events for E2EE rooms
            for ((_, entry) in eventChainSnapshot) {
                val redactionEvent = entry.ourBubble
                if (redactionEvent != null) {
                    // Check if this is a redaction event (can be m.room.redaction or m.room.encrypted with decryptedType == m.room.redaction)
                    val isRedaction = redactionEvent.type == "m.room.redaction" ||
                        (redactionEvent.type == "m.room.encrypted" && redactionEvent.decryptedType == "m.room.redaction")
                    
                    if (isRedaction) {
                        // For encrypted redactions, check decrypted content; for non-encrypted, check content
                        val redactsEventId = when {
                            redactionEvent.type == "m.room.encrypted" && redactionEvent.decryptedType == "m.room.redaction" -> {
                                // Encrypted redaction: redacts is in decrypted content
                                redactionEvent.decrypted?.optString("redacts")?.takeIf { it.isNotBlank() }
                            }
                            else -> {
                                // Non-encrypted redaction: redacts is in content
                                redactionEvent.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                            }
                        }
                        
                        if (redactsEventId != null) {
                            redactionMap[redactsEventId] = redactionEvent.eventId
                            if (BuildConfig.DEBUG) android.util.Log.d(
                                "Andromuks",
                                "buildTimelineFromChain: redaction ${redactionEvent.eventId} (type=${redactionEvent.type}, decryptedType=${redactionEvent.decryptedType}) targets $redactsEventId"
                            )
                        } else if (BuildConfig.DEBUG) {
                            android.util.Log.w(
                                "Andromuks",
                                "buildTimelineFromChain: Redaction event ${redactionEvent.eventId} has no redacts field (type=${redactionEvent.type}, decryptedType=${redactionEvent.decryptedType})"
                            )
                        }
                    }
                }
            }

            // DIAGNOSTIC: Log snapshot statistics
            val totalEntries = eventChainSnapshot.size
            var nullBubbleCount = 0
            var redactionCount = 0
            var processedCount = 0
            var errorCount = 0
            
            // Now process all non-redaction events with the populated map
            for ((eventId, entry) in eventChainSnapshot) {
                val ourBubble = entry.ourBubble
                if (ourBubble == null) {
                    nullBubbleCount++
                    android.util.Log.w("Andromuks", "buildTimelineFromChain: Entry $eventId has null ourBubble - skipping")
                    continue
                }
                // Skip redaction events (both encrypted and non-encrypted) - they're used to build redactionMap only
                if (ourBubble.type == "m.room.redaction" ||
                    (ourBubble.type == "m.room.encrypted" && ourBubble.decryptedType == "m.room.redaction")) {
                    redactionCount++
                    continue
                }
                try {
                    // Apply redaction if this event is targeted
                    // CRITICAL: Check both redactionMap (from redaction events) and the original event's redactedBy
                    // This handles cases where the backend already set redacted_by in the JSON (e.g., pagination responses)
                    val baseEvent = getFinalEventForBubble(entry)
                    val redactedByFromMap = redactionMap[eventId]
                    val redactedByFromEvent = baseEvent.redactedBy
                    
                    if (BuildConfig.DEBUG && (redactedByFromMap != null || redactedByFromEvent != null)) {
                        android.util.Log.d(
                            "Andromuks",
                            "buildTimelineFromChain: Event $eventId - redactedByFromMap=$redactedByFromMap, redactedByFromEvent=$redactedByFromEvent, baseEvent.type=${baseEvent.type}, baseEvent.decryptedType=${baseEvent.decryptedType}"
                        )
                    }
                    
                    // Prefer redactionMap value (from redaction events), but fall back to event's redactedBy if present
                    val finalRedactedBy = redactedByFromMap ?: redactedByFromEvent
                    
                    val finalEvent = if (finalRedactedBy != null) {
                        if (BuildConfig.DEBUG) {
                            val source = if (redactedByFromMap != null) "redactionMap" else "event.redactedBy"
                            android.util.Log.d(
                                "Andromuks",
                                "buildTimelineFromChain: applying redaction $finalRedactedBy to event $eventId (from $source)"
                            )
                        }
                        baseEvent.copy(redactedBy = finalRedactedBy)
                    } else {
                        baseEvent
                    }
                    
                    timelineEvents.add(finalEvent)
                    processedCount++
                    if (finalEvent.redactedBy != null) {
                        if (BuildConfig.DEBUG) android.util.Log.d(
                            "Andromuks",
                            "buildTimelineFromChain: timeline event ${finalEvent.eventId} marked redacted by ${finalEvent.redactedBy}"
                        )
                    }
                    //android.util.Log.d("Andromuks", "AppViewModel: Added event for ${eventId} with final content from ${entry.replacedBy ?: eventId}${if (redactedBy != null) " (redacted by $redactedBy)" else ""}")
                } catch (e: Exception) {
                    errorCount++
                    android.util.Log.e("Andromuks", "AppViewModel: Error processing event ${eventId} in buildTimelineFromChain", e)
                    // Skip this event if there's an error (prevents crash from corrupt edit chain)
                    // Add the base event without following edit chain as fallback
                    // ourBubble is guaranteed to be non-null here because we checked and continued if null above
                    timelineEvents.add(ourBubble)
                    processedCount++
                }
            }
            
            // DIAGNOSTIC: Log summary
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "buildTimelineFromChain: Processed $processedCount events from $totalEntries entries " +
                "(nullBubble=$nullBubbleCount, redactions=$redactionCount, errors=$errorCount, " +
                "timelineEvents.size=${timelineEvents.size})"
            )
            
            // Sort by timestamp, then timelineRowid (server ordering), then eventId (deterministic tiebreak)
            val sortedTimelineEvents = timelineEvents.sortedWith(
                compareBy({ it.timestamp }, { it.timelineRowid }, { it.eventId })
            )
            
            // State updates and animation logic must run on Main
            withContext(Dispatchers.Main) {
                val previousEventIds = this@AppViewModel.timelineEvents.map { it.eventId }.toSet()
                val newEventIds = sortedTimelineEvents.map { it.eventId }.toSet()
                val actuallyNewMessages = newEventIds - previousEventIds
                val isInitialRoomLoad = this@AppViewModel.timelineEvents.isEmpty() && sortedTimelineEvents.isNotEmpty()
                val roomOpenTimestamp = roomOpenTimestamps[currentRoomId]
                val timelineForegroundAt = timelineForegroundTimestamps[currentRoomId]
                val animationCutoverTimestamp = timelineForegroundAt ?: roomOpenTimestamp

                if (actuallyNewMessages.isNotEmpty() && !isInitialRoomLoad && animationCutoverTimestamp != null) {
                    val currentTime = System.currentTimeMillis()
                    var shouldPlaySound = false
                    var newMessageRoomId: String? = null
                    // Build lookup map once to avoid O(n²) find() inside forEach
                    val sortedEventById = sortedTimelineEvents.associateBy { it.eventId }
                    actuallyNewMessages.forEach { eventId ->
                        val newEvent = sortedEventById[eventId]
                        val isNewMessage = newEvent?.let { it.timestamp > animationCutoverTimestamp } ?: false
                        if (isNewMessage) newMessageAnimations[eventId] = currentTime
                        val isFromOtherUser = newEvent?.let { event ->
                            newMessageRoomId = event.roomId
                            (event.type == "m.room.message" || event.type == "m.room.encrypted") && event.sender != currentUserId
                        } ?: false
                        if (isFromOtherUser) shouldPlaySound = true
                    }
                    if (shouldPlaySound && BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: Sound suppressed for received message in room $newMessageRoomId (sound only plays for messages we send)")
                    }
                }
                this@AppViewModel.timelineEvents = sortedTimelineEvents
                timelineUpdateCounter++
                updateCounter++
                isTimelineLoading = false
                currentRoomId?.let { persistRenderableEvents(it, sortedTimelineEvents) }
                rebuildComplete?.complete(Unit)
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error in buildTimelineFromChain", e)
            if (this@AppViewModel.timelineEvents.isEmpty()) {
                android.util.Log.w("Andromuks", "AppViewModel: Timeline build failed and timeline is empty, keeping empty timeline")
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Timeline build failed, preserving existing ${this@AppViewModel.timelineEvents.size} events")
            }
            withContext(Dispatchers.Main) {
                isTimelineLoading = false
                rebuildComplete?.complete(Unit)
            }
        }
    }
    /**
     * Load older messages using backend pagination (cache-only approach, no DB loading)
     * Replaced DB-based loading with backend pagination request
     */
    fun loadOlderMessages(roomId: String, showToast: Boolean = true) {
        // CACHE-ONLY APPROACH: Use backend pagination instead of DB loading
        // requestPaginationWithSmallestRowId() handles backend pagination using cache-only
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: loadOlderMessages($roomId) - using backend pagination")
        requestPaginationWithSmallestRowId(roomId, limit = INITIAL_ROOM_PAGINATE_LIMIT)
    }
    
    /**
     * Request pagination from backend using the smallest row ID from cache only (no DB).
     * Used for pull-to-refresh to load older events.
     * 
     * @param roomId The room ID to paginate
     * @param limit Number of events to fetch (default INITIAL_ROOM_PAGINATE_LIMIT)
     */
    fun requestPaginationWithSmallestRowId(roomId: String, limit: Int = INITIAL_ROOM_PAGINATE_LIMIT) {
        if (isPaginating) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Already paginating, skipping request for $roomId")
            return
        }
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot paginate for $roomId")
            return
        }
        
        val context = appContext ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: No app context, cannot paginate for $roomId")
            return
        }
        
        // CACHE-ONLY APPROACH: Use cache to determine oldest event (fallback to tracked value)
        isPaginating = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // CRITICAL FIX: Use ONLY positive timeline_rowid values for pagination
                // According to Webmucks backend documentation and implementation:
                // - Negative timeline_rowid values are for state events and cannot be used for pagination
                // - Pagination requires positive timeline_rowid values only
                // - Positive N means "return events with timeline.rowid < N" (older than N)
                // - 0 means "no upper bound / fetch recent" (ONLY use when cache is empty)
                val cacheEventCount = RoomTimelineCache.getCachedEventCount(roomId)
                val oldestCachedRowId = RoomTimelineCache.getOldestPositiveCachedEventRowId(roomId) // CRITICAL: Only positive values!
                val oldestTrackedRowId = oldestRowIdPerRoom[roomId] // This should already be positive (tracked from responses)
                
                // CRITICAL FIX: Prefer tracked value over cached value to avoid getting stuck on same max_timeline_id
                // The tracked value is updated from the pagination response and represents the actual oldest
                // positive timelineRowid we received, while the cache value might be stale if duplicates were filtered out
                // Only use 0 when cache is actually empty (no events at all)
                val oldestRowId = when {
                    oldestTrackedRowId != null && oldestTrackedRowId != -1L && oldestTrackedRowId > 0 -> oldestTrackedRowId
                    oldestCachedRowId != -1L && oldestCachedRowId > 0 -> oldestCachedRowId
                    cacheEventCount == 0 -> {
                        // Cache is empty - use 0 to request most recent events
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "AppViewModel: Cache is empty for room $roomId. Using max_timeline_id=0 to request most recent events.")
                        }
                        0L
                    }
                    else -> {
                        // Cache has events but we couldn't get oldest positive row ID - this shouldn't happen
                        // Log warning and use 0 as fallback
                        android.util.Log.w("Andromuks", "AppViewModel: ⚠️ Cache has $cacheEventCount events for room $roomId but couldn't get oldest positive timelineRowId (cached=$oldestCachedRowId, tracked=$oldestTrackedRowId). Using max_timeline_id=0 as fallback.")
                        0L
                    }
                }
                
                // Use the oldest rowId to request events older than this
                // Note: max_timeline_id=0 means "get the oldest available events"
                val maxTimelineId = oldestRowId
                
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Using oldest timelineRowId=$oldestRowId (cached=$oldestCachedRowId, tracked=$oldestTrackedRowId) as max_timeline_id=$maxTimelineId for room $roomId")
                }
                
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Requesting pagination for $roomId with oldest timelineRowId=$oldestRowId, max_timeline_id=$maxTimelineId, limit=$limit"
                )
                
                val paginateRequestId = requestIdCounter++
                paginateRequests[paginateRequestId] = roomId
                paginateRequestMaxTimelineIds[paginateRequestId] = maxTimelineId // Track max_timeline_id used for progress detection
                
                // Log paginate request
                android.util.Log.d("Andromuks", "paginate: Room - $roomId, MaxTimelineRowID - $maxTimelineId, Limit - $limit")
                
                val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                    "room_id" to roomId,
                    "max_timeline_id" to maxTimelineId,
                    "limit" to limit,
                    "reset" to false
                ))
                
                if (result != WebSocketResult.SUCCESS) {
                    android.util.Log.w("Andromuks", "AppViewModel: Failed to send paginate request for $roomId: $result")
                    withContext(Dispatchers.Main) {
                        paginateRequests.remove(paginateRequestId)
                        paginateRequestMaxTimelineIds.remove(paginateRequestId)
                        isPaginating = false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error determining oldest timelineRowId", e)
                withContext(Dispatchers.Main) {
                    isPaginating = false
                }
            }
        }
    }
    
    /**
     * Gets the final event for a bubble, following the edit chain to the latest edit.
     */
    private fun getFinalEventForBubble(entry: EventChainEntry) =
        editVersionCoordinator.getFinalEventForBubble(entry)

    /**
     * Finds events that are superseded by a new event.
     *
     * @param newEvent The new event that might supersede others
     * @param existingEvents List of existing events to check
     * @return List of event IDs that are superseded by the new event
     */
    private fun findSupersededEvents(newEvent: TimelineEvent, existingEvents: List<TimelineEvent>) =
        editVersionCoordinator.findSupersededEvents(newEvent, existingEvents)

    /**
     * Merges edit content into the original event.
     *
     * @param originalEvent The original event to be updated
     * @param editEvent The edit event containing the new content
     * @return A new TimelineEvent with merged content
     */
    private fun mergeEditContent(originalEvent: TimelineEvent, editEvent: TimelineEvent) =
        editVersionCoordinator.mergeEditContent(originalEvent, editEvent)

    
    fun markRoomAsRead(roomId: String, eventId: String) =
        readReceiptsTypingCoordinator.markRoomAsRead(roomId, eventId)

    fun getRoomSummary(roomId: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Getting room summary for invite: $roomId")
        
        val summaryRequestId = requestIdCounter++
        roomSummaryRequests[summaryRequestId] = roomId
        val via = roomId.substringAfter(":").substringBefore(".") // Extract server from room ID
        sendWebSocketCommand("get_room_summary", summaryRequestId, mapOf(
            "room_id_or_alias" to roomId,
            "via" to listOf(via)
        ))
    }
    
    fun acceptRoomInvite(roomId: String) = roomInvitesCoordinator.acceptRoomInvite(roomId)

    fun refuseRoomInvite(roomId: String) = roomInvitesCoordinator.refuseRoomInvite(roomId)
    
    fun handleMarkReadResponse(requestId: Int, success: Boolean) {
        val roomId = markReadRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Mark read response for room $roomId: $success")
            // Remove the request from pending
            markReadRequests.remove(requestId)
            
            // PHASE 5.2: Acknowledgment is now handled in handleResponse() for all commands
            // No need to acknowledge here separately
            
            // Invoke completion callback for notification actions
            notificationActionCompletionCallbacks.remove(requestId)?.invoke()
        }
    }
    
    private fun handleMessageAcknowledgmentByRequestId(requestId: Int) =
        persistenceCoordinator.handleMessageAcknowledgmentByRequestId(requestId)
    
    fun handleRoomSummaryResponse(requestId: Int, data: Any) {
        val roomId = roomSummaryRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room summary response for room $roomId")
            // Room summary received - could be used to update invite details if needed
            roomSummaryRequests.remove(requestId)
        }
    }
    
    fun handleJoinRoomResponse(requestId: Int, data: Any) {
        val roomId = joinRoomRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Join room response for room $roomId")
            // Room join successful - invite will be removed from sync
            joinRoomRequests.remove(requestId)
        }
    }
    
    fun leaveRoom(roomId: String, reason: String? = null) =
        roomInvitesCoordinator.leaveRoom(roomId, reason)
    
    fun handleLeaveRoomResponse(requestId: Int, data: Any) {
        val roomId = leaveRoomRequests[requestId]
        if (roomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Leave room response for room $roomId")
            // Room leave successful - room will be removed from sync
            leaveRoomRequests.remove(requestId)
        }
    }
    
    fun executeCommand(roomId: String, text: String, context: android.content.Context, navController: androidx.navigation.NavController? = null): Boolean =
        slashCommandsCoordinator.executeCommand(roomId, text, context, navController)

    /**
     * Set room member avatar (myroomavatar command)
     * Called after image is uploaded
     */
    fun setRoomMemberAvatar(roomId: String, mxcUrl: String) {
        val requestId = requestIdCounter++
        sendWebSocketCommand("set_state", requestId, mapOf(
            "room_id" to roomId,
            "type" to "m.room.member",
            "state_key" to currentUserId,
            "content" to mapOf(
                "avatar_url" to mxcUrl,
                "membership" to "join"
            )
        ))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting room member avatar: $mxcUrl")
    }
    
    /**
     * Ban a user from a room with optional redaction
     */
    fun banUser(roomId: String, userId: String, reason: String, redactSystemMessages: Boolean = false) {
        val requestId = requestIdCounter++
        sendWebSocketCommand("set_membership", requestId, mapOf(
            "room_id" to roomId,
            "user_id" to userId,
            "action" to "ban",
            "reason" to reason,
            "msc4293_redact_events" to redactSystemMessages
        ))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Banning user $userId from room $roomId with reason: $reason")
    }
    
    /**
     * Redact an event
     */
    fun redactEvent(roomId: String, eventId: String, reason: String) {
        val requestId = requestIdCounter++
        sendWebSocketCommand("redact_event", requestId, mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "reason" to reason
        ))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Redacting event $eventId in room $roomId with reason: $reason")
    }
    
    /**
     * Check if a user is ignored
     */
    fun isUserIgnored(userId: String): Boolean {
        return ignoredUsers.contains(userId)
    }
    
    fun setIgnoredUser(userId: String, ignore: Boolean) = accountDataCoordinator.setIgnoredUser(userId, ignore)
    
    /**
     * Get next request ID (for operations that need multiple requests)
     */
    fun getNextRequestId(): Int {
        return requestIdCounter++
    }
    
    /**
     * Pin or unpin an event in a room
     * @param roomId The room ID
     * @param eventId The event ID to pin/unpin
     * @param pin true to pin, false to unpin
     */
    fun pinUnpinEvent(roomId: String, eventId: String, pin: Boolean) {
        val roomState = currentRoomState
        if (roomState == null || roomState.roomId != roomId) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Cannot pin/unpin - room state not available for room $roomId")
            return
        }
        
        val currentPinned = roomState.pinnedEventIds.toMutableList()
        
        if (pin) {
            // Pin: add event if not already pinned
            if (!currentPinned.contains(eventId)) {
                currentPinned.add(eventId)
            }
        } else {
            // Unpin: remove event if pinned
            currentPinned.remove(eventId)
        }
        
        val requestId = requestIdCounter++
        sendWebSocketCommand("set_state", requestId, mapOf(
            "room_id" to roomId,
            "type" to "m.room.pinned_events",
            "state_key" to "",
            "content" to mapOf("pinned" to currentPinned)
        ))
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: ${if (pin) "Pinned" else "Unpinned"} event $eventId in room $roomId (${currentPinned.size} total pinned events)")
        
        // Request updated room state to refresh m.pinned_events and other room info
        if (isWebSocketConnected() && !pendingRoomStateRequests.contains(roomId)) {
            val stateRequestId = requestIdCounter++
            synchronized(roomStateRequests) {
                roomStateRequests[stateRequestId] = roomId
            }
            pendingRoomStateRequests.add(roomId)
            sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
                "room_id" to roomId,
                "include_members" to false,
                "fetch_members" to false,
                "refetch" to false
            ))
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requested room state update after ${if (pin) "pin" else "unpin"} (reqId: $stateRequestId)")
        }
    }
    
    fun setRoomTag(roomId: String, tagType: String, enabled: Boolean) =
        accountDataCoordinator.setRoomTag(roomId, tagType, enabled)
    
    /**
     * Set room avatar (roomavatar command)
     * Called after image is uploaded
     */
    fun setRoomAvatar(roomId: String, mxcUrl: String) {
        val requestId = requestIdCounter++
        sendWebSocketCommand("set_state", requestId, mapOf(
            "room_id" to roomId,
            "type" to "m.room.avatar",
            "state_key" to "",
            "content" to mapOf("url" to mxcUrl)
        ))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting room avatar: $mxcUrl")
    }
    
    /**
     * Set global avatar (globalavatar command)
     * Called after image is uploaded
     */
    fun setGlobalAvatar(mxcUrl: String) {
        val requestId = requestIdCounter++
        sendWebSocketCommand("set_profile_field", requestId, mapOf(
            "field" to "avatar",
            "value" to mxcUrl
        ))
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting global avatar: $mxcUrl")
    }

    /**
     * Set an arbitrary custom profile field via Gomuks WebSocket API.
     * This is used for MSC profile fields such as m.status.
     */
    fun setCustomProfileField(field: String, value: Any) {
        val requestId = requestIdCounter++
        sendWebSocketCommand(
            "set_profile_field",
            requestId,
            mapOf(
                "field" to field,
                "value" to value
            )
        )
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting custom profile field '$field'")
    }
    /**
     * Send WebSocket command to the backend (raw payload). Delegates to [WebSocketCommandSender].
     */
    internal fun sendRawWebSocketCommand(command: String, requestId: Int, data: Any?): WebSocketResult =
        webSocketCommands.sendRaw(command, requestId, data)

    fun sendWidgetCommand(command: String, data: Any?, onResult: (Result<Any?>) -> Unit) =
        callsWidgetsCoordinator.sendWidgetCommand(command, data, onResult)

    internal fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): WebSocketResult =
        webSocketCommands.send(command, requestId, data)

    private fun flushPendingCommandsQueue() {
        webSocketCommands.flushPendingQueue()
    }
    
    /**
     * CRITICAL FIX: Request get_room_state for ALL rooms after init_complete and sync_complete processing
     * This ensures bridge badges are loaded before navigating to RoomListScreen
     * These requests are exempt from the canSendCommandsToBackend blocking
     */
    private fun loadAllRoomStatesAfterInitComplete() {
        // Only run on initial connection (not reconnections)
        val isReconnecting = WebSocketService.isReconnectingWithLastReceivedEvent()
        if (isReconnecting) {
            // On reconnection, allow commands immediately (no need to load all room states)
            canSendCommandsToBackend = true
            flushPendingCommandsQueue()
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Reconnection detected - skipping all room state loading, allowing commands immediately")
            }
            return
        }
        
        viewModelScope.launch(Dispatchers.Default) {
            // Grace period so roomMap / follow-up work from sync can settle before snapshotting (was 2000ms).
            delay(1000)
            val allRoomIds = synchronized(roomMap) {
                roomMap.keys.toList()
            }
            
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: loadAllRoomStatesAfterInitComplete - Found ${allRoomIds.size} rooms in roomMap")
            }
            
            if (allRoomIds.isEmpty()) {
                // No rooms - allow commands immediately
                allRoomStatesLoaded = true
                canSendCommandsToBackend = true
                flushPendingCommandsQueue()
                checkStartupComplete()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: No rooms to load - allowing commands immediately")
                }
                return@launch
            }
            
            // CRITICAL OPTIMIZATION: Check SharedPreferences cache to skip rooms we already know about
            // Only request get_room_state for rooms not in cache (new rooms)
            val context = appContext ?: return@launch
            val uncachedRoomIds = allRoomIds.filter { roomId ->
                !net.vrkknn.andromuks.utils.BridgeInfoCache.isCached(context, roomId)
            }
            
            // Load cached bridge info into roomMap for rooms that are cached
            val cachedCount = allRoomIds.size - uncachedRoomIds.size
            if (cachedCount > 0) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Loading ${cachedCount} cached bridge info entries from SharedPreferences")
                }
                
                allRoomIds.forEach { roomId ->
                    if (net.vrkknn.andromuks.utils.BridgeInfoCache.isCached(context, roomId)) {
                        val cachedAvatarUrl = net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, roomId)
                        // cachedAvatarUrl will be empty string if not bridged, or mxc:// URL if bridged
                        val bridgeAvatarUrl = if (cachedAvatarUrl != null && cachedAvatarUrl.isNotEmpty()) {
                            cachedAvatarUrl
                        } else {
                            null
                        }
                        
                        // Update roomMap with cached bridge info
                        val existing = roomMap[roomId]
                        if (existing != null && existing.bridgeProtocolAvatarUrl != bridgeAvatarUrl) {
                            val updatedRoom = existing.copy(bridgeProtocolAvatarUrl = bridgeAvatarUrl)
                            roomMap[roomId] = updatedRoom
                            allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                            invalidateRoomSectionCache()
                            roomListUpdateCounter++
                        }
                    }
                }
            }
            
            if (uncachedRoomIds.isEmpty()) {
                // All rooms are cached - no need to request room states
                allRoomStatesLoaded = true
                canSendCommandsToBackend = true
                flushPendingCommandsQueue()
                checkStartupComplete()
                addStartupProgressMessage("Bridge info loaded for all rooms (from cache)")
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: All ${allRoomIds.size} rooms are cached - skipping room state requests")
                }
                return@launch
            }
            
            // Mark that we're requesting room states (only for uncached rooms)
            allRoomStatesRequested = true
            totalRoomStateRequests = uncachedRoomIds.size
            completedRoomStateRequests = 0
            
            synchronized(pendingRoomStateResponses) {
                pendingRoomStateResponses.clear()
                pendingRoomStateResponses.addAll(uncachedRoomIds)
            }
            
            if (cachedCount > 0) {
                addStartupProgressMessage("Loading bridge info for ${uncachedRoomIds.size} new rooms (${cachedCount} cached)... 0 / ${uncachedRoomIds.size}")
            } else {
                addStartupProgressMessage("Loading bridge info for ${uncachedRoomIds.size} new rooms... 0 / ${uncachedRoomIds.size}")
            }
            
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Requesting room state for ${uncachedRoomIds.size} uncached rooms (${cachedCount} already cached)")
            }
            
            // Request room state only for uncached rooms with small delays to avoid overwhelming backend
            uncachedRoomIds.forEachIndexed { index, roomId ->
                // Skip if already requested
                if (!pendingRoomStateRequests.contains(roomId)) {
                    requestRoomState(roomId)
                } else {
                    // Already requested - remove from pending set and increment counter
                    synchronized(pendingRoomStateResponses) {
                        pendingRoomStateResponses.remove(roomId)
                    }
                    completedRoomStateRequests++
                }
                
                // Small delay every 10 requests to avoid overwhelming backend
                if ((index + 1) % 10 == 0) {
                    delay(100)
                } else {
                    delay(20) // Very small delay between requests
                }
            }
            
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Finished sending room state requests for ${uncachedRoomIds.size} uncached rooms. Waiting for responses...")
            }
        }
    }
    
    /**
     * NETWORK OPTIMIZATION: Queue command for retry when connection is restored
     * (implementation in [WebSocketCommandSender]).
     */
    private fun queueCommandForOfflineRetry(command: String, requestId: Int, data: Map<String, Any>) {
        webSocketCommands.queueOfflineRetry(command, requestId, data)
    }
    
    /**
     * Send get_event command to retrieve full event details from server
     * Useful when we only have partial event information (e.g., for reply previews)
     */
    fun getEvent(roomId: String, eventId: String, callback: (TimelineEvent?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getEvent called for roomId: '$roomId', eventId: '$eventId'")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with null, health monitor will handle reconnection")
            callback(null)
            return
        }
        
        val eventRequestId = requestIdCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_event: $eventRequestId")
        
        // Store the callback to handle the response
        eventRequests[eventRequestId] = roomId to callback
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: get_event with data: $commandData")
        sendWebSocketCommand("get_event", eventRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $eventRequestId")
        
        // Add timeout mechanism to prevent infinite loading
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = 10000L // 10 second timeout
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting get_event timeout to ${timeoutMs}ms for requestId=$eventRequestId")
            delay(timeoutMs)
            
            // Check if request is still pending
            if (eventRequests.containsKey(eventRequestId)) {
                android.util.Log.w("Andromuks", "AppViewModel: get_event timeout after ${timeoutMs}ms for requestId=$eventRequestId, calling callback with null")
                // Switch to Main dispatcher for callback
                withContext(Dispatchers.Main) {
                    eventRequests.remove(eventRequestId)?.let { (_, callback) ->
                        callback(null)
                    }
                }
            }
        }
    }
    
    /**
     * Get event context (events before and after a specific event)
     * @param roomId The room ID
     * @param eventId The event ID to get context for
     * @param limitBefore Number of events before the target event (default 5)
     * @param limitAfter Number of events after the target event (default 5)
     * @param callback Callback with list of events (target event in the middle)
     */
    fun getEventContext(
        roomId: String,
        eventId: String,
        limitBefore: Int = 5,
        limitAfter: Int = 5,
        callback: (List<TimelineEvent>?) -> Unit
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getEventContext called for roomId: '$roomId', eventId: '$eventId', limitBefore: $limitBefore, limitAfter: $limitAfter")
        
        // Check if WebSocket is connected
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with null, health monitor will handle reconnection")
            callback(null)
            return
        }
        
        val eventContextRequestId = requestIdCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_event_context: $eventContextRequestId")
        
        // Store the callback to handle the response
        eventContextRequests[eventContextRequestId] = roomId to callback
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "limit_before" to limitBefore,
            "limit_after" to limitAfter
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: get_event_context with data: $commandData")
        sendWebSocketCommand("get_event_context", eventContextRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $eventContextRequestId")
        
        // Add timeout mechanism to prevent infinite loading
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = 10000L // 10 second timeout
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting get_event_context timeout to ${timeoutMs}ms for requestId=$eventContextRequestId")
            delay(timeoutMs)
            
            // Check if request is still pending
            if (eventContextRequests.containsKey(eventContextRequestId)) {
                android.util.Log.w("Andromuks", "AppViewModel: get_event_context timeout after ${timeoutMs}ms for requestId=$eventContextRequestId, calling callback with null")
                // Switch to Main dispatcher for callback
                withContext(Dispatchers.Main) {
                    eventContextRequests.remove(eventContextRequestId)?.let { (_, callback) ->
                        callback(null)
                    }
                }
            }
        }
    }
    
    /**
     * Request mentions list from backend
     * @param maxTimestamp Timestamp to get mentions before this time (defaults to current time)
     * @param limit Maximum number of mentions to return (default 50)
     */
    fun requestMentionsList(maxTimestamp: Long? = null, limit: Int = 50) {
        val actualMaxTimestamp = maxTimestamp ?: System.currentTimeMillis()
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: requestMentionsList called with maxTimestamp=$actualMaxTimestamp, limit=$limit")
        
        val ws = WebSocketService.getWebSocket() ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - cannot request mentions list")
            return
        }
        
        val mentionsRequestId = requestIdCounter++
        mentionsRequests[mentionsRequestId] = Unit
        
        isMentionsLoading = true
        
        val commandData = mapOf<String, Any>(
            "type" to 4, // Mention type (from backend)
            "limit" to limit,
            "max_timestamp" to actualMaxTimestamp
        )
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sending get_mentions command with request_id=$mentionsRequestId, data=$commandData")
        sendWebSocketCommand("get_mentions", mentionsRequestId, commandData)
        
        // Add timeout mechanism
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = 15000L // 15 second timeout
            delay(timeoutMs)
            
            if (mentionsRequests.containsKey(mentionsRequestId)) {
                android.util.Log.w("Andromuks", "AppViewModel: get_mentions timeout after ${timeoutMs}ms for requestId=$mentionsRequestId")
                withContext(Dispatchers.Main) {
                    mentionsRequests.remove(mentionsRequestId)
                    isMentionsLoading = false
                }
            }
        }
    }
    
    /**
     * Handle mentions list response from backend
     */
    private fun handleMentionsListResponse(requestId: Int, data: Any) {
        mentionsRequests.remove(requestId)
        isMentionsLoading = false
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling mentions list response for requestId: $requestId")
        
        when (data) {
            is org.json.JSONArray -> {
                // Parse array of event JSON objects
                val events = mutableListOf<TimelineEvent>()
                for (i in 0 until data.length()) {
                    val eventJson = data.optJSONObject(i) ?: continue
                    try {
                        val event = TimelineEvent.fromJson(eventJson)
                        events.add(event)
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error parsing mention event at index $i: ${e.message}", e)
                    }
                }
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsed ${events.size} mention events from response")
                
                // Process events and fetch reply targets if needed
                processMentionEvents(events)
            }
            else -> {
                android.util.Log.w("Andromuks", "AppViewModel: Unexpected data type in mentions list response: ${data::class.java.simpleName}")
                mentionEvents = emptyList()
            }
        }
    }
    
    /**
     * Process mention events: convert to MentionEvent objects and fetch reply targets
     */
    private fun processMentionEvents(events: List<TimelineEvent>) {
        viewModelScope.launch(Dispatchers.IO) {
            val mentionEventList = mutableListOf<MentionEvent>()
            val replyTargetsToFetch = mutableListOf<Pair<String, String>>() // (roomId, eventId)
            
            // First pass: create MentionEvent objects and collect reply targets
            for (event in events) {
                val roomId = event.roomId
                val room = getRoomById(roomId)
                val roomName = room?.name
                val roomAvatarUrl = room?.avatarUrl
                
                // Check if this event is a reply
                val replyToEventId = event.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                    ?: event.decrypted?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                
                if (replyToEventId != null) {
                    replyTargetsToFetch.add(Pair(roomId, replyToEventId))
                }
                
                mentionEventList.add(
                    MentionEvent(
                        mentionEntry = MentionEntry(roomId, event.eventId),
                        event = event,
                        roomName = roomName,
                        roomAvatarUrl = roomAvatarUrl
                    )
                )
            }
            
            // Fetch reply targets in parallel
            val replyEventsMap = mutableMapOf<String, TimelineEvent?>() // "roomId:eventId" -> event
            if (replyTargetsToFetch.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Fetching ${replyTargetsToFetch.size} reply target events")
                
                // Helper function to convert callback-based getEvent to suspend function
                suspend fun fetchEvent(roomId: String, eventId: String): TimelineEvent? {
                    val deferred = CompletableDeferred<TimelineEvent?>()
                    getEvent(roomId, eventId) { event ->
                        deferred.complete(event)
                    }
                    return deferred.await()
                }
                
                val deferredResults = replyTargetsToFetch.map { (roomId, eventId) ->
                    async {
                        val key = "$roomId:$eventId"
                        val replyEvent = withTimeoutOrNull(5000L) {
                            fetchEvent(roomId, eventId)
                        }
                        key to replyEvent
                    }
                }
                
                // Wait for all requests to complete
                deferredResults.awaitAll().forEach { (key, event) ->
                    replyEventsMap[key] = event
                }
            }
            
            // Update mention events with reply targets
            val finalMentionEvents = mentionEventList.map { mentionEvent ->
                val replyToEventId = mentionEvent.event.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                    ?: mentionEvent.event.decrypted?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id")
                
                if (replyToEventId != null) {
                    val key = "${mentionEvent.event.roomId}:$replyToEventId"
                    val replyEvent = replyEventsMap[key]
                    mentionEvent.copy(replyToEvent = replyEvent)
                } else {
                    mentionEvent
                }
            }
            
            // Update mentionEvents on main thread
            withContext(Dispatchers.Main) {
                mentionEvents = finalMentionEvents
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated mentionEvents with ${finalMentionEvents.size} events")
            }
        }
    }

    fun toggleCompression() = settingsCoordinator.toggleCompression()

    fun toggleEnterKeyBehavior() = settingsCoordinator.toggleEnterKeyBehavior()

    fun toggleLoadThumbnailsIfAvailable() = settingsCoordinator.toggleLoadThumbnailsIfAvailable()

    fun toggleRenderThumbnailsAlways() = settingsCoordinator.toggleRenderThumbnailsAlways()

    fun toggleShowAllRoomListTabs() = settingsCoordinator.toggleShowAllRoomListTabs()

    fun toggleMoveReadReceiptsToEdge() = settingsCoordinator.toggleMoveReadReceiptsToEdge()

    fun toggleTrimLongDisplayNames() = settingsCoordinator.toggleTrimLongDisplayNames()

    fun updateElementCallBaseUrl(url: String) = settingsCoordinator.updateElementCallBaseUrl(url)

    fun updateBackgroundPurgeInterval(minutes: Int) = settingsCoordinator.updateBackgroundPurgeInterval(minutes)

    fun updateBackgroundPurgeThreshold(count: Int) = settingsCoordinator.updateBackgroundPurgeThreshold(count)



    fun loadSettings(context: Context? = null) = settingsCoordinator.loadSettings(context)
    /**
     * Starts the WebSocket service to maintain connection in background
     * 
     * Note: The service primarily shows a foreground notification to prevent
     * Android from killing the app process. The actual WebSocket connection
     * is managed by NetworkUtils and AppViewModel.
     */
    fun startWebSocketService() {
        appContext?.let { context ->
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Starting WebSocket foreground service")
            logActivity("Starting WebSocket Service", null)
            val intent = android.content.Intent(context, WebSocketService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    WebSocketService.shouldUseForegroundService()
                ) {
                    // Normal path on Android O+: request a foreground service start
                    context.startForegroundService(intent)
                } else {
                    // Either pre-O, or we've previously been denied FGS for this process.
                    // In that case, fall back to a regular service start to avoid
                    // ForegroundServiceDidNotStartInTimeException.
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to start WebSocketService", e)
            }
            
            // Service will manage connection lifecycle once started
        }
    }
    
    /**
     * PHASE 1.4 FIX: Initialize WebSocket connection using viewModelScope
     * This ensures the connection attempt survives activity recreation
     * Called from AuthCheckScreen when primary instance is ready to connect
     * 
     * @param isReconnection Whether to attempt to resume the previous session (run_id + last_received_event)
     */
    fun initializeWebSocketConnection(homeserverUrl: String, token: String, isReconnection: Boolean? = null) {
        // Only primary instance should connect
        if (instanceRole != InstanceRole.PRIMARY) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: initializeWebSocketConnection called on non-primary instance ($viewModelId), ignoring")
            return
        }
        
        // Check if already connected
        if (WebSocketService.isWebSocketConnected()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket already connected, skipping initialization")
            attachToExistingWebSocketIfAvailable()
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Delegating WebSocket connection to WebSocketService")
        
        // Start WebSocket service BEFORE connecting websocket
        startWebSocketService()
        
        // Set app as visible since we're starting the app
        WebSocketService.setAppVisibility(true)
        
        // Determine whether to resume. 
        // Priority: 1. Manual override (reconnectWithResume), 2. Parameter, 3. Default (false)
        val finalIsReconnection = when (reconnectWithResume) {
            true -> {
                reconnectWithResume = null // Consume
                true
            }
            false -> {
                reconnectWithResume = null // Consume
                false
            }
            null -> isReconnection ?: false
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: initializeWebSocketConnection - isReconnection: $finalIsReconnection")

        // REFACTORING: Delegate connection to service
        // The service now owns the WebSocket connection lifecycle
        // Pass this ViewModel for message routing (optional - service can work without it)
        WebSocketService.connectWebSocket(
            homeserverUrl,
            token,
            this@AppViewModel,
            trigger = ReconnectTrigger.Unclassified("Initial connection from AppViewModel"),
            isReconnection = finalIsReconnection
        )
    }

    // User Info Functions
    
    /**
     * Requests encryption info for a user
     */
    fun requestUserEncryptionInfo(userId: String, callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit) =
        userEncryptionCoordinator.requestUserEncryptionInfo(userId, callback)
    
    /**
     * Requests mutual rooms with a user
     */
    fun requestMutualRooms(userId: String, callback: (List<String>?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting mutual rooms with user: $userId")
        
        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return
        }
        
        val requestId = requestIdCounter++
        mutualRoomsRequests[requestId] = callback
        
        sendWebSocketCommand("get_mutual_rooms", requestId, mapOf(
            "user_id" to userId
        ))
    }
    
    /**
     * Starts tracking a user's devices
     */
    fun trackUserDevices(userId: String, callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit) =
        userEncryptionCoordinator.trackUserDevices(userId, callback)
    /**
     * Gets all messages in a thread (thread root + all replies)
     * @param roomId The room containing the thread
     * @param threadRootEventId The event ID that started the thread
     * @return List of timeline events in the thread, sorted by timestamp
     */
    fun getThreadMessages(roomId: String, threadRootEventId: String): List<TimelineEvent> {
        val threadMessages = mutableListOf<TimelineEvent>()
        
        // Add the thread root message first
        val rootMessage = timelineEvents.find { it.eventId == threadRootEventId }
        if (rootMessage != null) {
            threadMessages.add(rootMessage)
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found thread root: $threadRootEventId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Thread root not found: $threadRootEventId")
        }
        
        // Add all messages that are part of this thread
        val threadReplies = timelineEvents.filter { event ->
            event.isThreadMessage() && event.relatesTo == threadRootEventId
        }
        
        threadMessages.addAll(threadReplies)
        
        // Sort by timestamp
        val sortedMessages = threadMessages.sortedBy { it.timestamp }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: getThreadMessages - found ${sortedMessages.size} messages in thread (1 root + ${threadReplies.size} replies)")
        
        return sortedMessages
    }
    
    /**
     * Sends a reply in a thread
     * @param roomId The room ID
     * @param text The message text
     * @param threadRootEventId The thread root event ID (where the thread started)
     * @param fallbackReplyToEventId The specific message being replied to (for fallback compatibility)
     */
    fun sendThreadReply(
        roomId: String,
        text: String,
        threadRootEventId: String,
        fallbackReplyToEventId: String? = null
    ) = messageSendCoordinator.sendThreadReply(roomId, text, threadRootEventId, fallbackReplyToEventId)
    
    /**
     * Requests complete user profile information (profile, encryption info, mutual rooms)
     */
    fun requestFullUserInfo(userId: String, forceRefresh: Boolean = false, callback: (net.vrkknn.andromuks.utils.UserProfileInfo?, String?) -> Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting full user info for: $userId (forceRefresh: $forceRefresh)")
        
        var displayName: String? = null
        var avatarUrl: String? = null
        var timezone: String? = null
        var pronouns: List<net.vrkknn.andromuks.utils.UserPronouns>? = null
        var encryptionInfo: net.vrkknn.andromuks.utils.UserEncryptionInfo? = null
        var mutualRooms: List<String> = emptyList()
        var arbitraryFields: Map<String, Any> = emptyMap()
        
        var profileCompleted = false
        var encryptionCompleted = false
        var mutualRoomsCompleted = false
        var hasError = false
        
        fun checkCompletion() {
            val isSelfUser = userId == currentUserId
            
            val completedCount = (if (profileCompleted) 1 else 0) + 
                                (if (encryptionCompleted) 1 else 0) + 
                                (if (mutualRoomsCompleted) 1 else 0)
            
            val expectedRequests = if (isSelfUser) 2 else 3
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full user info progress - profile: $profileCompleted, encryption: $encryptionCompleted, mutualRooms: $mutualRoomsCompleted (expected: $expectedRequests, completed: $completedCount)")
            
            if (completedCount >= expectedRequests && !hasError) {
                val profileInfo = net.vrkknn.andromuks.utils.UserProfileInfo(
                    userId = userId,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    timezone = timezone,
                    pronouns = pronouns,
                    encryptionInfo = encryptionInfo,
                    mutualRooms = mutualRooms,
                    roomDisplayName = null, // Per-room profile will be loaded separately if roomId is provided
                    roomAvatarUrl = null,
                    arbitraryFields = arbitraryFields
                )
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full user info completed for $userId")
                callback(profileInfo, null)
            } else if (!hasError) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Full user info still waiting for requests to complete (${completedCount}/$expectedRequests)")
            }
        }
        
        // Request 1: Profile (always request fresh from backend - get_profile always fetches latest data)
        val profileRequestId = requestIdCounter++
        profileRequests[profileRequestId] = userId
        sendWebSocketCommand("get_profile", profileRequestId, mapOf(
            "user_id" to userId
        ))
        
        // Override the profile handler temporarily to capture the result
        val originalProfileCallback = profileRequests[profileRequestId]
        profileRequests[profileRequestId] = userId // Keep userId for routing
        
        // We need to intercept the profile response, so we'll handle it in handleProfileResponse
        // For now, let's use a different approach - store a callback for full user info requests
        
        // Request 2: Encryption Info
        requestUserEncryptionInfo(userId) { encInfo, error ->
            if (error != null) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to get encryption info: $error")
                // Don't treat as critical error - encryption info might not be available
            }
            encryptionInfo = encInfo
            encryptionCompleted = true
            checkCompletion()
        }
        
        // Request 3: Mutual Rooms
        // Skip mutual rooms request if viewing our own profile (backend returns HTTP 422)
        if (userId == currentUserId) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Skipping mutual rooms request for self")
            mutualRooms = emptyList()
            mutualRoomsCompleted = true
            checkCompletion()
        } else {
            requestMutualRooms(userId) { rooms, error ->
                if (error != null) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to get mutual rooms: $error")
                    hasError = true
                    callback(null, error)
                    return@requestMutualRooms
                }
                mutualRooms = rooms ?: emptyList()
                mutualRoomsCompleted = true
                checkCompletion()
            }
        }
        
        // Handle profile response separately
        val tempProfileCallback: (JSONObject?) -> Unit = { profileData ->
            if (profileData != null) {
                displayName = profileData.optString("displayname")?.takeIf { it.isNotBlank() }
                avatarUrl = profileData.optString("avatar_url")?.takeIf { it.isNotBlank() }
                // Support both timezone field formats: prefer m.tz (standardized) over us.cloke.msc4175.tz (legacy)
                timezone = profileData.optString("m.tz")?.takeIf { it.isNotBlank() }
                    ?: profileData.optString("us.cloke.msc4175.tz")?.takeIf { it.isNotBlank() }
                
                // Extract pronouns from io.fsky.nyx.pronouns array
                val pronounsArray = profileData.optJSONArray("io.fsky.nyx.pronouns")
                if (pronounsArray != null && pronounsArray.length() > 0) {
                    val pronounsList = mutableListOf<net.vrkknn.andromuks.utils.UserPronouns>()
                    for (i in 0 until pronounsArray.length()) {
                        val pronounObj = pronounsArray.optJSONObject(i)
                        if (pronounObj != null) {
                            val language = pronounObj.optString("language", "en")
                            val summary = pronounObj.optString("summary", "")
                            if (summary.isNotBlank()) {
                                pronounsList.add(net.vrkknn.andromuks.utils.UserPronouns(
                                    language = language,
                                    summary = summary
                                ))
                            }
                        }
                    }
                    if (pronounsList.isNotEmpty()) {
                        pronouns = pronounsList
                    }
                }
                
                // Extract all arbitrary fields (everything except known fields)
                val knownKeys = setOf("displayname", "avatar_url", "us.cloke.msc4175.tz", "m.tz", "io.fsky.nyx.pronouns")
                val arbitraryFieldsMap = mutableMapOf<String, Any>()
                val keys = profileData.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!knownKeys.contains(key)) {
                        val value = profileData.get(key)
                        // Convert JSON types to Kotlin types
                        when (value) {
                            is org.json.JSONArray -> arbitraryFieldsMap[key] = value
                            is org.json.JSONObject -> arbitraryFieldsMap[key] = value
                            is String -> arbitraryFieldsMap[key] = value
                            is Number -> arbitraryFieldsMap[key] = value
                            is Boolean -> arbitraryFieldsMap[key] = value
                            else -> arbitraryFieldsMap[key] = value.toString()
                        }
                    }
                }
                
                // Store arbitrary fields in outer variable
                arbitraryFields = arbitraryFieldsMap
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile data received for $userId - display: $displayName, avatar: ${avatarUrl != null}, timezone: $timezone, pronouns: ${pronouns?.size ?: 0}, arbitraryFields: ${arbitraryFieldsMap.size}")
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Profile data is null for $userId")
            }
            profileCompleted = true
            checkCompletion()
        }
        
        // Store this callback for later
        fullUserInfoCallbacks[profileRequestId] = tempProfileCallback
        
        // Add timeout mechanism to prevent hanging
        viewModelScope.launch {
            kotlinx.coroutines.delay(10000) // 10 second timeout
            if (!profileCompleted || !encryptionCompleted || (!mutualRoomsCompleted && userId != currentUserId)) {
                android.util.Log.w("Andromuks", "AppViewModel: Full user info request timed out for $userId")
                // Clean up callbacks
                fullUserInfoCallbacks.remove(profileRequestId)
                if (!hasError) {
                    // Create a partial result with what we have
                    val profileInfo = net.vrkknn.andromuks.utils.UserProfileInfo(
                        userId = userId,
                        displayName = displayName,
                        avatarUrl = avatarUrl,
                        timezone = timezone,
                        pronouns = pronouns,
                        encryptionInfo = encryptionInfo,
                        mutualRooms = mutualRooms,
                        roomDisplayName = null, // Per-room profile will be loaded separately if roomId is provided
                        roomAvatarUrl = null,
                        arbitraryFields = arbitraryFields
                    )
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Returning partial user info after timeout for $userId")
                    callback(profileInfo, null)
                }
            }
        }
    }
    
    // Temporary storage for full user info profile callbacks
    internal val fullUserInfoCallbacks = mutableMapOf<Int, (JSONObject?) -> Unit>()
    
    private fun handleMutualRoomsResponse(requestId: Int, data: Any) {
        val callback = mutualRoomsRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling mutual rooms response for requestId: $requestId, data type: ${data::class.simpleName}")
        
        try {
            val roomsList = when (data) {
                is JSONObject -> {
                    // Response format: {"joined": ["room1", "room2", ...]}
                    val joinedArray = data.optJSONArray("joined")
                    if (joinedArray != null) {
                        val list = mutableListOf<String>()
                        for (i in 0 until joinedArray.length()) {
                            list.add(joinedArray.getString(i))
                        }
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Parsed ${list.size} mutual rooms from joined array")
                        list
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: No 'joined' array in mutual rooms response")
                        emptyList()
                    }
                }
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        list.add(data.getString(i))
                    }
                    list
                }
                is List<*> -> data.mapNotNull { it as? String }
                else -> {
                    android.util.Log.e("Andromuks", "AppViewModel: Unexpected data type for mutual rooms: ${data::class.simpleName}")
                    emptyList()
                }
            }
            callback(roomsList, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing mutual rooms", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    /**
     * Navigate to a room after joining
     * If room already exists, navigate immediately. Otherwise wait for sync.
     */
    fun joinRoomAndNavigate(roomId: String, navController: androidx.navigation.NavController) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: joinRoomAndNavigate called for $roomId")
        
        // CRITICAL: Navigation must happen on the main thread
        // Dispatch to main thread to avoid IllegalStateException
        viewModelScope.launch(Dispatchers.Main) {
            // Navigate directly - RoomTimelineScreen's LaunchedEffect will handle timeline loading
            val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
            navController.navigate("room_timeline/$encodedRoomId")
            // Timeline loading will be handled by RoomTimelineScreen's LaunchedEffect(roomId)
            // which calls requestRoomTimeline, which will use reset=true for newly joined rooms
        }
    }
    
    private fun getRoomDisplayName(roomId: String): String {
        val roomItem = getRoomById(roomId)
        return roomItem?.name ?: roomId
    }
    
    /**
     * Resolve room alias to room ID with callback
     */
    fun resolveRoomAlias(alias: String, callback: (Pair<String, List<String>>?) -> Unit) {
        val requestId = requestIdCounter++
        resolveAliasRequests[requestId] = callback
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct send()
        sendWebSocketCommand("resolve_alias", requestId, mapOf("alias" to alias))
    }
    
    /**
     * Get room summary with callback
     */
    fun getRoomSummary(roomIdOrAlias: String, viaServers: List<String>, callback: (Pair<net.vrkknn.andromuks.utils.RoomSummary?, String?>?) -> Unit) {
        val requestId = requestIdCounter++
        getRoomSummaryRequests[requestId] = callback
        
        // Always include "matrix.org" as default server, plus any additional servers
        val finalViaServers = (viaServers + "matrix.org").distinct()
        
        // REFACTORING: Use sendWebSocketCommand() instead of direct send()
        sendWebSocketCommand("get_room_summary", requestId, mapOf(
            "room_id_or_alias" to roomIdOrAlias,
            "via" to finalViaServers
        ))
    }
    
    /**
     * Join room with callback
     */
    fun joinRoomWithCallback(roomIdOrAlias: String, viaServers: List<String>, callback: (Pair<String?, String?>?) -> Unit) =
        roomInvitesCoordinator.joinRoomWithCallback(roomIdOrAlias, viaServers, callback)
    
    private fun handleResolveAliasResponse(requestId: Int, data: Any) {
        val callback = resolveAliasRequests.remove(requestId) ?: return
        
        if (data is org.json.JSONObject) {
            val roomId = data.optString("room_id")
            val serversArray = data.optJSONArray("servers")
            val servers = mutableListOf<String>()
            if (serversArray != null) {
                for (i in 0 until serversArray.length()) {
                    servers.add(serversArray.getString(i))
                }
            }
            if (roomId.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Resolved alias to roomId=$roomId, servers=$servers")
                callback(Pair(roomId, servers))
            } else {
                callback(null)
            }
        } else {
            callback(null)
        }
    }
    
    private fun handleGetRoomSummaryResponse(requestId: Int, data: Any) {
        val callback = getRoomSummaryRequests.remove(requestId) ?: return
        
        if (data is org.json.JSONObject) {
            val summary = net.vrkknn.andromuks.utils.RoomSummary(
                roomId = data.optString("room_id", ""),
                avatarUrl = data.optString("avatar_url").takeIf { it.isNotEmpty() },
                canonicalAlias = data.optString("canonical_alias").takeIf { it.isNotEmpty() },
                guestCanJoin = data.optBoolean("guest_can_join", false),
                joinRule = data.optString("join_rule", ""),
                name = data.optString("name").takeIf { it.isNotEmpty() },
                numJoinedMembers = data.optInt("num_joined_members", 0),
                roomType = data.optString("room_type").takeIf { it.isNotEmpty() },
                worldReadable = data.optBoolean("world_readable", false),
                membership = data.optString("membership").takeIf { it.isNotEmpty() },
                roomVersion = data.optString("im.nheko.summary.version").takeIf { it.isNotEmpty() }
            )
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Got room summary for ${summary.roomId}")
            callback(Pair(summary, null))
        } else {
            callback(Pair(null, null))
        }
    }
    
    private fun handleJoinRoomCallbackResponse(requestId: Int, data: Any) {
        val callback = joinRoomCallbacks.remove(requestId) ?: return
        
        if (data is org.json.JSONObject) {
            val roomId = data.optString("room_id")
            if (roomId.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Joined room successfully, roomId=$roomId")
                
                // CRITICAL: Preemptively mark room as newly joined so it appears at top when sync arrives
                newlyJoinedRoomIds.add(roomId)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Preemptively marked room $roomId as newly joined from joinRoomWithCallback")
                
                callback(Pair(roomId, null))
            } else {
                callback(Pair(null, null))
            }
        } else {
            callback(Pair(null, null))
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Initialize periodic cleanup to prevent memory leaks
     */
    init {
        // Log app start (will be persisted when appContext is available)
        // Note: Activity log will be loaded when loadStateFromStorage is called from AuthCheck
        logActivity("App Started")
        
        // Populate all singleton caches on initialization
        // This ensures data is available even when AppViewModel is recreated
        populateReadReceiptsFromCache()
        populateMessageReactionsFromCache()
        populateRecentEmojisFromCache()
        populatePendingInvitesFromCache()
        populateRoomMemberCacheFromCache()
        populateEmojiPacksFromCache()
        populateStickerPacksFromCache()
        
        // Start periodic cleanup job
        viewModelScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000) // Run every 5 minutes
                performPeriodicMemoryCleanup()
            }
        }
        
        // CRITICAL FIX: Observe batch processing completion and rebuild deferred timelines
        // This prevents rebuilding the timeline for each sync_complete during batch processing,
        // instead rebuilding once after all batched sync_completes are processed.
        // We observe both isProcessingSyncBatch and shouldSkipTimelineRebuild to ensure rebuild happens
        viewModelScope.launch {
            var wasSkippingRebuild = false
            shouldSkipTimelineRebuild.collect { shouldSkip ->
                if (wasSkippingRebuild && !shouldSkip) {
                    triggerDeferredRebuild()
                }
                wasSkippingRebuild = shouldSkip
            }
        }
    }
    
    /**
     * Rebuild timelines for rooms that were deferred during batch processing.
     * Called when batch processing completes (either flag transitions to false).
     */
    private fun triggerDeferredRebuild() {
        val roomsToRebuild = synchronized(roomsNeedingRebuildDuringBatch) {
            roomsNeedingRebuildDuringBatch.toSet().also {
                roomsNeedingRebuildDuringBatch.clear()
            }
        }
        
        if (roomsToRebuild.isNotEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Batch processing completed - rebuilding timelines for ${roomsToRebuild.size} rooms: ${roomsToRebuild.joinToString(", ")}")
            }
            
            // Rebuild timeline for currently open room if it needs rebuilding
            val currentRoom = currentRoomId
            if (currentRoom != null && currentRoom in roomsToRebuild) {
                val eventsForChain = RoomTimelineCache.getCachedEventsForTimeline(currentRoom)
                if (eventsForChain.isNotEmpty()) {
                    buildEditChainsFromEvents(eventsForChain, clearExisting = true)
                    processEditRelationships()
                    buildTimelineFromChain()
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: Rebuilt timeline for current room $currentRoom after batch completion")
                    }
                }
            }
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Periodic cleanup of stale data to prevent memory pressure
     */
    private fun performPeriodicMemoryCleanup() {
        try {
            // Clean up stale member cache entries
            memberProfilesCoordinator.performMemberCacheCleanup()
            
            // Clean up stale message versions (keep only recent ones)
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - (7 * 24 * 60 * 60 * 1000) // 7 days ago
            
            val versionsToRemove = messageVersions.filter { (_, versioned) ->
                versioned.versions.isNotEmpty() && 
                versioned.versions.first().timestamp < cutoffTime
            }.keys
            
            if (versionsToRemove.isNotEmpty()) {
                // Note: MessageVersionsCache doesn't have per-event removal, so we clear all
                // In practice, this cleanup happens rarely and clearing all is acceptable
                MessageVersionsCache.clear()
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${versionsToRemove.size} old message versions")
            }
            
            // Clean up old processed reactions
            if (processedReactions.size > 200) {
                val toRemove = processedReactions.take(processedReactions.size - 100)
                processedReactions.removeAll(toRemove)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${toRemove.size} old processed reactions")
            }
            
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error during periodic memory cleanup", e)
        }
    }
    
    // =============================================================================
    // HELPER FUNCTIONS FOR TIMELINE RESPONSE REFACTORING
    // =============================================================================
    
    /**
     * Data class to hold parsed timeline response data
     */
    private data class TimelineResponseData(
        val events: JSONArray?,
        val hasMore: Boolean = true,
        val receipts: JSONObject? = null,
        val fromServer: Boolean = false
    ) {
        companion object {
            fun empty() = TimelineResponseData(events = JSONArray())
        }
    }
    /**
     * Extract profile information from member event
     */
    private fun extractProfileFromMemberEvent(event: TimelineEvent): MemberProfile {
        val displayName = event.content?.optString("displayname")?.takeIf { it.isNotBlank() }
        val avatarUrl = event.content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
        return MemberProfile(displayName, avatarUrl)
    }
    
    /**
     * Check if profile has changed
     */
    private fun isProfileChange(
        previousProfile: MemberProfile?,
        newProfile: MemberProfile,
        event: TimelineEvent
    ): Boolean {
        val membership = event.content?.optString("membership")
        val prevContent = event.unsigned?.optJSONObject("prev_content")
        val prevMembership = prevContent?.optString("membership")
        
        return prevMembership == "join" && membership == "join" &&
            (previousProfile?.displayName != newProfile.displayName ||
             previousProfile?.avatarUrl != newProfile.avatarUrl)
    }
    
    /**
     * Process member event - update cache
     */
    internal fun processMemberEvent(
        event: TimelineEvent,
        memberMap: MutableMap<String, MemberProfile>
    ): Boolean {
        if (event.type != "m.room.member" || event.timelineRowid != -1L) return false
        
        val userId = event.stateKey ?: event.sender
        val profile = extractProfileFromMemberEvent(event)
        val previousProfile = memberMap[userId]
        
        memberMap[userId] = profile
        ProfileCache.setGlobalProfile(userId, ProfileCache.CachedProfileEntry(profile, System.currentTimeMillis()))
        
        return if (isProfileChange(previousProfile, profile, event)) {
            memberUpdateCounter++
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Profile change detected in timeline for $userId")
            true
        } else {
            false
        }
    }
    
    /**
     * Build edit chains from events
     * @param clearExisting If true, clears existing eventChainMap before adding new events.
     *                      If false, merges new events with existing ones (for pagination).
     */
    internal fun buildEditChainsFromEvents(timelineList: List<TimelineEvent>, clearExisting: Boolean = true) =
        editVersionCoordinator.buildEditChainsFromEvents(timelineList, clearExisting)

    fun getCacheStatistics(context: android.content.Context): Map<String, String> =
        diagnosticsCoordinator.getCacheStatistics(context)

    fun formatBytes(bytes: Long): String = diagnosticsCoordinator.formatBytes(bytes)
}


data class SingleEventLoadResult(
    val event: TimelineEvent?,
    val contextEvents: List<TimelineEvent> = emptyList(),
    val error: String? = null
)

// Helper to safely access application context from extensions
