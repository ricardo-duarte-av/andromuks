package net.vrkknn.andromuks.database

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.database.dao.AccountDataDao
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.ReceiptDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import net.vrkknn.andromuks.database.entities.AccountDataEntity
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.ReceiptEntity
import net.vrkknn.andromuks.database.entities.RoomStateEntity
import net.vrkknn.andromuks.database.entities.RoomSummaryEntity
import net.vrkknn.andromuks.database.entities.SpaceEntity
import net.vrkknn.andromuks.database.entities.SpaceRoomEntity
import net.vrkknn.andromuks.database.entities.SyncMetaEntity
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * SyncIngestor - Persists sync_complete messages to Room database
 * 
 * Handles:
 * - Persisting events, room state, receipts from sync_complete
 * - Tracking run_id changes (clears DB if run_id changes)
 * - Storing last_received_id and since token for reconnection
 * - Transaction-based writes for consistency
 */
class SyncIngestor(private val context: Context) {
    private val database = AndromuksDatabase.getInstance(context)
    private val eventDao = database.eventDao()
    private val roomStateDao = database.roomStateDao()
    private val receiptDao = database.receiptDao()
    private val roomSummaryDao = database.roomSummaryDao()
    private val syncMetaDao = database.syncMetaDao()
    private val spaceDao = database.spaceDao()
    private val spaceRoomDao = database.spaceRoomDao()
    private val accountDataDao = database.accountDataDao()
    
    private val TAG = "SyncIngestor"
    
    /**
     * Check if run_id has changed and clear all data if it has
     * Returns true if run_id changed (data was cleared), false otherwise
     */
    suspend fun checkAndHandleRunIdChange(newRunId: String): Boolean = withContext(Dispatchers.IO) {
        val storedRunId = syncMetaDao.get("run_id") ?: ""
        
        if (storedRunId.isNotEmpty() && storedRunId != newRunId) {
            Log.w(TAG, "Run ID changed from '$storedRunId' to '$newRunId' - clearing all local data")
            clearAllData()
            syncMetaDao.upsert(SyncMetaEntity("run_id", newRunId))
            return@withContext true
        } else if (storedRunId.isEmpty()) {
            // First time - store run_id
            syncMetaDao.upsert(SyncMetaEntity("run_id", newRunId))
        }
        
        return@withContext false
    }
    
    /**
     * Clear all persisted data (called when run_id changes)
     */
    private suspend fun clearAllData() {
        Log.d(TAG, "Clearing all persisted sync data")
        database.withTransaction {
            // Clear all events, room states, summaries, receipts
            eventDao.deleteAll()
            roomStateDao.deleteAll()
            roomSummaryDao.deleteAll()
            receiptDao.deleteAll()
            
            // Clear spaces and space-room relationships
            spaceRoomDao.deleteAllSpaceRooms()
            spaceDao.deleteAllSpaces()
            
            // Clear account data
            accountDataDao.deleteAll()
            
            // Clear sync metadata except run_id (which we just set)
            syncMetaDao.upsert(SyncMetaEntity("last_received_id", "0"))
            syncMetaDao.upsert(SyncMetaEntity("since", ""))
        }
        Log.d(TAG, "Cleared all sync data: events, room states, summaries, receipts, spaces, account_data")
    }
    
