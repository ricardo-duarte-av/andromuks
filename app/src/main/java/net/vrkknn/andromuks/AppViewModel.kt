package net.vrkknn.andromuks

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
import android.content.Context
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build

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
    }
    var isLoading by mutableStateOf(false)
    var homeserverUrl by mutableStateOf("")
        private set
    var authToken by mutableStateOf("")
        private set
    var realMatrixHomeserverUrl by mutableStateOf("")
        private set
    private var appContext: Context? = null
    
    // Timeline cache for instant room opening
    private val roomTimelineCache = RoomTimelineCache()

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


    // List of spaces, each with their rooms
    var spaceList by mutableStateOf(listOf<SpaceItem>())
        private set
    
    // All rooms (for filtering into sections)
    var allRooms by mutableStateOf(listOf<RoomItem>())
        private set
    
    // All spaces (for Spaces section)
    var allSpaces by mutableStateOf(listOf<SpaceItem>())
        private set
    
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
    
    // Force recomposition counter
    var updateCounter by mutableStateOf(0)
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
    
    // Network Monitor (for immediate reconnection on network changes)
    private var networkMonitor: net.vrkknn.andromuks.utils.NetworkMonitor? = null
    
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
    
    private val pendingWebSocketOperations = mutableListOf<PendingWebSocketOperation>()
    private val maxRetryAttempts = 3

    var spacesLoaded by mutableStateOf(false)
        private set

    fun setSpaces(spaces: List<SpaceItem>) {
        android.util.Log.d("Andromuks", "AppViewModel: setSpaces called with ${spaces.size} spaces")
        spaceList = spaces
        updateCounter++
        android.util.Log.d("Andromuks", "AppViewModel: spaceList set to ${spaceList.size} spaces, updateCounter: $updateCounter")
    }
    
    fun updateAllSpaces(spaces: List<SpaceItem>) {
        allSpaces = spaces
        updateCounter++
        android.util.Log.d("Andromuks", "AppViewModel: allSpaces set to ${spaces.size} spaces")
    }
    
    fun changeSelectedSection(section: RoomSectionType) {
        selectedSection = section
        // Reset space navigation when switching tabs
        if (section != RoomSectionType.SPACES) {
            currentSpaceId = null
        }
        updateCounter++
    }
    
    fun enterSpace(spaceId: String) {
        currentSpaceId = spaceId
        updateCounter++
    }
    
    fun exitSpace() {
        currentSpaceId = null
        updateCounter++
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
        allSpaces = emptyList()
        spaceList = emptyList()
        spacesLoaded = false
        
        // 3. Reset requestIdCounter to 1
        requestIdCounter = 1
        
        // 4. FORCE CLEAR last_received_sync_id to 0 for clean state (keep run_id for reconnection)
        lastReceivedSyncId = 0
        lastReceivedRequestId = 0
        
        val preservedRunId = currentRunId
        android.util.Log.d("Andromuks", "AppViewModel: State reset complete - run_id preserved: $preservedRunId")
        android.util.Log.d("Andromuks", "AppViewModel: FORCE REFRESH - lastReceivedSyncId cleared to 0, will reconnect with run_id but NO last_received_id (full payload)")
        
        // 5. Trigger reconnection (will use run_id but not last_received_id since it's 0)
        onRestartWebSocket?.invoke("Full refresh")
    }
    
    
    // Get current room section based on selected tab
    fun getUnreadCount(): Int {
        val roomsToUse = if (allRooms.isEmpty() && spaceList.isNotEmpty()) {
            spaceList.firstOrNull()?.rooms ?: emptyList()
        } else {
            allRooms
        }
        return roomsToUse.count { 
            (it.unreadCount != null && it.unreadCount > 0) || 
            (it.highlightCount != null && it.highlightCount > 0) 
        }
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
        return readReceipts.mapValues { it.value.toList() }
    }
    
    fun getCurrentRoomSection(): RoomSection {
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
                val dmRooms = roomsToUse.filter { it.isDirectMessage }
                val unreadDmCount = dmRooms.count { 
                    (it.unreadCount != null && it.unreadCount > 0) || 
                    (it.highlightCount != null && it.highlightCount > 0) 
                }
                RoomSection(
                    type = RoomSectionType.DIRECT_CHATS,
                    rooms = dmRooms,
                    unreadCount = unreadDmCount
                )
            }
            RoomSectionType.UNREAD -> {
                val unreadRooms = roomsToUse.filter { 
                    (it.unreadCount != null && it.unreadCount > 0) || 
                    (it.highlightCount != null && it.highlightCount > 0) 
                }
                RoomSection(
                    type = RoomSectionType.UNREAD,
                    rooms = unreadRooms,
                    unreadCount = unreadRooms.size
                )
            }
            RoomSectionType.FAVOURITES -> {
                val favouriteRooms = roomsToUse.filter { it.isFavourite }
                val unreadFavouriteCount = favouriteRooms.count { 
                    (it.unreadCount != null && it.unreadCount > 0) || 
                    (it.highlightCount != null && it.highlightCount > 0) 
                }
                RoomSection(
                    type = RoomSectionType.FAVOURITES,
                    rooms = favouriteRooms,
                    unreadCount = unreadFavouriteCount
                )
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
        if (networkMonitor != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Network monitor already initialized")
            return
        }
        
        networkMonitor = net.vrkknn.andromuks.utils.NetworkMonitor(
            context = context,
            onNetworkAvailable = {
                android.util.Log.i("Andromuks", "AppViewModel: Network available - triggering immediate reconnection")
                // Immediate reconnection instead of waiting for ping timeout
                restartWebSocket("Network restored")
            },
            onNetworkLost = {
                android.util.Log.w("Andromuks", "AppViewModel: Network lost - connection will be down until network returns")
                // Could optionally clear WebSocket here, but better to let ping timeout handle it
                // This avoids unnecessary reconnect attempts when network is definitely unavailable
            }
        )
        
        networkMonitor?.startMonitoring()
        android.util.Log.d("Andromuks", "AppViewModel: Network monitoring started")
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
    
    fun processReactionEvent(reactionEvent: ReactionEvent) {
        // Create a unique key for this logical reaction (sender + emoji + target message)
        // This prevents the same logical reaction from being processed twice even if it comes
        // from both send_complete and sync_complete with different event IDs
        val reactionKey = "${reactionEvent.sender}_${reactionEvent.emoji}_${reactionEvent.relatesToEventId}"
        
        // Check if we've already processed this logical reaction recently
        if (processedReactions.contains(reactionKey)) {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping duplicate logical reaction: $reactionKey (eventId: ${reactionEvent.eventId})")
            return
        }
        
        // Mark this logical reaction as processed (temporarily, will be cleaned up)
        processedReactions.add(reactionKey)
        
        // Clean up old processed reactions to prevent memory leaks (keep only last 100)
        if (processedReactions.size > 100) {
            val toRemove = processedReactions.take(processedReactions.size - 100)
            processedReactions.removeAll(toRemove)
        }
        
        val oldReactions = messageReactions[reactionEvent.relatesToEventId]?.size ?: 0
        messageReactions = net.vrkknn.andromuks.utils.processReactionEvent(reactionEvent, currentRoomId, messageReactions)
        val newReactions = messageReactions[reactionEvent.relatesToEventId]?.size ?: 0
        android.util.Log.d("Andromuks", "AppViewModel: processReactionEvent - eventId: ${reactionEvent.eventId}, logicalKey: $reactionKey, oldCount: $oldReactions, newCount: $newReactions, updateCounter: $updateCounter")
        updateCounter++ // Trigger UI recomposition
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

    // Per-room member cache: roomId -> (userId -> MemberProfile)
    private val roomMemberCache = mutableMapOf<String, MutableMap<String, MemberProfile>>()
    
    // Global user profile cache for O(1) lookups (performance optimization)
    // This avoids scanning all room caches when looking up a profile
    private val globalProfileCache = mutableMapOf<String, MemberProfile>()
    
    // OPTIMIZED EDIT/REDACTION SYSTEM - O(1) lookups for all operations
    // Maps original event ID to its complete version history
    private val messageVersions = mutableMapOf<String, VersionedMessage>()
    
    // Maps edit event ID back to original event ID for quick lookup
    private val editToOriginal = mutableMapOf<String, String>()
    
    // Maps redacted event ID to the redaction event for O(1) deletion message creation
    private val redactionCache = mutableMapOf<String, TimelineEvent>()

    fun getMemberProfile(roomId: String, userId: String): MemberProfile? {
        return roomMemberCache[roomId]?.get(userId)
    }

    fun getMemberMap(roomId: String): Map<String, MemberProfile> {
        return roomMemberCache[roomId] ?: emptyMap()
    }
    
    fun isMemberCacheEmpty(roomId: String): Boolean {
        return roomMemberCache[roomId]?.isEmpty() ?: true
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
        // Check room member cache first if roomId is provided (most specific)
        if (roomId != null) {
            val roomMember = roomMemberCache[roomId]?.get(userId)
            if (roomMember != null) {
                return roomMember
            }
        }
        
        // Check if it's the current user
        if (userId == currentUserId && currentUserProfile != null) {
            return MemberProfile(
                displayName = currentUserProfile!!.displayName,
                avatarUrl = currentUserProfile!!.avatarUrl
            )
        }
        
        // PERFORMANCE: Check global profile cache (O(1) lookup instead of scanning all rooms)
        val globalProfile = globalProfileCache[userId]
        if (globalProfile != null) {
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
                            
                            messageVersions[originalEventId] = versioned.copy(
                                versions = updatedVersions
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
    
    private fun populateMemberCacheFromSync(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val roomsJson = data.optJSONObject("rooms") ?: return
        
        val roomKeys = roomsJson.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomObj = roomsJson.optJSONObject(roomId) ?: continue
            val events = roomObj.optJSONArray("events") ?: continue
            
            val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
            
            // Process all events to find member events
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventType = event.optString("type")
                
                if (eventType == "m.room.member") {
                    val userId = event.optString("state_key") ?: event.optString("sender")
                    val content = event.optJSONObject("content")
                    val membership = content?.optString("membership")
                    
                    if (userId != null) {
                        when (membership) {
                            "join" -> {
                                // Add/update only joined members to room cache for mention lists
                                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                                
                                val profile = MemberProfile(displayName, avatarUrl)
                                memberMap[userId] = profile
                                // PERFORMANCE: Also add to global cache for O(1) lookups
                                globalProfileCache[userId] = profile
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached joined member '$userId' in room '$roomId' -> displayName: '$displayName'")
                            }
                            "invite" -> {
                                // Store invited members in global cache only (for profile lookups) but not in room member cache
                                // This prevents them from appearing in mention lists but allows profile display if they send messages
                                val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                                val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                                
                                val profile = MemberProfile(displayName, avatarUrl)
                                globalProfileCache[userId] = profile
                                //android.util.Log.d("Andromuks", "AppViewModel: Cached invited member '$userId' profile in global cache only -> displayName: '$displayName'")
                            }
                            "leave", "ban" -> {
                                // Remove members who left or were banned from room cache only
                                memberMap.remove(userId)
                                // Note: Don't remove from global cache as they might be in other rooms
                                // Note: Keep disk cache for potential future re-joining
                            }
                        }
                    }
                }
            }
        }
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
    }

    fun updateRoomsFromSyncJson(syncJson: JSONObject) {
        // Update last sync timestamp for notification display
        lastSyncTimestamp = System.currentTimeMillis()
        
        // First, populate member cache from sync data
        populateMemberCacheFromSync(syncJson)
        
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
        
        // BATTERY OPTIMIZATION: Skip expensive UI updates when app is in background
        if (isAppVisible) {
            // Trigger timestamp update on sync (only for visible UI)
            triggerTimestampUpdate()
            
            // Update the UI with the current room list
            val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
            android.util.Log.d("Andromuks", "AppViewModel: Updating spaceList with ${sortedRooms.size} rooms (app visible)")
            
            // Update animation states with new positions
            sortedRooms.forEachIndexed { index, room ->
                updateRoomAnimationState(room.id, isAnimating = false, newPosition = index)
            }
            
            setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms)))
            allRooms = sortedRooms // Update allRooms for filtering
            
            // Update low priority rooms set for notification filtering
            updateLowPriorityRooms(sortedRooms)
            
            updateCounter++ // Force recomposition (only when visible)
            android.util.Log.d("Andromuks", "AppViewModel: spaceList updated, current size: ${spaceList.size}")
            
            // Update conversation shortcuts (only when visible)
            conversationsApi?.updateConversationShortcuts(sortedRooms)
            
            // Check if current room needs timeline update (only if a room is open)
            checkAndUpdateCurrentRoomTimeline(syncJson)
        } else {
            // App is in background - minimal processing for battery saving
            android.util.Log.d("Andromuks", "AppViewModel: BATTERY SAVE MODE - App in background, skipping UI updates")
            
            // Still update allRooms for data consistency (needed for notifications)
            val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
            allRooms = sortedRooms
            
            // Update low priority rooms set for notification filtering
            updateLowPriorityRooms(sortedRooms)
            
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
     * Populates space edges after init_complete when all rooms are loaded
     */
    private fun populateSpaceEdges() {
        if (storedSpaceEdges == null) {
            android.util.Log.d("Andromuks", "AppViewModel: No stored space edges to populate")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Populating space edges with ${allSpaces.size} spaces")
        
        // Create a mock sync data object with the stored space edges
        val mockSyncData = JSONObject()
        
        // Create rooms object from allRooms data
        val roomsObject = JSONObject()
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
        
        // Use the existing updateExistingSpacesWithEdges function
        net.vrkknn.andromuks.utils.SpaceRoomParser.updateExistingSpacesWithEdges(
            storedSpaceEdges!!, 
            mockSyncData, 
            this
        )
        
        // Clear stored space edges
        storedSpaceEdges = null
    }
    
    // Navigation callback
    var onNavigateToRoomList: (() -> Unit)? = null
    private var pendingNavigation = false
    private var navigationCallbackTriggered = false // Prevent multiple triggers
    
    // Pending room navigation from shortcuts
    private var pendingRoomNavigation: String? = null
    
    // Track if the pending navigation is from a notification (for optimized cache handling)
    private var isPendingNavigationFromNotification: Boolean = false
    
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
        
        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms)))
        allRooms = sortedRooms
        updateCounter++ // Trigger recomposition to show updated state
        
        // Update conversation shortcuts
        conversationsApi?.updateConversationShortcuts(sortedRooms)
        
        android.util.Log.d("Andromuks", "AppViewModel: UI refreshed, updateCounter: $updateCounter")
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
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        networkMonitor = null
        
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
    
    fun getAndIncrementRequestId(): Int = requestIdCounter++
    private val timelineRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val profileRequests = mutableMapOf<Int, String>() // requestId -> userId
    private val profileRequestRooms = mutableMapOf<Int, String>() // requestId -> roomId (for profile requests initiated from a specific room)
    private val roomStateRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val messageRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val reactionRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val markReadRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val readReceipts = mutableMapOf<String, MutableList<ReadReceipt>>() // eventId -> list of read receipts
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
    
    // Pagination state
    private var smallestRowId: Long = -1L // Smallest rowId from initial paginate
    private var isPaginating: Boolean = false // Prevent multiple pagination requests
    var hasMoreMessages by mutableStateOf(true) // Whether there are more messages to load
    
    
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var lastReceivedRequestId: Int = 0 // Tracks ANY incoming request_id (for pong detection)
    private var lastReceivedSyncId: Int = 0 // Tracks ONLY sync_complete negative request_ids (for reconnection)
    private var lastPingRequestId: Int = 0
    private var lastPingTimestamp: Long = 0 // Timestamp when ping was sent (for lag calculation)
    private var lastSyncTimestamp: Long = 0 // Timestamp of last sync_complete received
    private var pongTimeoutJob: Job? = null
    private var currentRunId: String = "" // Unique connection ID from gomuks backend
    private var vapidKey: String = "" // VAPID key for push notifications
    private var hasHadInitialConnection = false // Track if we've had an initial connection to only vibrate on reconnections

    fun setWebSocket(webSocket: WebSocket) {
        this.webSocket = webSocket
        startPingLoop()
        
        // Broadcast that socket connection is available and retry pending operations
        android.util.Log.i("Andromuks", "AppViewModel: WebSocket connection established - retrying ${pendingWebSocketOperations.size} pending operations")
        
        // Only vibrate on reconnections, not initial connection
        if (hasHadInitialConnection) {
            performReconnectionHaptic()
        } else {
            hasHadInitialConnection = true
        }
        
        retryPendingWebSocketOperations()
    }
    
    fun isWebSocketConnected(): Boolean {
        return webSocket != null
    }

    fun clearWebSocket() {
        this.webSocket = null
        pingJob?.cancel()
        pingJob = null
        pongTimeoutJob?.cancel()
        pongTimeoutJob = null
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
                        android.util.Log.w("Andromuks", "AppViewModel: Unknown operation type for retry: ${operation.type}")
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
    
    /**
     * Perform a subtle haptic vibration to indicate WebSocket reconnection
     */
    private fun performReconnectionHaptic() {
        appContext?.let { context ->
            // Ensure vibration happens on main thread
            viewModelScope.launch(Dispatchers.Main) {
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
                    if (vibrator?.hasVibrator() == true) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            // Modern API - use VibrationEffect for more control
                            val effect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                            vibrator.vibrate(effect)
                        } else {
                            // Legacy API
                            vibrator.vibrate(100)
                        }
                        android.util.Log.d("Andromuks", "AppViewModel: Performed reconnection haptic feedback")
                    } else {
                        android.util.Log.d("Andromuks", "AppViewModel: Device does not support vibration")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Andromuks", "AppViewModel: Failed to perform haptic feedback", e)
                }
            }
        }
    }

    fun noteIncomingRequestId(requestId: Int) {
        if (requestId != 0) {
            // Track ALL incoming request_ids for general purposes (pong detection, etc.)
            lastReceivedRequestId = requestId
            
            // Separately track ONLY negative request_ids from sync_complete for reconnection
            if (requestId < 0) {
                lastReceivedSyncId = requestId
                android.util.Log.d("Andromuks", "AppViewModel: Updated lastReceivedSyncId to $requestId (sync_complete)")
            }
            
            // If this is a pong response to our ping, cancel the timeout
            if (requestId == lastPingRequestId) {
                android.util.Log.d("Andromuks", "AppViewModel: Received pong for ping $requestId, canceling timeout")
                pongTimeoutJob?.cancel()
                pongTimeoutJob = null
                
                // Calculate lag
                val lagMs = System.currentTimeMillis() - lastPingTimestamp
                android.util.Log.d("Andromuks", "AppViewModel: Pong received, lag: ${lagMs}ms")
                
                // Update service notification with lag and last sync time
                if (lastSyncTimestamp > 0) {
                    WebSocketService.updateNotification(lagMs, lastSyncTimestamp)
                }
                
                // Trigger timestamp update on pong
                triggerTimestampUpdate()
            }
        }
    }
    
    /**
     * Stores the run_id and vapid_key received from the gomuks backend.
     * This is used for reconnection to resume from where we left off.
     */
    fun handleRunId(runId: String, vapidKey: String) {
        currentRunId = runId
        this.vapidKey = vapidKey
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
            
            android.util.Log.d("Andromuks", "AppViewModel: Cleared cached state")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to clear cached state", e)
        }
    }

    /**
     * Get appropriate ping interval based on app visibility
     * - 15 seconds when app is visible (responsive, user is actively using)
     * - 60 seconds when app is in background (battery efficient)
     */
    private fun getPingInterval(): Long {
        return if (isAppVisible) {
            15_000L  // 15 seconds - responsive when user is actively using app
        } else {
            60_000L  // 60 seconds - battery efficient when in background
        }
    }
    
    private fun startPingLoop() {
        pingJob?.cancel()
        val ws = webSocket ?: return
        pingJob = viewModelScope.launch {
            while (isActive) {
                val interval = getPingInterval()
                android.util.Log.d("Andromuks", "AppViewModel: Ping interval: ${interval}ms (app visible: $isAppVisible)")
                delay(interval)
                val currentWs = webSocket
                if (currentWs == null) {
                    // Socket gone; stop loop
                    break
                }
                val reqId = requestIdCounter++
                lastPingRequestId = reqId
                lastPingTimestamp = System.currentTimeMillis() // Store timestamp for lag calculation
                // Use lastReceivedSyncId (the negative sync_complete request_id) for reconnection tracking
                val data = mapOf("last_received_id" to lastReceivedSyncId)
                sendWebSocketCommand("ping", reqId, data)
                
                // Start timeout job for this ping
                startPongTimeout(reqId)
            }
        }
    }
    
    private fun startPongTimeout(pingRequestId: Int) {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = viewModelScope.launch {
            delay(5_000) // 5 second timeout
            android.util.Log.w("Andromuks", "AppViewModel: Pong timeout for ping $pingRequestId, restarting websocket")
            restartWebSocket("Ping timeout")
        }
    }
    
    private fun restartWebSocket(reason: String = "Unknown reason") {
        android.util.Log.d("Andromuks", "AppViewModel: Restarting websocket connection - Reason: $reason")
        
        // Show toast notification to user
        appContext?.let { context ->
            viewModelScope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "WS: $reason",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        clearWebSocket()
        // Trigger websocket restart via callback
        onRestartWebSocket?.invoke(reason)
    }

    fun requestUserProfile(userId: String, roomId: String? = null) {
        val ws = webSocket ?: return
        val reqId = requestIdCounter++
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
        val memberMap = roomMemberCache[roomId] ?: return
        val usersToRequest = mutableSetOf<String>()
        
        // Check each timeline event for missing user profile data
        for (event in timelineEvents) {
            val sender = event.sender
            val profile = memberMap[sender]
            
            // Check if we have incomplete profile data
            val hasDisplayName = !profile?.displayName.isNullOrBlank()
            val hasAvatar = !profile?.avatarUrl.isNullOrBlank()
            
            if (!hasDisplayName || !hasAvatar) {
                // Only request if we haven't already requested this user's profile (avoid duplicates)
                if (!profileRequests.values.contains(sender)) {
                    usersToRequest.add(sender)
                    android.util.Log.d("Andromuks", "AppViewModel: Missing profile data for $sender - displayName: $hasDisplayName, avatar: $hasAvatar")
                }
            }
        }
        
        // Request profiles for users with missing data
        for (userId in usersToRequest) {
            android.util.Log.d("Andromuks", "AppViewModel: Requesting missing profile for $userId")
            requestUserProfile(userId)
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
        
        // Check if we're opening from a notification (for optimized cache handling)
        val openingFromNotification = isPendingNavigationFromNotification && pendingRoomNavigation == roomId
        
        // Check if we have enough cached events BEFORE clearing anything
        // Use more lenient threshold for notification-based navigation to avoid loading spinners
        val cachedEvents = if (openingFromNotification) {
            // First try the standard threshold, then fall back to notification threshold
            roomTimelineCache.getCachedEvents(roomId) ?: roomTimelineCache.getCachedEventsForNotification(roomId)
        } else {
            roomTimelineCache.getCachedEvents(roomId)
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
            
            android.util.Log.d("Andromuks", "AppViewModel: ✅ Room opened INSTANTLY with ${timelineEvents.size} cached events (no loading flash)")
            
            // Clear notification flag since we've successfully loaded the room
            if (openingFromNotification) {
                isPendingNavigationFromNotification = false
            }
            
            // Request room state (doesn't affect timeline rendering)
            requestRoomState(roomId)
            
            // Send get_room_state command for member updates
            val stateRequestId = requestIdCounter++
            timelineRequests[stateRequestId] = roomId
            sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
                "room_id" to roomId,
                "include_members" to true,
                "fetch_members" to false,
                "refetch" to false
            ))
            
            // Mark as read
            val mostRecentEvent = cachedEvents.maxByOrNull { it.timestamp }
            if (mostRecentEvent != null) {
                markRoomAsRead(roomId, mostRecentEvent.eventId)
            }
            
            return // Exit early - room is already rendered from cache
        }
        
        // Check if we have partial cache (10-99 events) - show it and background prefetch more
        val partialCacheCount = roomTimelineCache.getCachedEventCount(roomId)
        if (partialCacheCount >= 10) {
            android.util.Log.d("Andromuks", "AppViewModel: 🔄 PARTIAL CACHE ($partialCacheCount events) - showing cached content and background prefetching more")
            
            // Show the partial cache immediately (like cache hit)
            val partialCachedEvents = roomTimelineCache.getCachedEventsForNotification(roomId)
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
                
                // Populate edit chain mapping from cached events
                for (event in partialCachedEvents) {
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
                val smallestCached = partialCachedEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
                if (smallestCached > 0) {
                    smallestRowId = smallestCached
                }
                
                android.util.Log.d("Andromuks", "AppViewModel: ✅ Room opened with partial cache (${timelineEvents.size} events) - background prefetch initiated")
                
                // Clear notification flag since we've successfully loaded the room
                if (openingFromNotification) {
                    isPendingNavigationFromNotification = false
                }
                
                // Request room state (doesn't affect timeline rendering)
                requestRoomState(roomId)
                
                // Send get_room_state command for member updates
                val stateRequestId = requestIdCounter++
                timelineRequests[stateRequestId] = roomId
                sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
                    "room_id" to roomId,
                    "include_members" to true,
                    "fetch_members" to false,
                    "refetch" to false
                ))
                
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
        
        // Request room state (including encryption status)
        requestRoomState(roomId)
        
        // Send get_room_state command with include_members = true
        val stateRequestId = requestIdCounter++
        timelineRequests[stateRequestId] = roomId
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to true,
            "fetch_members" to false,
            "refetch" to false
        ))
        
        // Send paginate command to fetch timeline from server
        val paginateRequestId = requestIdCounter++
        timelineRequests[paginateRequestId] = roomId
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 100,
            "reset" to false
        ))
        android.util.Log.d("Andromuks", "AppViewModel: Sent paginate request_id=$paginateRequestId for room=$roomId")
    }
    
    /**
     * Refreshes the room timeline by clearing cache and requesting fresh data from server.
     * This is useful for debugging missing events (e.g., messages from other devices).
     */
    fun refreshRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Refreshing timeline for room: $roomId (clearing cache and requesting fresh data)")
        
        // 1. Drop all cache for this room
        roomTimelineCache.clearRoomCache(roomId)
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
        
        // 6. Request fresh room state
        requestRoomState(roomId)
        
        // 7. Send fresh paginate command to get consistent data from server
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
    
    fun requestRoomState(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting room state for room: $roomId")
        val stateRequestId = requestIdCounter++
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
        android.util.Log.d("Andromuks", "AppViewModel: Requesting full member list for room: $roomId")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, skipping full member list request")
            return
        }
        
        val requestId = requestIdCounter++
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
        android.util.Log.d("Andromuks", "AppViewModel: Sending typing indicator for room: $roomId")
        val typingRequestId = requestIdCounter++
        val result = sendWebSocketCommand("set_typing", typingRequestId, mapOf(
            "room_id" to roomId,
            "timeout" to 10000
        ))
        
        if (result != WebSocketResult.SUCCESS) {
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
            
            return
        }
        
        // WebSocket is ready (maintained by foreground service), send message directly
        android.util.Log.d("Andromuks", "AppViewModel: Sending message from notification (WebSocket maintained by service)")
        val messageRequestId = requestIdCounter++
        
        messageRequests[messageRequestId] = roomId
        pendingSendCount++
        if (onComplete != null) {
            notificationActionCompletionCallbacks[messageRequestId] = onComplete
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
        
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        
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
            
            return
        }
        
        // WebSocket is ready (maintained by foreground service), mark as read directly
        android.util.Log.d("Andromuks", "AppViewModel: Marking room as read from notification (WebSocket maintained by service)")
        val markReadRequestId = requestIdCounter++
        
        markReadRequests[markReadRequestId] = roomId
        if (onComplete != null) {
            notificationActionCompletionCallbacks[markReadRequestId] = onComplete
        }
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        )
        
        sendWebSocketCommand("mark_read", markReadRequestId, commandData)
        
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

    fun handleResponse(requestId: Int, data: Any) {
        android.util.Log.d("Andromuks", "AppViewModel: handleResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}")
        android.util.Log.d("Andromuks", "AppViewModel: outgoingRequests contains $requestId: ${outgoingRequests.containsKey(requestId)}")
        
        if (profileRequests.containsKey(requestId)) {
            handleProfileResponse(requestId, data)
        } else if (timelineRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (roomStateRequests.containsKey(requestId)) {
            handleRoomStateResponse(requestId, data)
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
            android.util.Log.d("Andromuks", "AppViewModel: Routing background prefetch response to handleTimelineResponse")
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
        } else if (roomSpecificStateRequests.containsKey(requestId)) {
            handleRoomSpecificStateResponse(requestId, data)
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
        } else if (markReadRequests.containsKey(requestId)) {
            android.util.Log.w("Andromuks", "AppViewModel: Mark read error for requestId=$requestId: $errorMessage")
            // Remove the failed request from pending
            markReadRequests.remove(requestId)
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
            fullMemberListRequests.remove(requestId)
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Unknown error requestId=$requestId: $errorMessage")
        }
    }
    
    private fun handleProfileError(requestId: Int, errorMessage: String) {
        val userId = profileRequests.remove(requestId) ?: return
        val requestingRoomId = profileRequestRooms.remove(requestId)
        android.util.Log.d("Andromuks", "AppViewModel: Profile not found for $userId: $errorMessage")
        
        // If profile not found, use username part of Matrix ID
        val username = userId.removePrefix("@").substringBefore(":")
        val memberProfile = MemberProfile(username, null)
        
        // PERFORMANCE: Add to global cache first
        globalProfileCache[userId] = memberProfile
        
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
        
        // Trigger UI update since member cache changed
        updateCounter++
    }
    
    private fun handleProfileResponse(requestId: Int, data: Any) {
        val userId = profileRequests.remove(requestId) ?: return
        val requestingRoomId = profileRequestRooms.remove(requestId)
        val obj = data as? JSONObject ?: return
        val avatar = obj.optString("avatar_url")?.takeIf { it.isNotBlank() }
        val display = obj.optString("displayname")?.takeIf { it.isNotBlank() }
        
        val memberProfile = MemberProfile(display, avatar)
        
        // PERFORMANCE: Add to global profile cache first (O(1) access)
        globalProfileCache[userId] = memberProfile
        
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
            android.util.Log.d("Andromuks", "AppViewModel: Invoking full user info callback for profile")
            fullUserInfoCallback(obj)
        }
        
        // Trigger UI update since member cache changed
        updateCounter++
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
    
    /**
     * Loads a user profile from disk cache
     */
    private fun loadProfileFromDisk(context: android.content.Context, userId: String): MemberProfile? {
        return try {
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
            val profileJsonString = sharedPrefs.getString("profile_$userId", null) ?: return null
            
            val profileJson = JSONObject(profileJsonString)
            val displayName = profileJson.optString("displayName").takeIf { it.isNotBlank() }
            val avatarUrl = profileJson.optString("avatarUrl").takeIf { it.isNotBlank() }
            
            MemberProfile(displayName, avatarUrl)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load profile from disk for $userId", e)
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
        val roomId = timelineRequests[requestId] ?: paginateRequests[requestId] ?: backgroundPrefetchRequests[requestId]
        if (roomId == null) {
            android.util.Log.w("Andromuks", "AppViewModel: Received response for unknown request ID: $requestId")
            return
        }

        val isPaginateRequest = paginateRequests.containsKey(requestId)
        val isBackgroundPrefetchRequest = backgroundPrefetchRequests.containsKey(requestId)
        android.util.Log.d("Andromuks", "AppViewModel: Handling timeline response for room: $roomId, requestId: $requestId, isPaginate: $isPaginateRequest, isBackgroundPrefetch: $isBackgroundPrefetchRequest, data type: ${data::class.java.simpleName}")

        fun processEventsArray(eventsArray: JSONArray) {
            android.util.Log.d("Andromuks", "AppViewModel: processEventsArray called with ${eventsArray.length()} events from server")
            val timelineList = mutableListOf<TimelineEvent>()
            val allEvents = mutableListOf<TimelineEvent>()  // For version processing
            val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
            
            var ownMessageCount = 0
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
                    
                    if (event.type == "m.room.member" && event.timelineRowid == -1L) {
                        // State member event; update cache only
                        val userId = event.stateKey ?: event.sender
                        val displayName = event.content?.optString("displayname")?.takeIf { it.isNotBlank() }
                        val avatarUrl = event.content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                        val profile = MemberProfile(displayName, avatarUrl)
                        memberMap[userId] = profile
                        // PERFORMANCE: Also add to global cache for O(1) lookups
                        globalProfileCache[userId] = profile
                    } else {
                        // Process reaction events from paginate
                        if (event.type == "m.reaction") {
                            val content = event.content
                            if (content != null) {
                                val relatesTo = content.optJSONObject("m.relates_to")
                                if (relatesTo != null) {
                                    val relatesToEventId = relatesTo.optString("event_id")
                                    val emoji = relatesTo.optString("key")
                                    val relType = relatesTo.optString("rel_type")
                                    
                                    if (relatesToEventId.isNotBlank() && emoji.isNotBlank() && relType == "m.annotation") {
                                        // Check if this historical reaction has been redacted
                                        if (event.redactedBy == null) {
                                            // Only process non-redacted reactions
                                            val reactionEvent = ReactionEvent(
                                                eventId = event.eventId,
                                                sender = event.sender,
                                                emoji = emoji,
                                                relatesToEventId = relatesToEventId,
                                                timestamp = event.timestamp
                                            )
                                            processReactionEvent(reactionEvent)
                                            android.util.Log.d("Andromuks", "AppViewModel: Processed historical reaction: $emoji from ${event.sender} to $relatesToEventId")
                                        } else {
                                            android.util.Log.d("Andromuks", "AppViewModel: Skipping redacted historical reaction: $emoji from ${event.sender} to $relatesToEventId")
                                        }
                                    }
                                }
                            }
                        } else if (event.timelineRowid >= 0L) {
                            // Only render non-reaction timeline entries
                            timelineList.add(event)
                        }
                    }
                }
            }
            android.util.Log.d("Andromuks", "AppViewModel: Processed events - timeline=${timelineList.size}, members=${memberMap.size}, ownMessages=$ownMessageCount")
            if (ownMessageCount > 0) {
                android.util.Log.d("Andromuks", "AppViewModel: ★★★ PAGINATE RESPONSE CONTAINS $ownMessageCount OF YOUR OWN MESSAGES ★★★")
            }
            
            // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
            android.util.Log.d("Andromuks", "AppViewModel: Processing ${allEvents.size} events for version tracking")
            processVersionedMessages(allEvents)
            
            // Handle empty pagination responses
            if ((paginateRequests.containsKey(requestId) || backgroundPrefetchRequests.containsKey(requestId)) && timelineList.isEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Empty pagination/prefetch response - no more messages to load")
                paginateRequests.remove(requestId)
                backgroundPrefetchRequests.remove(requestId)
                isPaginating = false
                return
            }
            
            if (timelineList.isNotEmpty()) {
                // Handle background prefetch requests first - before any UI processing
                if (backgroundPrefetchRequests.containsKey(requestId)) {
                    // This is a background prefetch request - silently add to cache without affecting UI
                    android.util.Log.d("Andromuks", "AppViewModel: Processing background prefetch request, silently adding ${timelineList.size} events to cache")
                    
                    // Add events to cache silently (merge with existing cache)
                    roomTimelineCache.mergePaginatedEvents(roomId, timelineList)
                    
                    // Clean up the background prefetch request
                    backgroundPrefetchRequests.remove(requestId)
                    
                    val newCacheCount = roomTimelineCache.getCachedEventCount(roomId)
                    android.util.Log.d("Andromuks", "AppViewModel: ✅ Background prefetch completed - cache now has $newCacheCount events for room $roomId")
                    
                    return // Exit early - don't process edit chains or UI updates for background prefetch
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
                
                // Populate edit chain mapping for clean edit handling
                eventChainMap.clear()
                editEventsMap.clear()
                for (event in timelineList) {
                    // Check if this is an edit event
                    val isEditEvent = when {
                        event.type == "m.room.message" -> event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                        else -> false
                    }
                    
                    if (isEditEvent) {
                        // Store edit event separately
                        editEventsMap[event.eventId] = event
                        android.util.Log.d("Andromuks", "AppViewModel: Added edit event ${event.eventId} to edit events map")
                    } else {
                        // Regular events have their own bubble
                        eventChainMap[event.eventId] = EventChainEntry(
                            eventId = event.eventId,
                            ourBubble = event,
                            replacedBy = null,
                            originalTimestamp = event.timestamp
                        )
                        android.util.Log.d("Andromuks", "AppViewModel: Added regular event ${event.eventId} to chain mapping")
                    }
                }
                
                // Process edit relationships
                processEditRelationships()
                
                if (paginateRequests.containsKey(requestId)) {
                    // This is a pagination request - merge with existing timeline
                    android.util.Log.d("Andromuks", "AppViewModel: Processing pagination request, merging with existing timeline")
                    android.util.Log.d("Andromuks", "AppViewModel: Timeline list size before merge: ${timelineList.size}")
                    mergePaginationEvents(timelineList)
                    paginateRequests.remove(requestId)
                    isPaginating = false
                    android.util.Log.d("Andromuks", "AppViewModel: Pagination completed, isPaginating set to false")
                    android.util.Log.d("Andromuks", "AppViewModel: Timeline events count after merge: ${timelineEvents.size}")
                } else {
                    // This is an initial paginate - build timeline from chain mapping
                    buildTimelineFromChain()
                    isTimelineLoading = false
                    android.util.Log.d("Andromuks", "AppViewModel: timelineEvents set, isTimelineLoading set to false")
                    
                    // Seed the cache with these paginated events for future instant opens
                    android.util.Log.d("Andromuks", "AppViewModel: Seeding cache with ${timelineList.size} paginated events for room $roomId")
                    roomTimelineCache.seedCacheWithPaginatedEvents(roomId, timelineList)
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
        }

        when (data) {
            is JSONArray -> {
                processEventsArray(data)
            }
            is JSONObject -> {
                val eventsArray = data.optJSONArray("events")
                if (eventsArray != null) {
                    processEventsArray(eventsArray)
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: JSONObject did not contain 'events' array")
                }
                
                // Parse has_more field for pagination (but not for background prefetch)
                if (paginateRequests.containsKey(requestId)) {
                    val hasMore = data.optBoolean("has_more", true) // Default to true if not present
                    hasMoreMessages = hasMore
                    android.util.Log.d("Andromuks", "AppViewModel: Parsed has_more: $hasMore from pagination response")
                    android.util.Log.d("Andromuks", "AppViewModel: Full pagination response data keys: ${data.keys().asSequence().toList()}")
                } else if (backgroundPrefetchRequests.containsKey(requestId)) {
                    // For background prefetch, we don't update hasMoreMessages to avoid affecting UI
                    android.util.Log.d("Andromuks", "AppViewModel: Skipping has_more parsing for background prefetch request")
                }
                
                // Process read receipts from timeline response
                val receipts = data.optJSONObject("receipts")
                if (receipts != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: Processing read receipts from timeline response for room: $roomId")
                    ReceiptFunctions.processReadReceipts(receipts, readReceipts) { updateCounter++ }
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleTimelineResponse: ${data::class.java.simpleName}")
            }
        }

        timelineRequests.remove(requestId)
        paginateRequests.remove(requestId)
        backgroundPrefetchRequests.remove(requestId)
    }
    
    private fun handleRoomStateResponse(requestId: Int, data: Any) {
        val roomId = roomStateRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling room state response for room: $roomId")
        
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
    
    private fun parseRoomStateFromEvents(roomId: String, events: JSONArray) {
        var name: String? = null
        var canonicalAlias: String? = null
        var topic: String? = null
        var avatarUrl: String? = null
        var isEncrypted = false
        var powerLevels: PowerLevelsInfo? = null
        
        android.util.Log.d("Andromuks", "AppViewModel: Parsing room state for room: $roomId, events count: ${events.length()}")
        
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i)
            if (event != null) {
                val eventType = event.optString("type")
                val content = event.optJSONObject("content")
                
                android.util.Log.d("Andromuks", "AppViewModel: Processing event type: $eventType")
                
                when (eventType) {
                    "m.room.name" -> {
                        name = content?.optString("name")?.takeIf { it.isNotBlank() }
                        android.util.Log.d("Andromuks", "AppViewModel: Found room name: $name")
                    }
                    "m.room.canonical_alias" -> {
                        canonicalAlias = content?.optString("alias")?.takeIf { it.isNotBlank() }
                        android.util.Log.d("Andromuks", "AppViewModel: Found canonical alias: $canonicalAlias")
                    }
                    "m.room.topic" -> {
                        // Try simple topic first
                        topic = content?.optString("topic")?.takeIf { it.isNotBlank() }
                        
                        // If not found, try structured format
                        if (topic.isNullOrBlank()) {
                            val topicContent = content?.optJSONObject("m.topic")
                            val textArray = topicContent?.optJSONArray("m.text")
                            if (textArray != null && textArray.length() > 0) {
                                val firstText = textArray.optJSONObject(0)
                                topic = firstText?.optString("body")?.takeIf { it.isNotBlank() }
                            }
                        }
                        android.util.Log.d("Andromuks", "AppViewModel: Found topic: $topic")
                    }
                    "m.room.avatar" -> {
                        avatarUrl = content?.optString("url")?.takeIf { it.isNotBlank() }
                        android.util.Log.d("Andromuks", "AppViewModel: Found avatar URL: $avatarUrl")
                    }
                    "m.room.encryption" -> {
                        // Check if the room is encrypted (presence of m.room.encryption event)
                        val algorithm = content?.optString("algorithm")?.takeIf { it.isNotBlank() }
                        if (algorithm != null) {
                            isEncrypted = true
                            android.util.Log.d("Andromuks", "AppViewModel: Room is encrypted with algorithm: $algorithm")
                        }
                    }
                    "m.room.power_levels" -> {
                        // Parse power levels
                        val usersObj = content?.optJSONObject("users")
                        val usersMap = mutableMapOf<String, Int>()
                        usersObj?.keys()?.forEach { userId ->
                            usersMap[userId] = usersObj.optInt(userId, 0)
                        }
                        val usersDefault = content?.optInt("users_default", 0) ?: 0
                        val redact = content?.optInt("redact", 50) ?: 50
                        
                        powerLevels = PowerLevelsInfo(
                            users = usersMap,
                            usersDefault = usersDefault,
                            redact = redact
                        )
                        android.util.Log.d("Andromuks", "AppViewModel: Found power levels - users: ${usersMap.size}, usersDefault: $usersDefault, redact: $redact")
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
            powerLevels = powerLevels
        )
        
        // Update current room state
        currentRoomState = roomState
        updateCounter++
        
        android.util.Log.d("Andromuks", "AppViewModel: Parsed room state - Name: $name, Alias: $canonicalAlias, Topic: $topic, Avatar: $avatarUrl, Encrypted: $isEncrypted")
    }
    
    private fun handleMessageResponse(requestId: Int, data: Any) {
        val roomId = messageRequests.remove(requestId) ?: return
        if (pendingSendCount > 0) {
            pendingSendCount--
        }
        android.util.Log.d("Andromuks", "AppViewModel: Handling message response for room: $roomId, pendingSendCount=$pendingSendCount")
        
        // NOTE: We receive send_complete for sent messages, so we don't need to process
        // the response here to avoid duplicates. send_complete will add the event to timeline.
        android.util.Log.d("Andromuks", "AppViewModel: Message response received, waiting for send_complete for actual event")
        
        // Invoke completion callback for notification actions
        notificationActionCompletionCallbacks.remove(requestId)?.invoke()
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
    
    private fun handleRoomSpecificStateResponse(requestId: Int, data: Any) {
        val roomId = roomSpecificStateRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling room specific state response for room: $roomId")
        
        when (data) {
            is JSONArray -> {
                // Parse member events from the response
                parseMemberEventsForProfileUpdate(roomId, data)
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
        android.util.Log.d("Andromuks", "AppViewModel: Handling full member list response for room: $roomId")
        
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
                            // PERFORMANCE: Also add to global cache for O(1) lookups
                            globalProfileCache[stateKey] = newProfile
                            
                            android.util.Log.d("Andromuks", "AppViewModel: Added member $stateKey to room $roomId - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            updatedMembers++
                            
                            // Save to disk for persistence
                            appContext?.let { context ->
                                saveProfileToDisk(context, stateKey, newProfile)
                            }
                        }
                        "leave", "ban" -> {
                            // Remove members who left or were banned
                            val wasRemoved = memberMap.remove(stateKey) != null
                            if (wasRemoved) {
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
            android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedMembers members in full member list for room $roomId")
            // Trigger UI update since member cache changed
            updateCounter++
        }
    }
    
    private fun parseMemberEventsForProfileUpdate(roomId: String, events: JSONArray) {
        android.util.Log.d("Andromuks", "AppViewModel: Parsing ${events.length()} member events for profile update in room: $roomId")
        
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
                            globalProfileCache[stateKey] = newProfile
                            
                            android.util.Log.d("Andromuks", "AppViewModel: Updated profile for $stateKey - displayName: '$displayName', avatarUrl: '$avatarUrl'")
                            updatedProfiles++
                            
                            // Save updated profile to disk
                            appContext?.let { context ->
                                saveProfileToDisk(context, stateKey, newProfile)
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
            android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedProfiles profiles in room $roomId")
            // Trigger UI update since member cache changed
            updateCounter++
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
    private fun cacheTimelineEventsFromSync(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data") ?: return
        val rooms = data.optJSONObject("rooms") ?: return
        
        val roomKeys = rooms.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomData = rooms.optJSONObject(roomId) ?: continue
            val events = roomData.optJSONArray("events") ?: continue
            
            android.util.Log.d("Andromuks", "AppViewModel: Caching ${events.length()} events for room: $roomId (current room: $currentRoomId)")
            
            // Get member map for this room
            val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
            
            // Add events to cache
            roomTimelineCache.addEventsFromSync(roomId, events, memberMap)
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
                                powerLevels = currentRoomState?.powerLevels // Preserve existing power levels
                            )
                            currentRoomState = roomState
                            android.util.Log.d("Andromuks", "AppViewModel: Updated room state from sync: $roomState")
                        }
                    }
                    
                    // Process new timeline events
                    val events = roomData.optJSONArray("events")
                    if (events != null && events.length() > 0) {
                        android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.length()} new timeline events for room: $roomId")
                        processSyncEventsArray(events, roomId)
                    }
                    
                    // Process read receipts
                    val receipts = roomData.optJSONObject("receipts")
                    if (receipts != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: Processing read receipts for room: $roomId - found ${receipts.length()} event receipts")
                        ReceiptFunctions.processReadReceipts(receipts, readReceipts) { updateCounter++ }
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
        events.sortBy { it.timestamp }
        android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} events in timestamp order")
        
        // Count event types for debugging
        val eventTypeCounts = events.groupBy { it.type }.mapValues { it.value.size }
        val ownMessageCount = events.count { it.sender == currentUserId && (it.type == "m.room.message" || it.type == "m.room.encrypted") }
        android.util.Log.d("Andromuks", "AppViewModel: Event breakdown: $eventTypeCounts (including $ownMessageCount from YOU)")
        
        // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
        android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} sync events for version tracking")
        processVersionedMessages(events)
        
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
                        globalProfileCache[userId] = profile
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
                
                // Trigger UI update
                updateCounter++
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
            (event.type == "m.room.redaction")
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
     * Finds the end of an edit chain by following replacedBy links.
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
     */
    private fun buildTimelineFromChain() {
        android.util.Log.d("Andromuks", "AppViewModel: buildTimelineFromChain called with ${eventChainMap.size} events")
        android.util.Log.d("Andromuks", "AppViewModel: Edit events map has ${editEventsMap.size} events")
        
        val timelineEvents = mutableListOf<TimelineEvent>()
        
        // Process all events in the chain
        for ((eventId, entry) in eventChainMap) {
            if (entry.ourBubble != null) {
                // This is a regular event with a bubble
                android.util.Log.d("Andromuks", "AppViewModel: Processing event ${eventId} (type: ${entry.ourBubble?.type}) with replacedBy: ${entry.replacedBy}")
                val finalEvent = getFinalEventForBubble(entry)
                timelineEvents.add(finalEvent)
                android.util.Log.d("Andromuks", "AppViewModel: Added event for ${eventId} with final content from ${entry.replacedBy ?: eventId}")
            }
        }
        
        // Process redactions - mark events as redacted
        android.util.Log.d("Andromuks", "AppViewModel: Processing redactions from eventChainMap")
        for ((eventId, entry) in eventChainMap) {
            val redactionEvent = entry.ourBubble
            if (redactionEvent != null && redactionEvent.type == "m.room.redaction") {
                val redactsEventId = redactionEvent.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                
                if (redactsEventId != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: Found redaction ${redactionEvent.eventId} targeting $redactsEventId")
                    
                    // Find and mark the target event as redacted
                    val targetIndex = timelineEvents.indexOfFirst { it.eventId == redactsEventId }
                    if (targetIndex >= 0) {
                        val targetEvent = timelineEvents[targetIndex]
                        val redactedEvent = targetEvent.copy(redactedBy = redactionEvent.eventId)
                        timelineEvents[targetIndex] = redactedEvent
                        android.util.Log.d("Andromuks", "AppViewModel: Marked event $redactsEventId as redacted by ${redactionEvent.eventId}")
                    } else {
                        android.util.Log.w("Andromuks", "AppViewModel: Could not find target event $redactsEventId for redaction")
                    }
                }
            }
        }
        
        // Sort by timestamp and update timeline
        this.timelineEvents = timelineEvents.sortedBy { it.timestamp }
        updateCounter++
        
        android.util.Log.d("Andromuks", "AppViewModel: Built timeline with ${timelineEvents.size} events from chain")
    }
    
    private fun mergePaginationEvents(newEvents: List<TimelineEvent>) {
        android.util.Log.d("Andromuks", "AppViewModel: mergePaginationEvents called with ${newEvents.size} new events")
        android.util.Log.d("Andromuks", "AppViewModel: Current timeline has ${timelineEvents.size} events")
        
        // Separate redactions from regular events
        val redactionEvents = newEvents.filter { it.type == "m.room.redaction" }
        val regularEvents = newEvents.filter { it.type != "m.room.redaction" }
        
        // Add regular events to existing timeline
        val currentEvents = timelineEvents.toMutableList()
        currentEvents.addAll(regularEvents)
        
        android.util.Log.d("Andromuks", "AppViewModel: Combined timeline has ${currentEvents.size} events before sorting (${redactionEvents.size} redactions to process)")
        
        // Process redactions from paginated events
        for (redactionEvent in redactionEvents) {
            val redactsEventId = redactionEvent.content?.optString("redacts")?.takeIf { it.isNotBlank() }
            
            if (redactsEventId != null) {
                android.util.Log.d("Andromuks", "AppViewModel: Pagination redaction ${redactionEvent.eventId} targets $redactsEventId")
                
                // Find and mark the target event as redacted
                val targetIndex = currentEvents.indexOfFirst { it.eventId == redactsEventId }
                if (targetIndex >= 0) {
                    val targetEvent = currentEvents[targetIndex]
                    val redactedEvent = targetEvent.copy(redactedBy = redactionEvent.eventId)
                    currentEvents[targetIndex] = redactedEvent
                    android.util.Log.d("Andromuks", "AppViewModel: Marked paginated event $redactsEventId as redacted by ${redactionEvent.eventId}")
                } else {
                    android.util.Log.w("Andromuks", "AppViewModel: Could not find target event $redactsEventId for pagination redaction")
                }
            }
        }
        
        // Sort chronologically by timestamp
        this.timelineEvents = currentEvents.sortedBy { it.timestamp }
        updateCounter++
        
        android.util.Log.d("Andromuks", "AppViewModel: Timeline sorted and updated, updateCounter incremented to $updateCounter")
        
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
        android.util.Log.d("Andromuks", "AppViewModel: loadOlderMessages called for room: $roomId")
        android.util.Log.d("Andromuks", "AppViewModel: Current state - isPaginating: $isPaginating, smallestRowId: $smallestRowId, hasMoreMessages: $hasMoreMessages, webSocket connected: ${webSocket != null}")
        
        if (isPaginating || smallestRowId <= 0 || !hasMoreMessages) {
            android.util.Log.d("Andromuks", "AppViewModel: Pagination blocked - isPaginating: $isPaginating, smallestRowId: $smallestRowId, hasMoreMessages: $hasMoreMessages")
            return
        }
        
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, cannot load older messages")
            return
        }
        
        isPaginating = true
        val paginateRequestId = requestIdCounter++
        paginateRequests[paginateRequestId] = roomId
        
        android.util.Log.d("Andromuks", "AppViewModel: Sending pagination request with requestId: $paginateRequestId, max_timeline_id: $smallestRowId")
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to smallestRowId,
            "limit" to 50,
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
        updateCounter++
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
        updateCounter++
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
    
    private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): WebSocketResult {
        val ws = webSocket
        if (ws == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket is not connected, cannot send command: $command")
            return WebSocketResult.NOT_CONNECTED
        }
        
        return try {
            val json = org.json.JSONObject()
            json.put("command", command)
            json.put("request_id", requestId)
            json.put("data", org.json.JSONObject(data))
            val jsonString = json.toString()
            //android.util.Log.d("Andromuks", "AppViewModel: Sending command: $jsonString")
            ws.send(jsonString)
            WebSocketResult.SUCCESS
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to send WebSocket command: $command", e)
            WebSocketResult.CONNECTION_ERROR
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
        
        var completedRequests = 0
        val totalRequests = 3
        var hasError = false
        
        fun checkCompletion() {
            completedRequests++
            if (completedRequests >= totalRequests && !hasError) {
                val profileInfo = net.vrkknn.andromuks.utils.UserProfileInfo(
                    userId = userId,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    timezone = timezone,
                    encryptionInfo = encryptionInfo,
                    mutualRooms = mutualRooms
                )
                callback(profileInfo, null)
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
            checkCompletion()
        }
        
        // Request 3: Mutual Rooms
        // Skip mutual rooms request if viewing our own profile (backend returns HTTP 422)
        if (userId == currentUserId) {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping mutual rooms request for self")
            mutualRooms = emptyList()
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
                checkCompletion()
            }
        }
        
        // Handle profile response separately
        val tempProfileCallback: (JSONObject?) -> Unit = { profileData ->
            if (profileData != null) {
                displayName = profileData.optString("displayname")?.takeIf { it.isNotBlank() }
                avatarUrl = profileData.optString("avatar_url")?.takeIf { it.isNotBlank() }
                timezone = profileData.optString("us.cloke.msc4175.tz")?.takeIf { it.isNotBlank() }
            }
            checkCompletion()
        }
        
        // Store this callback for later
        fullUserInfoCallbacks[profileRequestId] = tempProfileCallback
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
}
