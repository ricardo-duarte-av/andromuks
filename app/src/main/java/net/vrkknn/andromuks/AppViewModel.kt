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
import org.json.JSONObject
import okhttp3.WebSocket
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.content.Context
import android.media.MediaPlayer
import android.media.AudioManager
import android.os.Build
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

data class MemberProfile(
    val displayName: String?,
    val avatarUrl: String?
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
    
    // Recent emojis for reactions
    var recentEmojis by mutableStateOf(listOf<String>())
        private set
    
    // Cache for DM room IDs from m.direct account data
    private var directMessageRoomIds by mutableStateOf(setOf<String>())
        private set
    
    // Bridge-related properties
    var allBridges by mutableStateOf(listOf<BridgeItem>())
        private set
    var currentBridgeId by mutableStateOf<String?>(null)
        private set
    private var bridgeInfoCache by mutableStateOf(mapOf<String, BridgeInfo>())
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
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated bridge info for room $roomId: ${bridgeInfo.protocol.displayname}")
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
        android.util.Log.d("Andromuks", "AppViewModel: Current bridge state requests: ${bridgeStateRequests.keys}")
        
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
    private val ROOM_REORDER_DEBOUNCE_MS = 1000L // 1 second debounce
    
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
            android.util.Log.d("Andromuks", "AppViewModel: spaceList set to ${spaceList.size} spaces, roomListUpdateCounter: $roomListUpdateCounter")
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: spaceList updated (counter update skipped for batching)")
        }
    }
    
    fun updateAllSpaces(spaces: List<SpaceItem>) {
        allSpaces = spaces
        roomListUpdateCounter++
        updateCounter++ // Keep for backward compatibility temporarily
        android.util.Log.d("Andromuks", "AppViewModel: allSpaces set to ${spaces.size} spaces")
    }
    
    fun changeSelectedSection(section: RoomSectionType) {
        selectedSection = section
        // Reset space navigation when switching tabs
        if (section != RoomSectionType.SPACES) {
            currentSpaceId = null
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
        clearWebSocket()
        
        // 2. Clear all room data
        roomMap.clear()
        allRooms = emptyList()
        invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
        allSpaces = emptyList()
        spaceList = emptyList()
        spacesLoaded = false
        
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
        val currentTime = System.currentTimeMillis()
        receiptMovements.entries.removeAll { (_, movement) ->
            currentTime - movement.third > 2000
        }
        return receiptMovements.toMap()
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
     */
    fun notifyMessageAnimationFinished(eventId: String) {
        if (runningBubbleAnimations.remove(eventId)) {
            bubbleAnimationCompletionCounter++
        }
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
        val components = FCMNotificationManager.initializeComponents(
            context = context,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            realMatrixHomeserverUrl = realMatrixHomeserverUrl
        )
        fcmNotificationManager = components.fcmNotificationManager
        conversationsApi = components.conversationsApi
        webClientPushIntegration = components.webClientPushIntegration
        
        // Initialize network monitor for immediate reconnection on network changes
        initializeNetworkMonitor(context)
    }
    
    /**
     * Initialize network monitoring for immediate WebSocket reconnection on network changes
     */
    private fun initializeNetworkMonitor(context: Context) {
        // Delegate network monitoring to service
        WebSocketService.startNetworkMonitoring(context)
        android.util.Log.d("Andromuks", "AppViewModel: Network monitoring delegated to service")
    }
    
    /**
     * Registers FCM notifications with the Gomuks backend.
     * 
     * This function delegates to FCMNotificationManager.registerNotifications() to initiate
     * the FCM token registration process. When the token is ready, it triggers the
     * WebSocket-based registration with the Gomuks backend.
     */
    fun registerFCMNotifications() {
        fcmNotificationManager?.let { manager ->
            FCMNotificationManager.registerNotifications(
                fcmNotificationManager = manager,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                currentUserId = currentUserId,
                onTokenReady = {
                    android.util.Log.d("Andromuks", "AppViewModel: FCM token ready, registering with Gomuks Backend")
                    registerFCMWithGomuksBackend()
                }
            )
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
        return webClientPushIntegration?.shouldRegisterPush() ?: false
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
        // Check if registration is needed (time-based check)
        if (!shouldRegisterPush()) {
            android.util.Log.d("Andromuks", "AppViewModel: FCM registration not needed (too recent)")
            return
        }
        
        val token = getFCMTokenForGomuksBackend()
        val deviceId = webClientPushIntegration?.getDeviceID()
        val encryptionKey = webClientPushIntegration?.getPushEncryptionKey()
        
        android.util.Log.d("Andromuks", "AppViewModel: registerFCMWithGomuksBackend - token=${token?.take(20)}..., deviceId=$deviceId, encryptionKey=${encryptionKey?.take(20)}...")
        
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
            
            sendWebSocketCommand("register_push", registrationRequestId, registrationData)
            
            android.util.Log.d("Andromuks", "AppViewModel: Sent FCM registration to Gomuks Backend with device_id=$deviceId")
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Missing required data for FCM registration - token=${token != null}, deviceId=${deviceId != null}, encryptionKey=${encryptionKey != null}")
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
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (boolean false)")
                }
            }
            is String -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (string): $data")
                // Assume string response means success
                webClientPushIntegration?.markPushRegistrationCompleted()
            }
            is org.json.JSONObject -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (JSON): ${data.toString()}")
                // Check if there's a success field or assume JSON response means success
                val success = data.optBoolean("success", true)
                if (success) {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration successful (JSON)")
                    webClientPushIntegration?.markPushRegistrationCompleted()
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (JSON)")
                }
            }
            else -> {
                android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (unknown type): $data")
                // Assume any response means success
                webClientPushIntegration?.markPushRegistrationCompleted()
            }
        }
    }
    
    fun updateTypingUsers(roomId: String, userIds: List<String>) {
        // Only update if this is the current room
        if (currentRoomId == roomId) {
            typingUsers = userIds
        }
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
        
        val oldReactions = messageReactions[reactionEvent.relatesToEventId]?.size ?: 0
        messageReactions = net.vrkknn.andromuks.utils.processReactionEvent(reactionEvent, currentRoomId, messageReactions)
        val newReactions = messageReactions[reactionEvent.relatesToEventId]?.size ?: 0
        android.util.Log.d("Andromuks", "AppViewModel: processReactionEvent - eventId: ${reactionEvent.eventId}, logicalKey: $reactionKey, oldCount: $oldReactions, newCount: $newReactions, reactionUpdateCounter: $reactionUpdateCounter")
        reactionUpdateCounter++ // Trigger UI recomposition for reactions only
        updateCounter++ // Keep for backward compatibility temporarily
    }

    fun handleClientState(userId: String?, device: String?, homeserver: String?) {
        if (!userId.isNullOrBlank()) {
            currentUserId = userId
            android.util.Log.d("Andromuks", "AppViewModel: Set currentUserId: $userId")
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

    // MEMORY MANAGEMENT: Flattened member cache for better memory usage and performance
    // Using roomId:userId as key instead of nested maps to reduce memory fragmentation
    private val flattenedMemberCache = ConcurrentHashMap<String, MemberProfile>() // Key: "roomId:userId"
    
    // OPTIMIZED: Indexed cache for fast room lookups (avoids string prefix checks)
    private val roomMemberIndex = ConcurrentHashMap<String, MutableSet<String>>() // Key: roomId, Value: Set of userIds
    
    // Global user profile cache with weak references to allow garbage collection
    private val globalProfileCache = ConcurrentHashMap<String, WeakReference<MemberProfile>>()
    
    // Legacy room member cache (deprecated, kept for compatibility)
    private val roomMemberCache = mutableMapOf<String, MutableMap<String, MemberProfile>>()
    
    // OPTIMIZED EDIT/REDACTION SYSTEM - O(1) lookups for all operations
    // Maps original event ID to its complete version history
    private val messageVersions = mutableMapOf<String, VersionedMessage>()
    
    // Maps edit event ID back to original event ID for quick lookup
    private val editToOriginal = mutableMapOf<String, String>()
    
    // Maps redacted event ID to the redaction event for O(1) deletion message creation
    private val redactionCache = mutableMapOf<String, TimelineEvent>()

    fun getMemberProfile(roomId: String, userId: String): MemberProfile? {
        // MEMORY MANAGEMENT: Try flattened cache first for better performance
        val flattenedKey = "$roomId:$userId"
        val flattenedProfile = flattenedMemberCache[flattenedKey]
        if (flattenedProfile != null) {
            return flattenedProfile
        }
        
        // Fallback to legacy cache (for compatibility during transition)
        return roomMemberCache[roomId]?.get(userId)
    }

    fun getMemberMap(roomId: String): Map<String, MemberProfile> {
        // OPTIMIZED: Use indexed cache for O(1) lookups instead of scanning all entries
        val memberMap = mutableMapOf<String, MemberProfile>()
        
        // Try indexed lookup first
        val userIds = roomMemberIndex[roomId]
        if (userIds != null && userIds.isNotEmpty()) {
            for (userId in userIds) {
                val flattenedKey = "$roomId:$userId"
                val profile = flattenedMemberCache[flattenedKey]
                if (profile != null) {
                    memberMap[userId] = profile
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
                    val globalProfileRef = globalProfileCache[sender]
                    val globalProfile = globalProfileRef?.get()
                    if (globalProfile != null) {
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
     */
    private fun storeMemberProfile(roomId: String, userId: String, profile: MemberProfile) {
        // Store in flattened cache for better memory efficiency
        val flattenedKey = "$roomId:$userId"
        flattenedMemberCache[flattenedKey] = profile
        
        // OPTIMIZED: Update indexed cache for fast lookups
        roomMemberIndex.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
        
        // Also maintain legacy cache for compatibility
        val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
        memberMap[userId] = profile
        
        // Store in global cache with weak reference for memory management
        globalProfileCache[userId] = WeakReference(profile)
        
        // MEMORY MANAGEMENT: Cleanup if cache gets too large
        if (flattenedMemberCache.size > MAX_MEMBER_CACHE_SIZE) {
            performMemberCacheCleanup()
        }
    }
    
    /**
     * MEMORY MANAGEMENT: Cleanup old member cache entries to prevent memory pressure
     */
    private fun performMemberCacheCleanup() {
        val currentTime = System.currentTimeMillis()
        val cutoffTime = currentTime - (24 * 60 * 60 * 1000) // 24 hours ago
        
        // Clean up stale global profile cache entries
        globalProfileCache.entries.removeAll { (_, weakRef) ->
            weakRef.get() == null // Remove cleared weak references
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
     * Gets the redaction event for a deleted message (O(1) lookup)
     * @param eventId The event ID that was redacted
     * @return The redaction event, or null if not redacted
     */
    fun getRedactionEvent(eventId: String): TimelineEvent? {
        return redactionCache[eventId]
    }
    
    /**
     * Checks if a message has been edited (O(1) lookup)
     */
    fun isMessageEdited(eventId: String): Boolean {
        val versioned = getMessageVersions(eventId)
        return versioned != null && versioned.versions.size > 1
    }
    
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
        // OPTIMIZED: Check if it's the current user (single string comparison)
        if (userId == currentUserId && currentUserProfile != null) {
            return MemberProfile(
                displayName = currentUserProfile!!.displayName,
                avatarUrl = currentUserProfile!!.avatarUrl
            )
        }
        
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
        
        // OPTIMIZED: Check global profile cache (fallback for when no roomId or room-specific not found)
        val globalProfileRef = globalProfileCache[userId]
        val globalProfile = globalProfileRef?.get()
        if (globalProfile != null) {
            return globalProfile
        } else if (globalProfileRef != null) {
            // Weak reference was cleared, remove the stale entry
            globalProfileCache.remove(userId)
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
                        
                        android.util.Log.d("Andromuks", "AppViewModel: Updated original event ${event.eventId} with ${updatedVersions.size} total versions")
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
            val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
            
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
                                
                                memberMap[userId] = profile
                                // PERFORMANCE: Also add to global cache for O(1) lookups
                                globalProfileCache[userId] = WeakReference(profile)
                                
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
                                globalProfileCache[userId] = WeakReference(profile)
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached invited member '$userId' profile in global cache only -> displayName: '$displayName'")
                            }
                            "leave", "ban" -> {
                                // Remove members who left or were banned from room cache only
                                memberMap.remove(userId)
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
    
    private fun processAccountData(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val accountData = data.optJSONObject("account_data") ?: return
        
        // Process recent emoji account data
        val recentEmojiData = accountData.optJSONObject("io.element.recent_emoji")
        if (recentEmojiData != null) {
            val content = recentEmojiData.optJSONObject("content")
            val recentEmojiArray = content?.optJSONArray("recent_emoji")
            
            if (recentEmojiArray != null) {
                val emojis = mutableListOf<String>()
                for (i in 0 until recentEmojiArray.length()) {
                    val emojiEntry = recentEmojiArray.optJSONArray(i)
                    if (emojiEntry != null && emojiEntry.length() >= 1) {
                        val emoji = emojiEntry.optString(0)
                        if (emoji.isNotBlank()) {
                            emojis.add(emoji)
                        }
                    }
                }
                recentEmojis = emojis
                android.util.Log.d("Andromuks", "AppViewModel: Loaded ${emojis.size} recent emojis from account_data")
            }
        }
        
        // Process m.direct account data for DM room detection
        val mDirectData = accountData.optJSONObject("m.direct")
        if (mDirectData != null) {
            val content = mDirectData.optJSONObject("content")
            if (content != null) {
                val dmRoomIds = mutableSetOf<String>()
                
                // Extract all room IDs from m.direct content
                val keys = content.names()
                if (keys != null) {
                    for (i in 0 until keys.length()) {
                        val userId = keys.optString(i)
                        val roomIdsArray = content.optJSONArray(userId)
                        if (roomIdsArray != null) {
                            for (j in 0 until roomIdsArray.length()) {
                                val roomId = roomIdsArray.optString(j)
                                if (roomId.isNotBlank()) {
                                    dmRoomIds.add(roomId)
                                }
                            }
                        }
                    }
                }
                
                // Update the DM room IDs cache
                directMessageRoomIds = dmRoomIds
                android.util.Log.d("Andromuks", "AppViewModel: Loaded ${dmRoomIds.size} DM room IDs from m.direct account data: ${dmRoomIds.take(5)}")
            }
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
     * Updates badges and timestamps immediately, but only reorders the list every 1 second
     * This prevents the frustrating "room jumping" effect when new messages arrive
     */
    private fun scheduleRoomReorder() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReorder = currentTime - lastRoomReorderTime
        
        // Cancel existing reorder job
        roomReorderJob?.cancel()
        
        if (timeSinceLastReorder >= ROOM_REORDER_DEBOUNCE_MS) {
            // Enough time has passed, reorder immediately
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
     * Actually perform the room reordering
     */
    private fun performRoomReorder() {
        lastRoomReorderTime = System.currentTimeMillis()
        
        val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
        
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
            android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room list update triggered")
        }
        
        if (needsTimelineUpdate) {
            timelineUpdateCounter++
            needsTimelineUpdate = false
            android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Timeline update triggered")
        }
        
        if (needsMemberUpdate) {
            memberUpdateCounter++
            needsMemberUpdate = false
            android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Member update triggered")
        }
        
        if (needsReactionUpdate) {
            reactionUpdateCounter++
            needsReactionUpdate = false
            android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Reaction update triggered")
        }
        
        // Keep for backward compatibility temporarily
        updateCounter++
        
        android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Batched UI update completed")
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
                android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Prefetching room data for: $roomId")
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
            android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Prefetched room state for: $roomId")
        }
        
        // Check if we have enough timeline cache, if not, do a lightweight prefetch
        val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
        if (cachedEventCount < 20) {
            // Lightweight timeline prefetch (smaller limit)
            val prefetchRequestId = requestIdCounter++
            backgroundPrefetchRequests[prefetchRequestId] = roomId
            sendWebSocketCommand("paginate", prefetchRequestId, mapOf(
                "room_id" to roomId,
                "max_timeline_id" to 0,
                "limit" to 25, // Smaller limit for prefetching
                "reset" to false
            ))
            android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Prefetched timeline for: $roomId")
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
            val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
            if (cachedEventCount < 50) {
                val prefetchRequestId = requestIdCounter++
                backgroundPrefetchRequests[prefetchRequestId] = roomId
                sendWebSocketCommand("paginate", prefetchRequestId, mapOf(
                    "room_id" to roomId,
                    "max_timeline_id" to 0,
                    "limit" to 50,
                    "reset" to false
                ))
                android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Loading timeline details for: $roomId")
            }
            
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
        
        // Process account_data for recent emojis
        processAccountData(syncJson)
        
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
        // Populate member cache from sync data and check for changes
        val oldMemberStateHash = generateMemberStateHash()
        populateMemberCacheFromSync(syncJson)
        val newMemberStateHash = generateMemberStateHash()
        val memberStateChanged = newMemberStateHash != oldMemberStateHash
        
        // Process account_data for recent emojis
        processAccountData(syncJson)
        
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
                val updatedRoom = if (room.messagePreview.isNullOrBlank() && !existingRoom.messagePreview.isNullOrBlank()) {
                    room.copy(
                        messagePreview = existingRoom.messagePreview,
                        messageSender = existingRoom.messageSender
                    )
                } else {
                    room
                }
                roomMap[room.id] = updatedRoom
                // Update animation state only if app is visible (battery optimization)
                if (isAppVisible) {
                    updateRoomAnimationState(room.id, isAnimating = true)
                }
                android.util.Log.d("Andromuks", "AppViewModel: Updated room: ${updatedRoom.name} (unread: ${updatedRoom.unreadCount}, message: ${updatedRoom.messagePreview?.take(20)}...)")
            } else {
                roomMap[room.id] = room
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
            // Update animation state only if app is visible (battery optimization)
            if (isAppVisible) {
                updateRoomAnimationState(room.id, isAnimating = true)
            }
            android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name}")
            
            // Check if this is a room we just joined and need to navigate to
            pendingJoinedRoomNavigation?.let { (pendingRoomId, navController) ->
                if (room.id == pendingRoomId) {
                    android.util.Log.d("Andromuks", "AppViewModel: Joined room appeared in sync, navigating to $pendingRoomId")
                    // Navigate to the newly joined room
                    val encodedRoomId = java.net.URLEncoder.encode(room.id, "UTF-8")
                    navController.navigate("room_timeline/$encodedRoomId")
                    // Request timeline for the room
                    requestRoomTimeline(room.id)
                    // Clear the pending navigation
                    pendingJoinedRoomNavigation = null
                }
            }
        }
        
        // Remove left rooms
        syncResult.removedRoomIds.forEach { roomId ->
            val removedRoom = roomMap.remove(roomId)
            if (removedRoom != null) {
                // Remove animation state only if app is visible (battery optimization)
                if (isAppVisible) {
                    roomAnimationStates = roomAnimationStates - roomId
                }
                android.util.Log.d("Andromuks", "AppViewModel: Removed room: ${removedRoom.name}")
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${roomMap.size} (updated: ${syncResult.updatedRooms.size}, new: ${syncResult.newRooms.size}, removed: ${syncResult.removedRoomIds.size}) - sync message #$syncMessageCount [App visible: $isAppVisible]")
        
        // Process room invitations first
        processRoomInvites(syncJson)
        
        // Always cache timeline events (lightweight, needed for instant room opening)
        cacheTimelineEventsFromSync(syncJson)
        
        // SYNC OPTIMIZATION: Always update data first, then check for diff-based UI updates
        val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
        
        // Update low priority rooms set for notification filtering (always needed)
        updateLowPriorityRooms(sortedRooms)
        
        // Diff-based update: Only update UI if room state actually changed
        val newRoomStateHash = generateRoomStateHash(sortedRooms)
        val roomStateChanged = newRoomStateHash != lastRoomStateHash
        
        // BATTERY OPTIMIZATION: Skip expensive UI updates when app is in background
        if (isAppVisible) {
            // Trigger timestamp update on sync (only for visible UI)
            triggerTimestampUpdate()
            
            // SYNC OPTIMIZATION: Selective updates - only update what actually changed
            if (roomStateChanged) {
                android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room state changed, scheduling UI update")
                
                // OPTIMIZED: Use background thread for non-UI operations
                viewModelScope.launch(Dispatchers.Default) {
                    // Update animation states with new positions (lightweight operation)
                    sortedRooms.forEachIndexed { index, room ->
                        updateRoomAnimationState(room.id, isAnimating = false, newPosition = index)
                    }
                }
                
                // PERFORMANCE: Use debounced room reordering to prevent "room jumping"
                // This updates badges/timestamps immediately but only reorders every 1 second
                scheduleRoomReorder()
                
                // Update allRooms for filtering (without reordering)
                allRooms = sortedRooms
                invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                
                // Mark for batched UI update (for badges/timestamps)
                needsRoomListUpdate = true
                scheduleUIUpdate("roomList")
                
                lastRoomStateHash = newRoomStateHash
                android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room list update scheduled")
                
                // OPTIMIZED: Update conversation shortcuts in background (non-UI operation)
                viewModelScope.launch(Dispatchers.Default) {
                    conversationsApi?.updateConversationShortcuts(sortedRooms)
                }
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Room state unchanged, skipping UI update")
            }
            
            // Always update allRooms for data consistency
            allRooms = sortedRooms
            invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
            
            // SYNC OPTIMIZATION: Check if current room needs timeline update with diff-based detection
            checkAndUpdateCurrentRoomTimelineOptimized(syncJson)
            
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
            allRooms = sortedRooms
            invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
            
            // Update shortcuts less frequently in background (every 10 sync messages)
            if (syncMessageCount % 10 == 0) {
                android.util.Log.d("Andromuks", "AppViewModel: Background: Updating conversation shortcuts (throttled)")
                conversationsApi?.updateConversationShortcuts(sortedRooms)
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
        
        // Start the WebSocket foreground service now that we have all connection parameters
        android.util.Log.d("Andromuks", "AppViewModel: Starting WebSocket foreground service after init_complete")
        startWebSocketService()
        
        // Update ConversationsApi with the real homeserver URL and refresh shortcuts
        // This happens after init_complete when we have all the data we need
        if (realMatrixHomeserverUrl.isNotEmpty() && appContext != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Updating ConversationsApi with real homeserver URL after init_complete")
            // Create new ConversationsApi instance with real homeserver URL
            conversationsApi = ConversationsApi(appContext!!, homeserverUrl, authToken, realMatrixHomeserverUrl)
            // Refresh shortcuts with the new homeserver URL and populated rooms
            if (roomMap.isNotEmpty()) {
                conversationsApi?.updateConversationShortcuts(roomMap.values.toList())
            }
        }
        
        // FCM registration with Gomuks Backend will be triggered via callback when token is ready
        // This ensures we don't try to register before the FCM token is available
        
        // Execute any pending notification actions now that websocket is ready
        executePendingNotificationActions()
        
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
        
        android.util.Log.d("Andromuks", "AppViewModel: Lazy loading bridges - requesting room states for ${allRooms.size} rooms")
        
        // Request room states for all rooms (will process responses as they arrive)
        net.vrkknn.andromuks.utils.SpaceRoomParser.requestRoomStatesForBridgeDetection(this)
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
        
        // Notify service of app visibility change
        WebSocketService.setAppVisibility(true)
        
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
        isAppVisible = visible
        
        if (visible) {
            // Cancel any pending shutdown
            appInvisibleJob?.cancel()
            appInvisibleJob = null
            
            // If a room is currently open, trigger timeline refresh to show new events from cache
            if (currentRoomId.isNotEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Room is open ($currentRoomId), triggering timeline refresh for bubble")
                timelineRefreshTrigger++
            }
        }
        // Don't call refreshUIState() - bubbles don't need room list updates or shortcut updates
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
        
        // Notify service of app visibility change
        WebSocketService.setAppVisibility(false)
        
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
        clearWebSocket()
    }
    
    override fun onCleared() {
        super.onCleared()
        android.util.Log.d("Andromuks", "AppViewModel: onCleared - cleaning up resources")
        
        // Cancel any pending jobs
        appInvisibleJob?.cancel()
        
        // Cancel reconnection in service
        WebSocketService.cancelReconnection()
        
        // Stop network monitoring in service
        WebSocketService.stopNetworkMonitoring()
        
        // Clear WebSocket connection
        clearWebSocket()
    }

    fun getRoomById(roomId: String): RoomItem? {
        return roomMap[roomId]
    }
    
    // Room timeline state
    var currentRoomId by mutableStateOf("")
        private set
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
    
    // Made public to allow access for RoomJoiner WebSocket operations
    var requestIdCounter = 1
        private set
    
    fun getAndIncrementRequestId(): Int {
        val id = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request ID: $id (counter now: $requestIdCounter)")
        return id
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
    
    // Track receipt movements for animation - userId -> (previousEventId, currentEventId, timestamp)
    private val receiptMovements = mutableMapOf<String, Triple<String?, String, Long>>()
    var receiptAnimationTrigger by mutableStateOf(0L)
        private set
    
        // Track new message animations - eventId -> timestamp when animation should complete
    private val newMessageAnimations = mutableMapOf<String, Long>()
    private val runningBubbleAnimations = mutableSetOf<String>()
    var bubbleAnimationCompletionCounter by mutableStateOf(0L)
        private set
    var newMessageAnimationTrigger by mutableStateOf(0L)
        private set
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
    
    // Pagination state
    private var smallestRowId: Long = -1L // Smallest rowId from initial paginate
    var isPaginating by mutableStateOf(false)
        private set
    var hasMoreMessages by mutableStateOf(true) // Whether there are more messages to load
    
    
    private var webSocket: WebSocket? = null
    private var lastReceivedRequestId: Int = 0 // Tracks ANY incoming request_id (for pong detection)
    private var lastReceivedSyncId: Int = 0 // Tracks ONLY sync_complete negative request_ids (for reconnection)
    private var lastSyncTimestamp: Long = 0 // Timestamp of last sync_complete received
    private var currentRunId: String = "" // Unique connection ID from gomuks backend
    private var vapidKey: String = "" // VAPID key for push notifications
    private var hasHadInitialConnection = false // Track if we've had an initial connection to only vibrate on reconnections

    // NETWORK OPTIMIZATION: Offline caching and connection state
    private var isOfflineMode = false
    private var lastNetworkState = true // true = online, false = offline
    private val offlineCacheExpiry = 24 * 60 * 60 * 1000L // 24 hours
    
    // WebSocket reconnection log
    data class ReconnectionLogEntry(
        val timestamp: Long,
        val reason: String
    )
    
    private val reconnectionLog = mutableListOf<ReconnectionLogEntry>()
    private val maxLogEntries = 100 // Keep last 100 entries
    
    /**
     * Log a WebSocket reconnection event
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
     * Get the reconnection log for display
     */
    fun getReconnectionLog(): List<ReconnectionLogEntry> {
        return reconnectionLog.toList()
    }

    fun setWebSocket(webSocket: WebSocket) {
        this.webSocket = webSocket
        
        // Set up service callbacks for ping/pong
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
        
        // Delegate WebSocket management to service
        WebSocketService.setWebSocket(webSocket)
        
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

    fun clearWebSocket() {
        this.webSocket = null
        
        // Delegate WebSocket clearing to service
        WebSocketService.clearWebSocket()
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
                lastReceivedSyncId = requestId
                android.util.Log.d("Andromuks", "AppViewModel: Updated lastReceivedSyncId to $requestId (sync_complete)")
                
                // Update service with sync ID for reconnection
                WebSocketService.updateLastReceivedSyncId(requestId)
                
                // Sync full reconnection state with service
                WebSocketService.setReconnectionState(currentRunId, lastReceivedSyncId, vapidKey)
            }
            
            // Delegate pong handling to service
            WebSocketService.handlePong(requestId)
        }
    }
    
    /**
     * Stores the run_id and vapid_key received from the gomuks backend.
     * This is used for reconnection to resume from where we left off.
     */
    fun handleRunId(runId: String, vapidKey: String) {
        currentRunId = runId
        this.vapidKey = vapidKey
        
        // Sync reconnection state with service
        WebSocketService.setReconnectionState(runId, lastReceivedSyncId, vapidKey)
        
        android.util.Log.d("Andromuks", "AppViewModel: Stored run_id: $runId, vapid_key: ${vapidKey.take(20)}...")
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
            editor.putInt("ws_last_received_sync_id", lastReceivedSyncId)
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
     * Returns true if cached data was loaded, false otherwise.
     * 
     * If cached data is older than 10 minutes, it's considered stale and a full refresh
     * is triggered instead of using the cached data.
     */
    fun loadStateFromStorage(context: android.content.Context): Boolean {
        try {
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
            val lastSyncId = prefs.getInt("ws_last_received_sync_id", 0)
            val savedVapidKey = prefs.getString("ws_vapid_key", "") ?: ""
            
            if (runId.isNotEmpty()) {
                currentRunId = runId
                lastReceivedSyncId = lastSyncId
                vapidKey = savedVapidKey
                
                // Sync restored state with service
                WebSocketService.setReconnectionState(runId, lastSyncId, savedVapidKey)
                
                android.util.Log.d("Andromuks", "AppViewModel: Restored WebSocket state - run_id: $runId, last_received_sync_id: $lastSyncId")
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
                
                // Mark spaces as loaded so UI can show cached data
                spacesLoaded = true
                
                return true
            }
            
            return false
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
            editor.remove("ws_last_received_sync_id")
            editor.remove("ws_vapid_key")
            editor.remove("cached_rooms")
            editor.remove("state_saved_timestamp")
            
            editor.apply()
            
            // Also clear in-memory state
            currentRunId = ""
            lastReceivedSyncId = 0
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
        android.util.Log.d("Andromuks", "AppViewModel: Delegating WebSocket restart to service")
        
        // Log the reconnection event
        logReconnection(reason)
        
        // Only show toast for important reconnection reasons, not every attempt
        val shouldShowToast = when {
            reason.contains("Network type changed") -> true
            reason.contains("Network restored") -> true
            reason.contains("Manual reconnection") -> true
            reason.contains("Full refresh") -> true
            reason.contains("attempt #1") -> true // Only show first attempt
            else -> false
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
        
        // Delegate to service
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
            android.util.Log.d("Andromuks", "AppViewModel: Profile already cached for $userId, skipping request")
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
    
   
    fun requestRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting timeline for room: $roomId")
        currentRoomId = roomId
        
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
        val cachedEvents = if (openingFromNotification) {
            // First try the standard threshold, then fall back to notification threshold
            RoomTimelineCache.getCachedEvents(roomId) ?: RoomTimelineCache.getCachedEventsForNotification(roomId)
        } else {
            RoomTimelineCache.getCachedEvents(roomId)
        }
        if (cachedEvents != null) {
            // CACHE HIT - instant room opening without clearing UI
            val ownMessagesInCache = cachedEvents.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
            val cacheType = if (openingFromNotification && cachedEvents.size < 100) "notification-optimized" else "standard"
            android.util.Log.d("Andromuks", "AppViewModel: ✓ CACHE HIT ($cacheType) - Instant room opening: ${cachedEvents.size} events (including $ownMessagesInCache of your own messages)")
            if (ownMessagesInCache > 0) {
                android.util.Log.d("Andromuks", "AppViewModel: ★ Cache contains $ownMessagesInCache messages from YOU")
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
            
            // Reset pagination state
            smallestRowId = -1L
            isPaginating = false
            hasMoreMessages = true
            
            // Ensure member cache exists for this room
            if (roomMemberCache[roomId] == null) {
                roomMemberCache[roomId] = mutableMapOf()
            }
            
            // OPTIMIZED: Populate edit chain mapping from cached events in background if large
            if (cachedEvents.size > 100) {
                // Use background thread for large event processing
                viewModelScope.launch(Dispatchers.Default) {
                    for (event in cachedEvents) {
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
                for (event in cachedEvents) {
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
            if (currentNavigationState?.essentialDataLoaded != true && !pendingRoomStateRequests.contains(roomId)) {
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
            val reactionRequestId = requestIdCounter++
            backgroundPrefetchRequests[reactionRequestId] = roomId
            val effectiveMaxTimelineId = if (smallestCached > 0) smallestCached else 0L
            android.util.Log.d("Andromuks", "AppViewModel: About to send reaction request - currentRoomId: $currentRoomId")
            sendWebSocketCommand("paginate", reactionRequestId, mapOf(
                "room_id" to roomId,
                "max_timeline_id" to effectiveMaxTimelineId,
                "limit" to 100,
                "reset" to false
            ))
            android.util.Log.d("Andromuks", "AppViewModel: ✅ Sent reaction request for cached room: $roomId (requestId: $reactionRequestId, smallestCached: $smallestCached, effectiveMaxTimelineId: $effectiveMaxTimelineId, currentRoomId: $currentRoomId)")
            
            return // Exit early - room is already rendered from cache
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
                
                // Reset pagination state
                smallestRowId = -1L
                isPaginating = false
                hasMoreMessages = true
                
                // Ensure member cache exists for this room
                if (roomMemberCache[roomId] == null) {
                    roomMemberCache[roomId] = mutableMapOf()
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
                
                // BACKGROUND PREFETCH: Request more events to reach 100 total
                val eventsNeeded = 100 - partialCacheCount
                if (eventsNeeded > 0 && smallestCached > 0) {
                    android.util.Log.d("Andromuks", "AppViewModel: 🔄 Background prefetching $eventsNeeded more events to reach 100 total")
                    val prefetchRequestId = requestIdCounter++
                    backgroundPrefetchRequests[prefetchRequestId] = roomId
                    sendWebSocketCommand("paginate", prefetchRequestId, mapOf(
                        "room_id" to roomId,
                        "max_timeline_id" to smallestCached,
                        "limit" to eventsNeeded,
                        "reset" to false
                    ))
                    android.util.Log.d("Andromuks", "AppViewModel: Sent background prefetch request_id=$prefetchRequestId for room=$roomId")
                }
                
                return // Exit early - room is shown with partial cache
            }
        }
        
        // CACHE MISS - show loading and fetch from server
        android.util.Log.d("Andromuks", "AppViewModel: ✗ CACHE MISS (or < 10 events) - showing loading and fetching from server")
        
        // Now clear everything and show loading
        timelineEvents = emptyList()
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
        
        // Ensure member cache exists for this room
        if (roomMemberCache[roomId] == null) {
            roomMemberCache[roomId] = mutableMapOf()
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
        
        // NAVIGATION PERFORMANCE: Only request timeline if not already cached
        val currentCachedCount = RoomTimelineCache.getCachedEventCount(roomId)
        if (currentCachedCount < 20) {
            val paginateRequestId = requestIdCounter++
            timelineRequests[paginateRequestId] = roomId
            sendWebSocketCommand("paginate", paginateRequestId, mapOf(
                "room_id" to roomId,
                "max_timeline_id" to 0,
                "limit" to 100,
                "reset" to false
            ))
            android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Requested timeline data, cachedCount: $currentCachedCount")
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: NAVIGATION OPTIMIZATION - Skipped timeline request, already cached: $currentCachedCount")
        }
    }
    
    /**
     * Silently refreshes the room cache without updating the UI.
     * This populates the cache with fresh data but doesn't trigger any UI updates or scrolling.
     */
    fun silentRefreshRoomCache(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Silent refresh for room: $roomId (populating cache without UI updates)")
        
        // Set current room ID to ensure proper request handling
        currentRoomId = roomId
        
        // Clear cache to get fresh data
        RoomTimelineCache.clearRoomCache(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Cleared timeline cache for room: $roomId")
        
        // Clear animation state to prevent corruption
        newMessageAnimations.clear()
        runningBubbleAnimations.clear()
        bubbleAnimationCompletionCounter = 0L
        newMessageAnimationTrigger = 0L
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
        val paginateRequestId = requestIdCounter++
        backgroundPrefetchRequests[paginateRequestId] = roomId  // Use backgroundPrefetchRequests for silent processing
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 100,
            "reset" to false
        ))
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent silent paginate request for room: $roomId")
    }

    /**
     * Refreshes the room timeline by clearing cache and requesting fresh data from server.
     * This is useful for debugging missing events (e.g., messages from other devices).
     */
    fun refreshRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Refreshing timeline for room: $roomId (clearing cache and requesting fresh data)")
        
        // Set current room ID to ensure reaction processing works correctly
        currentRoomId = roomId
        
        // 1. Drop all cache for this room
        RoomTimelineCache.clearRoomCache(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Cleared timeline cache for room: $roomId")
        
        // 2. Clear current timeline state
        timelineEvents = emptyList()
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
        val paginateRequestId = requestIdCounter++
        timelineRequests[paginateRequestId] = roomId
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 100,
            "reset" to false
        ))
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent fresh paginate request for room: $roomId")
    }
    
    // OPTIMIZATION #4: Cache-first navigation method
    fun navigateToRoomWithCache(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - Cache-first navigation to room: $roomId")
        currentRoomId = roomId
        
        // Check cache first - this is the key optimization
        val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - Cache check: $cachedEventCount events for room $roomId")
        
        // DEBUG: Let's see what's in the cache at this moment
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - DEBUG: Current roomId: $currentRoomId")
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - DEBUG: Timeline events count: ${timelineEvents.size}")
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - DEBUG: Is timeline loading: $isTimelineLoading")
        
        
        // OPTIMIZATION #4: Use the exact same logic as requestRoomTimeline for consistency
        if (cachedEventCount >= 10) {
            // OPTIMIZATION #4: Use cached data immediately (same threshold as requestRoomTimeline)
            android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - ✓ CACHE HIT - Using ${cachedEventCount} cached events for instant navigation")
            
                // Get cached events using the same method as requestRoomTimeline
                val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)
            android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - Notification cache result: ${cachedEvents?.size ?: "null"}")
            
            if (cachedEvents != null) {
                android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - ✅ SUCCESS - Using ${cachedEvents.size} cached events for instant navigation")
                
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
                    roomMemberCache[roomId] = mutableMapOf()
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
                
                android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - ✅ Room opened INSTANTLY with ${timelineEvents.size} cached events")
                
                // Request room state in background if needed
                if (webSocket != null && !pendingRoomStateRequests.contains(roomId)) {
                    val stateRequestId = requestIdCounter++
                    roomStateRequests[stateRequestId] = roomId
                    pendingRoomStateRequests.add(roomId)
                    android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - Requesting room state in background (reqId: $stateRequestId)")
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
        android.util.Log.d("Andromuks", "AppViewModel: OPTIMIZATION #4 - No cache available, falling back to requestRoomTimeline")
        requestRoomTimeline(roomId)
    }
    
    fun requestRoomState(roomId: String) {
        // PERFORMANCE: Prevent duplicate room state requests for the same room
        if (pendingRoomStateRequests.contains(roomId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Room state request already pending for $roomId, skipping duplicate")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Requesting room state for room: $roomId")
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
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with error, NetworkMonitor will handle reconnection")
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
    fun requestUserProfileOnDemand(userId: String, roomId: String) {
        // Check if we already have this profile in cache
        val existingProfile = getUserProfile(userId, roomId)
        if (existingProfile != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Profile already cached for $userId, skipping request")
            return
        }
        
        // Check if we're already requesting this profile to avoid duplicates
        val requestKey = "$roomId:$userId"
        if (pendingProfileRequests.contains(requestKey)) {
            android.util.Log.d("Andromuks", "AppViewModel: Profile request already pending for $userId, skipping duplicate")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Requesting profile on-demand for $userId in room $roomId")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping on-demand profile request")
            return
        }
        
        val requestId = requestIdCounter++
        
        // Track this request to prevent duplicates
        pendingProfileRequests.add(requestKey)
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
            android.util.Log.d("Andromuks", "AppViewModel: Full member list request already pending for $roomId, skipping duplicate")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Requesting full member list for room: $roomId")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping full member list request")
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
        
        android.util.Log.d("Andromuks", "AppViewModel: Sent get_room_state request with ID $requestId for full member list of room $roomId")
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
    
    private fun updateRecentEmojis(emoji: String) {
        val currentEmojis = recentEmojis.toMutableList()
        
        // Remove emoji if it already exists
        currentEmojis.remove(emoji)
        
        // Add emoji to the beginning
        currentEmojis.add(0, emoji)
        
        // Keep only the first 20 emojis
        val updatedEmojis = currentEmojis.take(20)
        
        // Update local state
        recentEmojis = updatedEmojis
        
        // Send to server
        sendAccountDataUpdate(updatedEmojis)
    }
    
    private fun sendAccountDataUpdate(emojis: List<String>) {
        val ws = webSocket ?: return
        val accountDataRequestId = requestIdCounter++
        
        // Create the recent_emoji array format: [["emoji", 1], ...]
        val recentEmojiArray = emojis.map { listOf(it, 1) }
        
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
        
        // If WebSocket is not available, just log it - let NetworkMonitor handle reconnection
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: sendReply failed with result: $result - NetworkMonitor will handle reconnection")
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
        android.util.Log.d("Andromuks", "AppViewModel: handleResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}")
        android.util.Log.d("Andromuks", "AppViewModel: outgoingRequests contains $requestId: ${outgoingRequests.containsKey(requestId)}")
        android.util.Log.d("Andromuks", "AppViewModel: roomSpecificStateRequests contains $requestId: ${roomSpecificStateRequests.containsKey(requestId)}")
        android.util.Log.d("AppViewModel", "AppViewModel: profileRequests contains $requestId: ${profileRequests.containsKey(requestId)}")
        android.util.Log.d("AppViewModel", "AppViewModel: timelineRequests contains $requestId: ${timelineRequests.containsKey(requestId)}")
        android.util.Log.d("Andromuks", "AppViewModel: roomStateRequests contains $requestId: ${roomStateRequests.containsKey(requestId)}")
        android.util.Log.d("Andromuks", "AppViewModel: bridgeStateRequests contains $requestId: ${bridgeStateRequests.containsKey(requestId)}")
        android.util.Log.d("Andromuks", "AppViewModel: Current bridge state requests: ${bridgeStateRequests.keys}")
        android.util.Log.d("Andromuks", "AppViewModel: Current room state requests: ${roomStateRequests.keys}")
        
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
            android.util.Log.d("Andromuks", "AppViewModel: Routing background prefetch response to handleTimelineResponse (requestId: $requestId)")
            handleTimelineResponse(requestId, data)
        } else if (paginateRequests.containsKey(requestId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Routing pagination response to handleTimelineResponse")
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
            android.util.Log.d("Andromuks", "AppViewModel: Routing to handleRoomSpecificStateResponse for requestId: $requestId")
            android.util.Log.d("Andromuks", "AppViewModel: About to call handleRoomSpecificStateResponse with data: ${data::class.java.simpleName}")
            handleRoomSpecificStateResponse(requestId, data)
            android.util.Log.d("Andromuks", "AppViewModel: handleRoomSpecificStateResponse completed for requestId: $requestId")
        } else if (fullMemberListRequests.containsKey(requestId)) {
            handleFullMemberListResponse(requestId, data)
        } else if (outgoingRequests.containsKey(requestId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Routing to handleOutgoingRequestResponse")
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
        
        // PERFORMANCE: Add to global cache first
        globalProfileCache[userId] = WeakReference(memberProfile)
        
        // If we know which room requested the profile, add it to that room's cache
        if (requestingRoomId != null) {
            val memberMap = roomMemberCache.getOrPut(requestingRoomId) { mutableMapOf() }
            memberMap[userId] = memberProfile
            android.util.Log.d("Andromuks", "AppViewModel: Added fallback profile for $userId to room $requestingRoomId")
        }
        
        // Also update member cache for all rooms that already contain this user
        roomMemberCache.forEach { (roomId, memberMap) ->
            if (memberMap.containsKey(userId)) {
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
        
        // PERFORMANCE: Add to global profile cache first (O(1) access)
        globalProfileCache[userId] = WeakReference(memberProfile)
        
        // If we know which room requested the profile, add it to that room's cache
        if (requestingRoomId != null) {
            val memberMap = roomMemberCache.getOrPut(requestingRoomId) { mutableMapOf() }
            memberMap[userId] = memberProfile
            android.util.Log.d("Andromuks", "AppViewModel: Added profile for $userId to room $requestingRoomId (display=$display)")
        }
        
        // Also update member cache for all rooms that already contain this user
        roomMemberCache.forEach { (roomId, memberMap) ->
            if (memberMap.containsKey(userId)) {
                memberMap[userId] = memberProfile
                android.util.Log.d("Andromuks", "AppViewModel: Updated member cache for $userId in room $roomId")
            }
        }
        
        if (userId == currentUserId) {
            currentUserProfile = UserProfile(userId = userId, displayName = display, avatarUrl = avatar)
        }
        android.util.Log.d("Andromuks", "AppViewModel: Profile updated for $userId display=$display avatar=${avatar != null} (added to global cache)")
        
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
     * This function stores user profile data (display name and avatar URL) to disk so that
     * it persists between app sessions. The data is stored as JSON in SharedPreferences
     * with a key format of "profile_[userId]".
     * 
     * @param context Android context for accessing SharedPreferences
     * @param userId The Matrix user ID to save the profile for
     * @param profile The MemberProfile object containing display name and avatar URL
     */
    fun saveProfileToDisk(context: android.content.Context, userId: String, profile: MemberProfile) {
        try {
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val editor = sharedPrefs.edit()
            
            // Create a JSON object for the profile
            val profileJson = JSONObject()
            profileJson.put("displayName", profile.displayName ?: "")
            profileJson.put("avatarUrl", profile.avatarUrl ?: "")
            
            // Store in shared preferences with key format: "profile_[userId]"
            editor.putString("profile_$userId", profileJson.toString())
            editor.apply()
            
            android.util.Log.d("Andromuks", "AppViewModel: Saved profile to disk for $userId")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to save profile to disk for $userId", e)
        }
    }
    
    // Database-based profile management
    private val pendingProfileSaves = mutableMapOf<String, MemberProfile>()
    private var profileSaveJob: kotlinx.coroutines.Job? = null
    private var profileRepository: net.vrkknn.andromuks.database.ProfileRepository? = null
    
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
                            eventId = event.eventId,
                            sender = event.sender,
                            emoji = emoji,
                            relatesToEventId = relatesToEventId,
                            timestamp = event.timestamp
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
            val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
            
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
                        android.util.Log.d("Andromuks", "AppViewModel: [PAGINATE] ★ Found OUR message in paginate response: ${event.eventId} body='$bodyPreview' timelineRowid=${event.timelineRowid}")
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
                    appContext?.let { context ->
                        android.widget.Toast.makeText(context, "No more messages available", android.widget.Toast.LENGTH_SHORT).show()
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
                
                // Process read receipts from timeline response
                val receipts = data.optJSONObject("receipts")
                if (receipts != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: Processing read receipts from timeline response for room: $roomId")
                    synchronized(readReceiptsLock) {
                        ReceiptFunctions.processReadReceipts(
                            receipts, 
                            readReceipts, 
                            { readReceiptsUpdateCounter++ },
                            { userId, previousEventId, newEventId ->
                                // Track receipt movement for animation
                                receiptMovements[userId] = Triple(previousEventId, newEventId, System.currentTimeMillis())
                                receiptAnimationTrigger = System.currentTimeMillis()
                                android.util.Log.d("Andromuks", "AppViewModel: Receipt movement detected: $userId from $previousEventId to $newEventId")
                            }
                        )
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
        
        val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
        
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
                            
                            // MEMORY MANAGEMENT: Add to flattened cache for efficient getMemberMap lookups
                            val flattenedKey = "$roomId:$stateKey"
                            flattenedMemberCache[flattenedKey] = newProfile
                            
                            // OPTIMIZED: Update indexed cache for fast lookups
                            roomMemberIndex.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(stateKey)
                            
                            // PERFORMANCE: Also add to global cache for O(1) lookups
                            manageGlobalCacheSize()
                            manageRoomMemberCacheSize(roomId)
                            manageFlattenedMemberCacheSize()
                            globalProfileCache[stateKey] = WeakReference(newProfile)
                            
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
        
        val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
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
                            // PERFORMANCE: Also add to global cache for O(1) lookups
                            manageGlobalCacheSize()
                            manageRoomMemberCacheSize(roomId)
                            manageFlattenedMemberCacheSize()
                            globalProfileCache[stateKey] = WeakReference(newProfile)
                            
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
                        if (wasRemoved) {
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
        val currentRoomData = mutableListOf<Pair<String, JSONArray>>()
        val otherRoomsData = mutableListOf<Pair<String, JSONArray>>()
        
        // Separate current room from other rooms
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomData = rooms.optJSONObject(roomId) ?: continue
            val events = roomData.optJSONArray("events") ?: continue
            
            if (roomId == currentRoomId) {
                currentRoomData.add(Pair(roomId, events))
            } else {
                otherRoomsData.add(Pair(roomId, events))
            }
        }
        
        // Process current room immediately (synchronously) for instant updates
        for ((roomId, events) in currentRoomData) {
            android.util.Log.d("Andromuks", "AppViewModel: Caching ${events.length()} events for current room: $roomId (PRIORITY)")
            val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
            RoomTimelineCache.addEventsFromSync(roomId, events, memberMap)
        }
        
        // Process other rooms in background thread (non-blocking)
        if (otherRoomsData.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.Default) {
                for ((roomId, events) in otherRoomsData) {
                    val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
                    RoomTimelineCache.addEventsFromSync(roomId, events, memberMap)
                }
                android.util.Log.d("Andromuks", "AppViewModel: Background cached ${otherRoomsData.size} non-current rooms")
            }
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
                                android.util.Log.d("Andromuks", "AppViewModel: Updated room state from sync: $roomState")
                            } else {
                                android.util.Log.d("Andromuks", "AppViewModel: Skipped updating currentRoomState from sync - roomId mismatch ($roomId != $currentRoomId)")
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
                                    // Track receipt movement for animation
                                    receiptMovements[userId] = Triple(previousEventId, newEventId, System.currentTimeMillis())
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
        val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
        
        // Process events in timestamp order for clean edit handling
        val events = mutableListOf<TimelineEvent>()
        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i)
            if (eventJson != null) {
                val event = TimelineEvent.fromJson(eventJson)
                events.add(event)
                // Log each event with its critical fields
                android.util.Log.d("Andromuks", "AppViewModel: [SYNC EVENT $i/${eventsArray.length()}] eventId=${event.eventId} type=${event.type} sender=${event.sender} timelineRowid=${event.timelineRowid}")
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
            android.util.Log.d("Andromuks", "AppViewModel: Processing event ${event.eventId} of type ${event.type} at timestamp ${event.timestamp}")
            
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
                        globalProfileCache[userId] = WeakReference(profile)
                        android.util.Log.d("Andromuks", "AppViewModel: [SYNC] ✓ Updated member cache for $userId: displayName='$displayName' (NOT added to timeline - correct)")
                    }
                }
            } else if (event.type == "m.room.member" && event.timelineRowid >= 0L) {
                // Timeline member event (join/leave that should show in timeline)
                android.util.Log.d("Andromuks", "AppViewModel: [SYNC] Member event with timelineRowid=${event.timelineRowid} - should this be added to timeline?")
                addNewEventToChain(event)
            } else if (event.type == "m.room.redaction") {
                // Handle redaction events (live sync path)
                android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Processing redaction event ${event.eventId} from ${event.sender}")
                
                // Add redaction event to timeline so findLatestRedactionEvent can find it
                addNewEventToChain(event)
                
                // Extract the event ID being redacted
                val redactsEventId = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                
                if (redactsEventId != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Redaction targets event: $redactsEventId")
                    
                    // Find and update the original message in the timeline
                    val currentEvents = timelineEvents.toMutableList()
                    val originalIndex = currentEvents.indexOfFirst { it.eventId == redactsEventId }
                    
                    if (originalIndex >= 0) {
                        val originalEvent = currentEvents[originalIndex]
                        // Create a copy with redactedBy set
                        val redactedEvent = originalEvent.copy(redactedBy = event.eventId)
                        currentEvents[originalIndex] = redactedEvent
                        timelineEvents = currentEvents
                        
                        android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Marked event $redactsEventId as redacted by ${event.eventId}")
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Could not find event $redactsEventId to mark as redacted (might be in paginated history)")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] Redaction event has no 'redacts' field")
                }
                
                // Request sender profile if missing from cache
                if (!memberMap.containsKey(event.sender)) {
                    android.util.Log.d("Andromuks", "AppViewModel: Requesting profile for redaction sender: ${event.sender} in room $roomId")
                    requestUserProfile(event.sender, roomId)
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: Redaction sender ${event.sender} already in cache: ${memberMap[event.sender]?.displayName}")
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
                                android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Reaction event ${event.eventId} has been redacted by ${event.redactedBy}, removing reaction")
                                // Remove this reaction by processing it as if the user toggled it off
                                val reactionEvent = ReactionEvent(
                                    eventId = event.eventId,
                                    sender = event.sender,
                                    emoji = emoji,
                                    relatesToEventId = relatesToEventId,
                                    timestamp = event.timestamp
                                )
                                // Process the reaction - it will be removed since the user is already in the list
                                processReactionEvent(reactionEvent)
                                android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Removed redacted reaction: $emoji from ${event.sender} to $relatesToEventId")
                            } else {
                                // Normal reaction, add it
                                android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Processing reaction: $emoji from ${event.sender} to $relatesToEventId")
                                
                                // Process all reactions normally - no special handling for our own reactions
                                val reactionEvent = ReactionEvent(
                                    eventId = event.eventId,
                                    sender = event.sender,
                                    emoji = emoji,
                                    relatesToEventId = relatesToEventId,
                                    timestamp = event.timestamp
                                )
                                processReactionEvent(reactionEvent)
                                android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Processed reaction: $emoji from ${event.sender} to $relatesToEventId")
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
                    android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] ★ Processing OUR OWN message from another device: ${event.eventId} body='$bodyPreview' timelineRowid=${event.timelineRowid}")
                }
                
                // Check if this is an edit event (m.replace relationship)
                val isEditEvent = when {
                    event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    else -> false
                }
                
                if (isEditEvent) {
                    // Handle edit event using edit chain system
                    android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Event ${event.eventId} is an EDIT, handling via chain")
                    handleEditEventInChain(event)
                } else {
                    // Add new timeline event to chain (works for messages from ANY client)
                    android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] ✓ Adding event ${event.eventId} to timeline chain (sender=${event.sender})")
                    addNewEventToChain(event)
                }
            } else if (event.type == "m.room.pinned_events" || event.type == "m.room.name" || event.type == "m.room.topic" || event.type == "m.room.avatar") {
                // System events that should appear in timeline
                android.util.Log.d("Andromuks", "AppViewModel: [LIVE SYNC] Processing system event: ${event.type} eventId=${event.eventId} sender=${event.sender}")
                addNewEventToChain(event)
            } else {
                // Unknown event type - log it so we can see what's being skipped
                android.util.Log.w("Andromuks", "AppViewModel: [LIVE SYNC] ✗ UNHANDLED event type: ${event.type} eventId=${event.eventId} sender=${event.sender} timelineRowid=${event.timelineRowid}")
                if (event.sender == currentUserId) {
                    android.util.Log.e("Andromuks", "AppViewModel: [LIVE SYNC] ★★★ WARNING: YOUR OWN message was SKIPPED due to unhandled type: ${event.type} ★★★")
                }
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
        
        android.util.Log.d("Andromuks", "AppViewModel: [SYNC SUMMARY] Total=${events.size}: addedToTimeline=$addedToTimeline, memberUpdates=$memberStateUpdates, reactions=$reactions, unhandled=$unhandled")
        
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
            android.util.Log.d("Andromuks", "AppViewModel: Processing relationships for ${newEditEvents.size} new edit events")
            processNewEditRelationships(newEditEvents)
        }
        
        // Build timeline from chain
        val timelineCountBefore = timelineEvents.size
        buildTimelineFromChain()
        val timelineCountAfter = timelineEvents.size
        android.util.Log.d("Andromuks", "AppViewModel: [SYNC] Timeline rebuilt: before=$timelineCountBefore, after=$timelineCountAfter (added ${timelineCountAfter - timelineCountBefore} events)")
        
        // Mark room as read for the newest event since user is actively viewing the room
        val newestEvent = events.maxByOrNull { it.timestamp }
        if (newestEvent != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Auto-marking room as read for newest event: ${newestEvent.eventId}")
            markRoomAsRead(roomId, newestEvent.eventId)
        }
    }
    
    /**
     * Handles edit events using the edit chain tracking system.
     * This ensures clean edit handling by tracking the edit chain properly.
     */
    private fun handleEditEventInChain(editEvent: TimelineEvent) {
        android.util.Log.d("Andromuks", "AppViewModel: handleEditEventInChain called for ${editEvent.eventId}")
        
        // Check if the edit event needs decryption
        val processedEditEvent = if (editEvent.type == "m.room.encrypted" && editEvent.decrypted == null) {
            android.util.Log.d("Andromuks", "AppViewModel: Edit event ${editEvent.eventId} needs decryption")
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
            android.util.Log.d("Andromuks", "AppViewModel: Edit event ${editEvent.eventId} targets ${targetEventId}")
            
            // Find the target event in our chain mapping
            val targetEntry = eventChainMap[targetEventId]
            if (targetEntry != null) {
                android.util.Log.d("Andromuks", "AppViewModel: Found target event ${targetEventId} in chain mapping")
                android.util.Log.d("Andromuks", "AppViewModel: Target event body before edit: ${targetEntry.ourBubble?.decrypted?.optString("body", "null")}")
                
                // Update the target event's replacedBy field with the new edit
                targetEntry.replacedBy = editEvent.eventId
                
                android.util.Log.d("Andromuks", "AppViewModel: Updated target event ${targetEventId} to be replaced by ${editEvent.eventId}")
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Target event ${targetEventId} not found in chain mapping")
                android.util.Log.d("Andromuks", "AppViewModel: Available events in chain: ${eventChainMap.keys}")
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
        android.util.Log.d("Andromuks", "AppViewModel: addNewEventToChain called for ${event.eventId}")
        
        // DEDUPLICATION: Check if event already exists in chain
        if (eventChainMap.containsKey(event.eventId)) {
            android.util.Log.d("Andromuks", "AppViewModel: Event ${event.eventId} already in chain, skipping duplicate")
            return
        }
        
        // Add regular event to chain mapping
        eventChainMap[event.eventId] = EventChainEntry(
            eventId = event.eventId,
            ourBubble = event,
            replacedBy = null,
            originalTimestamp = event.timestamp
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: Added event ${event.eventId} to chain mapping")
    }
    
    /**
     * Processes edit relationships for new edit events only.
     */
    private fun processNewEditRelationships(newEditEvents: List<TimelineEvent>) {
        android.util.Log.d("Andromuks", "AppViewModel: processNewEditRelationships called with ${newEditEvents.size} new edit events")
        
        // Process only the new edit events
        for (editEvent in newEditEvents) {
            val editEventId = editEvent.eventId
            android.util.Log.d("Andromuks", "AppViewModel: Processing new edit event ${editEventId}")
            
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
                            android.util.Log.d("Andromuks", "AppViewModel: Added ${editEventId} to end of chain for ${targetEventId}")
                        }
                    } else {
                        // First edit for this target
                        targetEntry.replacedBy = editEventId
                        android.util.Log.d("Andromuks", "AppViewModel: Set ${targetEventId} to be replaced by ${editEventId}")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: Target event ${targetEventId} not found in chain mapping for edit ${editEventId}")
                }
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Could not find target event ID in edit event ${editEventId}")
            }
        }
    }
    
    /**
     * Processes edit relationships to build the complete edit chain.
     */
    private fun processEditRelationships() {
        android.util.Log.d("Andromuks", "AppViewModel: processEditRelationships called with ${eventChainMap.size} events and ${editEventsMap.size} edit events")
        
        // Safety check: limit the number of edit events to prevent blocking
        if (editEventsMap.size > 100) {
            android.util.Log.w("Andromuks", "AppViewModel: Too many edit events (${editEventsMap.size}), limiting to 100 to prevent blocking")
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
            android.util.Log.d("Andromuks", "AppViewModel: Created chain entry for edit event ${editEventId}")
        }
        
        // Sort edit events by timestamp to process in chronological order
        val sortedEditEvents = editEventsMap.values.sortedBy { it.timestamp }
        android.util.Log.d("Andromuks", "AppViewModel: Processing ${sortedEditEvents.size} edit events in chronological order")
        
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
            android.util.Log.d("Andromuks", "AppViewModel: Processing edit event ${editEventId} at timestamp ${editEvent.timestamp}")
            
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
                            android.util.Log.d("Andromuks", "AppViewModel: Added ${editEventId} to end of chain for ${targetEventId}")
                        }
                    } else {
                        // First edit for this target
                        targetEntry.replacedBy = editEventId
                        android.util.Log.d("Andromuks", "AppViewModel: Set ${targetEventId} to be replaced by ${editEventId}")
                    }
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: Target event ${targetEventId} not found in chain mapping for edit ${editEventId}")
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
        android.util.Log.d("Andromuks", "AppViewModel: buildTimelineFromChain called with ${eventChainMap.size} events")
        android.util.Log.d("Andromuks", "AppViewModel: Edit events map has ${editEventsMap.size} events")
        
        val timelineEvents = mutableListOf<TimelineEvent>()
        val redactionMap = mutableMapOf<String, String>() // Map of target eventId -> redaction eventId
        
        // OPTIMIZED: Process events and collect redactions in single pass
        for ((eventId, entry) in eventChainMap) {
            // Collect redaction targets first
            val redactionEvent = entry.ourBubble
            if (redactionEvent != null && redactionEvent.type == "m.room.redaction") {
                val redactsEventId = redactionEvent.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                if (redactsEventId != null) {
                    redactionMap[redactsEventId] = redactionEvent.eventId
                    android.util.Log.d("Andromuks", "AppViewModel: Found redaction ${redactionEvent.eventId} targeting $redactsEventId")
                }
            }
            
            // Process regular events with bubbles
            if (entry.ourBubble != null && entry.ourBubble?.type != "m.room.redaction") {
                // Apply redaction if this event is targeted
                val redactedBy = redactionMap[eventId]
                val finalEvent = if (redactedBy != null) {
                    val baseEvent = getFinalEventForBubble(entry)
                    baseEvent.copy(redactedBy = redactedBy)
                } else {
                    getFinalEventForBubble(entry)
                }
                
                timelineEvents.add(finalEvent)
                android.util.Log.d("Andromuks", "AppViewModel: Added event for ${eventId} with final content from ${entry.replacedBy ?: eventId}${if (redactedBy != null) " (redacted by $redactedBy)" else ""}")
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
        
        // Track new messages for slide-up animation with 500ms delay
        if (actuallyNewMessages.isNotEmpty()) {
            val currentTime = System.currentTimeMillis()
            val animationEndTime = currentTime + NEW_MESSAGE_ANIMATION_DELAY_MS + NEW_MESSAGE_ANIMATION_DURATION_MS // Bubble anim starts after delay and runs to completion
            
            // Check if any of the new messages are from other users (not our own messages)
            var shouldPlaySound = false
            actuallyNewMessages.forEach { eventId ->
                newMessageAnimations[eventId] = animationEndTime
                runningBubbleAnimations.add(eventId)
                android.util.Log.d("Andromuks", "AppViewModel: Added new message animation for $eventId (ends at ${animationEndTime})")
                
                // Check if this message is from another user (not our own message)
                val newEvent = sortedTimelineEvents.find { it.eventId == eventId }
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
                android.util.Log.d("Andromuks", "AppViewModel: Playing notification sound for new messages from other users in current room: $currentRoomId")
                playNewMessageSound()
            } else if (isInitialRoomLoad && shouldPlaySound) {
                android.util.Log.d("Andromuks", "AppViewModel: Skipping sound during initial room load - would have played for ${actuallyNewMessages.size} messages")
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
        
        android.util.Log.d("Andromuks", "AppViewModel: Built timeline with ${timelineEvents.size} events from chain, ${actuallyNewMessages.size} new messages detected")
    }
    
    private fun mergePaginationEvents(newEvents: List<TimelineEvent>) {
        android.util.Log.d("Andromuks", "AppViewModel: mergePaginationEvents called with ${newEvents.size} new events")
        android.util.Log.d("Andromuks", "AppViewModel: Current timeline has ${timelineEvents.size} events")
        
        // OPTIMIZED: Early exit if no new events
        if (newEvents.isEmpty()) {
            android.util.Log.d("Andromuks", "AppViewModel: No new events to merge")
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
        android.util.Log.d("Andromuks", "AppViewModel: Combined timeline has ${combinedMap.size} unique events before redaction processing (${redactionMap.size} redactions)")
        
        // OPTIMIZED: Process redactions using HashMap lookup (O(1) instead of O(n))
        for ((targetEventId, redactionEvent) in redactionMap) {
            android.util.Log.d("Andromuks", "AppViewModel: Pagination redaction ${redactionEvent.eventId} targets $targetEventId")
            
            val targetEvent = combinedMap[targetEventId]
            if (targetEvent != null) {
                combinedMap[targetEventId] = targetEvent.copy(redactedBy = redactionEvent.eventId)
                android.util.Log.d("Andromuks", "AppViewModel: Marked paginated event $targetEventId as redacted by ${redactionEvent.eventId}")
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Could not find target event $targetEventId for pagination redaction")
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
                    android.util.Log.d("Andromuks", "AppViewModel: Timeline sorted and updated, timelineUpdateCounter incremented to $timelineUpdateCounter")
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
            
            android.util.Log.d("Andromuks", "AppViewModel: Timeline sorted and updated, timelineUpdateCounter incremented to $timelineUpdateCounter")
        }
        
        // Update smallest rowId for next pagination
        val newSmallest = newEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
        android.util.Log.d("Andromuks", "AppViewModel: New smallest rowId from pagination: $newSmallest, current smallestRowId: $smallestRowId")
        if (newSmallest > 0 && newSmallest < smallestRowId) {
            smallestRowId = newSmallest
            android.util.Log.d("Andromuks", "AppViewModel: Updated smallest rowId for pagination: $smallestRowId")
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Merged pagination events, timeline now has ${this.timelineEvents.size} events")
    }
    
    fun loadOlderMessages(roomId: String) {
        val cacheSize = RoomTimelineCache.getCachedEventCount(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: ========================================")
        android.util.Log.d("Andromuks", "AppViewModel: loadOlderMessages CALLED for room: $roomId")
        android.util.Log.d("Andromuks", "AppViewModel: Current cache size: $cacheSize events")
        android.util.Log.d("Andromuks", "AppViewModel: Current state - isPaginating: $isPaginating, smallestRowId: $smallestRowId, hasMoreMessages: $hasMoreMessages, webSocket: ${webSocket != null}")
        
        // Don't load if already loading
        if (isPaginating) {
            android.util.Log.w("Andromuks", "AppViewModel: ❌ BLOCKED - Pagination already in progress")
            return
        }
        
        // Don't load if backend says there's nothing more
        if (!hasMoreMessages) {
            android.util.Log.w("Andromuks", "AppViewModel: ❌ BLOCKED - No more messages (has_more=false)")
            appContext?.let { context ->
                android.widget.Toast.makeText(context, "No more messages to load", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        // Get the actual oldest event from cache
        val oldestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: Oldest cached event timeline_rowid: $oldestRowId")
        
        if (oldestRowId <= 0) {
            android.util.Log.e("Andromuks", "AppViewModel: ❌ BLOCKED - Cannot paginate, no valid timeline_rowid in cache (got $oldestRowId)")
            return
        }
        
        if (webSocket == null) {
            android.util.Log.e("Andromuks", "AppViewModel: ❌ BLOCKED - WebSocket not connected")
            return
        }
        
        isPaginating = true
        val paginateRequestId = requestIdCounter++
        paginateRequests[paginateRequestId] = roomId
        
        android.util.Log.d("Andromuks", "AppViewModel: ✅ SENDING PAGINATION REQUEST")
        android.util.Log.d("Andromuks", "AppViewModel:    requestId: $paginateRequestId")
        android.util.Log.d("Andromuks", "AppViewModel:    room_id: $roomId")
        android.util.Log.d("Andromuks", "AppViewModel:    max_timeline_id: $oldestRowId")
        android.util.Log.d("Andromuks", "AppViewModel:    limit: 100")
        android.util.Log.d("Andromuks", "AppViewModel: ========================================")
        
        // Show "Loading more..." toast
        appContext?.let { context ->
            android.widget.Toast.makeText(context, "Loading more messages...", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to oldestRowId,
            "limit" to 100,
            "reset" to false
        ))
        
        // Set a timeout to reset pagination state if no response comes back
        CoroutineScope(Dispatchers.Main).launch {
            delay(10000) // 10 second timeout
            if (paginateRequests.containsKey(paginateRequestId)) {
                android.util.Log.w("Andromuks", "AppViewModel: Pagination request $paginateRequestId timed out, resetting state")
                paginateRequests.remove(paginateRequestId)
                isPaginating = false
            }
        }
    }
    
    /**
     * Gets the final event for a bubble, following the edit chain to the latest edit.
     */
    private fun getFinalEventForBubble(entry: EventChainEntry): TimelineEvent {
        var currentEvent = entry.ourBubble!!
        var currentEntry = entry
        val visitedEvents = mutableSetOf<String>() // Prevent infinite loops
        
        android.util.Log.d("Andromuks", "AppViewModel: getFinalEventForBubble for ${entry.eventId}")
        android.util.Log.d("Andromuks", "AppViewModel: Initial event body: ${currentEvent.decrypted?.optString("body", "null")}")
        android.util.Log.d("Andromuks", "AppViewModel: replacedBy: ${entry.replacedBy}")
        
        // Follow the edit chain to find the latest edit
        var chainDepth = 0
        while (currentEntry.replacedBy != null) {
            // Safety check: limit chain depth to prevent infinite loops
            if (chainDepth >= 20) {
                android.util.Log.w("Andromuks", "AppViewModel: Chain depth limit reached (20), stopping to prevent infinite loop")
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
            
            val editEvent = editEventsMap[editEventId]
            
            if (editEvent != null) {
                android.util.Log.d("Andromuks", "AppViewModel: Following edit chain from ${currentEntry.eventId} to ${editEventId}")
                android.util.Log.d("Andromuks", "AppViewModel: Edit event body: ${editEvent.decrypted?.optString("body", "null")}")
                android.util.Log.d("Andromuks", "AppViewModel: Merging edit content from ${editEventId}")
                
                // Merge the edit content into the current event
                currentEvent = mergeEditContent(currentEvent, editEvent)
                
                android.util.Log.d("Andromuks", "AppViewModel: After merge body: ${currentEvent.decrypted?.optString("body", "null")}")
                
                // Update current entry to continue following the chain
                // Edit events have their own chain entries, so we can follow them
                val nextEntry = eventChainMap[editEventId]
                if (nextEntry == null) {
                    android.util.Log.d("Andromuks", "AppViewModel: Reached end of edit chain at ${editEventId}")
                    break
                }
                
                // Check if the next entry is the same as the current entry (infinite loop)
                if (nextEntry.eventId == currentEntry.eventId) {
                    android.util.Log.w("Andromuks", "AppViewModel: Edit event ${editEventId} points to itself, breaking chain")
                    break
                }
                
                currentEntry = nextEntry
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Edit event ${editEventId} not found in edit events map")
                break
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Final event body: ${currentEvent.decrypted?.optString("body", "null")}")
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
                        android.util.Log.d("Andromuks", "AppViewModel: Found original event at index $originalEventIndex: ${originalEvent.eventId}")
                        android.util.Log.d("Andromuks", "AppViewModel: Original event body before merge: ${originalEvent.decrypted?.optString("body", "null")}")
                        
                        // Create a new event that merges the original event with the edit content
                        val mergedEvent = mergeEditContent(originalEvent, newEvent)
                        result[originalEventIndex] = mergedEvent
                        android.util.Log.d("Andromuks", "AppViewModel: Merged edit content into original event $supersededEventId")
                        android.util.Log.d("Andromuks", "AppViewModel: Final merged event body: ${mergedEvent.decrypted?.optString("body", "null")}")
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
        android.util.Log.d("Andromuks", "AppViewModel: mergeEditContent called")
        android.util.Log.d("Andromuks", "AppViewModel: Original event ID: ${originalEvent.eventId}")
        android.util.Log.d("Andromuks", "AppViewModel: Original event body: ${originalEvent.decrypted?.optString("body", "null")}")
        android.util.Log.d("Andromuks", "AppViewModel: Original event decrypted: ${originalEvent.decrypted}")
        android.util.Log.d("Andromuks", "AppViewModel: Edit event ID: ${editEvent.eventId}")
        android.util.Log.d("Andromuks", "AppViewModel: Edit event body: ${editEvent.decrypted?.optString("body", "null")}")
        android.util.Log.d("Andromuks", "AppViewModel: Edit event decrypted: ${editEvent.decrypted}")
        
        // Create a new content JSON object based on the original event
        val mergedContent = JSONObject(originalEvent.content.toString())
        
        // Get the new content from the edit event
        // For encrypted rooms, look in decrypted field; for non-encrypted rooms, look in content field
        val newContent = when {
            editEvent.type == "m.room.encrypted" -> editEvent.decrypted?.optJSONObject("m.new_content")
            editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.new_content")
            else -> null
        }
        android.util.Log.d("Andromuks", "AppViewModel: newContent from edit event: $newContent")
        
        if (newContent != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Original decrypted content before merge: ${originalEvent.decrypted}")
            
            // Create a completely new decrypted content object with the new content
            // Use the new content directly as the merged decrypted content
            val mergedDecrypted = JSONObject(newContent.toString())
            android.util.Log.d("Andromuks", "AppViewModel: Created mergedDecrypted from newContent: ${mergedDecrypted.toString()}")
            
            android.util.Log.d("Andromuks", "AppViewModel: Merged decrypted content after merge: ${mergedDecrypted.toString()}")
            android.util.Log.d("Andromuks", "AppViewModel: Final body after merge: ${mergedDecrypted.optString("body", "null")}")
            
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
            
            android.util.Log.d("Andromuks", "AppViewModel: Merged event body: ${mergedEvent.decrypted?.optString("body", "null")}")
            android.util.Log.d("Andromuks", "AppViewModel: Merged event content body: ${mergedEvent.content?.optString("body", "null")}")
            return mergedEvent
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: No new content found, returning original event")
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
                                    inviteReason = inviteReason
                                )
                                
                                pendingInvites[roomId] = invite
                                android.util.Log.d("Andromuks", "AppViewModel: Added room invite: $roomName from $inviterUserId")
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
        
        val acceptRequestId = requestIdCounter++
        joinRoomRequests[acceptRequestId] = roomId
        val via = roomId.substringAfter(":").substringBefore(".") // Extract server from room ID
        sendWebSocketCommand("join_room", acceptRequestId, mapOf(
            "room_id_or_alias" to roomId,
            "via" to listOf(via)
        ))
        
        // Remove from pending invites
        pendingInvites.remove(roomId)
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
    
    fun handleLeaveRoomResponse(requestId: Int, data: Any) {
        val roomId = leaveRoomRequests[requestId]
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Leave room response for room $roomId")
            // Room leave successful - invite will be removed from sync
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
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - calling back with null, NetworkMonitor will handle reconnection")
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
        }
    }

    /**
     * Stops the WebSocket service (used on logout or app cleanup)
     */
    fun stopWebSocketService() {
        appContext?.let { context ->
            val intent = android.content.Intent(context, WebSocketService::class.java)
            context.stopService(intent)
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket service stopped")
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
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected - cannot send thread reply, NetworkMonitor will handle reconnection")
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
        
        // Check if room already exists in our list
        val existingRoom = getRoomById(roomId)
        if (existingRoom != null) {
            // Room already exists (already joined), navigate immediately
            android.util.Log.d("Andromuks", "AppViewModel: Room already in list, navigating immediately")
            val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
            navController.navigate("room_timeline/$encodedRoomId")
            // Request timeline for the room
            requestRoomTimeline(roomId)
        } else {
            // Room not in list yet, wait for sync
            android.util.Log.d("Andromuks", "AppViewModel: Room not in list yet, setting pending navigation")
            pendingJoinedRoomNavigation = Pair(roomId, navController)
            // The actual navigation will happen when the room appears in sync update
        }
    }
    
    private var pendingJoinedRoomNavigation: Pair<String, androidx.navigation.NavController>? = null
    
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
                eventId = event.eventId,
                sender = event.sender,
                emoji = emoji,
                relatesToEventId = relatesToEventId,
                timestamp = event.timestamp
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
        globalProfileCache[userId] = WeakReference(profile)
        
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
        
        android.util.Log.d("Andromuks", "AppViewModel: Timeline events AFTER merge: ${timelineEvents.size}")
        android.util.Log.d("Andromuks", "AppViewModel: Cache AFTER merge: ${RoomTimelineCache.getCachedEventCount(roomId)} events")
        
        val newSmallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        android.util.Log.d("Andromuks", "AppViewModel: smallestRowId BEFORE: $smallestRowId")
        android.util.Log.d("Andromuks", "AppViewModel: smallestRowId AFTER: $newSmallestRowId")
        smallestRowId = newSmallestRowId
        
        android.util.Log.d("Andromuks", "AppViewModel: ========================================")
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
        smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
    }
}
