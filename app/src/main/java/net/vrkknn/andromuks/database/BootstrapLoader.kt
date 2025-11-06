package net.vrkknn.andromuks.database

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.database.dao.AccountDataDao
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
    private val accountDataDao = database.accountDataDao()
    
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
                accountDataJson = null,
                isValid = false
            )
        }
        
        // 2. Load sync metadata
        val lastReceivedId = syncMetaDao.get("last_received_id")?.toIntOrNull() ?: 0
        val sinceToken = syncMetaDao.get("since") ?: ""
        
        // 2.5. Load account_data
        val accountDataJson = accountDataDao.getAccountData()
        Log.d(TAG, "Loaded account_data from database: ${if (accountDataJson != null) "${accountDataJson.length} chars" else "null"}")
        
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
            
            // FIX: If lastTimestamp is 0, try to get it from the most recent event in the database
            var sortingTimestamp = summary.lastTimestamp
            if (sortingTimestamp <= 0) {
                try {
                    val lastEventTimestamp = eventDao.getLastEventTimestamp(summary.roomId)
                    if (lastEventTimestamp != null && lastEventTimestamp > 0) {
                        sortingTimestamp = lastEventTimestamp
                        Log.d(TAG, "Room ${summary.roomId}: Using last event timestamp $sortingTimestamp instead of summary.lastTimestamp (0)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get last event timestamp for room ${summary.roomId}: ${e.message}")
                }
            }
            
            // CRITICAL FIX: If messagePreview is missing, load it from the most recent message event
            var messagePreview = summary.messagePreview
            var messageSender = summary.messageSender
            if (messagePreview.isNullOrBlank()) {
                try {
                    // Get the most recent events and find the last message
                    val recentEvents = eventDao.getEventsForRoomDesc(summary.roomId, 50)
                    for (eventEntity in recentEvents) {
                        try {
                            val eventJson = JSONObject(eventEntity.rawJson)
                            val eventType = eventJson.optString("type")
                            
                            if (eventType == "m.room.message" || eventType == "m.room.encrypted") {
                                // Extract message preview
                                val content = eventJson.optJSONObject("content")
                                if (content != null) {
                                    val body = content.optString("body")?.takeIf { it.isNotBlank() }
                                    if (body != null) {
                                        messagePreview = body
                                        messageSender = eventJson.optString("sender")?.takeIf { it.isNotBlank() }
                                        Log.d(TAG, "Room ${summary.roomId}: Loaded message preview from events: '${messagePreview.take(50)}...'")
                                        break
                                    }
                                }
                                
                                // For encrypted messages, check decrypted content
                                if (messagePreview.isNullOrBlank() && eventType == "m.room.encrypted") {
                                    val decrypted = eventJson.optJSONObject("decrypted")
                                    val body = decrypted?.optString("body")?.takeIf { it.isNotBlank() }
                                    if (body != null) {
                                        messagePreview = body
                                        messageSender = eventJson.optString("sender")?.takeIf { it.isNotBlank() }
                                        Log.d(TAG, "Room ${summary.roomId}: Loaded encrypted message preview from events: '${messagePreview.take(50)}...'")
                                        break
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse event for room ${summary.roomId}: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get last message event for room ${summary.roomId}: ${e.message}")
                }
            }
            
            // BUG FIX #1: Always include unreadCount and highlightCount (even if 0) for proper badge display
            // Also ensure sortingTimestamp is set (either from summary or from last event)
            val room = RoomItem(
                id = summary.roomId,
                name = roomState?.name ?: summary.roomId,
                avatarUrl = roomState?.avatarUrl,
                messagePreview = messagePreview,
                messageSender = messageSender,
                sortingTimestamp = sortingTimestamp.takeIf { it > 0 }, // Only set if > 0 (for time diff display)
                unreadCount = summary.unreadCount.takeIf { it > 0 }, // Only show if > 0 for badge
                highlightCount = summary.highlightCount.takeIf { it > 0 }, // Only show if > 0 for badge
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
            accountDataJson = accountDataJson,
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
     * BUG FIX #3: Also returns set of rooms that were checked (even if no bridge found)
     */
    suspend fun loadBridgeInfoFromDb(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val allStates = roomStateDao.getAllRoomStates()
            val bridgeInfoMap = mutableMapOf<String, String>()
            for (state in allStates) {
                // BUG FIX #3: Empty string means "checked, no bridge" - still include it but as checked
                // null means "not checked yet"
                if (state.bridgeInfoJson != null && state.bridgeInfoJson.isNotBlank()) {
                    bridgeInfoMap[state.roomId] = state.bridgeInfoJson
                }
            }
            Log.d(TAG, "Loaded ${bridgeInfoMap.size} bridge info entries from database (${allStates.count { it.bridgeInfoJson != null }} total checked)")
            bridgeInfoMap
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bridge info from database: ${e.message}", e)
            emptyMap()
        }
    }
    
    /**
     * Get list of rooms that have been checked for bridge info (even if no bridge found)
     * BUG FIX #3: This helps avoid requesting m.bridge for rooms already checked
     */
    suspend fun getBridgeCheckedRooms(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val allStates = roomStateDao.getAllRoomStates()
            val checkedRooms = allStates
                .filter { it.bridgeInfoJson != null } // null = not checked, empty string or JSON = checked
                .map { it.roomId }
                .toSet()
            Log.d(TAG, "Found ${checkedRooms.size} rooms already checked for bridge info")
            checkedRooms
        } catch (e: Exception) {
            Log.e(TAG, "Error getting bridge checked rooms: ${e.message}", e)
            emptySet()
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
        val accountDataJson: String? = null,
        val isValid: Boolean
    )
    
    /**
     * Load account_data from database (standalone method)
     */
    suspend fun loadAccountData(): String? = withContext(Dispatchers.IO) {
        try {
            accountDataDao.getAccountData()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading account_data from database: ${e.message}", e)
            null
        }
    }
}

