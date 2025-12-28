package net.vrkknn.andromuks.database

import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.database.dao.AccountDataDao
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.InviteDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.RoomListSummaryDao
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
    private val roomListSummaryDao = database.roomListSummaryDao()
    private val roomStateDao = database.roomStateDao()
    private val eventDao = database.eventDao()
    private val syncMetaDao = database.syncMetaDao()
    private val spaceDao = database.spaceDao()
    private val spaceRoomDao = database.spaceRoomDao()
    private val accountDataDao = database.accountDataDao()
    private val inviteDao = database.inviteDao()
    
    private val TAG = "BootstrapLoader"
    
    /**
     * Bootstrap data from database
     * Returns BootstrapResult with loaded data
     */
    suspend fun loadBootstrap(): BootstrapResult = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Loading bootstrap data from Room database")
        
        // 1. Check run_id - if it doesn't match, don't load anything
        val storedRunId = syncMetaDao.get("run_id") ?: ""
        if (storedRunId.isEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "No run_id stored - database is empty or was cleared")
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
        if (BuildConfig.DEBUG) Log.d(TAG, "Loaded account_data from database: ${if (accountDataJson != null) "${accountDataJson.length} chars" else "null"}")
        
        // 3. Load ALL room summaries (not just top 200) - needed for complete room list
        val roomSummaries = roomSummaryDao.getAllRooms()
        val roomListSummaries = roomListSummaryDao.getAll()
        val roomListSummaryMap = roomListSummaries.associateBy { it.roomId }
        if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${roomSummaries.size} room summaries and ${roomListSummaries.size} room_list summaries from database")
        
        // 4. Load ALL room states for efficient lookup
        val allRoomStates = roomStateDao.getAllRoomStates()
        val roomStateMap = allRoomStates.associateBy { it.roomId }
        if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${allRoomStates.size} room states from database")
        
        // 5. Build room list from summaries + room state
        val rooms = mutableListOf<RoomItem>()
        for (summary in roomSummaries) {
            val roomState = roomStateMap[summary.roomId]
            val listSummary = roomListSummaryMap[summary.roomId]
            
            // FIX: If lastTimestamp is 0, try to get it from the most recent event in the database
            var sortingTimestamp = summary.lastTimestamp
            if (sortingTimestamp <= 0) {
                try {
                    val lastEventTimestamp = eventDao.getLastEventTimestamp(summary.roomId)
                    if (lastEventTimestamp != null && lastEventTimestamp > 0) {
                        sortingTimestamp = lastEventTimestamp
                        if (BuildConfig.DEBUG) Log.d(TAG, "Room ${summary.roomId}: Using last event timestamp $sortingTimestamp instead of summary.lastTimestamp (0)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get last event timestamp for room ${summary.roomId}: ${e.message}")
                }
            }
            
            // CRITICAL FIX: Prefer room_list_summary (persisted) for preview/sender/timestamp; then DB last message; then summary.
            var messagePreview: String? = listSummary?.lastMessagePreview
            var messageSender: String? = listSummary?.lastMessageSenderUserId
            var messageTimestampFromEvents: Long? = listSummary?.lastMessageTimestamp
            var lastEventTimestampFallback: Long? = null
            try {
                val lastMsg = eventDao.getLastMessageForRoom(summary.roomId)
                if (lastMsg != null && lastMsg.timestamp > 0) {
                    val eventJson = JSONObject(lastMsg.rawJson)
                    val eventType = lastMsg.type
                    var preview: String? = null
                    if (eventType == "m.room.encrypted") {
                        val decrypted = eventJson.optJSONObject("decrypted")
                        preview = decrypted?.optString("body")?.takeIf { it.isNotBlank() }
                        // Handle edits
                        val relatesTo = decrypted?.optJSONObject("m.relates_to")
                        val isEdit = relatesTo?.optString("rel_type") == "m.replace"
                        if (isEdit) {
                            preview = decrypted?.optJSONObject("m.new_content")?.optString("body")?.takeIf { it.isNotBlank() }
                        }
                    } else if (eventType == "m.room.message") {
                        val content = eventJson.optJSONObject("content")
                        val relatesTo = content?.optJSONObject("m.relates_to")
                        val isEdit = relatesTo?.optString("rel_type") == "m.replace"
                        preview = if (isEdit) {
                            content?.optJSONObject("m.new_content")?.optString("body")?.takeIf { it.isNotBlank() }
                        } else {
                            content?.optString("body")?.takeIf { it.isNotBlank() }
                        }
                    }
                    if (preview.isNullOrBlank()) {
                        // Basic media fallback
                        val content = eventJson.optJSONObject("content")
                        val msgtype = content?.optString("msgtype", "")
                        preview = when (msgtype) {
                            "m.image" -> "\uD83D\uDCF7 Image"
                            "m.video" -> "\uD83C\uDFA5 Video"
                            "m.audio" -> "\uD83C\uDFB5 Audio"
                            "m.file" -> "\uD83D\uDCCE File"
                            "m.location" -> "\uD83D\uDCCD Location"
                            else -> null
                        }
                    }
                    messagePreview = preview
                    messageSender = lastMsg.sender
                    messageTimestampFromEvents = lastMsg.timestamp
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "Room ${summary.roomId}: Loaded last message from DB: preview='${messagePreview?.take(50)}', sender=$messageSender, ts=${lastMsg.timestamp}, rowId=${lastMsg.timelineRowId}"
                        )
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Room ${summary.roomId}: No last message row found with timestamp>0, falling back to summary")
                    // Try to at least capture the latest event timestamp (any type) to keep sorting correct
                    try {
                        val latestAny = eventDao.getEventsForRoomDesc(summary.roomId, 1).firstOrNull()
                        if (latestAny != null && latestAny.timestamp > 0) {
                            messageTimestampFromEvents = latestAny.timestamp
                            if (BuildConfig.DEBUG) Log.d(
                                TAG,
                                "Room ${summary.roomId}: Using latest event timestamp for sorting (type=${latestAny.type}, ts=${latestAny.timestamp}, rowId=${latestAny.timelineRowId})"
                            )
                        }
                    } catch (_: Exception) {
                    }
                }

                // If still missing preview/sender, fall back to summary
                if (messagePreview.isNullOrBlank()) {
                    messagePreview = summary.messagePreview
                    messageSender = summary.messageSender
                }

                // If we did not capture a timestamp above, fetch MAX(timestamp)
                if (messageTimestampFromEvents == null) {
                    lastEventTimestampFallback = eventDao.getLastEventTimestamp(summary.roomId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load last message for room ${summary.roomId}: ${e.message}")
                messagePreview = summary.messagePreview
                messageSender = summary.messageSender
                try {
                    lastEventTimestampFallback = eventDao.getLastEventTimestamp(summary.roomId)
                } catch (_: Exception) {}
            }
            
            // BUG FIX #1: Always include unreadCount and highlightCount (even if 0) for proper badge display
            // Also ensure sortingTimestamp is set (either from summary or from last event)
            // If we did not capture a timestamp from the event rows, fetch MAX(timestamp) as a fallback
            if (messageTimestampFromEvents == null) {
                try {
                    lastEventTimestampFallback = eventDao.getLastEventTimestamp(summary.roomId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch fallback lastEventTimestamp for room ${summary.roomId}: ${e.message}")
                }
            }

            val room = RoomItem(
                id = summary.roomId,
                name = roomState?.name ?: summary.roomId,
                avatarUrl = roomState?.avatarUrl,
                messagePreview = messagePreview,
                messageSender = messageSender,
                sortingTimestamp = (
                    messageTimestampFromEvents
                        ?: lastEventTimestampFallback
                        ?: sortingTimestamp
                        ?: summary.lastTimestamp
                ).takeIf { it != null && it > 0 },
                unreadCount = summary.unreadCount.takeIf { it > 0 }, // Only show if > 0 for badge
                highlightCount = summary.highlightCount.takeIf { it > 0 }, // Only show if > 0 for badge
                isDirectMessage = roomState?.isDirect ?: false,
                isFavourite = roomState?.isFavourite ?: false,
                isLowPriority = roomState?.isLowPriority ?: false
            )
            rooms.add(room)
        }
        
        // 5. Event pre-loading removed - cache is now populated only from sync_complete messages or paginate responses
        // This ensures cache consistency and prevents stale data from DB
        if (BuildConfig.DEBUG) Log.d(TAG, "Bootstrap complete: ${rooms.size} rooms, runId=$storedRunId, lastReceivedId=$lastReceivedId")
        
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
            val json = JSONObject(entity.rawJson)
            json.put("room_id", entity.roomId)
            json.put("timeline_rowid", entity.timelineRowId)
            json.put("rowid", entity.timelineRowId.toLong())
            if (!json.has("origin_server_ts") || json.optLong("origin_server_ts") == 0L) {
                json.put("origin_server_ts", entity.timestamp)
            }
            if (!json.has("timestamp") || json.optLong("timestamp") == 0L) {
                json.put("timestamp", entity.timestamp)
            }
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
    suspend fun loadRoomEvents(roomId: String, limit: Int = 200): List<TimelineEvent> = withContext(Dispatchers.IO) {
        try {
            // CRITICAL: Query gets events ordered by timestamp DESC (newest first)
            // This ensures we get the most recent events, not old ones
            val events = eventDao.getEventsForRoomDesc(roomId, limit)
            
            // Convert to TimelineEvent and sort in chronological order (oldest first) for timeline display
            val timelineEvents = events
                .mapNotNull { entity -> entityToTimelineEvent(entity) }
                .sortedWith { a, b ->
                    when {
                        // Both have timelineRowid - sort by timelineRowid (ascending = chronological)
                        a.timelineRowid > 0 && b.timelineRowid > 0 -> a.timelineRowid.compareTo(b.timelineRowid)
                        // Events with timelineRowid come after those without (they're newer)
                        a.timelineRowid > 0 -> 1
                        b.timelineRowid > 0 -> -1
                        // Neither has timelineRowid - sort by timestamp (ascending = chronological)
                        else -> {
                            val tsCompare = a.timestamp.compareTo(b.timestamp)
                            if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                        }
                    }
                }
            
            // CRITICAL FIX: Verify we got the newest events by checking the latest timestamp
            // If the latest event is very old, something went wrong with the query
            val latestTimestamp = timelineEvents.maxByOrNull { it.timestamp }?.timestamp ?: 0L
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - latestTimestamp
            
            // If the latest event is more than 24 hours old, log a warning
            // (This might indicate we're loading old events instead of new ones)
            if (latestTimestamp > 0 && timeDiff > 24 * 60 * 60 * 1000L) {
                Log.w(TAG, "loadRoomEvents: Latest event for room $roomId is ${timeDiff / (60 * 60 * 1000)} hours old - might be loading old events instead of new ones")
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "loadRoomEvents: Loaded ${timelineEvents.size} events for room $roomId (latest timestamp: $latestTimestamp, ${if (latestTimestamp > 0) "${timeDiff / 1000}s ago" else "unknown"})")
            }
            
            timelineEvents
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
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${spaces.size} spaces from database with ${allSpaceRooms.size} room relationships")
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
    
    /**
     * Load pending invites from database
     */
    suspend fun loadInvitesFromDb(): List<net.vrkknn.andromuks.RoomInvite> = withContext(Dispatchers.IO) {
        try {
            val inviteEntities = inviteDao.getAllInvites()
            val invites = inviteEntities.map { entity ->
                net.vrkknn.andromuks.RoomInvite(
                    roomId = entity.roomId,
                    createdAt = entity.createdAt,
                    inviterUserId = entity.inviterUserId,
                    inviterDisplayName = entity.inviterDisplayName,
                    roomName = entity.roomName,
                    roomAvatar = entity.roomAvatar,
                    roomTopic = entity.roomTopic,
                    roomCanonicalAlias = entity.roomCanonicalAlias,
                    inviteReason = entity.inviteReason,
                    isDirectMessage = entity.isDirectMessage
                )
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${invites.size} pending invites from database")
            invites
        } catch (e: Exception) {
            Log.e(TAG, "Error loading invites from database: ${e.message}", e)
            emptyList()
        }
    }
}