    /**
     * Ingest a sync_complete message into the database
     * 
     * @param syncJson The sync_complete JSON object
     * @param requestId The request_id from the sync_complete (used as last_received_id)
     * @param runId The current run_id (must match stored run_id or data will be cleared)
     */
    suspend fun ingestSyncComplete(
        syncJson: JSONObject,
        requestId: Int,
        runId: String
    ) = withContext(Dispatchers.IO) {
        // Check run_id first - this is critical!
        val runIdChanged = checkAndHandleRunIdChange(runId)
        if (runIdChanged) {
            Log.w(TAG, "Run ID changed - previous data cleared, ingesting fresh sync")
        }
        
        val data = syncJson.optJSONObject("data") ?: run {
            Log.w(TAG, "No 'data' field in sync_complete")
            return@withContext
        }
        
        // Extract "since" token (sync token from server)
        val since = data.optString("since", "")
        
        // Process account_data if present (must be done before other processing)
        // IMPORTANT: Partial updates - only replace keys present in incoming sync, merge with existing
        val incomingAccountData = data.optJSONObject("account_data")
        if (incomingAccountData != null) {
            try {
                // Load existing account_data from database
                val existingAccountDataStr = accountDataDao.getAccountData()
                val mergedAccountData = if (existingAccountDataStr != null) {
                    // Merge: existing + incoming (incoming keys replace existing keys)
                    val existingAccountData = JSONObject(existingAccountDataStr)
                    
                    // Copy all keys from existing
                    val merged = JSONObject(existingAccountData.toString())
                    
                    // Overwrite/replace with incoming keys
                    val incomingKeys = incomingAccountData.keys()
                    while (incomingKeys.hasNext()) {
                        val key = incomingKeys.next()
                        merged.put(key, incomingAccountData.get(key))
                        Log.d(TAG, "Account data: Merged key '$key' from incoming sync")
                    }
                    
                    merged
                } else {
                    // No existing data, use incoming as-is
                    Log.d(TAG, "Account data: No existing data, using incoming as-is")
                    incomingAccountData
                }
                
                // Store merged account_data
                val mergedAccountDataStr = mergedAccountData.toString()
                accountDataDao.upsert(AccountDataEntity("account_data", mergedAccountDataStr))
                Log.d(TAG, "Persisted merged account_data to database (${mergedAccountDataStr.length} chars, ${mergedAccountData.length()} keys)")
            } catch (e: Exception) {
                Log.e(TAG, "Error merging account_data: ${e.message}", e)
                // Fallback: store incoming as-is if merge fails
                val accountDataStr = incomingAccountData.toString()
                accountDataDao.upsert(AccountDataEntity("account_data", accountDataStr))
                Log.w(TAG, "Stored incoming account_data as-is (merge failed)")
            }
        }
        
        // Store sync metadata
        database.withTransaction {
            // Store last_received_id (request_id from sync_complete)
            syncMetaDao.upsert(SyncMetaEntity("last_received_id", requestId.toString()))
            
            // Store since token if present
            if (since.isNotEmpty()) {
                syncMetaDao.upsert(SyncMetaEntity("since", since))
            }
        }
        
        // Process spaces (top_level_spaces and space_edges)
        processSpaces(data)
        
        // Process rooms
        val roomsJson = data.optJSONObject("rooms")
        if (roomsJson != null) {
            val roomKeys = roomsJson.keys()
            val roomsToProcess = mutableListOf<String>()
            
            while (roomKeys.hasNext()) {
                roomsToProcess.add(roomKeys.next())
            }
            
            // Process all rooms in a single transaction for consistency
            database.withTransaction {
                for (roomId in roomsToProcess) {
                    val roomObj = roomsJson.optJSONObject(roomId) ?: continue
                    processRoom(roomId, roomObj)
                }
            }
            
            Log.d(TAG, "Ingested sync_complete: $requestId, ${roomsToProcess.size} rooms, since=$since")
        } else {
            Log.d(TAG, "Ingested sync_complete: $requestId, 0 rooms (no rooms object)")
        }
    }
    
