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

    fun showLoading() {
        isLoading = true
    }

    fun hideLoading() {
        isLoading = false
    }

    fun updateHomeserverUrl(url: String) {
        homeserverUrl = url
    }

    fun handleClientState(userId: String?, device: String?, homeserver: String?) {
        if (!userId.isNullOrBlank()) currentUserId = userId
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
                        android.util.Log.d("Andromuks", "AppViewModel: Cached member '$userId' in room '$roomId' -> displayName: '$displayName'")
                    }
                }
            }
        }
    }

    fun updateRoomsFromSyncJson(syncJson: JSONObject) {
        // First, populate member cache from sync data
        populateMemberCacheFromSync(syncJson)
        
        val syncResult = SpaceRoomParser.parseSyncUpdate(syncJson, roomMemberCache)
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
        android.util.Log.d("Andromuks", "AppViewModel: spaceList updated, current size: ${spaceList.size}")
        
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
        android.util.Log.d("Andromuks", "AppViewModel: Calling navigation callback (callback is ${if (onNavigateToRoomList != null) "set" else "null"})")
        if (onNavigateToRoomList != null) {
            onNavigateToRoomList?.invoke()
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Navigation callback not set yet, marking as pending")
            pendingNavigation = true
        }
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

    fun handleResponse(requestId: Int, data: Any) {
        if (profileRequests.containsKey(requestId)) {
            handleProfileResponse(requestId, data)
        } else if (timelineRequests.containsKey(requestId)) {
            handleTimelineResponse(requestId, data)
        } else {
            android.util.Log.d("Andromuks", "AppViewModel: Unknown response requestId=$requestId")
        }
    }
    
    private fun handleProfileResponse(requestId: Int, data: Any) {
        val userId = profileRequests.remove(requestId) ?: return
        val obj = data as? JSONObject ?: return
        val avatar = obj.optString("avatar_url")?.takeIf { it.isNotBlank() }
        val display = obj.optString("displayname")?.takeIf { it.isNotBlank() }
        if (userId == currentUserId) {
            currentUserProfile = UserProfile(userId = userId, displayName = display, avatarUrl = avatar)
        }
        android.util.Log.d("Andromuks", "AppViewModel: Profile updated for $userId display=$display avatar=${avatar != null}")
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
