package net.vrkknn.andromuks.database

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.database.dao.AccountDataDao
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.ReactionDao
import net.vrkknn.andromuks.database.dao.ReceiptDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import net.vrkknn.andromuks.database.entities.AccountDataEntity
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.ReactionEntity
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
    private val reactionDao = database.reactionDao()
    private val roomSummaryDao = database.roomSummaryDao()
    private val syncMetaDao = database.syncMetaDao()
    private val spaceDao = database.spaceDao()
    private val spaceRoomDao = database.spaceRoomDao()
    private val accountDataDao = database.accountDataDao()
    private val inviteDao = database.inviteDao()
    
    private val TAG = "SyncIngestor"
    
    private data class EventPersistCandidate(
        val entity: EventEntity,
        val source: String
    )
    
    private fun logEventPersisted(
        roomId: String,
        eventId: String,
        source: String,
        type: String,
        timelineRowId: Long
    ) {
        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "SyncIngestor: Persisted event (room=$roomId, eventId=$eventId, type=$type, timelineRowId=$timelineRowId, source=$source)"
        )
    }
    
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
        if (BuildConfig.DEBUG) Log.d(TAG, "Clearing all persisted sync data")
        database.withTransaction {
            // Clear all events, room states, summaries, receipts
            eventDao.deleteAll()
            roomStateDao.deleteAll()
            roomSummaryDao.deleteAll()
            receiptDao.deleteAll()
            reactionDao.clearAll()
            
            // Clear spaces and space-room relationships
            spaceRoomDao.deleteAllSpaceRooms()
            spaceDao.deleteAllSpaces()
            
            // Clear account data
            accountDataDao.deleteAll()
            
            // Clear sync metadata except run_id (which we just set)
            syncMetaDao.upsert(SyncMetaEntity("last_received_id", "0"))
            syncMetaDao.upsert(SyncMetaEntity("since", ""))
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleared all sync data: events, room states, summaries, receipts, spaces, account_data")
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
                        if (BuildConfig.DEBUG) Log.d(TAG, "Account data: Merged key '$key' from incoming sync")
                    }
                    
                    merged
                } else {
                    // No existing data, use incoming as-is
                    if (BuildConfig.DEBUG) Log.d(TAG, "Account data: No existing data, using incoming as-is")
                    incomingAccountData
                }
                
                // Store merged account_data
                val mergedAccountDataStr = mergedAccountData.toString()
                accountDataDao.upsert(AccountDataEntity("account_data", mergedAccountDataStr))
                if (BuildConfig.DEBUG) Log.d(TAG, "Persisted merged account_data to database (${mergedAccountDataStr.length} chars, ${mergedAccountData.length()} keys)")
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
        
        // Process left_rooms - delete all data for rooms we've left
        val leftRooms = data.optJSONArray("left_rooms")
        if (leftRooms != null && leftRooms.length() > 0) {
            val leftRoomIds = mutableListOf<String>()
            for (i in 0 until leftRooms.length()) {
                val roomId = leftRooms.optString(i)
                if (roomId.isNotBlank()) {
                    leftRoomIds.add(roomId)
                }
            }
            
            if (leftRoomIds.isNotEmpty()) {
                // Delete all data for left rooms in a single transaction
                database.withTransaction {
                    for (roomId in leftRoomIds) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Deleting data for left room: $roomId (events, state, summaries, receipts, reactions)")
                        eventDao.deleteAllForRoom(roomId)
                        roomStateDao.deleteForRoom(roomId)
                        roomSummaryDao.deleteForRoom(roomId)
                        receiptDao.deleteForRoom(roomId)
                        reactionDao.clearRoom(roomId)
                        // Also delete invite if it exists
                        inviteDao.deleteInvite(roomId)
                    }
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "Deleted all data for ${leftRoomIds.size} left rooms: ${leftRoomIds.joinToString(", ")}")
            }
        }
        
        // Process rooms
        val roomsJson = data.optJSONObject("rooms")
        if (roomsJson != null) {
            val roomKeys = roomsJson.keys()
            val roomsToProcess = mutableListOf<String>()
            
            while (roomKeys.hasNext()) {
                roomsToProcess.add(roomKeys.next())
            }
            
            // Process all rooms in a single transaction for consistency
            // CRITICAL: Load all existing room states BEFORE processing to preserve bridge info
            database.withTransaction {
                // Pre-load all existing room states to preserve bridge info during processing
                val existingStatesMap = roomStateDao.getAllRoomStates().associateBy { it.roomId }
                
                for (roomId in roomsToProcess) {
                    val roomObj = roomsJson.optJSONObject(roomId) ?: continue
                    processRoom(roomId, roomObj, existingStatesMap[roomId])
                }
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Ingested sync_complete: $requestId, ${roomsToProcess.size} rooms, since=$since")
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ingested sync_complete: $requestId, 0 rooms (no rooms object)")
        }
    }
    
    /**
     * Process a single room from sync_complete
     */
    private suspend fun processRoom(roomId: String, roomObj: JSONObject, existingState: RoomStateEntity? = null) {
        val existingTimelineRowCache = mutableMapOf<String, Long?>()
        
        // 1. Process room state (meta)
        val meta = roomObj.optJSONObject("meta")
        if (meta != null) {
            // Detect if this is a direct message room
            // Primary method: dm_user_id field in meta (most reliable)
            val dmUserId = meta.optString("dm_user_id")?.takeIf { it.isNotBlank() }
            val isDirect = dmUserId != null
            
            // CRITICAL FIX: Use pre-loaded existing state (passed as parameter) to preserve values when not present in sync
            // This ensures bridge info is preserved even if database queries happen in parallel
            val currentExistingState = existingState ?: roomStateDao.get(roomId)
            var isFavourite = currentExistingState?.isFavourite ?: false
            var isLowPriority = currentExistingState?.isLowPriority ?: false
            
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
                            if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId: Updated tags from account_data - isFavourite=$isFavourite, isLowPriority=$isLowPriority")
                        }
                    }
                }
                // If account_data exists but m.tag is not present, preserve existing tags
            } else {
                // If account_data is not present at all, preserve existing tags
                if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId: No account_data in sync, preserving existing tags - isFavourite=$isFavourite, isLowPriority=$isLowPriority")
            }
            
            // CRITICAL: Preserve bridge info from existing state
            // Bridge info is loaded separately from room state events and should NEVER be overwritten by sync data
            // Only update bridgeInfoJson if it's explicitly provided in sync data (which it never is)
            var bridgeInfoJson: String? = currentExistingState?.bridgeInfoJson
            
            // CRITICAL FIX: ALWAYS re-check database inside transaction to get the latest bridge info
            // This handles race conditions where:
            // 1. Bridge info was persisted after the pre-loaded states map was created
            // 2. Bridge info exists in DB but wasn't in the pre-loaded map (e.g., loaded from DB after sync started)
            // 3. Multiple sync_complete messages arrive and we need the latest state
            // We ALWAYS re-check, not just when bridgeInfoJson is null, to ensure we have the most up-to-date value
            val recheckedState = roomStateDao.get(roomId)
            val recheckedBridgeInfo = recheckedState?.bridgeInfoJson
            
            // Use re-checked bridge info if it exists and is not empty
            // This ensures we always have the latest bridge info from the database
            if (recheckedBridgeInfo != null && recheckedBridgeInfo.isNotBlank()) {
                bridgeInfoJson = recheckedBridgeInfo
                if (BuildConfig.DEBUG && (currentExistingState?.bridgeInfoJson != recheckedBridgeInfo)) {
                    Log.d(TAG, "Room $roomId: Updated bridge info from re-check (pre-loaded: ${currentExistingState?.bridgeInfoJson != null}, re-checked: $recheckedBridgeInfo)")
                }
            } else if (bridgeInfoJson != null && bridgeInfoJson.isNotBlank()) {
                // Bridge info exists in pre-loaded state but not in re-check - preserve it
                // This handles the case where re-check returns null but we had bridge info in pre-loaded map
                if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId: Preserving bridge info from pre-loaded state (re-check returned null/empty)")
            } else {
                // No bridge info found - this is OK, room might not be bridged
                if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId: No bridge info found (pre-loaded: ${currentExistingState?.bridgeInfoJson != null}, re-checked: ${recheckedBridgeInfo != null})")
            }
            
            // Note: Bridge info is loaded from room state events separately via persistBridgeInfo()
            // Sync data should NEVER overwrite existing bridge info - always preserve it
            
            // Preserve existing values if not present in meta (for nullable fields)
            val roomState = RoomStateEntity(
                roomId = roomId,
                name = meta.optString("name").takeIf { it.isNotBlank() } ?: currentExistingState?.name,
                topic = meta.optString("topic").takeIf { it.isNotBlank() } ?: currentExistingState?.topic,
                avatarUrl = meta.optString("avatar").takeIf { it.isNotBlank() } ?: currentExistingState?.avatarUrl,
                canonicalAlias = meta.optString("canonical_alias").takeIf { it.isNotBlank() } ?: currentExistingState?.canonicalAlias,
                isDirect = isDirect,
                isFavourite = isFavourite,
                isLowPriority = isLowPriority,
                bridgeInfoJson = bridgeInfoJson, // CRITICAL: Always preserve existing bridge info (including from re-check)
                updatedAt = System.currentTimeMillis()
            )
            roomStateDao.upsert(roomState)
            
            // CRITICAL: Log bridge info preservation for debugging
            if (BuildConfig.DEBUG) {
                if (bridgeInfoJson != null && bridgeInfoJson.isNotBlank()) {
                    Log.d(TAG, "Room $roomId: Preserved bridge info during sync processing (${bridgeInfoJson.length} chars)")
                } else if (currentExistingState?.bridgeInfoJson != null && currentExistingState.bridgeInfoJson.isNotBlank()) {
                    // WARNING: We had bridge info in pre-loaded state but lost it!
                    Log.w(TAG, "Room $roomId: WARNING - Had bridge info in pre-loaded state but lost it during sync processing!")
                }
            }
        }
        
        val reactionUpserts = mutableMapOf<String, ReactionEntity>()
        val reactionDeletes = mutableSetOf<String>()
        
        // 2. Process timeline events
        val timeline = roomObj.optJSONArray("timeline")
        if (timeline != null) {
            val events = mutableListOf<EventPersistCandidate>()
            for (i in 0 until timeline.length()) {
                val timelineEntry = timeline.optJSONObject(i) ?: continue
                val rowid = timelineEntry.optLong("rowid", -1)
                val eventJson = timelineEntry.optJSONObject("event") ?: continue
                
                collectReactionPersistenceFromEvent(roomId, eventJson, reactionUpserts, reactionDeletes)
                
                val sourceLabel = "timeline[$i]"
                val eventEntity = parseEventFromJson(
                    roomId = roomId,
                    eventJson = eventJson,
                    timelineRowid = rowid,
                    source = sourceLabel,
                    existingTimelineRowCache = existingTimelineRowCache
                )
                if (eventEntity != null) {
                    events.add(EventPersistCandidate(eventEntity, sourceLabel))
                }
            }
            
            if (events.isNotEmpty()) {
                eventDao.upsertAll(events.map { it.entity })
                events.forEach { candidate ->
                    logEventPersisted(
                        roomId = roomId,
                        eventId = candidate.entity.eventId,
                        source = candidate.source,
                        type = candidate.entity.type,
                        timelineRowId = candidate.entity.timelineRowId
                    )
                }
            }
        }
        
        // 3. Process events array (preview/additional events)
        val eventsArray = roomObj.optJSONArray("events")
        if (eventsArray != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Processing ${eventsArray.length()} events from 'events' array for room $roomId")
            val events = mutableListOf<EventPersistCandidate>()
            var skippedCount = 0
            for (i in 0 until eventsArray.length()) {
                val eventJson = eventsArray.optJSONObject(i) ?: continue
                
                collectReactionPersistenceFromEvent(roomId, eventJson, reactionUpserts, reactionDeletes)
                
                // Try to get timeline_rowid from event if available, otherwise use -1
                val timelineRowid = eventJson.optLong("timeline_rowid", -1)
                
                val sourceLabel = "events[$i]"
                val eventEntity = parseEventFromJson(
                    roomId = roomId,
                    eventJson = eventJson,
                    timelineRowid = timelineRowid,
                    source = sourceLabel,
                    existingTimelineRowCache = existingTimelineRowCache
                )
                if (eventEntity != null) {
                    events.add(EventPersistCandidate(eventEntity, sourceLabel))
                    if (BuildConfig.DEBUG && eventEntity.type == "m.room.encrypted") {
                        if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Parsed encrypted event ${eventEntity.eventId} with timestamp=${eventEntity.timestamp}, timelineRowId=${eventEntity.timelineRowId}")
                    }
                } else {
                    skippedCount++
                    val eventId = eventJson.optString("event_id", "unknown")
                    val eventType = eventJson.optString("type", "unknown")
                    Log.w(TAG, "SyncIngestor: Failed to parse event from 'events' array: eventId=$eventId, type=$eventType")
                }
            }
            
            if (events.isNotEmpty()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Persisting ${events.size} events from 'events' array for room $roomId (skipped $skippedCount)")
                eventDao.upsertAll(events.map { it.entity })
                events.forEach { candidate ->
                    logEventPersisted(
                        roomId = roomId,
                        eventId = candidate.entity.eventId,
                        source = candidate.source,
                        type = candidate.entity.type,
                        timelineRowId = candidate.entity.timelineRowId
                    )
                }
            } else if (eventsArray.length() > 0) {
                Log.w(TAG, "SyncIngestor: No events were parsed from 'events' array for room $roomId (all ${eventsArray.length()} events were skipped)")
            }
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: No 'events' array found for room $roomId")
        }
        
        if (reactionDeletes.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "SyncIngestor: Deleting ${reactionDeletes.size} reactions (sync) -> ${reactionDeletes.joinToString()}"
            )
            reactionDao.deleteByEventIds(reactionDeletes.toList())
        }
        if (reactionUpserts.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "SyncIngestor: Upserting ${reactionUpserts.size} reactions (sync) -> ${
                    reactionUpserts.values.joinToString { it.eventId }
                }"
            )
            reactionDao.upsertAll(reactionUpserts.values.toList())
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
    private suspend fun parseEventFromJson(
        roomId: String,
        eventJson: JSONObject,
        timelineRowid: Long,
        source: String,
        existingTimelineRowCache: MutableMap<String, Long?>
    ): EventEntity? {
        val eventIdRaw = eventJson.opt("event_id")
        if (eventIdRaw !is String || eventIdRaw.isBlank()) {
            Log.w(
                TAG,
                "SyncIngestor: Skipping event (room=$roomId, source=$source) - missing event_id. Payload keys=${eventJson.names()}"
            )
            return null
        }
        val eventId = eventIdRaw
        
        val typeRaw = eventJson.opt("type")
        if (typeRaw !is String || typeRaw.isBlank()) {
            Log.w(
                TAG,
                "SyncIngestor: Skipping event (room=$roomId, source=$source, eventId=$eventId) - missing type."
            )
            return null
        }
        val type = typeRaw
        val sender = eventJson.optString("sender") ?: ""
        val timestamp = eventJson.optLong("origin_server_ts", 0L)
        val decryptedType = eventJson.optString("decrypted_type")
        
        var resolvedTimelineRowId = when {
            timelineRowid > 0 -> timelineRowid
            eventJson.has("timeline_row_id") -> eventJson.optLong("timeline_row_id").takeIf { it > 0 }
            eventJson.has("rowid") -> eventJson.optLong("rowid").takeIf { it > 0 }
            else -> null
        } ?: -1L
        
        if (resolvedTimelineRowId <= 0) {
            val cachedValue = existingTimelineRowCache[eventId]
            val preservedRowId = if (cachedValue != null) {
                cachedValue
            } else {
                val existing = eventDao.getEventById(roomId, eventId)
                existingTimelineRowCache[eventId] = existing?.timelineRowId
                existing?.timelineRowId
            }
            if (preservedRowId != null && preservedRowId > 0) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "SyncIngestor: Preserving timelineRowId $preservedRowId for event $eventId (source=$source)"
                    )
                }
                resolvedTimelineRowId = preservedRowId
            }
        } else {
            existingTimelineRowCache[eventId] = resolvedTimelineRowId
        }
        
        // Extract relates_to for edits/reactions
        // For encrypted messages, check decrypted content if available
        val content = eventJson.optJSONObject("content")
        val messageContent = when {
            type == "m.room.message" -> content
            type == "m.room.encrypted" && decryptedType == "m.room.message" -> {
                eventJson.optJSONObject("decrypted") ?: content
            }
            else -> content
        }
        val relatesTo = messageContent?.optJSONObject("m.relates_to")
        val relType = relatesTo?.optString("rel_type")
        val isThreadMessage = relType == "m.thread"
        
        // Check if this is a redaction
        val isRedaction = type == "m.room.redaction"
        
        // Extract thread root and relates_to based on message type
        var threadRootEventId: String? = null
        var relatesToEventId: String? = null
        
        if (isThreadMessage && relatesTo != null) {
            // For thread messages:
            // - threadRootEventId = m.relates_to.event_id (the original thread root)
            // - relatesToEventId = m.relates_to.m.in_reply_to.event_id (the previous message in thread)
            threadRootEventId = relatesTo.optString("event_id")?.takeIf { it.isNotBlank() }
            val inReplyTo = relatesTo.optJSONObject("m.in_reply_to")
            relatesToEventId = inReplyTo?.optString("event_id")?.takeIf { it.isNotBlank() }
        } else if (relatesTo != null) {
            // For non-thread replies/edits/reactions:
            // - relatesToEventId = m.relates_to.event_id (the message being replied to/edited/reacted to)
            relatesToEventId = relatesTo.optString("event_id")?.takeIf { it.isNotBlank() }
        }
        
        // For redactions, the redacted event ID is in content.redacts, not m.relates_to
        if (isRedaction && relatesToEventId == null) {
            relatesToEventId = messageContent?.optString("redacts")?.takeIf { it.isNotBlank() }
        }
        
        // Include aggregated reactions (if any) in persisted JSON
        val reactionsObj = eventJson.optJSONObject("reactions") ?: content?.optJSONObject("reactions")
        val aggregatedReactionsJson = reactionsObj?.toString()
        val rawJson = if (reactionsObj != null) {
            try {
                val jsonCopy = JSONObject(eventJson.toString())
                if (!jsonCopy.has("reactions")) {
                    jsonCopy.put("reactions", reactionsObj)
                }
                val ensuredContent = jsonCopy.optJSONObject("content") ?: JSONObject().also {
                    jsonCopy.put("content", it)
                }
                if (!ensuredContent.has("reactions")) {
                    ensuredContent.put("reactions", reactionsObj)
                }
                jsonCopy.toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject aggregated reactions into rawJson for $eventId: ${e.message}")
                eventJson.toString()
            }
        } else {
            eventJson.toString()
        }
        if (aggregatedReactionsJson != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Detected aggregated reactions for event $eventId -> $aggregatedReactionsJson")
        }
        
        return EventEntity(
            eventId = eventId,
            roomId = roomId,
            timelineRowId = resolvedTimelineRowId,
            timestamp = timestamp,
            type = type,
            sender = sender,
            decryptedType = if (decryptedType != null && decryptedType.isNotBlank()) decryptedType else null,
            relatesToEventId = if (relatesToEventId != null && relatesToEventId.isNotBlank()) relatesToEventId else null,
            threadRootEventId = threadRootEventId,
            isRedaction = isRedaction,
            rawJson = rawJson,
            aggregatedReactionsJson = aggregatedReactionsJson
        )
    }
    
    private fun collectReactionPersistenceFromEvent(
        roomId: String,
        eventJson: JSONObject,
        reactionUpserts: MutableMap<String, ReactionEntity>,
        reactionDeletes: MutableSet<String>
    ) {
        val eventId = eventJson.optString("event_id")
        if (eventId.isNullOrBlank()) return
        
        when (eventJson.optString("type")) {
            "m.reaction" -> {
                val content = eventJson.optJSONObject("content") ?: return
                val relatesTo = content.optJSONObject("m.relates_to")
                if (relatesTo == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping reaction $eventId - missing m.relates_to")
                    return
                }
                if (relatesTo.optString("rel_type") != "m.annotation") {
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping reaction $eventId - rel_type='${relatesTo.optString("rel_type")}'")
                    return
                }
                
                val targetEventId = relatesTo.optString("event_id")
                val key = relatesTo.optString("key")
                if (targetEventId.isBlank() || key.isBlank()) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping reaction $eventId - missing target/key (target='$targetEventId', key='$key')")
                    return
                }
                
                val redactedBy = eventJson.optString("redacted_by")
                val unsigned = eventJson.optJSONObject("unsigned")
                val redactedBecause = unsigned?.optJSONObject("redacted_because")
                if (redactedBy.isNotBlank() || redactedBecause != null) {
                    reactionDeletes.add(eventId)
                    reactionUpserts.remove(eventId)
                    return
                }
                
                val sender = eventJson.optString("sender")
                val originTs = if (eventJson.has("origin_server_ts")) {
                    eventJson.optLong("origin_server_ts", 0L)
                } else {
                    0L
                }
                val fallbackTs = if (eventJson.has("timestamp")) {
                    eventJson.optLong("timestamp", 0L)
                } else {
                    0L
                }
                val timestamp = when {
                    originTs > 0 -> originTs
                    fallbackTs > 0 -> fallbackTs
                    else -> System.currentTimeMillis()
                }
                
                reactionDeletes.remove(eventId)
                reactionUpserts[eventId] = ReactionEntity(
                    roomId = roomId,
                    targetEventId = targetEventId,
                    key = key,
                    sender = sender,
                    eventId = eventId,
                    timestamp = timestamp
                )
                if (BuildConfig.DEBUG) Log.d(
                    TAG,
                    "SyncIngestor: Queued reaction upsert from JSON eventId=$eventId target=$targetEventId key=$key sender=$sender ts=$timestamp"
                )
            }
            "m.room.redaction" -> {
                val redacts = eventJson.optJSONObject("content")?.optString("redacts")
                if (!redacts.isNullOrBlank()) {
                    reactionDeletes.add(redacts)
                    reactionUpserts.remove(redacts)
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Queued reaction delete due to JSON redaction of $redacts")
                }
            }
            "m.room.message" -> {
                val reactionsObj = eventJson.optJSONObject("content")?.optJSONObject("reactions")
                if (reactionsObj != null && reactionsObj.length() > 0) {
                    val keys = reactionsObj.keys().asSequence().joinToString()
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Message $eventId contains aggregated reactions [$keys] (expecting individual m.reaction events)")
                }
            }
        }
    }
    
    private fun collectReactionPersistenceFromTimelineEvent(
        roomId: String,
        event: TimelineEvent,
        reactionUpserts: MutableMap<String, ReactionEntity>,
        reactionDeletes: MutableSet<String>
    ) {
        when (event.type) {
            "m.reaction" -> {
                val content = event.content
                if (content == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping timeline reaction ${event.eventId} - missing content")
                    return
                }
                val relatesTo = content.optJSONObject("m.relates_to")
                if (relatesTo == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping timeline reaction ${event.eventId} - missing m.relates_to")
                    return
                }
                if (relatesTo.optString("rel_type") != "m.annotation") {
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping timeline reaction ${event.eventId} - rel_type='${relatesTo.optString("rel_type")}'")
                    return
                }
                
                val targetEventId = relatesTo.optString("event_id")
                val key = relatesTo.optString("key")
                if (targetEventId.isBlank() || key.isBlank()) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping timeline reaction ${event.eventId} - missing target/key (target='$targetEventId', key='$key')")
                    return
                }
                
                val redactedBecause = event.unsigned?.optJSONObject("redacted_because")
                if (!event.redactedBy.isNullOrBlank() || redactedBecause != null) {
                    reactionDeletes.add(event.eventId)
                    reactionUpserts.remove(event.eventId)
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Skipping timeline reaction ${event.eventId} - redacted (by='${event.redactedBy}', because=${redactedBecause != null})")
                    return
                }
                
                reactionDeletes.remove(event.eventId)
                val reactionTimestamp = if (event.timestamp > 0) {
                    event.timestamp
                } else {
                    val unsignedTs = event.unsigned?.optLong("age_ts") ?: 0L
                    if (unsignedTs > 0) unsignedTs else System.currentTimeMillis()
                }
                
                reactionUpserts[event.eventId] = ReactionEntity(
                    roomId = roomId,
                    targetEventId = targetEventId,
                    key = key,
                    sender = event.sender,
                    eventId = event.eventId,
                    timestamp = reactionTimestamp
                )
                if (BuildConfig.DEBUG) Log.d(
                    TAG,
                    "SyncIngestor: Queued reaction upsert from timeline eventId=${event.eventId} target=$targetEventId key=$key sender=${event.sender} ts=$reactionTimestamp"
                )
            }
            "m.room.redaction" -> {
                val redacts = event.content?.optString("redacts")
                if (!redacts.isNullOrBlank()) {
                    reactionDeletes.add(redacts)
                    reactionUpserts.remove(redacts)
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Queued reaction delete due to timeline redaction of $redacts")
                }
            }
            "m.room.message" -> {
                val reactionsObj = event.content?.optJSONObject("reactions")
                if (reactionsObj != null && reactionsObj.length() > 0) {
                    val keys = reactionsObj.keys().asSequence().joinToString()
                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Timeline message ${event.eventId} contains aggregated reactions [$keys] (expecting individual m.reaction events)")
                }
            }
        }
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
            val reactionUpserts = mutableMapOf<String, ReactionEntity>()
            val reactionDeletes = mutableSetOf<String>()
            val candidates = mutableListOf<EventPersistCandidate>()
            val existingTimelineRowCache = mutableMapOf<String, Long?>()
            for ((index, event) in events.withIndex()) {
                collectReactionPersistenceFromTimelineEvent(roomId, event, reactionUpserts, reactionDeletes)
                val sourceLabel = "paginate[$index]"
                val entity = parseEventFromTimelineEvent(
                    roomId = roomId,
                    event = event,
                    source = sourceLabel,
                    existingTimelineRowCache = existingTimelineRowCache
                )
                if (entity != null) {
                    candidates.add(EventPersistCandidate(entity, sourceLabel))
                }
            }
            
            if (candidates.isNotEmpty() || reactionUpserts.isNotEmpty() || reactionDeletes.isNotEmpty()) {
                database.withTransaction {
                    if (candidates.isNotEmpty()) {
                        eventDao.upsertAll(candidates.map { it.entity })
                    }
                    if (reactionDeletes.isNotEmpty()) {
                        if (BuildConfig.DEBUG) Log.d(
                            TAG,
                            "SyncIngestor: Deleting ${reactionDeletes.size} reactions -> ${reactionDeletes.joinToString()}"
                        )
                        reactionDao.deleteByEventIds(reactionDeletes.toList())
                    }
                    if (reactionUpserts.isNotEmpty()) {
                        if (BuildConfig.DEBUG) Log.d(
                            TAG,
                            "SyncIngestor: Upserting ${reactionUpserts.size} reactions -> ${
                                reactionUpserts.values.joinToString { it.eventId }
                            }"
                        )
                        reactionDao.upsertAll(reactionUpserts.values.toList())
                    }
                }
                candidates.forEach { candidate ->
                    logEventPersisted(
                        roomId = roomId,
                        eventId = candidate.entity.eventId,
                        source = candidate.source,
                        type = candidate.entity.type,
                        timelineRowId = candidate.entity.timelineRowId
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting paginated events for room $roomId: ${e.message}", e)
        }
    }
    
    /**
     * Parse TimelineEvent to EventEntity
     */
    private suspend fun parseEventFromTimelineEvent(
        roomId: String,
        event: TimelineEvent,
        source: String,
        existingTimelineRowCache: MutableMap<String, Long?>
    ): EventEntity? {
        val eventId = event.eventId
        if (eventId.isBlank()) {
            Log.w(
                TAG,
                "SyncIngestor: Skipping timeline event (room=$roomId, source=$source) - missing event_id."
            )
            return null
        }
        
        // Store raw JSON - reconstruct it from TimelineEvent with all fields
        // CRITICAL: Must include unsigned field (contains prev_content for state events like m.room.pinned_events)
        val rawJson = try {
            val json = org.json.JSONObject()
            json.put("event_id", eventId)
            json.put("type", event.type)
            json.put("sender", event.sender)
            json.put("origin_server_ts", event.timestamp)
            if (event.timelineRowid > 0) {
                json.put("timeline_rowid", event.timelineRowid)
            }
            if (event.stateKey != null) {
                json.put("state_key", event.stateKey)
            }
            if (event.content != null) {
                json.put("content", event.content)
            }
            if (event.decrypted != null) {
                json.put("decrypted", event.decrypted)
                if (event.decryptedType != null) {
                    json.put("decrypted_type", event.decryptedType)
                }
            }
            // CRITICAL: Include unsigned field - contains prev_content needed for state event diffs
            if (event.unsigned != null) {
                json.put("unsigned", event.unsigned)
            }
            if (event.redactedBy != null) {
                json.put("redacted_by", event.redactedBy)
            }
            if (event.localContent != null) {
                json.put("local_content", event.localContent)
            }
            if (event.relationType != null) {
                json.put("relation_type", event.relationType)
            }
            if (event.relatesTo != null) {
                json.put("relates_to", event.relatesTo)
            }
            json.toString()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "SyncIngestor: Skipping timeline event (room=$roomId, source=$source, eventId=$eventId) - failed to serialize JSON: ${e.message}"
            )
            return null
        }
        
        var resolvedTimelineRowId = when {
            event.timelineRowid > 0 -> event.timelineRowid
            event.rowid > 0 -> event.rowid
            else -> -1L
        }
        if (resolvedTimelineRowId <= 0) {
            val cachedValue = existingTimelineRowCache[eventId]
            val preservedRowId = if (cachedValue != null) {
                cachedValue
            } else {
                val existing = eventDao.getEventById(roomId, eventId)
                existingTimelineRowCache[eventId] = existing?.timelineRowId
                existing?.timelineRowId
            }
            if (preservedRowId != null && preservedRowId > 0) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "SyncIngestor: Preserving timelineRowId $preservedRowId for timeline event $eventId (source=$source)"
                    )
                }
                resolvedTimelineRowId = preservedRowId
            }
        } else {
            existingTimelineRowCache[eventId] = resolvedTimelineRowId
        }
        
        // Extract relates_to for edits/reactions
        // For encrypted messages, check decrypted content if available
        val messageContent = when {
            event.type == "m.room.message" -> event.content
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted
            else -> event.content
        }
        val relatesTo = messageContent?.optJSONObject("m.relates_to")
        val relType = relatesTo?.optString("rel_type")
        val isThreadMessage = relType == "m.thread"
        
        // Extract thread root and relates_to based on message type
        var threadRootEventId: String? = null
        var relatesToEventId: String? = null
        
        if (isThreadMessage && relatesTo != null) {
            // For thread messages:
            // - threadRootEventId = m.relates_to.event_id (the original thread root)
            // - relatesToEventId = m.relates_to.m.in_reply_to.event_id (the previous message in thread)
            threadRootEventId = relatesTo.optString("event_id")?.takeIf { it.isNotBlank() }
            val inReplyTo = relatesTo.optJSONObject("m.in_reply_to")
            relatesToEventId = inReplyTo?.optString("event_id")?.takeIf { it.isNotBlank() }
        } else if (relatesTo != null) {
            // For non-thread replies/edits/reactions:
            // - relatesToEventId = m.relates_to.event_id (the message being replied to/edited/reacted to)
            relatesToEventId = relatesTo.optString("event_id")?.takeIf { it.isNotBlank() }
        }
        
        val isRedaction = event.type == "m.room.redaction"
        
        // For redactions, the redacted event ID is in content.redacts, not m.relates_to
        if (isRedaction && relatesToEventId == null) {
            relatesToEventId = messageContent?.optString("redacts")?.takeIf { it.isNotBlank() }
        }
        val reactionsObj = event.aggregatedReactions ?: event.content?.optJSONObject("reactions")
        val aggregatedReactionsJson = reactionsObj?.toString()
        if (aggregatedReactionsJson != null) {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "SyncIngestor: Detected aggregated reactions in timeline for event ${event.eventId} -> $aggregatedReactionsJson")
        }
        
        return EventEntity(
            eventId = eventId,
            roomId = roomId,
            timelineRowId = resolvedTimelineRowId,
            timestamp = event.timestamp,
            type = event.type,
            sender = event.sender,
            decryptedType = if (event.decryptedType != null && event.decryptedType.isNotBlank()) event.decryptedType else null,
            relatesToEventId = relatesToEventId,
            threadRootEventId = threadRootEventId,
            isRedaction = isRedaction,
            rawJson = rawJson,
            aggregatedReactionsJson = aggregatedReactionsJson
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
            
            // Use transaction to ensure atomicity and prevent race conditions with sync data
            database.withTransaction {
                // Get existing room state and update bridge info
                // CRITICAL: Re-read state inside transaction to get latest value (prevents race with sync data)
                val existingState = roomStateDao.get(roomId)
                if (existingState != null) {
                    // CRITICAL: Check if bridge info already exists - if it does, verify it matches
                    // This prevents overwriting bridge info that was just persisted
                    if (existingState.bridgeInfoJson != null && existingState.bridgeInfoJson.isNotBlank()) {
                        // Bridge info already exists - verify it's the same or update if different
                        try {
                            val existingBridgeObj = JSONObject(existingState.bridgeInfoJson)
                            val existingProtocolId = existingBridgeObj.optJSONObject("protocol")?.optString("id")
                            val newProtocolId = bridgeInfo.protocol.id
                            
                            if (existingProtocolId == newProtocolId) {
                                // Same bridge info - no need to update
                                if (BuildConfig.DEBUG) Log.d(TAG, "Bridge info for room $roomId already exists with same protocol ($newProtocolId) - skipping update")
                                return@withTransaction
                            } else {
                                // Different bridge info - update it
                                if (BuildConfig.DEBUG) Log.d(TAG, "Bridge info for room $roomId changed from $existingProtocolId to $newProtocolId - updating")
                            }
                        } catch (e: Exception) {
                            // Existing bridge info is invalid JSON - update it
                            if (BuildConfig.DEBUG) Log.w(TAG, "Existing bridge info for room $roomId is invalid JSON - updating")
                        }
                    }
                    
                    // Preserve all existing fields, only update bridgeInfoJson
                    val updatedState = existingState.copy(
                        bridgeInfoJson = bridgeJson.toString(),
                        updatedAt = System.currentTimeMillis()
                    )
                    roomStateDao.upsert(updatedState)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Persisted bridge info for room $roomId (updated existing state) - bridgeInfoJson length: ${bridgeJson.toString().length}")
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
                    if (BuildConfig.DEBUG) Log.d(TAG, "Created new room state with bridge info for room $roomId - bridgeInfoJson length: ${bridgeJson.toString().length}")
                }
                
                // CRITICAL: Verify persistence by reading back from database
                val verifyState = roomStateDao.get(roomId)
                if (verifyState?.bridgeInfoJson != null && verifyState.bridgeInfoJson.isNotBlank()) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Verified bridge info persistence for room $roomId - DB contains ${verifyState.bridgeInfoJson.length} chars")
                } else {
                    Log.e(TAG, "CRITICAL: Bridge info NOT found in DB after persistence for room $roomId!")
                }
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Replaced all spaces with ${spaces.size} spaces from top_level_spaces")
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Replaced space-room relationships for ${spacesToUpdate.size} spaces (${totalRelationships} total relationships)")
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

