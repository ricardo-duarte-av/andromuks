package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * RoomRepository - Single source of truth for room and timeline data
 * 
 * This repository coordinates:
 * - In-memory state (StateFlow) - what ViewModels observe
 * - RAM cache (RoomTimelineCache) - fast access for instant rendering
 * - Database persistence (AndromuksDatabase) - survives app restart
 * 
 * Phase 1: Created alongside existing AppViewModel state (non-breaking)
 * - Repository updates in parallel with AppViewModel
 * - AppViewModel continues to work as before
 * - No functionality changes
 * 
 * Phase 2+: ViewModels will observe Repository instead of holding state
 */
object RoomRepository {
    private const val TAG = "RoomRepository"
    
    // ========== STATE (StateFlow) ==========
    // Timeline events: roomId -> List<TimelineEvent>
    private val _timelineEvents = MutableStateFlow<Map<String, List<TimelineEvent>>>(emptyMap())
    val timelineEvents: StateFlow<Map<String, List<TimelineEvent>>> = _timelineEvents.asStateFlow()
    
    // Room map: roomId -> RoomItem
    private val _roomMap = MutableStateFlow<Map<String, RoomItem>>(emptyMap())
    val roomMap: StateFlow<Map<String, RoomItem>> = _roomMap.asStateFlow()
    
    // Current room ID
    private val _currentRoomId = MutableStateFlow<String>("")
    val currentRoomId: StateFlow<String> = _currentRoomId.asStateFlow()
    
    // ========== INITIALIZATION ==========
    private var context: Context? = null
    private var isInitialized = false
    
    /**
     * Initialize Repository (call from Application or MainActivity onCreate)
     * 
     * Phase 1: Must be called before using Repository
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "Repository already initialized")
            return
        }
        this.context = context.applicationContext
        isInitialized = true
        if (BuildConfig.DEBUG) Log.d(TAG, "RoomRepository initialized")
    }
    
    /**
     * Check if Repository is initialized
     */
    fun isInitialized(): Boolean = isInitialized
    
    // ========== QUERY FUNCTIONS ==========
    /**
     * Get timeline for a specific room
     */
    fun getTimelineForRoom(roomId: String): List<TimelineEvent> {
        return _timelineEvents.value[roomId] ?: emptyList()
    }
    
    /**
     * Get event count for a room
     */
    fun getEventCount(roomId: String): Int {
        return _timelineEvents.value[roomId]?.size ?: 0
    }
    
    /**
     * Get room by ID
     */
    fun getRoom(roomId: String): RoomItem? {
        return _roomMap.value[roomId]
    }
    
    /**
     * Get all rooms (sorted by sortingTimestamp descending)
     */
    fun getAllRooms(): List<RoomItem> {
        return _roomMap.value.values.sortedByDescending { it.sortingTimestamp ?: 0L }
    }
    
    /**
     * Set current room ID
     */
    fun setCurrentRoom(roomId: String) {
        _currentRoomId.value = roomId
        if (BuildConfig.DEBUG) Log.d(TAG, "Current room set to: $roomId")
    }
    
    /**
     * Get current room ID
     */
    fun getCurrentRoomId(): String {
        return _currentRoomId.value
    }
    
