package net.vrkknn.andromuks.database

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * SyncIngestor - Ingests sync_complete messages into in-memory caches
 * 
 * Handles:
 * - Persisting events, room state, receipts from sync_complete
 * - Tracking run_id changes (clears DB if run_id changes)
 * - Storing last_received_id and since token for reconnection
 * - Transaction-based writes for consistency
 */
class SyncIngestor(private val context: Context) {
    // All room/space/account/invite data is in-memory only
    private val TAG = "SyncIngestor"
    
    // SharedPreferences for run_id and since token (replacing syncMetaDao)
    private val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    
    companion object {
        
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
    
    /**
     * Callback interface for notifying ViewModel about events for cached rooms.
     * This allows SyncIngestor to update the LRU cache when new events arrive.
     */
    interface CacheUpdateListener {
        /** Returns set of room IDs currently in the LRU cache */
        fun getCachedRoomIds(): Set<String>
        
        /** 
         * Called when new events arrive for a cached room.
         * Returns true if events were appended, false if cache was invalidated (needs re-render).
         */
        fun onEventsForCachedRoom(roomId: String, events: List<TimelineEvent>, requiresFullRerender: Boolean): Boolean
    }
    
    // Listener for cache updates (set by AppViewModel)
    var cacheUpdateListener: CacheUpdateListener? = null
    

    /**
     * Analyzes a room object from sync_complete and returns a summary of its contents.
     */
    private fun analyzeRoomContents(roomId: String, roomObj: JSONObject): String {
        val timeline = roomObj.optJSONArray("timeline")
        val eventsArray = roomObj.optJSONArray("events")
        val meta = roomObj.optJSONObject("meta")
        val receipts = roomObj.optJSONObject("receipts")
        val accountData = roomObj.optJSONObject("account_data")
        
        val parts = mutableListOf<String>()
        if (timeline != null) {
            parts.add("timeline=${timeline.length()}")
        } else {
            parts.add("timeline=null")
        }
        if (eventsArray != null) {
            parts.add("events=${eventsArray.length()}")
        } else {
            parts.add("events=null")
        }
        if (meta != null) {
            val metaKeys = try {
                val keys = meta.keys().asSequence().toList()
                if (keys.isNotEmpty()) {
                    // Show key count and some important keys if present
                    val importantKeys = keys.filter { it in listOf("name", "avatar", "topic", "unread_messages", "unread_highlights", "sorting_timestamp") }
                    if (importantKeys.isNotEmpty()) {
                        val keyDetails = importantKeys.joinToString(",") { key ->
                            val value = when (key) {
                                "name", "avatar", "topic" -> meta.optString(key)?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "null"
                                "unread_messages", "unread_highlights" -> meta.optInt(key, 0).toString()
                                "sorting_timestamp" -> meta.optLong(key, 0L).toString()
                                else -> "present"
                            }
                            "$key=$value"
                        }
                        "keys=${keys.size}[$keyDetails]"
                    } else {
                        "keys=${keys.size}"
                    }
                } else {
                    "empty"
                }
            } catch (e: Exception) {
                "<error:${e.message}>"
            }
            parts.add("meta=$metaKeys")
        }
        if (receipts != null) {
            val receiptCount = receipts.keys().asSequence().sumOf { 
                receipts.optJSONArray(it)?.length() ?: 0 
            }
            parts.add("receipts=$receiptCount")
        }
        if (accountData != null) {
            val accountDataKeys = try {
                accountData.keys().asSequence().toList()
            } catch (e: Exception) {
                listOf("<error:${e.message}>")
            }
            if (accountDataKeys.isNotEmpty()) {
                parts.add("account_data=[${accountDataKeys.joinToString(",")}]")
            } else {
                parts.add("account_data=empty")
            }
        }
        
        return parts.joinToString(", ")
    }

    private suspend fun logUnprocessedEvent(
        roomId: String?,
        eventJson: JSONObject?,
        source: String,
        reason: String,
        exception: Exception? = null
    ) {
        val eventId = try {
            eventJson?.optString("event_id")?.takeIf { it.isNotBlank() } ?: "<missing>"
        } catch (_: Exception) {
            "<missing>"
        }
        val rawJson = try {
            eventJson?.toString() ?: "<null>"
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
        // No longer persisting unprocessed events - they'll be received again in future sync_complete
        val detailedReason = if (exception != null) "$reason: ${exception.message}" else reason
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "Unprocessed event: roomId=$roomId, eventId=$eventId, source=$source, reason=$detailedReason")
        }
    }

