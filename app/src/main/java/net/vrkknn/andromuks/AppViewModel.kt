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

data class MemberProfile(
    val displayName: String?,
    val avatarUrl: String?
)

data class UserProfile(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?
)

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
    var keepWebSocketOpened by mutableStateOf(false)
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
    private var notificationActionInProgress = false
    private var notificationActionShutdownTimer: Job? = null
    private val notificationActionCompletionCallbacks = mutableMapOf<Int, () -> Unit>()

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
    
    fun restartWebSocketConnection() {
        android.util.Log.d("Andromuks", "AppViewModel: Restarting WebSocket connection via pull-to-refresh")
        restartWebSocket()
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
        
        // 4. Reset last_received_sync_id to 0 (keep run_id for reconnection)
        lastReceivedSyncId = 0
        lastReceivedRequestId = 0
        
        android.util.Log.d("Andromuks", "AppViewModel: State reset complete - run_id preserved: $currentRunId")
        android.util.Log.d("Andromuks", "AppViewModel: Will reconnect with run_id but no last_received_id (full payload)")
        
        // 5. Trigger reconnection (will use run_id but not last_received_id since it's 0)
        onRestartWebSocket?.invoke()
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
        }
    }

    fun showLoading() {
        isLoading = true
    }

    fun hideLoading() {
        isLoading = false
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
        messageReactions = net.vrkknn.andromuks.utils.processReactionEvent(reactionEvent, currentRoomId, messageReactions)
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

    fun getMemberProfile(roomId: String, userId: String): MemberProfile? {
        return roomMemberCache[roomId]?.get(userId)
    }

    fun getMemberMap(roomId: String): Map<String, MemberProfile> {
        return roomMemberCache[roomId] ?: emptyMap()
    }
    
    /**
     * Gets user profile information for a given user ID
     * First checks room member cache, then current user profile, then requests profile if needed
     */
    fun getUserProfile(userId: String, roomId: String? = null): MemberProfile? {
        android.util.Log.d("Andromuks", "AppViewModel: getUserProfile called for userId='$userId', roomId='$roomId'")
        
        // Check room member cache first if roomId is provided
        if (roomId != null) {
            val roomMember = roomMemberCache[roomId]?.get(userId)
            android.util.Log.d("Andromuks", "AppViewModel: Room member cache for room '$roomId' has ${roomMemberCache[roomId]?.size ?: 0} members")
            if (roomMember != null) {
                android.util.Log.d("Andromuks", "AppViewModel: Found room member: $roomMember")
                return roomMember
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: User '$userId' not found in room '$roomId' member cache")
            }
        }
        
        // Check if it's the current user
        if (userId == currentUserId && currentUserProfile != null) {
            return MemberProfile(
                displayName = currentUserProfile!!.displayName,
                avatarUrl = currentUserProfile!!.avatarUrl
            )
        }
        
        // Try to find in any room's member cache
        for (roomMembers in roomMemberCache.values) {
            val member = roomMembers[userId]
            if (member != null) {
                return member
            }
        }
        
        // If we have a Matrix ID format but no profile, request it
        if (userId.startsWith("@") && userId.contains(":")) {
            android.util.Log.d("Andromuks", "AppViewModel: Requesting profile for Matrix user: $userId")
            requestUserProfile(userId)
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: User ID '$userId' is not a Matrix ID format, not requesting profile")
        }
        
        return null
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
                    val displayName = content?.optString("displayname")?.takeIf { it.isNotBlank() }
                    val avatarUrl = content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                    
                    if (userId != null) {
                        memberMap[userId] = MemberProfile(displayName, avatarUrl)
                        //android.util.Log.d("Andromuks", "AppViewModel: Cached member '$userId' in room '$roomId' -> displayName: '$displayName'")
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
        // First, populate member cache from sync data
        populateMemberCacheFromSync(syncJson)
        
        // Process account_data for recent emojis
        processAccountData(syncJson)
        
        // Auto-save state periodically (every 10 sync_complete messages)
        // Only save if keepWebSocketOpened is disabled (otherwise service maintains connection)
        if (!keepWebSocketOpened && syncMessageCount > 0 && syncMessageCount % 10 == 0) {
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
                // Update animation state for this room
                updateRoomAnimationState(room.id, isAnimating = true)
                android.util.Log.d("Andromuks", "AppViewModel: Updated room: ${updatedRoom.name} (unread: ${updatedRoom.unreadCount}, message: ${updatedRoom.messagePreview?.take(20)}...)")
            } else {
                roomMap[room.id] = room
                // Update animation state for new room
                updateRoomAnimationState(room.id, isAnimating = true)
                android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (unread: ${room.unreadCount})")
            }
        }
        
        // Add new rooms
        syncResult.newRooms.forEach { room ->
            roomMap[room.id] = room
            // Update animation state for new room
            updateRoomAnimationState(room.id, isAnimating = true)
            android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name}")
        }
        
        // Remove left rooms
        syncResult.removedRoomIds.forEach { roomId ->
            val removedRoom = roomMap.remove(roomId)
            if (removedRoom != null) {
                // Remove animation state for removed room
                roomAnimationStates = roomAnimationStates - roomId
                android.util.Log.d("Andromuks", "AppViewModel: Removed room: ${removedRoom.name}")
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${roomMap.size} (updated: ${syncResult.updatedRooms.size}, new: ${syncResult.newRooms.size}, removed: ${syncResult.removedRoomIds.size}) - sync message #$syncMessageCount")
        
        // Process room invitations first
        processRoomInvites(syncJson)
        
        // Trigger timestamp update on sync
        triggerTimestampUpdate()
        
        // Update the UI with the current room list
        val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
        android.util.Log.d("Andromuks", "AppViewModel: Updating spaceList with ${sortedRooms.size} rooms")
        
        // Update animation states with new positions
        sortedRooms.forEachIndexed { index, room ->
            updateRoomAnimationState(room.id, isAnimating = false, newPosition = index)
        }
        
        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms)))
        allRooms = sortedRooms // Update allRooms for filtering
        updateCounter++ // Force recomposition
        android.util.Log.d("Andromuks", "AppViewModel: spaceList updated, current size: ${spaceList.size}")
        
        // Update conversation shortcuts
        conversationsApi?.updateConversationShortcuts(sortedRooms)
        
        // Check if current room needs timeline update
        checkAndUpdateCurrentRoomTimeline(syncJson)
        
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
        if (onNavigateToRoomList != null) {
            onNavigateToRoomList?.invoke()
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
            pendingNavigation = true
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
    
    // Pending room navigation from shortcuts
    private var pendingRoomNavigation: String? = null
    
    // Pending bubble navigation from chat bubbles
    private var pendingBubbleNavigation: String? = null
    
    // Websocket restart callback
    var onRestartWebSocket: (() -> Unit)? = null
    
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
            callback()
        }
    }
    
    fun setPendingRoomNavigation(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Set pending room navigation to: $roomId")
        pendingRoomNavigation = roomId
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
        
        // Cancel notification action shutdown timer
        notificationActionShutdownTimer?.cancel()
        notificationActionShutdownTimer = null
        notificationActionInProgress = false
        
        // If keepWebSocketOpened is enabled, the service should be maintaining the connection
        if (keepWebSocketOpened) {
            android.util.Log.d("Andromuks", "AppViewModel: Keep WebSocket opened is enabled, service should maintain connection")
            return
        }
        
        // If WebSocket is not connected, restart it
        if (webSocket == null) {
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket not connected, restarting...")
            onRestartWebSocket?.invoke()
        }
    }
    
    /**
     * Called when app becomes invisible (background/standby)
     */
    fun onAppBecameInvisible() {
        android.util.Log.d("Andromuks", "AppViewModel: App became invisible")
        isAppVisible = false
        
        // If keepWebSocketOpened is enabled, don't save state or start shutdown timer
        // The foreground service maintains the connection, so no need to persist state
        if (keepWebSocketOpened) {
            android.util.Log.d("Andromuks", "AppViewModel: Keep WebSocket opened is enabled, not saving state or starting shutdown timer")
            android.util.Log.d("Andromuks", "AppViewModel: keepWebSocketOpened value: $keepWebSocketOpened")
            return
        }
        
        // Save state to storage when app goes to background (only if WebSocket will be closed)
        appContext?.let { context ->
            saveStateToStorage(context)
        }
        
        // Cancel any existing shutdown job
        appInvisibleJob?.cancel()
        
        // Start delayed shutdown (15 seconds) - reduced from 30 seconds for better UX
        appInvisibleJob = viewModelScope.launch {
            android.util.Log.d("Andromuks", "AppViewModel: Starting 15s shutdown timer")
            delay(15_000) // 15 seconds delay (changed from 30 seconds)
            
            // Check if app is still invisible after delay
            if (!isAppVisible) {
                android.util.Log.d("Andromuks", "AppViewModel: App still invisible after 15s, shutting down WebSocket")
                android.util.Log.d("Andromuks", "AppViewModel: Shutdown timer - keepWebSocketOpened: $keepWebSocketOpened")
                shutdownWebSocket()
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: App became visible again, canceling shutdown")
            }
        }
    }
    
    /**
     * Manually triggers app suspension (for back button from room list).
     * 
     * This function is called when the user presses the back button from the room list screen.
     * It starts the 15-second timer to close the websocket, allowing the app to suspend
     * gracefully while preserving resources.
     */
    fun suspendApp() {
        // Don't suspend if keepWebSocketOpened is enabled
        if (keepWebSocketOpened) {
            android.util.Log.d("Andromuks", "AppViewModel: Keep WebSocket opened is enabled, not suspending app")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: App manually suspended, starting 15-second timer to close websocket")
        onAppBecameInvisible() // This will start the 15-second timer
    }
    
    /**
     * Shuts down the WebSocket connection
     */
    private fun shutdownWebSocket() {
        android.util.Log.d("Andromuks", "AppViewModel: Shutting down WebSocket connection")
        
        // Don't shutdown if keepWebSocketOpened is enabled
        if (keepWebSocketOpened) {
            android.util.Log.d("Andromuks", "AppViewModel: Keep WebSocket opened is enabled, not shutting down")
            return
        }
        
        clearWebSocket()
    }
    
    override fun onCleared() {
        super.onCleared()
        android.util.Log.d("Andromuks", "AppViewModel: onCleared - cleaning up resources")
        
        // Cancel any pending jobs
        appInvisibleJob?.cancel()
        
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
    
    // Edit chain tracking system
    data class EventChainEntry(
        val eventId: String,
        var ourBubble: TimelineEvent?,
        var replacedBy: String?,
        val originalTimestamp: Long
    )
    
    private val eventChainMap = mutableMapOf<String, EventChainEntry>()
    private val editEventsMap = mutableMapOf<String, TimelineEvent>() // Store edit events separately
    
    private var requestIdCounter = 1
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
    private val eventRequests = mutableMapOf<Int, (TimelineEvent?) -> Unit>() // requestId -> callback
    private val paginateRequests = mutableMapOf<Int, String>() // requestId -> roomId (for pagination)
    private val roomStateWithMembersRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.RoomStateInfo?, String?) -> Unit>() // requestId -> callback
    private val userEncryptionInfoRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    private val mutualRoomsRequests = mutableMapOf<Int, (List<String>?, String?) -> Unit>() // requestId -> callback
    private val trackDevicesRequests = mutableMapOf<Int, (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit>() // requestId -> callback
    
    // Pagination state
    private var smallestRowId: Long = -1L // Smallest rowId from initial paginate
    private var isPaginating: Boolean = false // Prevent multiple pagination requests
    private var hasMoreMessages: Boolean = true // Whether there are more messages to load
    
    
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var lastReceivedRequestId: Int = 0 // Tracks ANY incoming request_id (for pong detection)
    private var lastReceivedSyncId: Int = 0 // Tracks ONLY sync_complete negative request_ids (for reconnection)
    private var lastPingRequestId: Int = 0
    private var pongTimeoutJob: Job? = null
    private var currentRunId: String = "" // Unique connection ID from gomuks backend
    private var vapidKey: String = "" // VAPID key for push notifications

    fun setWebSocket(webSocket: WebSocket) {
        this.webSocket = webSocket
        startPingLoop()
    }

    fun clearWebSocket() {
        this.webSocket = null
        pingJob?.cancel()
        pingJob = null
        pongTimeoutJob?.cancel()
        pongTimeoutJob = null
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
     * Note: This is only used when keepWebSocketOpened is disabled. When the WebSocket
     * is kept open via foreground service, state persistence is not needed.
     */
    fun saveStateToStorage(context: android.content.Context) {
        // Skip saving if keepWebSocketOpened is enabled (service maintains connection)
        if (keepWebSocketOpened) {
            android.util.Log.d("Andromuks", "AppViewModel: Skipping state save - WebSocket kept open by service")
            return
        }
        
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
            
            android.util.Log.d("Andromuks", "AppViewModel: Cleared cached state")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to clear cached state", e)
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        val ws = webSocket ?: return
        pingJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                val currentWs = webSocket
                if (currentWs == null) {
                    // Socket gone; stop loop
                    break
                }
                val reqId = requestIdCounter++
                lastPingRequestId = reqId
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
            restartWebSocket()
        }
    }
    
    private fun restartWebSocket() {
        android.util.Log.d("Andromuks", "AppViewModel: Restarting websocket connection")
        clearWebSocket()
        // Trigger websocket restart via callback
        onRestartWebSocket?.invoke()
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
        timelineEvents = emptyList()
        isTimelineLoading = true
        
        // Reset pagination state for new room
        smallestRowId = -1L
        isPaginating = false
        hasMoreMessages = true
        
        // Clear edit chain mapping when opening a new room
        eventChainMap.clear()
        editEventsMap.clear()
        
        // Clear message reactions when switching rooms
        messageReactions = emptyMap()
        
        // Ensure member cache exists for this room
        if (roomMemberCache[roomId] == null) {
            roomMemberCache[roomId] = mutableMapOf()
        }
        
        // Send get_room_state command with include_members = true
        val stateRequestId = requestIdCounter++
        timelineRequests[stateRequestId] = roomId
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to true,
            "fetch_members" to false,
            "refetch" to false
        ))
        
        // Send paginate command
        val paginateRequestId = requestIdCounter++
        timelineRequests[paginateRequestId] = roomId
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 50,
            "reset" to false
        ))
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
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, attempting to reconnect")
            restartWebSocketConnection()
            
            // Wait a bit for reconnection and then retry
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000) // Wait 2 seconds for reconnection
                if (webSocket != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: WebSocket reconnected, retrying requestRoomStateWithMembers")
                    requestRoomStateWithMembers(roomId, callback)
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: WebSocket reconnection failed, cannot request room state")
                    callback(null, "WebSocket not connected")
                }
            }
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
    
    fun sendTyping(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Sending typing indicator for room: $roomId")
        val typingRequestId = requestIdCounter++
        sendWebSocketCommand("set_typing", typingRequestId, mapOf(
            "room_id" to roomId,
            "timeout" to 10000
        ))
    }
    
    fun sendMessage(roomId: String, text: String) {
        android.util.Log.d("Andromuks", "AppViewModel: sendMessage called with roomId: '$roomId', text: '$text'")
        
        // Check if WebSocket is connected, if not, try to reconnect
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, attempting to reconnect")
            restartWebSocketConnection()
            
            // Wait a bit for reconnection and then retry
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000) // Wait 2 seconds for reconnection
                if (webSocket != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: WebSocket reconnected, retrying message send")
                    sendMessageInternal(roomId, text)
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: WebSocket reconnection failed, cannot send message")
                }
            }
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket is connected, proceeding with sendMessageInternal")
        sendMessageInternal(roomId, text)
    }
    
    private fun sendMessageInternal(roomId: String, text: String) {
        android.util.Log.d("Andromuks", "AppViewModel: sendMessageInternal called")
        val messageRequestId = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request_id: $messageRequestId")
        
        messageRequests[messageRequestId] = roomId
        android.util.Log.d("Andromuks", "AppViewModel: Stored request in messageRequests map")
        
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $messageRequestId")
    }
    
    /**
     * Sends a message from a notification action.
     * This handles websocket connection state and schedules auto-shutdown if needed.
     */
    fun sendMessageFromNotification(roomId: String, text: String, onComplete: (() -> Unit)? = null) {
        android.util.Log.d("Andromuks", "AppViewModel: sendMessageFromNotification called for room $roomId")
        
        // Check websocket state
        if (webSocket == null || !spacesLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket not ready, queueing notification action")
            
            // Queue the action
            pendingNotificationActions.add(
                PendingNotificationAction(
                    type = "send_message",
                    roomId = roomId,
                    text = text,
                    onComplete = onComplete
                )
            )
            
            // If app is not visible, reconnect websocket
            if (!isAppVisible) {
                android.util.Log.d("Andromuks", "AppViewModel: App not visible, reconnecting websocket for notification action")
                notificationActionInProgress = true
                restartWebSocketConnection()
            }
            
            return
        }
        
        // WebSocket is ready, send message
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket ready, sending message from notification")
        val messageRequestId = requestIdCounter++
        
        messageRequests[messageRequestId] = roomId
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
        
        // Schedule shutdown if app is not visible
        if (!isAppVisible) {
            scheduleNotificationActionShutdown()
        }
    }
    
    /**
     * Marks a room as read from a notification action.
     * This handles websocket connection state and schedules auto-shutdown if needed.
     */
    fun markRoomAsReadFromNotification(roomId: String, eventId: String, onComplete: (() -> Unit)? = null) {
        android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadFromNotification called for room $roomId")
        
        // Check websocket state
        if (webSocket == null || !spacesLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket not ready, queueing notification action")
            
            // Queue the action
            pendingNotificationActions.add(
                PendingNotificationAction(
                    type = "mark_read",
                    roomId = roomId,
                    eventId = eventId,
                    onComplete = onComplete
                )
            )
            
            // If app is not visible, reconnect websocket
            if (!isAppVisible) {
                android.util.Log.d("Andromuks", "AppViewModel: App not visible, reconnecting websocket for notification action")
                notificationActionInProgress = true
                restartWebSocketConnection()
            }
            
            return
        }
        
        // WebSocket is ready, mark as read
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket ready, marking room as read from notification")
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
        
        // Schedule shutdown if app is not visible
        if (!isAppVisible) {
            scheduleNotificationActionShutdown()
        }
    }
    
    /**
     * Schedules websocket shutdown after 15 seconds for notification actions
     */
    private fun scheduleNotificationActionShutdown() {
        // Cancel any existing timer
        notificationActionShutdownTimer?.cancel()
        
        android.util.Log.d("Andromuks", "AppViewModel: Scheduling notification action shutdown in 15 seconds")
        
        notificationActionShutdownTimer = viewModelScope.launch {
            delay(15_000) // 15 seconds
            
            // Only shutdown if app is still not visible
            if (!isAppVisible && notificationActionInProgress) {
                android.util.Log.d("Andromuks", "AppViewModel: Shutting down websocket after notification action")
                notificationActionInProgress = false
                shutdownWebSocket()
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: Not shutting down - app became visible or action completed")
            }
        }
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
        
        // Check if WebSocket is connected, if not, try to reconnect
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, attempting to reconnect")
            restartWebSocketConnection()
            
            // Wait a bit for reconnection and then retry
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000) // Wait 2 seconds for reconnection
                if (webSocket != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: WebSocket reconnected, retrying reply send")
                    sendReplyInternal(roomId, text, originalEvent)
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: WebSocket reconnection failed, cannot send reply")
                }
            }
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket is connected, proceeding with sendReplyInternal")
        sendReplyInternal(roomId, text, originalEvent)
    }
    
    private fun sendReplyInternal(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent) {
        android.util.Log.d("Andromuks", "AppViewModel: sendReplyInternal called")
        val messageRequestId = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request_id: $messageRequestId")
        
        messageRequests[messageRequestId] = roomId
        android.util.Log.d("Andromuks", "AppViewModel: Stored request in messageRequests map")
        
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
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with data: $commandData")
        sendWebSocketCommand("send_message", messageRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $messageRequestId")
    }
    
    fun sendEdit(roomId: String, text: String, originalEvent: net.vrkknn.andromuks.TimelineEvent) {
        android.util.Log.d("Andromuks", "AppViewModel: sendEdit called with roomId: '$roomId', text: '$text', originalEvent: ${originalEvent.eventId}")
        
        val ws = webSocket ?: return
        val editRequestId = requestIdCounter++
        
        // Track this outgoing request
        messageRequests[editRequestId] = roomId
        
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
        android.util.Log.d("Andromuks", "AppViewModel: Profile updated for $userId display=$display avatar=${avatar != null}")
        
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
            
            // Parse the response as a timeline event
            try {
                val obj = data as? JSONObject ?: return
                android.util.Log.d("Andromuks", "AppViewModel: Response data: ${obj.toString()}")
                val event = TimelineEvent.fromJson(obj)
                android.util.Log.d("Andromuks", "AppViewModel: Created timeline event: ${event.eventId}, eventRoomId=${event.roomId}")
                
                // Add the event to the timeline if it's for the current room
                if (event.roomId == currentRoomId) {
                    val currentEvents = timelineEvents.toMutableList()
                    currentEvents.add(event)
                    timelineEvents = currentEvents.sortedBy { it.timestamp }
                    android.util.Log.d("Andromuks", "AppViewModel: Added outgoing event to timeline, total events: ${timelineEvents.size}")
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: Event roomId (${event.roomId}) doesn't match currentRoomId ($currentRoomId), not adding to timeline")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Error parsing outgoing request response", e)
            }
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: No roomId found for outgoing request $requestId")
        }
    }
    
    fun processSendCompleteEvent(eventData: JSONObject) {
        android.util.Log.d("Andromuks", "AppViewModel: processSendCompleteEvent called")
        try {
            val event = TimelineEvent.fromJson(eventData)
            android.util.Log.d("Andromuks", "AppViewModel: Created timeline event from send_complete: ${event.eventId}, type=${event.type}, eventRoomId=${event.roomId}, currentRoomId=$currentRoomId")
            
            if (event.type == "m.reaction") {
                // Process reaction events to update messageReactions instead of adding to timeline
                val relatesTo = event.content?.optJSONObject("m.relates_to")
                val emoji = relatesTo?.optString("key", "") ?: ""
                val relatesToEventId = relatesTo?.optString("event_id", "") ?: ""
                
                if (emoji.isNotBlank() && relatesToEventId.isNotBlank()) {
                    val reactionEvent = ReactionEvent(
                        eventId = event.eventId,
                        sender = event.sender,
                        emoji = emoji,
                        relatesToEventId = relatesToEventId,
                        timestamp = event.timestamp
                    )
                    processReactionEvent(reactionEvent)
                    android.util.Log.d("Andromuks", "AppViewModel: Processed send_complete reaction: $emoji from ${event.sender} to $relatesToEventId")
                }
            } else {
                // Use addTimelineEvent for non-reaction events
                addTimelineEvent(event)
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
        val roomId = timelineRequests[requestId] ?: paginateRequests[requestId]
        if (roomId == null) {
            android.util.Log.w("Andromuks", "AppViewModel: Received response for unknown request ID: $requestId")
            return
        }

        android.util.Log.d("Andromuks", "AppViewModel: Handling timeline response for room: $roomId, requestId: $requestId, data type: ${data::class.java.simpleName}")

        fun processEventsArray(eventsArray: JSONArray) {
            val timelineList = mutableListOf<TimelineEvent>()
            val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
            for (i in 0 until eventsArray.length()) {
                val eventJson = eventsArray.optJSONObject(i)
                if (eventJson != null) {
                    val event = TimelineEvent.fromJson(eventJson)
                    if (event.type == "m.room.member" && event.timelineRowid == -1L) {
                        // State member event; update cache only
                        val userId = event.stateKey ?: event.sender
                        val displayName = event.content?.optString("displayname")?.takeIf { it.isNotBlank() }
                        val avatarUrl = event.content?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                        memberMap[userId] = MemberProfile(displayName, avatarUrl)
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
                                        val reactionEvent = ReactionEvent(
                                            eventId = event.eventId,
                                            sender = event.sender,
                                            emoji = emoji,
                                            relatesToEventId = relatesToEventId,
                                            timestamp = event.timestamp
                                        )
                                        processReactionEvent(reactionEvent)
                                        android.util.Log.d("Andromuks", "AppViewModel: Processed historical reaction: $emoji from ${event.sender} to $relatesToEventId")
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
            android.util.Log.d("Andromuks", "AppViewModel: Processed events - timeline=${timelineList.size}, members=${memberMap.size}")
            
            // Handle empty pagination responses
            if (paginateRequests.containsKey(requestId) && timelineList.isEmpty()) {
                android.util.Log.d("Andromuks", "AppViewModel: Empty pagination response - no more messages to load")
                paginateRequests.remove(requestId)
                isPaginating = false
                return
            }
            
            if (timelineList.isNotEmpty()) {
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
                }
                
                // Mark room as read when timeline is successfully loaded - use most recent event by timestamp
                val mostRecentEvent = timelineList.maxByOrNull { it.timestamp }
                if (mostRecentEvent != null) {
                    markRoomAsRead(roomId, mostRecentEvent.eventId)
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
                
                // Parse has_more field for pagination
                if (paginateRequests.containsKey(requestId)) {
                    val hasMore = data.optBoolean("has_more", true) // Default to true if not present
                    hasMoreMessages = hasMore
                    android.util.Log.d("Andromuks", "AppViewModel: Parsed has_more: $hasMore from pagination response")
                    android.util.Log.d("Andromuks", "AppViewModel: Full pagination response data keys: ${data.keys().asSequence().toList()}")
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
                }
            }
        }
        
        // Create room state object
        val roomState = RoomState(
            roomId = roomId,
            name = name,
            canonicalAlias = canonicalAlias,
            topic = topic,
            avatarUrl = avatarUrl
        )
        
        // Update current room state
        currentRoomState = roomState
        updateCounter++
        
        android.util.Log.d("Andromuks", "AppViewModel: Parsed room state - Name: $name, Alias: $canonicalAlias, Topic: $topic, Avatar: $avatarUrl")
    }
    
    private fun handleMessageResponse(requestId: Int, data: Any) {
        val roomId = messageRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Handling message response for room: $roomId")
        
        when (data) {
            is JSONObject -> {
                // Create TimelineEvent from the response
                val event = TimelineEvent.fromJson(data)
                if (event.type == "m.room.message") {
                    // Add the sent message to timeline using addTimelineEvent (which checks room ID)
                    addTimelineEvent(event)
                    android.util.Log.d("Andromuks", "AppViewModel: Added sent message to timeline: ${event.content?.optString("body")}")
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleMessageResponse: ${data::class.java.simpleName}")
            }
        }
        
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
        val callback = eventRequests.remove(requestId) ?: return
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
    
    private fun checkAndUpdateCurrentRoomTimeline(syncJson: JSONObject) {
        val data = syncJson.optJSONObject("data")
        if (data != null && currentRoomId != null) {
            val rooms = data.optJSONObject("rooms")
            if (rooms != null && rooms.has(currentRoomId)) {
                android.util.Log.d("Andromuks", "AppViewModel: Received sync_complete for current room: $currentRoomId")
                updateTimelineFromSync(syncJson, currentRoomId!!)
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
                                avatarUrl = avatarUrl
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
            }
        }
        
        // Sort events by timestamp to process in order
        events.sortBy { it.timestamp }
        android.util.Log.d("Andromuks", "AppViewModel: Processing ${events.size} events in timestamp order")
        
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
                        memberMap[userId] = MemberProfile(displayName, avatarUrl)
                        android.util.Log.d("Andromuks", "AppViewModel: Updated member cache for $userId: $displayName")
                    }
                }
            } else if (event.type == "m.room.redaction") {
                // Handle redaction events
                android.util.Log.d("Andromuks", "AppViewModel: Processing redaction event ${event.eventId} from ${event.sender}")
                
                // Add redaction event to timeline so findLatestRedactionEvent can find it
                addNewEventToChain(event)
                
                // Request sender profile if missing from cache
                if (!memberMap.containsKey(event.sender)) {
                    android.util.Log.d("Andromuks", "AppViewModel: Requesting profile for redaction sender: ${event.sender} in room $roomId")
                    requestUserProfile(event.sender, roomId)
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: Redaction sender ${event.sender} already in cache: ${memberMap[event.sender]?.displayName}")
                }
                
                // Trigger UI update
                updateCounter++
            } else if (event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker") {
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
                    // Process reaction events first (don't add to timeline)
                    if (event.type == "m.reaction") {
                        val content = event.content
                        if (content != null) {
                            val relatesTo = content.optJSONObject("m.relates_to")
                            if (relatesTo != null) {
                                val relatesToEventId = relatesTo.optString("event_id")
                                val emoji = relatesTo.optString("key")
                                val relType = relatesTo.optString("rel_type")
                                
                                if (relatesToEventId.isNotBlank() && emoji.isNotBlank() && relType == "m.annotation") {
                                    val reactionEvent = ReactionEvent(
                                        eventId = event.eventId,
                                        sender = event.sender,
                                        emoji = emoji,
                                        relatesToEventId = relatesToEventId,
                                        timestamp = event.timestamp
                                    )
                                    processReactionEvent(reactionEvent)
                                    android.util.Log.d("Andromuks", "AppViewModel: Processed reaction: $emoji from ${event.sender} to $relatesToEventId")
                                }
                            }
                        }
                    } else {
                        // Add new timeline event to chain
                        addNewEventToChain(event)
                    }
                }
            }
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
            android.util.Log.d("Andromuks", "AppViewModel: Processing relationships for ${newEditEvents.size} new edit events")
            processNewEditRelationships(newEditEvents)
        }
        
        // Build timeline from chain
        buildTimelineFromChain()
        
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
     */
    private fun addNewEventToChain(event: TimelineEvent) {
        android.util.Log.d("Andromuks", "AppViewModel: addNewEventToChain called for ${event.eventId}")
        
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
                android.util.Log.d("Andromuks", "AppViewModel: Processing event ${eventId} with replacedBy: ${entry.replacedBy}")
                val finalEvent = getFinalEventForBubble(entry)
                timelineEvents.add(finalEvent)
                android.util.Log.d("Andromuks", "AppViewModel: Added bubble for ${eventId} with final content from ${entry.replacedBy ?: eventId}")
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
        
        // Add new events to existing timeline
        val currentEvents = timelineEvents.toMutableList()
        currentEvents.addAll(newEvents)
        
        android.util.Log.d("Andromuks", "AppViewModel: Combined timeline has ${currentEvents.size} events before sorting")
        
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
        
        // Check if WebSocket is connected, if not, try to reconnect
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, attempting to reconnect")
            restartWebSocketConnection()
            
            // Wait a bit for reconnection and then retry
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000) // Wait 2 seconds for reconnection
                if (webSocket != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: WebSocket reconnected, retrying mark read")
                    markRoomAsReadInternal(roomId, eventId)
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: WebSocket reconnection failed, cannot mark room as read")
                }
            }
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket is connected, proceeding with markRoomAsReadInternal")
        markRoomAsReadInternal(roomId, eventId)
    }
    
    private fun markRoomAsReadInternal(roomId: String, eventId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadInternal called")
        val markReadRequestId = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request_id: $markReadRequestId")
        
        markReadRequests[markReadRequestId] = roomId
        android.util.Log.d("Andromuks", "AppViewModel: Stored request in markReadRequests map")
        
        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        )
        
        android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: mark_read with data: $commandData")
        sendWebSocketCommand("mark_read", markReadRequestId, commandData)
        android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $markReadRequestId")
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
    
    private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>) {
        val ws = webSocket
        if (ws == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket is not connected, cannot send command: $command")
            return
        }
        val json = org.json.JSONObject()
        json.put("command", command)
        json.put("request_id", requestId)
        json.put("data", org.json.JSONObject(data))
        val jsonString = json.toString()
        android.util.Log.d("Andromuks", "AppViewModel: Sending command: $jsonString")
        ws.send(jsonString)
    }
    
    /**
     * Send get_event command to retrieve full event details from server
     * Useful when we only have partial event information (e.g., for reply previews)
     */
    fun getEvent(roomId: String, eventId: String, callback: (TimelineEvent?) -> Unit) {
        android.util.Log.d("Andromuks", "AppViewModel: getEvent called for roomId: '$roomId', eventId: '$eventId'")
        
        // Check if WebSocket is connected
        if (webSocket == null) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected, attempting to reconnect")
            restartWebSocketConnection()
            
            // Wait a bit for reconnection and then retry
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000) // Wait 2 seconds for reconnection
                if (webSocket != null) {
                    android.util.Log.d("Andromuks", "AppViewModel: WebSocket reconnected, retrying getEvent")
                    getEvent(roomId, eventId, callback)
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: WebSocket reconnection failed, cannot get event")
                    callback(null)
                }
            }
            return
        }
        
        val eventRequestId = requestIdCounter++
        android.util.Log.d("Andromuks", "AppViewModel: Generated request_id for get_event: $eventRequestId")
        
        // Store the callback to handle the response
        eventRequests[eventRequestId] = callback
        
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

    fun enableKeepWebSocketOpened(enabled: Boolean) {
        keepWebSocketOpened = enabled
        android.util.Log.d("Andromuks", "AppViewModel: Keep WebSocket opened setting changed to: $keepWebSocketOpened")
    }
    
    fun toggleKeepWebSocketOpened() {
        keepWebSocketOpened = !keepWebSocketOpened
        android.util.Log.d("Andromuks", "AppViewModel: Keep WebSocket opened setting changed to: $keepWebSocketOpened")
        
        if (keepWebSocketOpened) {
            // Start the WebSocket service to maintain connection
            startWebSocketService()
        } else {
            // Stop the WebSocket service
            stopWebSocketService()
        }
    }

    private fun startWebSocketService() {
        appContext?.let { context ->
            val intent = android.content.Intent(context, WebSocketService::class.java)
            context.startForegroundService(intent)
            
            // Bind to the service to pass connection parameters
            context.bindService(intent, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    val webSocketService = (service as WebSocketService.WebSocketBinder).getService()
                    webSocketService.setConnectionParameters(
                        realMatrixHomeserverUrl,
                        authToken,
                        this@AppViewModel
                    )
                    android.util.Log.d("Andromuks", "AppViewModel: WebSocket service connected and parameters set")
                }
                
                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    android.util.Log.d("Andromuks", "AppViewModel: WebSocket service disconnected")
                }
            }, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopWebSocketService() {
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
}
