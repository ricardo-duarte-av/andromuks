package net.vrkknn.andromuks.database

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.SpaceEntity
import net.vrkknn.andromuks.database.entities.SpaceRoomEntity
import org.json.JSONObject

/**
 * BootstrapLoader - Loads data from Room database on app startup
 * 
 * Provides:
 * - Room list from room_summary and room_state
 * - Timeline events for RoomTimelineCache (instant room opening)
 * - Sync metadata (run_id, last_received_id, since token)
 */
class BootstrapLoader(private val context: Context) {
    private val database = AndromuksDatabase.getInstance(context)
    private val roomSummaryDao = database.roomSummaryDao()
    private val roomStateDao = database.roomStateDao()
    private val eventDao = database.eventDao()
    private val syncMetaDao = database.syncMetaDao()
    private val spaceDao = database.spaceDao()
    private val spaceRoomDao = database.spaceRoomDao()
    
    private val TAG = "BootstrapLoader"
    
    /**
     * Bootstrap data from database
     * Returns BootstrapResult with loaded data
     */
    suspend fun loadBootstrap(): BootstrapResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading bootstrap data from Room database")
        
        // 1. Check run_id - if it doesn't match, don't load anything
        val storedRunId = syncMetaDao.get("run_id") ?: ""
        if (storedRunId.isEmpty()) {
            Log.d(TAG, "No run_id stored - database is empty or was cleared")
            return@withContext BootstrapResult(
                rooms = emptyList(),
                runId = "",
                lastReceivedId = 0,
                sinceToken = "",
                isValid = false
            )
        }
        
        // 2. Load sync metadata
        val lastReceivedId = syncMetaDao.get("last_received_id")?.toIntOrNull() ?: 0
        val sinceToken = syncMetaDao.get("since") ?: ""
        
        // 3. Load ALL room summaries (not just top 200) - needed for complete room list
        val roomSummaries = roomSummaryDao.getAllRooms()
        Log.d(TAG, "Loaded ${roomSummaries.size} room summaries from database")
        
        // 4. Load ALL room states for efficient lookup
        val allRoomStates = roomStateDao.getAllRoomStates()
        val roomStateMap = allRoomStates.associateBy { it.roomId }
        Log.d(TAG, "Loaded ${allRoomStates.size} room states from database")
        
        // 5. Build room list from summaries + room state
        val rooms = mutableListOf<RoomItem>()
        for (summary in roomSummaries) {
            val roomState = roomStateMap[summary.roomId]
            
            val room = RoomItem(
                id = summary.roomId,
                name = roomState?.name ?: summary.roomId,
                avatarUrl = roomState?.avatarUrl,
                messagePreview = summary.messagePreview,
                messageSender = summary.messageSender,
                sortingTimestamp = summary.lastTimestamp.takeIf { it > 0 },
                unreadCount = summary.unreadCount.takeIf { it > 0 },
                highlightCount = summary.highlightCount.takeIf { it > 0 },
                isDirectMessage = roomState?.isDirect ?: false,
                isFavourite = roomState?.isFavourite ?: false,
                isLowPriority = roomState?.isLowPriority ?: false
            )
            rooms.add(room)
        }
        
        // 5. Pre-load timeline events for top N rooms into RoomTimelineCache
        // This enables instant room opening for frequently accessed rooms
        val roomsToPreload = roomSummaries.take(10) // Preload top 10 rooms
        var totalEventsLoaded = 0
        
        for (summary in roomsToPreload) {
            val events = eventDao.getEventsForRoomDesc(summary.roomId, 100)
            if (events.isNotEmpty()) {
                val timelineEvents = events.mapNotNull { entity -> entityToTimelineEvent(entity) }
                
                // Seed RoomTimelineCache with events from DB
                RoomTimelineCache.seedCacheWithPaginatedEvents(summary.roomId, timelineEvents)
                totalEventsLoaded += timelineEvents.size
                Log.d(TAG, "Pre-loaded ${timelineEvents.size} events for room ${summary.roomId}")
            }
        }
        
        Log.d(TAG, "Bootstrap complete: ${rooms.size} rooms, $totalEventsLoaded events pre-loaded, runId=$storedRunId, lastReceivedId=$lastReceivedId")
        
        BootstrapResult(
            rooms = rooms,
            runId = storedRunId,
            lastReceivedId = lastReceivedId,
            sinceToken = sinceToken,
            isValid = true
        )
    }
    
    /**
     * Convert EventEntity to TimelineEvent
     */
    private fun entityToTimelineEvent(entity: EventEntity): TimelineEvent? {
        return try {
            // Parse from raw JSON (future-proof)
            val json = JSONObject(entity.rawJson)
            TimelineEvent.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse event from JSON: ${e.message}")
            null
        }
    }
    
    /**
     * Load events for a specific room from database
     * Returns list of TimelineEvents or empty list if none found
     */
    suspend fun loadRoomEvents(roomId: String, limit: Int = 100): List<TimelineEvent> = withContext(Dispatchers.IO) {
        try {
            val events = eventDao.getEventsForRoomDesc(roomId, limit)
            events.mapNotNull { entity -> entityToTimelineEvent(entity) }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading events for room $roomId: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get stored run_id (for validation)
     */
    suspend fun getStoredRunId(): String = withContext(Dispatchers.IO) {
        syncMetaDao.get("run_id") ?: ""
    }
    
    /**
     * Load bridge info from database for all rooms
     */
    suspend fun loadBridgeInfoFromDb(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val allStates = roomStateDao.getAllRoomStates()
            val bridgeInfoMap = mutableMapOf<String, String>()
            for (state in allStates) {
                if (state.bridgeInfoJson != null) {
                    bridgeInfoMap[state.roomId] = state.bridgeInfoJson
                }
            }
            Log.d(TAG, "Loaded ${bridgeInfoMap.size} bridge info entries from database")
            bridgeInfoMap
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bridge info from database: ${e.message}", e)
            emptyMap()
        }
    }
    
    /**
     * Load spaces from database and reconstruct SpaceItem objects
     */
    suspend fun loadSpacesFromDb(roomMap: Map<String, RoomItem>): List<net.vrkknn.andromuks.SpaceItem> = withContext(Dispatchers.IO) {
        try {
            val allSpaces = spaceDao.getAllSpaces()
            val allSpaceRooms = spaceRoomDao.getAllRoomsForAllSpaces()
            
            // Group space rooms by space ID
            val spaceRoomsMap = allSpaceRooms.groupBy { it.spaceId }
            
            val spaces = mutableListOf<net.vrkknn.andromuks.SpaceItem>()
            
            for (spaceEntity in allSpaces) {
                val childRooms = spaceRoomsMap[spaceEntity.spaceId]?.mapNotNull { spaceRoom ->
                    roomMap[spaceRoom.childId]
                } ?: emptyList()
                
                val space = net.vrkknn.andromuks.SpaceItem(
                    id = spaceEntity.spaceId,
                    name = spaceEntity.name ?: spaceEntity.spaceId,
                    avatarUrl = spaceEntity.avatarUrl,
                    rooms = childRooms
                )
                spaces.add(space)
            }
            
            Log.d(TAG, "Loaded ${spaces.size} spaces from database with ${allSpaceRooms.size} room relationships")
            spaces
        } catch (e: Exception) {
            Log.e(TAG, "Error loading spaces from database: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Result of bootstrap loading
     */
    data class BootstrapResult(
        val rooms: List<RoomItem>,
        val runId: String,
        val lastReceivedId: Int,
        val sinceToken: String,
        val isValid: Boolean
    )
}