    private fun TimelineEvent.toJsonObject(): JSONObject {
        val json = JSONObject()
        try {
            json.put("event_id", eventId)
            json.put("room_id", roomId)
            json.put("sender", sender)
            json.put("type", type)
            json.put("origin_server_ts", timestamp)
            if (timelineRowid > 0) json.put("timeline_rowid", timelineRowid)
            if (rowid > 0) json.put("rowid", rowid)
            stateKey?.let { json.put("state_key", it) }
            content?.let { json.put("content", it) }
            decrypted?.let { json.put("decrypted", it) }
            decryptedType?.let { json.put("decrypted_type", it) }
            unsigned?.let { json.put("unsigned", it) }
            redactedBy?.let { json.put("redacted_by", it) }
            localContent?.let { json.put("local_content", it) }
            relationType?.let { json.put("relation_type", it) }
            relatesTo?.let { json.put("relates_to", it) }
            aggregatedReactions?.let { json.put("reactions", it) }
            transactionId?.let { json.put("transaction_id", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build JSON for unprocessed TimelineEvent ${eventId}", e)
        }
        return json
    }
    
    /**
     * Compress pending room JSON before storing to shrink rows and avoid CursorWindow limits.
     * Falls back to raw JSON if compression fails.
     */
    private fun compressPendingRoomJson(rawJson: String): String {
        return try {
            val byteStream = ByteArrayOutputStream()
            GZIPOutputStream(byteStream).use { it.write(rawJson.toByteArray(Charsets.UTF_8)) }
            Base64.encodeToString(byteStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compress pending room JSON, storing raw", e)
            rawJson
        }
    }
    
    /**
     * Decompress pending room JSON; if the payload is not compressed, return as-is.
     */
    private fun decompressPendingRoomJson(stored: String): String {
        return try {
            val compressed = Base64.decode(stored, Base64.NO_WRAP)
            GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // Not compressed (legacy rows) or failed to decode; use raw content
            stored
        }
    }

    /**
     * Merge a newly deferred room JSON with an existing pending payload to avoid overwriting
     * older events when multiple syncs are deferred for the same room.
     * - timeline/events arrays are de-duplicated by event_id (existing order preserved, new appended)
     * - other top-level keys from the new payload overwrite existing ones
     */
    private fun mergePendingRoomJson(existingCompressed: String, newRoomObj: JSONObject): String {
        return try {
            val existingObj = JSONObject(decompressPendingRoomJson(existingCompressed))
            val merged = JSONObject(existingObj.toString())

            fun mergeArray(key: String) {
                val existingArr = existingObj.optJSONArray(key)
                val newArr = newRoomObj.optJSONArray(key)
                if (existingArr == null && newArr == null) return

                val seen = mutableSetOf<String>()
                val out = mutableListOf<JSONObject>()

                fun addFrom(array: JSONArray?) {
                    if (array == null) return
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val evt = obj.optJSONObject("event") ?: obj
                        val eventId = evt.optString("event_id")
                        if (eventId.isNullOrBlank()) {
                            out.add(JSONObject(obj.toString()))
                            continue
                        }
                        if (seen.add(eventId)) {
                            out.add(JSONObject(obj.toString()))
                        }
                    }
                }

                addFrom(existingArr)
                addFrom(newArr)

                merged.put(key, JSONArray(out))
            }

            // Merge arrays that carry events
            mergeArray("timeline")
            mergeArray("events")

            // Overwrite/merge other keys from new payload
            val newKeys = newRoomObj.keys()
            while (newKeys.hasNext()) {
                val key = newKeys.next()
                if (key == "timeline" || key == "events") continue
                merged.put(key, newRoomObj.get(key))
            }

            compressPendingRoomJson(merged.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to merge pending room JSON, storing new payload only: ${e.message}")
            compressPendingRoomJson(newRoomObj.toString())
        }
    }
    
    /**
     * @deprecated No longer using pending rooms - all data is in-memory only
     */
    @Deprecated("All data is in-memory only, no pending rooms to load")
    private suspend fun loadPendingRoomsSafely(): List<Any> {
        return emptyList() // No pending rooms - all data is in-memory only
    }
    
    /**
     * Check if run_id has changed and update it if needed
     * 
     * CRITICAL: We do NOT clear any data when run_id changes because:
     * 1. Logout is not supported in this app - user data should always persist
     * 2. run_id change just means backend restarted or connection reset
     * 3. All user data is in-memory only now
     * 
     * Returns true if run_id changed, false otherwise
     */
    suspend fun checkAndHandleRunIdChange(newRunId: String): Boolean = withContext(Dispatchers.IO) {
        val storedRunId = sharedPrefs.getString("ws_run_id", "") ?: ""
        
        if (storedRunId.isNotEmpty() && storedRunId != newRunId) {
            Log.w(TAG, "Run ID changed from '$storedRunId' to '$newRunId' - updating run_id")
            sharedPrefs.edit().putString("ws_run_id", newRunId).apply()
            return@withContext true
        } else if (storedRunId.isEmpty()) {
            // First time - store run_id
            sharedPrefs.edit().putString("ws_run_id", newRunId).apply()
        }
        
        return@withContext false
    }
    
    /**
     * Clear all derived room/space state when server sends clear_state=true.
     * All data is now in-memory only - nothing to clear from DB.
     */
    suspend fun handleClearStateSignal() = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) Log.w(TAG, "SyncIngestor: clear_state=true received - all data is in-memory only, nothing to clear from DB")
        // All room/space/invite/account data is in-memory only - no DB cleanup needed
    }
    
    /**
     * Result of ingesting a sync_complete message
     */
    data class IngestResult(
        val roomsWithEvents: Set<String>,
        val invites: List<net.vrkknn.andromuks.RoomInvite>
    )
    
    /**
     * Ingest a sync_complete message - updates cache for cached rooms only, no DB persistence
     * 
     * @param syncJson The sync_complete JSON object
     * @param requestId The request_id from the sync_complete (no longer stored)
     * @param runId The current run_id (must match stored run_id or data will be cleared)
     * @param isAppVisible Whether the app is currently visible (affects processing optimizations)
     * @return IngestResult containing rooms with events and parsed invites
     */
    suspend fun ingestSyncComplete(
        syncJson: JSONObject,
        requestId: Int,
        runId: String,
        isAppVisible: Boolean = true
    ): IngestResult = withContext(Dispatchers.IO) {
        // Track which rooms had events added to cache (for notifying timeline screens)
        val roomsWithEvents = mutableSetOf<String>()
        
        // Check which rooms are currently cached before processing
        val cachedRoomIds = cacheUpdateListener?.getCachedRoomIds() ?: emptySet()
        
        // Check run_id first - this is critical!
        val runIdChanged = checkAndHandleRunIdChange(runId)
        if (runIdChanged) {
            Log.w(TAG, "Run ID changed - updated run_id, preserving all user data and ingesting sync")
        }
        
        val data = syncJson.optJSONObject("data") ?: run {
            Log.w(TAG, "No 'data' field in sync_complete")
            return@withContext IngestResult(emptySet(), emptyList())
        }
        
        // If server instructs us to clear state, all data is in-memory only - nothing to clear
        val clearState = data.optBoolean("clear_state", false)
        if (clearState) {
            handleClearStateSignal()
        }
        
        // Extract "since" token (sync token from server) - store in SharedPreferences
        val since = data.optString("since", "")
        if (since.isNotEmpty()) {
            sharedPrefs.edit().putString("ws_since_token", since).apply()
        }
        
        // Account data and spaces are now in-memory only - processed by SpaceRoomParser
        // Invites are parsed and returned (in-memory only, no DB persistence)
        val invites = processInvitedRooms(data)
        
        // Process left_rooms - all data is in-memory only, no DB cleanup needed
        val leftRooms = data.optJSONArray("left_rooms")
        if (leftRooms != null && leftRooms.length() > 0) {
            val leftRoomIds = mutableListOf<String>()
            for (i in 0 until leftRooms.length()) {
                val roomId = leftRooms.optString(i)
                if (roomId.isNotBlank()) {
                    leftRoomIds.add(roomId)
                }
            }
            if (leftRoomIds.isNotEmpty() && BuildConfig.DEBUG) {
                Log.d(TAG, "Left rooms (in-memory only, no DB cleanup): ${leftRoomIds.joinToString(", ")}")
            }
        }
        
        // All data is in-memory only - no pending room processing needed
        
        // Process rooms - all data is in-memory only, no DB operations
        val roomsJson = data.optJSONObject("rooms")
        if (roomsJson != null) {
            val roomKeys = roomsJson.keys()
            val roomsToProcess = mutableListOf<String>()
            
            while (roomKeys.hasNext()) {
                roomsToProcess.add(roomKeys.next())
            }
            
            // Process all rooms immediately - no deferral or DB operations
            for ((index, roomId) in roomsToProcess.withIndex()) {
                val roomObj = roomsJson.optJSONObject(roomId) ?: continue
                
                // Log room contents for debugging
                val roomContents = analyzeRoomContents(roomId, roomObj)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SyncIngestor: Processing room $roomId - $roomContents")
                }
                
                val hadEvents = processRoom(roomId, roomObj, isAppVisible)
                if (hadEvents) {
                    roomsWithEvents.add(roomId)
                } else if (BuildConfig.DEBUG) {
                    // Log when room had no events added to cache (room not cached or no events)
                    Log.d(TAG, "SyncIngestor: Room $roomId had no events added to cache (contents: $roomContents)")
                }
                // Yield every 20 rooms to allow other coroutines to run (prevents ANR on reconnect)
                if ((index + 1) % 20 == 0) {
                    kotlinx.coroutines.yield()
                }
            }
            
            // Enhanced logging to show what sync_complete actually contained
            if (BuildConfig.DEBUG) {
                val roomsWithoutEvents = roomsToProcess.size - roomsWithEvents.size
                val summary = buildString {
                    append("Ingested sync_complete: requestId=$requestId, since=$since, ")
                    append("rooms=${roomsToProcess.size} (${roomsWithEvents.size} with events added to cache, $roomsWithoutEvents without events)")
                    if (roomsWithoutEvents > 0) {
                        append(" - Rooms without events: ")
                        val roomsWithoutEventsList = roomsToProcess.filter { it !in roomsWithEvents }
                        roomsWithoutEventsList.forEachIndexed { index, roomId ->
                            if (index > 0) append(", ")
                            val roomObj = roomsJson.optJSONObject(roomId)
                            if (roomObj != null) {
                                val contents = analyzeRoomContents(roomId, roomObj)
                                append("$roomId($contents)")
                            } else {
                                append(roomId)
                            }
                        }
                    }
                }
                Log.d(TAG, summary)
            }
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ingested sync_complete: $requestId, 0 rooms (no rooms object)")
        }
        
        // Return result with rooms that had events and parsed invites
        IngestResult(roomsWithEvents, invites)
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
    private suspend fun processRoom(
        roomId: String,
        roomObj: JSONObject,
        isAppVisible: Boolean = true
    ): Boolean {
        val existingTimelineRowCache = mutableMapOf<String, Long?>()
        var hasPersistedEvents = false
        
        // LRU CACHE: Track events for cache notification
        val eventsForCacheUpdate = mutableListOf<TimelineEvent>()
        var hasEditRedactionReaction = false
        
        // 1. Process room state (meta)
        val meta = roomObj.optJSONObject("meta")
        if (meta != null) {
            // Spaces are handled by SpaceRoomParser.parseSyncUpdate() which builds in-memory Space objects
            // No DB persistence needed - all data is in-memory only

            // Room metadata is handled by SpaceRoomParser.parseSyncUpdate() which builds in-memory RoomItem objects
            // No DB persistence needed - all data is in-memory only
        }
        
        // 2. Process timeline events - only update cache if room is cached, no DB persistence
        val timeline = roomObj.optJSONArray("timeline")
        if (timeline != null) {
            // Check if room is in cache before processing events
            val listener = cacheUpdateListener
            val isRoomCached = listener?.getCachedRoomIds()?.contains(roomId) == true
            
            if (isRoomCached) {
                for (i in 0 until timeline.length()) {
                    val timelineEntry = timeline.optJSONObject(i) ?: continue
                    val timelineRowid = timelineEntry.optLong("timeline_rowid", -1)
                    val eventJson = timelineEntry.optJSONObject("event") ?: continue
                    
                    // No longer collecting reactions for persistence - they're in cache only
                    
                    val sourceLabel = "timeline[$i]"
                    val eventId = eventJson.optString("event_id") ?: "<missing>"
                    val eventType = eventJson.optString("type")
                    
                    val timelineEvent = try {
                        parseEventFromJson(
                            roomId = roomId,
                            eventJson = eventJson,
                            timelineRowid = timelineRowid,
                            source = sourceLabel,
                            existingTimelineRowCache = existingTimelineRowCache
                        )
                    } catch (e: Exception) {
                        logUnprocessedEvent(roomId, eventJson, sourceLabel, "parse_exception", e)
                        null
                    }

                    if (timelineEvent != null) {
                        // LRU CACHE: Collect for cache update and detect edit/redaction/reaction
                        if (timelineEvent.type == "m.room.redaction" || timelineEvent.type == "m.reaction") {
                            hasEditRedactionReaction = true
                        }
                        if (timelineEvent.relationType == "m.replace") {
                            hasEditRedactionReaction = true
                        }
                        // Add to cache
                        eventsForCacheUpdate.add(timelineEvent)
                    }
                }
                
                if (eventsForCacheUpdate.isNotEmpty()) {
                    hasPersistedEvents = true
                }
            } else {
                // Room not in cache - still collect reactions but discard events
                for (i in 0 until timeline.length()) {
                    val timelineEntry = timeline.optJSONObject(i) ?: continue
                    val eventJson = timelineEntry.optJSONObject("event") ?: continue
                    // No longer collecting reactions for persistence - they're in cache only
                }
            }
        }
        
        // 3. Process events array (preview/additional events) - only update cache if room is cached
        val eventsArray = roomObj.optJSONArray("events")
        if (eventsArray != null) {
            // Check if room is in cache before processing events
            val listener = cacheUpdateListener
            val isRoomCached = listener?.getCachedRoomIds()?.contains(roomId) == true
            
            if (isRoomCached) {
                if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Processing ${eventsArray.length()} events from 'events' array for room $roomId (cached)")
                for (i in 0 until eventsArray.length()) {
                    val eventJson = eventsArray.optJSONObject(i) ?: continue
                    
                    // No longer collecting reactions for persistence - they're in cache only
                    
                    // Try to get timeline_rowid from event if available, otherwise use -1
                    val timelineRowid = eventJson.optLong("timeline_rowid", -1)
                    
                    val sourceLabel = "events[$i]"
                    val eventId = eventJson.optString("event_id") ?: "<missing>"
                    
                    val timelineEvent = try {
                        parseEventFromJson(
                            roomId = roomId,
                            eventJson = eventJson,
                            timelineRowid = timelineRowid,
                            source = sourceLabel,
                            existingTimelineRowCache = existingTimelineRowCache
                        )
                    } catch (e: Exception) {
                        logUnprocessedEvent(roomId, eventJson, sourceLabel, "parse_exception", e)
                        null
                    }

                    if (timelineEvent != null) {
                        // LRU CACHE: Collect for cache update and detect edit/redaction/reaction
                        if (timelineEvent.type == "m.room.redaction" || timelineEvent.type == "m.reaction") {
                            hasEditRedactionReaction = true
                        }
                        if (timelineEvent.relationType == "m.replace") {
                            hasEditRedactionReaction = true
                        }
                        eventsForCacheUpdate.add(timelineEvent)
                    }
                }
                
                if (eventsForCacheUpdate.isNotEmpty()) {
                    hasPersistedEvents = true
                }
            } else {
                // Room not in cache - still collect reactions but discard events
                for (i in 0 until eventsArray.length()) {
                    val eventJson = eventsArray.optJSONObject(i) ?: continue
                    // No longer collecting reactions for persistence - they're in cache only
                }
            }
        }
        
        // No longer persisting reactions - they're received from paginate and sync_complete
        // Reactions are stored in the event's aggregatedReactions field in the cache
        
        // No longer persisting receipts - they're received from paginate and sync_complete
        // Receipts are available in the sync_complete response when needed
        
        // 4. Room summaries are no longer persisted to DB - they're built in-memory from sync_complete
        // via SpaceRoomParser.parseSyncUpdate() which creates RoomItem objects with all needed data.
        // This eliminates DB I/O during sync and simplifies the architecture.
        
        // LRU CACHE: Notify listener if this room is cached and has new events
        if (hasPersistedEvents && eventsForCacheUpdate.isNotEmpty()) {
            val listener = cacheUpdateListener
            if (listener != null && listener.getCachedRoomIds().contains(roomId)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Notifying cache listener for room $roomId (${eventsForCacheUpdate.size} events, requiresRerender=$hasEditRedactionReaction)")
                listener.onEventsForCachedRoom(roomId, eventsForCacheUpdate, hasEditRedactionReaction)
            }
        }
        
        return hasPersistedEvents
    }
    
    /**
     * Parse an event JSON into TimelineEvent for in-memory cache updates.
     */
    private suspend fun parseEventFromJson(
        roomId: String,
        eventJson: JSONObject,
        timelineRowid: Long,
        source: String,
        existingTimelineRowCache: MutableMap<String, Long?>
    ): TimelineEvent? {
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
        // Prefer origin_server_ts; fall back to other keys some payloads use (timestamp / ts).
        var timestamp = eventJson.optLong("origin_server_ts", 0L)
        if (timestamp <= 0L) {
            val altTs = eventJson.optLong("timestamp", 0L)
            if (altTs > 0L) {
                timestamp = altTs
            } else {
                val tsKey = eventJson.optLong("ts", 0L)
                if (tsKey > 0L) timestamp = tsKey
            }
            if (BuildConfig.DEBUG && timestamp <= 0L) {
                Log.w(TAG, "parseEventFromJson($roomId,$eventId): missing origin_server_ts/timestamp/ts, keeping 0")
            }
        }
        val decryptedType = eventJson.optString("decrypted_type")
        
        // CRITICAL: Only use timeline_rowid, never rowid (rowid is for backend debugging only)
        // timeline_rowid can be negative (for state events or certain syncs), so we accept any value
        var resolvedTimelineRowId = when {
            timelineRowid != -1L -> timelineRowid  // Use parameter if provided (can be negative)
            eventJson.has("timeline_row_id") -> eventJson.optLong("timeline_row_id")  // Can be negative
            else -> -1L
        }
        
        // Only preserve from cache if we didn't get a valid timeline_rowid (i.e., it's -1)
        // Negative values are valid timeline_rowid values, so we should NOT override them
        // NOTE: No longer querying local storage since events are not persisted
        if (resolvedTimelineRowId == -1L) {
            val cachedValue = existingTimelineRowCache[eventId]
            if (cachedValue != null) {
                resolvedTimelineRowId = cachedValue
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "SyncIngestor: Preserving timelineRowId $resolvedTimelineRowId for event $eventId (source=$source) from cache"
                    )
                }
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
        
        if (isThreadMessage) {
            // For thread messages:
            // - threadRootEventId = m.relates_to.event_id (the original thread root)
            // - relatesToEventId = m.relates_to.m.in_reply_to.event_id (the previous message in thread)
            threadRootEventId = relatesTo?.optString("event_id")?.takeIf { it.isNotBlank() }
            val inReplyTo = relatesTo?.optJSONObject("m.in_reply_to")
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
        
        // Include aggregated reactions (if any) in content for cache consumption
        val reactionsObj = eventJson.optJSONObject("reactions") ?: content?.optJSONObject("reactions")
        if (reactionsObj != null && content != null && !content.has("reactions")) {
            content.put("reactions", reactionsObj)
        }

        val unsigned = eventJson.optJSONObject("unsigned")
        val localContent = eventJson.optJSONObject("local_content")
        val transactionId = eventJson.optString("transaction_id").takeIf { it.isNotBlank() }
            ?: localContent?.optString("transaction_id")?.takeIf { it.isNotBlank() }
            ?: unsigned?.optString("transaction_id")?.takeIf { it.isNotBlank() }

        return TimelineEvent(
            rowid = resolvedTimelineRowId,
            timelineRowid = resolvedTimelineRowId,
            roomId = roomId,
            eventId = eventId,
            sender = sender,
            type = type,
            timestamp = timestamp,
            content = content,
            stateKey = eventJson.optString("state_key").takeIf { it.isNotBlank() },
            decrypted = eventJson.optJSONObject("decrypted"),
            decryptedType = decryptedType?.takeIf { it.isNotBlank() },
            unsigned = unsigned,
            redactedBy = eventJson.optString("redacted_by").takeIf { it.isNotBlank() },
            localContent = localContent,
            relationType = relType?.takeIf { it.isNotBlank() },
            relatesTo = threadRootEventId ?: relatesToEventId,
            aggregatedReactions = reactionsObj,
            transactionId = transactionId
        )
    }
    
    
    /**
     * Get stored since token from SharedPreferences
     */
    suspend fun getSinceToken(): String = withContext(Dispatchers.IO) {
        sharedPrefs.getString("ws_since_token", "") ?: ""
    }
    
    /**
     * @deprecated No longer persisting paginated events - cache is in-memory only.
     */
    @Deprecated("No longer persisting timeline events - using cache only")
    suspend fun persistPaginatedEvents(roomId: String, events: List<TimelineEvent>) = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG && events.isNotEmpty()) {
            Log.d(TAG, "SyncIngestor: Skipping paginated persistence for ${events.size} events (in-memory cache only)")
        }
    }
    
