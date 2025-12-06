package net.vrkknn.andromuks.database

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.database.dao.AccountDataDao
import net.vrkknn.andromuks.database.dao.EventDao
import net.vrkknn.andromuks.database.dao.PendingRoomDao
import net.vrkknn.andromuks.database.dao.ReactionDao
import net.vrkknn.andromuks.database.dao.ReceiptDao
import net.vrkknn.andromuks.database.dao.RoomStateDao
import net.vrkknn.andromuks.database.dao.RoomSummaryDao
import net.vrkknn.andromuks.database.dao.SpaceDao
import net.vrkknn.andromuks.database.dao.SpaceRoomDao
import net.vrkknn.andromuks.database.dao.SyncMetaDao
import net.vrkknn.andromuks.database.entities.AccountDataEntity
import net.vrkknn.andromuks.database.entities.EventEntity
import net.vrkknn.andromuks.database.entities.PendingRoomEntity
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
    private val pendingRoomDao = database.pendingRoomDao()
    
    private val TAG = "SyncIngestor"
    
    companion object {
        // BATTERY OPTIMIZATION: Track pending receipts for deferred processing
        // Key: roomId, Value: List of ReceiptEntity
        private val pendingReceipts = mutableMapOf<String, MutableList<ReceiptEntity>>()
        
        // Lock for thread-safe access to pending receipts
        @JvmStatic
        private val pendingReceiptsLock = Any()
        
        // Adaptive threshold for pending rooms (starts high, reduces if processing takes too long)
        // This prevents massive payload accumulation while adapting to device performance
        @Volatile
        private var pendingRoomThreshold = 200 // Start with 200 rooms threshold
        
        // Minimum threshold (never go below this)
        private const val MIN_THRESHOLD = 50
        
        // Maximum threshold (never go above this)
        private const val MAX_THRESHOLD = 500
        
        // Processing time threshold in milliseconds (if processing takes longer, reduce threshold)
        private const val PROCESSING_TIME_THRESHOLD_MS = 1000L // 1 second
        
        // Threshold adjustment factor (reduce by this percentage if processing too slow)
        private const val THRESHOLD_REDUCTION_FACTOR = 0.7f // Reduce by 30%
    }
    
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
     * Check if run_id has changed and update it if needed
     * 
     * CRITICAL: We do NOT clear any data when run_id changes because:
     * 1. Logout is not supported in this app - user data should always persist
     * 2. run_id change just means backend restarted or connection reset
     * 3. All user data (events, rooms, account_data, etc.) should persist across connection resets
     * 
     * Returns true if run_id changed, false otherwise
     */
    suspend fun checkAndHandleRunIdChange(newRunId: String): Boolean = withContext(Dispatchers.IO) {
        val storedRunId = syncMetaDao.get("run_id") ?: ""
        
        if (storedRunId.isNotEmpty() && storedRunId != newRunId) {
            Log.w(TAG, "Run ID changed from '$storedRunId' to '$newRunId' - updating run_id (preserving all user data)")
            // Just update the run_id - do NOT clear any data
            // All user data (events, rooms, account_data, etc.) should persist
            syncMetaDao.upsert(SyncMetaEntity("run_id", newRunId))
            return@withContext true
        } else if (storedRunId.isEmpty()) {
            // First time - store run_id
            syncMetaDao.upsert(SyncMetaEntity("run_id", newRunId))
        }
        
        return@withContext false
    }
    
    /**
     * Ingest a sync_complete message into the database
     * 
     * @param syncJson The sync_complete JSON object
     * @param requestId The request_id from the sync_complete (used as last_received_id)
     * @param runId The current run_id (must match stored run_id or data will be cleared)
     * @param isAppVisible Whether the app is currently visible (affects processing optimizations)
     * @return Set of room IDs that had events persisted (for notifying timeline screens)
     */
    suspend fun ingestSyncComplete(
        syncJson: JSONObject,
        requestId: Int,
        runId: String,
        isAppVisible: Boolean = true
    ): Set<String> = withContext(Dispatchers.IO) {
        // Track which rooms had events persisted (for notifying timeline screens)
        val roomsWithEvents = mutableSetOf<String>()
        
        // Check run_id first - this is critical!
        val runIdChanged = checkAndHandleRunIdChange(runId)
        if (runIdChanged) {
            Log.w(TAG, "Run ID changed - updated run_id, preserving all user data and ingesting sync")
        }
        
        val data = syncJson.optJSONObject("data") ?: run {
            Log.w(TAG, "No 'data' field in sync_complete")
            return@withContext emptySet<String>()
        }
        
        // Extract "since" token (sync token from server)
        val since = data.optString("since", "")
        
        // Process account_data if present (must be done before other processing)
        // IMPORTANT: Partial updates - only replace keys present in incoming sync, merge with existing
        // BATTERY OPTIMIZATION: account_data can be large (50KB+), so we:
        // 1. Use efficient JSON copying (manual key copy instead of toString/parse)
        // 2. Skip DB write if nothing changed (detect during merge)
        // NOTE: account_data is only sent when changed (not on every sync), so this optimization
        // is most useful when account_data appears frequently or when the JSON is very large
        val incomingAccountData = data.optJSONObject("account_data")
        if (incomingAccountData != null) {
            try {
                // Load existing account_data from database
                val existingAccountDataStr = accountDataDao.getAccountData()
                if (existingAccountDataStr != null) {
                    // Merge: existing + incoming (incoming keys replace existing keys)
                    val existingAccountData = JSONObject(existingAccountDataStr)
                    
                    // BATTERY OPTIMIZATION: Manual key copy is more efficient than toString() + parse
                    // This avoids serializing/parsing the entire 50KB+ JSON object twice
                    val merged = JSONObject()
                    
                    // Copy all keys from existing JSON object (more efficient than toString/parse)
                    val existingKeys = existingAccountData.keys()
                    while (existingKeys.hasNext()) {
                        val key = existingKeys.next()
                        merged.put(key, existingAccountData.get(key))
                    }
                    
                    // Overwrite/replace with incoming keys and detect if anything changed
                    var hasChanges = false
                    val incomingKeys = incomingAccountData.keys()
                    while (incomingKeys.hasNext()) {
                        val key = incomingKeys.next()
                        val incomingValue = incomingAccountData.get(key)
                        val existingValue = merged.opt(key)
                        
                        // Check if value actually changed (avoid unnecessary serialization/write)
                        // Compare JSONObject values: null/JSONObject.NULL means key doesn't exist
                        val valueChanged = when {
                            existingValue == null || existingValue == JSONObject.NULL -> true // New key
                            else -> {
                                // Key exists, check if value changed
                                incomingValue.toString() != existingValue.toString()
                            }
                        }
                        
                        if (valueChanged) {
                            merged.put(key, incomingValue)
                            hasChanges = true
                            if (BuildConfig.DEBUG) Log.d(TAG, "Account data: Merged key '$key' from incoming sync")
                        }
                    }
                    
                    // BATTERY OPTIMIZATION: Skip DB write if nothing changed
                    if (hasChanges) {
                        val mergedAccountDataStr = merged.toString()
                        accountDataDao.upsert(AccountDataEntity("account_data", mergedAccountDataStr))
                        if (BuildConfig.DEBUG) Log.d(TAG, "Persisted merged account_data to database (${mergedAccountDataStr.length} chars, ${merged.length()} keys)")
                    } else {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Account data: No changes detected, skipping DB write")
                    }
                } else {
                    // No existing data, use incoming as-is
                    if (BuildConfig.DEBUG) Log.d(TAG, "Account data: No existing data, using incoming as-is")
                    val accountDataStr = incomingAccountData.toString()
                    accountDataDao.upsert(AccountDataEntity("account_data", accountDataStr))
                    if (BuildConfig.DEBUG) Log.d(TAG, "Persisted initial account_data to database (${accountDataStr.length} chars, ${incomingAccountData.length()} keys)")
                }
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
                        // Also delete pending room if it exists
                        pendingRoomDao.deletePendingRoom(roomId)
                    }
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "Deleted all data for ${leftRoomIds.size} left rooms: ${leftRoomIds.joinToString(", ")}")
            }
        }
        
        // BATTERY OPTIMIZATION: Process any pending rooms from previous syncs (if app was killed)
        // This ensures rooms are not lost even if app is killed while backgrounded
        val pendingRooms = pendingRoomDao.getAllPendingRooms()
        if (pendingRooms.isNotEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SyncIngestor: Found ${pendingRooms.size} pending rooms from previous syncs, processing them now")
            }
            
            // Get room IDs from current sync to avoid duplicate processing
            val currentSyncRoomIds = data.optJSONObject("rooms")?.keys()?.asSequence()?.toSet() ?: emptySet()
            
            // Process pending rooms that are NOT in current sync (they'll be processed from current sync)
            val pendingRoomsToProcess = pendingRooms.filter { it.roomId !in currentSyncRoomIds }
            
            if (pendingRoomsToProcess.isNotEmpty()) {
                database.withTransaction {
                    val existingStatesMap = if (pendingRoomsToProcess.isNotEmpty()) {
                        roomStateDao.getRoomStatesByIds(pendingRoomsToProcess.map { it.roomId }).associateBy { it.roomId }
                    } else {
                        emptyMap()
                    }
                    
                    for (pendingRoom in pendingRoomsToProcess) {
                        try {
                            val roomObj = JSONObject(pendingRoom.roomJson)
                            val hadEvents = processRoom(pendingRoom.roomId, roomObj, existingStatesMap[pendingRoom.roomId], isAppVisible)
                            if (hadEvents) {
                                roomsWithEvents.add(pendingRoom.roomId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "SyncIngestor: Error processing pending room ${pendingRoom.roomId}: ${e.message}", e)
                        }
                    }
                    
                    // Delete processed pending rooms
                    pendingRoomDao.deletePendingRooms(pendingRoomsToProcess.map { it.roomId })
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SyncIngestor: Processed ${pendingRoomsToProcess.size} pending rooms")
                }
            }
            
            // Delete pending rooms that ARE in current sync (they'll be processed from current sync with fresh data)
            val pendingRoomsInCurrentSync = pendingRooms.filter { it.roomId in currentSyncRoomIds }
            if (pendingRoomsInCurrentSync.isNotEmpty()) {
                pendingRoomDao.deletePendingRooms(pendingRoomsInCurrentSync.map { it.roomId })
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SyncIngestor: Deleted ${pendingRoomsInCurrentSync.size} pending rooms (present in current sync with fresh data)")
                }
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
            
            // BATTERY OPTIMIZATION: Adaptive deferred processing when backgrounded
            // When backgrounded: Check pending count, if threshold reached, process all (pending + current)
            // Otherwise: Defer all to pending DB for later processing
            var thresholdReached = false
            val roomsToProcessNow = if (isAppVisible) {
                // Foreground: Process all rooms immediately
                roomsToProcess
            } else {
                // Background: Check pending count threshold
                val currentPendingCount = pendingRoomDao.getPendingCount()
                
                if (currentPendingCount >= pendingRoomThreshold) {
                    // Threshold reached - process all pending rooms + current sync rooms
                    thresholdReached = true
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "SyncIngestor: Pending room threshold reached ($currentPendingCount >= $pendingRoomThreshold) - processing all pending + current sync rooms")
                    }
                    
                    // Load all pending rooms to process
                    val pendingRooms = pendingRoomDao.getAllPendingRooms()
                    val pendingRoomIds = pendingRooms.map { it.roomId }.toSet()
                    
                    // Combine pending rooms + current sync rooms (avoid duplicates)
                    val allRoomIds = (pendingRoomIds + roomsToProcess.toSet()).toList()
                    
                    // Process all in a single transaction with time tracking
                    val processingStartTime = System.currentTimeMillis()
                    database.withTransaction {
                        val existingStatesMap = if (allRoomIds.isNotEmpty()) {
                            roomStateDao.getRoomStatesByIds(allRoomIds).associateBy { it.roomId }
                        } else {
                            emptyMap()
                        }
                        
                        // Process pending rooms first
                        for (pendingRoom in pendingRooms) {
                            try {
                                val roomObj = JSONObject(pendingRoom.roomJson)
                                val hadEvents = processRoom(pendingRoom.roomId, roomObj, existingStatesMap[pendingRoom.roomId], isAppVisible)
                                if (hadEvents) {
                                    roomsWithEvents.add(pendingRoom.roomId)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "SyncIngestor: Error processing pending room ${pendingRoom.roomId}: ${e.message}", e)
                            }
                        }
                        
                        // Process current sync rooms
                        for (roomId in roomsToProcess) {
                            if (roomId !in pendingRoomIds) { // Skip if already processed as pending
                                val roomObj = roomsJson.optJSONObject(roomId) ?: continue
                                val hadEvents = processRoom(roomId, roomObj, existingStatesMap[roomId], isAppVisible)
                                if (hadEvents) {
                                    roomsWithEvents.add(roomId)
                                }
                            }
                        }
                        
                        // Delete all processed pending rooms
                        pendingRoomDao.deleteAll()
                    }
                    
                    val processingTime = System.currentTimeMillis() - processingStartTime
                    
                    // Adaptive threshold adjustment: If processing took too long, reduce threshold (like GC)
                    if (processingTime > PROCESSING_TIME_THRESHOLD_MS) {
                        val oldThreshold = pendingRoomThreshold
                        pendingRoomThreshold = (pendingRoomThreshold * THRESHOLD_REDUCTION_FACTOR).toInt().coerceAtLeast(MIN_THRESHOLD)
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "SyncIngestor: Processing took ${processingTime}ms (threshold: ${PROCESSING_TIME_THRESHOLD_MS}ms) - reducing pending room threshold from $oldThreshold to $pendingRoomThreshold")
                        }
                    }
                    
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "SyncIngestor: Processed ${pendingRooms.size} pending + ${roomsToProcess.size} current rooms in ${processingTime}ms (threshold: ${pendingRoomThreshold})")
                    }
                    
                    // All processed - return empty list since we already processed everything
                    emptyList()
                } else {
                    // Under threshold - defer all rooms to pending DB
                    emptyList()
                }
            }
            
            // If rooms are being deferred, store them to pending DB
            val roomsToDefer = if (isAppVisible || thresholdReached) {
                emptyList() // No deferral when foregrounded or when threshold was reached (already processed)
            } else {
                roomsToProcess // Defer all when backgrounded and under threshold
            }
            
            if (roomsToDefer.isNotEmpty()) {
                // BATTERY OPTIMIZATION: Persist all rooms to database for processing later
                // This ensures rooms are not lost if app is killed
                val pendingRooms = roomsToDefer.mapNotNull { roomId ->
                    val roomObj = roomsJson.optJSONObject(roomId) ?: return@mapNotNull null
                    PendingRoomEntity(
                        roomId = roomId,
                        roomJson = roomObj.toString(),
                        timestamp = System.currentTimeMillis()
                    )
                }
                
                if (pendingRooms.isNotEmpty()) {
                    database.withTransaction {
                        pendingRoomDao.upsertAll(pendingRooms)
                    }
                    val pendingCount = pendingRoomDao.getPendingCount()
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "SyncIngestor: Background deferred ${pendingRooms.size} rooms to pending DB (total pending: $pendingCount / threshold: $pendingRoomThreshold)")
                    }
                }
            }
            
            // Process rooms that should be processed now (foreground, or threshold processing already handled above)
            if (roomsToProcessNow.isNotEmpty()) {
                // Process rooms in a single transaction for consistency
                // BATTERY OPTIMIZATION: Only load room states for rooms actually in sync_complete (not all 588 rooms)
                // getRoomStatesByIds() queries only the rooms being processed (typically 2-3 rooms per sync)
                // This is much more efficient than getAllRoomStates() which would load all 588 rooms
                database.withTransaction {
                    // Load existing room states only for rooms being processed in this batch
                    // roomsToProcessNow contains only rooms in sync_complete JSON, not all known rooms
                    val existingStatesMap = if (roomsToProcessNow.isNotEmpty()) {
                        roomStateDao.getRoomStatesByIds(roomsToProcessNow).associateBy { it.roomId }
                    } else {
                        emptyMap()
                    }
                    
                    // BATTERY OPTIMIZATION: processRoom() is only called for rooms in sync_complete JSON (typically 2-3 rooms)
                    // It does NOT process all 588 rooms - only incremental changes from sync_complete
                    for (roomId in roomsToProcessNow) {
                        val roomObj = roomsJson.optJSONObject(roomId) ?: continue
                        val hadEvents = processRoom(roomId, roomObj, existingStatesMap[roomId], isAppVisible)
                        if (hadEvents) {
                            roomsWithEvents.add(roomId)
                        }
                    }
                }
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Ingested sync_complete: $requestId, ${roomsToProcess.size} rooms, since=$since, ${roomsWithEvents.size} rooms with events")
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ingested sync_complete: $requestId, 0 rooms (no rooms object)")
        }
        
        // Return set of room IDs that had events persisted (for notifying timeline screens)
        roomsWithEvents
    }
    
    /**
     * Process a single room from sync_complete
     * 
     * @param roomId The room ID
     * @param roomObj The room JSON object from sync
     * @param existingState Pre-loaded existing room state (null if new room)
     * @param isAppVisible Whether app is visible (affects summary processing optimization)
     * @return true if events were persisted for this room, false otherwise
     */
    private suspend fun processRoom(roomId: String, roomObj: JSONObject, existingState: RoomStateEntity? = null, isAppVisible: Boolean = true): Boolean {
        val existingTimelineRowCache = mutableMapOf<String, Long?>()
        var hasPersistedEvents = false
        
        // 1. Process room state (meta)
        val meta = roomObj.optJSONObject("meta")
        if (meta != null) {
            // Detect if this is a direct message room
            // Primary method: dm_user_id field in meta (most reliable)
            val dmUserId = meta.optString("dm_user_id")?.takeIf { it.isNotBlank() }
            val isDirect = dmUserId != null
            
            // BATTERY OPTIMIZATION: Use pre-loaded existing state (passed as parameter) to preserve values when not present in sync
            // This preserves name, topic, avatarUrl, canonicalAlias, isFavourite, isLowPriority from existing state
            // existingState is pre-loaded via getRoomStatesByIds() which only queries rooms in sync_complete (not all 588 rooms)
            // Fallback to roomStateDao.get(roomId) only if pre-loading failed (rare case)
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
                bridgeInfoJson = null, // Bridge info not used - kept for schema compatibility
                updatedAt = System.currentTimeMillis()
            )
            roomStateDao.upsert(roomState)
            
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
                hasPersistedEvents = true
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
                hasPersistedEvents = true
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
        // BATTERY OPTIMIZATION: Defer receipt processing when backgrounded
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
                if (isAppVisible) {
                    // Foreground: Process receipts immediately
                    receiptDao.upsertAll(receipts)
                } else {
                    // Background: Defer receipt processing
                    synchronized(pendingReceiptsLock) {
                        val roomReceipts = pendingReceipts.getOrPut(roomId) { mutableListOf() }
                        roomReceipts.addAll(receipts)
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "SyncIngestor: Deferred ${receipts.size} receipts for room $roomId (app backgrounded, total pending: ${roomReceipts.size})")
                        }
                    }
                }
            }
        }
        
        // 5. Update room summary (last message, unread counts, etc.)
        val unreadMessages = meta?.optInt("unread_messages", 0) ?: 0
        val unreadHighlights = meta?.optInt("unread_highlights", 0) ?: 0
        val shouldExtractPreview = isAppVisible
        val existingSummaryForPreview = if (!shouldExtractPreview) {
            roomSummaryDao.getRoomSummary(roomId)
        } else {
            null
        }
        
        // OPTIMIZATION: Query database for last message instead of scanning JSON
        // Events are already persisted in Phases 2-3, so we can query efficiently
        val lastMessageEvent = eventDao.getLastMessageForRoom(roomId)
        
        var lastEventId: String? = null
        var lastTimestamp: Long = 0L
        var messageSender: String? = existingSummaryForPreview?.messageSender
        var messagePreview: String? = existingSummaryForPreview?.messagePreview
        
        if (lastMessageEvent != null) {
            // Use last message from database
            lastEventId = lastMessageEvent.eventId
            lastTimestamp = lastMessageEvent.timestamp
            messageSender = lastMessageEvent.sender ?: messageSender
            
            if (shouldExtractPreview) {
                // Extract message preview from rawJson
                try {
                    val eventJson = JSONObject(lastMessageEvent.rawJson)
                    
                    // E2EE: For encrypted messages (type='m.room.encrypted'), body is in decrypted.content.body
                    // For regular messages (type='m.room.message'), body is in content.body
                    // Backend already decrypts messages, so decrypted content is always available
                    messagePreview = if (lastMessageEvent.type == "m.room.encrypted") {
                        val decrypted = eventJson.optJSONObject("decrypted")
                        decrypted?.optString("body")
                    } else {
                        val content = eventJson.optJSONObject("content")
                        content?.optString("body")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract message preview from rawJson for event ${lastMessageEvent.eventId}: ${e.message}")
                }
            }
        } else {
            // Fallback: Scan sync JSON if database query returned nothing (new room, no messages yet)
            // This should be rare - only for brand new rooms
            if (timeline != null && timeline.length() > 0) {
                for (i in timeline.length() - 1 downTo 0) {
                    val timelineEntry = timeline.optJSONObject(i) ?: continue
                    val eventJson = timelineEntry.optJSONObject("event") ?: continue
                    
                    val eventType = eventJson.optString("type")
                    if (eventType == "m.room.message" || eventType == "m.room.encrypted") {
                        lastEventId = eventJson.optString("event_id")
                        lastTimestamp = eventJson.optLong("origin_server_ts", 0L)
                        messageSender = eventJson.optString("sender")?.takeIf { it.isNotBlank() } ?: messageSender
                        
                        if (shouldExtractPreview) {
                            val content = eventJson.optJSONObject("content")
                            messagePreview = content?.optString("body") ?: eventJson.optJSONObject("decrypted")?.optString("body")
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
                        messageSender = eventJson.optString("sender")?.takeIf { it.isNotBlank() } ?: messageSender
                        
                        if (shouldExtractPreview) {
                            val content = eventJson.optJSONObject("content")
                            messagePreview = content?.optString("body")
                                ?: eventJson.optJSONObject("decrypted")?.optString("body")
                        }
                        break
                    }
                }
            }
        }
        
        if (!shouldExtractPreview && messagePreview.isNullOrBlank()) {
            messagePreview = existingSummaryForPreview?.messagePreview
        }
        if (messageSender.isNullOrBlank()) {
            messageSender = existingSummaryForPreview?.messageSender
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
        
        return hasPersistedEvents
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
                // CRITICAL: Use NonCancellable to ensure database transaction completes even if coroutine is cancelled
                // This prevents data loss when ViewModel is cleared (e.g., when activity is destroyed)
                withContext(NonCancellable) {
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
    
    /**
     * BATTERY OPTIMIZATION: Process all pending receipts and rooms that were deferred when backgrounded
     * Called when app becomes visible to catch up on deferred processing
     */
    suspend fun rushProcessPendingItems() = withContext(Dispatchers.IO) {
        // Process pending rooms first
        val pendingRooms = pendingRoomDao.getAllPendingRooms()
        if (pendingRooms.isNotEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SyncIngestor: Rushing processing of ${pendingRooms.size} pending rooms")
            }
            
            // Track processing time for adaptive threshold adjustment
            val processingStartTime = System.currentTimeMillis()
            
            database.withTransaction {
                val existingStatesMap = if (pendingRooms.isNotEmpty()) {
                    roomStateDao.getRoomStatesByIds(pendingRooms.map { it.roomId }).associateBy { it.roomId }
                } else {
                    emptyMap()
                }
                
                for (pendingRoom in pendingRooms) {
                    try {
                        val roomObj = JSONObject(pendingRoom.roomJson)
                        // Process with isAppVisible = true since we're rushing (app is now visible)
                        processRoom(pendingRoom.roomId, roomObj, existingStatesMap[pendingRoom.roomId], isAppVisible = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "SyncIngestor: Error rushing pending room ${pendingRoom.roomId}: ${e.message}", e)
                    }
                }
                
                // Delete processed pending rooms
                pendingRoomDao.deleteAll()
            }
            
            val processingTime = System.currentTimeMillis() - processingStartTime
            
            // Adaptive threshold adjustment: If processing took too long, reduce threshold (like GC)
            if (processingTime > PROCESSING_TIME_THRESHOLD_MS) {
                val oldThreshold = pendingRoomThreshold
                pendingRoomThreshold = (pendingRoomThreshold * THRESHOLD_REDUCTION_FACTOR).toInt().coerceAtLeast(MIN_THRESHOLD)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SyncIngestor: Rush processing took ${processingTime}ms (threshold: ${PROCESSING_TIME_THRESHOLD_MS}ms) - reducing pending room threshold from $oldThreshold to $pendingRoomThreshold")
                }
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SyncIngestor: Rushed processing ${pendingRooms.size} pending rooms in ${processingTime}ms (threshold: ${pendingRoomThreshold})")
            }
        }
        
        // Process pending receipts
        val receiptsToProcess = synchronized(pendingReceiptsLock) {
            // Get all pending receipts and clear the map
            val allReceipts = pendingReceipts.values.flatten()
            pendingReceipts.clear()
            allReceipts
        }
        
        if (receiptsToProcess.isEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: No pending receipts to process")
            return@withContext
        }
        
        // Group receipts by room for efficient batch upserts
        val receiptsByRoom = receiptsToProcess.groupBy { it.roomId }
        
        database.withTransaction {
            receiptsByRoom.forEach { (roomId, receipts) ->
                receiptDao.upsertAll(receipts)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SyncIngestor: Rushed processing ${receipts.size} pending receipts for room $roomId")
                }
            }
        }
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SyncIngestor: Rushed processing ${receiptsToProcess.size} total pending receipts across ${receiptsByRoom.size} rooms")
        }
    }
    
    /**
     * @deprecated Use rushProcessPendingItems() instead
     */
    @Deprecated("Use rushProcessPendingItems() to process both rooms and receipts")
    suspend fun rushProcessPendingReceipts() = rushProcessPendingItems()
    
    /**
     * Get count of pending receipts (for debugging/monitoring)
     */
    fun getPendingReceiptsCount(): Int {
        return synchronized(pendingReceiptsLock) {
            pendingReceipts.values.sumOf { it.size }
        }
    }
    
    /**
     * Check if there are any pending rooms or receipts to process
     * This is used to determine if RoomListScreen should wait before displaying
     */
    suspend fun hasPendingItems(): Boolean = withContext(Dispatchers.IO) {
        val pendingRoomCount = pendingRoomDao.getPendingCount()
        val pendingReceiptCount = getPendingReceiptsCount()
        val hasPending = pendingRoomCount > 0 || pendingReceiptCount > 0
        if (BuildConfig.DEBUG && hasPending) {
            Log.d(TAG, "SyncIngestor: hasPendingItems = true (rooms: $pendingRoomCount, receipts: $pendingReceiptCount)")
        }
        hasPending
    }
}

