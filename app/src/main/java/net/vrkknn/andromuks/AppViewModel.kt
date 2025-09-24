package net.vrkknn.andromuks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.vrkknn.andromuks.SpaceItem
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.SpaceRoomParser
import org.json.JSONObject
import okhttp3.WebSocket
import org.json.JSONArray
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    var isLoading by mutableStateOf(false)
    var homeserverUrl by mutableStateOf("")
        private set
    var authToken by mutableStateOf("")
        private set

    // Auth/client state
    var currentUserId by mutableStateOf("")
        private set
    var deviceId by mutableStateOf("")
        private set
    var imageAuthToken by mutableStateOf("")
        private set
    var currentUserProfile by mutableStateOf<UserProfile?>(null)
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
    
    // Force recomposition counter
    var updateCounter by mutableStateOf(0)
        private set

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
    
    
    // Get current room section based on selected tab
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
                val unreadDmCount = dmRooms.count { it.unreadCount != null && it.unreadCount > 0 }
                RoomSection(
                    type = RoomSectionType.DIRECT_CHATS,
                    rooms = dmRooms,
                    unreadCount = unreadDmCount
                )
            }
            RoomSectionType.UNREAD -> {
                val unreadRooms = roomsToUse.filter { it.unreadCount != null && it.unreadCount > 0 }
                RoomSection(
                    type = RoomSectionType.UNREAD,
                    rooms = unreadRooms
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
    
    fun updateTypingUsers(roomId: String, userIds: List<String>) {
        // Only update if this is the current room
        if (currentRoomId == roomId) {
            typingUsers = userIds
        }
    }
    
    fun processReactionEvent(reactionEvent: ReactionEvent) {
        // Only process reactions for the current room
        if (currentRoomId != null) {
            val currentReactions = messageReactions.toMutableMap()
            val eventReactions = currentReactions[reactionEvent.relatesToEventId]?.toMutableList() ?: mutableListOf()
            
            // Find existing reaction with same emoji
            val existingReactionIndex = eventReactions.indexOfFirst { it.emoji == reactionEvent.emoji }
            
            if (existingReactionIndex >= 0) {
                // Update existing reaction
                val existingReaction = eventReactions[existingReactionIndex]
                val updatedUsers = existingReaction.users.toMutableList()
                
                if (reactionEvent.sender in updatedUsers) {
                    // Remove user from reaction
                    updatedUsers.remove(reactionEvent.sender)
                    if (updatedUsers.isEmpty()) {
                        eventReactions.removeAt(existingReactionIndex)
                    } else {
                        eventReactions[existingReactionIndex] = existingReaction.copy(
                            count = updatedUsers.size,
                            users = updatedUsers
                        )
                    }
                } else {
                    // Add user to reaction
                    updatedUsers.add(reactionEvent.sender)
                    eventReactions[existingReactionIndex] = existingReaction.copy(
                        count = updatedUsers.size,
                        users = updatedUsers
                    )
                }
            } else {
                // Add new reaction
                eventReactions.add(MessageReaction(
                    emoji = reactionEvent.emoji,
                    count = 1,
                    users = listOf(reactionEvent.sender)
                ))
            }
            
            currentReactions[reactionEvent.relatesToEventId] = eventReactions
            messageReactions = currentReactions
        }
    }

    fun handleClientState(userId: String?, device: String?, homeserver: String?) {
        if (!userId.isNullOrBlank()) {
            currentUserId = userId
            android.util.Log.d("Andromuks", "AppViewModel: Set currentUserId: $userId")
        }
        if (!device.isNullOrBlank()) deviceId = device
        // IMPORTANT: Do NOT override gomuks backend URL with Matrix homeserver URL from client_state
        // The backend URL is set via AuthCheck from SharedPreferences (e.g., https://webmuks.aguiarvieira.pt)
        // if (!homeserver.isNullOrBlank()) updateHomeserverUrl(homeserver)
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

    fun updateRoomsFromSyncJson(syncJson: JSONObject) {
        // First, populate member cache from sync data
        populateMemberCacheFromSync(syncJson)
        
        val syncResult = SpaceRoomParser.parseSyncUpdate(syncJson, roomMemberCache, this)
        syncMessageCount++
        
        // Update existing rooms
        syncResult.updatedRooms.forEach { room ->
            val existingRoom = roomMap[room.id]
            if (existingRoom != null) {
                // Preserve existing message preview if new room data doesn't have one
                val updatedRoom = if (room.messagePreview.isNullOrBlank() && !existingRoom.messagePreview.isNullOrBlank()) {
                    room.copy(messagePreview = existingRoom.messagePreview)
                } else {
                    room
                }
                roomMap[room.id] = updatedRoom
                android.util.Log.d("Andromuks", "AppViewModel: Updated room: ${updatedRoom.name} (unread: ${updatedRoom.unreadCount}, message: ${updatedRoom.messagePreview?.take(20)}...)")
            } else {
                roomMap[room.id] = room
                android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (unread: ${room.unreadCount})")
            }
        }
        
        // Add new rooms
        syncResult.newRooms.forEach { room ->
            roomMap[room.id] = room
            android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name}")
        }
        
        // Remove left rooms
        syncResult.removedRoomIds.forEach { roomId ->
            val removedRoom = roomMap.remove(roomId)
            if (removedRoom != null) {
                android.util.Log.d("Andromuks", "AppViewModel: Removed room: ${removedRoom.name}")
            }
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${roomMap.size} (updated: ${syncResult.updatedRooms.size}, new: ${syncResult.newRooms.size}, removed: ${syncResult.removedRoomIds.size}) - sync message #$syncMessageCount")
        
        // Update the UI with the current room list
        val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
        android.util.Log.d("Andromuks", "AppViewModel: Updating spaceList with ${sortedRooms.size} rooms")
        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms)))
        allRooms = sortedRooms // Update allRooms for filtering
        updateCounter++ // Force recomposition
        android.util.Log.d("Andromuks", "AppViewModel: spaceList updated, current size: ${spaceList.size}")
        
        // Check if current room needs timeline update
        checkAndUpdateCurrentRoomTimeline(syncJson)
        
        // Temporary workaround: navigate after 3 sync messages if we have rooms
        if (syncMessageCount >= 3 && roomMap.isNotEmpty() && !spacesLoaded) {
            android.util.Log.d("Andromuks", "AppViewModel: Workaround - navigating after $syncMessageCount sync messages")
            spacesLoaded = true
            if (onNavigateToRoomList != null) {
                onNavigateToRoomList?.invoke()
            } else {
                android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
                pendingNavigation = true
            }
        }
    }
    
    fun onInitComplete() {
        android.util.Log.d("Andromuks", "AppViewModel: onInitComplete called - setting spacesLoaded = true")
        spacesLoaded = true
        
        // Now that all rooms are loaded, populate space edges
        populateSpaceEdges()
        
        android.util.Log.d("Andromuks", "AppViewModel: Calling navigation callback (callback is ${if (onNavigateToRoomList != null) "set" else "null"})")
        if (onNavigateToRoomList != null) {
            onNavigateToRoomList?.invoke()
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
            pendingNavigation = true
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
    
    // Websocket restart callback
    var onRestartWebSocket: (() -> Unit)? = null
    
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
    
    private var requestIdCounter = 100
    private val timelineRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val profileRequests = mutableMapOf<Int, String>() // requestId -> userId
    private val roomStateRequests = mutableMapOf<Int, String>() // requestId -> roomId
    private val messageRequests = mutableMapOf<Int, String>() // requestId -> roomId
    
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var lastReceivedRequestId: Int = 0
    private var lastPingRequestId: Int = 0
    private var pongTimeoutJob: Job? = null

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
            lastReceivedRequestId = requestId
            
            // If this is a pong response to our ping, cancel the timeout
            if (requestId == lastPingRequestId) {
                android.util.Log.d("Andromuks", "AppViewModel: Received pong for ping $requestId, canceling timeout")
                pongTimeoutJob?.cancel()
                pongTimeoutJob = null
            }
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
                val data = mapOf("last_received_id" to lastReceivedRequestId)
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

    fun requestUserProfile(userId: String) {
        val ws = webSocket ?: return
        val reqId = requestIdCounter++
        profileRequests[reqId] = userId
        val json = org.json.JSONObject()
        json.put("command", "get_profile")
        json.put("request_id", reqId)
        val data = org.json.JSONObject()
        data.put("user_id", userId)
        json.put("data", data)
        val payload = json.toString()
        android.util.Log.d("Andromuks", "AppViewModel: Sending get_profile: $payload")
        ws.send(payload)
    }
    
    fun requestRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting timeline for room: $roomId")
        currentRoomId = roomId
        timelineEvents = emptyList()
        isTimelineLoading = true
        
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
    
    fun sendTyping(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Sending typing indicator for room: $roomId")
        val typingRequestId = requestIdCounter++
        sendWebSocketCommand("set_typing", typingRequestId, mapOf(
            "room_id" to roomId,
            "timeout" to 10000
        ))
    }
    
    fun sendMessage(roomId: String, text: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Sending message to room: $roomId")
        val messageRequestId = requestIdCounter++
        messageRequests[messageRequestId] = roomId
        sendWebSocketCommand("send_message", messageRequestId, mapOf(
            "room_id" to roomId,
            "text" to text,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        ))
    }

    fun handleResponse(requestId: Int, data: Any) {
        if (profileRequests.containsKey(requestId)) {
            handleProfileResponse(requestId, data)
        } else if (timelineRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else if (roomStateRequests.containsKey(requestId)) {
            handleRoomStateResponse(requestId, data)
        } else if (messageRequests.containsKey(requestId)) {
            handleMessageResponse(requestId, data)
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Unknown response requestId=$requestId")
        }
    }
    
    fun handleError(requestId: Int, errorMessage: String) {
        if (profileRequests.containsKey(requestId)) {
            handleProfileError(requestId, errorMessage)
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Unknown error requestId=$requestId: $errorMessage")
        }
    }
    
    private fun handleProfileError(requestId: Int, errorMessage: String) {
        val userId = profileRequests.remove(requestId) ?: return
        android.util.Log.d("Andromuks", "AppViewModel: Profile not found for $userId: $errorMessage")
        
        // If profile not found, use username part of Matrix ID
        val username = userId.removePrefix("@").substringBefore(":")
        val memberProfile = MemberProfile(username, null)
        
        // Update member cache for all rooms that might contain this user
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
        val obj = data as? JSONObject ?: return
        val avatar = obj.optString("avatar_url")?.takeIf { it.isNotBlank() }
        val display = obj.optString("displayname")?.takeIf { it.isNotBlank() }
        
        // Update member cache for all rooms that might contain this user
        val memberProfile = MemberProfile(display, avatar)
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
        
        // Trigger UI update since member cache changed
        updateCounter++
    }
    
    fun handleTimelineResponse(requestId: Int, data: Any) {
        android.util.Log.d("Andromuks", "AppViewModel: handleTimelineResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}")
        val roomId = timelineRequests[requestId]
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
                        // Only render paginate timeline entries (timeline_rowid >= 0)
                        if (event.timelineRowid >= 0L) {
                            timelineList.add(event)
                        }
                    }
                }
            }
            android.util.Log.d("Andromuks", "AppViewModel: Processed events - timeline=${timelineList.size}, members=${memberMap.size}")
            if (timelineList.isNotEmpty()) {
                timelineEvents = timelineList
                isTimelineLoading = false
                android.util.Log.d("Andromuks", "AppViewModel: timelineEvents set, isTimelineLoading set to false")
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
                    // Add the sent message to timeline immediately
                    val updatedTimeline = timelineEvents + event
                    timelineEvents = updatedTimeline.sortedBy { it.timestamp }
                    updateCounter++
                    android.util.Log.d("Andromuks", "AppViewModel: Added sent message to timeline: ${event.content?.optString("body")}")
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleMessageResponse: ${data::class.java.simpleName}")
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
                }
            }
        }
    }
    
    private fun processSyncEventsArray(eventsArray: JSONArray, roomId: String) {
        val timelineList = mutableListOf<TimelineEvent>()
        val memberMap = roomMemberCache.getOrPut(roomId) { mutableMapOf() }
        
        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i)
            if (eventJson != null) {
                val event = TimelineEvent.fromJson(eventJson)
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
                } else if (event.timelineRowid >= 0) {
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
                        // Add non-reaction timeline events to timeline
                        timelineList.add(event)
                        android.util.Log.d("Andromuks", "AppViewModel: Added timeline event: ${event.type} from ${event.sender}")
                    }
                }
            }
        }
        
        if (timelineList.isNotEmpty()) {
            // Add new events to existing timeline
            val updatedTimeline = timelineEvents + timelineList
            timelineEvents = updatedTimeline.sortedBy { it.timestamp }
            updateCounter++
            android.util.Log.d("Andromuks", "AppViewModel: Added ${timelineList.size} new events to timeline, total: ${timelineEvents.size}")
        }
    }
    
    private fun markRoomAsRead(roomId: String, eventId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Marking room as read: $roomId, eventId: $eventId")
        
        val markReadRequestId = requestIdCounter++
        timelineRequests[markReadRequestId] = roomId
        sendWebSocketCommand("mark_read", markReadRequestId, mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        ))
    }
    
    fun handleMarkReadResponse(requestId: Int, success: Boolean) {
        val roomId = timelineRequests[requestId]
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Mark read response for room $roomId: $success")
            // Remove the request from pending
            timelineRequests.remove(requestId)
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
}