    /**
     * Process spaces from sync_complete (top_level_spaces and space_edges)
     * All data is in-memory only - handled by SpaceRoomParser.parseSyncUpdate()
     */
    private suspend fun processSpaces(data: JSONObject) {
        // Spaces are handled by SpaceRoomParser.parseSyncUpdate() which builds in-memory Space objects
        // No DB persistence needed - all data is in-memory only
        if (BuildConfig.DEBUG) {
            val topLevelSpaces = data.optJSONArray("top_level_spaces")
            val spaceEdges = data.optJSONObject("space_edges")
            Log.d(TAG, "processSpaces: Spaces handled in-memory (top_level_spaces=${topLevelSpaces?.length() ?: 0}, space_edges=${spaceEdges?.length() ?: 0})")
        }
    }
    
    /**
     * Get stored run_id from SharedPreferences
     */
    suspend fun getStoredRunId(): String = withContext(Dispatchers.IO) {
        sharedPrefs.getString("ws_run_id", "") ?: ""
    }
    
    /**
     * Get current user ID from SharedPreferences
     */
    private fun getCurrentUserId(): String {
        return try {
            val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            sharedPrefs.getString("current_user_id", "") ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get current user ID from SharedPreferences: ${e.message}", e)
            ""
        }
    }
    
    /**
     * Process invited_rooms from sync_complete
     * Extracts room metadata and inviter information from invite_state events
     * Returns list of RoomInvite objects (in-memory only, no DB persistence)
     */
    private suspend fun processInvitedRooms(data: JSONObject): List<net.vrkknn.andromuks.RoomInvite> = withContext(Dispatchers.IO) {
        val invitedRooms = data.optJSONArray("invited_rooms") ?: return@withContext emptyList()
        
        if (invitedRooms.length() == 0) return@withContext emptyList()
        
        val currentUserId = getCurrentUserId()
        if (currentUserId.isBlank()) {
            Log.w(TAG, "SyncIngestor: Cannot process invites - current user ID not available")
            return@withContext emptyList()
        }
        
        if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Processing ${invitedRooms.length()} room invitations")
        
        val invites = mutableListOf<net.vrkknn.andromuks.RoomInvite>()
        
        for (i in 0 until invitedRooms.length()) {
            val inviteJson = invitedRooms.optJSONObject(i) ?: continue
            val roomId = inviteJson.optString("room_id", "")
            val createdAt = inviteJson.optLong("created_at", 0)
            val inviteState = inviteJson.optJSONArray("invite_state")
            
            if (roomId.isBlank() || inviteState == null) continue
            
            var inviterUserId = ""
            var inviterDisplayName: String? = null
            var roomName: String? = null
            var roomAvatar: String? = null
            var roomTopic: String? = null
            var roomCanonicalAlias: String? = null
            var inviteReason: String? = null
            var isDirectMessage = false
            
            // Parse invite_state events to extract room metadata and inviter info
            // First pass: Find our invite event to identify the inviter
            for (j in 0 until inviteState.length()) {
                val stateEvent = inviteState.optJSONObject(j) ?: continue
                val eventType = stateEvent.optString("type", "")
                val stateKey = stateEvent.optString("state_key", "")
                val content = stateEvent.optJSONObject("content")
                
                if (eventType == "m.room.member") {
                    val membership = content?.optString("membership", "")
                    // Our invite event: state_key matches current user and membership="invite"
                    // The sender of this event is the inviter
                    if (membership == "invite" && stateKey == currentUserId) {
                        inviterUserId = stateEvent.optString("sender", "")
                        // Extract invite reason if present
                        inviteReason = content?.optString("reason")?.takeIf { it.isNotBlank() }
                        // Check if is_direct flag is set
                        val isDirect = content?.optBoolean("is_direct", false) ?: false
                        if (isDirect) {
                            isDirectMessage = true
                        }
                        break // Found our invite event, can break
                    }
                }
            }
            
            // Second pass: Extract all metadata and inviter details
            for (j in 0 until inviteState.length()) {
                val stateEvent = inviteState.optJSONObject(j) ?: continue
                val eventType = stateEvent.optString("type", "")
                val stateKey = stateEvent.optString("state_key", "")
                val content = stateEvent.optJSONObject("content")
                
                when (eventType) {
                    "m.room.member" -> {
                        val membership = content?.optString("membership", "")
                        // Get inviter's display name and avatar from their member event (membership="join")
                        if (membership == "join" && stateKey == inviterUserId && inviterUserId.isNotBlank()) {
                            inviterDisplayName = content.optString("displayname")?.takeIf { it.isNotBlank() }
                            // Use inviter's avatar as room avatar for DMs if room avatar is not available
                            val inviterAvatar = content.optString("avatar_url")?.takeIf { it.isNotBlank() }
                            if (inviterAvatar != null && roomAvatar == null && isDirectMessage) {
                                roomAvatar = inviterAvatar
                            }
                        }
                    }
                    "m.room.name" -> {
                        roomName = content?.optString("name")?.takeIf { it.isNotBlank() }
                    }
                    "m.room.avatar" -> {
                        val avatarUrl = content?.optString("url")?.takeIf { it.isNotBlank() }
                        if (avatarUrl != null) {
                            roomAvatar = avatarUrl
                        }
                    }
                    "m.room.topic" -> {
                        // Handle both simple topic format and complex m.topic format
                        val simpleTopic = content?.optString("topic")?.takeIf { it.isNotBlank() }
                        if (simpleTopic != null) {
                            roomTopic = simpleTopic
                        } else {
                            // Try m.topic.m.text format
                            val mTopic = content?.optJSONObject("m.topic")
                            val mText = mTopic?.optJSONArray("m.text")
                            if (mText != null && mText.length() > 0) {
                                val firstText = mText.optJSONObject(0)
                                val body = firstText?.optString("body")?.takeIf { it.isNotBlank() }
                                if (body != null) {
                                    roomTopic = body
                                }
                            }
                        }
                    }
                    "m.room.canonical_alias" -> {
                        roomCanonicalAlias = content?.optString("alias")?.takeIf { it.isNotBlank() }
                    }
                    "m.room.create" -> {
                        // Check if additional_creators contains current user ID (indicates DM)
                        val additionalCreators = content?.optJSONArray("additional_creators")
                        if (additionalCreators != null) {
                            for (k in 0 until additionalCreators.length()) {
                                val creatorId = additionalCreators.optString(k, "")
                                if (creatorId == currentUserId) {
                                    isDirectMessage = true
                                    if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Detected DM invite - additional_creators contains current user")
                                    break
                                }
                            }
                        }
                    }
                }
            }
            
            // For DMs, if we have an inviter but no room name, use inviter's display name
            if (isDirectMessage && roomName.isNullOrBlank() && inviterDisplayName != null) {
                roomName = inviterDisplayName
            }
            
            // Only create invite if we have an inviter (required field)
            if (inviterUserId.isNotBlank()) {
                val roomInvite = net.vrkknn.andromuks.RoomInvite(
                    roomId = roomId,
                    createdAt = createdAt,
                    inviterUserId = inviterUserId,
                    inviterDisplayName = inviterDisplayName,
                    roomName = roomName,
                    roomAvatar = roomAvatar,
                    roomTopic = roomTopic,
                    roomCanonicalAlias = roomCanonicalAlias,
                    inviteReason = inviteReason,
                    isDirectMessage = isDirectMessage
                )
                invites.add(roomInvite)
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SyncIngestor: Parsed invite for room $roomId: name=$roomName, inviter=$inviterUserId (displayName=$inviterDisplayName, DM: $isDirectMessage)")
                }
            } else {
                Log.w(TAG, "SyncIngestor: Skipping invite for room $roomId - no inviter found (currentUserId=$currentUserId)")
                // Debug: Log invite_state to understand why inviter wasn't found
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SyncIngestor: Debug - invite_state events for room $roomId:")
                    for (j in 0 until inviteState.length()) {
                        val stateEvent = inviteState.optJSONObject(j)
                        if (stateEvent != null) {
                            val eventType = stateEvent.optString("type", "")
                            val stateKey = stateEvent.optString("state_key", "")
                            val sender = stateEvent.optString("sender", "")
                            if (eventType == "m.room.member") {
                                val content = stateEvent.optJSONObject("content")
                                val membership = content?.optString("membership", "")
                                Log.d(TAG, "SyncIngestor:   - member event: state_key=$stateKey, sender=$sender, membership=$membership")
                            }
                        }
                    }
                }
            }
        }
        
        // Return invites (in-memory only, no DB persistence)
        if (BuildConfig.DEBUG) Log.d(TAG, "SyncIngestor: Parsed ${invites.size} invites (in-memory only)")
        invites
    }
    
    /**
     * @deprecated No longer processing pending items - all data is in-memory only
     */
    @Deprecated("All data is in-memory only, no pending items to process")
    suspend fun rushProcessPendingItems() = withContext(Dispatchers.IO) {
        // No-op - all data is in-memory only
    }
    
    /**
     * @deprecated No longer processing receipts - they're not persisted locally
     */
    @Deprecated("Receipts are no longer persisted locally", level = DeprecationLevel.HIDDEN)
    @Suppress("DEPRECATION")
    suspend fun rushProcessPendingReceipts() = rushProcessPendingItems()
    
    /**
     * @deprecated No longer tracking pending receipts - they're not persisted locally
     */
    @Deprecated("Receipts are no longer persisted locally")
    fun getPendingReceiptsCount(): Int = 0
    
    /**
     * Check if there are any pending rooms to process
     * @deprecated All data is in-memory only, no pending items
     */
    @Deprecated("All data is in-memory only, no pending items")
    suspend fun hasPendingItems(): Boolean = withContext(Dispatchers.IO) {
        false // No pending items - all data is in-memory only
    }
    
}

