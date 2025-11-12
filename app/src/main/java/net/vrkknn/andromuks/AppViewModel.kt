package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.vrkknn.andromuks.SpaceItem
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.SpaceRoomParser
import net.vrkknn.andromuks.utils.ReceiptFunctions
import net.vrkknn.andromuks.utils.processReactionEvent
import net.vrkknn.andromuks.database.entities.EventEntity
import org.json.JSONObject
import okhttp3.WebSocket
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
import kotlinx.coroutines.TimeoutCancellationException
import android.content.Context
import android.media.MediaPlayer
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.util.Collections
import net.vrkknn.andromuks.utils.IntelligentMediaCache

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

/**
 * Result of WebSocket operations to handle connection issues gracefully
 */
enum class WebSocketResult {
    SUCCESS,
    NOT_CONNECTED,
    CONNECTION_ERROR
}

class AppViewModel : ViewModel() {
    companion object {
        // File name for user profile disk cache (used in SharedPreferences)
        private const val PROFILE_CACHE_FILE = "user_profiles_cache.json"
        
        // MEMORY MANAGEMENT: Constants for cache limits and cleanup
        private const val MAX_TIMELINE_EVENTS_PER_ROOM = 1000
        private const val MAX_ANIMATION_STATES = 50
        private const val MAX_MEMBER_CACHE_SIZE = 5000
        private const val ANIMATION_STATE_CLEANUP_INTERVAL_MS = 30000L // 30 seconds
        private const val MAX_MESSAGE_VERSIONS_PER_EVENT = 50
        const val NEW_MESSAGE_ANIMATION_DURATION_MS = 450L
        const val NEW_MESSAGE_ANIMATION_DELAY_MS = 500L
        
        // PHASE 4: Counter for generating unique ViewModel IDs
        private var viewModelCounter = 0
    }
    
    // PHASE 4: Unique ID for this ViewModel instance (for WebSocket callback registration)
    private val viewModelId: String = "AppViewModel_${viewModelCounter++}"
    
    private enum class InstanceRole {
        PRIMARY,
        BUBBLE,
        SECONDARY
    }
    
    private var instanceRole: InstanceRole = InstanceRole.SECONDARY
    
    fun markAsPrimaryInstance() {
        instanceRole = InstanceRole.PRIMARY
        android.util.Log.d("Andromuks", "AppViewModel: Instance role set to PRIMARY for $viewModelId")
    }
    
    fun markAsBubbleInstance() {
        instanceRole = InstanceRole.BUBBLE
        android.util.Log.d("Andromuks", "AppViewModel: Instance role set to BUBBLE for $viewModelId")
    }
    
    var isLoading by mutableStateOf(false)
    var homeserverUrl by mutableStateOf("")
        private set
    var authToken by mutableStateOf("")
        private set
    var realMatrixHomeserverUrl by mutableStateOf("")
        private set
    private var appContext: Context? = null
    
    // Timeline cache for instant room opening (now singleton)
    // No need to instantiate - using object RoomTimelineCache

    // Auth/client state
    var currentUserId by mutableStateOf("")
        private set
    var deviceId by mutableStateOf("")
        private set
    var imageAuthToken by mutableStateOf("")
        private set
    var currentUserProfile by mutableStateOf<UserProfile?>(null)
        private set

    // Settings
    var showUnprocessedEvents by mutableStateOf(true)
        private set
    var enableCompression by mutableStateOf(true)
        private set

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
        android.util.Log.d(
            "Andromuks",
            "AppViewModel: Pending share set with ${items.size} items, hasText=${!text.isNullOrBlank()}, autoSelectRoom=$autoSelectRoomId"
        )
        pendingShare = PendingSharePayload(items, text)
        pendingShareTargetRoomId = null
        pendingShareNavigationRequested = autoSelectRoomId == null
        pendingShareUpdateCounter++
        if (!autoSelectRoomId.isNullOrBlank()) {
            pendingShareTargetRoomId = autoSelectRoomId
            android.util.Log.d(
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
        android.util.Log.d("Andromuks", "AppViewModel: Cleared pending share state")
    }

    fun markPendingShareNavigationHandled() {
        if (pendingShareNavigationRequested) {
            android.util.Log.d("Andromuks", "AppViewModel: Pending share navigation marked as handled")
        }
        pendingShareNavigationRequested = false
    }

    fun selectPendingShareRoom(roomId: String) {
        pendingShareTargetRoomId = roomId
        pendingShareNavigationRequested = false
        pendingShareUpdateCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Pending share target room selected: $roomId")
    }

    fun consumePendingShareForRoom(roomId: String): PendingSharePayload? {
        val share = pendingShare
        return if (share != null && pendingShareTargetRoomId == roomId) {
            android.util.Log.d("Andromuks", "AppViewModel: Consuming pending share for room $roomId")
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
        private set
    
    // All rooms (for filtering into sections)
    var allRooms by mutableStateOf(listOf<RoomItem>())
        private set
    
    // All spaces (for Spaces section)
    var allSpaces by mutableStateOf(listOf<SpaceItem>())
        private set
    
    // PERFORMANCE: Cached room sections to avoid expensive filtering on every recomposition
    private var cachedDirectChatRooms by mutableStateOf<List<RoomItem>>(emptyList())
        private set
    
    private var cachedUnreadRooms by mutableStateOf<List<RoomItem>>(emptyList())
        private set
    
    private var cachedFavouriteRooms by mutableStateOf<List<RoomItem>>(emptyList())
        private set
    
    // PERFORMANCE: Pre-computed badge counts (always computed for immediate tab bar display)
    private var cachedDirectChatsUnreadCount by mutableStateOf(0)
        private set
    private var cachedDirectChatsHasHighlights by mutableStateOf(false)
        private set
    private var cachedUnreadCount by mutableStateOf(0)
        private set
    private var cachedFavouritesUnreadCount by mutableStateOf(0)
        private set
    private var cachedFavouritesHasHighlights by mutableStateOf(false)
        private set
    private var cachedBridgesUnreadCount by mutableStateOf(0)
        private set
    private var cachedBridgesHasHighlights by mutableStateOf(false)
        private set
    
    // PERFORMANCE: Track which sections have been loaded (for lazy loading)
    private val loadedSections = mutableSetOf<RoomSectionType>()
    
    // Cache invalidation tracking
    private var lastAllRoomsHash: Int = 0
    
    /**
     * Invalidate room section cache when allRooms data changes
     */
    private fun invalidateRoomSectionCache() {
        lastAllRoomsHash = 0 // Force cache recalculation on next access
    }
    
    // Current selected section
    var selectedSection by mutableStateOf(RoomSectionType.HOME)
        private set
    
    // Space navigation state
    var currentSpaceId by mutableStateOf<String?>(null)
        private set
    
    // Store space edges data for later processing
    private var storedSpaceEdges: JSONObject? = null
    
    // Room state data
    var currentRoomState by mutableStateOf<RoomState?>(null)
        private set
    
    // Typing indicators for current room
    var typingUsers by mutableStateOf(listOf<String>())
        private set
    
    // PERFORMANCE: Rate limiting for typing indicators to reduce WebSocket traffic
    private val lastTypingSent = mutableMapOf<String, Long>() // roomId -> timestamp
    private val TYPING_SEND_INTERVAL = 3000L // 3 seconds instead of 1 second
    
    // Message reactions: eventId -> list of reactions
    var messageReactions by mutableStateOf(mapOf<String, List<MessageReaction>>())
        private set
    
    // Track processed reaction events to prevent duplicate processing
    private val processedReactions = mutableSetOf<String>()
    
    // Track pending message sends for send button animation
    var pendingSendCount by mutableStateOf(0)
        private set
    
    // Recent emojis for reactions (stored as list of strings for UI)
    var recentEmojis by mutableStateOf(listOf<String>())
        private set
    
    // Internal storage for emoji frequencies: list of [emoji, count] pairs
    private var recentEmojiFrequencies = mutableListOf<Pair<String, Int>>()
    
    // Cache for DM room IDs from m.direct account data
    private var directMessageRoomIds by mutableStateOf(setOf<String>())
        private set

    // Cache mapping of userId -> set of direct room IDs (from m.direct)
    private var directMessageUserMap: Map<String, Set<String>> = emptyMap()
        private set
    
    // Bridge-related properties
    var allBridges by mutableStateOf(listOf<BridgeItem>())
        private set
    var currentBridgeId by mutableStateOf<String?>(null)
        private set
    private var bridgeInfoCache by mutableStateOf(mapOf<String, BridgeInfo>())
        private set
    
    // Bridge cache persistence - tracks which rooms have been checked for bridge state
    private var bridgeCacheCheckedRooms by mutableStateOf(setOf<String>())
        private set
    
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
    
    /**
     * Enter a bridge (similar to entering a space)
     */
    fun enterBridge(bridgeId: String) {
        currentBridgeId = bridgeId
        currentSpaceId = null // Clear space selection when entering bridge
        roomListUpdateCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Entered bridge: $bridgeId")
    }
    
    /**
     * Exit the current bridge
     */
    fun exitBridge() {
        currentBridgeId = null
        roomListUpdateCounter++
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Exited bridge")
    }
    
    /**
     * Update bridge information for a room
     */
    fun updateBridgeInfo(roomId: String, bridgeInfo: BridgeInfo) {
        bridgeInfoCache = bridgeInfoCache + (roomId to bridgeInfo)
        bridgeCacheCheckedRooms = bridgeCacheCheckedRooms + roomId
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated bridge info for room $roomId: ${bridgeInfo.protocol.displayname}")
        
        // Persist bridge info to database
        appContext?.let { context ->
            if (syncIngestor == null) {
                syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context)
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    syncIngestor?.persistBridgeInfo(roomId, bridgeInfo)
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error persisting bridge info: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Mark a room as checked for bridge state (even if no bridge was found)
     * BUG FIX #3: Also persist "no bridge" status to DB so we don't request again
     */
    fun markRoomAsBridgeChecked(roomId: String) {
        bridgeCacheCheckedRooms = bridgeCacheCheckedRooms + roomId
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Marked room $roomId as bridge checked")
        
        // BUG FIX #3: Persist "no bridge" status to DB (empty bridgeInfoJson means "checked, no bridge")
        appContext?.let { context ->
            if (syncIngestor == null) {
                syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context)
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // Update RoomStateEntity to mark as checked (even if no bridge)
                    val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
                    val roomState = database.roomStateDao().get(roomId)
                    if (roomState != null && roomState.bridgeInfoJson == null) {
                        // Mark as checked by setting an empty JSON string (different from null)
                        database.roomStateDao().upsert(
                            net.vrkknn.andromuks.database.entities.RoomStateEntity(
                                roomId = roomState.roomId,
                                name = roomState.name,
                                avatarUrl = roomState.avatarUrl,
                                canonicalAlias = roomState.canonicalAlias,
                                topic = roomState.topic,
                                isDirect = roomState.isDirect,
                                isFavourite = roomState.isFavourite,
                                isLowPriority = roomState.isLowPriority,
                                bridgeInfoJson = "", // Empty string means "checked, no bridge"
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Silently fail - not critical
                }
            }
        }
    }
    
    /**
     * Check if a room has been checked for bridge state
     */
    fun isRoomBridgeChecked(roomId: String): Boolean {
        return bridgeCacheCheckedRooms.contains(roomId)
    }
    
    /**
     * Get rooms that haven't been checked for bridge state yet
     */
    fun getUncheckedRoomsForBridge(): List<String> {
        return allRooms.map { it.id }.filter { !isRoomBridgeChecked(it) }
    }
    
    /**
     * Get bridge information for a room
     */
    fun getBridgeInfo(roomId: String): BridgeInfo? {
        return bridgeInfoCache[roomId]
    }
    
    /**
     * Update all bridges (called when bridge data changes)
     */
    fun updateAllBridges(bridges: List<BridgeItem>) {
        allBridges = bridges
        roomListUpdateCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Updated ${bridges.size} bridges")
    }
    
    /**
     * Create bridge pseudo-spaces from collected bridge information
     * This groups rooms by their bridge protocol
     */
    fun createBridgePseudoSpaces() {
        android.util.Log.d("Andromuks", "AppViewModel: Creating bridge pseudo-spaces for ${allRooms.size} rooms")
        val bridgeGroups = mutableMapOf<String, MutableList<RoomItem>>()
        
        // Group rooms by bridge protocol
        allRooms.forEach { room ->
            val bridgeInfo = getBridgeInfo(room.id)
            if (bridgeInfo != null) {
                val protocolId = bridgeInfo.protocol.id
                if (protocolId.isNotBlank()) {
                    android.util.Log.d("Andromuks", "AppViewModel: Adding room ${room.id} to bridge group: $protocolId")
                    bridgeGroups.getOrPut(protocolId) { mutableListOf() }.add(room)
                }
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Found ${bridgeGroups.size} bridge groups: ${bridgeGroups.keys}")
        
        // Create bridge items from groups
        val bridges = bridgeGroups.map { (protocolId, rooms) ->
            val firstRoom = rooms.first()
            val bridgeInfo = getBridgeInfo(firstRoom.id)
            val protocolName = bridgeInfo?.protocol?.displayname ?: protocolId
            val protocolAvatar = bridgeInfo?.protocol?.avatarUrl
            val externalUrl = bridgeInfo?.protocol?.externalUrl
            
            BridgeItem(
                id = protocolId,
                name = protocolName,
                avatarUrl = protocolAvatar,
                protocol = protocolId,
                externalUrl = externalUrl,
                rooms = rooms
            )
        }
        
        updateAllBridges(bridges)
        android.util.Log.d("Andromuks", "AppViewModel: Created ${bridges.size} bridge pseudo-spaces")
    }
    
    /**
     * Request bridge state events for all rooms
     * This will make websocket calls to fetch m.bridge state events
     */
    fun requestBridgeStateEvents() {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting room states for ${allRooms.size} rooms")
        
        // Request room state for each room individually
        allRooms.forEach { room ->
            android.util.Log.d("Andromuks", "AppViewModel: Requesting room state for room: ${room.id}")
            requestRoomStateForBridgeDetection(room.id)
        }
    }
    
    /**
     * Request room state for bridge detection and storage
     */
    fun requestRoomStateForBridgeDetection(roomId: String) {
        val requestId = getAndIncrementRequestId()
        
        android.util.Log.d("Andromuks", "AppViewModel: Requesting room state for bridge detection - room: $roomId (requestId: $requestId)")
        
        // Store the request for response handling
        bridgeStateRequests[requestId] = roomId
        android.util.Log.d("Andromuks", "AppViewModel: Stored bridge state request for room $roomId with requestId $requestId")
        
        // Use get_room_state to get all room state events
        sendWebSocketCommand("get_room_state", requestId, mapOf(
            "room_id" to roomId
        ))
    }
    
    /**
     * Request bridge state for a specific room (kept for individual requests if needed)
     */
    private fun requestBridgeStateForRoom(roomId: String) {
        val requestId = getAndIncrementRequestId()
        
        android.util.Log.d("Andromuks", "AppViewModel: Requesting bridge state for room $roomId (requestId: $requestId)")
        
        // Store the request for response handling
        bridgeStateRequests[requestId] = roomId
        android.util.Log.d("Andromuks", "AppViewModel: Stored bridge state request for room $roomId with requestId $requestId")
        // THREAD SAFETY: Create safe copy to avoid ConcurrentModificationException during logging
        android.util.Log.d("Andromuks", "AppViewModel: Current bridge state requests: ${bridgeStateRequests.keys.toList()}")
        
        // Use the same format as other WebSocket commands
        sendWebSocketCommand("get_room_state", requestId, mapOf(
            "room_id" to roomId
        ))
    }
    
    // Force recomposition counter - DEPRECATED: Use specific counters below instead
    var updateCounter by mutableStateOf(0)
        private set
    
    // Granular update counters to reduce unnecessary recompositions
    var roomListUpdateCounter by mutableStateOf(0)
        private set
    
    var timelineUpdateCounter by mutableStateOf(0)
        private set
    
    var reactionUpdateCounter by mutableStateOf(0)
        private set
    
    var memberUpdateCounter by mutableStateOf(0)
        private set
    
    var roomStateUpdateCounter by mutableStateOf(0)
        private set
    
    // SYNC OPTIMIZATION: Batched update mechanism
    private var pendingUIUpdates = mutableSetOf<String>() // Track which UI sections need updates
    private var batchUpdateJob: Job? = null // Job for batching UI updates
    
    // PERFORMANCE: Debounced room reordering to prevent frustrating "room jumping"
    private var lastRoomReorderTime = 0L
    private var roomReorderJob: Job? = null
    private val ROOM_REORDER_DEBOUNCE_MS = 30000L // 30 seconds debounce - reduces visual jumping
    private var forceSortNextReorder = false // Flag to force immediate sort on next reorder
    
    // SYNC OPTIMIZATION: Diff-based update tracking
    private var lastRoomStateHash: String = ""
    private var lastTimelineStateHash: String = ""
    private var lastMemberStateHash: String = ""
    
    // SYNC OPTIMIZATION: Selective update flags
    private var needsRoomListUpdate = false
    private var needsTimelineUpdate = false
    private var needsMemberUpdate = false
    private var needsReactionUpdate = false
    
    // NAVIGATION PERFORMANCE: Prefetch and caching system
    private val prefetchedRooms = mutableSetOf<String>() // Track which rooms have been prefetched
    private val navigationCache = mutableMapOf<String, RoomNavigationState>() // Cache room navigation state
    private var lastRoomListScrollPosition = 0 // Track scroll position for prefetching
    
    // Read receipts update counter - separate from main updateCounter to reduce unnecessary UI updates
    var readReceiptsUpdateCounter by mutableStateOf(0)
        private set
    
    // Timestamp update counter for dynamic time displays
    var timestampUpdateCounter by mutableStateOf(0)
        private set
    
    // Per-room animation state for smooth transitions
    var roomAnimationStates by mutableStateOf(mapOf<String, RoomAnimationState>())
        private set
    
    // FCM notification manager
    private var fcmNotificationManager: FCMNotificationManager? = null
    
    // Conversations API for shortcuts and enhanced notifications
    private var conversationsApi: ConversationsApi? = null
    
    // Persons API for People/Share surfaces
    private var personsApi: PersonsApi? = null
    
    // Web client push integration
    private var webClientPushIntegration: WebClientPushIntegration? = null
    
    
    // Notification action tracking
    private data class PendingNotificationAction(
        val type: String, // "send_message" or "mark_read"
        val roomId: String,
        val text: String? = null,
        val eventId: String? = null,
        val requestId: Int? = null,
        val onComplete: (() -> Unit)? = null
    )
    
    private val pendingNotificationActions = mutableListOf<PendingNotificationAction>()
    private val notificationActionCompletionCallbacks = mutableMapOf<Int, () -> Unit>()

    // WebSocket pending operations for retry when connection is restored
    private data class PendingWebSocketOperation(
        val type: String, // "sendMessage", "sendReply", "markRoomAsRead", etc.
        val data: Map<String, Any>,
        val retryCount: Int = 0
    )
    
    // NAVIGATION PERFORMANCE: Room navigation state cache
    data class RoomNavigationState(
        val roomId: String,
        val essentialDataLoaded: Boolean = false,
        val memberDataLoaded: Boolean = false,
        val timelineDataLoaded: Boolean = false,
        val lastPrefetchTime: Long = System.currentTimeMillis()
    )
    
    private val pendingWebSocketOperations = mutableListOf<PendingWebSocketOperation>()
    private val maxRetryAttempts = 3

    var spacesLoaded by mutableStateOf(false)
        private set

    fun setSpaces(spaces: List<SpaceItem>, skipCounterUpdate: Boolean = false) {
        android.util.Log.d("Andromuks", "AppViewModel: setSpaces called with ${spaces.size} spaces")
        spaceList = spaces
        
        // SYNC OPTIMIZATION: Allow skipping immediate counter updates for batched updates
        if (!skipCounterUpdate) {
            roomListUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily     
        } 
    }
    
    fun updateAllSpaces(spaces: List<SpaceItem>) {
        allSpaces = spaces
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
        android.util.Log.d("Andromuks", "AppViewModel: allSpaces set to ${spaces.size} spaces")
    }
    
    fun changeSelectedSection(section: RoomSectionType) {
        val previousSection = selectedSection
        selectedSection = section
        // Reset space navigation when switching tabs
        if (section != RoomSectionType.SPACES) {
            currentSpaceId = null
        }
        
        // PERFORMANCE: Force immediate sort when switching tabs to show correct order
        if (previousSection != section) {
            android.util.Log.d("Andromuks", "AppViewModel: Tab changed from $previousSection to $section - forcing immediate sort")
            forceRoomListSort()
        }
        
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun enterSpace(spaceId: String) {
        currentSpaceId = spaceId
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun exitSpace() {
        currentSpaceId = null
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun incrementUpdateCounter() {
        updateCounter++
    }
    
    fun triggerTimestampUpdate() {
        timestampUpdateCounter++
    }
    
    /**
     * Updates animation state for a specific room
     */
    private fun updateRoomAnimationState(roomId: String, isAnimating: Boolean = false, newPosition: Int? = null) {
        val currentState = roomAnimationStates[roomId]
        val updatedState = RoomAnimationState(
            roomId = roomId,
            lastUpdateTime = System.currentTimeMillis(),
            isAnimating = isAnimating,
            previousPosition = currentState?.currentPosition,
            currentPosition = newPosition ?: currentState?.currentPosition
        )
        roomAnimationStates = roomAnimationStates + (roomId to updatedState)
        
        // MEMORY MANAGEMENT: Cleanup old animation states if we have too many
        if (roomAnimationStates.size > MAX_ANIMATION_STATES) {
            performAnimationStateCleanup()
        }
    }
    
    /**
     * Gets animation state for a specific room
     */
    fun getRoomAnimationState(roomId: String): RoomAnimationState? {
        return roomAnimationStates[roomId]
    }
    
    /**
     * Clears animation state for a room after animation completes
     */
    fun clearRoomAnimationState(roomId: String) {
        roomAnimationStates = roomAnimationStates - roomId
    }
    
    /**
     * MEMORY MANAGEMENT: Cleanup old animation states to prevent memory leaks
     */
    private fun performAnimationStateCleanup() {
        val currentTime = System.currentTimeMillis()
        val cutoffTime = currentTime - ANIMATION_STATE_CLEANUP_INTERVAL_MS
        
        // Remove old animation states that are no longer animating and haven't been updated recently
        val statesToRemove = roomAnimationStates.filter { (_, state) ->
            !state.isAnimating && state.lastUpdateTime < cutoffTime
        }.keys
        
        if (statesToRemove.isNotEmpty()) {
            roomAnimationStates = roomAnimationStates - statesToRemove.toSet()
            android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${statesToRemove.size} old animation states")
        }
    }
    
    fun restartWebSocketConnection(reason: String = "Manual reconnection") {
        android.util.Log.d("Andromuks", "AppViewModel: Restarting WebSocket connection - Reason: $reason")
        restartWebSocket(reason)
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
    fun performFullRefresh() {
        android.util.Log.d("Andromuks", "AppViewModel: Performing full refresh - resetting state")
        
        // 1. Drop WebSocket connection
        clearWebSocket("Full refresh")
        
        // 2. Clear all room data
        roomMap.clear()
        allRooms = emptyList()
        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
        allSpaces = emptyList()
        spaceList = emptyList()
        spacesLoaded = false
        personsApi?.clear()
        synchronized(readReceiptsLock) {
            readReceipts.clear()
        }
        roomsWithLoadedReceiptsFromDb.clear()
        roomsWithLoadedReactionsFromDb.clear()
        lastKnownDbLatestEventId.clear()
        messageReactions = emptyMap()
        readReceiptsUpdateCounter++
        
        // 3. Reset requestIdCounter to 1
        requestIdCounter = 1
        
        // 4. FORCE CLEAR last_received_sync_id to 0 for clean state (keep run_id for reconnection)
        lastReceivedSyncId = 0
        lastReceivedRequestId = 0
        
        // Sync cleared state with service
        WebSocketService.setReconnectionState(currentRunId, 0, vapidKey)
        
        val preservedRunId = currentRunId
        android.util.Log.d("Andromuks", "AppViewModel: State reset complete - run_id preserved: $preservedRunId")
        android.util.Log.d("Andromuks", "AppViewModel: FORCE REFRESH - lastReceivedSyncId cleared to 0, will reconnect with run_id but NO last_received_id (full payload)")
        
        // 5. Trigger reconnection (will use run_id but not last_received_id since it's 0)
        onRestartWebSocket?.invoke("Full refresh")
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
    fun getDirectRoomIdForUser(userId: String): String? {
        if (userId.isBlank()) return null

        val normalizedUserId = if (userId.startsWith("@")) userId else "@$userId"
        val candidateRooms = mutableListOf<RoomItem>()

        // 1. Use mapping from m.direct account data if available
        directMessageUserMap[normalizedUserId]?.forEach { roomId ->
            roomMap[roomId]?.let { candidateRooms.add(it) }
        }

        // 2. Fallback: scan known direct rooms for the user
        if (candidateRooms.isEmpty()) {
            val roomsToCheck = if (cachedDirectChatRooms.isNotEmpty()) {
                cachedDirectChatRooms
            } else {
                allRooms.filter { it.isDirectMessage }
            }

            for (room in roomsToCheck) {
                try {
                    val members = getMemberMap(room.id)
                    if (members.containsKey(normalizedUserId)) {
                        candidateRooms.add(room)
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Failed to inspect members for room ${room.id} when resolving DM for $normalizedUserId",
                        e
                    )
                }
            }
        }

        if (candidateRooms.isEmpty()) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: No direct room found for user $normalizedUserId"
            )
        }

        return candidateRooms.maxByOrNull { it.sortingTimestamp ?: 0L }?.id
    }

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
    
    /**
     * Get the count of unread rooms for Bridges tab
     */
    fun getBridgesUnreadCount(): Int {
        val roomsToCount = if (currentBridgeId != null) {
            // Count unread rooms in the selected bridge
            val bridge = allBridges.find { it.id == currentBridgeId }
            bridge?.rooms ?: emptyList()
        } else {
            // Count unread rooms across all bridges
            allBridges.flatMap { it.rooms }
        }
        return roomsToCount.count { 
            (it.unreadCount != null && it.unreadCount > 0) || 
            (it.highlightCount != null && it.highlightCount > 0) 
        }
    }
    
    /**
     * Check if Bridges has any room with highlights
     */
    fun hasBridgesHighlights(): Boolean {
        val roomsToCheck = if (currentBridgeId != null) {
            // Check highlights in the selected bridge
            val bridge = allBridges.find { it.id == currentBridgeId }
            bridge?.rooms ?: emptyList()
        } else {
            // Check highlights across all bridges
            allBridges.flatMap { it.rooms }
        }
        return roomsToCheck.any { it.highlightCount != null && it.highlightCount > 0 }
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
     * @return snapshot of bubble animation completion times (eventId -> millis when animation ends)
     */
    fun getNewMessageAnimations(): Map<String, Long> = newMessageAnimations.toMap()

    /**
     * Whether any message bubble animations are still running.
     */
    fun isBubbleAnimationRunning(): Boolean = runningBubbleAnimations.isNotEmpty()

    /**
     * Called by the UI when a bubble animation finishes so the timeline can proceed to scroll.
     * CRITICAL: Also removes the event from newMessageAnimations to prevent re-animation on recomposition.
     */
    fun notifyMessageAnimationFinished(eventId: String) {
        if (runningBubbleAnimations.remove(eventId)) {
            bubbleAnimationCompletionCounter++
            // CRITICAL: Remove from newMessageAnimations to prevent re-animation when items recompose
            // (e.g., when keyboard opens and causes scroll, items shouldn't animate again)
            newMessageAnimations.remove(eventId)
            android.util.Log.d("Andromuks", "AppViewModel: Animation finished for $eventId, removed from animation map")
        }
    }
    
    /**
     * Enable animations for a room after initial load completes.
     * This should be called after the room has been opened and scrolled to bottom.
     */
    fun enableAnimationsForRoom(roomId: String) {
        animationsEnabledForRoom[roomId] = true
        android.util.Log.d("Andromuks", "AppViewModel: Enabled animations for room: $roomId")
    }
    
    /**
     * Play a notification sound for new messages
     */
    private fun playNewMessageSound() {
        appContext?.let { context ->
            try {
                val mediaPlayer = MediaPlayer.create(context, net.vrkknn.andromuks.R.raw.pop_alert)
                mediaPlayer?.let { player ->
                    // Set audio stream to notification channel
                    player.setAudioStreamType(AudioManager.STREAM_NOTIFICATION)
                    
                    // Set completion listener to release resources
                    player.setOnCompletionListener { mp ->
                        mp.release()
                    }
                    
                    // Set error listener to handle any issues
                    player.setOnErrorListener { mp, what, extra ->
                        android.util.Log.w("Andromuks", "AppViewModel: Error playing notification sound: what=$what, extra=$extra")
                        mp.release()
                        true
                    }
                    
                    // Start playing
                    player.start()
                    android.util.Log.d("Andromuks", "AppViewModel: Playing new message sound")
                } ?: run {
                    android.util.Log.w("Andromuks", "AppViewModel: Failed to create MediaPlayer for new message sound")
                }
            } catch (e: Exception) {
                android.util.Log.w("Andromuks", "AppViewModel: Error playing new message sound", e)
            }
        }
    }
    
    /**
     * Update cached room sections to avoid expensive filtering on every recomposition.
     * Only recalculates when allRooms actually changes.
     */
    /**
     * PERFORMANCE OPTIMIZATION: Update cached room sections and badge counts
     * Always pre-computes badge counts for immediate tab bar display, but only
     * filters room lists for sections that have been loaded (lazy loading)
     */
    private fun updateCachedRoomSections() {
        // Get rooms from spaceList if allRooms is empty (fallback for existing data)
        val roomsToUse = if (allRooms.isEmpty() && spaceList.isNotEmpty()) {
            spaceList.firstOrNull()?.rooms ?: emptyList()
        } else {
            allRooms
        }
        
        // Check if we need to update cache (simple hash-based invalidation)
        val currentHash = roomsToUse.hashCode()
        if (currentHash == lastAllRoomsHash) {
            return // Cache is still valid
        }
        
        lastAllRoomsHash = currentHash
        
        // PERFORMANCE: Always pre-compute badge counts (needed for tab bar badges)
        // This is fast even for large room lists (O(n) single pass)
        updateBadgeCounts(roomsToUse)
        
        // PERFORMANCE: Only update filtered lists for sections that have been loaded
        // This defers expensive filtering until the user actually visits the tab
        if (loadedSections.contains(RoomSectionType.DIRECT_CHATS)) {
            cachedDirectChatRooms = roomsToUse.filter { it.isDirectMessage }
        }
        
        if (loadedSections.contains(RoomSectionType.UNREAD)) {
            cachedUnreadRooms = roomsToUse.filter { 
                (it.unreadCount != null && it.unreadCount > 0) || 
                (it.highlightCount != null && it.highlightCount > 0) 
            }
        }
        
        if (loadedSections.contains(RoomSectionType.FAVOURITES)) {
            cachedFavouriteRooms = roomsToUse.filter { it.isFavourite }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Updated cached sections - Loaded: $loadedSections, DMs: ${cachedDirectChatRooms.size}, Unread: ${cachedUnreadRooms.size}, Favourites: ${cachedFavouriteRooms.size}")
    }
    
    /**
     * PERFORMANCE: Pre-compute badge counts in a single O(n) pass
     * Always computed for immediate tab bar badge display
     */
    private fun updateBadgeCounts(rooms: List<RoomItem>) {
        var directChatsUnread = 0
        var directChatsHighlights = false
        var unreadCount = 0
        var favouritesUnread = 0
        var favouritesHighlights = false
        var bridgesUnread = 0
        var bridgesHighlights = false
        
        for (room in rooms) {
            val hasUnread = (room.unreadCount != null && room.unreadCount > 0) || 
                           (room.highlightCount != null && room.highlightCount > 0)
            val hasHighlights = room.highlightCount != null && room.highlightCount > 0
            
            // Count unread for all rooms
            if (hasUnread) {
                unreadCount++
            }
            
            // Count direct chats
            if (room.isDirectMessage) {
                if (hasUnread) {
                    directChatsUnread++
                    if (hasHighlights) {
                        directChatsHighlights = true
                    }
                }
            }
            
            // Count favourites
            if (room.isFavourite) {
                if (hasUnread) {
                    favouritesUnread++
                    if (hasHighlights) {
                        favouritesHighlights = true
                    }
                }
            }
        }
        
        // Update cached counts
        cachedDirectChatsUnreadCount = directChatsUnread
        cachedDirectChatsHasHighlights = directChatsHighlights
        cachedUnreadCount = unreadCount
        cachedFavouritesUnreadCount = favouritesUnread
        cachedFavouritesHasHighlights = favouritesHighlights
        cachedBridgesUnreadCount = bridgesUnread
        cachedBridgesHasHighlights = bridgesHighlights
        
        android.util.Log.d("Andromuks", "AppViewModel: Badge counts - DMs: $directChatsUnread, Unread: $unreadCount, Favs: $favouritesUnread")
    }

    private fun buildDirectPersonTargets(rooms: List<RoomItem>): List<PersonTarget> {
        if (currentUserId.isBlank()) {
            return emptyList()
        }

        val result = mutableMapOf<String, PersonTarget>()

        for (room in rooms) {
            if (!room.isDirectMessage) continue

            val timestamp = room.sortingTimestamp ?: 0L
            val roomDisplayName = room.name
            var foundOtherMember = false

            val memberMap = try {
                getMemberMap(room.id)
            } catch (e: Exception) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to get member map for ${room.id}", e)
                emptyMap()
            }

            for ((userId, profile) in memberMap) {
                if (userId == currentUserId) continue
                foundOtherMember = true
                val displayName = profile.displayName?.takeIf { it.isNotBlank() }
                    ?: roomDisplayName.ifBlank { userId }
                val existing = result[userId]
                if (existing == null || timestamp > existing.lastActiveTimestamp) {
                    result[userId] = PersonTarget(
                        userId = userId,
                        displayName = displayName,
                        avatarUrl = profile.avatarUrl,
                        roomId = room.id,
                        roomDisplayName = roomDisplayName,
                        lastActiveTimestamp = timestamp
                    )
                }
            }

            if (!foundOtherMember) {
                val inferredUserId = room.messageSender
                if (!inferredUserId.isNullOrBlank() && inferredUserId != currentUserId) {
                    val displayName = roomDisplayName.ifBlank { inferredUserId }
                    val existing = result[inferredUserId]
                    if (existing == null || timestamp > existing.lastActiveTimestamp) {
                        result[inferredUserId] = PersonTarget(
                            userId = inferredUserId,
                            displayName = displayName,
                            avatarUrl = null,
                            roomId = room.id,
                            roomDisplayName = roomDisplayName,
                            lastActiveTimestamp = timestamp
                        )
                    }
                }
            }
        }

        return result.values.sortedByDescending { it.lastActiveTimestamp }
    }
    
    fun getCurrentRoomSection(): RoomSection {
        // PERFORMANCE: Mark current section as loaded (enables lazy filtering)
        if (!loadedSections.contains(selectedSection)) {
            loadedSections.add(selectedSection)
            android.util.Log.d("Andromuks", "AppViewModel: Lazy loading section: $selectedSection")
            
            // If this is the first section being loaded, also mark HOME as loaded
            // This ensures badge counts are always computed (HOME doesn't need filtering)
            if (loadedSections.size == 1 && selectedSection != RoomSectionType.HOME) {
                loadedSections.add(RoomSectionType.HOME)
                android.util.Log.d("Andromuks", "AppViewModel: Auto-loading HOME section for badge counts")
            }
            
            // PERFORMANCE: Lazy load bridges when Bridges tab is first accessed
            if (selectedSection == RoomSectionType.BRIDGES) {
                loadBridgesIfNeeded()
            }
            
            // Trigger cache update to filter this section
            invalidateRoomSectionCache()
        }
        
        // Update cached room sections if needed
        updateCachedRoomSections()
        
        // Get rooms from spaceList if allRooms is empty (fallback for existing data)
        val roomsToUse = if (allRooms.isEmpty() && spaceList.isNotEmpty()) {
            spaceList.firstOrNull()?.rooms ?: emptyList()
        } else {
            allRooms
        }
        
        return when (selectedSection) {
            RoomSectionType.HOME -> RoomSection(
                type = RoomSectionType.HOME,
                rooms = roomsToUse
            )
            RoomSectionType.SPACES -> {
                android.util.Log.d("Andromuks", "AppViewModel: SPACES section - currentSpaceId = $currentSpaceId, allSpaces.size = ${allSpaces.size}")
                if (currentSpaceId != null) {
                    // Show rooms within the selected space
                    val selectedSpace = allSpaces.find { it.id == currentSpaceId }
                    android.util.Log.d("Andromuks", "AppViewModel: Selected space = $selectedSpace, rooms.size = ${selectedSpace?.rooms?.size ?: 0}")
                    RoomSection(
                        type = RoomSectionType.SPACES,
                        rooms = selectedSpace?.rooms ?: emptyList(),
                        spaces = emptyList()
                    )
                } else {
                    // Show list of spaces
                    android.util.Log.d("Andromuks", "AppViewModel: Showing space list with ${allSpaces.size} spaces")
                    RoomSection(
                        type = RoomSectionType.SPACES,
                        rooms = emptyList(),
                        spaces = allSpaces
                    )
                }
            }
            RoomSectionType.DIRECT_CHATS -> {
                // PERFORMANCE: Use cached direct chat rooms instead of filtering every time
                val unreadDmCount = cachedDirectChatRooms.count { 
                    (it.unreadCount != null && it.unreadCount > 0) || 
                    (it.highlightCount != null && it.highlightCount > 0) 
                }
                RoomSection(
                    type = RoomSectionType.DIRECT_CHATS,
                    rooms = cachedDirectChatRooms,
                    unreadCount = unreadDmCount
                )
            }
            RoomSectionType.UNREAD -> {
                // PERFORMANCE: Use cached unread rooms instead of filtering every time
                RoomSection(
                    type = RoomSectionType.UNREAD,
                    rooms = cachedUnreadRooms,
                    unreadCount = cachedUnreadRooms.size
                )
            }
            RoomSectionType.FAVOURITES -> {
                // PERFORMANCE: Use cached favourite rooms instead of filtering every time
                val unreadFavouriteCount = cachedFavouriteRooms.count { 
                    (it.unreadCount != null && it.unreadCount > 0) || 
                    (it.highlightCount != null && it.highlightCount > 0) 
                }
                RoomSection(
                    type = RoomSectionType.FAVOURITES,
                    rooms = cachedFavouriteRooms,
                    unreadCount = unreadFavouriteCount
                )
            }
            RoomSectionType.BRIDGES -> {
                if (currentBridgeId != null) {
                    // Show rooms for the selected bridge
                    val bridge = allBridges.find { it.id == currentBridgeId }
                    val bridgeRooms = bridge?.rooms ?: emptyList()
                    val unreadBridgeCount = bridgeRooms.count { 
                        (it.unreadCount != null && it.unreadCount > 0) || 
                        (it.highlightCount != null && it.highlightCount > 0) 
                    }
                    RoomSection(
                        type = RoomSectionType.BRIDGES,
                        rooms = bridgeRooms,
                        unreadCount = unreadBridgeCount
                    )
                } else {
                    // Show all bridges
                    val unreadBridgeCount = allBridges.sumOf { bridge ->
                        bridge.rooms.count { 
                            (it.unreadCount != null && it.unreadCount > 0) || 
                            (it.highlightCount != null && it.highlightCount > 0) 
                        }
                    }
                    RoomSection(
                        type = RoomSectionType.BRIDGES,
                        rooms = emptyList(),
                        bridges = allBridges,
                        unreadCount = unreadBridgeCount
                    )
                }
            }
        }
    }

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
    private fun updateLowPriorityRooms(rooms: List<RoomItem>) {
        val lowPriorityRoomIds = rooms.filter { it.isLowPriority }.map { it.id }.toSet()
        
        appContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit()
                .putStringSet("low_priority_rooms", lowPriorityRoomIds)
                .apply()
            
            android.util.Log.d("Andromuks", "AppViewModel: Updated low priority rooms set: ${lowPriorityRoomIds.size} rooms")
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
    fun initializeFCM(context: Context, homeserverUrl: String = "", authToken: String = "") {
        appContext = context
        
        // Clear current room ID on app startup - ensures notifications aren't suppressed after crash/restart
        // The room ID will be set again when user actually opens a room
        clearCurrentRoomId()
        android.util.Log.d("Andromuks", "AppViewModel: Cleared current room ID on app startup")
        
        val components = FCMNotificationManager.initializeComponents(
            context = context,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            realMatrixHomeserverUrl = realMatrixHomeserverUrl
        )
        fcmNotificationManager = components.fcmNotificationManager
        conversationsApi = components.conversationsApi
        personsApi = components.personsApi
        webClientPushIntegration = components.webClientPushIntegration
        
        // Network monitoring will be started when WebSocket service starts
    }
    
    
    /**
     * Registers FCM notifications with the Gomuks backend.
     * 
     * This function delegates to FCMNotificationManager.registerNotifications() to initiate
     * the FCM token registration process. When the token is ready, it triggers the
     * WebSocket-based registration with the Gomuks backend.
     */
    fun registerFCMNotifications() {
        android.util.Log.d("Andromuks", "AppViewModel: registerFCMNotifications called")
        android.util.Log.d("Andromuks", "AppViewModel: fcmNotificationManager=${fcmNotificationManager != null}, homeserverUrl=$homeserverUrl, authToken=${authToken.take(20)}..., currentUserId=$currentUserId")
        
        fcmNotificationManager?.let { manager ->
            android.util.Log.d("Andromuks", "AppViewModel: Calling FCMNotificationManager.registerNotifications")
            FCMNotificationManager.registerNotifications(
                fcmNotificationManager = manager,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                currentUserId = currentUserId,
                onTokenReady = {
                    android.util.Log.d("Andromuks", "AppViewModel: FCM token ready callback triggered, registering with Gomuks Backend")
                    registerFCMWithGomuksBackend()
                }
            )
        } ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: fcmNotificationManager is null, cannot register FCM notifications")
        }
    }
    
    /**
     * Unregisters FCM notifications from the backend.
     * 
     * This cleans up the FCM registration and removes the device from receiving
     * push notifications.
     */
    fun unregisterFCMNotifications() {
        fcmNotificationManager?.unregisterFromBackend()
    }
    
    /**
     * Get FCM token for Gomuks Backend registration
     */
    fun getFCMTokenForGomuksBackend(): String? {
        return fcmNotificationManager?.getTokenForGomuksBackend()
    }
    
    /**
     * Create push registration message for web client
     */
    fun createWebClientPushMessage(token: String): JSONObject? {
        return webClientPushIntegration?.createPushRegistrationMessage(token)
    }
    
    /**
     * Check if push registration should be performed (time-based)
     */
    fun shouldRegisterPush(): Boolean {
        val result = webClientPushIntegration?.shouldRegisterPush() ?: false
        android.util.Log.d("Andromuks", "AppViewModel: shouldRegisterPush() called, result=$result")
        return result
    }
    
    /**
     * Mark push registration as completed
     */
    fun markPushRegistrationCompleted() {
        webClientPushIntegration?.markPushRegistrationCompleted()
    }
    
    /**
     * Get device ID for push registration
     */
    fun getDeviceID(): String? {
        return webClientPushIntegration?.getDeviceID()
    }
    
    /**
     * Store FCM token for Gomuks Backend
     */
    fun storeFCMToken(token: String, context: Context) {
        fcmNotificationManager?.let { manager ->
            // Store token for Gomuks Backend registration
            val prefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("fcm_token_for_gomuks", token).apply()
        }
    }
    
    /**
     * Register FCM token with Gomuks Backend via WebSocket
     */
    fun registerFCMWithGomuksBackend() {
        android.util.Log.d("Andromuks", "AppViewModel: registerFCMWithGomuksBackend called")
        
        // Check if registration is needed (time-based check)
        val shouldRegister = shouldRegisterPush()
        android.util.Log.d("Andromuks", "AppViewModel: shouldRegisterPush() returned $shouldRegister")
        
        // Force registration if we have a new FCM token but haven't registered via WebSocket yet
        val hasRegisteredViaWebSocket = appContext?.let { context ->
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .getBoolean("fcm_registered_via_websocket", false)
        } ?: false
        val forceRegistration = !hasRegisteredViaWebSocket
        
        android.util.Log.d("Andromuks", "AppViewModel: hasRegisteredViaWebSocket=$hasRegisteredViaWebSocket, forceRegistration=$forceRegistration")
        
        // Always register FCM on every connection to ensure backend has current token
        android.util.Log.d("Andromuks", "AppViewModel: Always registering FCM on WebSocket connection to ensure backend has current token")
        
        val token = getFCMTokenForGomuksBackend()
        val deviceId = webClientPushIntegration?.getDeviceID()
        val encryptionKey = webClientPushIntegration?.getPushEncryptionKey()
        
        android.util.Log.d("Andromuks", "AppViewModel: registerFCMWithGomuksBackend - token=${token?.take(20)}..., deviceId=$deviceId, encryptionKey=${encryptionKey?.take(20)}...")
        android.util.Log.d("Andromuks", "AppViewModel: webClientPushIntegration=${webClientPushIntegration != null}")
        
        if (token != null && deviceId != null && encryptionKey != null) {
            val registrationRequestId = requestIdCounter++
            fcmRegistrationRequests[registrationRequestId] = "fcm_registration"
            
            android.util.Log.d("Andromuks", "AppViewModel: Registering FCM with request_id=$registrationRequestId")
            
            // Use the correct JSON structure for Gomuks Backend
            val registrationData = mapOf(
                "type" to "fcm",
                "device_id" to deviceId,
                "data" to token,
                "encryption" to mapOf(
                    "key" to encryptionKey
                ),
                "expiration" to (System.currentTimeMillis() / 1000 + 86400) // 24 hours from now
            )
            
            android.util.Log.d("Andromuks", "AppViewModel: Sending WebSocket command: register_push with data: $registrationData")
            sendWebSocketCommand("register_push", registrationRequestId, registrationData)
            
            android.util.Log.d("Andromuks", "AppViewModel: Sent FCM registration to Gomuks Backend with device_id=$deviceId")
            
            // Mark that we've attempted WebSocket registration (will be confirmed when response comes back)
            appContext?.let { context ->
                context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("fcm_websocket_registration_attempted", true)
                    .apply()
            }
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Missing required data for FCM registration - token=${token != null}, deviceId=${deviceId != null}, encryptionKey=${encryptionKey != null}")
            android.util.Log.w("Andromuks", "AppViewModel: webClientPushIntegration=${webClientPushIntegration != null}")
        }
    }
    
    /**
     * Handle FCM registration response from Gomuks Backend
     */
    fun handleFCMRegistrationResponse(requestId: Int, data: Any) {
        android.util.Log.d("Andromuks", "AppViewModel: handleFCMRegistrationResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}")
        android.util.Log.d("Andromuks", "AppViewModel: FCM registration response data: $data")
        
        // Remove from pending requests
        fcmRegistrationRequests.remove(requestId)
        
        // Handle the response - it could be a boolean, string, or object
        when (data) {
            is Boolean -> {
                if (data) {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration successful (boolean true)")
                    webClientPushIntegration?.markPushRegistrationCompleted()
                    // Mark that WebSocket registration was successful
                    appContext?.let { context ->
                        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("fcm_registered_via_websocket", true)
                            .apply()
                    }
                    android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket")
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (boolean false)")
                }
            }
            is String -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (string): $data")
                // Assume string response means success
                webClientPushIntegration?.markPushRegistrationCompleted()
                // Mark that WebSocket registration was successful
                appContext?.let { context ->
                    context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("fcm_registered_via_websocket", true)
                        .apply()
                }
                android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket (string response)")
            }
            is org.json.JSONObject -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (JSON): ${data.toString()}")
                // Check if there's a success field or assume JSON response means success
                val success = data.optBoolean("success", true)
                if (success) {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration successful (JSON)")
                    webClientPushIntegration?.markPushRegistrationCompleted()
                    // Mark that WebSocket registration was successful
                    appContext?.let { context ->
                        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("fcm_registered_via_websocket", true)
                            .apply()
                    }
                    android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket (JSON response)")
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (JSON)")
                }
            }
            else -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (unknown type): $data")
                // Assume any response means success
                webClientPushIntegration?.markPushRegistrationCompleted()
                // Mark that WebSocket registration was successful
                appContext?.let { context ->
                    context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("fcm_registered_via_websocket", true)
                        .apply()
                }
                android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket (unknown response type)")
            }
        }
    }
    
    fun updateTypingUsers(roomId: String, userIds: List<String>) {
        // Only update if this is the current room
        if (currentRoomId == roomId) {
            typingUsers = userIds
        }
    }
    
    private fun normalizeTimestamp(primary: Long, vararg fallbacks: Long): Long {
        if (primary > 0) return primary
        for (candidate in fallbacks) {
            if (candidate > 0) return candidate
        }
        return System.currentTimeMillis()
    }
    
    fun processReactionEvent(reactionEvent: ReactionEvent, isHistorical: Boolean = false) {
        // Create a unique key for this logical reaction (sender + emoji + target message)
        // This prevents the same logical reaction from being processed twice even if it comes
        // from both send_complete and sync_complete with different event IDs
        val reactionKey = "${reactionEvent.sender}_${reactionEvent.emoji}_${reactionEvent.relatesToEventId}"
        
        // Only apply duplicate detection to live reactions, not historical reactions
        // Historical reactions should always be processed as they may have been previously
        // processed during app startup but need to be displayed in the current room context
        if (!isHistorical && processedReactions.contains(reactionKey)) {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping duplicate logical reaction: $reactionKey (eventId: ${reactionEvent.eventId})")
            return
        }
        
        // Only mark live reactions as processed to prevent duplicates
        if (!isHistorical) {
            processedReactions.add(reactionKey)
        }
        
        // Clean up old processed reactions to prevent memory leaks (keep only last 100)
        // Only do cleanup for live reactions since historical reactions don't add to processedReactions
        if (!isHistorical && processedReactions.size > 100) {
            val toRemove = processedReactions.take(processedReactions.size - 100)
            processedReactions.removeAll(toRemove)
        }
        
        val previousReactions = messageReactions[reactionEvent.relatesToEventId] ?: emptyList()
        messageReactions = net.vrkknn.andromuks.utils.processReactionEvent(reactionEvent, currentRoomId, messageReactions)
        val updatedReactions = messageReactions[reactionEvent.relatesToEventId] ?: emptyList()
        android.util.Log.d("Andromuks", "AppViewModel: processReactionEvent - eventId: ${reactionEvent.eventId}, logicalKey: $reactionKey, previous=${previousReactions.size}, updated=${updatedReactions.size}, reactionUpdateCounter: $reactionUpdateCounter")
        
        if (!isHistorical) {
            val previousPairs = previousReactions.flatMap { reaction ->
                reaction.users.map { userId -> reaction.emoji to userId }
            }.toSet()
            val updatedPairs = updatedReactions.flatMap { reaction ->
                reaction.users.map { userId -> reaction.emoji to userId }
            }.toSet()

            val additionOccurred = updatedPairs.contains(reactionEvent.emoji to reactionEvent.sender) &&
                !previousPairs.contains(reactionEvent.emoji to reactionEvent.sender)
            val removalOccurred = previousPairs.contains(reactionEvent.emoji to reactionEvent.sender) &&
                !updatedPairs.contains(reactionEvent.emoji to reactionEvent.sender)

            val applicationContext = appContext?.applicationContext
            if (applicationContext != null && (additionOccurred || removalOccurred)) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(applicationContext)
                        if (additionOccurred) {
                            val reactionEntity = net.vrkknn.andromuks.database.entities.ReactionEntity(
                                roomId = reactionEvent.roomId,
                                targetEventId = reactionEvent.relatesToEventId,
                                key = reactionEvent.emoji,
                                sender = reactionEvent.sender,
                                eventId = reactionEvent.eventId,
                                timestamp = normalizeTimestamp(reactionEvent.timestamp)
                            )
                            database.reactionDao().upsertAll(listOf(reactionEntity))
                            android.util.Log.d("Andromuks", "AppViewModel: Persisted reaction to DB for event ${reactionEvent.relatesToEventId}")
                        }
                        if (removalOccurred) {
                            database.reactionDao().deleteByEventIds(listOf(reactionEvent.eventId))
                            android.util.Log.d("Andromuks", "AppViewModel: Removed reaction from DB for reaction event ${reactionEvent.eventId}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Failed to persist reaction change for ${reactionEvent.eventId}", e)
                    }
                }
            }
        }

        reactionUpdateCounter++ // Trigger UI recomposition for reactions only
        updateCounter++ // Keep for backward compatibility temporarily
    }

    fun handleClientState(userId: String?, device: String?, homeserver: String?) {
        if (!userId.isNullOrBlank()) {
            currentUserId = userId
            android.util.Log.d("Andromuks", "AppViewModel: Set currentUserId: $userId")
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
            android.util.Log.d("Andromuks", "AppViewModel: Set realMatrixHomeserverUrl: $homeserver")
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

    // Use a Map for efficient room lookups and updates
    private val roomMap = mutableMapOf<String, RoomItem>()
    private var syncMessageCount = 0
    
    // Track newly joined rooms (rooms that appeared in sync_complete for the first time)
    // These should be sorted to the top of the room list
    private val newlyJoinedRoomIds = mutableSetOf<String>()

    // MEMORY MANAGEMENT: Flattened member cache for better memory usage and performance
    // Using roomId:userId as key instead of nested maps to reduce memory fragmentation
    private val flattenedMemberCache = ConcurrentHashMap<String, MemberProfile>() // Key: "roomId:userId"
    
    // OPTIMIZED: Indexed cache for fast room lookups (avoids string prefix checks)
    private val roomMemberIndex = ConcurrentHashMap<String, MutableSet<String>>() // Key: roomId, Value: Set of userIds
    
    // Global user profile cache with access timestamps for LRU-style cleanup
    private data class CachedProfileEntry(var profile: MemberProfile, var lastAccess: Long)
    private val globalProfileCache = ConcurrentHashMap<String, CachedProfileEntry>()
    
    // Legacy room member cache (deprecated, kept for compatibility)
    private val roomMemberCache = ConcurrentHashMap<String, ConcurrentHashMap<String, MemberProfile>>()
    
    // OPTIMIZED EDIT/REDACTION SYSTEM - O(1) lookups for all operations
    // Maps original event ID to its complete version history
    private val messageVersions = mutableMapOf<String, VersionedMessage>()
    
    // Maps edit event ID back to original event ID for quick lookup
    private val editToOriginal = mutableMapOf<String, String>()
    
    // Maps redacted event ID to the redaction event for O(1) deletion message creation
    private val redactionCache = mutableMapOf<String, TimelineEvent>()

    fun getMemberProfile(roomId: String, userId: String): MemberProfile? {
        // MEMORY MANAGEMENT: Try room-specific cache first (only exists if profile differs from global)
        val flattenedKey = "$roomId:$userId"
        val flattenedProfile = flattenedMemberCache[flattenedKey]
        if (flattenedProfile != null) {
            return flattenedProfile
        }
        
        // If no room-specific profile, check global cache
        val globalProfileEntry = globalProfileCache[userId]
        val globalProfile = globalProfileEntry?.profile
        if (globalProfile != null) {
            globalProfileEntry.lastAccess = System.currentTimeMillis()
            return globalProfile
        }
        
        // Fallback to legacy cache (for compatibility during transition)
        return roomMemberCache[roomId]?.get(userId)
    }

    fun getMemberMap(roomId: String): Map<String, MemberProfile> {
        // OPTIMIZED: Use indexed cache for O(1) lookups instead of scanning all entries
        val memberMap = mutableMapOf<String, MemberProfile>()
        
        // Try indexed lookup first - get room-specific profiles
        val userIds = roomMemberIndex[roomId]
        if (userIds != null && userIds.isNotEmpty()) {
            for (userId in userIds) {
                val flattenedKey = "$roomId:$userId"
                val profile = flattenedMemberCache[flattenedKey]
                if (profile != null) {
                    memberMap[userId] = profile
                } else {
                    // Room-specific profile doesn't exist, check global cache
                    val globalProfileEntry = globalProfileCache[userId]
                    val globalProfile = globalProfileEntry?.profile
                    if (globalProfile != null) {
                        globalProfileEntry.lastAccess = System.currentTimeMillis()
                        memberMap[userId] = globalProfile
                    }
                }
            }
        } else {
            // Fallback to legacy cache if index is empty
            if (roomMemberCache.containsKey(roomId)) {
                roomMemberCache[roomId]?.let { legacyMap ->
                    memberMap.putAll(legacyMap)
                }
            }
        }
        
        return memberMap
    }
    
    /**
     * Enhanced getMemberMap that includes global cache fallback for users in timeline events
     */
    fun getMemberMapWithFallback(roomId: String, timelineEvents: List<TimelineEvent>? = null): Map<String, MemberProfile> {
        val memberMap = getMemberMap(roomId).toMutableMap()
        
        // If timeline events are provided, add fallback profiles from global cache for users in events
        timelineEvents?.let { events ->
            for (event in events) {
                val sender = event.sender
                if (!memberMap.containsKey(sender)) {
                    // Check global cache and add to member map if found
                    val globalProfileEntry = globalProfileCache[sender]
                    val globalProfile = globalProfileEntry?.profile
                    if (globalProfile != null) {
                        globalProfileEntry.lastAccess = System.currentTimeMillis()
                        memberMap[sender] = globalProfile
                        android.util.Log.d("Andromuks", "AppViewModel: Added global profile fallback for $sender in room $roomId")
                    }
                }
            }
        }
        
        return memberMap
    }
    fun isMemberCacheEmpty(roomId: String): Boolean {
        // MEMORY MANAGEMENT: Check if any flattened entries exist for this room
        val hasFlattenedEntries = flattenedMemberCache.keys.any { it.startsWith("$roomId:") }
        if (hasFlattenedEntries) {
            return false
        }
        
        // Fallback to legacy cache
        return roomMemberCache[roomId]?.isEmpty() ?: true
    }
    
    /**
     * MEMORY MANAGEMENT: Helper method to store member profile in both flattened and legacy caches
     * OPTIMIZATION: Only stores room-specific profile if it differs from global profile to avoid redundancy.
     * 
     * Strategy:
     * - If no global profile exists, set it as global (no room-specific entry needed)
     * - If global profile exists and matches, don't store room-specific entry (use global)
     * - If global profile exists and differs, store room-specific entry (but don't update global)
     * 
     * Note: Global profile should ideally come from explicit profile requests, not room member events.
     * This prevents the global from drifting and ensures room-specific entries only exist when truly different.
     */
    private fun storeMemberProfile(roomId: String, userId: String, profile: MemberProfile) {
        // Check existing global profile BEFORE updating (to compare)
        val existingGlobalProfileEntry = globalProfileCache[userId]
        val existingGlobalProfile = existingGlobalProfileEntry?.profile
        
        val flattenedKey = "$roomId:$userId"
        
        if (existingGlobalProfile == null) {
            // No global profile yet - set this as global, don't store room-specific
            globalProfileCache[userId] = CachedProfileEntry(profile, System.currentTimeMillis())
            // Remove any existing room-specific entry (cleanup)
            flattenedMemberCache.remove(flattenedKey)
            roomMemberIndex[roomId]?.remove(userId)
        } else {
            // Global profile exists - compare
            val profilesDiffer = existingGlobalProfile.displayName != profile.displayName ||
                existingGlobalProfile.avatarUrl != profile.avatarUrl
            
            if (profilesDiffer) {
                // Profile differs from global - store room-specific entry
                flattenedMemberCache[flattenedKey] = profile
                
                // OPTIMIZED: Update indexed cache for fast lookups
                roomMemberIndex.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
                
                // DON'T update global here - it should come from explicit profile requests
                // This prevents global from drifting and ensures consistency
            } else {
                // Profile matches global - remove room-specific entry if it exists (cleanup)
                flattenedMemberCache.remove(flattenedKey)
                // Also remove from index if present
                roomMemberIndex[roomId]?.remove(userId)
            }
        }
        
        // Also maintain legacy cache for compatibility (but this will be deprecated)
        val memberMap = roomMemberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
        memberMap[userId] = profile
        
        // MEMORY MANAGEMENT: Cleanup if cache gets too large
        if (flattenedMemberCache.size > MAX_MEMBER_CACHE_SIZE) {
            performMemberCacheCleanup()
        }
    }
    
    /**
     * Updates the global profile cache explicitly (e.g., from profile requests).
     * This should be called when we receive a canonical profile, not from room member events.
     * When global profile is updated, we should re-evaluate room-specific entries.
     */
    fun updateGlobalProfile(userId: String, profile: MemberProfile) {
        val existingGlobalProfileEntry = globalProfileCache[userId]
        val existingGlobalProfile = existingGlobalProfileEntry?.profile
        
        // Update global profile
        globalProfileCache[userId] = CachedProfileEntry(profile, System.currentTimeMillis())
        
        // If global profile changed, clean up room-specific entries that now match global
        if (existingGlobalProfile != null && 
            (existingGlobalProfile.displayName != profile.displayName ||
             existingGlobalProfile.avatarUrl != profile.avatarUrl)) {
            
            // Find all room-specific entries for this user and remove those that now match global
            val keysToRemove = mutableListOf<String>()
            for ((key, roomProfile) in flattenedMemberCache) {
                if (key.endsWith(":$userId")) {
                    // Check if room profile now matches the new global profile
                    if (roomProfile.displayName == profile.displayName &&
                        roomProfile.avatarUrl == profile.avatarUrl) {
                        keysToRemove.add(key)
                        
                        // Also remove from index
                        val roomId = key.substringBefore(":")
                        roomMemberIndex[roomId]?.remove(userId)
                    }
                }
            }
            
            // Remove matching entries
            for (key in keysToRemove) {
                flattenedMemberCache.remove(key)
            }
            
            if (keysToRemove.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${keysToRemove.size} room-specific profile entries that now match global for $userId")
            }
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Cleanup old member cache entries to prevent memory pressure
     */
    private fun performMemberCacheCleanup() {
        val currentTime = System.currentTimeMillis()
        val cutoffTime = currentTime - (24 * 60 * 60 * 1000) // 24 hours ago
        
        // Clean up stale global profile cache entries (LRU-style)
        globalProfileCache.entries.removeIf { (_, entry) ->
            currentTime - entry.lastAccess > cutoffTime
        }

        // Enforce cache size limit by evicting least recently accessed entries
        if (globalProfileCache.size > MAX_MEMBER_CACHE_SIZE) {
            val overflow = globalProfileCache.size - MAX_MEMBER_CACHE_SIZE
            val oldestKeys = globalProfileCache.entries
                .sortedBy { it.value.lastAccess }
                .take(overflow)
                .map { it.key }
            oldestKeys.forEach { globalProfileCache.remove(it) }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Performed member cache cleanup - flattened: ${flattenedMemberCache.size}, global: ${globalProfileCache.size}")
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
     * Loads edit history from database if not in memory.
     * This is useful when the user requests edit history on a cold start or after memory was cleared.
     * @param eventId The original event ID (not the edit event ID)
     * @param roomId The room ID containing the event
     * @return VersionedMessage if found in database, null otherwise
     */
    suspend fun loadMessageVersionsFromDb(eventId: String, roomId: String): VersionedMessage? = withContext(Dispatchers.IO) {
        try {
            val context = appContext ?: return@withContext null
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
            val eventDao = database.eventDao()
            
            // Get the original event
            val originalEntity = eventDao.getEventById(roomId, eventId) ?: return@withContext null
            val originalJson = org.json.JSONObject(originalEntity.rawJson)
            // Add missing fields that TimelineEvent.fromJson expects
            originalJson.put("rowid", originalEntity.timelineRowId.toLong())
            originalJson.put("timeline_rowid", originalEntity.timelineRowId)
            originalJson.put("room_id", originalEntity.roomId)
            val originalEvent = TimelineEvent.fromJson(originalJson)
            
            // Get all edit events that relate to this event
            val editEntities = eventDao.getEventsByRelatesTo(roomId, eventId)
            
            val versions = mutableListOf<MessageVersion>()
            
            // Add original event
            versions.add(MessageVersion(
                eventId = originalEvent.eventId,
                event = originalEvent,
                timestamp = originalEvent.timestamp,
                isOriginal = true
            ))
            
            // Add edit events
            for (editEntity in editEntities) {
                val editJson = org.json.JSONObject(editEntity.rawJson)
                // Add missing fields that TimelineEvent.fromJson expects
                editJson.put("rowid", editEntity.timelineRowId.toLong())
                editJson.put("timeline_rowid", editEntity.timelineRowId)
                editJson.put("room_id", editEntity.roomId)
                val editEvent = TimelineEvent.fromJson(editJson)
                
                versions.add(MessageVersion(
                    eventId = editEvent.eventId,
                    event = editEvent,
                    timestamp = editEvent.timestamp,
                    isOriginal = false
                ))
                
                // Store reverse mapping for quick lookup
                editToOriginal[editEvent.eventId] = eventId
            }
            
            // Sort by timestamp (newest first)
            val sortedVersions = versions.sortedByDescending { it.timestamp }
            
            val versioned = VersionedMessage(
                originalEventId = eventId,
                originalEvent = originalEvent,
                versions = sortedVersions
            )
            
            // Cache in memory for future lookups
            messageVersions[eventId] = versioned
            
            android.util.Log.d("Andromuks", "AppViewModel: Loaded ${sortedVersions.size} versions from DB for event $eventId (${editEntities.size} edits)")
            
            return@withContext versioned
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load message versions from DB for $eventId", e)
            return@withContext null
        }
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
     * Note: This only checks in-memory cache. For database check, use loadMessageVersionsFromDb.
     */
    fun isMessageEdited(eventId: String): Boolean {
        val versioned = getMessageVersions(eventId)
        return versioned != null && versioned.versions.size > 1
    }
    
    /**
     * Checks if a message has been edited by querying the database.
     * This is useful when checking edit status on cold start.
     */
    suspend fun isMessageEditedInDb(eventId: String, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val context = appContext ?: return@withContext false
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
            val eventDao = database.eventDao()
            
            // Check if there are any edit events that relate to this event
            val editCount = eventDao.getEventsByRelatesTo(roomId, eventId).size
            return@withContext editCount > 0
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to check if message is edited in DB for $eventId", e)
            return@withContext false
        }
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
     * Gets all profiles from memory cache (flattenedMemberCache + globalProfileCache).
     * For memory cache, we preserve room-specific profiles since Matrix allows different
     * display names/avatars per room.
     * @return List of RoomProfileEntry with roomId and profile info, sorted by display name
     */
    suspend fun getAllMemoryCachedProfiles(): List<RoomProfileEntry> = withContext(Dispatchers.Default) {
        val profiles = mutableListOf<RoomProfileEntry>()
        
        // Collect from flattened member cache (room-specific profiles)
        // Keys are in format "roomId:userId" where roomId starts with '!' and userId starts with '@'
        for ((key, profile) in flattenedMemberCache) {
            // Find the last colon (userId starts with '@' so we need to find the separator correctly)
            val lastColonIndex = key.lastIndexOf(':')
            if (lastColonIndex > 0 && lastColonIndex < key.length - 1) {
                val roomId = key.substring(0, lastColonIndex)
                val userId = key.substring(lastColonIndex + 1)
                // Validate that userId is a valid Matrix user ID (starts with '@' and contains ':')
                // Also ensure it's not just a domain (like "aguiarvieira.pt")
                if (userId.startsWith("@") && userId.contains(":") && userId.length > 2) {
                    // Additional validation: userId should have format @name:domain
                    val parts = userId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("@") && parts[0].length > 1 && parts[1].isNotEmpty()) {
                        // Validate roomId format (should start with '!' or be a valid room ID)
                        // Allow roomId to be present even if it doesn't start with '!' (for edge cases)
                        profiles.add(RoomProfileEntry(
                            roomId = roomId.takeIf { it.isNotEmpty() },
                            userId = userId,
                            profile = profile
                        ))
                    }
                }
            }
        }
        
        // Collect from global profile cache (room-agnostic profiles)
        // These are global (not room-specific), so roomId is null
        for ((userId, entry) in globalProfileCache) {
            val profile = entry.profile
                // Validate userId format: must be @name:domain
                if (userId.startsWith("@") && userId.contains(":") && userId.length > 2) {
                    val parts = userId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("@") && parts[0].length > 1 && parts[1].isNotEmpty()) {
                        profiles.add(RoomProfileEntry(
                            roomId = null, // Global profile, not room-specific
                            userId = userId,
                            profile = profile
                        ))
                }
            }
        }
        
        // Sort by display name (nulls last), then by userId, then by roomId
        profiles.sortedWith(compareBy(
            { it.profile.displayName ?: "\uFFFF" }, // Put nulls at the end
            { it.userId },
            { it.roomId ?: "" }
        ))
    }
    
    /**
     * Gets unique user profiles from memory cache (deduplicated by userId).
     * Use this if you want to show only one entry per user (merged profile data).
     * @return List of pairs (userId, MemberProfile) sorted by display name
     */
    suspend fun getUniqueMemoryCachedProfiles(): List<Pair<String, MemberProfile>> = withContext(Dispatchers.Default) {
        val profiles = mutableMapOf<String, MemberProfile>()
        
        // Collect from flattened member cache (room-specific profiles)
        for ((key, profile) in flattenedMemberCache) {
            val lastColonIndex = key.lastIndexOf(':')
            if (lastColonIndex > 0 && lastColonIndex < key.length - 1) {
                val userId = key.substring(lastColonIndex + 1)
                if (userId.startsWith("@") && userId.contains(":") && userId.length > 2) {
                    val parts = userId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("@") && parts[0].length > 1 && parts[1].isNotEmpty()) {
                        // Merge profile data intelligently: prefer non-null values
                        val existing = profiles[userId]
                        if (existing == null) {
                            profiles[userId] = profile
                        } else {
                            profiles[userId] = MemberProfile(
                                displayName = profile.displayName ?: existing.displayName,
                                avatarUrl = profile.avatarUrl ?: existing.avatarUrl
                            )
                        }
                    }
                }
            }
        }
        
        // Collect from global profile cache
        for ((userId, entry) in globalProfileCache) {
            val profile = entry.profile
                if (userId.startsWith("@") && userId.contains(":") && userId.length > 2) {
                    val parts = userId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("@") && parts[0].length > 1 && parts[1].isNotEmpty()) {
                        val existing = profiles[userId]
                        if (existing == null) {
                            profiles[userId] = profile
                        } else {
                            profiles[userId] = MemberProfile(
                                displayName = profile.displayName ?: existing.displayName,
                                avatarUrl = profile.avatarUrl ?: existing.avatarUrl
                            )
                    }
                }
            }
        }
        
        profiles.toList().sortedWith(compareBy(
            { it.second.displayName ?: "\uFFFF" },
            { it.first }
        ))
    }
    
    /**
     * Gets all profiles from disk cache (ProfileRepository).
     * @return List of pairs (userId, MemberProfile) sorted by display name
     */
    suspend fun getAllDiskCachedProfiles(context: Context): List<Pair<String, MemberProfile>> = withContext(Dispatchers.IO) {
        try {
            val profileRepository = net.vrkknn.andromuks.database.ProfileRepository(context)
            profileRepository.getAllProfiles()
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to get disk cached profiles", e)
            emptyList()
        }
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
            val entries = mutableListOf<CachedMediaEntry>()
            
            // Show Coil's disk cache (these get loaded into RAM when accessed)
            val coilCacheDir = java.io.File(context.cacheDir, "image_cache")
            android.util.Log.d("Andromuks", "AppViewModel: Checking Coil disk cache at: ${coilCacheDir.absolutePath}, exists: ${coilCacheDir.exists()}")
            
            if (coilCacheDir.exists() && coilCacheDir.isDirectory) {
                val files = coilCacheDir.walkTopDown().filter { it.isFile }.toList()
                android.util.Log.d("Andromuks", "AppViewModel: Found ${files.size} files in Coil disk cache")
                
                for (file in files) {
                    // Try to find MXC URL from event database
                    val mxcUrl = findMxcUrlForCoilFile(context, file)
                    
                    entries.add(CachedMediaEntry(
                        mxcUrl = mxcUrl,
                        filePath = file.absolutePath,
                        fileSize = file.length(),
                        cacheType = "memory",
                        file = file
                    ))
                }
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: Coil disk cache directory does not exist or is not a directory")
            }
            
            android.util.Log.d("Andromuks", "AppViewModel: Returning ${entries.size} memory cached media entries")
            entries.sortedByDescending { it.fileSize }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to get memory cached media", e)
            emptyList()
        }
    }
    
    /**
     * Find MXC URL for a Coil cached file by checking event database.
     */
    private suspend fun findMxcUrlForCoilFile(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        try {
            // Coil's disk cache uses URL-based keys, so we need to check event database
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
            val eventDao = database.eventDao()
            
            val rooms = roomMap.keys.toList().take(20) // Limit to first 20 rooms
            
            for (roomId in rooms) {
                try {
                    val events = eventDao.getEventsForRoomDesc(roomId, 50) // Get last 50 events per room
                    for (event in events) {
                        val content = event.rawJson ?: continue
                        val mxcUrls = extractMxcUrlsFromJson(content)
                        
                        for (mxcUrl in mxcUrls) {
                            // Convert MXC to HTTP URL and check if it matches the file
                            val httpUrl = net.vrkknn.andromuks.utils.MediaUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
                            if (httpUrl != null) {
                                // Coil uses URL hash as cache key - try to match
                                val urlHash = httpUrl.hashCode().toString()
                                if (file.name.contains(urlHash) || file.absolutePath.contains(httpUrl.replace("https://", "").replace("http://", ""))) {
                                    return@withContext mxcUrl
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue with next room
                }
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
            
            // 2. Get entries from Coil's disk cache
            val coilCacheDir = java.io.File(context.cacheDir, "image_cache")
            if (coilCacheDir.exists() && coilCacheDir.isDirectory) {
                val files = coilCacheDir.walkTopDown().filter { it.isFile }
                for (file in files) {
                    // Try to find MXC URL from event database
                    val mxcUrl = findMxcUrlForCoilFile(context, file)
                    
                    entries.add(CachedMediaEntry(
                        mxcUrl = mxcUrl, // May be null if not found
                        filePath = file.absolutePath,
                        fileSize = file.length(),
                        cacheType = "disk",
                        file = file
                    ))
                }
            }
            
            entries.sortedByDescending { it.fileSize }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to get disk cached media", e)
            emptyList()
        }
    }
    
    /**
     * Find MXC URL for a cached file by checking IntelligentMediaCache first, then event database.
     */
    private suspend fun findMxcUrlForFile(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = file.name
            
            // First, try IntelligentMediaCache's direct lookup
            val mxcUrl: String? = IntelligentMediaCache.getMxcUrlForFile(fileName)
            if (mxcUrl != null) {
                return@withContext mxcUrl
            }
            
            // Fallback: check event database (limited to avoid performance issues)
            // This is slower but necessary for files not tracked by IntelligentMediaCache
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
            val eventDao = database.eventDao()
            
            val rooms = roomMap.keys.toList().take(20) // Limit to first 20 rooms
            
            for (roomId in rooms) {
                try {
                    val events = eventDao.getEventsForRoomDesc(roomId, 50) // Get last 50 events per room
                    for (event in events) {
                        val content = event.rawJson ?: continue
                        val mxcUrls = extractMxcUrlsFromJson(content)
                        
                        for (mxcUrl in mxcUrls) {
                            val cacheKey = IntelligentMediaCache.getCacheKey(mxcUrl)
                            if (fileName.contains(cacheKey) || fileName == cacheKey || cacheKey.contains(fileName)) {
                                return@withContext mxcUrl
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue with next room
                }
            }
            
            null
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error finding MXC URL for file ${file.name}", e)
            null
        }
    }
    
    /**
     * Extract MXC URLs from JSON content (checks common fields like url, thumbnail_url, avatar_url, etc.)
     */
    private fun extractMxcUrlsFromJson(json: String): List<String> {
        val mxcUrls = mutableListOf<String>()
        try {
            val jsonObj = org.json.JSONObject(json)
            
            // Check common fields that contain MXC URLs
            val fieldsToCheck = listOf("url", "thumbnail_url", "avatar_url", "info", "file")
            
            for (field in fieldsToCheck) {
                val value = jsonObj.optString(field, null)
                if (value != null && value.startsWith("mxc://")) {
                    mxcUrls.add(value)
                }
                
                // Check nested objects
                val nestedObj = jsonObj.optJSONObject(field)
                if (nestedObj != null) {
                    val nestedUrl = nestedObj.optString("url", null)
                    if (nestedUrl != null && nestedUrl.startsWith("mxc://")) {
                        mxcUrls.add(nestedUrl)
                    }
                    
                    val thumbnailUrl = nestedObj.optString("thumbnail_url", null)
                    if (thumbnailUrl != null && thumbnailUrl.startsWith("mxc://")) {
                        mxcUrls.add(thumbnailUrl)
                    }
                    
                    // Check info.url for media messages
                    val infoObj = nestedObj.optJSONObject("info")
                    if (infoObj != null) {
                        val infoUrl = infoObj.optString("url", null)
                        if (infoUrl != null && infoUrl.startsWith("mxc://")) {
                            mxcUrls.add(infoUrl)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        
        return mxcUrls
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
            val flattenedKey = "$roomId:$userId"
            val flattenedProfile = flattenedMemberCache[flattenedKey]
            if (flattenedProfile != null) {
                return flattenedProfile
            }
            
            // Fallback to legacy room member cache
            val roomMember = roomMemberCache[roomId]?.get(userId)
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
            val globalProfileEntry = globalProfileCache[userId]
            val globalProfile = globalProfileEntry?.profile
            if (globalProfile != null) {
                globalProfileEntry.lastAccess = System.currentTimeMillis()
                return globalProfile
            }
        }
        
        // OPTIMIZED: Check global profile cache (fallback for when no roomId or room-specific not found)
        val globalProfileEntry = globalProfileCache[userId]
        val globalProfile = globalProfileEntry?.profile
        if (globalProfile != null) {
            globalProfileEntry.lastAccess = System.currentTimeMillis()
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
        android.util.Log.d("Andromuks", "AppViewModel: Async requesting profile for Matrix user: $userId")
        requestUserProfile(userId, roomId)
    }
    

    
    /**
     * Helper function to check if an event is an edit (m.replace relationship)
     */
    private fun isEditEvent(event: TimelineEvent): Boolean {
        return when {
            event.type == "m.room.message" -> 
                event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> 
                event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            else -> false
        }
    }
    /**
     * OPTIMIZED: Process events to build version cache (O(n) where n = number of events)
     * This replaces the old chain-following approach with direct version storage
     */
    private fun processVersionedMessages(events: List<TimelineEvent>) {
        for (event in events) {
            when {
                // Handle redaction events - O(1) storage
                event.type == "m.room.redaction" -> {
                    val redactsEventId = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                    
                    if (redactsEventId != null) {
                        // Store in redaction cache for O(1) lookup
                        redactionCache[redactsEventId] = event
                        
                        // Mark the original message as redacted
                        val versioned = messageVersions[redactsEventId]
                        if (versioned != null) {
                            messageVersions[redactsEventId] = versioned.copy(
                                redactedBy = event.eventId,
                                redactionEvent = event
                            )
                        } else {
                            // Redaction came before the original event - create placeholder
                            // This will be updated when the original event arrives
                            android.util.Log.d("Andromuks", "AppViewModel: Redaction event ${event.eventId} received before original $redactsEventId")
                        }
                    }
                }
                
                // Handle edit events (m.replace) - O(1) storage
                isEditEvent(event) -> {
                    val relatesTo = when {
                        event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")
                        event.type == "m.room.encrypted" -> event.decrypted?.optJSONObject("m.relates_to")
                        else -> null
                    }
                    
                    val originalEventId = relatesTo?.optString("event_id")?.takeIf { it.isNotBlank() }
                    
                    if (originalEventId != null) {
                        // Store reverse mapping for quick lookup
                        editToOriginal[event.eventId] = originalEventId
                        
                        val versioned = messageVersions[originalEventId]
                        if (versioned != null) {
                            // Add this edit to the version list
                            val newVersion = MessageVersion(
                                eventId = event.eventId,
                                event = event,
                                timestamp = event.timestamp,
                                isOriginal = false
                            )
                            
                            // Merge and sort versions (newest first)
                            val updatedVersions = (versioned.versions + newVersion)
                                .sortedByDescending { it.timestamp }
                            
                            // MEMORY MANAGEMENT: Limit versions per message to prevent memory leaks
                            val limitedVersions = if (updatedVersions.size > MAX_MESSAGE_VERSIONS_PER_EVENT) {
                                updatedVersions.take(MAX_MESSAGE_VERSIONS_PER_EVENT)
                            } else {
                                updatedVersions
                            }
                            
                            messageVersions[originalEventId] = versioned.copy(
                                versions = limitedVersions
                            )
                            
                            android.util.Log.d("Andromuks", "AppViewModel: Added edit ${event.eventId} to original $originalEventId (total versions: ${updatedVersions.size})")
                        } else {
                            // Edit came before original - create placeholder with just the edit
                            messageVersions[originalEventId] = VersionedMessage(
                                originalEventId = originalEventId,
                                originalEvent = event,  // Temporary, will be replaced when original arrives
                                versions = listOf(MessageVersion(
                                    eventId = event.eventId,
                                    event = event,
                                    timestamp = event.timestamp,
                                    isOriginal = false
                                ))
                            )
                            android.util.Log.d("Andromuks", "AppViewModel: Edit ${event.eventId} received before original $originalEventId - created placeholder")
                        }
                    }
                }
                
                // Handle regular messages (original events) - O(1) storage
                event.type == "m.room.message" || event.type == "m.room.encrypted" -> {
                    val existing = messageVersions[event.eventId]
                    
                    if (existing != null) {
                        // We already have edit versions, now add/update the original
                        val originalVersion = MessageVersion(
                            eventId = event.eventId,
                            event = event,
                            timestamp = event.timestamp,
                            isOriginal = true
                        )
                        
                        // Merge with existing edits and sort
                        val updatedVersions = (existing.versions.filter { !it.isOriginal } + originalVersion)
                            .sortedByDescending { it.timestamp }
                        
                        messageVersions[event.eventId] = existing.copy(
                            originalEvent = event,
                            versions = updatedVersions
                        )
                        
                        //android.util.Log.d("Andromuks", "AppViewModel: Updated original event ${event.eventId} with ${updatedVersions.size} total versions")
                    } else {
                        // First time seeing this message - create new versioned message
                        messageVersions[event.eventId] = VersionedMessage(
                            originalEventId = event.eventId,
                            originalEvent = event,
                            versions = listOf(MessageVersion(
                                eventId = event.eventId,
                                event = event,
                                timestamp = event.timestamp,
                                isOriginal = true
                            ))
                        )
                    }
                }
            }
        }
    }
    
    // PERFORMANCE: Track member processing state for incremental updates
    private var memberProcessingIndex = 0
    private val lastProcessedMembers = mutableSetOf<String>() // Track which rooms had member events
    
    /**
     * PERFORMANCE OPTIMIZATION: Incremental member cache processing
     * Only processes members for rooms that changed, and only every 3rd sync message
     * This prevents 100-300ms delays on every sync for large rooms
     */
    private fun populateMemberCacheFromSync(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val roomsJson = data.optJSONObject("rooms") ?: return
        
        // PERFORMANCE: Incremental processing - only process every 3rd sync message
        memberProcessingIndex++
        val shouldProcessMembers = memberProcessingIndex % 3 == 0
        
        if (!shouldProcessMembers) {
            val skipsUntilNext = 3 - (memberProcessingIndex % 3)
            android.util.Log.d("Andromuks", "AppViewModel: MEMBER PROCESSING - Skipping member cache update ($skipsUntilNext sync(s) until next processing)")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: MEMBER PROCESSING - Processing member cache update (sync #$memberProcessingIndex)")
        
        // Track current sync's room IDs with member events
        val currentSyncRooms = mutableSetOf<String>()
        
        val roomKeys = roomsJson.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomObj = roomsJson.optJSONObject(roomId) ?: continue
            val events = roomObj.optJSONArray("events") ?: continue
            
            var hasMemberEvents = false
            val memberMap = roomMemberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
            
            // Process all events to find member events
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventType = event.optString("type")
                
                if (eventType == "m.room.member") {
                    hasMemberEvents = true
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
                                
                                // Check if this is actually a new join (not just a profile change)
                                val isNewJoin = previousProfile == null
                                
                                // Check if this is a profile change (join -> join with different profile data)
                                val isProfileChange = prevMembership == "join" && membership == "join" && !isNewJoin &&
                                    (previousProfile?.displayName != displayName || previousProfile?.avatarUrl != avatarUrl)
                                
                                // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                                storeMemberProfile(roomId, userId, profile)
                                
                                if (isNewJoin) {
                                    android.util.Log.d("Andromuks", "AppViewModel: New member joined: $userId in room $roomId - triggering immediate UI update")
                                    // New joins are critical - trigger member update immediately
                                    memberUpdateCounter++
                                } else if (isProfileChange) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Profile change detected for $userId - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                                    // Trigger UI update for profile changes
                                    memberUpdateCounter++
                                }
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached joined member '$userId' in room '$roomId' -> displayName: '$displayName'")
                            }
                            "invite" -> {
                                // Store invited members in global cache only (for profile lookups) but not in room member cache
                                // This prevents them from appearing in mention lists but allows profile display if they send messages
                                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                                
                                val profile = MemberProfile(displayName, avatarUrl)
                                globalProfileCache[userId] = CachedProfileEntry(profile, System.currentTimeMillis())
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached invited member '$userId' profile in global cache only -> displayName: '$displayName'")
                            }
                            "leave", "ban" -> {
                                // Remove members who left or were banned from room cache only
                                memberMap.remove(userId)
                                val flattenedKey = "$roomId:$userId"
                                flattenedMemberCache.remove(flattenedKey)
                                
                                // OPTIMIZED: Remove from indexed cache
                                roomMemberIndex[roomId]?.remove(userId)
                                
                                // Trigger member update for leaves (critical change)
                                memberUpdateCounter++
                                android.util.Log.d("Andromuks", "AppViewModel: Member left/banned: $userId in room $roomId - triggering immediate UI update")
                                // Note: Don't remove from global cache as they might be in other rooms
                                // Note: Keep disk cache for potential future re-joining
                            }
                        }
                    }
                }
            }
            
            // Track rooms with member events in this sync
            if (hasMemberEvents) {
                currentSyncRooms.add(roomId)
            }
        }
        
        // Update tracking set for next sync comparison
        lastProcessedMembers.clear()
        lastProcessedMembers.addAll(currentSyncRooms)
        
        android.util.Log.d("Andromuks", "AppViewModel: MEMBER PROCESSING - Updated ${currentSyncRooms.size} rooms with member changes")
    }
    
    /**
     * Process account_data from sync_complete or database
     * Can be called with either a sync JSON object or a direct account_data JSON string
     */
    private fun processAccountData(accountDataJson: JSONObject) {
        // Account data is already extracted, process it directly
        
        // Process recent emoji account data
        val recentEmojiData = accountDataJson.optJSONObject("io.element.recent_emoji")
        if (recentEmojiData != null) {
            val content = recentEmojiData.optJSONObject("content")
            val recentEmojiArray = content?.optJSONArray("recent_emoji")
            
            if (recentEmojiArray != null) {
                val frequencies = mutableListOf<Pair<String, Int>>()
                for (i in 0 until recentEmojiArray.length()) {
                    val emojiEntry = recentEmojiArray.optJSONArray(i)
                    if (emojiEntry != null && emojiEntry.length() >= 1) {
                        val emoji = emojiEntry.optString(0)
                        if (emoji.isNotBlank()) {
                            // Get count from entry, default to 1 if not present
                            val count = if (emojiEntry.length() >= 2) {
                                emojiEntry.optInt(1, 1)
                            } else {
                                1
                            }
                            frequencies.add(Pair(emoji, count))
                        }
                    }
                }
                // Sort by frequency (descending) to ensure proper order
                val sortedFrequencies = frequencies.sortedByDescending { it.second }
                // Store frequencies and update UI list
                recentEmojiFrequencies = sortedFrequencies.toMutableList()
                recentEmojis = sortedFrequencies.map { it.first }
                android.util.Log.d("Andromuks", "AppViewModel: Loaded ${sortedFrequencies.size} recent emojis from account_data with frequencies (sorted by count)")
            }
        }
        
        // Process m.direct account data for DM room detection
        val mDirectData = accountDataJson.optJSONObject("m.direct")
        if (mDirectData != null) {
            val content = mDirectData.optJSONObject("content")
            if (content != null) {
                val dmRoomIds = mutableSetOf<String>()
                val dmUserMap = mutableMapOf<String, MutableSet<String>>()
                
                // Extract all room IDs from m.direct content
                val keys = content.names()
                if (keys != null) {
                    for (i in 0 until keys.length()) {
                        val userId = keys.optString(i)
                        val roomIdsArray = content.optJSONArray(userId)
                        if (roomIdsArray != null) {
                            val roomsForUser = dmUserMap.getOrPut(userId) { mutableSetOf() }
                            for (j in 0 until roomIdsArray.length()) {
                                val roomId = roomIdsArray.optString(j)
                                if (roomId.isNotBlank()) {
                                    dmRoomIds.add(roomId)
                                    roomsForUser.add(roomId)
                                }
                            }
                        }
                    }
                }
                
                // Update the DM room IDs cache
                directMessageRoomIds = dmRoomIds
                directMessageUserMap = dmUserMap.mapValues { it.value.toSet() }
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Loaded ${dmRoomIds.size} DM room IDs for ${dmUserMap.size} users from m.direct account data"
                )
                
                // PERFORMANCE: Update existing rooms in roomMap with correct DM status from account_data
                // This ensures rooms loaded from database have correct isDirectMessage flag
                updateRoomsDirectMessageStatus(dmRoomIds)
            }
        }
    }
    
    /**
     * Updates the isDirectMessage flag for all rooms in roomMap based on m.direct account data
     * This ensures the Direct tab is correctly populated even when rooms are loaded from database
     */
    private fun updateRoomsDirectMessageStatus(dmRoomIds: Set<String>) {
        var updatedCount = 0
        
        // Update each room's isDirectMessage flag based on m.direct account data
        // Update the map in place (roomMap is a val but points to a mutable map)
        for ((roomId, room) in roomMap) {
            val shouldBeDirect = dmRoomIds.contains(roomId)
            if (room.isDirectMessage != shouldBeDirect) {
                // Update room with correct DM status
                roomMap[roomId] = room.copy(isDirectMessage = shouldBeDirect)
                updatedCount++
            }
        }
        
        if (updatedCount > 0) {
            // Update allRooms to reflect the changes
            allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
            // Invalidate cache to force refresh of filtered sections
            invalidateRoomSectionCache()
            android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedCount rooms with correct DM status from m.direct account data")
        }
    }

    // SYNC OPTIMIZATION: Helper functions for diff-based and batched updates
    
    /**
     * Generate a hash for room state to detect actual changes
     */
    private fun generateRoomStateHash(rooms: List<RoomItem>): String {
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
    private fun generateMemberStateHash(): String {
        return flattenedMemberCache.entries.take(100).joinToString("|") { "${it.key}:${it.value.displayName}:${it.value.avatarUrl}" }
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
    private fun scheduleUIUpdate(updateType: String) {
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
    private fun scheduleRoomReorder(forceImmediate: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReorder = currentTime - lastRoomReorderTime
        
        // Cancel existing reorder job
        roomReorderJob?.cancel()
        
        if (forceImmediate || timeSinceLastReorder >= ROOM_REORDER_DEBOUNCE_MS) {
            // Force immediate sort or enough time has passed, reorder immediately
            performRoomReorder()
        } else {
            // Schedule reorder for later
            val delayMs = ROOM_REORDER_DEBOUNCE_MS - timeSinceLastReorder
            roomReorderJob = viewModelScope.launch {
                delay(delayMs)
                performRoomReorder()
            }
        }
    }
    
    /**
     * Force immediate room list re-sorting
     * Called when switching tabs or returning to RoomListScreen
     */
    fun forceRoomListSort() {
        android.util.Log.d("Andromuks", "AppViewModel: Force sorting room list (tab change or screen return)")
        scheduleRoomReorder(forceImmediate = true)
    }
    
    /**
     * Actually perform the room reordering
     */
    private fun performRoomReorder() {
        lastRoomReorderTime = System.currentTimeMillis()
        
        // Separate newly joined rooms from existing rooms
        val newlyJoinedRooms = roomMap.values.filter { newlyJoinedRoomIds.contains(it.id) }
        val existingRooms = roomMap.values.filter { !newlyJoinedRoomIds.contains(it.id) }
        
        // Sort existing rooms by timestamp (newest first)
        val sortedExistingRooms = existingRooms.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        // Sort newly joined rooms by timestamp (newest first) - these go at the top
        val sortedNewlyJoinedRooms = newlyJoinedRooms.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        // Combine: newly joined rooms first, then existing rooms
        val sortedRooms = sortedNewlyJoinedRooms + sortedExistingRooms
        
        // Clear newly joined set after sorting (rooms are no longer "new" after first sort)
        if (newlyJoinedRoomIds.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: Sorting ${newlyJoinedRoomIds.size} newly joined rooms to the top")
            newlyJoinedRoomIds.clear()
        }
        
        // Update UI with new room order
        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms)), skipCounterUpdate = true)
        allRooms = sortedRooms
        invalidateRoomSectionCache()
        
        // Trigger UI update
        needsRoomListUpdate = true
        scheduleUIUpdate("roomList")
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PERFORMANCE - Debounced room reorder completed (${sortedRooms.size} rooms)")
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
        // NAVIGATION PERFORMANCE: Update scroll position for prefetch tracking
        lastRoomListScrollPosition = scrollPosition
        
        // Only prefetch if we haven't done it recently (avoid excessive requests)
        val currentTime = System.currentTimeMillis()
        val prefetchThreshold = 30 * 1000L // 30 seconds
        
        visibleRoomIds.forEach { roomId ->
            val navigationState = navigationCache[roomId]
            val shouldPrefetch = when {
                navigationState == null -> true // Never prefetched
                currentTime - navigationState.lastPrefetchTime > prefetchThreshold -> true // Stale data
                !navigationState.essentialDataLoaded -> true // Essential data missing
                else -> false
            }
            
            if (shouldPrefetch && !prefetchedRooms.contains(roomId)) {
                prefetchEssentialRoomData(roomId)
            }
        }
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
    private fun loadRoomDetails(roomId: String, navigationState: RoomNavigationState) {
        // OPPORTUNISTIC PROFILE LOADING: Skip bulk member loading to prevent OOM
        // Profiles will be loaded on-demand when actually needed for rendering
        if (navigationState.essentialDataLoaded && !navigationState.memberDataLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: SKIPPING bulk member loading for $roomId (using opportunistic loading)")
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
     * PERFORMANCE OPTIMIZATION: Async version that processes JSON on background thread
     * This prevents UI blocking during sync parsing (200-500ms improvement)
     */
    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
        // Update last sync timestamp immediately (this is lightweight)
        lastSyncTimestamp = System.currentTimeMillis()
        
        // Update service with new sync timestamp
        WebSocketService.updateLastSyncTimestamp()
        
        // Extract request_id from sync_complete (used as last_received_id)
        val requestId = syncJson.optInt("request_id", 0)
        
        if (requestId != 0) {
            if (requestId < 0) {
                synchronized(pendingSyncLock) {
                    val currentPending = pendingLastReceivedSyncId
                    if (currentPending == null || requestId < currentPending) {
                        pendingLastReceivedSyncId = requestId
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Recorded pending sync_complete requestId=$requestId (previous pending=$currentPending)"
                        )
                    } else {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Pending sync_complete requestId=$requestId ignored (current pending=$currentPending)"
                        )
                    }
                }
            }
        }
        
        // Persist sync_complete to database (run in background)
        appContext?.let { context ->
            if (syncIngestor == null) {
                syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context)
            }

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
                    return@launch
                }

                try {
                    syncIngestor?.ingestSyncComplete(jsonForPersistence, requestId, currentRunId)
                    
                    if (requestId < 0) {
                        withContext(Dispatchers.Main) {
                            onSyncCompletePersisted(requestId)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error persisting sync_complete: ${e.message}", e)
                    // Don't block UI updates if persistence fails
                }
            }
        } ?: run {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Skipping sync persistence because appContext is null"
            )
        }
        
        // PERFORMANCE: Move heavy JSON parsing to background thread
        viewModelScope.launch(Dispatchers.Default) {
            // Parse sync data on background thread (200-500ms for large accounts)
            val syncResult = SpaceRoomParser.parseSyncUpdate(syncJson, roomMemberCache, this@AppViewModel)
            
            // Switch back to main thread for UI updates only
            withContext(Dispatchers.Main) {
                processParsedSyncResult(syncResult, syncJson)
            }
        }
    }
    
    /**
     * Legacy synchronous version - kept for backward compatibility
     * DEPRECATED: Use updateRoomsFromSyncJsonAsync instead for better performance
     */
    fun updateRoomsFromSyncJson(syncJson: JSONObject) {
        // Update last sync timestamp for notification display
        lastSyncTimestamp = System.currentTimeMillis()
        
        // Update service with new sync timestamp
        WebSocketService.updateLastSyncTimestamp()
        
        // First, populate member cache from sync data and check for changes
        val oldMemberStateHash = generateMemberStateHash()
        populateMemberCacheFromSync(syncJson)
        val newMemberStateHash = generateMemberStateHash()
        val memberStateChanged = newMemberStateHash != oldMemberStateHash
        
        // Process account_data for recent emojis and m.direct
        val data = syncJson.optJSONObject("data")
        val accountData = data?.optJSONObject("account_data")
        if (accountData != null) {
            processAccountData(accountData)
        }
        
        // Auto-save state periodically (every 10 sync_complete messages) for crash recovery
        if (syncMessageCount > 0 && syncMessageCount % 10 == 0) {
            appContext?.let { context ->
                saveStateToStorage(context)
            }
        }
        
        val syncResult = SpaceRoomParser.parseSyncUpdate(syncJson, roomMemberCache, this)
        syncMessageCount++
        
        // Process the sync result
        processParsedSyncResult(syncResult, syncJson)
    }
    /**
     * Process parsed sync result and update UI
     * Called on main thread after background parsing completes
     */
    private fun processParsedSyncResult(syncResult: SyncUpdateResult, syncJson: JSONObject) {
        // CRITICAL: Increment sync message count FIRST to prevent duplicate processing
        syncMessageCount++
        
        // Populate member cache from sync data and check for changes
        val oldMemberStateHash = generateMemberStateHash()
        populateMemberCacheFromSync(syncJson)
        val newMemberStateHash = generateMemberStateHash()
        val memberStateChanged = newMemberStateHash != oldMemberStateHash
        
        // Process account_data for recent emojis and m.direct
        val data = syncJson.optJSONObject("data")
        val accountData = data?.optJSONObject("account_data")
        if (accountData != null) {
            processAccountData(accountData)
        }
        
        // Auto-save state periodically (every 10 sync_complete messages) for crash recovery
        if (syncMessageCount > 0 && syncMessageCount % 10 == 0) {
            appContext?.let { context ->
                saveStateToStorage(context)
            }
        }
        
        // Update existing rooms
        syncResult.updatedRooms.forEach { room ->
            val existingRoom = roomMap[room.id]
            if (existingRoom != null) {
                // Preserve existing message preview and sender if new room data doesn't have one
                // CRITICAL: Also preserve isFavourite and isLowPriority flags if sync doesn't include account_data.m.tag
                // This prevents favorite rooms from disappearing from the Favs tab
                val updatedRoom = room.copy(
                    messagePreview = if (room.messagePreview.isNullOrBlank() && !existingRoom.messagePreview.isNullOrBlank()) {
                        existingRoom.messagePreview
                    } else {
                        room.messagePreview
                    },
                    messageSender = if (room.messageSender.isNullOrBlank() && !existingRoom.messageSender.isNullOrBlank()) {
                        existingRoom.messageSender
                    } else {
                        room.messageSender
                    },
                    // Preserve favorite and low priority flags if sync doesn't explicitly update them
                    // SpaceRoomParser only sets these to true if account_data.m.tag is present
                    // If sync doesn't include account_data, we preserve the existing values
                    isFavourite = room.isFavourite || existingRoom.isFavourite, // Keep true if either is true
                    isLowPriority = room.isLowPriority || existingRoom.isLowPriority, // Keep true if either is true
                    isDirectMessage = room.isDirectMessage || existingRoom.isDirectMessage // Preserve DM status
                )
                // Log if favorite status was preserved (for debugging)
                if (existingRoom.isFavourite && !room.isFavourite && updatedRoom.isFavourite) {
                    android.util.Log.d("Andromuks", "AppViewModel: Preserved isFavourite=true for room ${room.id} (sync didn't include account_data.m.tag)")
                }
                roomMap[room.id] = updatedRoom
                // PHASE 1: Update Repository in parallel with AppViewModel
                RoomRepository.updateRoom(updatedRoom)
                // Update animation state only if app is visible (battery optimization)
                if (isAppVisible) {
                    updateRoomAnimationState(room.id, isAnimating = true)
                }
            } else {
                roomMap[room.id] = room
                // PHASE 1: Update Repository in parallel with AppViewModel
                RoomRepository.updateRoom(room)
                // Update animation state only if app is visible (battery optimization)
                if (isAppVisible) {
                    updateRoomAnimationState(room.id, isAnimating = true)
                }
                android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (unread: ${room.unreadCount})")
            }
        }
        
        // Add new rooms
        syncResult.newRooms.forEach { room ->
            roomMap[room.id] = room
            // PHASE 1: Update Repository in parallel with AppViewModel
            RoomRepository.updateRoom(room)
            // Mark as newly joined - will be sorted to the top
            newlyJoinedRoomIds.add(room.id)
            android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (marked as newly joined)")
            
            // Update animation state only if app is visible (battery optimization)
            if (isAppVisible) {
                updateRoomAnimationState(room.id, isAnimating = true)
            }
            
            // Bridge cache invalidation: New rooms need to be checked for bridge state
            // Remove from checked rooms set so it gets checked on next bridge load
            bridgeCacheCheckedRooms = bridgeCacheCheckedRooms - room.id
        }
        
        // CRITICAL: If we have newly joined rooms, force immediate sort to show them at the top
        if (syncResult.newRooms.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: New rooms detected - forcing immediate sort to show them at the top")
            scheduleRoomReorder(forceImmediate = true)
        }
        
        // Remove left rooms
        var roomsWereRemoved = false
        val removedRoomIdsSet = syncResult.removedRoomIds.toSet()
        syncResult.removedRoomIds.forEach { roomId ->
            val removedRoom = roomMap.remove(roomId)
            if (removedRoom != null) {
                roomsWereRemoved = true
                // Remove from newly joined set if it was there
                newlyJoinedRoomIds.remove(roomId)
                
                // Remove animation state only if app is visible (battery optimization)
                if (isAppVisible) {
                    roomAnimationStates = roomAnimationStates - roomId
                }
                
                // Bridge cache invalidation: Remove bridge info and checked status for removed rooms
                bridgeInfoCache = bridgeInfoCache - roomId
                bridgeCacheCheckedRooms = bridgeCacheCheckedRooms - roomId
                android.util.Log.d("Andromuks", "AppViewModel: Removed room: ${removedRoom.name}")
            }
        }
        
        // CRITICAL: If rooms were removed, immediately filter them out from allRooms and update UI
        if (roomsWereRemoved) {
            android.util.Log.d("Andromuks", "AppViewModel: Rooms removed - immediately filtering from allRooms and updating UI")
            // Immediately filter out removed rooms from allRooms
            val filteredRooms = allRooms.filter { it.id !in removedRoomIdsSet }
            allRooms = filteredRooms
            invalidateRoomSectionCache()
            
            // PHASE 1: Update Repository in parallel with AppViewModel
            RoomRepository.updateRooms(roomMap)
            
            // Also update spaces list
            setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = filteredRooms)), skipCounterUpdate = true)
            
            // Trigger immediate UI update (bypass debounce)
            needsRoomListUpdate = true
            roomListUpdateCounter++
            android.util.Log.d("Andromuks", "AppViewModel: Immediately updated UI after room removal (roomListUpdateCounter: $roomListUpdateCounter)")
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${roomMap.size} (updated: ${syncResult.updatedRooms.size}, new: ${syncResult.newRooms.size}, removed: ${syncResult.removedRoomIds.size}) - sync message #$syncMessageCount [App visible: $isAppVisible]")
        
        // DETECT INVITES ACCEPTED ON OTHER DEVICES: Remove pending invites for rooms already joined
        if (pendingInvites.isNotEmpty()) {
            val acceptedInvites = pendingInvites.keys.filter { roomMap.containsKey(it) }
            if (acceptedInvites.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Detected ${acceptedInvites.size} invites already joined via sync - removing pending invites")
                
                acceptedInvites.forEach { roomId ->
                    pendingInvites.remove(roomId)
                }
                
                // Remove from database asynchronously
                appContext?.let { context ->
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
                            acceptedInvites.forEach { roomId ->
                                database.inviteDao().deleteInvite(roomId)
                            }
                            android.util.Log.d("Andromuks", "AppViewModel: Removed ${acceptedInvites.size} invites from database (accepted elsewhere)")
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "AppViewModel: Error removing accepted invites from database", e)
                        }
                    }
                }
                
                // Trigger UI update to remove invites from RoomListScreen
                needsRoomListUpdate = true
                roomListUpdateCounter++
                android.util.Log.d("Andromuks", "AppViewModel: Room list updated after removing accepted invites (roomListUpdateCounter: $roomListUpdateCounter)")
            }
        }
        
        // Process room invitations first
        processRoomInvites(syncJson)
        
        // Always cache timeline events (lightweight, needed for instant room opening)
        cacheTimelineEventsFromSync(syncJson)
        
        // SYNC OPTIMIZATION: Update room data (last message, unread count) without immediate sorting
        // This prevents visual jumping while still showing real-time updates
        val allRoomsUnsorted = roomMap.values.toList()
        
        // Update low priority rooms set for notification filtering (always needed)
        updateLowPriorityRooms(allRoomsUnsorted)
        
        // Diff-based update: Only update UI if room state actually changed
        val newRoomStateHash = generateRoomStateHash(allRoomsUnsorted)
        val roomStateChanged = newRoomStateHash != lastRoomStateHash
        
        // BATTERY OPTIMIZATION: Skip expensive UI updates when app is in background
        if (isAppVisible) {
            // Trigger timestamp update on sync (only for visible UI)
            triggerTimestampUpdate()
            
            // SYNC OPTIMIZATION: Selective updates - only update what actually changed
            if (roomStateChanged) {
                android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room state changed, updating data without sorting")
                
                // PERFORMANCE: Update room data in current order (preserves visual stability)
                // If allRooms is empty or this is first sync, initialize with sorted list
                if (allRooms.isEmpty()) {
                    // First sync - initialize with sorted list
                    val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                    allRooms = sortedRooms
                    android.util.Log.d("Andromuks", "AppViewModel: Initializing allRooms with ${sortedRooms.size} sorted rooms")
                    
                    // PHASE 1: Update Repository in parallel with AppViewModel
                    RoomRepository.updateRooms(roomMap)
                } else {
                    // Update existing rooms in current order, add new rooms at end
                    // PERFORMANCE: Only create new RoomItem instances when data actually changes
                    val existingRoomIds = allRooms.map { it.id }.toSet()
                    var hasChanges = false
                    val updatedExistingRooms = allRooms.mapIndexed { index, existingRoom ->
                        val updatedRoom = roomMap[existingRoom.id] ?: existingRoom
                        // Only create new instance if data actually changed (data class equality check)
                        if (updatedRoom != existingRoom) {
                            hasChanges = true
                            updatedRoom
                        } else {
                            existingRoom // Keep existing instance to avoid recomposition
                        }
                    }
                    
                    // Add any new rooms that appeared in roomMap (at the end, will be sorted later)
                    val newRooms = roomMap.values.filter { it.id !in existingRoomIds }
                    
                    // Only update if there are actual changes (new rooms or updated rooms)
                    if (newRooms.isNotEmpty() || hasChanges) {
                        // Combine existing (in current order) with new rooms (will be sorted on next reorder)
                        allRooms = updatedExistingRooms + newRooms
                        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                        
                        // PHASE 1: Update Repository in parallel with AppViewModel
                        RoomRepository.updateRooms(roomMap)
                        
                        // Mark for batched UI update (for badges/timestamps - no sorting)
                        needsRoomListUpdate = true
                        scheduleUIUpdate("roomList")
                    } else {
                        // No changes - skip cache invalidation and UI updates to avoid recomposition
                        android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room data unchanged, skipping updates")
                    }
                }
                
                // PERFORMANCE: Use debounced room reordering (30 seconds) to prevent "room jumping"
                // This allows real-time badge/timestamp updates while only re-sorting periodically
                scheduleRoomReorder()
                
                lastRoomStateHash = newRoomStateHash
                android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room data updated (no sorting), debounced sort scheduled")
                
                // OPTIMIZED: Update conversation shortcuts in background (non-UI operation)
                viewModelScope.launch(Dispatchers.Default) {
                    // Use sorted rooms for shortcuts (they need to be sorted)
                    val sortedRoomsForShortcuts = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                    conversationsApi?.updateConversationShortcuts(sortedRoomsForShortcuts)
                    personsApi?.updatePersons(buildDirectPersonTargets(sortedRoomsForShortcuts))
                }
            } else {
                // Room state hash unchanged - check if individual rooms need timestamp updates
                // PERFORMANCE: Only update rooms that actually changed to avoid unnecessary recomposition
                if (allRooms.isNotEmpty()) {
                    var needsUpdate = false
                    val updatedRooms = allRooms.map { existingRoom ->
                        val updatedRoom = roomMap[existingRoom.id] ?: existingRoom
                        // Only create new instance if data actually changed (data class equality check)
                        if (updatedRoom != existingRoom) {
                            needsUpdate = true
                            updatedRoom
                        } else {
                            existingRoom // Keep existing instance to avoid recomposition
                        }
                    }
                    
                    // Only update if something actually changed
                    if (needsUpdate) {
                        allRooms = updatedRooms
                        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                        
                        // Trigger UI update for timestamp changes only
                        needsRoomListUpdate = true
                        scheduleUIUpdate("roomList")
                        android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Some room timestamps changed, updating UI")
                    } else {
                        // No changes at all - skip all updates to avoid unnecessary recomposition
                        android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - No room changes detected, skipping all updates")
                    }
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room state unchanged, allRooms empty")
                }
            }
            
            // SYNC OPTIMIZATION: Check if current room needs timeline update with diff-based detection
            checkAndUpdateCurrentRoomTimelineOptimized(syncJson)
            
            // FAILSAFE: Ensure currently open room timeline matches cached data
            val openRoomId = currentRoomId
            if (openRoomId.isNotEmpty()) {
                val cachedEvents = RoomTimelineCache.getCachedEvents(openRoomId)
                    ?: RoomTimelineCache.getCachedEventsForNotification(openRoomId)
                if (cachedEvents != null && cachedEvents.isNotEmpty()) {
                    val cachedLatest = cachedEvents.maxByOrNull { it.timestamp }?.eventId
                    val timelineLatest = timelineEvents.maxByOrNull { it.timestamp }?.eventId
                    if (cachedLatest != null && cachedLatest != timelineLatest) {
                        android.util.Log.w("Andromuks", "AppViewModel: Timeline desync detected for $openRoomId (cached latest=$cachedLatest, timeline latest=$timelineLatest) - rebuilding from cache")
                        processCachedEvents(openRoomId, cachedEvents, openingFromNotification = false, skipNetworkRequests = true)
                        lastTimelineStateHash = generateTimelineStateHash(timelineEvents)
                        needsTimelineUpdate = true
                        scheduleUIUpdate("timeline")
                    }
                }
            }
            
            // SYNC OPTIMIZATION: Schedule member update if member cache actually changed
            if (memberStateChanged) {
                android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Member state changed, scheduling UI update")
                needsMemberUpdate = true
                scheduleUIUpdate("member")
                lastMemberStateHash = newMemberStateHash
            }
        } else {
            // App is in background - minimal processing for battery saving
            android.util.Log.d("Andromuks", "AppViewModel: BATTERY SAVE MODE - App in background, skipping UI updates")
            
            // Still update allRooms for data consistency (needed for notifications)
            // Sort rooms for background processing (needed for shortcuts and consistency)
            val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
            allRooms = sortedRooms
            invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
            
            // Update shortcuts less frequently in background (every 10 sync messages)
            if (syncMessageCount % 10 == 0) {
                android.util.Log.d("Andromuks", "AppViewModel: Background: Updating conversation shortcuts (throttled)")
                conversationsApi?.updateConversationShortcuts(sortedRooms)
                personsApi?.updatePersons(buildDirectPersonTargets(sortedRooms))
            }
        }
        
        // Set spacesLoaded after 3 sync messages, but don't trigger navigation yet
        // Navigation will be triggered by onInitComplete() after all initialization is done
        if (syncMessageCount >= 3 && !spacesLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: Setting spacesLoaded after $syncMessageCount sync messages")
            spacesLoaded = true
        }
    }
    
    fun onInitComplete() {
        android.util.Log.d("Andromuks", "AppViewModel: onInitComplete called - setting spacesLoaded = true")
        spacesLoaded = true
        
        // Now that all rooms are loaded, populate space edges
        populateSpaceEdges()
        
        // PERFORMANCE OPTIMIZATION: Defer bridge detection until Bridges tab is accessed
        // This prevents 500-1000ms blocking on init_complete with many rooms
        android.util.Log.d("Andromuks", "AppViewModel: Init complete - bridge detection deferred until Bridges tab is accessed")
        
        // Update ConversationsApi with the real homeserver URL and refresh shortcuts
        // This happens after init_complete when we have all the data we need
        if (realMatrixHomeserverUrl.isNotEmpty() && appContext != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Updating ConversationsApi with real homeserver URL after init_complete")
            // Create new ConversationsApi instance with real homeserver URL
            conversationsApi = ConversationsApi(appContext!!, homeserverUrl, authToken, realMatrixHomeserverUrl)
            personsApi = PersonsApi(appContext!!, homeserverUrl, authToken, realMatrixHomeserverUrl)
            // Refresh shortcuts with the new homeserver URL and populated rooms
            if (roomMap.isNotEmpty()) {
                conversationsApi?.updateConversationShortcuts(roomMap.values.toList())
                personsApi?.updatePersons(buildDirectPersonTargets(roomMap.values.toList()))
            }
        }
        
        // FCM registration with Gomuks Backend will be triggered via callback when token is ready
        // This ensures we don't try to register before the FCM token is available
        
        // Execute any pending notification actions now that websocket is ready
        executePendingNotificationActions()
        
        // Register FCM on every WebSocket connection to ensure backend has current token
        android.util.Log.d("Andromuks", "AppViewModel: onInitComplete - registering FCM to ensure backend has current token")
        registerFCMWithGomuksBackend()
        
        android.util.Log.d("Andromuks", "AppViewModel: Calling navigation callback (callback is ${if (onNavigateToRoomList != null) "set" else "null"})")
        
        // Only trigger navigation callback once to prevent double navigation
        if (!navigationCallbackTriggered) {
            if (onNavigateToRoomList != null) {
                navigationCallbackTriggered = true
                onNavigateToRoomList?.invoke()
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
                pendingNavigation = true
            }
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Navigation callback already triggered, skipping")
        }
    }
    
    /**
     * Executes any pending notification actions after init_complete
     */
    private fun executePendingNotificationActions() {
        if (pendingNotificationActions.isEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: No pending notification actions to execute")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Executing ${pendingNotificationActions.size} pending notification actions")
        
        val actionsToExecute = pendingNotificationActions.toList()
        pendingNotificationActions.clear()
        
        actionsToExecute.forEach { action ->
            when (action.type) {
                "send_message" -> {
                    if (action.text != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Executing pending send_message for room ${action.roomId}")
                        sendMessageFromNotification(action.roomId, action.text, action.onComplete)
                    }
                }
                "mark_read" -> {
                    if (action.eventId != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Executing pending mark_read for room ${action.roomId}")
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
        android.util.Log.d("Andromuks", "AppViewModel: Storing space edges for later processing")
        storedSpaceEdges = spaceEdges
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Lazy loads bridges on first access to Bridges tab
     * This prevents 500-1000ms blocking on app initialization with many rooms
     * 
     * Process:
     * 1. Check if bridges are already loaded (prevent duplicate requests)
     * 2. Request room states for bridge detection in background
     * 3. Process bridge info progressively as responses arrive
     */
    private fun loadBridgesIfNeeded() {
        // Only load if not already loaded
        if (allBridges.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: Bridges already loaded (${allBridges.size} bridges)")
            return
        }
        
        // First, try to create bridges from cached data
        createBridgePseudoSpaces()
        
        // BUG FIX #3: Load bridge checked rooms from database before requesting
        appContext?.let { context ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val bootstrapLoader = net.vrkknn.andromuks.database.BootstrapLoader(context)
                    val checkedRooms = bootstrapLoader.getBridgeCheckedRooms()
                    
                    // Mark all checked rooms from DB as checked in memory
                    withContext(Dispatchers.Main) {
                        bridgeCacheCheckedRooms = bridgeCacheCheckedRooms + checkedRooms
                        android.util.Log.d("Andromuks", "AppViewModel: Marked ${checkedRooms.size} rooms as bridge-checked from DB")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error loading bridge checked rooms from DB: ${e.message}", e)
                }
            }
        }
        
        // Only request room states for rooms that haven't been checked yet
        val uncheckedRooms = getUncheckedRoomsForBridge()
        if (uncheckedRooms.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: Lazy loading bridges - requesting room states for ${uncheckedRooms.size} unchecked rooms (${allRooms.size - uncheckedRooms.size} already cached)")
            
            // Request room states only for unchecked rooms
            uncheckedRooms.forEach { roomId ->
                requestRoomStateForBridgeDetection(roomId)
            }
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: All rooms already checked for bridges - using cached data only")
        }
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
    private fun populateSpaceEdges() {
        if (storedSpaceEdges == null) {
            android.util.Log.d("Andromuks", "AppViewModel: No stored space edges to populate")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Starting background space edge processing for ${allSpaces.size} spaces")
        
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
                
                android.util.Log.d("Andromuks", "AppViewModel: Background space edge processing - Created mock data for ${allRooms.size} rooms")
                
                // Process space edges in background (parsing is expensive)
                net.vrkknn.andromuks.utils.SpaceRoomParser.updateExistingSpacesWithEdges(
                    storedSpaceEdges!!, 
                    mockSyncData, 
                    this@AppViewModel
                )
                
                android.util.Log.d("Andromuks", "AppViewModel: Background space edge processing - Completed processing")
                
                // Switch to main thread for UI update
                withContext(Dispatchers.Main) {
                    // Clear stored space edges on main thread (atomic state change)
                    storedSpaceEdges = null
                    android.util.Log.d("Andromuks", "AppViewModel: Space edge processing complete - UI updated")
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
    private var pendingRoomNavigation: String? = null
    
    // Track if the pending navigation is from a notification (for optimized cache handling)
    private var isPendingNavigationFromNotification: Boolean = false
    
    // OPTIMIZATION #1: Direct room navigation (bypasses pending state)
    private var directRoomNavigation: String? = null
    
    // Pending bubble navigation from chat bubbles
    private var pendingBubbleNavigation: String? = null
    
    // Websocket restart callback
    var onRestartWebSocket: ((String) -> Unit)? = null
    
    // App lifecycle state
    var isAppVisible by mutableStateOf(true)
        private set
    
    // Delayed shutdown job for when app becomes invisible
    private var appInvisibleJob: Job? = null
    
    fun setNavigationCallback(callback: () -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: Navigation callback set")
        onNavigateToRoomList = callback
        
        // If we have a pending navigation, trigger it now
        if (pendingNavigation) {
            android.util.Log.d("Andromuks", "AppViewModel: Triggering pending navigation")
            pendingNavigation = false
            navigationCallbackTriggered = true
            callback()
        }
        // If spaces are already loaded (from cached state), DON'T trigger yet
        // Wait for WebSocket to connect and init_complete to trigger it
        else if (spacesLoaded && !navigationCallbackTriggered) {
            android.util.Log.d("Andromuks", "AppViewModel: Spaces already loaded from cache, but waiting for WebSocket connection before navigating")
            // Don't trigger here - let onInitComplete() or WebSocket connection handle it
        }
    }
    
    fun setPendingRoomNavigation(roomId: String, fromNotification: Boolean = false) {
        android.util.Log.d("Andromuks", "AppViewModel: Set pending room navigation to: $roomId (fromNotification: $fromNotification)")
        pendingRoomNavigation = roomId
        isPendingNavigationFromNotification = fromNotification
    }
    
    // OPTIMIZATION #1: Direct navigation method (bypasses pending state)
    fun setDirectRoomNavigation(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #1 - Set direct room navigation to: $roomId")
        directRoomNavigation = roomId
    }
    
    fun getDirectRoomNavigation(): String? {
        val roomId = directRoomNavigation
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #1 - Getting direct room navigation: $roomId")
        }
        return roomId
    }
    
    fun clearDirectRoomNavigation() {
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #1 - Clearing direct room navigation")
        directRoomNavigation = null
    }
    
    fun getPendingRoomNavigation(): String? {
        val roomId = pendingRoomNavigation
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Getting pending room navigation: $roomId")
        }
        return roomId
    }
    
    fun clearPendingRoomNavigation() {
        android.util.Log.d("Andromuks", "AppViewModel: Clearing pending room navigation")
        pendingRoomNavigation = null
        isPendingNavigationFromNotification = false
    }
    
    fun setPendingBubbleNavigation(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Set pending bubble navigation to: $roomId")
        pendingBubbleNavigation = roomId
    }
    
    fun getPendingBubbleNavigation(): String? {
        val roomId = pendingBubbleNavigation
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Getting pending bubble navigation: $roomId")
        }
        return roomId
    }
    
    fun clearPendingBubbleNavigation() {
        android.util.Log.d("Andromuks", "AppViewModel: Clearing pending bubble navigation")
        pendingBubbleNavigation = null
    }
    
    /**
     * Called when app becomes visible (foreground)
     */
    fun onAppBecameVisible() {
        android.util.Log.d("Andromuks", "AppViewModel: App became visible")
        isAppVisible = true
        updateAppVisibilityInPrefs(true)
        
        // Notify service of app visibility change
        WebSocketService.setAppVisibility(true)

        if (currentRoomId.isEmpty()) {
            val roomToRestore = pendingRoomToRestore
            if (!roomToRestore.isNullOrEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Restoring current room to $roomToRestore after visibility change")
                updateCurrentRoomIdInPrefs(roomToRestore)
            }
        }
        pendingRoomToRestore = null
        
        // Cancel any pending shutdown
        appInvisibleJob?.cancel()
        appInvisibleJob = null
        
        // Refresh UI with current state (in case updates happened while app was invisible)
        refreshUIState()
        
        // If a room is currently open, trigger timeline refresh to show new events from cache
        if (currentRoomId.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: Room is open ($currentRoomId), triggering timeline refresh")
            timelineRefreshTrigger++
        }
        
        // WebSocket service maintains connection
        android.util.Log.d("Andromuks", "AppViewModel: App visible, refreshing UI with current state")
    }
    
    /**
     * Lightweight version for chat bubbles - sets visibility without expensive UI refresh
     * Bubbles don't need to update shortcuts or refresh the room list
     */
    fun setBubbleVisible(visible: Boolean) {
        android.util.Log.d("Andromuks", "AppViewModel: Bubble visibility set to $visible (lightweight)")
        
        if (visible) {
            if (currentRoomId.isEmpty()) {
                val roomToRestore = pendingRoomToRestore
                if (!roomToRestore.isNullOrEmpty()) {
                    android.util.Log.d("Andromuks", "AppViewModel: Restoring bubble room to $roomToRestore after becoming visible")
                    updateCurrentRoomIdInPrefs(roomToRestore)
                }
            }
            pendingRoomToRestore = null
            // Cancel any pending shutdown
            appInvisibleJob?.cancel()
            appInvisibleJob = null
            
            // If a room is currently open, trigger timeline refresh to show new events from cache
            if (currentRoomId.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Room is open ($currentRoomId), triggering timeline refresh for bubble")
                timelineRefreshTrigger++
            }
        } else {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Bubble hidden - notifications remain enabled for $currentRoomId"
            )
        }
        // Don't call refreshUIState() - bubbles don't need room list updates or shortcut updates
    }
    
    fun attachToExistingWebSocketIfAvailable() {
        val existingWebSocket = WebSocketService.getWebSocket()
        if (existingWebSocket != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Attaching $viewModelId to existing WebSocket")
            webSocket = existingWebSocket
            WebSocketService.registerReceiveCallback(viewModelId, this)
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: No existing WebSocket to attach for $viewModelId")
        }
    }
    
    /**
     * Refreshes UI state when app becomes visible
     * This updates the UI with any changes that happened while app was in background
     */
    private fun refreshUIState() {
        val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        android.util.Log.d("Andromuks", "AppViewModel: Refreshing UI with ${sortedRooms.size} rooms")
        
        // Update animation states
        sortedRooms.forEachIndexed { index, room ->
            updateRoomAnimationState(room.id, isAnimating = false, newPosition = index)
        }
        
        // PERFORMANCE: Use debounced reordering for UI refresh too
        scheduleRoomReorder()
        allRooms = sortedRooms
        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
        
        // Update conversation shortcuts
        conversationsApi?.updateConversationShortcuts(sortedRooms)
        personsApi?.updatePersons(buildDirectPersonTargets(sortedRooms))
        
        android.util.Log.d("Andromuks", "AppViewModel: UI refreshed, roomListUpdateCounter: $roomListUpdateCounter")
    }
    
    /**
     * Lightweight refresh of the UI from cached data without restarting WebSocket
     * This should be used when app comes to foreground to update the room list from
     * any cached sync events received while the app was in background
     */
    fun refreshUIFromCache() {
        android.util.Log.d("Andromuks", "AppViewModel: Refreshing UI from cached data")
        refreshUIState()
    }
    
    /**
     * Lightweight timeline refresh that triggers UI update from cached timeline data
     * This should be used when app comes to foreground to refresh the timeline view
     * without making new network requests
     */
    fun refreshTimelineUI() {
        android.util.Log.d("Andromuks", "AppViewModel: Refreshing timeline UI from cached data")
        timelineRefreshTrigger++
    }
    
    /**
     * Called when app becomes invisible (background/standby)
     */
    fun onAppBecameInvisible() {
        android.util.Log.d("Andromuks", "AppViewModel: App became invisible")
        isAppVisible = false
        updateAppVisibilityInPrefs(false)
        
        // Notify service of app visibility change
        WebSocketService.setAppVisibility(false)
        if (currentRoomId.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: Clearing current room ($currentRoomId) while app invisible to allow notifications")
            clearCurrentRoomId(shouldRestoreOnVisible = true)
        }
        
        // Save state to storage for crash recovery (preserves run_id and last_received_sync_id)
        // This allows seamless resumption if app is killed by system
        appContext?.let { context ->
            saveStateToStorage(context)
        }
        
        // Cancel any existing shutdown job (no shutdown needed - service maintains connection)
        appInvisibleJob?.cancel()
        appInvisibleJob = null
        
        // WebSocket service maintains connection in background
        android.util.Log.d("Andromuks", "AppViewModel: App invisible, WebSocket service continues maintaining connection")
    }
    
    /**
     * Manually triggers app suspension (for back button from room list).
     * 
     * This function is called when the user presses the back button from the room list screen.
     * With the foreground service, we just save state but keep the WebSocket open.
     */
    fun suspendApp() {
        android.util.Log.d("Andromuks", "AppViewModel: App manually suspended, WebSocket service continues")
        onAppBecameInvisible() // This will save state for crash recovery
    }
    
    /**
     * Shuts down the WebSocket connection
     * Note: With foreground service, this is only called on app cleanup (onCleared)
     */
    private fun shutdownWebSocket() {
        android.util.Log.d("Andromuks", "AppViewModel: Shutting down WebSocket connection")
        clearWebSocket("App shutdown")
    }
    
    override fun onCleared() {
        super.onCleared()
        android.util.Log.d("Andromuks", "AppViewModel: onCleared - cleaning up resources for $viewModelId")
        
        // PHASE 4: Unregister this ViewModel from receiving WebSocket messages
        android.util.Log.d("Andromuks", "AppViewModel: Unregistering $viewModelId from WebSocket callbacks")
        WebSocketService.unregisterReceiveCallback(viewModelId)
        
        // Cancel any pending jobs
        appInvisibleJob?.cancel()
        appInvisibleJob = null
        
        if (instanceRole == InstanceRole.PRIMARY) {
            // Only the primary instance should perform global teardown
        WebSocketService.cancelReconnection()
        clearWebSocket("ViewModel cleared")
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping global WebSocket teardown for role=$instanceRole")
        }
    }

    fun getRoomById(roomId: String): RoomItem? {
        return roomMap[roomId]
    }
    
    // Room timeline state
    var currentRoomId by mutableStateOf("")
        private set
    private var pendingRoomToRestore: String? = null
    /**
     * Helper function to set currentRoomId and save it to SharedPreferences.
     * This allows notification services to check if a room is currently open.
     */
    private fun updateCurrentRoomIdInPrefs(roomId: String) {
        if (roomId.isNotEmpty()) {
            pendingRoomToRestore = null
        }
        currentRoomId = roomId
        // PHASE 1: Update Repository in parallel with AppViewModel
        RoomRepository.setCurrentRoom(roomId)

        val shouldPersistForNotifications = instanceRole != InstanceRole.BUBBLE
        if (!shouldPersistForNotifications) {
            android.util.Log.d(
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
                android.util.Log.d("Andromuks", "AppViewModel: Saving current open room ID to SharedPreferences: $roomId")
            } else {
                editor.remove("current_open_room_id")
                android.util.Log.d("Andromuks", "AppViewModel: Clearing current open room ID from SharedPreferences")
            }
            // Use commit() for synchronous write - critical to prevent race condition with notifications
            val success = editor.commit()
            android.util.Log.d("Andromuks", "AppViewModel: SharedPreferences commit ${if (success) "succeeded" else "failed"} for room ID: $roomId")
        }
    }
    
    private fun updateAppVisibilityInPrefs(visible: Boolean) {
        appContext?.applicationContext?.let { context ->
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = sharedPrefs.edit()
            editor.putBoolean("app_is_visible", visible)
            val success = editor.commit()
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: SharedPreferences commit ${if (success) "succeeded" else "failed"} for app visibility: $visible"
            )
        }
    }
    
    /**
     * Clears the current room ID when user navigates back to room list.
     * This allows notifications to resume for rooms that were previously open.
     */
    fun clearCurrentRoomId(shouldRestoreOnVisible: Boolean = false) {
        if (shouldRestoreOnVisible && currentRoomId.isNotEmpty()) {
            pendingRoomToRestore = currentRoomId
        } else if (!shouldRestoreOnVisible) {
            pendingRoomToRestore = null
        }
        updateCurrentRoomIdInPrefs("")
    }
    
    var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
        private set
    var isTimelineLoading by mutableStateOf(false)
        private set
    
    // Trigger for timeline refresh when app resumes (incremented when app becomes visible)
    var timelineRefreshTrigger by mutableStateOf(0)
        private set
    
    // Edit chain tracking system
    data class EventChainEntry(
        val eventId: String,
        var ourBubble: TimelineEvent?,
        var replacedBy: String?,
        val originalTimestamp: Long
    )
    
    private val eventChainMap = mutableMapOf<String, EventChainEntry>()
    private val editEventsMap = mutableMapOf<String, TimelineEvent>() // Store edit events separately
    private val roomsPendingDbRehydrate = Collections.synchronizedSet(mutableSetOf<String>())
    private val roomRehydrateJobs = Collections.synchronizedMap(mutableMapOf<String, Job>())
    private val roomsPaginatedOnce = Collections.synchronizedSet(mutableSetOf<String>())

    private fun hasInitialPaginate(roomId: String): Boolean = roomsPaginatedOnce.contains(roomId)

    private fun markInitialPaginate(roomId: String, reason: String) {
        val added = roomsPaginatedOnce.add(roomId)
        android.util.Log.d(
            "Andromuks",
            "AppViewModel: Recorded initial paginate for $roomId (reason=$reason, added=$added)"
        )
        setAutoPaginationEnabled(false, "paginate_lock_$roomId")
    }

    private fun logSkippedPaginate(roomId: String, reason: String) {
        android.util.Log.d(
            "Andromuks",
            "AppViewModel: Skipping paginate for $roomId ($reason) - already paginated once this session"
        )
    }
    private val roomSnapshotAwaiters = ConcurrentHashMap<String, MutableList<CompletableDeferred<Unit>>>()
    
    // Made public to allow access for RoomJoiner WebSocket operations
    var requestIdCounter = 1
        private set
    
    fun getAndIncrementRequestId(): Int {
        val id = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request ID: $id (counter now: $requestIdCounter)")
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
    
    private fun signalRoomSnapshotReady(roomId: String) {
        val awaiters = synchronized(roomSnapshotAwaiters) {
            roomSnapshotAwaiters.remove(roomId)?.toList()
        } ?: return
        
        for (awaiter in awaiters) {
            if (!awaiter.isCompleted && !awaiter.isCancelled) {
                awaiter.complete(Unit)
            }
        }
    }
    private val timelineRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val profileRequestRooms = mutableMapOf<Int, String>() // requestId -> roomId (for profile requests initiated from a specific room)
    private val roomStateRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val messageRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val bridgeStateRequests = mutableMapOf<Int, String>() // requestId -> roomId
    
    // PERFORMANCE: Track pending room state requests to prevent duplicate WebSocket commands
    private val pendingRoomStateRequests = mutableSetOf<String>() // roomId that have pending state requests
    private val reactionRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val markReadRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val readReceipts = mutableMapOf<String, MutableList<ReadReceipt>>() // eventId -> list of read receipts
    private val readReceiptsLock = Any() // Synchronization lock for readReceipts access
    private val roomsWithLoadedReceiptsFromDb = mutableSetOf<String>() // Track which rooms had receipts restored from DB
    private val roomsWithLoadedReactionsFromDb = mutableSetOf<String>() // Track which rooms had reactions restored from DB
    private val lastKnownDbLatestEventId = ConcurrentHashMap<String, String>()
    
    // Track receipt movements for animation - userId -> (previousEventId, currentEventId, timestamp)
    // THREAD SAFETY: Protected by readReceiptsLock since it's accessed from background threads
    private val receiptMovements = mutableMapOf<String, Triple<String?, String, Long>>()
    var receiptAnimationTrigger by mutableStateOf(0L)
        private set
    
        // Track new message animations - eventId -> timestamp when animation should complete
    // CRITICAL FIX: Use ConcurrentHashMap for thread-safe access (modified from background threads, read from UI thread)
    private val newMessageAnimations = ConcurrentHashMap<String, Long>()
    private val runningBubbleAnimations = ConcurrentHashMap.newKeySet<String>()
    var bubbleAnimationCompletionCounter by mutableStateOf(0L)
        private set
    var newMessageAnimationTrigger by mutableStateOf(0L)
        private set
    
    // CRITICAL: Track if animations should be enabled (disabled during initial room load)
    // Animations should only occur for new messages when room is already open
    private var animationsEnabledForRoom = mutableMapOf<String, Boolean>() // roomId -> enabled
    
    // CRITICAL: Track when each room was opened (in milliseconds, Matrix timestamp format)
    // Only messages with timestamp NEWER than this will animate
    // This ensures paginated (old) messages don't animate, only truly new messages do
    private var roomOpenTimestamps = mutableMapOf<String, Long>() // roomId -> openTimestamp
    private val pendingInvites = mutableMapOf<String, RoomInvite>() // roomId -> RoomInvite
    private val roomSummaryRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val joinRoomRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val leaveRoomRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val outgoingRequests = mutableMapOf<Int, String>() // requestId -> roomId (for all outgoing requests)
    private val fcmRegistrationRequests = mutableMapOf<Int, String>() // requestId -> "fcm_registration"
    private val eventRequests = mutableMapOf<Int, Pair<String, (TimelineEvent?) -> Unit>>() // requestId -> (roomId, callback)
    private val paginateRequests = mutableMapOf<Int, String>() // requestId -> roomId (for pagination)
    private val backgroundPrefetchRequests = mutableMapOf<Int, String>() // requestId -> roomId (for background prefetch)
    private val roomStateWithMembersRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.RoomStateInfo?, String?) -> Unit>() // requestId -> callback
    private val userEncryptionInfoRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    private val mutualRoomsRequests = mutableMapOf<Int, (List<String>?, String?) -> Unit>() // requestId -> callback
    private val trackDevicesRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    private val resolveAliasRequests = mutableMapOf<Int, (Pair<String, List<String>>?) -> Unit>() // requestId -> callback
    private val getRoomSummaryRequests = mutableMapOf<Int, (Pair<net.vrkknn.andromuks.utils.RoomSummary?, String?>?) -> Unit>() // requestId -> callback
    private val joinRoomCallbacks = mutableMapOf<Int, (Pair<String?, String?>?) -> Unit>() // requestId -> callback
    private val roomSpecificStateRequests = mutableMapOf<Int, String>() // requestId -> roomId (for get_specific_room_state requests)
    private val fullMemberListRequests = mutableMapOf<Int, String>() // requestId -> roomId (for get_room_state with include_members requests)
    
    // PERFORMANCE: Track pending full member list requests to prevent duplicate WebSocket commands
    private val pendingFullMemberListRequests = mutableSetOf<String>() // roomId that have pending full member list requests
    
    // OPPORTUNISTIC PROFILE LOADING: Track pending on-demand profile requests
    private val pendingProfileRequests = mutableSetOf<String>() // "roomId:userId" keys for pending profile requests
    private val profileRequests = mutableMapOf<Int, String>() // requestId -> "roomId:userId" for on-demand profile requests
    
    // PERFORMANCE: Throttle profile requests to prevent animation-blocking bursts
    // Tracks recent profile request timestamps to skip rapid re-requests during animation window
    private val recentProfileRequestTimes = mutableMapOf<String, Long>() // "roomId:userId" -> timestamp
    private val PROFILE_REQUEST_THROTTLE_MS = 5000L // Skip if requested within last 5 seconds
    private val REACTION_BACKFILL_ON_OPEN_ENABLED = false
    
    // Pagination state
    private var smallestRowId: Long = -1L // Smallest rowId from initial paginate
    var isPaginating by mutableStateOf(false)
        private set
    var hasMoreMessages by mutableStateOf(true) // Whether there are more messages to load
        private set
    var autoPaginationEnabled by mutableStateOf(false)
        private set
    
    fun setAutoPaginationEnabled(enabled: Boolean, reason: String? = null) {
        if (autoPaginationEnabled != enabled) {
            val reasonText = reason?.let { " ($it)" } ?: ""
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Auto-pagination ${if (enabled) "ENABLED" else "DISABLED"}$reasonText"
            )
            autoPaginationEnabled = enabled
        }
    }
    
    fun hasPendingTimelineRequest(roomId: String): Boolean {
        return timelineRequests.values.any { it == roomId }
    }
    
    
    private var webSocket: WebSocket? = null
    private var lastReceivedRequestId: Int = 0 // Tracks ANY incoming request_id (for pong detection)
    private var lastReceivedSyncId: Int = 0 // Tracks ONLY sync_complete negative request_ids (for reconnection)
    @Volatile private var pendingLastReceivedSyncId: Int? = null // Candidate sync_complete ID awaiting persistence
    private val pendingSyncLock = Any()
    private var hasPersistedSync = false
    private var lastSyncTimestamp: Long = 0 // Timestamp of last sync_complete received
    private var currentRunId: String = "" // Unique connection ID from gomuks backend
    private var vapidKey: String = "" // VAPID key for push notifications
    private var hasHadInitialConnection = false // Track if we've had an initial connection to only vibrate on reconnections

    // NETWORK OPTIMIZATION: Offline caching and connection state
    private var isOfflineMode = false
    private var lastNetworkState = true // true = online, false = offline
    private val offlineCacheExpiry = 24 * 60 * 60 * 1000L // 24 hours
    
    // WebSocket reconnection log
    data class ActivityLogEntry(
        val timestamp: Long,
        val event: String,
        val networkType: String? = null
    )
    
    private val activityLog = mutableListOf<ActivityLogEntry>()
    private val maxLogEntries = 100 // Keep last 100 entries
    
    /**
     * Log an activity event (app started, websocket connected, disconnected, etc.)
     */
    fun logActivity(event: String, networkType: String? = null) {
        val entry = ActivityLogEntry(
            timestamp = System.currentTimeMillis(),
            event = event,
            networkType = networkType
        )
        activityLog.add(entry)
        
        // Keep only the last maxLogEntries entries
        if (activityLog.size > maxLogEntries) {
            activityLog.removeAt(0)
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Logged activity - $event")
    }
    
    /**
     * Get the activity log for display
     */
    fun getActivityLog(): List<ActivityLogEntry> {
        return activityLog.toList()
    }
    
    // Backwards compatibility - keep old reconnection log methods
    data class ReconnectionLogEntry(
        val timestamp: Long,
        val reason: String
    )
    
    private val reconnectionLog = mutableListOf<ReconnectionLogEntry>()
    
    /**
     * Log a WebSocket reconnection event (legacy - now calls logActivity)
     */
    private fun logReconnection(reason: String) {
        val entry = ReconnectionLogEntry(
            timestamp = System.currentTimeMillis(),
            reason = reason
        )
        reconnectionLog.add(entry)
        
        // Keep only the last maxLogEntries entries
        if (reconnectionLog.size > maxLogEntries) {
            reconnectionLog.removeAt(0)
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Logged reconnection - $reason")
    }
    
    /**
     * Get the reconnection log for display (legacy)
     */
    fun getReconnectionLog(): List<ReconnectionLogEntry> {
        return reconnectionLog.toList()
    }

    fun setWebSocket(webSocket: WebSocket) {
        android.util.Log.d("Andromuks", "AppViewModel: setWebSocket() called for $viewModelId")
        this.webSocket = webSocket
        
        // PHASE 4: Register this ViewModel to receive WebSocket messages
        android.util.Log.d("Andromuks", "AppViewModel: Registering $viewModelId to receive WebSocket messages")
        WebSocketService.registerReceiveCallback(viewModelId, this)
        
        // Set up service callbacks for ping/pong (using deprecated method for now)
        android.util.Log.d("Andromuks", "AppViewModel: Setting up service callbacks")
        @Suppress("DEPRECATION")
        WebSocketService.setWebSocketSendCallback { command, requestId, data ->
            sendWebSocketCommand(command, requestId, data) == WebSocketResult.SUCCESS
        }
        WebSocketService.setReconnectionCallback { reason ->
            restartWebSocket(reason)
        }
        WebSocketService.setOfflineModeCallback { isOffline ->
            if (isOffline) {
                android.util.Log.w("Andromuks", "AppViewModel: Entering offline mode")
                isOfflineMode = true
                lastNetworkState = false
            } else {
                android.util.Log.i("Andromuks", "AppViewModel: Exiting offline mode")
                isOfflineMode = false
                lastNetworkState = true
                // Reset reconnection state on network restoration
                WebSocketService.resetReconnectionState()
            }
        }
        
        // Set up activity logging callback
        WebSocketService.setActivityLogCallback { event, networkType ->
            logActivity(event, networkType)
        }
        
        // Delegate WebSocket management to service
        android.util.Log.d("Andromuks", "AppViewModel: Calling WebSocketService.setWebSocket()")
        WebSocketService.setWebSocket(webSocket)
        
        // Register FCM on every WebSocket connection to ensure backend has current token
        android.util.Log.d("Andromuks", "AppViewModel: setWebSocket - registering FCM to ensure backend has current token")
        registerFCMWithGomuksBackend()
        
        // Broadcast that socket connection is available and retry pending operations
        android.util.Log.i("Andromuks", "AppViewModel: WebSocket connection established - retrying ${pendingWebSocketOperations.size} pending operations")
        
        // Track if we've had an initial connection (no longer needed for vibration)
        if (!hasHadInitialConnection) {
            hasHadInitialConnection = true
        }
        
        retryPendingWebSocketOperations()
    }
    
    /**
     * RECONNECTION: Reset reconnection state after successful connection
     */
    fun resetReconnectionState() {
        android.util.Log.d("Andromuks", "AppViewModel: Resetting reconnection state (successful connection)")
        // Delegate to service
        WebSocketService.resetReconnectionState()
    }
    
    /**
     * RECONNECTION: Schedule WebSocket reconnection with exponential backoff
     * 
     * This method implements exponential backoff to avoid overwhelming the server
     * during outages and to be more battery efficient.
     * 
     * @param reason Human-readable reason for reconnection (for logging)
     */
    fun scheduleReconnection(reason: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Delegating reconnection scheduling to service")
        // Delegate to service
        WebSocketService.scheduleReconnection(reason)
    }
    
    fun isWebSocketConnected(): Boolean {
        return WebSocketService.isWebSocketConnected()
    }

    fun clearWebSocket(reason: String = "Unknown") {
        this.webSocket = null
        
        // Delegate WebSocket clearing to service
        WebSocketService.clearWebSocket(reason)
    }
    
    /**
     * Retry all pending WebSocket operations now that connection is available
     */
    private fun retryPendingWebSocketOperations() {
        if (pendingWebSocketOperations.isEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: No pending WebSocket operations to retry")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Retrying ${pendingWebSocketOperations.size} pending WebSocket operations")
        
        val operationsToRetry = pendingWebSocketOperations.toList()
        pendingWebSocketOperations.clear()
        
        operationsToRetry.forEach { operation ->
            try {
                when (operation.type) {
                    "sendMessage" -> {
                        val roomId = operation.data["roomId"] as String?
                        val text = operation.data["text"] as String?
                        if (roomId != null && text != null) {
                            android.util.Log.d("Andromuks", "AppViewModel: Retrying sendMessage for room $roomId")
                            val result = sendMessageInternal(roomId, text)
                            if (result != WebSocketResult.SUCCESS && operation.retryCount < maxRetryAttempts) {
                                // Re-queue if still failing
                                pendingWebSocketOperations.add(operation.copy(retryCount = operation.retryCount + 1))
                            }
                        }
                    }
                    "sendReply" -> {
                        // Note: SendReply operations would need the originalEvent, which is complex to serialize
                        // For now, we'll skip retrying sendReply operations as they're less critical
                        android.util.Log.w("Andromuks", "AppViewModel: Skipping retry of sendReply operation (complex to serialize)")
                    }
                    "markRoomAsRead" -> {
                        val roomId = operation.data["roomId"] as String?
                        val eventId = operation.data["eventId"] as String?
                        if (roomId != null && eventId != null) {
                            android.util.Log.d("Andromuks", "AppViewModel: Retrying markRoomAsRead for room $roomId")
                            markRoomAsReadInternal(roomId, eventId)
                        }
                    }
                    else -> {
                        // NETWORK OPTIMIZATION: Handle offline retry commands
                        if (operation.type.startsWith("offline_")) {
                            val command = operation.data["command"] as String?
                            val requestId = operation.data["requestId"] as Int?
                            @Suppress("UNCHECKED_CAST")
                            val data = operation.data["data"] as? Map<String, Any>
                            
                            if (command != null && requestId != null && data != null) {
                                android.util.Log.d("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Retrying offline command: $command")
                                sendWebSocketCommand(command, requestId, data)
                            }
                        } else {
                            android.util.Log.w("Andromuks", "AppViewModel: Unknown operation type for retry: ${operation.type}")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error retrying operation ${operation.type}: ${e.message}")
                if (operation.retryCount < maxRetryAttempts) {
                    pendingWebSocketOperations.add(operation.copy(retryCount = operation.retryCount + 1))
                }
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Finished retrying pending operations, ${pendingWebSocketOperations.size} remain queued")
    }
    

    fun noteIncomingRequestId(requestId: Int) {
        if (requestId != 0) {
            // Track ALL incoming request_ids for general purposes (pong detection, etc.)
            lastReceivedRequestId = requestId
            
            // Separately track ONLY negative request_ids from sync_complete for reconnection
            if (requestId < 0) {
                synchronized(pendingSyncLock) {
                    val currentPending = pendingLastReceivedSyncId
                    if (currentPending == null || requestId < currentPending) {
                        pendingLastReceivedSyncId = requestId
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Recorded pending sync_complete requestId=$requestId (previous pending=$currentPending)"
                        )
                    } else {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Pending sync_complete requestId=$requestId ignored (current pending=$currentPending)"
                        )
                    }
                }
            }
            
            // Pong handling is now done directly in NetworkUtils
        }
    }
    
    /**
     * Stores the run_id and vapid_key received from the gomuks backend.
     * This is used for reconnection to resume from where we left off.
     */
    fun handleRunId(runId: String, vapidKey: String) {
        android.util.Log.d("Andromuks", "AppViewModel: handleRunId called with runId='$runId', vapidKey='${vapidKey.take(20)}...'")
        android.util.Log.d("Andromuks", "AppViewModel: DEBUG - runId type: ${runId.javaClass.simpleName}, length: ${runId.length}")
        android.util.Log.d("Andromuks", "AppViewModel: DEBUG - runId starts with '{': ${runId.startsWith("{")}")
        
        currentRunId = runId
        this.vapidKey = vapidKey
        
        // Sync reconnection state with service
        WebSocketService.setReconnectionState(runId, lastReceivedSyncId, vapidKey)
        
        android.util.Log.d("Andromuks", "AppViewModel: Stored run_id: $runId, vapid_key: ${vapidKey.take(20)}...")
    }
    
    /**
     * Commits a persisted sync_complete request ID after the payload has been written to storage.
     * This ensures we never advance lastReceivedSyncId before the local database is consistent.
     */
    private fun onSyncCompletePersisted(requestId: Int) {
        if (requestId >= 0) {
            return
        }
        
        val pendingLog = synchronized(pendingSyncLock) {
            if (pendingLastReceivedSyncId == requestId) {
                pendingLastReceivedSyncId = null
            }
            pendingLastReceivedSyncId
        }
        
        val shouldUpdate = lastReceivedSyncId == 0 || requestId < lastReceivedSyncId
        if (!shouldUpdate) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Persisted sync_complete requestId=$requestId ignored (current lastReceivedSyncId=$lastReceivedSyncId, pending=$pendingLog)"
            )
            return
        }
        
        val previous = lastReceivedSyncId
        lastReceivedSyncId = requestId
        hasPersistedSync = true
        android.util.Log.d(
            "Andromuks",
            "AppViewModel: Committed lastReceivedSyncId from $previous to $requestId after persistence (pending=$pendingLog)"
        )
        
        // Notify service for reconnection
        WebSocketService.updateLastReceivedSyncId(lastReceivedSyncId)
        WebSocketService.setReconnectionState(currentRunId, lastReceivedSyncId, vapidKey)
        WebSocketService.markInitialSyncPersisted()
    }
    
    /**
     * Gets the current run_id for reconnection
     */
    fun getCurrentRunId(): String = currentRunId
    
    /**
     * Gets the last received sync_complete request_id for reconnection
     */
    fun getLastReceivedId(): Int = lastReceivedSyncId
    
    /**
     * Determines whether the current session has persisted sync data,
     * allowing us to include last_received_id on reconnection attempts.
     */
    fun shouldIncludeLastReceivedId(): Boolean = hasPersistedSync
    
    /**
     * Gets the current VAPID key for push notifications
     */
    fun getVapidKey(): String = vapidKey
    
    /**
     * Gets the current request ID counter (next ID to be used)
     */
    fun getCurrentRequestId(): Int = requestIdCounter
    
    /**
     * Gets the last received request ID from the server
     */
    fun getLastReceivedRequestId(): Int = lastReceivedRequestId
    
    /**
     * Check if an event is pinned in the current room
     */
    
    /**
     * Gets the next request ID for outgoing commands
     * This should be used by all components that send WebSocket commands
     */
    fun getNextRequestId(): Int = requestIdCounter++
    
    /**
     * Saves the current WebSocket state and room data to persistent storage.
     * This allows the app to resume quickly on restart by loading cached data
     * and reconnecting with run_id and last_received_id.
     * 
     * Note: With foreground service maintaining connection, this primarily serves as
     * crash recovery - preserving run_id and sync state for seamless resumption if
     * the app process is killed by the system.
     */
    fun saveStateToStorage(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Save WebSocket connection state
            editor.putString("ws_run_id", currentRunId)
            editor.putString("ws_vapid_key", vapidKey)
            
            // Save room data as JSON
            val roomsArray = JSONArray()
            for (room in allRooms) {
                val roomJson = JSONObject()
                roomJson.put("id", room.id)
                roomJson.put("name", room.name)
                roomJson.put("avatarUrl", room.avatarUrl ?: "")
                roomJson.put("messagePreview", room.messagePreview ?: "")
                roomJson.put("messageSender", room.messageSender ?: "")
                roomJson.put("sortingTimestamp", room.sortingTimestamp ?: 0L)
                roomJson.put("unreadCount", room.unreadCount ?: 0)
                roomJson.put("highlightCount", room.highlightCount ?: 0)
                roomJson.put("isDirectMessage", room.isDirectMessage)
                roomsArray.put(roomJson)
            }
            editor.putString("cached_rooms", roomsArray.toString())
            
            // Save bridge cache data
            val bridgeInfoArray = JSONArray()
            for ((roomId, bridgeInfo) in bridgeInfoCache) {
                val bridgeJson = JSONObject()
                bridgeJson.put("roomId", roomId)
                bridgeJson.put("bridgebot", bridgeInfo.bridgebot)
                bridgeJson.put("creator", bridgeInfo.creator)
                
                // Channel data
                val channelJson = JSONObject()
                channelJson.put("avatarUrl", bridgeInfo.channel.avatarUrl ?: "")
                channelJson.put("displayname", bridgeInfo.channel.displayname)
                channelJson.put("id", bridgeInfo.channel.id)
                bridgeJson.put("channel", channelJson)
                
                // Protocol data
                val protocolJson = JSONObject()
                protocolJson.put("avatarUrl", bridgeInfo.protocol.avatarUrl ?: "")
                protocolJson.put("displayname", bridgeInfo.protocol.displayname)
                protocolJson.put("externalUrl", bridgeInfo.protocol.externalUrl ?: "")
                protocolJson.put("id", bridgeInfo.protocol.id)
                bridgeJson.put("protocol", protocolJson)
                
                bridgeInfoArray.put(bridgeJson)
            }
            editor.putString("cached_bridge_info", bridgeInfoArray.toString())
            
            // Save checked rooms set
            val checkedRoomsArray = JSONArray()
            for (roomId in bridgeCacheCheckedRooms) {
                checkedRoomsArray.put(roomId)
            }
            editor.putString("cached_bridge_checked_rooms", checkedRoomsArray.toString())
            
            // Save timestamp of when state was saved
            editor.putLong("state_saved_timestamp", System.currentTimeMillis())
            
            editor.apply()
            android.util.Log.d("Andromuks", "AppViewModel: Saved state to storage - run_id: $currentRunId, last_received_sync_id: $lastReceivedSyncId, rooms: ${allRooms.size}")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to save state to storage", e)
        }
    }
    /**
     * Loads the previously saved WebSocket state and room data from persistent storage.
     * Tries Room database first, then falls back to SharedPreferences.
     * Returns true if cached data was loaded, false otherwise.
     */
    fun loadStateFromStorage(context: android.content.Context): Boolean {
        try {
            // Initialize bootstrap loader
            if (bootstrapLoader == null) {
                bootstrapLoader = net.vrkknn.andromuks.database.BootstrapLoader(context)
            }
            
            // Try loading from Room database first (synchronous check for run_id)
            try {
                val storedRunId = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    bootstrapLoader!!.getStoredRunId()
                }
                
                if (storedRunId.isNotEmpty()) {
                    // We have a valid run_id, load from database
                    android.util.Log.d("Andromuks", "AppViewModel: Found run_id in database, loading bootstrap data")
                    
                    val loaded = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                        val bootstrapResult = bootstrapLoader!!.loadBootstrap()
                        
                        if (bootstrapResult.isValid && bootstrapResult.rooms.isNotEmpty()) {
                            android.util.Log.d("Andromuks", "AppViewModel: Loaded ${bootstrapResult.rooms.size} rooms from Room database")
                            
                            // Restore WebSocket state from DB
                            if (bootstrapResult.runId.isNotEmpty()) {
                                currentRunId = bootstrapResult.runId
                                lastReceivedSyncId = bootstrapResult.lastReceivedId
                                hasPersistedSync = false
                                pendingLastReceivedSyncId = null
                                
                                // Get vapid key from SharedPreferences (not stored in DB yet)
                                val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                                vapidKey = prefs.getString("ws_vapid_key", "") ?: ""
                                
                                // Sync restored state with service
                                WebSocketService.setReconnectionState(bootstrapResult.runId, bootstrapResult.lastReceivedId, vapidKey)
                                
                                android.util.Log.d("Andromuks", "AppViewModel: Restored WebSocket state from DB - run_id: ${bootstrapResult.runId}, last_received_id: ${bootstrapResult.lastReceivedId}")
                            }
                            
                            // Update room map with ALL rooms from database
                            for (room in bootstrapResult.rooms) {
                                roomMap[room.id] = room
                            }
                            android.util.Log.d("Andromuks", "AppViewModel: Restored ${bootstrapResult.rooms.size} rooms from database into roomMap")
                            
                            // CRITICAL: Update allRooms so sections can filter properly
                            // Convert roomMap to sorted list for allRooms
                            allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                            android.util.Log.d("Andromuks", "AppViewModel: Updated allRooms with ${allRooms.size} rooms from database")
                            
                            // Load bridge info from database
                            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                                val bridgeInfoMap = bootstrapLoader!!.loadBridgeInfoFromDb()
                                val checkedRooms = bootstrapLoader!!.getBridgeCheckedRooms()
                                
                                for ((roomId, bridgeJson) in bridgeInfoMap) {
                                    try {
                                        val bridgeObj = org.json.JSONObject(bridgeJson)
                                        val bridgeInfo = parseBridgeInfo(bridgeObj)
                                        if (bridgeInfo != null) {
                                            // Update in-memory cache without triggering persistence (already in DB)
                                            bridgeInfoCache = bridgeInfoCache + (roomId to bridgeInfo)
                                            android.util.Log.d("Andromuks", "AppViewModel: Loaded bridge info from DB for room $roomId")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Andromuks", "AppViewModel: Error loading bridge info from DB for room $roomId: ${e.message}", e)
                                    }
                                }
                                
                                // BUG FIX #3: Mark all checked rooms (including those with no bridge) as checked
                                bridgeCacheCheckedRooms = bridgeCacheCheckedRooms + checkedRooms
                                
                                // Create bridge pseudo-spaces after loading all bridge info
                                createBridgePseudoSpaces()
                                android.util.Log.d("Andromuks", "AppViewModel: Loaded ${bridgeInfoMap.size} bridge info entries and ${checkedRooms.size} checked rooms from database")
                            }
                            
                            // Load spaces from database
                            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                                val loadedSpaces = bootstrapLoader!!.loadSpacesFromDb(roomMap)
                                allSpaces = loadedSpaces
                                spaceList = loadedSpaces
                                spacesLoaded = true
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Loaded ${loadedSpaces.size} spaces from database (spacesLoaded=${spacesLoaded})"
                                )
                            }
                            
                            // Load account_data from database
                            if (bootstrapResult.accountDataJson != null) {
                                try {
                                    val accountDataObj = org.json.JSONObject(bootstrapResult.accountDataJson)
                                    processAccountData(accountDataObj)
                                    android.util.Log.d("Andromuks", "AppViewModel: Processed account_data from database (m.direct, recent_emoji, etc.)")
                                } catch (e: Exception) {
                                    android.util.Log.e("Andromuks", "AppViewModel: Error processing account_data from database: ${e.message}", e)
                                }
                            }
                            
                            // Load pending invites from database
                            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                                val loadedInvites = bootstrapLoader!!.loadInvitesFromDb()
                                if (loadedInvites.isNotEmpty()) {
                                    for (invite in loadedInvites) {
                                        pendingInvites[invite.roomId] = invite
                                    }
                                    android.util.Log.d("Andromuks", "AppViewModel: Loaded ${loadedInvites.size} pending invites from database")
                                    // Trigger UI update to show invites
                                    roomListUpdateCounter++
                                } else {
                                    android.util.Log.d("Andromuks", "AppViewModel: No pending invites found in database")
                                }
                            }
                            
                            // BUG FIX #2: Force update cached room sections after all data is loaded
                            // Reset hash to force recalculation since we're loading from DB
                            lastAllRoomsHash = 0
                            updateCachedRoomSections()
                            updateBadgeCounts(allRooms)
                            android.util.Log.d("Andromuks", "AppViewModel: Updated cached room sections and badge counts (allRooms.size=${allRooms.size})")
                            
                            // CRITICAL FIX: Force room list sort to ensure proper sorting and UI update
                            // This ensures rooms are properly sorted and the UI update mechanism is triggered
                            forceRoomListSort()
                            android.util.Log.d("Andromuks", "AppViewModel: Triggered room list sort and UI update after loading from database")
                            
                            true
                        } else {
                            android.util.Log.d("Andromuks", "AppViewModel: Database bootstrap returned invalid/empty result, falling back to SharedPreferences")
                            false
                        }
                    }
                    
                    if (loaded) {
                        return true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error loading from database: ${e.message}", e)
                // Fall through to SharedPreferences fallback
            }
            
            // Fallback to SharedPreferences (legacy path)
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            
            // Check if we have cached data
            val savedTimestamp = prefs.getLong("state_saved_timestamp", 0)
            if (savedTimestamp == 0L) {
                android.util.Log.d("Andromuks", "AppViewModel: No cached state found")
                return false
            }
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastSave = currentTime - savedTimestamp
            
            // Check if data is stale (> 10 minutes old)
            val staleThreshold = 10 * 60 * 1000L // 10 minutes in milliseconds
            if (timeSinceLastSave > staleThreshold) {
                android.util.Log.d("Andromuks", "AppViewModel: Cached state is stale (${timeSinceLastSave / 60000} minutes old)")
                android.util.Log.d("Andromuks", "AppViewModel: Performing full refresh instead of using stale cache")
                
                // Load run_id for reconnection, but don't load rooms or last_received_sync_id
                val runId = prefs.getString("ws_run_id", "") ?: ""
                val savedVapidKey = prefs.getString("ws_vapid_key", "") ?: ""
                
                if (runId.isNotEmpty()) {
                    currentRunId = runId
                    vapidKey = savedVapidKey
                    android.util.Log.d("Andromuks", "AppViewModel: Preserved run_id for reconnection: $runId")
                }
                
                // Clear stale cache
                clearCachedState(context)
                
                return false // Don't use stale cache
            }
            
            // Check if cached data is not too old (e.g., max 7 days)
            val maxCacheAge = 7 * 24 * 60 * 60 * 1000L // 7 days in milliseconds
            if (timeSinceLastSave > maxCacheAge) {
                android.util.Log.d("Andromuks", "AppViewModel: Cached state is too old (${timeSinceLastSave / 86400000} days), ignoring")
                return false
            }
            
            // Restore WebSocket connection state
            val runId = prefs.getString("ws_run_id", "") ?: ""
            val savedVapidKey = prefs.getString("ws_vapid_key", "") ?: ""
            
            android.util.Log.d("Andromuks", "AppViewModel: Loading from SharedPreferences - runId='$runId', vapidKey='${savedVapidKey.take(20)}...'")
            android.util.Log.d("Andromuks", "AppViewModel: DEBUG - loaded runId type: ${runId.javaClass.simpleName}, length: ${runId.length}")
            android.util.Log.d("Andromuks", "AppViewModel: DEBUG - loaded runId starts with '{': ${runId.startsWith("{")}")
            
            if (runId.isNotEmpty()) {
                currentRunId = runId
                lastReceivedSyncId = 0
                pendingLastReceivedSyncId = null
                hasPersistedSync = false
                vapidKey = savedVapidKey
                
                // Sync restored state with service (without last_received_sync_id)
                WebSocketService.setReconnectionState(runId, lastReceivedSyncId, savedVapidKey)
                
                android.util.Log.d("Andromuks", "AppViewModel: Restored WebSocket state - run_id: $runId (last_received_sync_id not restored)")
            }
            
            // Restore room data
            val cachedRoomsJson = prefs.getString("cached_rooms", null)
            if (cachedRoomsJson != null) {
                val roomsArray = JSONArray(cachedRoomsJson)
                val cachedRooms = mutableListOf<RoomItem>()
                
                for (i in 0 until roomsArray.length()) {
                    val roomJson = roomsArray.getJSONObject(i)
                    val room = RoomItem(
                        id = roomJson.getString("id"),
                        name = roomJson.getString("name"),
                        avatarUrl = roomJson.getString("avatarUrl").takeIf { it.isNotBlank() },
                        messagePreview = roomJson.getString("messagePreview").takeIf { it.isNotBlank() },
                        messageSender = roomJson.getString("messageSender").takeIf { it.isNotBlank() },
                        sortingTimestamp = roomJson.getLong("sortingTimestamp").takeIf { it > 0 },
                        unreadCount = roomJson.getInt("unreadCount").takeIf { it > 0 },
                        highlightCount = roomJson.getInt("highlightCount").takeIf { it > 0 },
                        isDirectMessage = roomJson.getBoolean("isDirectMessage")
                    )
                    cachedRooms.add(room)
                }
                
                // Update room map and UI
                roomMap.clear()
                cachedRooms.forEach { room ->
                    roomMap[room.id] = room
                }
                
                allRooms = cachedRooms
                invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = cachedRooms)))
                
                android.util.Log.d("Andromuks", "AppViewModel: Restored ${cachedRooms.size} rooms from cache")
            }
            
            // Restore bridge cache data
            val cachedBridgeInfoJson = prefs.getString("cached_bridge_info", null)
            if (cachedBridgeInfoJson != null) {
                val bridgeInfoArray = JSONArray(cachedBridgeInfoJson)
                val cachedBridgeInfo = mutableMapOf<String, BridgeInfo>()
                
                for (i in 0 until bridgeInfoArray.length()) {
                    val bridgeJson = bridgeInfoArray.getJSONObject(i)
                    val roomId = bridgeJson.getString("roomId")
                    
                    val channelObj = bridgeJson.getJSONObject("channel")
                    val channel = BridgeChannel(
                        avatarUrl = channelObj.getString("avatarUrl").takeIf { it.isNotBlank() },
                        displayname = channelObj.getString("displayname"),
                        id = channelObj.getString("id")
                    )
                    
                    val protocolObj = bridgeJson.getJSONObject("protocol")
                    val protocol = BridgeProtocol(
                        avatarUrl = protocolObj.getString("avatarUrl").takeIf { it.isNotBlank() },
                        displayname = protocolObj.getString("displayname"),
                        externalUrl = protocolObj.getString("externalUrl").takeIf { it.isNotBlank() },
                        id = protocolObj.getString("id")
                    )
                    
                    val bridgeInfo = BridgeInfo(
                        bridgebot = bridgeJson.getString("bridgebot"),
                        channel = channel,
                        creator = bridgeJson.getString("creator"),
                        protocol = protocol
                    )
                    
                    cachedBridgeInfo[roomId] = bridgeInfo
                }
                
                bridgeInfoCache = cachedBridgeInfo
                android.util.Log.d("Andromuks", "AppViewModel: Restored ${cachedBridgeInfo.size} bridge info entries from cache")
            }
            
            // Restore checked rooms set
            val cachedCheckedRoomsJson = prefs.getString("cached_bridge_checked_rooms", null)
            if (cachedCheckedRoomsJson != null) {
                val checkedRoomsArray = JSONArray(cachedCheckedRoomsJson)
                val cachedCheckedRooms = mutableSetOf<String>()
                
                for (i in 0 until checkedRoomsArray.length()) {
                    cachedCheckedRooms.add(checkedRoomsArray.getString(i))
                }
                
                bridgeCacheCheckedRooms = cachedCheckedRooms
                android.util.Log.d("Andromuks", "AppViewModel: Restored ${cachedCheckedRooms.size} checked rooms from cache")
            }
            
            // Mark spaces as loaded so UI can show cached data
            spacesLoaded = true
            
            return true
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load state from storage", e)
            return false
        }
    }
    
    /**
     * Clears the cached state from storage.
     * This should be called on logout or when we want to force a fresh start.
     */
    fun clearCachedState(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            editor.remove("ws_run_id")
            editor.remove("ws_vapid_key")
            editor.remove("cached_rooms")
            editor.remove("cached_bridge_info")
            editor.remove("cached_bridge_checked_rooms")
            editor.remove("state_saved_timestamp")
            
            editor.apply()
            
            // Also clear in-memory state
            currentRunId = ""
            lastReceivedSyncId = 0
            pendingLastReceivedSyncId = null
            hasPersistedSync = false
            vapidKey = ""
            navigationCallbackTriggered = false // Reset navigation flag for fresh start
            
            // Clear service reconnection state
            WebSocketService.clearReconnectionState()
            
            android.util.Log.d("Andromuks", "AppViewModel: Cleared cached state")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to clear cached state", e)
        }
    }

    
    private fun restartWebSocket(reason: String = "Unknown reason") {
        android.util.Log.d("Andromuks", "AppViewModel: restartWebSocket invoked - reason: $reason")
        
        // Only show toast for important reconnection reasons, not every attempt
        val shouldShowToast = when {
            reason.contains("Manual reconnection") -> true
            reason.contains("Full refresh") -> true
            reason.contains("attempt #1") && reason.contains("Network type changed") -> true // Only first network change attempt
            else -> false // Hide "Network restored" and other spam
        }
        
        if (shouldShowToast) {
            appContext?.let { context ->
                viewModelScope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "WS: $reason",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val restartCallback = onRestartWebSocket

        if (restartCallback != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Using direct reconnect callback for reason: $reason")
            // Cancel any pending reconnection jobs in the service to avoid duplicate attempts
            WebSocketService.cancelReconnection()
            // Ensure the service state is reset before establishing a new connection
            WebSocketService.clearWebSocket(reason)
            restartCallback.invoke(reason)
            return
        }

        android.util.Log.w(
            "Andromuks",
            "AppViewModel: onRestartWebSocket callback not set - falling back to service restart"
        )
        WebSocketService.restartWebSocket(reason)
    }

    fun requestUserProfile(userId: String, roomId: String? = null) {
        // PERFORMANCE: Prevent duplicate profile requests for the same user
        if (pendingProfileRequests.contains(userId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Profile request already pending for $userId, skipping duplicate")
            return
        }
        
        // Check if already in cache to avoid unnecessary requests
        val cachedProfile = getUserProfile(userId, roomId)
        if (cachedProfile != null) {
            //android.util.Log.d("Andromuks", "AppViewModel: Profile already cached for $userId, skipping request")
            return
        }
        
        val ws = webSocket ?: return
        val reqId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingProfileRequests.add(userId)
        profileRequests[reqId] = userId
        if (roomId != null) {
            profileRequestRooms[reqId] = roomId
        }
        
        val json = org.json.JSONObject()
        json.put("command", "get_profile")
        json.put("request_id", reqId)
        val data = org.json.JSONObject()
        data.put("user_id", userId)
        json.put("data", data)
        val payload = json.toString()
        android.util.Log.d("Andromuks", "AppViewModel: Sending get_profile for $userId (roomId=$roomId): $payload")
        ws.send(payload)
    }
    
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
                // PERFORMANCE: Only request if we haven't already requested this user's profile (avoid duplicates)
                if (!pendingProfileRequests.contains(sender)) {
                    usersToRequest.add(sender)
                    android.util.Log.d("Andromuks", "AppViewModel: Missing profile data for $sender - displayName: $hasDisplayName, avatar: $hasAvatar")
                }
            }
        }
        
        // Request profiles for users with missing data
        for (userId in usersToRequest) {
            android.util.Log.d("Andromuks", "AppViewModel: Requesting missing profile for $userId")
            requestUserProfile(userId, roomId)
        }
    }
    
    // Track outgoing requests for timeline processing
    fun trackOutgoingRequest(requestId: Int, roomId: String) {
        outgoingRequests[requestId] = roomId
        android.util.Log.d("Andromuks", "AppViewModel: Tracking outgoing request $requestId for room $roomId")
    }
    
    // Send a message and track the response
    fun sendMessage(roomId: String, text: String, mentions: List<String> = emptyList()) {
        val ws = webSocket ?: return
        val reqId = requestIdCounter++
        
        android.util.Log.d("Andromuks", "AppViewModel: sendMessage called with roomId=$roomId, text='$text', reqId=$reqId")
        
        // Track this outgoing request
        trackOutgoingRequest(reqId, roomId)
        
        val json = org.json.JSONObject()
        json.put("command", "send_message")
        json.put("request_id", reqId)
        val data = org.json.JSONObject()
        data.put("room_id", roomId)
        data.put("text", text)
        val mentionsObj = org.json.JSONObject()
        val userIdsArray = org.json.JSONArray()
        mentions.forEach { userIdsArray.put(it) }
        mentionsObj.put("user_ids", userIdsArray)
        mentionsObj.put("room", false)
        data.put("mentions", mentionsObj)
        data.put("url_previews", org.json.JSONArray())
        json.put("data", data)
        
        val payload = json.toString()
        android.util.Log.d("Andromuks", "AppViewModel: Sending message: $payload")
        ws.send(payload)
    }
    
   
    /**
     * Helper function to process cached events and display them
     */
    private fun processCachedEvents(
        roomId: String,
        cachedEvents: List<TimelineEvent>,
        openingFromNotification: Boolean,
        skipNetworkRequests: Boolean = false
    ) {
        val ownMessagesInCache = cachedEvents.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
        val cacheType = if (openingFromNotification && cachedEvents.size < 100) "notification-optimized" else "standard"
        android.util.Log.d("Andromuks", "AppViewModel: ✓ CACHE HIT ($cacheType) - Instant room opening: ${cachedEvents.size} events (including $ownMessagesInCache of your own messages)")
        if (ownMessagesInCache > 0) {
            android.util.Log.d("Andromuks", "AppViewModel: ★ Cache contains $ownMessagesInCache messages from YOU")
        }
        if (BuildConfig.DEBUG) {
            val firstCached = cachedEvents.firstOrNull()
            val lastCached = cachedEvents.lastOrNull()
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Cached snapshot for $roomId -> first=${firstCached?.eventId}@${firstCached?.timestamp}, " +
                    "last=${lastCached?.eventId}@${lastCached?.timestamp}"
            )
        }
        
        // Ensure loading is false BEFORE processing to prevent loading flash
        isTimelineLoading = false
        
        // Clear and rebuild internal structures (but don't clear timelineEvents yet)
        eventChainMap.clear()
        editEventsMap.clear()
        messageVersions.clear()
        editToOriginal.clear()
        redactionCache.clear()
        messageReactions = emptyMap()
        roomsWithLoadedReceiptsFromDb.remove(roomId)
        roomsWithLoadedReactionsFromDb.remove(roomId)
        lastKnownDbLatestEventId.remove(roomId)
        
        // Reset pagination state
        smallestRowId = -1L
        isPaginating = false
        hasMoreMessages = true
        
        // Ensure member cache exists for this room
        if (roomMemberCache[roomId] == null) {
            roomMemberCache[roomId] = ConcurrentHashMap()
        }
        
        // Populate edit chain mapping from cached events
        // Process synchronously to ensure all events are added before building timeline
        android.util.Log.d("Andromuks", "AppViewModel: Processing ${cachedEvents.size} cached events into eventChainMap")
        var regularEventCount = 0
        var editEventCount = 0
        
        for (event in cachedEvents) {
            val isEditEvent = isEditEvent(event)
            
            if (isEditEvent) {
                editEventsMap[event.eventId] = event
                editEventCount++
            } else {
                eventChainMap[event.eventId] = EventChainEntry(
                    eventId = event.eventId,
                    ourBubble = event,
                    replacedBy = null,
                    originalTimestamp = event.timestamp
                )
                regularEventCount++
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Added ${regularEventCount} regular events and ${editEventCount} edit events to maps")
        android.util.Log.d("Andromuks", "AppViewModel: eventChainMap now has ${eventChainMap.size} entries")
        
        // Process edit relationships
        processEditRelationships()
        
        // Build timeline from chain (this updates timelineEvents)
        // CRITICAL: This must be called AFTER all events are added to eventChainMap
        buildTimelineFromChain()
        
        android.util.Log.d("Andromuks", "AppViewModel: Built timeline with ${timelineEvents.size} events from ${cachedEvents.size} cached events")
        val latestTimelineEvent = timelineEvents.lastOrNull()
        android.util.Log.d("Andromuks", "AppViewModel: Timeline latest event=${latestTimelineEvent?.eventId} timelineRowId=${latestTimelineEvent?.timelineRowid} ts=${latestTimelineEvent?.timestamp}")
        if (latestTimelineEvent != null) {
            val applicationContext = appContext?.applicationContext
            if (applicationContext != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(applicationContext)
                        val dbLatest = database.eventDao().getMostRecentEventForRoom(roomId)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: DB latest eventId=${dbLatest?.eventId} timelineRowId=${dbLatest?.timelineRowId} ts=${dbLatest?.timestamp} (timeline latest=${latestTimelineEvent.eventId})"
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Failed to compare DB latest for $roomId", e)
                    }
                }
            }
        }
        
        // Restore read receipts from database for cached events (only once per room)
        loadReceiptsForCachedEvents(roomId, cachedEvents)
        loadReactionsForRoom(roomId, cachedEvents)
        applyAggregatedReactionsFromEvents(cachedEvents, "cache")
        
        // Set smallest rowId from cached events for pagination
        val smallestCached = cachedEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
        if (smallestCached > 0) {
            smallestRowId = smallestCached
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: ✅ Room opened INSTANTLY with ${timelineEvents.size} cached events (no loading flash)")
        
        // Clear notification flag since we've successfully loaded the room
        if (openingFromNotification) {
            isPendingNavigationFromNotification = false
        }
        
        // NAVIGATION PERFORMANCE: Only request missing data for cached room opening
        val currentNavigationState = getRoomNavigationState(roomId)
        
        // Only request essential room state if not already loaded
        if (!skipNetworkRequests && currentNavigationState?.essentialDataLoaded != true && !pendingRoomStateRequests.contains(roomId)) {
            requestRoomState(roomId)
        }
        
        // OPPORTUNISTIC PROFILE LOADING: Skip member data loading to prevent OOM
        // Member profiles will be loaded on-demand when actually needed for rendering
        if (currentNavigationState?.memberDataLoaded != true) {
            android.util.Log.d("Andromuks", "AppViewModel: SKIPPING member data loading (using opportunistic loading)")
            // Mark as loaded to prevent repeated attempts
            navigationCache[roomId] = currentNavigationState?.copy(memberDataLoaded = true) ?: RoomNavigationState(roomId, memberDataLoaded = true)
        }
        
        // Mark as read
        val mostRecentEvent = cachedEvents.maxByOrNull { it.timestamp }
        if (mostRecentEvent != null) {
            markRoomAsRead(roomId, mostRecentEvent.eventId)
        }
        
        // IMPORTANT: Request historical reactions even when using cache
        // The cache filters out reaction events, so we need a paginate request to load them
        if (!skipNetworkRequests && REACTION_BACKFILL_ON_OPEN_ENABLED) {
            requestHistoricalReactions(roomId, smallestCached)
        }

        // No forward paginate on open; all additional history must be user-triggered
    }

    private fun loadReceiptsForCachedEvents(roomId: String, cachedEvents: List<TimelineEvent>) {
        if (cachedEvents.isEmpty()) {
            return
        }

        val context = appContext ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot restore receipts for $roomId - appContext is null")
            return
        }

        if (!roomsWithLoadedReceiptsFromDb.add(roomId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Read receipts for room $roomId already restored from database, skipping")
            return
        }

        val eventIds = cachedEvents.map { it.eventId }.toSet()
        if (eventIds.isEmpty()) {
            return
        }

        val applicationContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(applicationContext)
                val receiptEntities = database.receiptDao().getReceiptsForRoom(roomId)

                if (receiptEntities.isEmpty()) {
                    android.util.Log.d("Andromuks", "AppViewModel: No stored receipts found in database for room $roomId")
                    return@launch
                }

                val receiptsByEvent = receiptEntities
                    .filter { eventIds.contains(it.eventId) }
                    .groupBy { it.eventId }
                    .mapValues { (_, entities) ->
                        entities.sortedBy { it.timestamp }.map { entity ->
                            ReadReceipt(
                                userId = entity.userId,
                                eventId = entity.eventId,
                                timestamp = entity.timestamp,
                                receiptType = entity.type
                            )
                        }
                    }

                if (receiptsByEvent.isEmpty()) {
                    android.util.Log.d("Andromuks", "AppViewModel: Stored receipts did not match cached events for room $roomId")
                    return@launch
                }

                var didChange = false
                synchronized(readReceiptsLock) {
                    for ((eventId, receipts) in receiptsByEvent) {
                        val newList = receipts.toMutableList()
                        val existing = readReceipts[eventId]
                        if (existing == null || existing.size != newList.size || !existing.zip(newList).all { (a, b) -> a == b }) {
                            readReceipts[eventId] = newList
                            didChange = true
                        }
                    }
                }

                if (didChange) {
                    withContext(Dispatchers.Main) {
                        android.util.Log.d("Andromuks", "AppViewModel: Restored ${receiptsByEvent.size} read receipt groups from database for room $roomId")
                        readReceiptsUpdateCounter++
                    }
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: Database read receipts already match in-memory state for room $roomId")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to restore read receipts from database for room $roomId", e)
            }
        }
    }

    private fun loadReactionsForRoom(roomId: String, cachedEvents: List<TimelineEvent>) {
        if (cachedEvents.isEmpty()) return

        val context = appContext ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot restore reactions for $roomId - appContext is null")
            return
        }

        if (!roomsWithLoadedReactionsFromDb.add(roomId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Reactions for room $roomId already restored from database, skipping")
            return
        }

        val eventIds = cachedEvents.map { it.eventId }.toSet()
        if (eventIds.isEmpty()) return

        val applicationContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(applicationContext)
                val reactionEntities = database.reactionDao().getReactionsForRoom(roomId)
                if (reactionEntities.isEmpty()) {
                    android.util.Log.d("Andromuks", "AppViewModel: No stored reactions found in database for room $roomId")
                    return@launch
                }

                val reactionsByEvent = reactionEntities
                    .filter { eventIds.contains(it.targetEventId) }
                    .groupBy { it.targetEventId }
                    .mapValues { (_, entities) ->
                        entities
                            .groupBy { it.key }
                            .map { (key, perKeyEntities) ->
                                MessageReaction(
                                    emoji = key,
                                    count = perKeyEntities.size,
                                    users = perKeyEntities.map { reaction -> reaction.sender }.sorted()
                                )
                            }
                            .sortedBy { it.emoji }
                    }

                if (reactionsByEvent.isEmpty()) {
                    android.util.Log.d("Andromuks", "AppViewModel: Stored reactions did not match cached events for room $roomId")
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val updated = messageReactions.toMutableMap()
                    var changed = false
                    for ((eventId, reactions) in reactionsByEvent) {
                        val existing = updated[eventId]
                        if (existing != reactions) {
                            updated[eventId] = reactions
                            changed = true
                        }
                    }

                    if (changed) {
                        messageReactions = updated
                        reactionUpdateCounter++
                        android.util.Log.d("Andromuks", "AppViewModel: Restored reactions for ${reactionsByEvent.size} events in room $roomId")
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: Database reactions already match in-memory state for room $roomId")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to restore reactions from database for room $roomId", e)
            }
        }
    }

    private fun applyAggregatedReactionsFromEvents(events: List<TimelineEvent>, source: String) {
        if (events.isEmpty()) return

        val aggregatedByEvent = mutableMapOf<String, List<MessageReaction>>()
        for (event in events) {
            val reactionsObject = event.aggregatedReactions ?: continue
            val reactionList = mutableListOf<MessageReaction>()
            val keys = reactionsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                var count = 0
                when (val value = reactionsObject.opt(key)) {
                    is Number -> count = value.toInt()
                    is JSONObject -> count = value.optInt("count", 0)
                    else -> {
                        // Attempt fallback to optInt
                        count = reactionsObject.optInt(key, 0)
                    }
                }
                if (count > 0 && !key.isNullOrBlank()) {
                    reactionList.add(
                        MessageReaction(
                            emoji = key,
                            count = count,
                            users = emptyList()
                        )
                    )
                }
            }
            if (reactionList.isNotEmpty()) {
                aggregatedByEvent[event.eventId] = reactionList.sortedBy { it.emoji }
            }
        }

        if (aggregatedByEvent.isEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: applyAggregatedReactionsFromEvents($source) - no aggregated reactions found")
            return
        }

        val updated = messageReactions.toMutableMap()
        var changed = false

        for ((eventId, reactions) in aggregatedByEvent) {
            val existing = updated[eventId]
            if (existing == null || existing.isEmpty()) {
                updated[eventId] = reactions
                changed = true
            }
        }

        if (changed) {
            messageReactions = updated
            reactionUpdateCounter++
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Applied aggregated reactions from $source for ${aggregatedByEvent.size} events"
            )
        }
    }

    private fun eventEntityToTimelineEvent(entity: EventEntity): TimelineEvent? {
        return try {
            val json = JSONObject(entity.rawJson)
            json.put("rowid", entity.timelineRowId.toLong())
            json.put("timeline_rowid", entity.timelineRowId)
            json.put("room_id", entity.roomId)
            if (!json.has("origin_server_ts") || json.optLong("origin_server_ts") == 0L) {
                json.put("origin_server_ts", entity.timestamp)
            }
            if (!json.has("timestamp") || json.optLong("timestamp") == 0L) {
                json.put("timestamp", entity.timestamp)
            }
            if (entity.aggregatedReactionsJson != null) {
                val content = json.optJSONObject("content")
                if (content != null && !content.has("reactions")) {
                    content.put("reactions", JSONObject(entity.aggregatedReactionsJson))
                }
            }
            TimelineEvent.fromJson(json)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to convert EventEntity ${entity.eventId} to TimelineEvent", e)
            null
        }
    }

    suspend fun ensureTimelineCacheIsFresh(roomId: String, limit: Int = 200) {
        val context = appContext ?: return
        val cachedMetadata = RoomTimelineCache.getLatestCachedEventMetadata(roomId)

        withContext(Dispatchers.IO) {
            try {
                val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context.applicationContext)
                val eventDao = database.eventDao()
                val latestDbEntity = eventDao.getMostRecentEventForRoom(roomId)

                if (latestDbEntity == null) {
                    lastKnownDbLatestEventId.remove(roomId)
                    return@withContext
                }

                val latestDbEventId = latestDbEntity.eventId
                if (cachedMetadata?.eventId == latestDbEventId) {
                    lastKnownDbLatestEventId[roomId] = latestDbEventId
                    return@withContext
                }

                if (lastKnownDbLatestEventId[roomId] == latestDbEventId) {
                    return@withContext
                }

                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: Cache for $roomId is stale (cache=${cachedMetadata?.eventId}, db=$latestDbEventId). Re-seeding from database."
                )

                val recentEntities = eventDao.getEventsForRoomDesc(roomId, limit)
                if (recentEntities.isEmpty()) {
                    lastKnownDbLatestEventId[roomId] = latestDbEventId
                    return@withContext
                }

                val timelineEvents = recentEntities
                    .asReversed()
                    .mapNotNull { eventEntityToTimelineEvent(it) }
                    .sortedWith { a, b ->
                        when {
                            a.timelineRowid > 0 && b.timelineRowid > 0 -> a.timelineRowid.compareTo(b.timelineRowid)
                            a.timelineRowid > 0 -> -1
                            b.timelineRowid > 0 -> 1
                            else -> {
                                val tsCompare = a.timestamp.compareTo(b.timestamp)
                                if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                            }
                        }
                    }

                val firstDbEvent = timelineEvents.firstOrNull()
                val lastDbEvent = timelineEvents.lastOrNull()
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ensureTimelineCacheIsFresh($roomId) DB snapshot -> size=${timelineEvents.size}, first=${firstDbEvent?.eventId}@${firstDbEvent?.timelineRowid}, last=${lastDbEvent?.eventId}@${lastDbEvent?.timelineRowid}"
                )

                withContext(Dispatchers.Main) {
                    RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, timelineEvents)
                    roomsWithLoadedReceiptsFromDb.remove(roomId)
                    roomsWithLoadedReactionsFromDb.remove(roomId)
                    lastKnownDbLatestEventId[roomId] = latestDbEventId
                    applyAggregatedReactionsFromEvents(timelineEvents, "db reseed")
                    val latestCached = RoomTimelineCache.getLatestCachedEventMetadata(roomId)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Cache for $roomId refreshed from DB with ${timelineEvents.size} events (dbLatest=$latestDbEventId cacheLatest=${latestCached?.eventId})"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to verify timeline cache for $roomId", e)
            }
        }
    }

    suspend fun getRoomEventsFromDb(roomId: String, limit: Int): List<EventEntity> {
        val context = appContext ?: return emptyList()
        val safeLimit = limit.coerceAtLeast(1)
        return withContext(Dispatchers.IO) {
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context.applicationContext)
            database.eventDao().getEventsForRoomDesc(roomId, safeLimit)
        }
    }

    suspend fun getRoomEventCountFromDb(roomId: String): Int {
        val context = appContext ?: return 0
        return withContext(Dispatchers.IO) {
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context.applicationContext)
            database.eventDao().getEventCountForRoom(roomId)
        }
    }
    
    fun requestRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting timeline for room: $roomId")
        
        // Check if we're refreshing the same room before updating currentRoomId
        val isRefreshingSameRoom = currentRoomId == roomId && timelineEvents.isNotEmpty()
        
        // CRITICAL: Store room open timestamp when opening a room (not when refreshing the same room)
        // This timestamp will be used to determine which messages should animate
        // Only messages with timestamp NEWER than this will animate
        if (!isRefreshingSameRoom || !roomOpenTimestamps.containsKey(roomId)) {
            val openTimestamp = System.currentTimeMillis()
            roomOpenTimestamps[roomId] = openTimestamp
            android.util.Log.d("Andromuks", "AppViewModel: Stored room open timestamp for $roomId: $openTimestamp (only messages newer than this will animate)")
        }
        
        updateCurrentRoomIdInPrefs(roomId)
        
        // OPPORTUNISTIC PROFILE LOADING: Only request room state without members to prevent OOM
        // Member profiles will be loaded on-demand when actually needed for rendering
        if (webSocket != null && !pendingRoomStateRequests.contains(roomId)) {
            val stateRequestId = requestIdCounter++
            roomStateRequests[stateRequestId] = roomId
            pendingRoomStateRequests.add(roomId)
            android.util.Log.d("Andromuks", "AppViewModel: Requesting room state WITHOUT members to prevent OOM (reqId: $stateRequestId)")
            sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
                "room_id" to roomId,
                "include_members" to false,  // CRITICAL: Don't load all members
                "fetch_members" to false,
                "refetch" to false
            ))
        }
        
        // NAVIGATION PERFORMANCE: Check cached navigation state and use partial loading
        val navigationState = getRoomNavigationState(roomId)
        if (navigationState != null && navigationState.essentialDataLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Using cached navigation state for: $roomId")
            
            // Load additional details in background if needed
            loadRoomDetails(roomId, navigationState)
        }
        
        // Check if we're opening from a notification (for optimized cache handling)
        val openingFromNotification = isPendingNavigationFromNotification && pendingRoomNavigation == roomId
        
        // Check if we have enough cached events BEFORE clearing anything
        // Use more lenient threshold for notification-based navigation to avoid loading spinners
        var cachedEvents = if (openingFromNotification) {
            // First try the standard threshold, then fall back to notification threshold
            RoomTimelineCache.getCachedEvents(roomId) ?: RoomTimelineCache.getCachedEventsForNotification(roomId)
        } else {
            RoomTimelineCache.getCachedEvents(roomId)
        }
        
        // A: Loading from Memory Cache
        if (cachedEvents != null) {
            if (BuildConfig.DEBUG) {
                appContext?.let { context ->
                    android.widget.Toast.makeText(context, "A: Loading from Memory Cache", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            processCachedEvents(roomId, cachedEvents!!, openingFromNotification)
            return
        }
        
        // If cache miss, try loading from database (synchronously to avoid race condition)
        if (cachedEvents == null && appContext != null && bootstrapLoader != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Cache miss for $roomId, checking database...")
            try {
                // Use runBlocking to wait for database query (prevents race condition)
                val dbEvents = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    bootstrapLoader!!.loadRoomEvents(roomId, 200)
                }
                
                if (dbEvents.isNotEmpty()) {
                    android.util.Log.d("Andromuks", "AppViewModel: Found ${dbEvents.size} events in database for $roomId")
                    // Seed cache with DB events
                    RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, dbEvents)
                    
                    // Check if we now have enough events in RAM after loading from disk
                    val eventsInRamAfterDiskLoad = RoomTimelineCache.getCachedEventCount(roomId)
                    android.util.Log.d("Andromuks", "AppViewModel: After loading from disk, RAM cache has $eventsInRamAfterDiskLoad events")
                    
                    if (eventsInRamAfterDiskLoad >= 200) {
                        // We have enough events, show them
                        // B: Loading from Disk Storage
                        if (BuildConfig.DEBUG) {
                            appContext?.let { context ->
                                android.widget.Toast.makeText(context, "B: Loading from Disk Storage", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        cachedEvents = dbEvents
                        processCachedEvents(roomId, cachedEvents!!, openingFromNotification)
                        return
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: Only have $eventsInRamAfterDiskLoad events after disk load - waiting for manual refresh to fetch more")
                        cachedEvents = dbEvents
                        processCachedEvents(roomId, cachedEvents!!, openingFromNotification)
                        return
                    }
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: No events in database for $roomId, will send paginate request")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error loading events from database: ${e.message}", e)
            }
        }
        
        if (cachedEvents != null) {
            processCachedEvents(roomId, cachedEvents!!, openingFromNotification)
            return
        }
        
        // Check if we have partial cache (10-99 events) - show it and background prefetch more
        val partialCacheCount = RoomTimelineCache.getCachedEventCount(roomId)
        if (partialCacheCount >= 10) {
            android.util.Log.d("Andromuks", "AppViewModel: 🔄 PARTIAL CACHE ($partialCacheCount events) - showing cached content and background prefetching more")
            
            // Show the partial cache immediately (like cache hit)
            val partialCachedEvents = RoomTimelineCache.getCachedEventsForNotification(roomId)
            if (partialCachedEvents != null) {
                val ownMessagesInCache = partialCachedEvents.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
                android.util.Log.d("Andromuks", "AppViewModel: ✓ Showing partial cache with ${partialCachedEvents.size} events (including $ownMessagesInCache of your own messages)")
                
                // Ensure loading is false BEFORE processing to prevent loading flash
                isTimelineLoading = false
                
                // Clear and rebuild internal structures (but don't clear timelineEvents yet)
                eventChainMap.clear()
                editEventsMap.clear()
                messageVersions.clear()
                editToOriginal.clear()
                redactionCache.clear()
                messageReactions = emptyMap()
                roomsWithLoadedReactionsFromDb.remove(roomId)
                roomsWithLoadedReactionsFromDb.remove(roomId)
                
                // Reset pagination state
                smallestRowId = -1L
                isPaginating = false
                hasMoreMessages = true
                
                // Ensure member cache exists for this room
                if (roomMemberCache[roomId] == null) {
                    roomMemberCache[roomId] = ConcurrentHashMap()
                }
                
                // OPTIMIZED: Populate edit chain mapping from cached events in background if large
                if (partialCachedEvents.size > 100) {
                    // Use background thread for large event processing
                    viewModelScope.launch(Dispatchers.Default) {
                        for (event in partialCachedEvents) {
                            val isEditEvent = isEditEvent(event)
                            
                            if (isEditEvent) {
                                editEventsMap[event.eventId] = event
                            } else {
                                eventChainMap[event.eventId] = EventChainEntry(
                                    eventId = event.eventId,
                                    ourBubble = event,
                                    replacedBy = null,
                                    originalTimestamp = event.timestamp
                                )
                            }
                        }
                        
                        // Process edit relationships on background thread
                        processEditRelationships()
                    }
                } else {
                    // Synchronous processing for small batches
                    for (event in partialCachedEvents) {
                        val isEditEvent = isEditEvent(event)
                        
                        if (isEditEvent) {
                            editEventsMap[event.eventId] = event
                        } else {
                            eventChainMap[event.eventId] = EventChainEntry(
                                eventId = event.eventId,
                                ourBubble = event,
                                replacedBy = null,
                                originalTimestamp = event.timestamp
                            )
                        }
                    }
                    
                    // Process edit relationships
                    processEditRelationships()
                }
                
                // Build timeline from chain (this updates timelineEvents)
                buildTimelineFromChain()
                
                // Set smallest rowId from cached events for pagination
                val smallestCached = partialCachedEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
                if (smallestCached > 0) {
                    smallestRowId = smallestCached
                }
                
                android.util.Log.d("Andromuks", "AppViewModel: ✅ Room opened with partial cache (${timelineEvents.size} events) - background prefetch initiated")
                
                // Clear notification flag since we've successfully loaded the room
                if (openingFromNotification) {
                    isPendingNavigationFromNotification = false
                }
                
                // NAVIGATION PERFORMANCE: Only request missing data for partial cache opening
                val partialNavigationState = getRoomNavigationState(roomId)
                
                // Only request essential room state if not already loaded
                if (partialNavigationState?.essentialDataLoaded != true && !pendingRoomStateRequests.contains(roomId)) {
                    requestRoomState(roomId)
                }
                
                // OPPORTUNISTIC PROFILE LOADING: Skip member data loading to prevent OOM
                // Member profiles will be loaded on-demand when actually needed for rendering
                if (partialNavigationState?.memberDataLoaded != true) {
                    android.util.Log.d("Andromuks", "AppViewModel: SKIPPING member data loading (using opportunistic loading)")
                    // Mark as loaded to prevent repeated attempts
                    navigationCache[roomId] = partialNavigationState?.copy(memberDataLoaded = true) ?: RoomNavigationState(roomId, memberDataLoaded = true)
                }
                
                // Mark as read
                val mostRecentEvent = partialCachedEvents.maxByOrNull { it.timestamp }
                if (mostRecentEvent != null) {
                    markRoomAsRead(roomId, mostRecentEvent.eventId)
                }
                
                return // Exit early - room is shown with partial cache
            }
        }
        
        // CACHE MISS - show loading and fetch from server
        android.util.Log.d("Andromuks", "AppViewModel: ✗ CACHE MISS (or < 10 events) - showing loading and fetching from server")
        
        // C: Requesting from Backend
        if (BuildConfig.DEBUG) {
            appContext?.let { context ->
                android.widget.Toast.makeText(context, "C: Requesting from Backend", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        // Only clear timeline if we're opening a different room, or if timeline is already empty
        // This prevents clearing timeline when resuming from background for the same room
        if (isRefreshingSameRoom) {
            android.util.Log.d("Andromuks", "AppViewModel: Preserving existing timeline on resume (${timelineEvents.size} events) - will fetch new events in background")
            // Don't clear timelineEvents - keep existing events visible
            // Don't set loading to true - keep UI responsive
            // Don't clear internal structures - they're still needed for the existing timeline
            isTimelineLoading = false
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Clearing timeline - opening new room or timeline empty")
            timelineEvents = emptyList()
            // PHASE 1: Update Repository in parallel with AppViewModel
            if (currentRoomId.isNotEmpty()) {
                RoomRepository.clearTimeline(currentRoomId)
            }
            isTimelineLoading = true
            
            // Reset pagination state for new room
            smallestRowId = -1L
            isPaginating = false
            hasMoreMessages = true
            
            // Clear edit chain mapping when opening a new room
            eventChainMap.clear()
            editEventsMap.clear()
            
            // Clear optimized version cache when opening a new room
            messageVersions.clear()
            editToOriginal.clear()
            redactionCache.clear()
            android.util.Log.d("Andromuks", "AppViewModel: Cleared version cache for new room: $roomId")
            
            // Clear message reactions when switching rooms
            messageReactions = emptyMap()
            roomsWithLoadedReactionsFromDb.remove(roomId)
        }
        
        // Ensure member cache exists for this room
        if (roomMemberCache[roomId] == null) {
            roomMemberCache[roomId] = ConcurrentHashMap()
        }
        
        // NAVIGATION PERFORMANCE: Partial loading - only request what's not already available
        val missNavigationState = getRoomNavigationState(roomId)
        
        // Load essential data first (room state without members) - only if not already loaded
        if (missNavigationState?.essentialDataLoaded != true && !pendingRoomStateRequests.contains(roomId)) {
            requestRoomState(roomId)
            android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Requested essential room state")
        }
        
        // OPPORTUNISTIC PROFILE LOADING: Skip member data loading to prevent OOM
        // Member profiles will be loaded on-demand when actually needed for rendering
        if (missNavigationState?.memberDataLoaded != true) {
            android.util.Log.d("Andromuks", "AppViewModel: SKIPPING member data loading (using opportunistic loading)")
            // Mark as loaded to prevent repeated attempts
            navigationCache[roomId] = missNavigationState?.copy(memberDataLoaded = true) ?: RoomNavigationState(roomId, memberDataLoaded = true)
        }
        
        val currentCachedCount = RoomTimelineCache.getCachedEventCount(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION NOTE - Cache miss with $currentCachedCount events available. Waiting for manual refresh to request additional history.")
        isTimelineLoading = false
    }
    
    /**
     * Fully refreshes the room timeline by resetting in-memory state and fetching a clean snapshot.
     * Steps:
     * 1. Marks the room as the current timeline so downstream handlers know which room is active
     * 2. Clears all RAM caches and timeline bookkeeping for the room
     * 3. Resets pagination flags
     * 4. Requests fresh room state
     * 5. Sends a paginate command for up to 200 events (ingest pipeline upserts the database)
     * 6. Flags the room for a database-backed rehydrate once new data is persisted
     */
    fun fullRefreshRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Full refresh for room: $roomId (resetting caches and requesting fresh snapshot)")
        setAutoPaginationEnabled(false, "manual_refresh_$roomId")
        
        if (hasInitialPaginate(roomId)) {
            logSkippedPaginate(roomId, "full_refresh")
            viewModelScope.launch {
                ensureTimelineCacheIsFresh(roomId)
            }
            return
        }
        
        // 1. Mark room as current so sync handlers and pagination know which timeline is active
        updateCurrentRoomIdInPrefs(roomId)
        roomsPendingDbRehydrate.add(roomId)
        
        // 2. Wipe in-memory cache/state for this room
        RoomTimelineCache.clearRoomCache(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Cleared timeline cache for room: $roomId")
        
        timelineEvents = emptyList()
        if (currentRoomId.isNotEmpty()) {
            RoomRepository.clearTimeline(currentRoomId)
        }
        isTimelineLoading = true
        
        // 3. Reset pagination flags and bookkeeping
        smallestRowId = -1L
        isPaginating = false
        hasMoreMessages = true
        
        eventChainMap.clear()
        editEventsMap.clear()
        messageVersions.clear()
        editToOriginal.clear()
        redactionCache.clear()
        messageReactions = emptyMap()
        
        // Clear animation and room-open tracking state
        newMessageAnimations.clear()
        runningBubbleAnimations.clear()
        bubbleAnimationCompletionCounter = 0L
        newMessageAnimationTrigger = 0L
        animationsEnabledForRoom.remove(roomId)
        roomOpenTimestamps.remove(roomId)
        
        // Reset member update counter to avoid stale diffs
        memberUpdateCounter = 0
        
        // 4. Request fresh room state
        requestRoomState(roomId)
        
        // 5. Request up to 200 events from the backend; ingest path will upsert into the database
        val paginateRequestId = requestIdCounter++
        timelineRequests[paginateRequestId] = roomId
        val result = sendWebSocketCommand(
            "paginate",
            paginateRequestId,
            mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 200,
            "reset" to false
            )
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent paginate request for room: $roomId (200 events) - awaiting response to rebuild timeline")
        if (result == WebSocketResult.SUCCESS) {
            markInitialPaginate(roomId, "full_refresh")
        } else {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Failed to send full refresh paginate for $roomId (result=$result)"
            )
        }
    }
    
    private fun scheduleRoomRehydrateFromDb(roomId: String) {
        if (!roomsPendingDbRehydrate.contains(roomId)) {
            return
        }
        
        synchronized(roomRehydrateJobs) {
            if (roomRehydrateJobs.containsKey(roomId)) {
                return
            }
            
            val job = viewModelScope.launch(Dispatchers.IO) {
                val maxAttempts = 5
                var attempt = 0
                
                while (roomsPendingDbRehydrate.contains(roomId) && attempt < maxAttempts && isActive) {
                    val delayMs = 500L * (attempt + 1)
                    android.util.Log.d("Andromuks", "AppViewModel: Waiting ${delayMs}ms before DB rehydrate attempt ${attempt + 1} for room $roomId")
                    delay(delayMs)
                    
                    val loader = ensureBootstrapLoader()
                    if (loader == null) {
                        android.util.Log.w("Andromuks", "AppViewModel: Cannot rehydrate $roomId from DB - bootstrapLoader unavailable")
                        break
                    }
                    
                    val dbEvents = try {
                        loader.loadRoomEvents(roomId, 200)
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error loading events from database for $roomId during rehydrate: ${e.message}", e)
                        emptyList()
                    }
                    
                    if (dbEvents.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            android.util.Log.d("Andromuks", "AppViewModel: Rehydrating $roomId from database with ${dbEvents.size} events")
                            roomsPendingDbRehydrate.remove(roomId)
                            RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, dbEvents)
                            processCachedEvents(
                                roomId = roomId,
                                cachedEvents = dbEvents,
                                openingFromNotification = false,
                                skipNetworkRequests = true
                            )
                        }
                        break
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: Rehydrate attempt ${attempt + 1} for $roomId returned no events from DB")
                        attempt++
                    }
                }
                
                if (roomsPendingDbRehydrate.contains(roomId)) {
                    android.util.Log.w("Andromuks", "AppViewModel: Unable to rehydrate $roomId from database after $maxAttempts attempts")
                    roomsPendingDbRehydrate.remove(roomId)
                }
                
                synchronized(roomRehydrateJobs) {
                    roomRehydrateJobs.remove(roomId)
                }
            }
            
            roomRehydrateJobs[roomId] = job
        }
    }
    
    /**
     * Silently refreshes the room cache without updating the UI.
     * This populates the cache with fresh data but doesn't trigger any UI updates or scrolling.
     */
    fun silentRefreshRoomCache(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Silent refresh for room: $roomId (populating cache without UI updates)")
        
        // Set current room ID to ensure proper request handling
        updateCurrentRoomIdInPrefs(roomId)
        
        // Clear cache to get fresh data
        RoomTimelineCache.clearRoomCache(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Cleared timeline cache for room: $roomId")
        
        // Clear animation state to prevent corruption
        newMessageAnimations.clear()
        runningBubbleAnimations.clear()
        bubbleAnimationCompletionCounter = 0L
        newMessageAnimationTrigger = 0L
        animationsEnabledForRoom.remove(roomId) // Reset animation state for this room
        roomOpenTimestamps.remove(roomId) // Clear room open timestamp
        android.util.Log.d("Andromuks", "AppViewModel: Cleared animation state for room: $roomId")
        
        // Reset member update counter to prevent stale state (but keep timelineUpdateCounter for UI consistency)
        memberUpdateCounter = 0
        android.util.Log.d("Andromuks", "AppViewModel: Reset member update counter for room: $roomId")
        
        // Reset all update flags and state hashes to prevent stale diff detection
        needsTimelineUpdate = false
        needsMemberUpdate = false
        needsRoomListUpdate = false
        needsReactionUpdate = false
        lastTimelineStateHash = ""
        lastMemberStateHash = ""
        lastRoomStateHash = ""
        android.util.Log.d("Andromuks", "AppViewModel: Reset all update flags and state hashes for room: $roomId")
        
        // Request fresh room state
        requestRoomState(roomId)
        
        // Send fresh paginate command to get consistent data from server (silent)
        // Request 200 events to ensure we have enough
        val paginateRequestId = requestIdCounter++
        backgroundPrefetchRequests[paginateRequestId] = roomId  // Use backgroundPrefetchRequests for silent processing
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 200,
            "reset" to false
        ))
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent silent paginate request for room: $roomId (200 events)")
    }

    /**
     * Refreshes the room timeline by clearing cache and requesting fresh data from server.
     * This is useful for debugging missing events (e.g., messages from other devices).
     */
    fun refreshRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Refreshing timeline for room: $roomId (clearing cache and requesting fresh data)")
        
        if (hasInitialPaginate(roomId)) {
            logSkippedPaginate(roomId, "refresh_timeline")
            viewModelScope.launch {
                ensureTimelineCacheIsFresh(roomId)
            }
            isTimelineLoading = false
            return
        }
        
        // Set current room ID to ensure reaction processing works correctly
        updateCurrentRoomIdInPrefs(roomId)
        
        // 1. Drop all cache for this room
        RoomTimelineCache.clearRoomCache(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Cleared timeline cache for room: $roomId")
        
        // 2. Clear current timeline state
        timelineEvents = emptyList()
        // PHASE 1: Update Repository in parallel with AppViewModel
        if (currentRoomId.isNotEmpty()) {
            RoomRepository.clearTimeline(currentRoomId)
        }
        isTimelineLoading = true
        
        // 3. Reset pagination state
        smallestRowId = -1L
        isPaginating = false
        hasMoreMessages = true
        
        // 4. Clear edit chain mapping and version cache
        eventChainMap.clear()
        editEventsMap.clear()
        messageVersions.clear()
        editToOriginal.clear()
        redactionCache.clear()
        android.util.Log.d("Andromuks", "AppViewModel: Cleared version cache for room: $roomId")
        
        // 5. Clear message reactions
        messageReactions = emptyMap()
        roomsWithLoadedReceiptsFromDb.remove(roomId)
        roomsWithLoadedReactionsFromDb.remove(roomId)
        lastKnownDbLatestEventId.remove(roomId)
        
        // 6. Clear animation state to prevent corruption
        newMessageAnimations.clear()
        runningBubbleAnimations.clear()
        bubbleAnimationCompletionCounter = 0L
        newMessageAnimationTrigger = 0L
        android.util.Log.d("Andromuks", "AppViewModel: Cleared animation state for room: $roomId")
        
        // 7. Reset member update counter to prevent stale state (but keep timelineUpdateCounter for UI consistency)
        memberUpdateCounter = 0
        android.util.Log.d("Andromuks", "AppViewModel: Reset member update counter for room: $roomId")
        
        // 8. Request fresh room state
        requestRoomState(roomId)
        
        // 9. Send fresh paginate command to get consistent data from server
        // Request 200 events to ensure we have enough
        val paginateRequestId = requestIdCounter++
        timelineRequests[paginateRequestId] = roomId
        val result = sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 200,
            "reset" to false
        ))
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent fresh paginate request for room: $roomId (200 events)")
        if (result == WebSocketResult.SUCCESS) {
            markInitialPaginate(roomId, "refresh_timeline")
        } else {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Failed to send refresh paginate for $roomId (result=$result)"
            )
        }
    }
    
    suspend fun prefetchRoomSnapshot(roomId: String, limit: Int = 200, timeoutMs: Long = 6000L): Boolean {
        if (hasInitialPaginate(roomId)) {
            logSkippedPaginate(roomId, "prefetch_snapshot")
            return true
        }

        val ws = webSocket ?: run {
            android.util.Log.w("Andromuks", "AppViewModel: Cannot prefetch snapshot for $roomId - WebSocket not connected")
            return false
        }
        
        val deferred = CompletableDeferred<Unit>()
        registerRoomSnapshotAwaiter(roomId, deferred)
        
        val requestId = requestIdCounter++
        backgroundPrefetchRequests[requestId] = roomId
        android.util.Log.d(
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
            android.util.Log.d("Andromuks", "AppViewModel: Prefetch snapshot complete for room $roomId")
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
    
    // OPTIMIZATION #4: Cache-first navigation method
    fun navigateToRoomWithCache(roomId: String) {
        updateCurrentRoomIdInPrefs(roomId)
        
        // Check cache first - this is the key optimization
        val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
        
        // DEBUG: Let's see what's in the cache at this moment
        
        
        // OPTIMIZATION #4: Use the exact same logic as requestRoomTimeline for consistency
        if (cachedEventCount >= 10) {
            // OPTIMIZATION #4: Use cached data immediately (same threshold as requestRoomTimeline)

            
                // Get cached events using the same method as requestRoomTimeline
                val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)

            
            if (cachedEvents != null) {

                
                // Set loading to false immediately to prevent loading flash
                isTimelineLoading = false
                
                // Clear and rebuild internal structures
                eventChainMap.clear()
                editEventsMap.clear()
                messageVersions.clear()
                editToOriginal.clear()
                redactionCache.clear()
                messageReactions = emptyMap()
                
                // Reset pagination state
                smallestRowId = -1L
                isPaginating = false
                hasMoreMessages = true
                
                // Ensure member cache exists for this room
                if (roomMemberCache[roomId] == null) {
                    roomMemberCache[roomId] = ConcurrentHashMap()
                }
                
                // Populate edit chain mapping from cached events
                for (event in cachedEvents) {
                    val isEditEvent = when {
                        event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                        else -> false
                    }
                    
                    if (isEditEvent) {
                        editEventsMap[event.eventId] = event
                    } else {
                        eventChainMap[event.eventId] = EventChainEntry(
                            eventId = event.eventId,
                            ourBubble = event,
                            replacedBy = null,
                            originalTimestamp = event.timestamp
                        )
                    }
                }
                
                // Process edit relationships
                processEditRelationships()
                
                // Build timeline from chain (this updates timelineEvents)
                buildTimelineFromChain()
                
                // Set smallest rowId from cached events for pagination
                val smallestCached = cachedEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
                if (smallestCached > 0) {
                    smallestRowId = smallestCached
                }

                // Historical reactions are not cached, request them in the background
                if (REACTION_BACKFILL_ON_OPEN_ENABLED) {
                    requestHistoricalReactions(roomId, smallestCached)
                }
                
                
                // Request room state in background if needed
                if (webSocket != null && !pendingRoomStateRequests.contains(roomId)) {
                    val stateRequestId = requestIdCounter++
                    roomStateRequests[stateRequestId] = roomId
                    pendingRoomStateRequests.add(roomId)
                    sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
                        "room_id" to roomId,
                        "include_members" to false,
                        "fetch_members" to false,
                        "refetch" to false
                    ))
                }
                
                // Mark as read
                val mostRecentEvent = cachedEvents.maxByOrNull { it.timestamp }
                if (mostRecentEvent != null) {
                    markRoomAsRead(roomId, mostRecentEvent.eventId)
                }
                
                return // Exit early - room is already rendered from cache
            }
        }
        
        // OPTIMIZATION #4: Fallback to regular requestRoomTimeline if no cache
        requestRoomTimeline(roomId)
    }

    private fun requestHistoricalReactions(roomId: String, smallestCached: Long) {
        if (hasInitialPaginate(roomId)) {
            logSkippedPaginate(roomId, "historical_reactions")
            return
        }
        val reactionRequestId = requestIdCounter++
        backgroundPrefetchRequests[reactionRequestId] = roomId
        val effectiveMaxTimelineId = if (smallestCached > 0) smallestCached else 0L
        android.util.Log.d("Andromuks", "AppViewModel: About to send reaction request - currentRoomId: $currentRoomId, roomId=$roomId, smallestCached=$smallestCached, effectiveMaxTimelineId=$effectiveMaxTimelineId")
        val result = sendWebSocketCommand(
            "paginate",
            reactionRequestId,
            mapOf(
                "room_id" to roomId,
                "max_timeline_id" to effectiveMaxTimelineId,
                "limit" to 100,
                "reset" to false
            )
        )
        if (result == WebSocketResult.SUCCESS) {
            android.util.Log.d("Andromuks", "AppViewModel: ✅ Sent reaction request for cached room: $roomId (requestId: $reactionRequestId)")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Reaction request for $roomId (requestId: $reactionRequestId) could not be sent immediately (result=$result)")
        }
    }
    
    fun requestRoomState(roomId: String) {
        // PERFORMANCE: Prevent duplicate room state requests for the same room
        if (pendingRoomStateRequests.contains(roomId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Room state request already pending for $roomId, skipping duplicate")
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
        android.util.Log.d("Andromuks", "AppViewModel: Requesting room state with members for room: $roomId")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with error, health monitor will handle reconnection")
            callback(null, "WebSocket not connected")
            return
        }
        
        val stateRequestId = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_room_state with members: $stateRequestId")
        
        // Store the callback to handle the response
        roomStateWithMembersRequests[stateRequestId] = callback
        
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to true,
            "fetch_members" to false,
            "refetch" to false
        ))
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $stateRequestId")
    }
    
    /**
     * OPPORTUNISTIC PROFILE LOADING: Request profile for a single user only when needed for rendering.
     * This prevents loading 15,000+ profiles upfront and only loads what's actually displayed.
     */
    private fun logProfileCacheStats(source: String) {
        val totalProfiles = globalProfileCache.size + flattenedMemberCache.size
        val flattenedBytes = flattenedMemberCache.entries.sumOf { (key, profile) ->
            estimateProfileEntryBytes(key, profile)
        }
        val globalBytes = globalProfileCache.entries.sumOf { (key, entry) ->
            estimateProfileEntryBytes(key, entry.profile)
        }
        val runtime = Runtime.getRuntime()
        val usedMem = runtime.totalMemory() - runtime.freeMemory()
        android.util.Log.d(
            "Andromuks",
            "AppViewModel: ProfileCacheStats[$source] - totalProfiles=$totalProfiles, flattened=${flattenedMemberCache.size} (~${formatBytes(flattenedBytes)}), global=${globalProfileCache.size} (~${formatBytes(globalBytes)}), usedMem=${formatBytes(usedMem)}"
        )
    }

    private fun estimateProfileEntryBytes(key: String, profile: MemberProfile): Long {
        var bytes = (key.length * 2).toLong()
        profile.displayName?.let { bytes += it.length * 2L }
        profile.avatarUrl?.let { bytes += it.length * 2L }
        // overhead fudge factor
        bytes += 64
        return bytes
    }

    fun requestUserProfileOnDemand(userId: String, roomId: String) {
        // Check if we already have this profile in cache
        val existingProfile = getUserProfile(userId, roomId)
        if (existingProfile != null) {
            //android.util.Log.d("Andromuks", "AppViewModel: Profile already cached for $userId, skipping request")
            return
        }
        
        val requestKey = "$roomId:$userId"
        
        fun enqueueNetworkRequest() {
            // Avoid duplicate network requests
        if (pendingProfileRequests.contains(requestKey)) {
            android.util.Log.d("Andromuks", "AppViewModel: Profile request already pending for $userId, skipping duplicate")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        val lastRequestTime = recentProfileRequestTimes[requestKey]
        if (lastRequestTime != null && (currentTime - lastRequestTime) < PROFILE_REQUEST_THROTTLE_MS) {
            android.util.Log.d("Andromuks", "AppViewModel: Profile request throttled for $userId (requested ${currentTime - lastRequestTime}ms ago)")
            return
        }
        
        // Clean up old throttle entries (older than throttle window) to prevent memory leaks
        val cutoffTime = currentTime - PROFILE_REQUEST_THROTTLE_MS
        recentProfileRequestTimes.entries.removeAll { (_, timestamp) -> timestamp < cutoffTime }
        
            android.util.Log.d("Andromuks", "AppViewModel: Requesting profile on-demand (network) for $userId in room $roomId")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping on-demand profile request")
            return
        }
        
        val requestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingProfileRequests.add(requestKey)
        recentProfileRequestTimes[requestKey] = currentTime // Record request time for throttling
        roomSpecificStateRequests[requestId] = roomId  // Use roomSpecificStateRequests for get_specific_room_state responses
        
        // Request specific room state for this user
        sendWebSocketCommand("get_specific_room_state", requestId, mapOf(
            "keys" to listOf(mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            ))
        ))
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent on-demand profile request with ID $requestId for $userId")
    }
    
        val context = appContext
        if (context != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val diskProfile = loadProfileFromDatabase(userId)
                if (diskProfile != null) {
                    withContext(Dispatchers.Main) {
                        storeMemberProfile(roomId, userId, diskProfile)
                        updateGlobalProfile(userId, diskProfile)
                        //logProfileCacheStats("disk-restore:$userId")
                        needsMemberUpdate = true
                        scheduleUIUpdate("member")
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    enqueueNetworkRequest()
                    //logProfileCacheStats("enqueue-network:$userId")
                }
            }
        } else {
            enqueueNetworkRequest()
            //logProfileCacheStats("enqueue-network:$userId")
        }
    }
    /**
     * Requests updated profile information for users in a room using get_specific_room_state.
     * This is used to refresh stale profile cache data when opening a room.
     * The room will render immediately with cached data, then update as fresh data arrives.
     */
    fun requestUpdatedRoomProfiles(roomId: String, timelineEvents: List<TimelineEvent>) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting updated room profiles for room: $roomId")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping profile refresh")
            return
        }
        
        // Extract unique user IDs from timeline events
        val userIds = timelineEvents
            .map { it.sender }
            .distinct()
            .filter { !it.isBlank() && it != currentUserId } // Exclude current user and blanks
        
        if (userIds.isEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: No users found in timeline events, skipping profile refresh")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Requesting profile updates for ${userIds.size} users: $userIds")
        
        // Build the keys array for get_specific_room_state
        val keys = userIds.map { userId ->
            mapOf(
                "room_id" to roomId,
                "type" to "m.room.member",
                "state_key" to userId
            )
        }
        
        val requestId = requestIdCounter++
        roomSpecificStateRequests[requestId] = roomId
        
        // Build JSON structure manually to ensure proper array handling
        val ws = webSocket
        if (ws != null) {
            val json = org.json.JSONObject()
            json.put("command", "get_specific_room_state")
            json.put("request_id", requestId)
            
            val data = org.json.JSONObject()
            val keysArray = org.json.JSONArray()
            
            for (key in keys) {
                val keyObj = org.json.JSONObject()
                keyObj.put("room_id", key["room_id"])
                keyObj.put("type", key["type"])
                keyObj.put("state_key", key["state_key"])
                keysArray.put(keyObj)
            }
            
            data.put("keys", keysArray)
            json.put("data", data)
            
            val jsonString = json.toString()
            android.util.Log.d("Andromuks", "AppViewModel: Sending get_specific_room_state: $jsonString")
            ws.send(jsonString)
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent get_specific_room_state request with ID $requestId for ${keys.size} members")
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
        if (webSocket == null) {
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
    
    fun sendTyping(roomId: String) {
        // PERFORMANCE: Rate limit typing indicators to reduce WebSocket traffic
        val currentTime = System.currentTimeMillis()
        val lastSent = lastTypingSent[roomId] ?: 0L
        if (currentTime - lastSent < TYPING_SEND_INTERVAL) {
            android.util.Log.d("Andromuks", "AppViewModel: Typing indicator rate limited for room: $roomId (last sent ${currentTime - lastSent}ms ago)")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Sending typing indicator for room: $roomId")
        val typingRequestId = requestIdCounter++
        val result = sendWebSocketCommand("set_typing", typingRequestId, mapOf(
            "room_id" to roomId,
            "timeout" to 10000
        ))
        
        if (result == WebSocketResult.SUCCESS) {
            lastTypingSent[roomId] = currentTime
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Typing indicator failed (result: $result), skipping")
        }
    }
    
    fun sendMessage(roomId: String, text: String) {
        android.util.Log.d("Andromuks", "AppViewModel: sendMessage called with roomId: '$roomId', text: '$text'")
        
        // Try to send the message immediately
        val result = sendMessageInternal(roomId, text)
        
        // If WebSocket is not available, queue the operation for retry when connection is restored
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: sendMessage failed with result: $result - queuing for retry when connection is restored")
            pendingWebSocketOperations.add(
                PendingWebSocketOperation(
                    type = "sendMessage",
                    data = mapOf(
                        "roomId" to roomId,
                        "text" to text
                    )
                )
            )
        }
    }
    
    private fun sendMessageInternal(roomId: String, text: String): WebSocketResult {
        android.util.Log.d("Andromuks", "AppViewModel: sendMessageInternal called")
        val messageRequestId = requestIdCounter++
        
        val result = sendWebSocketCommand("send_message", messageRequestId, mapOf(
            "room_id" to roomId,
            "text" to text,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        ))
        
        if (result == WebSocketResult.SUCCESS) {
            messageRequests[messageRequestId] = roomId
            pendingSendCount++
            android.util.Log.d("Andromuks", "AppViewModel: Message send queued with request_id: $messageRequestId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send message, result: $result")
        }
        
        return result
    }
    
    /**
     * Sends a message from a notification action.
     * This handles websocket connection state and schedules auto-shutdown if needed.
     */
    fun sendMessageFromNotification(roomId: String, text: String, onComplete: (() -> Unit)? = null) {
        android.util.Log.d("Andromuks", "AppViewModel: sendMessageFromNotification called for room $roomId")
        
        // Check websocket state
        if (webSocket == null || !spacesLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket not ready yet, queueing notification action")
            
            // Queue the action to be executed when WebSocket is ready
            // (Foreground service maintains connection, this should be rare - only during initial startup)
            pendingNotificationActions.add(
                PendingNotificationAction(
                    type = "send_message",
                    roomId = roomId,
                    text = text,
                    onComplete = onComplete
                )
            )
            
            // If callback provided and WebSocket not ready, call it immediately to prevent UI stalling
            if (onComplete != null) {
                android.util.Log.w("Andromuks", "AppViewModel: WebSocket not ready, calling completion callback immediately to prevent UI stalling")
                onComplete()
            }
            return
        }
        
        // WebSocket is ready (maintained by foreground service), send message directly
        android.util.Log.d("Andromuks", "AppViewModel: Sending message from notification (WebSocket maintained by service)")
        val messageRequestId = requestIdCounter++
        
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        if (onComplete != null) {
            notificationActionCompletionCallbacks[messageRequestId] = onComplete
            
            // Set up timeout to prevent infinite stalling - use IO dispatcher to avoid background throttling
            // Use shorter timeout when app is in background to handle throttling issues
            viewModelScope.launch(Dispatchers.IO) {
                val timeoutMs = if (isAppVisible) 30000L else 10000L // 10s timeout in background
                android.util.Log.d("Andromuks", "AppViewModel: Setting message send timeout to ${timeoutMs}ms (app visible: $isAppVisible)")
                delay(timeoutMs)
                // Switch to Main dispatcher only for the final callback
                withContext(Dispatchers.Main) {
                    if (notificationActionCompletionCallbacks.containsKey(messageRequestId)) {
                        android.util.Log.w("Andromuks", "AppViewModel: Message send timeout after ${timeoutMs}ms for requestId=$messageRequestId, calling completion callback")
                        notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
                        // Also clean up from messageRequests and pendingSendCount
                        messageRequests.remove(messageRequestId)
                        if (pendingSendCount > 0) {
                            pendingSendCount--
                        }
                    }
                }
            }
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        val result = sendWebSocketCommand("send_message", messageRequestId, commandData)
        
        // Handle immediate failure cases
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to send message from notification, result: $result")
            messageRequests.remove(messageRequestId)
            if (pendingSendCount > 0) {
                pendingSendCount--
            }
            notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
            return
        }
        
        // No shutdown needed - foreground service keeps WebSocket open
        android.util.Log.d("Andromuks", "AppViewModel: Message sent, WebSocket remains connected via service")
    }
    
    /**
     * Marks a room as read from a notification action.
     * Uses the always-connected WebSocket maintained by the foreground service.
     */
    fun markRoomAsReadFromNotification(roomId: String, eventId: String, onComplete: (() -> Unit)? = null) {
        android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadFromNotification called for room $roomId")
        
        // Check websocket state
        if (webSocket == null || !spacesLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket not ready yet, queueing notification action")
            
            // Queue the action to be executed when WebSocket is ready
            // (Foreground service maintains connection, this should be rare - only during initial startup)
            pendingNotificationActions.add(
                PendingNotificationAction(
                    type = "mark_read",
                    roomId = roomId,
                    eventId = eventId,
                    onComplete = onComplete
                )
            )
            
            // If callback provided and WebSocket not ready, call it immediately to prevent UI stalling
            if (onComplete != null) {
                android.util.Log.w("Andromuks", "AppViewModel: WebSocket not ready, calling completion callback immediately to prevent UI stalling")
                onComplete()
            }
            return
        }
        
        // WebSocket is ready (maintained by foreground service), mark as read directly
        android.util.Log.d("Andromuks", "AppViewModel: Marking room as read from notification (WebSocket maintained by service)")
        val markReadRequestId = requestIdCounter++
        
        markReadRequests[markReadRequestId] = roomId
        if (onComplete != null) {
            notificationActionCompletionCallbacks[markReadRequestId] = onComplete
            
            // Set up timeout to prevent infinite stalling - use IO dispatcher to avoid background throttling
            // Use shorter timeout when app is in background to handle throttling issues
            viewModelScope.launch(Dispatchers.IO) {
                val timeoutMs = if (isAppVisible) 30000L else 10000L // 10s timeout in background
                android.util.Log.d("Andromuks", "AppViewModel: Setting mark read timeout to ${timeoutMs}ms (app visible: $isAppVisible)")
                delay(timeoutMs)
                // Switch to Main dispatcher only for the final callback
                withContext(Dispatchers.Main) {
                    if (notificationActionCompletionCallbacks.containsKey(markReadRequestId)) {
                        android.util.Log.w("Andromuks", "AppViewModel: Mark read timeout after ${timeoutMs}ms for requestId=$markReadRequestId, calling completion callback")
                        notificationActionCompletionCallbacks.remove(markReadRequestId)?.invoke()
                        // Also clean up from markReadRequests
                        markReadRequests.remove(markReadRequestId)
                    }
                }
            }
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        )
        
        val result = sendWebSocketCommand("mark_read", markReadRequestId, commandData)
        
        // Handle immediate failure cases
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to send mark read from notification, result: $result")
            markReadRequests.remove(markReadRequestId)
            notificationActionCompletionCallbacks.remove(markReadRequestId)?.invoke()
            return
        }
        
        // No shutdown needed - foreground service keeps WebSocket open
        android.util.Log.d("Andromuks", "AppViewModel: Mark read sent, WebSocket remains connected via service")
    }
    
    fun sendReaction(roomId: String, eventId: String, emoji: String) {
        android.util.Log.d("Andromuks", "AppViewModel: sendReaction called with roomId: '$roomId', eventId: '$eventId', emoji: '$emoji'")
        
        val ws = webSocket ?: return
        
        val reactionRequestId = requestIdCounter++
        
        // Track this outgoing request
        reactionRequests[reactionRequestId] = roomId
        
        val commandData = mapOf(
            "room_id" to roomId,
            "type" to "m.reaction",
            "content" to mapOf(
                "m.relates_to" to mapOf(
                    "rel_type" to "m.annotation",
                    "event_id" to eventId,
                    "key" to emoji
                )
            ),
            "disable_encryption" to false,
            "synchronous" to false
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_event with data: $commandData")
        sendWebSocketCommand("send_event", reactionRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $reactionRequestId")
        
        // Update recent emojis
        updateRecentEmojis(emoji)
    }
    
    fun updateRecentEmojis(emoji: String) {
        // Find existing emoji in frequencies
        val existingIndex = recentEmojiFrequencies.indexOfFirst { it.first == emoji }
        
        if (existingIndex >= 0) {
            // Emoji exists - increment its count
            val existingPair = recentEmojiFrequencies[existingIndex]
            val newCount = existingPair.second + 1
            recentEmojiFrequencies[existingIndex] = Pair(emoji, newCount)
        } else {
            // New emoji - add with count 1
            recentEmojiFrequencies.add(Pair(emoji, 1))
        }
        
        // Sort by frequency (descending), then by insertion order (maintain order for same frequency)
        // We'll sort by count descending, keeping the order stable
        val sortedFrequencies = recentEmojiFrequencies.sortedByDescending { it.second }
        
        // Keep only top 20
        val updatedFrequencies = sortedFrequencies.take(20)
        
        // Update internal storage and UI list
        recentEmojiFrequencies = updatedFrequencies.toMutableList()
        recentEmojis = updatedFrequencies.map { it.first }
        
        android.util.Log.d("Andromuks", "AppViewModel: Updated recent emojis, ${updatedFrequencies.size} total, emoji '$emoji' now has count ${updatedFrequencies.find { it.first == emoji }?.second ?: 1}")
        
        // Send to server
        sendAccountDataUpdate(updatedFrequencies)
    }
    
    private fun sendAccountDataUpdate(frequencies: List<Pair<String, Int>>) {
        val ws = webSocket ?: return
        val accountDataRequestId = requestIdCounter++
        
        // Create the recent_emoji array format: [["emoji", count], ...]
        val recentEmojiArray = frequencies.map { listOf(it.first, it.second) }
        
        val commandData = mapOf(
            "type" to "io.element.recent_emoji",
            "content" to mapOf(
                "recent_emoji" to recentEmojiArray
            )
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: set_account_data with data: $commandData")
        sendWebSocketCommand("set_account_data", accountDataRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $accountDataRequestId")
    }
    
    fun sendReply(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent) {
        android.util.Log.d("Andromuks", "AppViewModel: sendReply called with roomId: '$roomId', text: '$text', originalEvent: ${originalEvent.eventId}")
        
        // Try to send the reply immediately
        val result = sendReplyInternal(roomId, text, originalEvent)
        
        // If WebSocket is not available, just log it - health monitoring will handle reconnection
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: sendReply failed with result: $result - health monitor will handle reconnection")
        }
    }
    
    private fun sendReplyInternal(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent): WebSocketResult {
        android.util.Log.d("Andromuks", "AppViewModel: sendReplyInternal called")
        val messageRequestId = requestIdCounter++
        
        // Extract mentions from the original message sender
        val mentions = mutableListOf<String>()
        if (originalEvent.sender.isNotBlank()) {
            mentions.add(originalEvent.sender)
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to mapOf(
                "m.in_reply_to" to mapOf(
                    "event_id" to originalEvent.eventId
                )
            ),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        val result = sendWebSocketCommand("send_message", messageRequestId, commandData)
        
        if (result == WebSocketResult.SUCCESS) {
            messageRequests[messageRequestId] = roomId
            pendingSendCount++
            android.util.Log.d("Andromuks", "AppViewModel: Reply send queued with request_id: $messageRequestId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send reply, result: $result")
        }
        
        return result
    }
    
    fun sendEdit(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent) {
        android.util.Log.d("Andromuks", "AppViewModel: sendEdit called with roomId: '$roomId', text: '$text', originalEvent: ${originalEvent.eventId}")
        
        val ws = webSocket ?: return
        val editRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[editRequestId] = roomId
        pendingSendCount++
        
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to mapOf(
                "rel_type" to "m.replace",
                "event_id" to originalEvent.eventId
            ),
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with edit data: $commandData")
        sendWebSocketCommand("send_message", editRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: Edit command sent with request_id: $editRequestId")
    }
    
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
        msgType: String = "m.image"
    ) {
        android.util.Log.d("Andromuks", "AppViewModel: sendMediaMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl'")
        
        val ws = webSocket ?: return
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        // Use caption if provided, otherwise use filename
        val body = caption.ifBlank { filename }
        
        val baseContent = mapOf(
            "msgtype" to msgType,
            "body" to body,
            "url" to mxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "xyz.amorgan.blurhash" to blurHash,
                "w" to width,
                "h" to height,
                "size" to size
            ),
            "filename" to filename
        )
        
        val commandData = mapOf(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to "",
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with media data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: Media message command sent with request_id: $messageRequestId")
    }
    
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
        caption: String? = null
    ) {
        // Extract filename from mxc URL (format: mxc://server/mediaId)
        val filename = mxcUrl.substringAfterLast("/").let { mediaId ->
            // Try to infer extension from mime type
            val extension = when {
                mimeType.startsWith("image/jpeg") -> "jpg"
                mimeType.startsWith("image/png") -> "png"
                mimeType.startsWith("image/gif") -> "gif"
                mimeType.startsWith("image/webp") -> "webp"
                else -> "jpg"
            }
            "image_$mediaId.$extension"
        }
        
        sendMediaMessage(
            roomId = roomId,
            mxcUrl = mxcUrl,
            filename = filename,
            mimeType = mimeType,
            width = width,
            height = height,
            size = size,
            blurHash = blurHash,
            caption = caption ?: "",
            msgType = "m.image"
        )
    }
    
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
        caption: String? = null
    ) {
        android.util.Log.d("Andromuks", "AppViewModel: sendVideoMessage called with roomId: '$roomId', videoMxcUrl: '$videoMxcUrl'")
        
        val ws = webSocket ?: return
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        // Extract filename from mxc URL
        val filename = videoMxcUrl.substringAfterLast("/").let { mediaId ->
            val extension = when {
                mimeType.startsWith("video/mp4") -> "mp4"
                mimeType.startsWith("video/quicktime") -> "mov"
                mimeType.startsWith("video/webm") -> "webm"
                else -> "mp4"
            }
            "video_$mediaId.$extension"
        }
        
        // Use caption if provided, otherwise use filename
        val body = caption?.takeIf { it.isNotBlank() } ?: filename
        
        val baseContent = mapOf(
            "msgtype" to "m.video",
            "body" to body,
            "url" to videoMxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "thumbnail_info" to mapOf(
                    "mimetype" to "image/jpeg",
                    "xyz.amorgan.blurhash" to thumbnailBlurHash,
                    "w" to thumbnailWidth,
                    "h" to thumbnailHeight,
                    "size" to thumbnailSize
                ),
                "thumbnail_url" to thumbnailMxcUrl,
                "w" to width,
                "h" to height,
                "duration" to duration,
                "size" to size
            ),
            "filename" to filename
        )
        
        val commandData = mapOf(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with video data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: Video message command sent with request_id: $messageRequestId")
    }
    
    fun sendDelete(roomId: String, originalEvent: net.vrkknn.andromuks.TimelineEvent, reason: String = "") {
        android.util.Log.d("Andromuks", "AppViewModel: sendDelete called with roomId: '$roomId', eventId: ${originalEvent.eventId}, reason: '$reason'")
        
        val ws = webSocket ?: return
        val deleteRequestId = requestIdCounter++
        
        // Track this outgoing request
        reactionRequests[deleteRequestId] = roomId
        
        val commandData = mutableMapOf(
            "room_id" to roomId,
            "event_id" to originalEvent.eventId
        )
        
        // Only add reason if it's not blank
        if (reason.isNotBlank()) {
            commandData["reason"] = reason
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: redact_event with data: $commandData")
        sendWebSocketCommand("redact_event", deleteRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: Delete command sent with request_id: $deleteRequestId")
    }
    
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
        caption: String? = null
    ) {
        android.util.Log.d("Andromuks", "AppViewModel: sendAudioMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', duration: ${duration}ms")
        
        val ws = webSocket ?: return
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        val baseContent = mapOf(
            "msgtype" to "m.audio",
            "body" to filename,
            "url" to mxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "duration" to duration,
                "size" to size
            ),
            "filename" to filename
        )
        
        val commandData = mapOf(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with audio data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: Audio message command sent with request_id: $messageRequestId")
    }
    
    /**
     * Send a file message
     */
    fun sendFileMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        size: Long,
        mimeType: String,
        caption: String? = null
    ) {
        android.util.Log.d("Andromuks", "AppViewModel: sendFileMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', filename: '$filename'")
        
        val ws = webSocket ?: return
        val messageRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        val baseContent = mapOf(
            "msgtype" to "m.file",
            "body" to filename,
            "url" to mxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "size" to size
            ),
            "filename" to filename
        )
        
        val commandData = mapOf(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with file data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: File message command sent with request_id: $messageRequestId")
    }
    fun handleResponse(requestId: Int, data: Any) {
        // THREAD SAFETY: Create safe copies to avoid ConcurrentModificationException during logging
        val bridgeStateKeysSnapshot = synchronized(bridgeStateRequests) { bridgeStateRequests.keys.toList() }
        val roomStateKeysSnapshot = synchronized(roomStateRequests) { roomStateRequests.keys.toList() }

        
        if (profileRequests.containsKey(requestId)) {
            handleProfileResponse(requestId, data)
        } else if (timelineRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (roomStateRequests.containsKey(requestId)) {
            handleRoomStateResponse(requestId, data)
        } else if (bridgeStateRequests.containsKey(requestId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Routing bridge state response for requestId: $requestId")
            handleBridgeStateResponse(requestId, data)
        } else if (messageRequests.containsKey(requestId)) {
            handleMessageResponse(requestId, data)
        } else if (reactionRequests.containsKey(requestId)) {
            handleReactionResponse(requestId, data)
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
        } else if (backgroundPrefetchRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (paginateRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (roomStateWithMembersRequests.containsKey(requestId)) {
            handleRoomStateWithMembersResponse(requestId, data)
        } else if (userEncryptionInfoRequests.containsKey(requestId)) {
            handleUserEncryptionInfoResponse(requestId, data)
        } else if (mutualRoomsRequests.containsKey(requestId)) {
            handleMutualRoomsResponse(requestId, data)
        } else if (trackDevicesRequests.containsKey(requestId)) {
            handleTrackDevicesResponse(requestId, data)
        } else if (resolveAliasRequests.containsKey(requestId)) {
            handleResolveAliasResponse(requestId, data)
        } else if (getRoomSummaryRequests.containsKey(requestId)) {
            handleGetRoomSummaryResponse(requestId, data)
        } else if (joinRoomCallbacks.containsKey(requestId)) {
            handleJoinRoomCallbackResponse(requestId, data)
        } else if (profileRequests.containsKey(requestId)) {
            handleOnDemandProfileResponse(requestId, data)
        } else if (roomSpecificStateRequests.containsKey(requestId)) {
            handleRoomSpecificStateResponse(requestId, data)
        } else if (fullMemberListRequests.containsKey(requestId)) {
            handleFullMemberListResponse(requestId, data)
        } else if (outgoingRequests.containsKey(requestId)) {
            handleOutgoingRequestResponse(requestId, data)
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Unknown response requestId=$requestId")
        }
    }
    
    fun handleError(requestId: Int, errorMessage: String) {
        if (profileRequests.containsKey(requestId)) {
            handleProfileError(requestId, errorMessage)
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
        } else if (roomStateRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Room state error for requestId=$requestId: $errorMessage")
            val roomId = roomStateRequests.remove(requestId)
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
    
    private fun handleProfileError(requestId: Int, errorMessage: String) {
        val userId = profileRequests.remove(requestId) ?: return
        val requestingRoomId = profileRequestRooms.remove(requestId)
        
        // PERFORMANCE: Remove from pending requests set even on error
        pendingProfileRequests.remove(userId)
        android.util.Log.d("Andromuks", "AppViewModel: Profile not found for $userId: $errorMessage")
        
        // If profile not found, use username part of Matrix ID
        val username = userId.removePrefix("@").substringBefore(":")
        val memberProfile = MemberProfile(username, null)
        
        // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
        // This is a fallback profile (username only), so it should be set as global
        if (requestingRoomId != null) {
            storeMemberProfile(requestingRoomId, userId, memberProfile)
            android.util.Log.d("Andromuks", "AppViewModel: Added fallback profile for $userId to room $requestingRoomId")
        }
        
        // Also update member cache for all rooms that already contain this user
        roomMemberCache.forEach { (roomId, memberMap) ->
            if (memberMap.containsKey(userId)) {
                storeMemberProfile(roomId, userId, memberProfile)
                memberMap[userId] = memberProfile
                android.util.Log.d("Andromuks", "AppViewModel: Updated member cache with username '$username' for $userId in room $roomId")
            }
        }
        
        // SYNC OPTIMIZATION: Schedule member update instead of immediate counter increment
        needsMemberUpdate = true
        scheduleUIUpdate("member")
    }
    
    private fun handleProfileResponse(requestId: Int, data: Any) {
        val userId = profileRequests.remove(requestId) ?: return
        val requestingRoomId = profileRequestRooms.remove(requestId)
        
        // PERFORMANCE: Remove from pending requests set
        pendingProfileRequests.remove(userId)
        val obj = data as? JSONObject ?: return
        val avatar = obj.optString("avatar_url")?.takeIf { it.isNotBlank() }
        val display = obj.optString("displayname")?.takeIf { it.isNotBlank() }
        
        val memberProfile = MemberProfile(display, avatar)
        
        // Update global profile (canonical profile from explicit request)
        // This will automatically clean up room-specific entries that now match global
        updateGlobalProfile(userId, memberProfile)
        
        // Update legacy cache for compatibility (but this will be deprecated)
        if (requestingRoomId != null) {
            val memberMap = roomMemberCache.computeIfAbsent(requestingRoomId) { ConcurrentHashMap() }
            memberMap[userId] = memberProfile
        }
        
        // Also update legacy cache for all rooms that already contain this user
        roomMemberCache.forEach { (roomId, memberMap) ->
            if (memberMap.containsKey(userId)) {
                memberMap[userId] = memberProfile
            }
        }
        
        if (userId == currentUserId) {
            currentUserProfile = UserProfile(userId = userId, displayName = display, avatarUrl = avatar)
        }
        
        
        // Check if this is part of a full user info request
        val fullUserInfoCallback = fullUserInfoCallbacks.remove(requestId)
        if (fullUserInfoCallback != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Invoking full user info callback for profile (requestId: $requestId, userId: $userId)")
            fullUserInfoCallback(obj)
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Profile response received but no full user info callback found (requestId: $requestId)")
        }
        
        // SYNC OPTIMIZATION: Schedule member update instead of immediate counter increment
        needsMemberUpdate = true
        scheduleUIUpdate("member")
    }
    
    /**
     * Saves a user profile to disk cache for persistence between app sessions.
     * 
     * Uses SQLite database (ProfileRepository) instead of SharedPreferences for better
     * performance, scalability, and memory efficiency. This is especially important when
     * storing thousands of user profiles.
     * 
     * @param context Android context for accessing database
     * @param userId The Matrix user ID to save the profile for
     * @param profile The MemberProfile object containing display name and avatar URL
     */
    fun saveProfileToDisk(context: android.content.Context, userId: String, profile: MemberProfile) {
        // Use SQLite database instead of SharedPreferences for better performance
        // Initialize repository if needed
        if (profileRepository == null) {
            profileRepository = net.vrkknn.andromuks.database.ProfileRepository(context)
        }
        
        // Save asynchronously to avoid blocking UI
        viewModelScope.launch {
            profileRepository?.saveProfile(userId, profile)
        }
    }
    
    // Database-based profile management
    private val pendingProfileSaves = mutableMapOf<String, MemberProfile>()
    private var profileSaveJob: kotlinx.coroutines.Job? = null
    private var profileRepository: net.vrkknn.andromuks.database.ProfileRepository? = null
    private var syncIngestor: net.vrkknn.andromuks.database.SyncIngestor? = null
    private var bootstrapLoader: net.vrkknn.andromuks.database.BootstrapLoader? = null

    private fun ensureBootstrapLoader(): net.vrkknn.andromuks.database.BootstrapLoader? {
        val context = appContext ?: return null
        if (bootstrapLoader == null) {
            bootstrapLoader = net.vrkknn.andromuks.database.BootstrapLoader(context)
        }
        return bootstrapLoader
    }
    
    /**
     * Manages global profile cache size to prevent memory issues.
     */
    private fun manageGlobalCacheSize() {
        if (globalProfileCache.size > 1000) {
            // Clear oldest entries to make room - much more aggressive
            val keysToRemove = globalProfileCache.keys.take(500)
            keysToRemove.forEach { globalProfileCache.remove(it) }
            android.util.Log.w("Andromuks", "AppViewModel: Cleared ${keysToRemove.size} old entries from global cache")
        }
    }
    
    /**
     * Manages room member cache size to prevent memory issues.
     */
    private fun manageRoomMemberCacheSize(roomId: String) {
        val memberMap = roomMemberCache[roomId]
        if (memberMap != null && memberMap.size > 500) {
            // Clear oldest entries to make room
            val keysToRemove = memberMap.keys.take(250)
            keysToRemove.forEach { memberMap.remove(it) }
            android.util.Log.w("Andromuks", "AppViewModel: Cleared ${keysToRemove.size} old entries from room $roomId cache")
        }
    }
    
    /**
     * Manages flattened member cache size to prevent memory issues.
     */
    private fun manageFlattenedMemberCacheSize() {
        if (flattenedMemberCache.size > 2000) {
            // Clear oldest entries to make room
            val keysToRemove = flattenedMemberCache.keys.take(1000)
            keysToRemove.forEach { flattenedMemberCache.remove(it) }
            android.util.Log.w("Andromuks", "AppViewModel: Cleared ${keysToRemove.size} old entries from flattened cache")
        }
    }
    
    /**
     * Queues a profile for batch saving to database. This prevents blocking the main thread
     * with individual disk I/O operations when processing large member lists.
     * 
     * @param userId The Matrix user ID to save the profile for
     * @param profile The MemberProfile object containing display name and avatar URL
     */
    private fun queueProfileForBatchSave(userId: String, profile: MemberProfile) {
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
    
    /**
     * Performs batch saving of all queued profiles to database.
     * This significantly reduces disk I/O when processing large member lists.
     */
    private suspend fun performBatchProfileSave() {
        if (pendingProfileSaves.isEmpty()) return
        
        val startTime = System.currentTimeMillis()
        val profilesToSave = pendingProfileSaves.toMap()
        pendingProfileSaves.clear()
        
        try {
            // Initialize repository if needed
            if (profileRepository == null) {
                appContext?.let { context ->
                    profileRepository = net.vrkknn.andromuks.database.ProfileRepository(context)
                }
            }
            
            // Save profiles to database
            profileRepository?.saveProfiles(profilesToSave)
            
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.d("Andromuks", "AppViewModel: Batch saved ${profilesToSave.size} profiles to database in ${duration}ms")
            
            // Aggressive cleanup after every save to prevent memory buildup
            profileRepository?.cleanupOldProfiles()
            
            // Force garbage collection to free memory
            System.gc()
            
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to batch save profiles to database", e)
        }
    }
    
    /**
     * Forces immediate saving of all pending profiles to disk.
     * This should be called when the app is being paused or closed to ensure
     * no profile data is lost.
     */
    fun flushPendingProfileSaves() {
        if (pendingProfileSaves.isNotEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: Flushing ${pendingProfileSaves.size} pending profile saves")
            viewModelScope.launch(Dispatchers.IO) {
                performBatchProfileSave()
            }
        }
    }
    
    /**
     * Loads a user profile from disk cache
     */
    /**
     * Loads a user profile from database cache
     */
    private suspend fun loadProfileFromDatabase(userId: String): MemberProfile? {
        return try {
            // Initialize repository if needed
            if (profileRepository == null) {
                appContext?.let { context ->
                    profileRepository = net.vrkknn.andromuks.database.ProfileRepository(context)
                }
            }
            
            profileRepository?.loadProfile(userId)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load profile from database for $userId", e)
            null
        }
    }
    
    /**
     * Legacy function for backward compatibility - now uses database
     */
    private fun loadProfileFromDisk(context: android.content.Context, userId: String): MemberProfile? {
        // This is now a blocking call to maintain compatibility
        // In the future, this should be updated to use coroutines
        return try {
            runBlocking {
                loadProfileFromDatabase(userId)
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load profile for $userId", e)
            null
        }
    }
    
    /**
     * Loads all cached profiles from disk and populates the member cache
     */
    fun loadCachedProfiles(context: android.content.Context) {
        try {
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val allKeys = sharedPrefs.all.keys.filter { it.startsWith("profile_") }
            
            for (key in allKeys) {
                val userId = key.removePrefix("profile_")
                val profile = loadProfileFromDisk(context, userId)
                if (profile != null) {
                    // Add to all room member caches where this user might be present
                    roomMemberCache.forEach { (roomId, memberMap) ->
                        if (memberMap.containsKey(userId)) {
                            memberMap[userId] = profile
                        }
                    }
                }
            }
            
            android.util.Log.d("Andromuks", "AppViewModel: Loaded ${allKeys.size} cached profiles from disk")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load cached profiles from disk", e)
        }
    }
    
    private fun handleOutgoingRequestResponse(requestId: Int, data: Any) {
        android.util.Log.d("Andromuks", "AppViewModel: handleOutgoingRequestResponse called with requestId=$requestId")
        val roomId = outgoingRequests.remove(requestId)
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Processing outgoing request response for room $roomId, currentRoomId=$currentRoomId")
            
            // NOTE: Outgoing requests also receive send_complete, so we wait for that instead
            // to avoid adding the same event multiple times
            android.util.Log.d("Andromuks", "AppViewModel: Outgoing request response received, waiting for send_complete for actual event")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: No roomId found for outgoing request $requestId")
        }
    }
    
    fun processSendCompleteEvent(eventData: JSONObject) {
        android.util.Log.d("Andromuks", "AppViewModel: processSendCompleteEvent called")
        try {
            val event = TimelineEvent.fromJson(eventData)
            android.util.Log.d("Andromuks", "AppViewModel: Created timeline event from send_complete: ${event.eventId}, type=${event.type}, eventRoomId=${event.roomId}, currentRoomId=$currentRoomId")
            
            // Only process send_complete if it's for the current room
            if (event.roomId != currentRoomId) {
                android.util.Log.d("Andromuks", "AppViewModel: send_complete for different room (${event.roomId}), ignoring")
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
                        android.util.Log.d("Andromuks", "AppViewModel: Skipping send_complete reaction from ourself (will be processed by sync_complete): $emoji from ${event.sender} to $relatesToEventId")
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
                        android.util.Log.d("Andromuks", "AppViewModel: Processed send_complete reaction from other user: $emoji from ${event.sender} to $relatesToEventId")
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
    
    fun addTimelineEvent(event: TimelineEvent) {
        android.util.Log.d("Andromuks", "AppViewModel: addTimelineEvent called for event: ${event.eventId}, type=${event.type}, roomId=${event.roomId}, currentRoomId=$currentRoomId")
        
        // Only add to timeline if it's for the current room
        if (event.roomId == currentRoomId) {
            val currentEvents = timelineEvents.toMutableList()
            currentEvents.add(event)
            timelineEvents = currentEvents.sortedBy { it.timestamp }
            // PHASE 1: Update Repository in parallel with AppViewModel
            RoomRepository.updateTimeline(currentRoomId, timelineEvents)
            android.util.Log.d("Andromuks", "AppViewModel: Added event to timeline, total events: ${timelineEvents.size}")
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Event roomId (${event.roomId}) doesn't match currentRoomId ($currentRoomId), not adding to timeline")
        }
    }
    
    fun handleTimelineResponse(requestId: Int, data: Any) {
        android.util.Log.d("Andromuks", "AppViewModel: handleTimelineResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}")
        
        // Determine request type and get room ID
        val roomId = timelineRequests[requestId] ?: paginateRequests[requestId] ?: backgroundPrefetchRequests[requestId]
        if (roomId == null) {
            android.util.Log.w("Andromuks", "AppViewModel: Received response for unknown request ID: $requestId")
            return
        }

        val isPaginateRequest = paginateRequests.containsKey(requestId)
        val isBackgroundPrefetchRequest = backgroundPrefetchRequests.containsKey(requestId)
        android.util.Log.d("Andromuks", "AppViewModel: Handling timeline response for room: $roomId, requestId: $requestId, isPaginate: $isPaginateRequest, isBackgroundPrefetch: $isBackgroundPrefetchRequest, data type: ${data::class.java.simpleName}")

        var totalReactionsProcessed = 0
        
        // Process events array - main event processing logic
        fun processEventsArray(eventsArray: JSONArray): Int {
            android.util.Log.d("Andromuks", "AppViewModel: processEventsArray called with ${eventsArray.length()} events from server")
            val timelineList = mutableListOf<TimelineEvent>()
            val allEvents = mutableListOf<TimelineEvent>()  // For version processing
            val memberMap = roomMemberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
            
            var ownMessageCount = 0
            var reactionProcessedCount = 0
            for (i in 0 until eventsArray.length()) {
                val eventJson = eventsArray.optJSONObject(i)
                if (eventJson != null) {
                    val event = TimelineEvent.fromJson(eventJson)
                    allEvents.add(event)  // Collect all events for version processing
                    
                    // Track our own messages
                    if (event.sender == currentUserId && (event.type == "m.room.message" || event.type == "m.room.encrypted")) {
                        ownMessageCount++
                        val bodyPreview = when {
                            event.type == "m.room.message" -> event.content?.optString("body", "")?.take(50)
                            event.type == "m.room.encrypted" -> event.decrypted?.optString("body", "")?.take(50)
                            else -> ""
                        }
                        //android.util.Log.d("Andromuks", "AppViewModel: [PAGINATE] ★ Found OUR message in paginate response: ${event.eventId} body='$bodyPreview' timelineRowid=${event.timelineRowid}")
                    }
                    
                    // Process member events using helper function
                    if (event.type == "m.room.member" && event.timelineRowid == -1L) {
                        processMemberEvent(event, memberMap)
                    } else {
                        // Process reaction events using helper function
                        if (event.type == "m.reaction") {
                            if (processReactionFromTimeline(event)) {
                                reactionProcessedCount++
                            }
                        } else if (event.timelineRowid >= 0L) {
                            // Only render non-reaction timeline entries
                            timelineList.add(event)
                        }
                    }
                }
            }
            android.util.Log.d("Andromuks", "AppViewModel: Processed events - timeline=${timelineList.size}, members=${memberMap.size}, ownMessages=$ownMessageCount, reactions=$reactionProcessedCount")
            if (ownMessageCount > 0) {
                android.util.Log.d("Andromuks", "AppViewModel: ★★★ PAGINATE RESPONSE CONTAINS $ownMessageCount OF YOUR OWN MESSAGES ★★★")
            }
            if (reactionProcessedCount > 0) {
                android.util.Log.d("Andromuks", "AppViewModel: ★★★ PROCESSED $reactionProcessedCount REACTIONS FROM PAGINATE RESPONSE ★★★")
            }
            
            // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
            android.util.Log.d("Andromuks", "AppViewModel: Processing ${allEvents.size} events for version tracking")
            processVersionedMessages(allEvents)
            
            // Handle empty pagination responses
            if ((paginateRequests.containsKey(requestId) || backgroundPrefetchRequests.containsKey(requestId)) && timelineList.isEmpty()) {
                android.util.Log.w("Andromuks", "AppViewModel: ========================================")
                android.util.Log.w("Andromuks", "AppViewModel: ⚠️ EMPTY PAGINATION RESPONSE (requestId: $requestId)")
                android.util.Log.w("Andromuks", "AppViewModel: Backend returned 0 events")
                
                // Mark as no more messages and show toast for user-initiated pagination
                if (paginateRequests.containsKey(requestId)) {
                    android.util.Log.w("Andromuks", "AppViewModel: Setting hasMoreMessages to FALSE")
                    hasMoreMessages = false
                    // Show toast on main thread to avoid crashes
                    appContext?.let { context ->
                        try {
                            // Ensure we're on main thread for Toast
                            viewModelScope.launch(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "No more messages available", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "AppViewModel: Error showing toast", e)
                        }
                    }
                }
                
                paginateRequests.remove(requestId)
                backgroundPrefetchRequests.remove(requestId)
                isPaginating = false
                
                android.util.Log.w("Andromuks", "AppViewModel: isPaginating set to FALSE")
                android.util.Log.w("Andromuks", "AppViewModel: ========================================")
                return reactionProcessedCount
            }
            
            if (timelineList.isNotEmpty()) {
                // Handle background prefetch requests first - before any UI processing
                if (backgroundPrefetchRequests.containsKey(requestId)) {
                    return handleBackgroundPrefetch(roomId, timelineList)
                }

                // Store smallest rowId for pagination (only for initial paginate, not pagination requests)
                if (!paginateRequests.containsKey(requestId)) {
                    val currentSmallest = timelineList.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
                    android.util.Log.d("Andromuks", "AppViewModel: Initial paginate - found smallest rowId: $currentSmallest from ${timelineList.size} events")
                    if (currentSmallest > 0) {
                        smallestRowId = currentSmallest
                        android.util.Log.d("Andromuks", "AppViewModel: Stored smallest rowId for pagination: $smallestRowId")
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: No valid rowId found for pagination")
                    }
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: This is a pagination request, not storing rowId")
                }
                
                // Populate edit chain mapping for clean edit handling using helper function
                buildEditChainsFromEvents(timelineList)
                
                // Process edit relationships
                processEditRelationships()
                
                if (paginateRequests.containsKey(requestId)) {
                    // This is a pagination request - merge with existing timeline
                    handlePaginationMerge(roomId, timelineList, requestId)
                    paginateRequests.remove(requestId)
                    isPaginating = false
                    android.util.Log.d("Andromuks", "AppViewModel: isPaginating set to FALSE")
                } else {
                    // This is an initial paginate - build timeline from chain mapping
                    handleInitialTimelineBuild(roomId, timelineList)
                }
                
                // Mark room as read when timeline is successfully loaded - use most recent event by timestamp
                // (But not for background prefetch requests since we're just silently updating cache)
                if (!backgroundPrefetchRequests.containsKey(requestId)) {
                    val mostRecentEvent = timelineList.maxByOrNull { it.timestamp }
                    if (mostRecentEvent != null) {
                        markRoomAsRead(roomId, mostRecentEvent.eventId)
                    }
                }
            }
            
            return reactionProcessedCount
        }

        when (data) {
            is JSONArray -> {
                totalReactionsProcessed = processEventsArray(data)
            }
            is JSONObject -> {
                val eventsArray = data.optJSONArray("events")
                if (eventsArray != null) {
                    totalReactionsProcessed = processEventsArray(eventsArray)
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: JSONObject did not contain 'events' array")
                }
                
                // Parse has_more field for pagination (but not for background prefetch)
                if (paginateRequests.containsKey(requestId)) {
                    val hasMore = data.optBoolean("has_more", true) // Default to true if not present
                    val fromServer = data.optBoolean("from_server", false)
                    
                    android.util.Log.d("Andromuks", "AppViewModel: ========================================")
                    android.util.Log.d("Andromuks", "AppViewModel: PARSING PAGINATION METADATA")
                    android.util.Log.d("Andromuks", "AppViewModel:    has_more: $hasMore")
                    android.util.Log.d("Andromuks", "AppViewModel:    from_server: $fromServer")
                    android.util.Log.d("Andromuks", "AppViewModel:    hasMoreMessages BEFORE: $hasMoreMessages")
                    
                    hasMoreMessages = hasMore
                    
                    android.util.Log.d("Andromuks", "AppViewModel:    hasMoreMessages AFTER: $hasMoreMessages")
                    android.util.Log.d("Andromuks", "AppViewModel: Full pagination response data keys: ${data.keys().asSequence().toList()}")
                    android.util.Log.d("Andromuks", "AppViewModel: ========================================")
                    
                    // Show toast when reaching the end
                    if (!hasMore) {
                        android.util.Log.w("Andromuks", "AppViewModel: 🏁 REACHED END OF MESSAGE HISTORY (has_more=false)")
                        appContext?.let { context ->
                            android.widget.Toast.makeText(context, "No more messages available", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (backgroundPrefetchRequests.containsKey(requestId)) {
                    // For background prefetch, we don't update hasMoreMessages to avoid affecting UI
                    android.util.Log.d("Andromuks", "AppViewModel: Skipping has_more parsing for background prefetch request")
                }
                
                // Process read receipts from timeline response (in background to avoid blocking animation)
                val receipts = data.optJSONObject("receipts")
                if (receipts != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: Processing read receipts from timeline response for room: $roomId")
                    // Process receipts in background to avoid blocking UI thread during bubble animation
                    viewModelScope.launch(Dispatchers.Default) {
                        try {
                            val processedMovements = mutableMapOf<String, Triple<String?, String, Long>>()
                            var hasReceiptChanges = false
                            
                            synchronized(readReceiptsLock) {
                                hasReceiptChanges = ReceiptFunctions.processReadReceipts(
                                    receipts, 
                                    readReceipts, 
                                    { }, // Update counter after processing (on main thread)
                                    { userId, previousEventId, newEventId ->
                                        // Track receipt movement for animation (collect in background)
                                        processedMovements[userId] = Triple(previousEventId, newEventId, System.currentTimeMillis())
                                    }
                                )
                            }
                            
                            // Apply updates on main thread after processing (only if there were changes)
                            if (hasReceiptChanges || processedMovements.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    try {
                                        if (processedMovements.isNotEmpty()) {
                                            synchronized(readReceiptsLock) {
                                                receiptMovements.putAll(processedMovements)
                                            }
                                            receiptAnimationTrigger = System.currentTimeMillis()
                                        }
                                        // Single UI update after all processing
                                        if (hasReceiptChanges) {
                                            readReceiptsUpdateCounter++
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Andromuks", "AppViewModel: Error updating receipt state on main thread", e)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Andromuks", "AppViewModel: Error processing receipts in background", e)
                        }
                    }
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleTimelineResponse: ${data::class.java.simpleName}")
            }
        }

        // IMPORTANT: If we processed reactions in background prefetch, trigger UI update
        if (isBackgroundPrefetchRequest && totalReactionsProcessed > 0) {
            android.util.Log.d("Andromuks", "AppViewModel: Triggering UI update for $totalReactionsProcessed reactions processed in background prefetch")
            reactionUpdateCounter++ // Trigger UI recomposition for reactions
        }

        timelineRequests.remove(requestId)
        paginateRequests.remove(requestId)
        backgroundPrefetchRequests.remove(requestId)
    }
    private fun handleRoomStateResponse(requestId: Int, data: Any) {
        val roomId = roomStateRequests.remove(requestId) ?: return
        
        // PERFORMANCE: Remove from pending requests set
        pendingRoomStateRequests.remove(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Handling room state response for room: $roomId")
        
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
                    android.util.Log.d("Andromuks", "AppViewModel: No events array in room state response")
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleRoomStateResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun handleBridgeStateResponse(requestId: Int, data: Any) {
        val roomId = bridgeStateRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling room state response for room: $roomId (requestId: $requestId)")
        
        when (data) {
            is JSONArray -> {
                android.util.Log.d("Andromuks", "AppViewModel: Received JSONArray with ${data.length()} events for room: $roomId")
                // Store the complete room state for future use
                storeRoomState(roomId, data)
                // Extract and process m.bridge events
                processRoomStateForBridges(roomId, data)
            }
            is JSONObject -> {
                val events = data.optJSONArray("events")
                if (events != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: Received JSONObject with ${events.length()} events for room: $roomId")
                    // Store the complete room state for future use
                    storeRoomState(roomId, events)
                    // Extract and process m.bridge events
                    processRoomStateForBridges(roomId, events)
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: No events array in room state response for room: $roomId")
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleBridgeStateResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    /**
     * Store complete room state for future use
     */
    private fun storeRoomState(roomId: String, events: JSONArray) {
        android.util.Log.d("Andromuks", "AppViewModel: Storing room state for room: $roomId with ${events.length()} events")
        roomStatesCache = roomStatesCache + (roomId to events)
        android.util.Log.d("Andromuks", "AppViewModel: Room state cache now contains ${roomStatesCache.size} rooms")
    }
    
    /**
     * Process room state events to extract m.bridge events for bridge detection
     */
    private fun processRoomStateForBridges(roomId: String, events: JSONArray) {
        android.util.Log.d("Andromuks", "AppViewModel: Processing room state for bridges - room: $roomId with ${events.length()} events")
        
        // Filter for m.bridge events from all room state events
        val bridgeEvents = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i)
            if (event != null && event.optString("type") == "m.bridge") {
                bridgeEvents.put(event)
            }
        }
        
        if (bridgeEvents.length() > 0) {
            android.util.Log.d("Andromuks", "AppViewModel: Found ${bridgeEvents.length()} m.bridge events for room: $roomId")
            parseBridgeStateFromEvents(roomId, bridgeEvents)
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: No m.bridge events found for room: $roomId")
        }
        
        // Always mark room as checked, even if no bridge was found
        markRoomAsBridgeChecked(roomId)
    }
    
    /**
     * Get stored room state for a specific room
     */
    fun getRoomState(roomId: String): JSONArray? {
        return roomStatesCache[roomId]
    }
    
    /**
     * Get all stored room states
     */
    fun getAllRoomStates(): Map<String, JSONArray> {
        return roomStatesCache
    }
    
    private fun parseBridgeStateFromEvents(roomId: String, events: JSONArray) {
        try {
            android.util.Log.d("Andromuks", "AppViewModel: Parsing ${events.length()} events for room: $roomId")
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i)
                if (event != null && event.optString("type") == "m.bridge") {
                    android.util.Log.d("Andromuks", "AppViewModel: Found m.bridge event for room: $roomId")
                    val content = event.optJSONObject("content")
                    if (content != null) {
                        val bridgeInfo = parseBridgeInfo(content)
                        if (bridgeInfo != null) {
                            updateBridgeInfo(roomId, bridgeInfo)
                            android.util.Log.d("Andromuks", "AppViewModel: Parsed bridge info for room $roomId: ${bridgeInfo.protocol.displayname}")
                            
                            // Create bridge pseudo-spaces when we have bridge info
                            createBridgePseudoSpaces()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing bridge state for room $roomId", e)
        }
    }
    
    private fun parseBridgeInfo(content: JSONObject): BridgeInfo? {
        return try {
            android.util.Log.d("Andromuks", "AppViewModel: Parsing bridge info from content: ${content.toString()}")
            val bridgebot = content.optString("bridgebot")
            val creator = content.optString("creator")
            
            val channelObj = content.optJSONObject("channel")
            val channel = if (channelObj != null) {
                BridgeChannel(
                    avatarUrl = channelObj.optString("avatar_url")?.takeIf { it.isNotBlank() },
                    displayname = channelObj.optString("displayname") ?: "",
                    id = channelObj.optString("id") ?: ""
                )
            } else {
                BridgeChannel(null, "", "")
            }
            
            val protocolObj = content.optJSONObject("protocol")
            val protocol = if (protocolObj != null) {
                BridgeProtocol(
                    avatarUrl = protocolObj.optString("avatar_url")?.takeIf { it.isNotBlank() },
                    displayname = protocolObj.optString("displayname") ?: "",
                    externalUrl = protocolObj.optString("external_url")?.takeIf { it.isNotBlank() },
                    id = protocolObj.optString("id") ?: ""
                )
            } else {
                BridgeProtocol(null, "", null, "")
            }
            
            val bridgeInfo = BridgeInfo(
                bridgebot = bridgebot,
                channel = channel,
                creator = creator,
                protocol = protocol
            )
            android.util.Log.d("Andromuks", "AppViewModel: Created BridgeInfo: bridgebot=$bridgebot, creator=$creator, protocol=${protocol.displayname} (${protocol.id})")
            bridgeInfo
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing bridge info", e)
            null
        }
    }
    
    /**
     * Parse room state from state events.
     * OPTIMIZED: Single pass with minimal JSON access; early exits in branches.
     */
    private fun parseRoomStateFromEvents(roomId: String, events: JSONArray) {
        var name: String? = null
        var canonicalAlias: String? = null
        var topic: String? = null
        var avatarUrl: String? = null
        var isEncrypted = false
        var powerLevels: PowerLevelsInfo? = null
        val pinnedEventIds = mutableListOf<String>()
        
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
                        
                        powerLevels = PowerLevelsInfo(
                            users = usersMap,
                            usersDefault = content.optInt("users_default", 0),
                            redact = content.optInt("redact", 50)
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
            pinnedEventIds = pinnedEventIds
        )
        
        // ✓ FIX: Only update currentRoomState if this is the currently open room
        // This prevents the room header from flashing through all rooms during shortcut loading
        if (roomId == currentRoomId) {
            currentRoomState = roomState
            roomStateUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily
            android.util.Log.d("Andromuks", "AppViewModel: ✓ Updated current room state - Name: $name, Alias: $canonicalAlias, Topic: $topic, Avatar: $avatarUrl, Encrypted: $isEncrypted")
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Parsed room state for $roomId (not current room) - Name: $name")
        }
    }
    
    private fun handleMessageResponse(requestId: Int, data: Any) {
        val roomId = messageRequests.remove(requestId) ?: return
        if (pendingSendCount > 0) {
            pendingSendCount--
        }
        android.util.Log.d("Andromuks", "AppViewModel: Handling message response for room: $roomId, pendingSendCount=$pendingSendCount, data: $data")
        
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
        android.util.Log.d("Andromuks", "AppViewModel: Message response received, waiting for send_complete for actual event")
        
        // Always invoke completion callback for notification actions, even if there was an error
        // This prevents the UI from stalling indefinitely
        val callback = notificationActionCompletionCallbacks.remove(requestId)
        if (callback != null) {
            if (isError) {
                android.util.Log.w("Andromuks", "AppViewModel: Message send had error, but calling completion callback to prevent UI stalling")
            }
            callback()
        }
    }
    
    private fun handleReactionResponse(requestId: Int, data: Any) {
        val roomId = reactionRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling reaction response for room: $roomId, currentRoomId: $currentRoomId")
        
        when (data) {
            is JSONObject -> {
                // Create TimelineEvent from the response
                val event = TimelineEvent.fromJson(data)
                android.util.Log.d("Andromuks", "AppViewModel: Created reaction event: type=${event.type}, roomId=${event.roomId}, eventId=${event.eventId}")
                if (event.type == "m.reaction") {
                    // Don't add response events to timeline - they have temporary transaction IDs
                    // The real event will come via send_complete with proper Matrix event ID
                    android.util.Log.d("Andromuks", "AppViewModel: Ignoring reaction response event (temporary ID), waiting for send_complete: ${event.content?.optJSONObject("m.relates_to")?.optString("key")}")
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: Expected m.reaction event but got: ${event.type}")
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleReactionResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun handleRoomStateWithMembersResponse(requestId: Int, data: Any) {
        val callback = roomStateWithMembersRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling room state with members response for requestId: $requestId")
        
        try {
            // Parse the room state data using the utility function
            val roomStateInfo = net.vrkknn.andromuks.utils.parseRoomStateResponse(data)
            
            if (roomStateInfo != null) {
                android.util.Log.d("Andromuks", "AppViewModel: Successfully parsed room state with ${roomStateInfo.members.size} members")
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
        android.util.Log.d("Andromuks", "AppViewModel: Handling event response for requestId: $requestId")
        
        when (data) {
            is JSONObject -> {
                // Create TimelineEvent from the response
                val event = TimelineEvent.fromJson(data)
                android.util.Log.d("Andromuks", "AppViewModel: Retrieved event: ${event.eventId}, type: ${event.type}, sender: ${event.sender}")
                callback(event)
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleEventResponse: ${data::class.java.simpleName}")
                callback(null)
            }
        }
    }
    
    private fun handleOnDemandProfileResponse(requestId: Int, data: Any) {
        val requestKey = profileRequests.remove(requestId) ?: return
        val (roomId, userId) = requestKey.split(":", limit = 2)
        
        // Remove from pending requests
        pendingProfileRequests.remove(requestKey)
        
        android.util.Log.d("Andromuks", "AppViewModel: Handling on-demand profile response for $userId in room $roomId")
        
        when (data) {
            is JSONArray -> {
                if (data.length() > 0) {
                    // Parse the single member event
                    parseMemberEventsForProfileUpdate(roomId, data)
                    android.util.Log.d("Andromuks", "AppViewModel: Successfully loaded profile for $userId")
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: No profile data found for $userId")
                }
            }
            is JSONObject -> {
                android.util.Log.d("Andromuks", "AppViewModel: On-demand profile response is JSONObject, expected JSONArray")
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleOnDemandProfileResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun handleRoomSpecificStateResponse(requestId: Int, data: Any) {
        val roomId = roomSpecificStateRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling room specific state response for room: $roomId, requestId: $requestId")
        android.util.Log.d("Andromuks", "AppViewModel: Room specific state response data type: ${data::class.java.simpleName}")
        
        // Clean up profile request tracking - we need to find the user ID from the response data
        // This will be cleaned up properly when we process the member events
        android.util.Log.d("Andromuks", "AppViewModel: Will clean up pendingProfileRequests after processing member events")
        
        when (data) {
            is JSONArray -> {
                android.util.Log.d("Andromuks", "AppViewModel: Processing JSONArray response with ${data.length()} items")
                // Parse member events from the response
                parseMemberEventsForProfileUpdate(roomId, data)
                android.util.Log.d("Andromuks", "AppViewModel: parseMemberEventsForProfileUpdate completed")
            }
            is JSONObject -> {
                android.util.Log.d("Andromuks", "AppViewModel: Room specific state response is JSONObject, expected JSONArray")
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleRoomSpecificStateResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun handleFullMemberListResponse(requestId: Int, data: Any) {
        val roomId = fullMemberListRequests.remove(requestId) ?: return
        
        // PERFORMANCE: Remove from pending requests set
        pendingFullMemberListRequests.remove(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Handling full member list response for room: $roomId")
        
        // NAVIGATION PERFORMANCE: Update navigation state cache when member data is loaded
        val currentState = navigationCache[roomId] ?: RoomNavigationState(roomId)
        navigationCache[roomId] = currentState.copy(
            memberDataLoaded = true,
            lastPrefetchTime = System.currentTimeMillis()
        )
        
        when (data) {
            is JSONArray -> {
                // Parse all room state events but only process m.room.member events
                parseFullMemberListFromRoomState(roomId, data)
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleFullMemberListResponse: ${data::class.java.simpleName}")
            }
        }
    }
    
    private fun parseFullMemberListFromRoomState(roomId: String, events: JSONArray) {
        val startTime = System.currentTimeMillis()
        android.util.Log.d("Andromuks", "AppViewModel: Parsing full member list from ${events.length()} room state events for room: $roomId")
        
        val memberMap = roomMemberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
        
        // Clear existing cache to ensure we don't have stale invite members or other invalid entries
        // Since this is a full member list request, we want to start fresh
        val previousSize = memberMap.size
        memberMap.clear()
        android.util.Log.d("Andromuks", "AppViewModel: Cleared $previousSize existing members from cache for fresh member list")
        
        var updatedMembers = 0
        
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val eventType = event.optString("type")
            
            if (eventType == "m.room.member") {
                val stateKey = event.optString("state_key")
                val content = event.optJSONObject("content")
                val membership = content?.optString("membership")
                
                if (stateKey.isNotEmpty()) {
                    when (membership) {
                        "join" -> {
                            // Add/update joined members with full profile data
                            val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                            val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                            
                            val newProfile = MemberProfile(displayName, avatarUrl)
                            memberMap[stateKey] = newProfile
                            
                            // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                            storeMemberProfile(roomId, stateKey, newProfile)
                            
                            // MEMORY MANAGEMENT: Cleanup if needed
                            manageGlobalCacheSize()
                            manageRoomMemberCacheSize(roomId)
                            manageFlattenedMemberCacheSize()
                            
                            android.util.Log.d("Andromuks", "AppViewModel: Added member $stateKey to room $roomId - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            updatedMembers++
                            
                            // For large member lists, save immediately to prevent OOM
                            if (updatedMembers > 100) {
                                // Immediate save for large lists to prevent memory buildup
                                appContext?.let { context ->
                                    if (profileRepository == null) {
                                        profileRepository = net.vrkknn.andromuks.database.ProfileRepository(context)
                                    }
                                    viewModelScope.launch(Dispatchers.IO) {
                                        profileRepository?.saveProfile(stateKey, newProfile)
                                    }
                                }
                                
                                // For very large lists, clear caches aggressively
                                if (updatedMembers > 500) {
                                    // Clear all caches to prevent OOM
                                    globalProfileCache.clear()
                                    flattenedMemberCache.clear()
                                    roomMemberIndex.clear() // OPTIMIZED: Clear index when clearing cache
                                    android.util.Log.w("Andromuks", "AppViewModel: Cleared all caches due to large member list ($updatedMembers members)")
                                }
                            } else {
                                // Queue for batch saving for smaller lists
                                queueProfileForBatchSave(stateKey, newProfile)
                            }
                        }
                        "leave", "ban" -> {
                            // Remove members who left or were banned
                            val wasRemoved = memberMap.remove(stateKey) != null
                            val flattenedKey = "$roomId:$stateKey"
                            val wasRemovedFromFlattened = flattenedMemberCache.remove(flattenedKey) != null
                            
                            // OPTIMIZED: Remove from indexed cache
                            roomMemberIndex[roomId]?.remove(stateKey)
                            
                            if (wasRemoved || wasRemovedFromFlattened) {
                                android.util.Log.d("Andromuks", "AppViewModel: Removed $stateKey from room $roomId (membership: $membership)")
                                updatedMembers++
                            }
                            // Note: Don't remove from global cache as they might be in other rooms
                        }
                    }
                }
            }
        }
        
        if (updatedMembers > 0) {
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedMembers members in full member list for room $roomId in ${duration}ms")
            // Trigger UI update since member cache changed
            updateCounter++
        }
        
        // Also parse room state metadata (name, alias, topic, avatar) for header display
        parseRoomStateFromEvents(roomId, events)
    }
    
    private fun parseMemberEventsForProfileUpdate(roomId: String, events: JSONArray) {
        val startTime = System.currentTimeMillis()
        android.util.Log.d("Andromuks", "AppViewModel: Parsing ${events.length()} member events for profile update in room: $roomId")
        android.util.Log.d("Andromuks", "AppViewModel: Member events data: ${events.toString()}")
        
        val memberMap = roomMemberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
        var updatedProfiles = 0
        
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val eventType = event.optString("type")
            
            if (eventType == "m.room.member") {
                val stateKey = event.optString("state_key")
                val content = event.optJSONObject("content")
                val membership = content?.optString("membership")
                
                if (stateKey.isNotEmpty()) {
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
                            
                            // Use storeMemberProfile to ensure optimization (only store room-specific if differs from global)
                            storeMemberProfile(roomId, stateKey, newProfile)
                            
                            // MEMORY MANAGEMENT: Cleanup if needed
                            manageGlobalCacheSize()
                            manageRoomMemberCacheSize(roomId)
                            manageFlattenedMemberCacheSize()
                            
                            android.util.Log.d("Andromuks", "AppViewModel: Updated profile for $stateKey - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            android.util.Log.d("Andromuks", "AppViewModel: Profile update completed for $stateKey, triggering memberUpdateCounter")
                            updatedProfiles++
                            
                            // For large member lists, save immediately to prevent OOM
                            if (updatedProfiles > 100) {
                                // Immediate save for large lists to prevent memory buildup
                                appContext?.let { context ->
                                    if (profileRepository == null) {
                                        profileRepository = net.vrkknn.andromuks.database.ProfileRepository(context)
                                    }
                                    viewModelScope.launch(Dispatchers.IO) {
                                        profileRepository?.saveProfile(stateKey, newProfile)
                                    }
                                }
                                
                                // For very large lists, clear caches aggressively
                                if (updatedProfiles > 500) {
                                    // Clear all caches to prevent OOM
                                    globalProfileCache.clear()
                                    flattenedMemberCache.clear()
                                    roomMemberIndex.clear() // OPTIMIZED: Clear index when clearing cache
                                    android.util.Log.w("Andromuks", "AppViewModel: Cleared all caches due to large profile update ($updatedProfiles profiles)")
                                }
                            } else {
                                // Queue for batch saving for smaller lists
                                queueProfileForBatchSave(stateKey, newProfile)
                            }
                        }
                    } else if (membership == "leave" || membership == "ban") {
                        // Remove members who left or were banned from room cache
                        val wasRemoved = memberMap.remove(stateKey) != null
                        val flattenedKey = "$roomId:$stateKey"
                        val wasRemovedFromFlattened = flattenedMemberCache.remove(flattenedKey) != null
                        
                        // OPTIMIZED: Remove from indexed cache
                        roomMemberIndex[roomId]?.remove(stateKey)
                        
                        if (wasRemoved || wasRemovedFromFlattened) {
                            android.util.Log.d("Andromuks", "AppViewModel: Removed $stateKey from room cache (membership: $membership)")
                            updatedProfiles++
                        }
                        // Note: Don't remove from global cache as they might be in other rooms
                    }
                }
            }
        }
        
        if (updatedProfiles > 0) {
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedProfiles profiles in room $roomId in ${duration}ms")
            // Trigger UI update since member cache changed
            updateCounter++
            memberUpdateCounter++
            android.util.Log.d("Andromuks", "AppViewModel: Triggered memberUpdateCounter++ for $updatedProfiles profile updates")
        }
        
        // Clean up pending profile requests for processed users
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("type") == "m.room.member") {
                val stateKey = event.optString("state_key")
                if (stateKey.isNotEmpty()) {
                    val requestKey = "$roomId:$stateKey"
                    pendingProfileRequests.remove(requestKey)
                    android.util.Log.d("Andromuks", "AppViewModel: Cleaned up pendingProfileRequests for $requestKey")
                }
            }
        }
    }
    
    /**
     * SYNC OPTIMIZATION: Check if current room needs timeline update with diff-based detection
     */
    private fun checkAndUpdateCurrentRoomTimelineOptimized(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null && currentRoomId != null && rooms.has(currentRoomId)) {
                android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Checking timeline diff for room: $currentRoomId")
                
                // Update timeline data first
                updateTimelineFromSync(syncJson, currentRoomId!!)
                
                // Check if timeline actually changed using diff-based detection
                val newTimelineStateHash = generateTimelineStateHash(timelineEvents)
                val timelineStateChanged = newTimelineStateHash != lastTimelineStateHash
                
                if (timelineStateChanged) {
                    android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Timeline state changed, scheduling UI update")
                    needsTimelineUpdate = true
                    scheduleUIUpdate("timeline")
                    lastTimelineStateHash = newTimelineStateHash
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Timeline state unchanged, skipping UI update")
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
                android.util.Log.d("Andromuks", "AppViewModel: sync_complete contains events for ${roomsInSync.size} rooms: $roomsInSync")
                android.util.Log.d("Andromuks", "AppViewModel: currentRoomId = $currentRoomId (${if (currentRoomId != null) "ROOM OPEN" else "NO ROOM OPEN"})")
                
                // Only update timeline if room is currently open
                if (currentRoomId != null && rooms.has(currentRoomId)) {
                    android.util.Log.d("Andromuks", "AppViewModel: ✓ Processing sync_complete events for OPEN room: $currentRoomId")
                    updateTimelineFromSync(syncJson, currentRoomId!!)
                } else if (currentRoomId != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: ✗ Skipping sync_complete - current room $currentRoomId not in this sync batch")
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: ✗ Skipping sync_complete - no room currently open (events will be cached only)")
                }
            }
        }
    }
    
    /**
     * Cache timeline events from sync_complete for all rooms
     * This allows instant room opening if we have enough cached events
     */
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
            val events = roomData.optJSONArray("events") ?: continue
            
            val memberMap = roomMemberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
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
                                pinnedEventIds = currentRoomState?.pinnedEventIds ?: emptyList() // Preserve existing pinned events
                            )
                            // ✓ Safety check: Only update if this is the currently open room
                            if (roomId == currentRoomId) {
                                currentRoomState = roomState
                            } 
                        }
                    }
                    
                    // Process new timeline events
                    val events = roomData.optJSONArray("events")
                    if (events != null && events.length() > 0) {
                        android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.length()} new timeline events for room: $roomId")
                        processSyncEventsArray(events, roomId)
                    }
                    
                    // Process read receipts (optimized to only update UI if receipts actually changed)
                    val receipts = roomData.optJSONObject("receipts")
                    if (receipts != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Processing read receipts for room: $roomId - found ${receipts.length()} event receipts")
                        synchronized(readReceiptsLock) {
                            ReceiptFunctions.processReadReceipts(
                                receipts, 
                                readReceipts, 
                                { readReceiptsUpdateCounter++ },
                                { userId, previousEventId, newEventId ->
                                    // Track receipt movement for animation (thread-safe)
                                    synchronized(readReceiptsLock) {
                                        receiptMovements[userId] = Triple(previousEventId, newEventId, System.currentTimeMillis())
                                    }
                                    receiptAnimationTrigger = System.currentTimeMillis()
                                    android.util.Log.d("Andromuks", "AppViewModel: Receipt movement detected: $userId from $previousEventId to $newEventId")
                                }
                            )
                        }
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: No receipts found in sync for room: $roomId")
                    }
                }
            }
        }
    }
    
    private fun processSyncEventsArray(eventsArray: JSONArray, roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: processSyncEventsArray called with ${eventsArray.length()} events")
        val memberMap = roomMemberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
        
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
            android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} events in timestamp order")
            
            // Count event types for debugging (lightweight operation, can defer if needed)
            val eventTypeCounts = events.groupBy { it.type }.mapValues { it.value.size }
            val ownMessageCount = events.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
            android.util.Log.d("Andromuks", "AppViewModel: Event breakdown: $eventTypeCounts (including $ownMessageCount from YOU)")
        }
        
        // Skip processing if no events
        if (events.isEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: No events to process")
            return
        }
        
        // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
        // Defer to background thread if we have many events
        android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} sync events for version tracking")
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
                        memberMap[userId] = profile
                        // PERFORMANCE: Also add to global cache for O(1) lookups
                        globalProfileCache[userId] = CachedProfileEntry(profile, System.currentTimeMillis())
                    }
                }
            } else if (event.type == "m.room.member" && event.timelineRowid >= 0L) {
                // Timeline member event (join/leave that should show in timeline)
                addNewEventToChain(event)
            } else if (event.type == "m.room.redaction") {
                
                // Add redaction event to timeline so findLatestRedactionEvent can find it
                addNewEventToChain(event)
                
                // Extract the event ID being redacted
                val redactsEventId = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                
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
                        
                        // PHASE 1: Update Repository in parallel with AppViewModel
                        if (roomId.isNotEmpty()) {
                            RoomRepository.updateTimeline(roomId, currentEvents)
                        }
                        
                        android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Marked event $redactsEventId as redacted by ${event.eventId}")
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Could not find event $redactsEventId to mark as redacted (might be in paginated history)")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Redaction event has no 'redacts' field")
                }
                
                // Request sender profile if missing from cache
                if (!memberMap.containsKey(event.sender)) {
                    requestUserProfile(event.sender, roomId)
                } 
                
                // OPTIMIZED: UI update will be triggered by buildTimelineFromChain() at the end of processSyncEventsArray
            } else if (event.type == "m.reaction") {
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
        
        // Build timeline from chain
        val timelineCountBefore = timelineEvents.size
        buildTimelineFromChain()
        val timelineCountAfter = timelineEvents.size

        // FAILSAFE: If the rebuilt timeline is significantly smaller than the cached snapshot,
        // fall back to rebuilding entirely from the cache to prevent data loss.
        val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
        if (cachedEventCount >= 50 && timelineEvents.size + 5 < cachedEventCount) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Timeline/cache mismatch detected for $roomId (timeline=${timelineEvents.size}, cache=$cachedEventCount) - rebuilding from cache"
            )
            val cachedSnapshot = RoomTimelineCache.getCachedEvents(roomId)
                ?: RoomTimelineCache.getCachedEventsForNotification(roomId)
            if (cachedSnapshot != null && cachedSnapshot.size > timelineEvents.size) {
                processCachedEvents(
                    roomId = roomId,
                    cachedEvents = cachedSnapshot,
                    openingFromNotification = false,
                    skipNetworkRequests = true
                )
            } 
        }
        
        // Mark room as read for the newest event since user is actively viewing the room
        val newestEvent = events.maxByOrNull { it.timestamp }
        if (newestEvent != null) {
            markRoomAsRead(roomId, newestEvent.eventId)
        }
    }
    
    /**
     * Handles edit events using the edit chain tracking system.
     * This ensures clean edit handling by tracking the edit chain properly.
     */
    private fun handleEditEventInChain(editEvent: TimelineEvent) {        
        // Check if the edit event needs decryption
        val processedEditEvent = if (editEvent.type == "m.room.encrypted" && editEvent.decrypted == null) {
            // For now, store as-is - decryption should happen elsewhere
            editEvent
        } else {
            editEvent
        }
        
        // Store the edit event
        editEventsMap[editEvent.eventId] = processedEditEvent
        
        // Get the target event ID from the edit event
        val relatesTo = when {
            editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
            editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" -> editEvent.decrypted?.optJSONObject("m.relates_to")
            else -> null
        }
        
        val targetEventId = relatesTo?.optString("event_id")
        if (targetEventId != null) {
            
            // Find the target event in our chain mapping
            val targetEntry = eventChainMap[targetEventId]
            if (targetEntry != null) {
               
                // Update the target event's replacedBy field with the new edit
                targetEntry.replacedBy = editEvent.eventId
                
            } 
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Could not find target event ID in edit event ${editEvent.eventId}")
        }
    }
    
    /**
     * Adds a new timeline event to the edit chain.
     * Includes deduplication to prevent the same event from being added multiple times.
     */
    private fun addNewEventToChain(event: TimelineEvent) {
        
        // DEDUPLICATION: Check if event already exists in chain
        if (eventChainMap.containsKey(event.eventId)) {
            return
        }
        
        // Add regular event to chain mapping
        eventChainMap[event.eventId] = EventChainEntry(
            eventId = event.eventId,
            ourBubble = event,
            replacedBy = null,
            originalTimestamp = event.timestamp
        )

    }
    
    /**
     * Processes edit relationships for new edit events only.
     */
    private fun processNewEditRelationships(newEditEvents: List<TimelineEvent>) {
        
        // Process only the new edit events
        for (editEvent in newEditEvents) {
            val editEventId = editEvent.eventId
            
            // Get the target event ID from the edit event
            val relatesTo = when {
                editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
                editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" -> editEvent.decrypted?.optJSONObject("m.relates_to")
                else -> null
            }
            
            val targetEventId = relatesTo?.optString("event_id")
            if (targetEventId != null) {
                val targetEntry = eventChainMap[targetEventId]
                if (targetEntry != null) {
                    // Check if the target already has a replacement
                    if (targetEntry.replacedBy != null) {
                        // Find the end of the current chain and add this edit to the end
                        val chainEnd = findChainEnd(targetEntry.replacedBy!!)
                        if (chainEnd != null) {
                            chainEnd.replacedBy = editEventId
                        }
                    } else {
                        // First edit for this target
                        targetEntry.replacedBy = editEventId
                    }
                } 
            } 
        }
    }
    
    /**
     * Processes edit relationships to build the complete edit chain.
     */
    private fun processEditRelationships() {
        
        // Safety check: limit the number of edit events to prevent blocking
        if (editEventsMap.size > 100) {
            val limitedEditEvents = editEventsMap.toList().take(100).toMap()
            editEventsMap.clear()
            editEventsMap.putAll(limitedEditEvents)
        }
        
        // First, create chain entries for all edit events
        for ((editEventId, editEvent) in editEventsMap) {
            eventChainMap[editEventId] = EventChainEntry(
                eventId = editEventId,
                ourBubble = null, // Edit events don't have their own bubble
                replacedBy = null,
                originalTimestamp = editEvent.timestamp
            )
        }
        
        // Sort edit events by timestamp to process in chronological order
        val sortedEditEvents = editEventsMap.values.sortedBy { it.timestamp }
        
        // OPTIMIZED: Use memoization cache for chain ends to avoid repeated traversals
        val chainEndCache = mutableMapOf<String, EventChainEntry?>()
        
        // Process all edit events to build the chain
        var processedCount = 0
        for (editEvent in sortedEditEvents) {
            // Safety check: limit processing to prevent blocking
            if (processedCount >= 50) {
                android.util.Log.w("Andromuks", "AppViewModel: Reached processing limit (50), stopping to prevent blocking")
                break
            }
            processedCount++
            val editEventId = editEvent.eventId
            
            // Get the target event ID from the edit event
            val relatesTo = when {
                editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
                editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" -> editEvent.decrypted?.optJSONObject("m.relates_to")
                else -> null
            }
            
            val targetEventId = relatesTo?.optString("event_id")
            if (targetEventId != null) {
                val targetEntry = eventChainMap[targetEventId]
                if (targetEntry != null) {
                    // Check if the target already has a replacement
                    if (targetEntry.replacedBy != null) {
                        // OPTIMIZED: Use memoized chain end lookup
                        val chainEnd = findChainEndOptimized(targetEntry.replacedBy!!, chainEndCache)
                        if (chainEnd != null) {
                            chainEnd.replacedBy = editEventId
                            // Update cache: the new end is now this edit event
                            chainEndCache[targetEntry.replacedBy!!] = null // Invalidate old chain
                        }
                    } else {
                        // First edit for this target
                        targetEntry.replacedBy = editEventId

                    }
                } 
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Could not find target event ID in edit event ${editEventId}")
            }
        }
    }
    
    /**
     * Finds the end of an edit chain by following replacedBy links.
     * OPTIMIZED: Now uses memoization to avoid repeated traversals (O(n²) -> O(n))
     */
    private fun findChainEndOptimized(startEventId: String, cache: MutableMap<String, EventChainEntry?>): EventChainEntry? {
        // Check cache first
        if (cache.containsKey(startEventId)) {
            return cache[startEventId]
        }
        
        var currentId = startEventId
        var currentEntry = eventChainMap[currentId]
        
        // Follow the chain to find the end
        while (currentEntry?.replacedBy != null) {
            currentId = currentEntry.replacedBy!!
            // Check if we've already cached this chain end
            if (cache.containsKey(currentId)) {
                val cachedEnd = cache[currentId]
                cache[startEventId] = cachedEnd
                return cachedEnd
            }
            currentEntry = eventChainMap[currentId]
        }
        
        // Cache the result for future lookups
        cache[startEventId] = currentEntry
        return currentEntry
    }
    
    /**
     * LEGACY: Finds the end of an edit chain by following replacedBy links.
     * Kept for backward compatibility but should use findChainEndOptimized instead.
     */
    private fun findChainEnd(startEventId: String): EventChainEntry? {
        var currentId = startEventId
        var currentEntry = eventChainMap[currentId]
        
        while (currentEntry?.replacedBy != null) {
            currentId = currentEntry.replacedBy!!
            currentEntry = eventChainMap[currentId]
        }
        
        return currentEntry
    }
    
    /**
     * Builds the timeline from the edit chain mapping.
     * OPTIMIZED: Combined event processing and redaction handling in single pass.
     */
    private fun buildTimelineFromChain() {
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
                eventChainMap.toMap()
            }
        
            // OPTIMIZED: Process events and collect redactions in single pass
            for ((eventId, entry) in eventChainSnapshot) {
                // Collect redaction targets first
                val redactionEvent = entry.ourBubble
                if (redactionEvent != null && redactionEvent.type == "m.room.redaction") {
                    val redactsEventId = redactionEvent.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                    if (redactsEventId != null) {
                        redactionMap[redactsEventId] = redactionEvent.eventId
                    }
                }
                
                // Process regular events with bubbles
                val ourBubble = entry.ourBubble
                if (ourBubble != null && ourBubble.type != "m.room.redaction") {
                    try {
                        // Apply redaction if this event is targeted
                        val redactedBy = redactionMap[eventId]
                        val finalEvent = if (redactedBy != null) {
                            val baseEvent = getFinalEventForBubble(entry)
                            baseEvent.copy(redactedBy = redactedBy)
                        } else {
                            getFinalEventForBubble(entry)
                        }
                        
                        timelineEvents.add(finalEvent)
                        //android.util.Log.d("Andromuks", "AppViewModel: Added event for ${eventId} with final content from ${entry.replacedBy ?: eventId}${if (redactedBy != null) " (redacted by $redactedBy)" else ""}")
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Error processing event ${eventId} in buildTimelineFromChain", e)
                        // Skip this event if there's an error (prevents crash from corrupt edit chain)
                        // Add the base event without following edit chain as fallback
                        if (ourBubble != null) {
                            timelineEvents.add(ourBubble)
                        }
                    }
                }
            }
            
            // Sort by timestamp and update timeline
            val sortedTimelineEvents = timelineEvents.sortedBy { it.timestamp }
            
            // Detect new messages for animation
            val previousEventIds = this.timelineEvents.map { it.eventId }.toSet()
            val newEventIds = sortedTimelineEvents.map { it.eventId }.toSet()
            val actuallyNewMessages = newEventIds - previousEventIds
            
            // Check if this is initial room loading (when previous timeline was empty)
            val isInitialRoomLoad = this.timelineEvents.isEmpty() && sortedTimelineEvents.isNotEmpty()
            
            // CRITICAL: Disable animations during initial room load
            // Animations should only occur for new messages when room is already open
            if (isInitialRoomLoad) {
                animationsEnabledForRoom[currentRoomId] = false
            } else {
                // Enable animations after initial load (when we have existing events and new ones arrive)
                if (!animationsEnabledForRoom.containsKey(currentRoomId)) {
                    animationsEnabledForRoom[currentRoomId] = true
                }
            }
            
            // Track new messages for slide-in animation (only if animations are enabled)
            val animationsEnabled = animationsEnabledForRoom[currentRoomId] ?: false
            val roomOpenTimestamp = roomOpenTimestamps[currentRoomId] // Get timestamp when room was opened
            
            if (actuallyNewMessages.isNotEmpty() && animationsEnabled && !isInitialRoomLoad && roomOpenTimestamp != null) {
                val currentTime = System.currentTimeMillis()
                val animationEndTime = currentTime + NEW_MESSAGE_ANIMATION_DELAY_MS + NEW_MESSAGE_ANIMATION_DURATION_MS // Bubble anim starts after delay and runs to completion
                
                // Check if any of the new messages are from other users (not our own messages)
                var shouldPlaySound = false
                actuallyNewMessages.forEach { eventId ->
                    val newEvent = sortedTimelineEvents.find { it.eventId == eventId }
                    
                    // CRITICAL: Only animate messages that are NEWER than when the room was opened
                    // This ensures:
                    // - Messages loaded during initial load don't animate (their timestamp < roomOpenTimestamp)
                    // - Messages loaded via pagination don't animate (old messages, timestamp < roomOpenTimestamp)
                    // - Only truly NEW messages arriving after room open animate (timestamp > roomOpenTimestamp)
                    val shouldAnimateThisMessage = newEvent?.let { event ->
                        event.timestamp > roomOpenTimestamp
                    } ?: false
                    
                    if (shouldAnimateThisMessage) {
                        newMessageAnimations[eventId] = animationEndTime
                        runningBubbleAnimations.add(eventId)
                    }
                    
                    // Check if this message is from another user (not our own message) for sound notification
                    val isFromOtherUser = newEvent?.let { event ->
                        // Only play sound for message events from other users in the current room
                        (event.type == "m.room.message" || event.type == "m.room.encrypted") && 
                        event.sender != currentUserId &&
                        event.roomId == currentRoomId
                    } ?: false
                    
                    if (isFromOtherUser) {
                        shouldPlaySound = true
                    }
                }
                
                // Play sound for new messages from other users in the current room
                // BUT NOT during initial room loading (when opening a room for the first time)
                if (shouldPlaySound && isAppVisible && currentRoomId.isNotEmpty() && !isInitialRoomLoad) {
                    playNewMessageSound()
                }
                
                // Trigger animation system update
                newMessageAnimationTrigger = currentTime
            }
            
            // MEMORY MANAGEMENT: Limit timeline events to prevent memory pressure
            val limitedTimelineEvents = if (sortedTimelineEvents.size > MAX_TIMELINE_EVENTS_PER_ROOM) {
                // Keep the most recent events (sorted by timestamp, newest first in the list)
                sortedTimelineEvents.sortedByDescending { it.timestamp }.take(MAX_TIMELINE_EVENTS_PER_ROOM).sortedBy { it.timestamp }
            } else {
                sortedTimelineEvents
            }
            
            this.timelineEvents = limitedTimelineEvents
            timelineUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily
            
            // PHASE 1: Update Repository in parallel with AppViewModel
            if (currentRoomId.isNotEmpty()) {
                RoomRepository.updateTimeline(currentRoomId, limitedTimelineEvents)
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error in buildTimelineFromChain", e)
            // If building timeline fails, try to preserve existing timeline if possible
            // This prevents the UI from going completely blank
            if (this.timelineEvents.isEmpty()) {
                android.util.Log.w("Andromuks", "AppViewModel: Timeline build failed and timeline is empty, keeping empty timeline")
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Timeline build failed, preserving existing ${this.timelineEvents.size} events")
            }
            // Re-throw to let caller handle it
            throw e
        }
    }
    private fun mergePaginationEvents(newEvents: List<TimelineEvent>) {
        // OPTIMIZED: Early exit if no new events
        if (newEvents.isEmpty()) {
            return
        }
        
        // OPTIMIZED: Use HashMap for fast lookup instead of filtering
        val redactionMap = mutableMapOf<String, TimelineEvent>()
        val regularEvents = mutableListOf<TimelineEvent>()
        
        // Single pass to separate redactions from regular events
        for (event in newEvents) {
            if (event.type == "m.room.redaction") {
                val redactsEventId = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                if (redactsEventId != null) {
                    redactionMap[redactsEventId] = event
                }
            } else {
                regularEvents.add(event)
            }
        }
        
        // Merge regular events, preserving existing entries when duplicates are encountered
        val combinedMap = LinkedHashMap<String, TimelineEvent>(timelineEvents.size + regularEvents.size)
        timelineEvents.forEach { existing ->
            combinedMap[existing.eventId] = existing
        }
        for (event in regularEvents) {
            if (!combinedMap.containsKey(event.eventId)) {
                combinedMap[event.eventId] = event
            }
        }
        
        // OPTIMIZED: Process redactions using HashMap lookup (O(1) instead of O(n))
        for ((targetEventId, redactionEvent) in redactionMap) {
            
            val targetEvent = combinedMap[targetEventId]
            if (targetEvent != null) {
                combinedMap[targetEventId] = targetEvent.copy(redactedBy = redactionEvent.eventId)
            } 
        }
        
        // OPTIMIZED: Use background thread for large merges
        if (combinedMap.size > 200) {
            // Process large merges on background thread
            viewModelScope.launch(Dispatchers.Default) {
                val sortedEvents = combinedMap.values.sortedWith { a, b ->
                    when {
                        a.timelineRowid > 0 && b.timelineRowid > 0 -> a.timelineRowid.compareTo(b.timelineRowid)
                        a.timelineRowid > 0 -> -1
                        b.timelineRowid > 0 -> 1
                        else -> {
                            val tsCompare = a.timestamp.compareTo(b.timestamp)
                            if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                        }
                    }
                }
                
                // MEMORY MANAGEMENT: Limit timeline events to prevent memory pressure
                val limitedTimelineEvents = if (sortedEvents.size > MAX_TIMELINE_EVENTS_PER_ROOM) {
                    // Keep the most recent events
                    sortedEvents.sortedByDescending { it.timestamp }.take(MAX_TIMELINE_EVENTS_PER_ROOM).sortedBy { it.timestamp }
                } else {
                    sortedEvents
                }
                
                // Switch back to main thread for UI update
                withContext(Dispatchers.Main) {
                    this@AppViewModel.timelineEvents = limitedTimelineEvents
                    timelineUpdateCounter++
                    updateCounter++ // Keep for backward compatibility temporarily
                    
                    // PHASE 1: Update Repository in parallel with AppViewModel
                    if (currentRoomId.isNotEmpty()) {
                        RoomRepository.updateTimeline(currentRoomId, limitedTimelineEvents)
                    }
                    
                }
            }
        } else {
            // Synchronous processing for small merges
            val sortedEvents = combinedMap.values.sortedWith { a, b ->
                when {
                    a.timelineRowid > 0 && b.timelineRowid > 0 -> a.timelineRowid.compareTo(b.timelineRowid)
                    a.timelineRowid > 0 -> -1
                    b.timelineRowid > 0 -> 1
                    else -> {
                        val tsCompare = a.timestamp.compareTo(b.timestamp)
                        if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                    }
                }
            }
            
            // MEMORY MANAGEMENT: Limit timeline events to prevent memory pressure
            val limitedTimelineEvents = if (sortedEvents.size > MAX_TIMELINE_EVENTS_PER_ROOM) {
                // Keep the most recent events
                sortedEvents.sortedByDescending { it.timestamp }.take(MAX_TIMELINE_EVENTS_PER_ROOM).sortedBy { it.timestamp }
            } else {
                sortedEvents
            }
            
            this.timelineEvents = limitedTimelineEvents
            timelineUpdateCounter++
            updateCounter++ // Keep for backward compatibility temporarily
            
            // PHASE 1: Update Repository in parallel with AppViewModel
            if (currentRoomId.isNotEmpty()) {
                RoomRepository.updateTimeline(currentRoomId, limitedTimelineEvents)
            }
            
            android.util.Log.d("Andromuks", "AppViewModel: Timeline sorted and updated, timelineUpdateCounter incremented to $timelineUpdateCounter")
        }
        
        // Update smallest rowId for next pagination
        val newSmallest = newEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
        if (newSmallest > 0 && newSmallest < smallestRowId) {
            smallestRowId = newSmallest 
        }
        
    }
    
    fun loadOlderMessages(roomId: String, showToast: Boolean = true) {
        val context = appContext ?: run {
            return
        }

        if (isPaginating) {
            return
        }

        if (!hasMoreMessages) {
            android.util.Log.d("Andromuks", "AppViewModel: No more historical messages available in DB for $roomId")
            if (showToast) {
                android.widget.Toast.makeText(context, "No more messages to load", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        val cacheSize = RoomTimelineCache.getCachedEventCount(roomId)

        isPaginating = true

        if (showToast) {
            android.widget.Toast.makeText(context, "Loading more messages...", android.widget.Toast.LENGTH_SHORT).show()
        }

        viewModelScope.launch(Dispatchers.IO) {
            val limit = 100
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context.applicationContext)
            val eventDao = database.eventDao()

            val oldestMetadata = RoomTimelineCache.getOldestCachedEventMetadata(roomId)
            val oldestRowId = oldestMetadata?.timelineRowId ?: -1L
            val oldestTimestamp = oldestMetadata?.timestamp ?: Long.MAX_VALUE

            android.util.Log.d(
                "Andromuks",
                "AppViewModel: DB fetch parameters for $roomId -> oldestRowId=$oldestRowId, oldestTimestamp=$oldestTimestamp"
            )

            val entities = when {
                oldestRowId > 0 -> eventDao.getEventsBeforeRowId(roomId, oldestRowId, limit)
                oldestTimestamp < Long.MAX_VALUE -> eventDao.getEventsBeforeTimestamp(roomId, oldestTimestamp, limit)
                else -> eventDao.getEventsForRoomAsc(roomId, limit)
            }

            if (entities.isEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: No older events found in DB for room $roomId")
                withContext(Dispatchers.Main) {
                    hasMoreMessages = false
                    isPaginating = false
                    if (showToast) {
                        android.widget.Toast.makeText(context, "No more messages to load", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                return@launch
            }

            val timelineList = entities
                .asReversed() // convert to ascending order
                .mapNotNull { eventEntityToTimelineEvent(it) }

            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Loaded ${timelineList.size} historical events from DB for $roomId (entities=${entities.size})"
            )

            withContext(Dispatchers.Main) {
                if (timelineList.isNotEmpty()) {
                    applyAggregatedReactionsFromEvents(timelineList, "db_load")
                    RoomTimelineCache.mergePaginatedEvents(roomId, timelineList)
                    mergePaginationEvents(timelineList)
                    smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: After DB merge, cache has ${RoomTimelineCache.getCachedEventCount(roomId)} events, smallestRowId=$smallestRowId"
                    )
                } else {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Converted 0 timeline events from DB entities for room $roomId"
                    )
                }

                if (entities.size < limit) {
                    hasMoreMessages = false
                    android.util.Log.d("Andromuks", "AppViewModel: Marking hasMoreMessages=false for $roomId (fetched less than limit)")
                }

                isPaginating = false
            }
        }
    }
    
    
    /**
     * Gets the final event for a bubble, following the edit chain to the latest edit.
     */
    private fun getFinalEventForBubble(entry: EventChainEntry): TimelineEvent {
        // Safety check: ensure ourBubble is not null
        val initialBubble = entry.ourBubble
        if (initialBubble == null) {
            android.util.Log.e("Andromuks", "AppViewModel: getFinalEventForBubble called with null ourBubble for event ${entry.eventId}")
            throw IllegalStateException("Entry ${entry.eventId} has null ourBubble")
        }
        
        // Explicitly type as non-null since we've checked for null above
        var currentEvent: TimelineEvent = requireNotNull(initialBubble) { "ourBubble should be non-null after null check" }
        var currentEntry = entry
        val visitedEvents = mutableSetOf<String>() // Prevent infinite loops
        
        
        // Follow the edit chain to find the latest edit
        var chainDepth = 0
        while (currentEntry.replacedBy != null) {
            // Safety check: limit chain depth to prevent infinite loops
            if (chainDepth >= 20) {
                break
            }
            chainDepth++
            
            val editEventId = currentEntry.replacedBy!!
            
            // Check for infinite loop - only check if we're trying to visit an event we've already processed in this chain
            if (visitedEvents.contains(editEventId)) {
                android.util.Log.w("Andromuks", "AppViewModel: Infinite loop detected! Edit event ${editEventId} already visited in this chain")
                break
            }
            visitedEvents.add(editEventId)
            
            val editEventNullable = editEventsMap[editEventId]
            if (editEventNullable == null) {
                android.util.Log.w("Andromuks", "AppViewModel: Edit event ${editEventId} not found in edit events map")
                break
            }
            
            // editEvent is guaranteed non-null here due to the null check above
            // Use requireNotNull to ensure the compiler recognizes it as non-null
            val editEvent = requireNotNull(editEventNullable) { "Edit event $editEventId should be non-null after null check" }
            
            // Merge the edit content into the current event
            currentEvent = mergeEditContent(currentEvent, editEvent)
            
            // Update current entry to continue following the chain
            // Edit events have their own chain entries, so we can follow them
            val nextEntry = eventChainMap[editEventId]
            if (nextEntry == null) {
                break
            }
            
            // Check if the next entry is the same as the current entry (infinite loop)
            if (nextEntry.eventId == currentEntry.eventId) {
                android.util.Log.w("Andromuks", "AppViewModel: Edit event ${editEventId} points to itself, breaking chain")
                break
            }
            
            currentEntry = nextEntry
        }
        
        return currentEvent
    }
    
    /**
     * Handles event superseding when new events are added to the timeline.
     * 
     * This function ensures that when new events (like edits) are added, they properly
     * supersede the original events they replace, preventing the display of outdated content.
     * 
     * @param existingEvents Current timeline events
     * @param newEvents New events to add
     * @return Updated timeline with proper superseding handled
     */
    private fun handleEventSuperseding(existingEvents: List<TimelineEvent>, newEvents: List<TimelineEvent>): List<TimelineEvent> {
        val result = existingEvents.toMutableList()
        
        for (newEvent in newEvents) {
            // Check if this new event supersedes any existing events
            val supersededEventIds = findSupersededEvents(newEvent, existingEvents)
            
            if (supersededEventIds.isNotEmpty()) {
                // This is an edit event - merge the new content into the original event
                android.util.Log.d("Andromuks", "AppViewModel: Processing edit event ${newEvent.eventId} that supersedes: $supersededEventIds")
                for (supersededEventId in supersededEventIds) {
                    val originalEventIndex = result.indexOfFirst { it.eventId == supersededEventId }
                    if (originalEventIndex != -1) {
                        val originalEvent = result[originalEventIndex]
                        //android.util.Log.d("Andromuks", "AppViewModel: Found original event at index $originalEventIndex: ${originalEvent.eventId}")
                        //android.util.Log.d("Andromuks", "AppViewModel: Original event body before merge: ${originalEvent.decrypted?.optString("body", "null")}")
                        
                        // Create a new event that merges the original event with the edit content
                        val mergedEvent = mergeEditContent(originalEvent, newEvent)
                        result[originalEventIndex] = mergedEvent
                        //android.util.Log.d("Andromuks", "AppViewModel: Merged edit content into original event $supersededEventId")
                        //android.util.Log.d("Andromuks", "AppViewModel: Final merged event body: ${mergedEvent.decrypted?.optString("body", "null")}")
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: WARNING - Could not find original event $supersededEventId in timeline")
                    }
                }
            } else {
                // This is a regular new event - add it to the timeline
                result.add(newEvent)
                android.util.Log.d("Andromuks", "AppViewModel: Added new event ${newEvent.eventId}")
            }
        }
        
        return result
    }
    
    /**
     * Finds events that are superseded by a new event.
     * 
     * @param newEvent The new event that might supersede others
     * @param existingEvents List of existing events to check
     * @return List of event IDs that are superseded by the new event
     */
    private fun findSupersededEvents(newEvent: TimelineEvent, existingEvents: List<TimelineEvent>): List<String> {
        android.util.Log.d("Andromuks", "AppViewModel: findSupersededEvents called for event ${newEvent.eventId}")
        val supersededEventIds = mutableListOf<String>()
        
        // Check if this is an edit event (m.replace relationship)
        val relatesTo = when {
            newEvent.type == "m.room.message" -> newEvent.content?.optJSONObject("m.relates_to")
            newEvent.type == "m.room.encrypted" && newEvent.decryptedType == "m.room.message" -> newEvent.decrypted?.optJSONObject("m.relates_to")
            else -> null
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: relatesTo for event ${newEvent.eventId}: $relatesTo")
        
        val relatesToEventId = relatesTo?.optString("event_id")
        val relType = relatesTo?.optString("rel_type")
        
        android.util.Log.d("Andromuks", "AppViewModel: relatesToEventId: $relatesToEventId, relType: $relType")
        
        if (relType == "m.replace" && relatesToEventId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: This is an edit event targeting $relatesToEventId")
            // This is an edit event - find the original event it replaces
            val originalEvent = existingEvents.find { it.eventId == relatesToEventId }
            if (originalEvent != null) {
                supersededEventIds.add(originalEvent.eventId)
                android.util.Log.d("Andromuks", "AppViewModel: Edit event ${newEvent.eventId} supersedes original event ${originalEvent.eventId}")
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: WARNING - Could not find original event $relatesToEventId in existing events")
                android.util.Log.d("Andromuks", "AppViewModel: Available event IDs: ${existingEvents.map { it.eventId }}")
            }
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Not an edit event (relType: $relType, relatesToEventId: $relatesToEventId)")
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Returning superseded event IDs: $supersededEventIds")
        return supersededEventIds
    }
    
    /**
     * Checks if an event is superseded by another event.
     * 
     * @param event The event to check if it's superseded
     * @param otherEvent The other event that might supersede it
     * @return True if the event is superseded by the other event
     */
    private fun isEventSupersededBy(event: TimelineEvent, otherEvent: TimelineEvent): Boolean {
        // Check if otherEvent is an edit that supersedes this event
        val relatesTo = when {
            otherEvent.type == "m.room.message" -> otherEvent.content?.optJSONObject("m.relates_to")
            otherEvent.type == "m.room.encrypted" && otherEvent.decryptedType == "m.room.message" -> otherEvent.decrypted?.optJSONObject("m.relates_to")
            else -> null
        }
        
        val relatesToEventId = relatesTo?.optString("event_id")
        val relType = relatesTo?.optString("rel_type")
        
        android.util.Log.d("Andromuks", "AppViewModel: Checking if ${event.eventId} is superseded by ${otherEvent.eventId}")
        android.util.Log.d("Andromuks", "AppViewModel: otherEvent type: ${otherEvent.type}, relatesTo: $relatesTo")
        android.util.Log.d("Andromuks", "AppViewModel: relatesToEventId: $relatesToEventId, relType: $relType")
        
        val isSuperseded = relType == "m.replace" && relatesToEventId == event.eventId
        android.util.Log.d("Andromuks", "AppViewModel: isSuperseded: $isSuperseded")
        
        return isSuperseded
    }
    
    /**
     * Merges edit content into the original event.
     * 
     * This function takes the original event and merges the new content from an edit event,
     * preserving the original event's structure while updating the content.
     * 
     * @param originalEvent The original event to be updated
     * @param editEvent The edit event containing the new content
     * @return A new TimelineEvent with merged content
     */
    private fun mergeEditContent(originalEvent: TimelineEvent, editEvent: TimelineEvent): TimelineEvent {
        // Create a new content JSON object based on the original event
        val mergedContent = JSONObject(originalEvent.content.toString())
        
        // Get the new content from the edit event
        // For encrypted rooms, look in decrypted field; for non-encrypted rooms, look in content field
        val newContent = when {
            editEvent.type == "m.room.encrypted" -> editEvent.decrypted?.optJSONObject("m.new_content")
            editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.new_content")
            else -> null
        }
        if (newContent != null) {
            // Create a completely new decrypted content object with the new content
            // Use the new content directly as the merged decrypted content
            val mergedDecrypted = JSONObject(newContent.toString())

            // For non-encrypted rooms, also update the content field
            val finalContent = if (originalEvent.type == "m.room.message") {
                // For non-encrypted rooms, update the content field with new content
                val updatedContent = JSONObject(originalEvent.content.toString())
                updatedContent.put("body", newContent.optString("body", ""))
                updatedContent.put("msgtype", newContent.optString("msgtype", "m.text"))
                updatedContent
            } else {
                // For encrypted rooms, keep the original content
                mergedContent
            }
            
            // Create the merged event with updated content
            val mergedEvent = originalEvent.copy(
                content = finalContent,
                decrypted = mergedDecrypted
            )
            return mergedEvent
        }
        // If no new content, return the original event
        return originalEvent
    }
    
    
    private fun processRoomInvites(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data")
        if (data != null) {
            val invitedRooms = data.optJSONArray("invited_rooms")
            if (invitedRooms != null) {
                android.util.Log.d("Andromuks", "AppViewModel: Processing ${invitedRooms.length()} room invitations")
                
                for (i in 0 until invitedRooms.length()) {
                    val inviteJson = invitedRooms.optJSONObject(i)
                    if (inviteJson != null) {
                        val roomId = inviteJson.optString("room_id", "")
                        val createdAt = inviteJson.optLong("created_at", 0)
                        val inviteState = inviteJson.optJSONArray("invite_state")
                        
                        if (roomId.isNotBlank() && inviteState != null) {
                            var inviterUserId = ""
                            var inviterDisplayName: String? = null
                            var roomName: String? = null
                            var roomAvatar: String? = null
                            var roomTopic: String? = null
                            var roomCanonicalAlias: String? = null
                            var inviteReason: String? = null
                            var isDirectMessage = false
                            
                            // Parse invite state events
                            for (j in 0 until inviteState.length()) {
                                val stateEvent = inviteState.optJSONObject(j)
                                if (stateEvent != null) {
                                    val eventType = stateEvent.optString("type", "")
                                    val sender = stateEvent.optString("sender", "")
                                    val content = stateEvent.optJSONObject("content")
                                    
                                    when (eventType) {
                                        "m.room.member" -> {
                                            val stateKey = stateEvent.optString("state_key", "")
                                            val membership = content?.optString("membership", "")
                                            if (membership == "invite" && stateKey.isNotBlank()) {
                                                inviterUserId = sender
                                                inviterDisplayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                                inviteReason = content?.optString("reason")?.takeIf { it.isNotBlank() }
                                                // Check if is_direct is true in the member event
                                                val isDirect = content?.optBoolean("is_direct", false) ?: false
                                                if (isDirect) {
                                                    isDirectMessage = true
                                                }
                                            }
                                        }
                                        "m.room.create" -> {
                                            // Check if additional_creators contains our user ID (indicates DM request)
                                            val additionalCreators = content?.optJSONArray("additional_creators")
                                            if (additionalCreators != null) {
                                                for (k in 0 until additionalCreators.length()) {
                                                    val creatorId = additionalCreators.optString(k, "")
                                                    if (creatorId == currentUserId) {
                                                        isDirectMessage = true
                                                        android.util.Log.d("Andromuks", "AppViewModel: Detected DM invite - additional_creators contains current user")
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                        "m.room.name" -> {
                                            roomName = content?.optString("name")?.takeIf { it.isNotBlank() }
                                        }
                                        "m.room.avatar" -> {
                                            roomAvatar = content?.optString("url")?.takeIf { it.isNotBlank() }
                                        }
                                        "m.room.topic" -> {
                                            roomTopic = content?.optString("topic")?.takeIf { it.isNotBlank() }
                                        }
                                        "m.room.canonical_alias" -> {
                                            roomCanonicalAlias = content?.optString("alias")?.takeIf { it.isNotBlank() }
                                        }
                                    }
                                }
                            }
                            
                            if (inviterUserId.isNotBlank()) {
                                val invite = RoomInvite(
                                    roomId = roomId,
                                    createdAt = createdAt,
                                    inviterUserId = inviterUserId,
                                    inviterDisplayName = inviterDisplayName,
                                    roomName = roomName,
                                    roomAvatar = roomAvatar,
                                    roomTopic = roomTopic,
                                    roomCanonicalAlias = roomCanonicalAlias,
                                    inviteReason = inviteReason,
                                    isDirectMessage = isDirectMessage
                                )
                                
                                val isNewInvite = !pendingInvites.containsKey(roomId)
                                pendingInvites[roomId] = invite
                                android.util.Log.d("Andromuks", "AppViewModel: Added room invite: $roomName from $inviterUserId (DM: $isDirectMessage)")
                                
                                // CRITICAL: Save invite to database for persistence
                                appContext?.let { context ->
                                    viewModelScope.launch(Dispatchers.IO) {
                                        try {
                                            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
                                            val inviteEntity = net.vrkknn.andromuks.database.entities.InviteEntity(
                                                roomId = invite.roomId,
                                                createdAt = invite.createdAt,
                                                inviterUserId = invite.inviterUserId,
                                                inviterDisplayName = invite.inviterDisplayName,
                                                roomName = invite.roomName,
                                                roomAvatar = invite.roomAvatar,
                                                roomTopic = invite.roomTopic,
                                                roomCanonicalAlias = invite.roomCanonicalAlias,
                                                inviteReason = invite.inviteReason,
                                                isDirectMessage = invite.isDirectMessage
                                            )
                                            database.inviteDao().upsert(inviteEntity)
                                            android.util.Log.d("Andromuks", "AppViewModel: Saved invite to database: $roomId")
                                        } catch (e: Exception) {
                                            android.util.Log.e("Andromuks", "AppViewModel: Error saving invite to database", e)
                                        }
                                    }
                                }
                                
                                // CRITICAL: If this is a new invite, immediately trigger UI update
                                // This bypasses the debounce to show invites as soon as they arrive
                                if (isNewInvite) {
                                    android.util.Log.d("Andromuks", "AppViewModel: New invite detected - immediately updating room list UI")
                                    needsRoomListUpdate = true
                                    // Force immediate UI update (bypass debounce for invites)
                                    roomListUpdateCounter++
                                    android.util.Log.d("Andromuks", "AppViewModel: Invite UI update triggered immediately (roomListUpdateCounter: $roomListUpdateCounter)")
                                }
                            }
                        }
                    }
                }
                
                android.util.Log.d("Andromuks", "AppViewModel: Total pending invites: ${pendingInvites.size}")
            }
        }
    }
    
    fun markRoomAsRead(roomId: String, eventId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: markRoomAsRead called with roomId: '$roomId', eventId: '$eventId'")
        
        // Try to mark as read immediately
        val result = markRoomAsReadInternal(roomId, eventId)
        
        // If WebSocket is not available, queue the operation for retry when connection is restored
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: markRoomAsRead failed with result: $result - queuing for retry when connection is restored")
            pendingWebSocketOperations.add(
                PendingWebSocketOperation(
                    type = "markRoomAsRead",
                    data = mapOf(
                        "roomId" to roomId,
                        "eventId" to eventId
                    )
                )
            )
        }
    }
    
    private fun markRoomAsReadInternal(roomId: String, eventId: String): WebSocketResult {
        android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadInternal called")
        val markReadRequestId = requestIdCounter++
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        )
        
        val result = sendWebSocketCommand("mark_read", markReadRequestId, commandData)
        
        if (result == WebSocketResult.SUCCESS) {
            markReadRequests[markReadRequestId] = roomId
            android.util.Log.d("Andromuks", "AppViewModel: Mark read queued with request_id: $markReadRequestId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send mark read, result: $result")
        }
        
        return result
    }
    
    fun getRoomSummary(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Getting room summary for invite: $roomId")
        
        val summaryRequestId = requestIdCounter++
        roomSummaryRequests[summaryRequestId] = roomId
        val via = roomId.substringAfter(":").substringBefore(".") // Extract server from room ID
        sendWebSocketCommand("get_room_summary", summaryRequestId, mapOf(
            "room_id_or_alias" to roomId,
            "via" to listOf(via)
        ))
    }
    
    fun acceptRoomInvite(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Accepting room invite: $roomId")
        
        // CRITICAL: Preemptively mark room as newly joined so it appears at top when sync arrives
        newlyJoinedRoomIds.add(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Preemptively marked room $roomId as newly joined")
        
        val acceptRequestId = requestIdCounter++
        joinRoomRequests[acceptRequestId] = roomId
        val via = roomId.substringAfter(":").substringBefore(".") // Extract server from room ID
        sendWebSocketCommand("join_room", acceptRequestId, mapOf(
            "room_id_or_alias" to roomId,
            "via" to listOf(via)
        ))
        
        // Remove from pending invites
        pendingInvites.remove(roomId)
        
        // Remove from database
        appContext?.let { context ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
                    database.inviteDao().deleteInvite(roomId)
                    android.util.Log.d("Andromuks", "AppViewModel: Removed invite from database: $roomId")
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error removing invite from database", e)
                }
            }
        }
        
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun refuseRoomInvite(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Refusing room invite: $roomId")
        
        val refuseRequestId = requestIdCounter++
        leaveRoomRequests[refuseRequestId] = roomId
        sendWebSocketCommand("leave_room", refuseRequestId, mapOf(
            "room_id" to roomId
        ))
        
        // Remove from pending invites
        pendingInvites.remove(roomId)
        
        // Remove from database
        appContext?.let { context ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
                    database.inviteDao().deleteInvite(roomId)
                    android.util.Log.d("Andromuks", "AppViewModel: Removed invite from database: $roomId")
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error removing invite from database", e)
                }
            }
        }
        
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
    }
    
    fun handleMarkReadResponse(requestId: Int, success: Boolean) {
        val roomId = markReadRequests[requestId]
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Mark read response for room $roomId: $success")
            // Remove the request from pending
            markReadRequests.remove(requestId)
            
            // Invoke completion callback for notification actions
            notificationActionCompletionCallbacks.remove(requestId)?.invoke()
        }
    }
    
    fun handleRoomSummaryResponse(requestId: Int, data: Any) {
        val roomId = roomSummaryRequests[requestId]
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Room summary response for room $roomId")
            // Room summary received - could be used to update invite details if needed
            roomSummaryRequests.remove(requestId)
        }
    }
    
    fun handleJoinRoomResponse(requestId: Int, data: Any) {
        val roomId = joinRoomRequests[requestId]
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Join room response for room $roomId")
            // Room join successful - invite will be removed from sync
            joinRoomRequests.remove(requestId)
        }
    }
    
    fun leaveRoom(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Leaving room: $roomId")
        
        val leaveRequestId = requestIdCounter++
        leaveRoomRequests[leaveRequestId] = roomId
        sendWebSocketCommand("leave_room", leaveRequestId, mapOf(
            "room_id" to roomId
        ))
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent leave_room command for $roomId with requestId=$leaveRequestId")
    }
    
    fun handleLeaveRoomResponse(requestId: Int, data: Any) {
        val roomId = leaveRoomRequests[requestId]
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Leave room response for room $roomId")
            // Room leave successful - room will be removed from sync
            leaveRoomRequests.remove(requestId)
        }
    }
    /**
     * Send WebSocket command to the backend
     * Commands are sent individually with sequential request IDs
     */
    private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): WebSocketResult {
        // Handle offline mode
        if (isOfflineMode && !isOfflineCapableCommand(command)) {
            android.util.Log.w("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Command $command queued for offline retry")
            queueCommandForOfflineRetry(command, requestId, data)
            return WebSocketResult.NOT_CONNECTED
        }
        
        val ws = WebSocketService.getWebSocket()
        if (ws == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket is not connected, cannot send command: $command")
            return WebSocketResult.NOT_CONNECTED
        }
        
        // Send command immediately
        return try {
            val json = org.json.JSONObject()
            json.put("command", command)
            json.put("request_id", requestId)
            json.put("data", org.json.JSONObject(data))
            val jsonString = json.toString()
            android.util.Log.d("Andromuks", "Websocket: $command (reqId: $requestId) -> $jsonString")
            webSocket?.send(jsonString)
            WebSocketResult.SUCCESS
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to send WebSocket command: $command", e)
            WebSocketResult.CONNECTION_ERROR
        }
    }
    
    /**
     * NETWORK OPTIMIZATION: Check if command can work offline (use cached data)
     */
    private fun isOfflineCapableCommand(command: String): Boolean {
        return when (command) {
            "get_profile", "get_room_state" -> true // These can use cached data
            else -> false
        }
    }
    
    /**
     * NETWORK OPTIMIZATION: Queue command for retry when connection is restored
     */
    private fun queueCommandForOfflineRetry(command: String, requestId: Int, data: Map<String, Any>) {
        // Add to pending operations for retry when connection is restored
        if (pendingWebSocketOperations.size < 50) { // Limit offline queue size
            pendingWebSocketOperations.add(
                PendingWebSocketOperation(
                    type = "offline_$command",
                    data = mapOf(
                        "command" to command,
                        "requestId" to requestId,
                        "data" to data
                    ),
                    retryCount = 0
                )
            )
            android.util.Log.d("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Queued offline command: $command")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Offline queue full, dropping command: $command")
        }
    }
    
    /**
     * Send get_event command to retrieve full event details from server
     * Useful when we only have partial event information (e.g., for reply previews)
     */
    fun getEvent(roomId: String, eventId: String, callback: (TimelineEvent?) -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: getEvent called for roomId: '$roomId', eventId: '$eventId'")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with null, health monitor will handle reconnection")
            callback(null)
            return
        }
        
        val eventRequestId = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_event: $eventRequestId")
        
        // Store the callback to handle the response
        eventRequests[eventRequestId] = roomId to callback
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: get_event with data: $commandData")
        sendWebSocketCommand("get_event", eventRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $eventRequestId")
        
        // Add timeout mechanism to prevent infinite loading
        viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = 10000L // 10 second timeout
            android.util.Log.d("Andromuks", "AppViewModel: Setting get_event timeout to ${timeoutMs}ms for requestId=$eventRequestId")
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

    // Settings functions
    fun toggleShowUnprocessedEvents() {
        showUnprocessedEvents = !showUnprocessedEvents
        
        // Save setting to SharedPreferences
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("show_unprocessed_events", showUnprocessedEvents)
                .apply()
            android.util.Log.d("Andromuks", "AppViewModel: Saved showUnprocessedEvents setting: $showUnprocessedEvents")
        }
    }
    
    fun toggleCompression() {
        enableCompression = !enableCompression
        
        // Save setting to SharedPreferences
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("enable_compression", enableCompression)
                .apply()
            android.util.Log.d("Andromuks", "AppViewModel: Saved enableCompression setting: $enableCompression")
        }
        
        // Restart WebSocket with new compression setting
        android.util.Log.d("Andromuks", "AppViewModel: Restarting WebSocket due to compression setting change")
        restartWebSocket("Compression setting changed")
    }
    
    /**
     * Load settings from SharedPreferences
     */
    fun loadSettings(context: Context? = null) {
        val contextToUse = context ?: appContext
        contextToUse?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            showUnprocessedEvents = prefs.getBoolean("show_unprocessed_events", true) // Default to true
            enableCompression = prefs.getBoolean("enable_compression", true) // Default to true
            android.util.Log.d("Andromuks", "AppViewModel: Loaded showUnprocessedEvents setting: $showUnprocessedEvents")
            android.util.Log.d("Andromuks", "AppViewModel: Loaded enableCompression setting: $enableCompression")
        }
    }

    /**
     * Starts the WebSocket service to maintain connection in background
     * 
     * Note: The service primarily shows a foreground notification to prevent
     * Android from killing the app process. The actual WebSocket connection
     * is managed by NetworkUtils and AppViewModel.
     */
    fun startWebSocketService() {
        appContext?.let { context ->
            android.util.Log.d("Andromuks", "AppViewModel: Starting WebSocket foreground service")
            val intent = android.content.Intent(context, WebSocketService::class.java)
            context.startForegroundService(intent)
            
            // Service will manage connection lifecycle once started
        }
    }

    /**
     * Stops the WebSocket service (used on logout or app cleanup)
     */
    fun stopWebSocketService() {
        android.util.Log.d("Andromuks", "AppViewModel: Stopping WebSocket service")
        
        appContext?.let { context ->
            // Use intent-based stop to ensure proper cleanup within Android timeout
            val intent = android.content.Intent(context, WebSocketService::class.java)
            intent.action = "STOP_SERVICE"
            context.startService(intent)
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket service stop intent sent")
        }
    }
    
    // User Info Functions
    
    /**
     * Requests encryption info for a user
     */
    fun requestUserEncryptionInfo(userId: String, callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting encryption info for user: $userId")
        
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return
        }
        
        val requestId = requestIdCounter++
        userEncryptionInfoRequests[requestId] = callback
        
        sendWebSocketCommand("get_profile_encryption_info", requestId, mapOf(
            "user_id" to userId
        ))
    }
    
    /**
     * Requests mutual rooms with a user
     */
    fun requestMutualRooms(userId: String, callback: (List<String>?, String?) -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting mutual rooms with user: $userId")
        
        if (webSocket == null) {
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
    fun trackUserDevices(userId: String, callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: Tracking devices for user: $userId")
        
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return
        }
        
        val requestId = requestIdCounter++
        trackDevicesRequests[requestId] = callback
        
        sendWebSocketCommand("track_user_devices", requestId, mapOf(
            "user_id" to userId
        ))
    }
    
    /**
     * Gets all messages in a thread (thread root + all replies)
     * @param roomId The room containing the thread
     * @param threadRootEventId The event ID that started the thread
     * @return List of timeline events in the thread, sorted by timestamp
     */
    fun getThreadMessages(roomId: String, threadRootEventId: String): List<TimelineEvent> {
        // Only return thread messages if we're in the same room
        if (currentRoomId != roomId) {
            android.util.Log.w("Andromuks", "AppViewModel: getThreadMessages called for different room")
            return emptyList()
        }
        
        val threadMessages = mutableListOf<TimelineEvent>()
        
        // Add the thread root message first
        val rootMessage = timelineEvents.find { it.eventId == threadRootEventId }
        if (rootMessage != null) {
            threadMessages.add(rootMessage)
            android.util.Log.d("Andromuks", "AppViewModel: Found thread root: $threadRootEventId")
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
        
        android.util.Log.d("Andromuks", "AppViewModel: getThreadMessages - found ${sortedMessages.size} messages in thread (1 root + ${threadReplies.size} replies)")
        
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
    ) {
        android.util.Log.d("Andromuks", "AppViewModel: sendThreadReply called - roomId: $roomId, text: '$text', threadRoot: $threadRootEventId, fallbackReply: $fallbackReplyToEventId")
        
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - cannot send thread reply, health monitor will handle reconnection")
            return
        }
        
        val messageRequestId = requestIdCounter++
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        
        // Build the thread reply structure
        val relatesTo = mutableMapOf<String, Any>(
            "rel_type" to "m.thread",
            "event_id" to threadRootEventId,
            "is_falling_back" to true
        )
        
        // Add fallback reply-to for clients without thread support
        if (fallbackReplyToEventId != null) {
            relatesTo["m.in_reply_to"] = mapOf("event_id" to fallbackReplyToEventId)
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to relatesTo,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: Sending thread reply with data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
    }
    
    /**
     * Requests complete user profile information (profile, encryption info, mutual rooms)
     */
    fun requestFullUserInfo(userId: String, callback: (net.vrkknn.andromuks.utils.UserProfileInfo?, String?) -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting full user info for: $userId")
        
        var displayName: String? = null
        var avatarUrl: String? = null
        var timezone: String? = null
        var encryptionInfo: net.vrkknn.andromuks.utils.UserEncryptionInfo? = null
        var mutualRooms: List<String> = emptyList()
        
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
            
            android.util.Log.d("Andromuks", "AppViewModel: Full user info progress - profile: $profileCompleted, encryption: $encryptionCompleted, mutualRooms: $mutualRoomsCompleted (expected: $expectedRequests, completed: $completedCount)")
            
            if (completedCount >= expectedRequests && !hasError) {
                val profileInfo = net.vrkknn.andromuks.utils.UserProfileInfo(
                    userId = userId,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    timezone = timezone,
                    encryptionInfo = encryptionInfo,
                    mutualRooms = mutualRooms
                )
                android.util.Log.d("Andromuks", "AppViewModel: Full user info completed for $userId")
                callback(profileInfo, null)
            } else if (!hasError) {
                android.util.Log.d("Andromuks", "AppViewModel: Full user info still waiting for requests to complete (${completedCount}/$expectedRequests)")
            }
        }
        
        // Request 1: Profile
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
            android.util.Log.d("Andromuks", "AppViewModel: Skipping mutual rooms request for self")
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
                timezone = profileData.optString("us.cloke.msc4175.tz")?.takeIf { it.isNotBlank() }
                android.util.Log.d("Andromuks", "AppViewModel: Profile data received for $userId - display: $displayName, avatar: ${avatarUrl != null}, timezone: $timezone")
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
                        encryptionInfo = encryptionInfo,
                        mutualRooms = mutualRooms
                    )
                    android.util.Log.d("Andromuks", "AppViewModel: Returning partial user info after timeout for $userId")
                    callback(profileInfo, null)
                }
            }
        }
    }
    
    // Temporary storage for full user info profile callbacks
    private val fullUserInfoCallbacks = mutableMapOf<Int, (JSONObject?) -> Unit>()
    
    private fun handleUserEncryptionInfoResponse(requestId: Int, data: Any) {
        val callback = userEncryptionInfoRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling encryption info response for requestId: $requestId")
        
        try {
            val encInfo = parseUserEncryptionInfo(data)
            callback(encInfo, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing encryption info", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun handleMutualRoomsResponse(requestId: Int, data: Any) {
        val callback = mutualRoomsRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling mutual rooms response for requestId: $requestId")
        
        try {
            val roomsList = when (data) {
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        list.add(data.getString(i))
                    }
                    list
                }
                is List<*> -> data.mapNotNull { it as? String }
                else -> {
                    android.util.Log.e("Andromuks", "AppViewModel: Unexpected data type for mutual rooms")
                    emptyList()
                }
            }
            callback(roomsList, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing mutual rooms", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun handleTrackDevicesResponse(requestId: Int, data: Any) {
        val callback = trackDevicesRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling track devices response for requestId: $requestId")
        
        try {
            val encInfo = parseUserEncryptionInfo(data)
            callback(encInfo, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing track devices response", e)
            callback(null, "Error: ${e.message}")
        }
    }
    
    private fun parseUserEncryptionInfo(data: Any): net.vrkknn.andromuks.utils.UserEncryptionInfo {
        val jsonData = when (data) {
            is JSONObject -> data
            else -> JSONObject(data.toString())
        }
        
        val devicesTracked = jsonData.optBoolean("devices_tracked", false)
        val devicesArray = jsonData.optJSONArray("devices")
        val devices = if (devicesArray != null) {
            val list = mutableListOf<net.vrkknn.andromuks.utils.DeviceInfo>()
            for (i in 0 until devicesArray.length()) {
                val deviceJson = devicesArray.getJSONObject(i)
                list.add(
                    net.vrkknn.andromuks.utils.DeviceInfo(
                        deviceId = deviceJson.getString("device_id"),
                        name = deviceJson.getString("name"),
                        identityKey = deviceJson.getString("identity_key"),
                        signingKey = deviceJson.getString("signing_key"),
                        fingerprint = deviceJson.getString("fingerprint"),
                        trustState = deviceJson.getString("trust_state")
                    )
                )
            }
            list
        } else {
            null
        }
        
        return net.vrkknn.andromuks.utils.UserEncryptionInfo(
            devicesTracked = devicesTracked,
            devices = devices,
            masterKey = jsonData.optString("master_key")?.takeIf { it.isNotBlank() },
            firstMasterKey = jsonData.optString("first_master_key")?.takeIf { it.isNotBlank() },
            userTrusted = jsonData.optBoolean("user_trusted", false),
            errors = jsonData.opt("errors")
        )
    }
    
    /**
     * Send a raw WebSocket message
     */
    fun sendWebSocketMessage(message: String) {
        webSocket?.send(message)
    }
    
    /**
     * Navigate to a room after joining
     * If room already exists, navigate immediately. Otherwise wait for sync.
     */
    fun joinRoomAndNavigate(roomId: String, navController: androidx.navigation.NavController) {
        android.util.Log.d("Andromuks", "AppViewModel: joinRoomAndNavigate called for $roomId")
        
        // Navigate directly - RoomTimelineScreen's LaunchedEffect will handle timeline loading
        val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
        navController.navigate("room_timeline/$encodedRoomId")
        // Timeline loading will be handled by RoomTimelineScreen's LaunchedEffect(roomId)
        // which calls requestRoomTimeline, which will use reset=true for newly joined rooms
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
        
        val request = org.json.JSONObject().apply {
            put("command", "resolve_alias")
            put("request_id", requestId)
            put("data", org.json.JSONObject().apply {
                put("alias", alias)
            })
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Sending resolve_alias for $alias with requestId=$requestId")
        webSocket?.send(request.toString())
    }
    
    /**
     * Get room summary with callback
     */
    fun getRoomSummary(roomIdOrAlias: String, viaServers: List<String>, callback: (Pair<net.vrkknn.andromuks.utils.RoomSummary?, String?>?) -> Unit) {
        val requestId = requestIdCounter++
        getRoomSummaryRequests[requestId] = callback
        
        val request = org.json.JSONObject().apply {
            put("command", "get_room_summary")
            put("request_id", requestId)
            put("data", org.json.JSONObject().apply {
                put("room_id_or_alias", roomIdOrAlias)
                put("via", org.json.JSONArray(viaServers))
            })
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Sending get_room_summary for $roomIdOrAlias with requestId=$requestId")
        webSocket?.send(request.toString())
    }
    
    /**
     * Join room with callback
     */
    fun joinRoomWithCallback(roomIdOrAlias: String, viaServers: List<String>, callback: (Pair<String?, String?>?) -> Unit) {
        val requestId = requestIdCounter++
        joinRoomCallbacks[requestId] = callback
        
        // Store the roomIdOrAlias so we can mark it as newly joined when we get the response
        // We'll mark it in handleJoinRoomCallbackResponse when we get the actual room ID
        
        val request = org.json.JSONObject().apply {
            put("command", "join_room")
            put("request_id", requestId)
            put("data", org.json.JSONObject().apply {
                put("room_id_or_alias", roomIdOrAlias)
                if (viaServers.isNotEmpty()) {
                    put("via", org.json.JSONArray(viaServers))
                }
            })
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Sending join_room for $roomIdOrAlias with requestId=$requestId")
        webSocket?.send(request.toString())
    }
    
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
                android.util.Log.d("Andromuks", "AppViewModel: Resolved alias to roomId=$roomId, servers=$servers")
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
            android.util.Log.d("Andromuks", "AppViewModel: Got room summary for ${summary.roomId}")
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
                android.util.Log.d("Andromuks", "AppViewModel: Joined room successfully, roomId=$roomId")
                
                // CRITICAL: Preemptively mark room as newly joined so it appears at top when sync arrives
                newlyJoinedRoomIds.add(roomId)
                android.util.Log.d("Andromuks", "AppViewModel: Preemptively marked room $roomId as newly joined from joinRoomWithCallback")
                
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
        // Log app start
        logActivity("App Started")
        
        // Start periodic cleanup job
        viewModelScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000) // Run every 5 minutes
                performPeriodicMemoryCleanup()
            }
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Periodic cleanup of stale data to prevent memory pressure
     */
    private fun performPeriodicMemoryCleanup() {
        try {
            // Clean up stale animation states
            performAnimationStateCleanup()
            
            // Clean up stale member cache entries
            performMemberCacheCleanup()
            
            // Clean up stale message versions (keep only recent ones)
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - (7 * 24 * 60 * 60 * 1000) // 7 days ago
            
            val versionsToRemove = messageVersions.filter { (_, versioned) ->
                versioned.versions.isNotEmpty() && 
                versioned.versions.first().timestamp < cutoffTime
            }.keys
            
            if (versionsToRemove.isNotEmpty()) {
                versionsToRemove.forEach { eventId ->
                    messageVersions.remove(eventId)
                    editToOriginal.remove(eventId)
                    redactionCache.remove(eventId)
                }
                android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${versionsToRemove.size} old message versions")
            }
            
            // Clean up old processed reactions
            if (processedReactions.size > 200) {
                val toRemove = processedReactions.take(processedReactions.size - 100)
                processedReactions.removeAll(toRemove)
                android.util.Log.d("Andromuks", "AppViewModel: Cleaned up ${toRemove.size} old processed reactions")
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
     * Parse timeline response data into structured format
     */
    private fun parseTimelineResponseData(
        data: Any,
        requestId: Int,
        isPaginate: Boolean
    ): TimelineResponseData {
        return when (data) {
            is JSONArray -> TimelineResponseData(events = data)
            is JSONObject -> {
                val eventsArray = data.optJSONArray("events")
                val hasMore = if (isPaginate && paginateRequests.containsKey(requestId)) {
                    data.optBoolean("has_more", true)
                } else {
                    true
                }
                val receipts = data.optJSONObject("receipts")
                val fromServer = data.optBoolean("from_server", false)
                
                TimelineResponseData(
                    events = eventsArray,
                    hasMore = hasMore,
                    receipts = receipts,
                    fromServer = fromServer
                )
            }
            else -> TimelineResponseData.empty()
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
     * Extract reaction event from timeline event
     */
    private fun extractReactionEvent(event: TimelineEvent): ReactionEvent? {
        val content = event.content ?: return null
        val relatesTo = content.optJSONObject("m.relates_to") ?: return null
        
        val relatesToEventId = relatesTo.optString("event_id")
        val emoji = relatesTo.optString("key")
        val relType = relatesTo.optString("rel_type")
        
        return if (relatesToEventId.isNotBlank() && emoji.isNotBlank() && relType == "m.annotation") {
            ReactionEvent(
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
        } else {
            null
        }
    }
    

    
    /**
     * Process member event - update cache
     */
    private fun processMemberEvent(
        event: TimelineEvent,
        memberMap: MutableMap<String, MemberProfile>
    ): Boolean {
        if (event.type != "m.room.member" || event.timelineRowid != -1L) return false
        
        val userId = event.stateKey ?: event.sender
        val profile = extractProfileFromMemberEvent(event)
        val previousProfile = memberMap[userId]
        
        memberMap[userId] = profile
        globalProfileCache[userId] = CachedProfileEntry(profile, System.currentTimeMillis())
        
        return if (isProfileChange(previousProfile, profile, event)) {
            memberUpdateCounter++
            android.util.Log.d("Andromuks", "AppViewModel: Profile change detected in timeline for $userId")
            true
        } else {
            false
        }
    }
    
    /**
     * Process reaction event from timeline
     */
    private fun processReactionFromTimeline(event: TimelineEvent): Boolean {
        if (event.type != "m.reaction") return false
        
        val reaction = extractReactionEvent(event) ?: return false
        
        return if (event.redactedBy == null) {
            processReactionEvent(reaction, isHistorical = true)
            true
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping redacted historical reaction: ${reaction.emoji} from ${reaction.sender} to ${reaction.relatesToEventId}")
            false
        }
    }
    
    /**
     * Build edit chains from events
     */
    private fun buildEditChainsFromEvents(timelineList: List<TimelineEvent>) {
        eventChainMap.clear()
        editEventsMap.clear()
        
        for (event in timelineList) {
            if (isEditEvent(event)) {
                editEventsMap[event.eventId] = event
                android.util.Log.d("Andromuks", "AppViewModel: Added edit event ${event.eventId} to edit events map")
            } else {
                eventChainMap[event.eventId] = EventChainEntry(
                    eventId = event.eventId,
                    ourBubble = event,
                    replacedBy = null,
                    originalTimestamp = event.timestamp
                )
                android.util.Log.d("Andromuks", "AppViewModel: Added regular event ${event.eventId} to chain mapping")
            }
        }
    }
    
    /**
     * Handle background prefetch request
     */
    private fun handleBackgroundPrefetch(
        roomId: String,
        timelineList: List<TimelineEvent>
    ): Int {
        android.util.Log.d("Andromuks", "AppViewModel: Processing background prefetch request, silently adding ${timelineList.size} events to cache (roomId: $roomId)")
        RoomTimelineCache.mergePaginatedEvents(roomId, timelineList)
        
        // Persist prefetched events to database
        val persistenceJob = appContext?.let { context ->
            if (syncIngestor == null) {
                syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context)
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    syncIngestor?.persistPaginatedEvents(roomId, timelineList)
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error persisting prefetched events: ${e.message}", e)
                }
            }
        }
        
        if (persistenceJob != null) {
            persistenceJob.invokeOnCompletion {
                signalRoomSnapshotReady(roomId)
            }
        } else {
            signalRoomSnapshotReady(roomId)
        }
        
        val newCacheCount = RoomTimelineCache.getCachedEventCount(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: ✅ Background prefetch completed - cache now has $newCacheCount events for room $roomId")
        smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        return 0 // No reactions processed
    }
    
    /**
     * Handle pagination merge
     */
    private fun handlePaginationMerge(
        roomId: String,
        timelineList: List<TimelineEvent>,
        requestId: Int
    ) {
        android.util.Log.d("Andromuks", "AppViewModel: ========================================")
        android.util.Log.d("Andromuks", "AppViewModel: PAGINATION RESPONSE RECEIVED (requestId: $requestId)")
        android.util.Log.d("Andromuks", "AppViewModel: Received ${timelineList.size} events from backend")
        android.util.Log.d("Andromuks", "AppViewModel: Timeline events BEFORE merge: ${timelineEvents.size}")
        android.util.Log.d("Andromuks", "AppViewModel: Cache BEFORE merge: ${RoomTimelineCache.getCachedEventCount(roomId)} events")
        
        RoomTimelineCache.mergePaginatedEvents(roomId, timelineList)
        mergePaginationEvents(timelineList)
        
        // Persist paginated events to database
        appContext?.let { context ->
            if (syncIngestor == null) {
                syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context)
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    syncIngestor?.persistPaginatedEvents(roomId, timelineList)
                    android.util.Log.d("Andromuks", "AppViewModel: Persisted ${timelineList.size} paginated events to database for room $roomId")
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error persisting paginated events: ${e.message}", e)
                }
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Timeline events AFTER merge: ${timelineEvents.size}")
        android.util.Log.d("Andromuks", "AppViewModel: Cache AFTER merge: ${RoomTimelineCache.getCachedEventCount(roomId)} events")
        
        val newSmallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: smallestRowId BEFORE: $smallestRowId")
        android.util.Log.d("Andromuks", "AppViewModel: smallestRowId AFTER: $newSmallestRowId")
        smallestRowId = newSmallestRowId
        
        android.util.Log.d("Andromuks", "AppViewModel: ========================================")
        if (roomsPendingDbRehydrate.contains(roomId)) {
            scheduleRoomRehydrateFromDb(roomId)
        }
        
    }
    
    /**
     * Handle initial timeline build
     */
    private fun handleInitialTimelineBuild(
        roomId: String,
        timelineList: List<TimelineEvent>
    ) {
        buildTimelineFromChain()
        isTimelineLoading = false
        android.util.Log.d("Andromuks", "AppViewModel: timelineEvents set, isTimelineLoading set to false")
        android.util.Log.d("Andromuks", "AppViewModel: Seeding cache with ${timelineList.size} paginated events for room $roomId")
        RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, timelineList)
        if (roomsPendingDbRehydrate.contains(roomId)) {
            scheduleRoomRehydrateFromDb(roomId)
        }
        
        // Persist initial paginated events to database
        appContext?.let { context ->
            if (syncIngestor == null) {
                syncIngestor = net.vrkknn.andromuks.database.SyncIngestor(context)
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    syncIngestor?.persistPaginatedEvents(roomId, timelineList)
                    android.util.Log.d("Andromuks", "AppViewModel: Persisted ${timelineList.size} initial paginated events to database for room $roomId")
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error persisting initial paginated events: ${e.message}", e)
                }
            }
        }
        
        smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
    }
    
    /**
     * Get cache statistics for display in settings
     * Returns a map with cache size information for various caches
     */
    fun getCacheStatistics(context: android.content.Context): Map<String, String> {
        val stats = mutableMapOf<String, String>()
        
        // 1. Current app RAM usage
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        stats["app_ram_usage"] = formatBytes(usedMemory)
        stats["app_ram_max"] = formatBytes(maxMemory)
        
        // 2. Room timeline memory cache size
        val timelineCacheStats = RoomTimelineCache.getCacheStats()
        val totalTimelineEvents = timelineCacheStats["total_events_cached"] as? Int ?: 0
        // Estimate memory: each TimelineEvent with all fields is roughly 1-2KB
        val estimatedTimelineMemory = totalTimelineEvents * 1.5 * 1024 // 1.5KB per event estimate
        stats["timeline_memory_cache"] = formatBytes(estimatedTimelineMemory.toLong())
        stats["timeline_event_count"] = "$totalTimelineEvents events"
        
        // 3. User profiles memory cache size
        val flattenedCount = flattenedMemberCache.size
        val roomMemberCount = roomMemberCache.values.sumOf { it.size }
        val globalCount = globalProfileCache.size
        // Estimate: MemberProfile with strings is roughly 200-500 bytes
        val estimatedProfileMemory = (flattenedCount + roomMemberCount + globalCount) * 350L // 350 bytes per profile estimate
        stats["user_profiles_memory_cache"] = formatBytes(estimatedProfileMemory)
        stats["user_profiles_count"] = "${flattenedCount + roomMemberCount + globalCount} profiles"
        
        // 4. User profile disk cache size (SQLite database)
        val profileDiskSize = try {
            // SQLite database file: /data/data/<package>/databases/andromuks_profiles.db
            val dbFile = context.getDatabasePath("andromuks_profiles.db")
            if (dbFile.exists()) {
                // Also include journal file if it exists
                val journalFile = File(dbFile.parent, "${dbFile.name}-journal")
                val walFile = File(dbFile.parent, "${dbFile.name}-wal")
                val shmFile = File(dbFile.parent, "${dbFile.name}-shm")
                
                dbFile.length() +
                    (if (journalFile.exists()) journalFile.length() else 0L) +
                    (if (walFile.exists()) walFile.length() else 0L) +
                    (if (shmFile.exists()) shmFile.length() else 0L)
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
        stats["user_profiles_disk_cache"] = formatBytes(profileDiskSize)
        
        // 5. Media memory cache size (from Coil)
        // NOTE: Coil's MemoryCache doesn't expose actual current usage, only max size configuration
        // We show the maximum configured size (25% of max memory)
        val mediaMemoryCacheSize = try {
            val imageLoader = net.vrkknn.andromuks.utils.ImageLoaderSingleton.get(context)
            val memoryCache = imageLoader.memoryCache
            // MemoryCache uses 25% of available memory (from ImageLoaderSingleton.MEMORY_CACHE_PERCENT)
            val runtime = Runtime.getRuntime()
            (runtime.maxMemory() * 0.25).toLong()
        } catch (e: Exception) {
            0L
        }
        stats["media_memory_cache"] = formatBytes(mediaMemoryCacheSize)
        stats["media_memory_cache_max"] = "Max: ${formatBytes(mediaMemoryCacheSize)}" // Store max for display
        
        // 6. Media disk cache size (from Coil)
        val mediaDiskCacheSize = try {
            val cacheDir = java.io.File(context.cacheDir, "image_cache")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
        stats["media_disk_cache"] = formatBytes(mediaDiskCacheSize)
        
        return stats
    }
    
    /**
     * Format bytes to human-readable string (e.g., "1.5 MB")
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
    
    /**
     * Get room statistics: room_id, display name, and disk size
     */
    suspend fun getRoomDiskStatistics(context: android.content.Context): List<RoomDiskStat> = withContext(Dispatchers.IO) {
        try {
            val database = net.vrkknn.andromuks.database.AndromuksDatabase.getInstance(context)
            val roomSummaryDao = database.roomSummaryDao()
            val eventDao = database.eventDao()
            val roomStateDao = database.roomStateDao()
            
            // Get all room summaries
            val roomSummaries = roomSummaryDao.getAllRooms()
            val stats = mutableListOf<RoomDiskStat>()
            
            for (summary in roomSummaries) {
                // Get room display name from room state or room map
                val roomState = roomStateDao.get(summary.roomId)
                val roomItem = roomMap[summary.roomId]
                val displayName = roomState?.name ?: roomItem?.name ?: summary.roomId
                
                // Get event count and size
                val eventCount = eventDao.getEventCountForRoom(summary.roomId)
                val totalSize = eventDao.getTotalSizeForRoom(summary.roomId) ?: 0L
                
                stats.add(RoomDiskStat(
                    roomId = summary.roomId,
                    displayName = displayName,
                    diskSizeBytes = totalSize,
                    isFavourite = roomState?.isFavourite ?: false,
                    isLowPriority = roomState?.isLowPriority ?: false
                ))
            }
            
            // Sort by disk size (descending) and return
            stats.sortedByDescending { it.diskSizeBytes }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error getting room disk statistics: ${e.message}", e)
            emptyList()
        }
    }
    
    data class RoomDiskStat(
        val roomId: String,
        val displayName: String,
        val diskSizeBytes: Long,
        val isFavourite: Boolean = false,
        val isLowPriority: Boolean = false
    )
}