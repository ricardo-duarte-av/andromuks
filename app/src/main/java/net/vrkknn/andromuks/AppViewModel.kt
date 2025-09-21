package net.vrkknn.andromuks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import net.vrkknn.andromuks.SpaceItem
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.SpaceRoomParser
import org.json.JSONObject
import okhttp3.WebSocket
import org.json.JSONArray

class AppViewModel : ViewModel() {
    var isLoading by mutableStateOf(false)
    var homeserverUrl by mutableStateOf("")
        private set

    // List of spaces, each with their rooms
    var spaceList by mutableStateOf(listOf<SpaceItem>())
        private set

    var spacesLoaded by mutableStateOf(false)
        private set

    fun setSpaces(spaces: List<SpaceItem>) {
        spaceList = spaces
    }

    fun showLoading() {
        isLoading = true
    }

    fun hideLoading() {
        isLoading = false
    }

    // Use a Map for efficient room lookups and updates
    private val roomMap = mutableMapOf<String, RoomItem>()
    private var syncMessageCount = 0
    
    fun updateRoomsFromSyncJson(syncJson: JSONObject) {
        val syncResult = SpaceRoomParser.parseSyncUpdate(syncJson)
        syncMessageCount++
        
        // Update existing rooms
        syncResult.updatedRooms.forEach { room ->
            roomMap[room.id] = room
            android.util.Log.d("Andromuks", "AppViewModel: Updated room: ${room.name} (unread: ${room.unreadCount})")
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
        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms)))
        
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
    
    fun updateHomeserverUrl(url: String) {
        homeserverUrl = url
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
    private val pendingRequests = mutableMapOf<Int, String>() // requestId -> roomId
    
    private var webSocket: WebSocket? = null

    fun setWebSocket(webSocket: WebSocket) {
        this.webSocket = webSocket
    }
    
    fun requestRoomTimeline(roomId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Requesting timeline for room: $roomId")
        currentRoomId = roomId
        timelineEvents = emptyList()
        isTimelineLoading = true
        
        // Send get_room_state command
        val stateRequestId = requestIdCounter++
        pendingRequests[stateRequestId] = roomId
        sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
            "room_id" to roomId,
            "include_members" to false,
            "fetch_members" to false,
            "refetch" to false
        ))
        
        // Send paginate command
        val paginateRequestId = requestIdCounter++
        pendingRequests[paginateRequestId] = roomId
        sendWebSocketCommand("paginate", paginateRequestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to 0,
            "limit" to 50,
            "reset" to false
        ))
    }
    
    fun handleTimelineResponse(requestId: Int, data: Any) {
        android.util.Log.d("Andromuks", "AppViewModel: handleTimelineResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}")
        val roomId = pendingRequests[requestId]
        if (roomId == null) {
            android.util.Log.w("Andromuks", "AppViewModel: Received response for unknown request ID: $requestId")
            return
        }

        android.util.Log.d("Andromuks", "AppViewModel: Handling timeline response for room: $roomId, requestId: $requestId, data type: ${data::class.java.simpleName}")

        when (data) {
            is JSONArray -> {
                val events = mutableListOf<TimelineEvent>()
                for (i in 0 until data.length()) {
                    val eventJson = data.optJSONObject(i)
                    if (eventJson != null) {
                        events.add(TimelineEvent.fromJson(eventJson))
                    }
                }
                android.util.Log.d("Andromuks", "AppViewModel: Processed JSONArray, ${events.size} events: ${events.joinToString { it.type }}")
                timelineEvents = events
                isTimelineLoading = false
                android.util.Log.d("Andromuks", "AppViewModel: timelineEvents set from JSONArray, isTimelineLoading set to false")
            }
            is JSONObject -> {
                val eventsArray = data.optJSONArray("events")
                if (eventsArray != null) {
                    val events = mutableListOf<TimelineEvent>()
                    for (i in 0 until eventsArray.length()) {
                        val eventJson = eventsArray.optJSONObject(i)
                        if (eventJson != null) {
                            events.add(TimelineEvent.fromJson(eventJson))
                        }
                    }
                    android.util.Log.d("Andromuks", "AppViewModel: Processed JSONObject.events, ${events.size} events: ${events.joinToString { it.type }}")
                    timelineEvents = events
                    isTimelineLoading = false
                    android.util.Log.d("Andromuks", "AppViewModel: timelineEvents set from JSONObject.events, isTimelineLoading set to false")
                } else {
                    android.util.Log.d("Andromuks", "AppViewModel: JSONObject did not contain 'events' array")
                }
            }
            else -> {
                android.util.Log.d("Andromuks", "AppViewModel: Unhandled data type in handleTimelineResponse: ${data::class.java.simpleName}")
            }
        }

        pendingRequests.remove(requestId)
    }
    
    private fun markRoomAsRead(roomId: String, eventId: String) {
        android.util.Log.d("Andromuks", "AppViewModel: Marking room as read: $roomId, eventId: $eventId")
        
        val markReadRequestId = requestIdCounter++
        pendingRequests[markReadRequestId] = roomId
        sendWebSocketCommand("mark_read", markReadRequestId, mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "receipt_type" to "m.read"
        ))
    }
    
    fun handleMarkReadResponse(requestId: Int, success: Boolean) {
        val roomId = pendingRequests[requestId]
        if (roomId != null) {
            android.util.Log.d("Andromuks", "AppViewModel: Mark read response for room $roomId: $success")
            // Remove the request from pending
            pendingRequests.remove(requestId)
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