    /**
     * Process a single room from sync_complete
     */
    private suspend fun processRoom(roomId: String, roomObj: JSONObject) {
        // 1. Process room state (meta)
        val meta = roomObj.optJSONObject("meta")
        if (meta != null) {
            // Detect if this is a direct message room
            // Primary method: dm_user_id field in meta (most reliable)
            val dmUserId = meta.optString("dm_user_id")?.takeIf { it.isNotBlank() }
            val isDirect = dmUserId != null
            
            // CRITICAL FIX: Load existing room state to preserve values when not present in sync
            val existingState = roomStateDao.get(roomId)
            var isFavourite = existingState?.isFavourite ?: false
            var isLowPriority = existingState?.isLowPriority ?: false
            
            // Extract tags from account_data.m.tag (isFavourite, isLowPriority)
            // Only update if account_data.m.tag is actually present in the sync message
            val accountData = roomObj.optJSONObject("account_data")
            if (accountData != null) {
                val tagData = accountData.optJSONObject("m.tag")
                if (tagData != null) {
                    val content = tagData.optJSONObject("content")
                    if (content != null) {
                        val tags = content.optJSONObject("tags")
                        if (tags != null) {
                            // Only update tags if m.tag is present (explicit tag update)
                            isFavourite = tags.has("m.favourite")
                            isLowPriority = tags.has("m.lowpriority")
                            Log.d(TAG, "Room $roomId: Updated tags from account_data - isFavourite=$isFavourite, isLowPriority=$isLowPriority")
                        }
                    }
                }
                // If account_data exists but m.tag is not present, preserve existing tags
            } else {
                // If account_data is not present at all, preserve existing tags
                Log.d(TAG, "Room $roomId: No account_data in sync, preserving existing tags - isFavourite=$isFavourite, isLowPriority=$isLowPriority")
            }
            
            // Extract bridge info if present (from room state events)
            // Bridge info is typically stored separately, but we can check if there's a bridge event
            var bridgeInfoJson: String? = existingState?.bridgeInfoJson
            // Note: Bridge info is typically loaded from room state events separately
            // We'll store it here if we have it in the sync data
            
            // Preserve existing values if not present in meta (for nullable fields)
            val roomState = RoomStateEntity(
                roomId = roomId,
                name = meta.optString("name").takeIf { it.isNotBlank() } ?: existingState?.name,
                topic = meta.optString("topic").takeIf { it.isNotBlank() } ?: existingState?.topic,
                avatarUrl = meta.optString("avatar").takeIf { it.isNotBlank() } ?: existingState?.avatarUrl,
                canonicalAlias = meta.optString("canonical_alias").takeIf { it.isNotBlank() } ?: existingState?.canonicalAlias,
                isDirect = isDirect,
                isFavourite = isFavourite,
                isLowPriority = isLowPriority,
                bridgeInfoJson = bridgeInfoJson,
                updatedAt = System.currentTimeMillis()
            )
            roomStateDao.upsert(roomState)
        }
        
        // 2. Process timeline events
        val timeline = roomObj.optJSONArray("timeline")
        if (timeline != null) {
            val events = mutableListOf<EventEntity>()
            for (i in 0 until timeline.length()) {
                val timelineEntry = timeline.optJSONObject(i) ?: continue
                val rowid = timelineEntry.optLong("rowid", -1)
                val eventJson = timelineEntry.optJSONObject("event") ?: continue
                
                val eventEntity = parseEventFromJson(roomId, eventJson, rowid)
                if (eventEntity != null) {
                    events.add(eventEntity)
                }
            }
            
            if (events.isNotEmpty()) {
                eventDao.upsertAll(events)
            }
        }
        
        // 3. Process events array (preview/additional events)
        val eventsArray = roomObj.optJSONArray("events")
        if (eventsArray != null) {
            val events = mutableListOf<EventEntity>()
            for (i in 0 until eventsArray.length()) {
                val eventJson = eventsArray.optJSONObject(i) ?: continue
                
                // Try to get timeline_rowid from event if available, otherwise use -1
                val timelineRowid = eventJson.optLong("timeline_rowid", -1)
                
                val eventEntity = parseEventFromJson(roomId, eventJson, timelineRowid)
                if (eventEntity != null) {
                    events.add(eventEntity)
                }
            }
            
            if (events.isNotEmpty()) {
                eventDao.upsertAll(events)
            }
        }
        
        // 4. Process receipts
        val receiptsJson = roomObj.optJSONObject("receipts")
        if (receiptsJson != null) {
            val receipts = mutableListOf<ReceiptEntity>()
            val receiptKeys = receiptsJson.keys()
            
            while (receiptKeys.hasNext()) {
                val eventId = receiptKeys.next()
                val receiptArray = receiptsJson.optJSONArray(eventId) ?: continue
                
                for (i in 0 until receiptArray.length()) {
                    val receiptJson = receiptArray.optJSONObject(i) ?: continue
                    val userId = receiptJson.optString("user_id") ?: continue
                    val data = receiptJson.optJSONObject("data")
                    val timestamp = data?.optLong("ts", 0L) ?: 0L
                    val type = data?.optString("type") ?: "m.read"
                    
                    receipts.add(
                        ReceiptEntity(
                            userId = userId,
                            eventId = eventId,
                            roomId = roomId,
                            timestamp = timestamp,
                            type = type
                        )
                    )
                }
            }
            
            if (receipts.isNotEmpty()) {
                receiptDao.upsertAll(receipts)
            }
        }
        
        // 5. Update room summary (last message, unread counts, etc.)
        val unreadMessages = meta?.optInt("unread_messages", 0) ?: 0
        val unreadHighlights = meta?.optInt("unread_highlights", 0) ?: 0
        
        // Find last message event for preview
        var lastEventId: String? = null
        var lastTimestamp: Long = 0L
        var messageSender: String? = null
        var messagePreview: String? = null
        
        // Check timeline first
        if (timeline != null && timeline.length() > 0) {
            // Timeline is in chronological order, last entry is newest
            for (i in timeline.length() - 1 downTo 0) {
                val timelineEntry = timeline.optJSONObject(i) ?: continue
                val eventJson = timelineEntry.optJSONObject("event") ?: continue
                
                val eventType = eventJson.optString("type")
                if (eventType == "m.room.message" || eventType == "m.room.encrypted") {
                    lastEventId = eventJson.optString("event_id")
                    lastTimestamp = eventJson.optLong("origin_server_ts", 0L)
                    messageSender = eventJson.optString("sender")
                    
                    // Extract message preview
                    val content = eventJson.optJSONObject("content")
                    if (content != null) {
                        messagePreview = content.optString("body")
                    } else {
                        // Check decrypted content for encrypted messages
                        val decrypted = eventJson.optJSONObject("decrypted")
                        messagePreview = decrypted?.optString("body")
                    }
                    break
                }
            }
        }
        
        // Fallback to events array if no message in timeline
        if (lastEventId == null && eventsArray != null) {
            for (i in eventsArray.length() - 1 downTo 0) {
                val eventJson = eventsArray.optJSONObject(i) ?: continue
                val eventType = eventJson.optString("type")
                if (eventType == "m.room.message" || eventType == "m.room.encrypted") {
                    lastEventId = eventJson.optString("event_id")
                    lastTimestamp = eventJson.optLong("origin_server_ts", 0L)
                    messageSender = eventJson.optString("sender")
                    
                    val content = eventJson.optJSONObject("content")
                    messagePreview = content?.optString("body") ?: 
                        eventJson.optJSONObject("decrypted")?.optString("body")
                    break
                }
            }
        }
        
        val summary = RoomSummaryEntity(
            roomId = roomId,
            lastEventId = lastEventId,
            lastTimestamp = lastTimestamp,
            unreadCount = unreadMessages,
            highlightCount = unreadHighlights,
            messageSender = messageSender,
            messagePreview = messagePreview
        )
        roomSummaryDao.upsert(summary)
    }
    
