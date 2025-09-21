package net.vrkknn.andromuks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import net.vrkknn.andromuks.SpaceItem
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.SpaceRoomParser
import org.json.JSONObject

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

    private val allRooms = mutableListOf<RoomItem>()
    private var syncMessageCount = 0
    
    fun updateRoomsFromSyncJson(syncJson: JSONObject) {
        val rooms = SpaceRoomParser.parseRooms(syncJson)
        // Use a Set to avoid duplicates based on room ID
        val existingIds = allRooms.map { it.id }.toSet()
        val newRooms = rooms.filter { !existingIds.contains(it.id) }
        allRooms.addAll(newRooms)
        syncMessageCount++
        android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${allRooms.size} (added ${newRooms.size} new) - sync message #$syncMessageCount")
        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = allRooms.toList())))
        
        // Temporary workaround: navigate after 3 sync messages if we have rooms
        if (syncMessageCount >= 3 && allRooms.isNotEmpty() && !spacesLoaded) {
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
    
    // Room timeline state
    var currentRoomId by mutableStateOf("")
        private set
    var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
        private set
    var isTimelineLoading by mutableStateOf(false)
        private set
    
    private var requestIdCounter = 100
    private val pendingRequests = mutableMapOf<Int, String>() // requestId -> roomId
    
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
        val roomId = pendingRequests[requestId]
        if (roomId == null) {
            android.util.Log.w("Andromuks", "AppViewModel: Received response for unknown request ID: $requestId")
            return
        }
        
        android.util.Log.d("Andromuks", "AppViewModel: Handling timeline response for room: $roomId, requestId: $requestId")
        
        when (data) {
            is List<*> -> {
                // Room state response
                val events = data.filterIsInstance<JSONObject>().map { TimelineEvent.fromJson(it) }
                android.util.Log.d("Andromuks", "AppViewModel: Received ${events.size} room state events")
            }
            is JSONObject -> {
                // Paginate response
                val eventsArray = data.optJSONArray("events")
                if (eventsArray != null) {
                    val events = mutableListOf<TimelineEvent>()
                    for (i in 0 until eventsArray.length()) {
                        val eventJson = eventsArray.optJSONObject(i)
                        if (eventJson != null) {
                            events.add(TimelineEvent.fromJson(eventJson))
                        }
                    }
                    android.util.Log.d("Andromuks", "AppViewModel: Received ${events.size} timeline events")
                    timelineEvents = events
                    isTimelineLoading = false
                    
                    // Mark room as read with the latest event ID
                    if (events.isNotEmpty()) {
                        val latestEvent = events.maxByOrNull { it.timelineRowid }
                        if (latestEvent != null) {
                            markRoomAsRead(roomId, latestEvent.eventId)
                        }
                    }
                }
            }
        }
        
        // Remove the request from pending
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
        // This will be implemented to send commands via websocket
        android.util.Log.d("Andromuks", "AppViewModel: Would send command: $command, requestId: $requestId")
    }
}