    // ========== UPDATE FUNCTIONS (Phase 1: Called alongside AppViewModel updates) ==========
    /**
     * Update timeline for a room
     * 
     * Phase 1: Called from AppViewModel AFTER it updates its own state
     * This ensures Repository stays in sync with AppViewModel
     * 
     * @param roomId The room ID
     * @param events The timeline events for this room
     */
    fun updateTimeline(roomId: String, events: List<TimelineEvent>) {
        if (!isInitialized) {
            Log.w(TAG, "Repository not initialized, skipping updateTimeline")
            return
        }
        
        _timelineEvents.update { currentMap ->
            val existingEvents = currentMap[roomId] ?: emptyList()
            
            // Merge: Add new events, deduplicate by eventId
            val existingEventIds = existingEvents.map { it.eventId }.toSet()
            val uniqueNewEvents = events.filter { it.eventId !in existingEventIds }
            
            if (uniqueNewEvents.isEmpty() && existingEvents.size == events.size) {
                currentMap // No changes
            } else {
                // Replace with new events (AppViewModel already handled deduplication)
                currentMap + (roomId to events.sortedBy { it.timestamp })
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Updated timeline for room $roomId: ${getEventCount(roomId)} events")
    }
    
    /**
     * Append events to timeline (for sync_complete)
     * 
     * Phase 1: Called from AppViewModel when sync_complete adds new events
     * 
     * @param roomId The room ID
     * @param newEvents New events to append
     */
    fun appendTimelineEvents(roomId: String, newEvents: List<TimelineEvent>) {
        if (!isInitialized) {
            Log.w(TAG, "Repository not initialized, skipping appendTimelineEvents")
            return
        }
        
        if (newEvents.isEmpty()) {
            return
        }
        
        _timelineEvents.update { currentMap ->
            val existingEvents = currentMap[roomId] ?: emptyList()
            val existingEventIds = existingEvents.map { it.eventId }.toSet()
            val uniqueNewEvents = newEvents.filter { it.eventId !in existingEventIds }
            
            if (uniqueNewEvents.isEmpty()) {
                currentMap // No new events
            } else {
                val merged = (existingEvents + uniqueNewEvents).sortedBy { it.timestamp }
                currentMap + (roomId to merged)
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Appended ${newEvents.size} events to room $roomId: ${getEventCount(roomId)} total")
    }
    
    /**
     * Update room map
     * 
     * Phase 1: Called from AppViewModel when room list updates
     * 
     * @param rooms Map of roomId -> RoomItem
     */
    fun updateRooms(rooms: Map<String, RoomItem>) {
        if (!isInitialized) {
            Log.w(TAG, "Repository not initialized, skipping updateRooms")
            return
        }
        
        _roomMap.value = rooms
        if (BuildConfig.DEBUG) Log.d(TAG, "Updated room map: ${rooms.size} rooms")
    }
    
    /**
     * Update single room
     * 
     * Phase 1: Called from AppViewModel when a single room updates
     * 
     * @param room The room to update
     */
    fun updateRoom(room: RoomItem) {
        if (!isInitialized) {
            Log.w(TAG, "Repository not initialized, skipping updateRoom")
            return
        }
        
        _roomMap.update { it + (room.id to room) }
        if (BuildConfig.DEBUG) Log.d(TAG, "Updated room: ${room.id}")
    }
    
    /**
     * Clear timeline for a room
     * 
     * Phase 1: Called from AppViewModel when leaving a room
     * 
     * @param roomId The room ID to clear
     */
    fun clearTimeline(roomId: String) {
        if (!isInitialized) {
            Log.w(TAG, "Repository not initialized, skipping clearTimeline")
            return
        }
        
        _timelineEvents.update { it - roomId }
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleared timeline for room: $roomId")
    }
    
    /**
     * Clear all data (for logout or reset)
     */
    fun clearAll() {
        if (!isInitialized) {
            Log.w(TAG, "Repository not initialized, skipping clearAll")
            return
        }
        
        _timelineEvents.value = emptyMap()
        _roomMap.value = emptyMap()
        _currentRoomId.value = ""
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleared all Repository data")
    }
    
    // ========== PHASE 1: PLACEHOLDER FUNCTIONS (Will be implemented in Phase 2) ==========
    /**
     * Update from sync_complete
     * 
     * Phase 1: Placeholder - does nothing
     * Phase 2: Will coordinate RAM cache, database, and StateFlow updates
     * 
     * @param syncJson The sync_complete JSON object
     * @param requestId The request_id from sync_complete
     * @param runId The current run_id
     * @param memberMap Map of userId -> MemberProfile for event parsing
     */
    suspend fun updateFromSyncComplete(
        syncJson: JSONObject,
        requestId: Int,
        runId: String,
        memberMap: Map<String, MemberProfile>
    ) = withContext(Dispatchers.IO) {
        // Phase 1: Do nothing - AppViewModel still handles this
        if (BuildConfig.DEBUG) Log.d(TAG, "updateFromSyncComplete called (Phase 1: placeholder - AppViewModel handles this)")
    }
    
    /**
     * Load events from database
     * 
     * Phase 1: Placeholder - returns empty list
     * Phase 2: Will load from database and update Repository
     * 
     * @param roomId The room ID
     * @param limit Maximum number of events to load
     * @param beforeRowId Load events before this row ID (for pagination)
     * @return List of TimelineEvent objects
     */
    suspend fun loadEventsFromDatabase(
        roomId: String,
        limit: Int = 100,
        beforeRowId: Long = -1
    ): List<TimelineEvent> = withContext(Dispatchers.IO) {
        // Phase 1: Return empty - AppViewModel still handles this
        if (BuildConfig.DEBUG) Log.d(TAG, "loadEventsFromDatabase called (Phase 1: placeholder - AppViewModel handles this)")
        emptyList()
    }
    
    /**
     * Update from paginate response
     * 
     * Phase 1: Placeholder - does nothing
     * Phase 2: Will update Repository from pagination response
     * 
     * @param roomId The room ID
     * @param events Events from paginate response
     */
    suspend fun updateFromPaginateResponse(
        roomId: String,
        events: List<TimelineEvent>
    ) = withContext(Dispatchers.IO) {
        // Phase 1: Do nothing - AppViewModel still handles this
        if (BuildConfig.DEBUG) Log.d(TAG, "updateFromPaginateResponse called (Phase 1: placeholder - AppViewModel handles this)")
    }
    
    // ========== DEBUG/STATS ==========
    /**
     * Get Repository statistics for debugging
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "initialized" to isInitialized,
            "total_rooms" to _roomMap.value.size,
            "total_rooms_with_timeline" to _timelineEvents.value.size,
            "current_room_id" to _currentRoomId.value,
            "total_events" to _timelineEvents.value.values.sumOf { it.size }
        )
    }
}