    /**
     * Parse an event JSON into EventEntity
     */
    private fun parseEventFromJson(roomId: String, eventJson: JSONObject, timelineRowid: Long): EventEntity? {
        val eventId = eventJson.optString("event_id") ?: return null
        if (eventId.isBlank()) return null
        
        val type = eventJson.optString("type") ?: return null
        val sender = eventJson.optString("sender") ?: ""
        val timestamp = eventJson.optLong("origin_server_ts", 0L)
        val decryptedType = eventJson.optString("decrypted_type")
        
        // Extract relates_to for edits/reactions
        val content = eventJson.optJSONObject("content")
        val relatesTo = content?.optJSONObject("m.relates_to")
        var relatesToEventId = relatesTo?.optString("event_id")
        
        // Check if this is a redaction
        val isRedaction = type == "m.room.redaction"
        
        // For redactions, the redacted event ID is in content.redacts, not m.relates_to
        if (isRedaction && relatesToEventId == null) {
            relatesToEventId = content?.optString("redacts")?.takeIf { it.isNotBlank() }
        }
        
        // Extract thread root event ID (from m.in_reply_to or m.thread_relation)
        var threadRootEventId: String? = null
        if (relatesTo != null) {
            val inReplyTo = relatesTo.optJSONObject("m.in_reply_to")
            if (inReplyTo != null) {
                threadRootEventId = inReplyTo.optString("event_id")?.takeIf { it.isNotBlank() }
            } else {
                val threadRelation = relatesTo.optJSONObject("m.thread_relation")
                threadRootEventId = threadRelation?.optString("event_id")?.takeIf { it.isNotBlank() }
            }
        }
        
        // Store raw JSON for future-proofing (handles schema changes)
        val rawJson = eventJson.toString()
        
        return EventEntity(
            eventId = eventId,
            roomId = roomId,
            timelineRowId = timelineRowid,
            timestamp = timestamp,
            type = type,
            sender = sender,
            decryptedType = if (decryptedType != null && decryptedType.isNotBlank()) decryptedType else null,
            relatesToEventId = if (relatesToEventId != null && relatesToEventId.isNotBlank()) relatesToEventId else null,
            threadRootEventId = threadRootEventId,
            isRedaction = isRedaction,
            rawJson = rawJson
        )
    }
    
    /**
     * Get stored last_received_id (request_id from last sync_complete)
     */
    suspend fun getLastReceivedId(): Int = withContext(Dispatchers.IO) {
        val stored = syncMetaDao.get("last_received_id") ?: "0"
        stored.toIntOrNull() ?: 0
    }
    
    /**
     * Get stored since token
     */
    suspend fun getSinceToken(): String = withContext(Dispatchers.IO) {
        syncMetaDao.get("since") ?: ""
    }
    
    /**
     * Persist paginated events to database (from paginate responses)
     * This is separate from sync_complete ingestion since paginate responses have a different structure
     */
    suspend fun persistPaginatedEvents(roomId: String, events: List<TimelineEvent>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        
        try {
            val eventEntities = events.mapNotNull { event ->
                parseEventFromTimelineEvent(roomId, event)
            }
            
            if (eventEntities.isNotEmpty()) {
                database.withTransaction {
                    eventDao.upsertAll(eventEntities)
                }
                Log.d(TAG, "Persisted ${eventEntities.size} paginated events for room $roomId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting paginated events for room $roomId: ${e.message}", e)
        }
    }
    
    /**
     * Parse TimelineEvent to EventEntity
     */
    private fun parseEventFromTimelineEvent(roomId: String, event: TimelineEvent): EventEntity? {
        val eventId = event.eventId
        if (eventId.isBlank()) return null
        
        // Store raw JSON - we need to reconstruct it from TimelineEvent
        // For now, create a minimal JSON representation
        val rawJson = try {
            val json = org.json.JSONObject()
            json.put("event_id", eventId)
            json.put("type", event.type)
            json.put("sender", event.sender)
            json.put("origin_server_ts", event.timestamp)
            if (event.timelineRowid > 0) {
                json.put("timeline_rowid", event.timelineRowid)
            }
            if (event.content != null) {
                json.put("content", event.content)
            }
            if (event.decrypted != null) {
                json.put("decrypted", event.decrypted)
                json.put("decrypted_type", event.decryptedType)
            }
            json.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize event to JSON: ${e.message}")
            return null
        }
        
        // Extract relates_to for edits/reactions
        val content = event.content
        val relatesTo = content?.optJSONObject("m.relates_to")
        val relatesToEventIdString = relatesTo?.optString("event_id")
        val relatesToEventId = if (relatesToEventIdString != null && relatesToEventIdString.isNotBlank()) relatesToEventIdString else null
        
        // Extract thread root event ID
        var threadRootEventId: String? = null
        if (relatesTo != null) {
            val inReplyTo = relatesTo.optJSONObject("m.in_reply_to")
            if (inReplyTo != null) {
                val inReplyToEventId = inReplyTo.optString("event_id")
                threadRootEventId = if (inReplyToEventId != null && inReplyToEventId.isNotBlank()) inReplyToEventId else null
            } else {
                val threadRelation = relatesTo.optJSONObject("m.thread_relation")
                if (threadRelation != null) {
                    val threadEventId = threadRelation.optString("event_id")
                    threadRootEventId = if (threadEventId != null && threadEventId.isNotBlank()) threadEventId else null
                }
            }
        }
        
        val isRedaction = event.type == "m.room.redaction"
        
        return EventEntity(
            eventId = eventId,
            roomId = roomId,
            timelineRowId = event.timelineRowid,
            timestamp = event.timestamp,
            type = event.type,
            sender = event.sender,
            decryptedType = if (event.decryptedType != null && event.decryptedType.isNotBlank()) event.decryptedType else null,
            relatesToEventId = relatesToEventId,
            threadRootEventId = threadRootEventId,
            isRedaction = isRedaction,
            rawJson = rawJson
        )
    }
    
    /**
     * Persist bridge info for a room
     */
    suspend fun persistBridgeInfo(roomId: String, bridgeInfo: net.vrkknn.andromuks.BridgeInfo) = withContext(Dispatchers.IO) {
        try {
            // Serialize bridge info to JSON
            val bridgeJson = JSONObject().apply {
                put("bridgebot", bridgeInfo.bridgebot)
                put("creator", bridgeInfo.creator)
                put("channel", JSONObject().apply {
                    put("avatar_url", bridgeInfo.channel.avatarUrl ?: "")
                    put("displayname", bridgeInfo.channel.displayname)
                    put("id", bridgeInfo.channel.id)
                })
                put("protocol", JSONObject().apply {
                    put("avatar_url", bridgeInfo.protocol.avatarUrl ?: "")
                    put("displayname", bridgeInfo.protocol.displayname)
                    put("external_url", bridgeInfo.protocol.externalUrl ?: "")
                    put("id", bridgeInfo.protocol.id)
                })
            }
            
            // Get existing room state and update bridge info
            val existingState = roomStateDao.get(roomId)
            if (existingState != null) {
                val updatedState = existingState.copy(bridgeInfoJson = bridgeJson.toString())
                roomStateDao.upsert(updatedState)
                Log.d(TAG, "Persisted bridge info for room $roomId")
            } else {
                // Create new room state entry with bridge info
                val newState = RoomStateEntity(
                    roomId = roomId,
                    name = null,
                    topic = null,
                    avatarUrl = null,
                    canonicalAlias = null,
                    isDirect = false,
                    isFavourite = false,
                    isLowPriority = false,
                    bridgeInfoJson = bridgeJson.toString(),
                    updatedAt = System.currentTimeMillis()
                )
                roomStateDao.upsert(newState)
                Log.d(TAG, "Created new room state with bridge info for room $roomId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting bridge info for room $roomId: ${e.message}", e)
        }
    }
    
    /**
     * Process spaces from sync_complete (top_level_spaces and space_edges)
     * 
     * IMPORTANT: Both top_level_spaces and space_edges are complete replacements when present.
     * - If top_level_spaces is present → Replace ALL spaces in DB with only those in the array
     * - If space_edges is present → Replace ALL relationships for each space in the object
     */
    private suspend fun processSpaces(data: JSONObject) {
        try {
            // Process top_level_spaces (complete replacement)
            val topLevelSpaces = data.optJSONArray("top_level_spaces")
            if (topLevelSpaces != null) {
                val spaces = mutableListOf<SpaceEntity>()
                val roomsJson = data.optJSONObject("rooms")
                
                for (i in 0 until topLevelSpaces.length()) {
                    val spaceId = topLevelSpaces.optString(i)
                    if (spaceId.isNotBlank()) {
                        // Get space metadata from rooms data if available
                        val spaceRoomObj = roomsJson?.optJSONObject(spaceId)
                        val meta = spaceRoomObj?.optJSONObject("meta")
                        
                        val space = SpaceEntity(
                            spaceId = spaceId,
                            name = meta?.optString("name")?.takeIf { it.isNotBlank() },
                            avatarUrl = meta?.optString("avatar")?.takeIf { it.isNotBlank() },
                            updatedAt = System.currentTimeMillis()
                        )
                        spaces.add(space)
                    }
                }
                
                // Complete replacement: Delete all existing spaces and insert new ones
                database.withTransaction {
                    spaceDao.deleteAllSpaces()
                    if (spaces.isNotEmpty()) {
                        spaceDao.upsertAll(spaces)
                    }
                    // Also clear all space-room relationships since spaces are being replaced
                    spaceRoomDao.deleteAllSpaceRooms()
                }
                Log.d(TAG, "Replaced all spaces with ${spaces.size} spaces from top_level_spaces")
            }
            
            // Process space_edges (complete replacement per space)
            val spaceEdges = data.optJSONObject("space_edges")
            if (spaceEdges != null) {
                val spaceKeys = spaceEdges.keys()
                val spacesToUpdate = mutableListOf<String>()
                
                while (spaceKeys.hasNext()) {
                    spacesToUpdate.add(spaceKeys.next())
                }
                
                // For each space in space_edges, replace all its relationships
                database.withTransaction {
                    for (spaceId in spacesToUpdate) {
                        // Delete all existing relationships for this space
                        spaceRoomDao.deleteRoomsForSpace(spaceId)
                        
                        // Insert new relationships for this space
                        val childrenArray = spaceEdges.optJSONArray(spaceId) ?: continue
                        val spaceRooms = mutableListOf<SpaceRoomEntity>()
                        
                        for (i in 0 until childrenArray.length()) {
                            val childObj = childrenArray.optJSONObject(i) ?: continue
                            val childId = childObj.optString("child_id") ?: continue
                            
                            if (childId.isNotBlank()) {
                                val spaceRoom = SpaceRoomEntity(
                                    spaceId = spaceId,
                                    childId = childId,
                                    parentEventRowId = childObj.optLong("parent_event_rowid").takeIf { it > 0 },
                                    childEventRowId = childObj.optLong("child_event_rowid").takeIf { it > 0 },
                                    canonical = childObj.optBoolean("canonical", false),
                                    suggested = childObj.optBoolean("suggested", false),
                                    order = childObj.optString("order")?.takeIf { it.isNotBlank() },
                                    updatedAt = System.currentTimeMillis()
                                )
                                spaceRooms.add(spaceRoom)
                            }
                        }
                        
                        if (spaceRooms.isNotEmpty()) {
                            spaceRoomDao.upsertAll(spaceRooms)
                        }
                    }
                }
                
                val totalRelationships = spacesToUpdate.sumOf { spaceId ->
                    spaceEdges.optJSONArray(spaceId)?.length() ?: 0
                }
                Log.d(TAG, "Replaced space-room relationships for ${spacesToUpdate.size} spaces (${totalRelationships} total relationships)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing spaces: ${e.message}", e)
        }
    }
    
    /**
     * Get stored run_id
     */
    suspend fun getStoredRunId(): String = withContext(Dispatchers.IO) {
        syncMetaDao.get("run_id") ?: ""
    }
}

