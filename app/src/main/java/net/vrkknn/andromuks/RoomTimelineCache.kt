package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import net.vrkknn.andromuks.BuildConfig

/**
 * RoomTimelineCache - Singleton cache for timeline events from sync_complete messages
 * 
 * This singleton stores timeline events received via sync_complete for all rooms.
 * All rooms have unlimited events (no per-room limits) and LRU eviction removes
 * entire rooms when RAM usage exceeds MAX_CACHE_MEMORY_MB.
 * 
 * Cache Management:
 * - Currently opened rooms: unlimited events, exempt from eviction
 * - All other rooms: unlimited events, subject to LRU eviction when RAM threshold exceeded
 * - When a room is evicted, all its events are cleared (we can paginate again if needed)
 * 
 * Benefits:
 * - No artificial event limits - cache as much as RAM allows
 * - LRU-based eviction prevents OOM while preserving active rooms
 * - Better UX with always-on WebSocket
 * - Persistent across AppViewModel instances (crucial for shortcut navigation)
 */
object RoomTimelineCache {
    private const val TAG = "RoomTimelineCache"

    // Serialize all access to the underlying LinkedHashMap + mutable lists.
    // This prevents rare cross-thread corruption when sync/pagination/clear happen concurrently.
    private val cacheLock = Any()
    
    // RAM-based cache management: Maximum memory usage for timeline cache (in MB)
    // LRU eviction will purge entire rooms when this threshold is exceeded
    // Default: 100MB (conservative to prevent OOM, can be adjusted)
    private var MAX_CACHE_MEMORY_MB = 100L // Configurable variable
    
    // Application context for debug-only toasts and diagnostics (optional)
    private var appContext: Context? = null
    
    fun setAppContext(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "RoomTimelineCache: App context set for debug diagnostics")
        }
    }
    
    // Track currently opened rooms (unlimited events allowed, exempt from cache clearing on reconnect)
    // Supports multiple rooms (e.g., RoomTimelineScreen + BubbleTimelineScreen)
    private val currentlyOpenedRooms = mutableSetOf<String>()
    private val openedRoomsLock = Any()
    
    // Processed timeline state (for quick room switching)
    // Uses AppViewModel.EventChainEntry for consistency
    data class ProcessedTimelineState(
        val eventChainMap: MutableMap<String, AppViewModel.EventChainEntry> = mutableMapOf(),
        val editEventsMap: MutableMap<String, TimelineEvent> = mutableMapOf(),
        // Store redaction events: redactionEventId -> redactionEvent
        // This allows us to show deleted messages similar to how we show edits
        val redactionEventsMap: MutableMap<String, TimelineEvent> = mutableMapOf(),
        // Store redaction mapping: originalEventId -> redactionEventId (for quick lookup)
        val redactionMapping: MutableMap<String, String> = mutableMapOf(),
        var lastAccessedAt: Long = System.currentTimeMillis()
    )
    
    private data class RoomCache(
        val events: MutableList<TimelineEvent> = mutableListOf(),
        val eventIds: MutableSet<String> = mutableSetOf(),
        // Store redaction events separately (they're filtered from main events list)
        val redactionEvents: MutableList<TimelineEvent> = mutableListOf(),
        // Store reaction events separately (they're filtered from main events list but needed to restore reactions)
        val reactionEvents: MutableList<TimelineEvent> = mutableListOf(),
        var lastAccessedAt: Long = System.currentTimeMillis(),
        // Processed timeline state (event chains, edits, and redactions) for quick room switching
        val processedState: ProcessedTimelineState = ProcessedTimelineState()
    )

    // Per-room cache: roomId -> RoomCache
    // Uses LRU eviction based on RAM usage instead of room count
    private val roomEventsCache = object : LinkedHashMap<String, RoomCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RoomCache>?): Boolean {
            if (eldest == null) return false
            
            // Don't evict currently opened rooms (exempt from eviction)
            if (isRoomOpened(eldest.key)) {
                return false
            }
            
            // Check if cache memory usage exceeds threshold
            val currentMemoryUsageMB = estimateCacheMemoryUsageMB()
            if (currentMemoryUsageMB > MAX_CACHE_MEMORY_MB) {
                val roomId = eldest.key
                val roomCache = eldest.value
                val eventIds = roomCache.eventIds.toSet() // Capture eventIds before clearing
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Evicting room $roomId from timeline cache (RAM usage: ${currentMemoryUsageMB}MB > ${MAX_CACHE_MEMORY_MB}MB limit, ${eventIds.size} events)")
                }
                
                // Clear timeline cache for this room
                roomsInitialized.remove(roomId)
                roomCache.events.clear()
                roomCache.eventIds.clear()
                
                // Clean up all related caches for this room
                // 1. Profile cache (room-specific profiles)
                ProfileCache.clearRoom(roomId)
                
                // 2. Room member cache
                RoomMemberCache.clearRoom(roomId)
                
                // 3. Read receipts (by roomId and by eventIds for thorough cleanup)
                ReadReceiptCache.clearRoom(roomId)
                if (eventIds.isNotEmpty()) {
                    ReadReceiptCache.clearForEventIds(eventIds)
                }
                
                // 4. Message reactions (by eventIds)
                if (eventIds.isNotEmpty()) {
                    MessageReactionsCache.clearForEventIds(eventIds)
                }
                
                // 5. Message versions (by eventIds)
                if (eventIds.isNotEmpty()) {
                    MessageVersionsCache.clearForEventIds(eventIds)
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Cleared all related caches for evicted room $roomId (profiles, members, receipts, reactions, versions)")
                }
                
                return true
            }
            
            return false
        }
    }
    
    /**
     * Estimate current cache memory usage in MB
     * Rough estimate: ~1.5KB per TimelineEvent
     */
    private fun estimateCacheMemoryUsageMB(): Long {
        synchronized(cacheLock) {
            val totalEvents = roomEventsCache.values.sumOf { it.events.size }
            val estimatedBytes = (totalEvents * 1.5 * 1024).toLong() // 1.5KB per event, convert to Long
            return estimatedBytes / (1024 * 1024) // Convert to MB (result is Long)
        }
    }
    
    // Track which rooms have received their initial paginate (to avoid duplicate caching)
    private val roomsInitialized = mutableSetOf<String>()
    
    // PROACTIVE CACHE MANAGEMENT: Track which rooms are actively cached and should receive events from sync_complete
    // When a room is opened and paginated, it's marked as cached. When WebSocket reconnects, all are marked as needing pagination.
    private val activelyCachedRooms = mutableSetOf<String>()
    private val cacheStateLock = Any()

    /**
     * Set the currently opened room (unlimited events allowed for this room)
     * @deprecated Use addOpenedRoom() and removeOpenedRoom() instead for multi-room support
     */
    @Deprecated("Use addOpenedRoom() and removeOpenedRoom() for multi-room support")
    fun setCurrentRoom(roomId: String?) {
        synchronized(cacheLock) {
            synchronized(openedRoomsLock) {
                currentlyOpenedRooms.clear()
                if (roomId != null) {
                    currentlyOpenedRooms.add(roomId)
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Set current room to $roomId (unlimited events allowed, opened rooms: ${currentlyOpenedRooms.size})")
            }
        }
    }
    
    /**
     * Add a room to the set of currently opened rooms (exempt from cache clearing on reconnect)
     * Called when RoomTimelineScreen or BubbleTimelineScreen opens a room
     */
    fun addOpenedRoom(roomId: String) {
        synchronized(cacheLock) {
            synchronized(openedRoomsLock) {
                currentlyOpenedRooms.add(roomId)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Added room $roomId to opened rooms (total: ${currentlyOpenedRooms.size})")
        }
    }
    
    /**
     * Remove a room from the set of currently opened rooms
     * Called when RoomTimelineScreen or BubbleTimelineScreen closes a room
     */
    fun removeOpenedRoom(roomId: String) {
        synchronized(cacheLock) {
            synchronized(openedRoomsLock) {
                currentlyOpenedRooms.remove(roomId)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Removed room $roomId from opened rooms (total: ${currentlyOpenedRooms.size})")
        }
    }
    
    /**
     * Get the set of currently opened rooms (exempt from cache clearing)
     */
    fun getOpenedRooms(): Set<String> {
        synchronized(cacheLock) {
            synchronized(openedRoomsLock) {
                return currentlyOpenedRooms.toSet()
            }
        }
    }
    
    /**
     * Check if a room is currently opened
     */
    fun isRoomOpened(roomId: String): Boolean {
        synchronized(cacheLock) {
            synchronized(openedRoomsLock) {
                return currentlyOpenedRooms.contains(roomId)
            }
        }
    }


    /**
     * Adds events into the in-memory cache for a room. Performs deduplication, keeps events ordered
     * by timeline_rowid (and timestamp fallback), and enforces adaptive max size limits.
     * Redaction events are stored separately.
     */
    private fun addEventsToCache(roomId: String, incomingEvents: List<TimelineEvent>): Int {
        if (incomingEvents.isEmpty()) return 0

        // SAFETY: Prevent cross-room cache contamination if a caller accidentally passes the wrong roomId.
        // Drop mismatched events (cheap and safe).
        val filteredEvents = incomingEvents.filter { it.roomId == roomId }
        val droppedCount = incomingEvents.size - filteredEvents.size
        if (droppedCount > 0 && BuildConfig.DEBUG) {
            val sampleRooms = incomingEvents.asSequence().map { it.roomId }.distinct().take(5).toList()
            Log.w(TAG, "addEventsToCache: Dropped $droppedCount events due to room_id mismatch (target=$roomId, incomingRooms=$sampleRooms)")
            // Show a debug-only toast so developers immediately see that something is wrong
            appContext?.let { context ->
                Toast.makeText(
                    context,
                    "Dropped $droppedCount events with wrong room_id for room $roomId",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (filteredEvents.isEmpty()) return 0

        val cache = roomEventsCache.getOrPut(roomId) { RoomCache() }
        // Update access time when events are added (for LRU eviction)
        cache.lastAccessedAt = System.currentTimeMillis()

        var addedCount = 0
        for (event in filteredEvents) {
            if (event.eventId.isBlank()) continue
            
            // Handle redaction events separately
            if (event.type == "m.room.redaction") {
                if (cache.redactionEvents.none { it.eventId == event.eventId }) {
                    cache.redactionEvents.add(event)
                    addedCount++
                    
                    // Extract the event ID being redacted and store mapping
                    val redactsString = event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                    val redactsObject = event.content?.optJSONObject("redacts")?.optString("event_id")?.takeIf { it.isNotBlank() }
                    val originalEventId = redactsString ?: redactsObject
                    
                    if (originalEventId != null) {
                        synchronized(cacheStateLock) {
                            cache.processedState.redactionMapping[originalEventId] = event.eventId
                            cache.processedState.redactionEventsMap[event.eventId] = event
                        }
                    }
                }
            } else if (event.type == "m.reaction") {
                // Store reaction events separately (they're filtered from main events list but needed to restore reactions)
                if (cache.reactionEvents.none { it.eventId == event.eventId }) {
                    cache.reactionEvents.add(event)
                    addedCount++
                    if (BuildConfig.DEBUG) {
                        val relatesTo = event.content?.optJSONObject("m.relates_to")
                        val relatesToEventId = relatesTo?.optString("event_id")
                        val emoji = relatesTo?.optString("key")
                        Log.d(TAG, "RoomTimelineCache: Cached reaction event: ${event.eventId} relatesTo=$relatesToEventId emoji=$emoji (room=$roomId, totalReactions=${cache.reactionEvents.size})")
                    }
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "RoomTimelineCache: Reaction event ${event.eventId} already in cache, skipping duplicate")
                    }
                }
            } else {
                // Regular events
                if (cache.eventIds.add(event.eventId)) {
                    // New event - add it
                    cache.events.add(event)
                    addedCount++
                } else {
                    // Event already exists - merge aggregatedReactions and/or redactedBy if present
                    // This handles sync_complete updates where events come with updated reaction aggregations
                    // or redaction info (redacted_by) - critical for rendering "Removed by X for Y at Z"
                    val existingEventIndex = cache.events.indexOfFirst { it.eventId == event.eventId }
                    if (existingEventIndex >= 0) {
                        val existingEvent = cache.events[existingEventIndex]
                        var updatedEvent = existingEvent
                        var needsUpdate = false
                        if (event.aggregatedReactions != null) {
                            updatedEvent = updatedEvent.copy(aggregatedReactions = event.aggregatedReactions)
                            needsUpdate = true
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "RoomTimelineCache: Updated aggregatedReactions for existing event ${event.eventId} in room $roomId")
                            }
                        }
                        // CRITICAL: Merge redactedBy when sync_complete sends the redacted message
                        // (backend includes redacted_by on the original event; without this, redactions
                        // from sync_complete never render in the UI)
                        if (event.redactedBy != null && existingEvent.redactedBy != event.redactedBy) {
                            updatedEvent = updatedEvent.copy(redactedBy = event.redactedBy)
                            needsUpdate = true
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "RoomTimelineCache: Updated redactedBy for existing event ${event.eventId} in room $roomId (redactedBy=${event.redactedBy})")
                            }
                        }
                        if (needsUpdate) {
                            cache.events[existingEventIndex] = updatedEvent
                        }
                    }
                }
            }
        }

        if (addedCount == 0) {
            return 0
        }

        // Ensure deterministic ordering: primary by timeline_rowid (ascending), fallback to timestamp, then eventId
        // Note: timelineRowid can be negative, and negative numbers are naturally smaller than positive ones
        // So we can just compare timelineRowid directly - no need for special handling
        // OPTIMIZATION: Check if we can append without sorting (common case for new messages)
        // This avoids O(N log N) sort on every sync for active rooms
        val needsSort = if (cache.events.size <= addedCount) {
             // Cache was empty or all events are new, we need to ensure the whole list is sorted
             // But if we just added a single event to empty list, no sort needed
             cache.events.size > 1
        } else {
             // Check if the newly added events (which are at the end) are actually newer than the previous last event
             // The events were added to the end of the list in the loop above
             // We need to check if the boundary between old events and new events respects the sort order
             val newEventsStartIndex = cache.events.size - addedCount
             val previousLastEvent = cache.events[newEventsStartIndex - 1]
             
             // Check if any of the new events should be before previousLastEvent
             // We only need to check the first of the added events because we assume the added batch
             // might be sorted or unsorted, but if the *oldest* of the new batch is newer than 
             // present cache tail, and the new batch itself is sorted, we might save time.
             // However, incoming events might not be sorted.
             // Simplest safe optimization:
             // If we added events, and the new list is not naturally sorted at the boundary, we sort.
             
             // Let's rely on a simpler heuristic: 
             // 1. If we added 1 event (common case for live chat), check if it's after the previous last.
             if (addedCount == 1) {
                 val newEvent = cache.events.last()
                 val rowIdCompare = previousLastEvent.timelineRowid.compareTo(newEvent.timelineRowid)
                 if (rowIdCompare < 0) {
                     false // previous < new, order is correct
                 } else if (rowIdCompare > 0) {
                     true // previous > new, older event arrived, need sort
                 } else {
                     // rowIds equal, comparing timestamps
                     val tsCompare = previousLastEvent.timestamp.compareTo(newEvent.timestamp)
                     if (tsCompare < 0) {
                         false 
                     } else if (tsCompare > 0) {
                         true
                     } else {
                         // Timestamps equal, comparing eventIds
                         previousLastEvent.eventId.compareTo(newEvent.eventId) > 0
                     }
                 }
             } else {
                 // For multiple events, just sort to be safe and simple
                 true
             }
        }

        if (needsSort) {
            // CRASH FIX: Defensive null checks in sort lambda (handles edge cases where nulls might sneak in)
            cache.events.sortWith { a, b ->
                // CRASH FIX: Defensive null checks in sort lambda
                // This should never happen with proper typing, but protects against runtime issues
                if (a == null || b == null) {
                    Log.e(TAG, "CRITICAL: Null event found during sort for room $roomId, a=${a == null}, b=${b == null}. This should not happen!")
                    return@sortWith if (a == null && b == null) 0 else if (a == null) 1 else -1
                }
                
                // Sort by timelineRowid first (negative numbers are naturally smaller)
                val rowIdCompare = a.timelineRowid.compareTo(b.timelineRowid)
                if (rowIdCompare != 0) {
                    rowIdCompare
                } else {
                    // If timelineRowid is the same, fallback to timestamp, then eventId
                    val tsCompare = a.timestamp.compareTo(b.timestamp)
                    if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                }
            }
        }

        // No per-room event limits - rely on LRU eviction based on RAM usage
        // All rooms have unlimited events (LRU evicts entire rooms when RAM threshold exceeded)
        if (BuildConfig.DEBUG && cache.events.size > 1000) {
            Log.d(TAG, "Room $roomId cache now has ${cache.events.size} events (unlimited, LRU-based eviction)")
        }

        return addedCount
    }
    
    /**
     * Add events from sync_complete to the cache for a specific room
     * Includes deduplication to prevent duplicate events
     */
    fun addEventsFromSync(roomId: String, eventsArray: JSONArray, memberMap: Map<String, MemberProfile>) {
        synchronized(cacheLock) {
            val events = parseEventsFromArray(eventsArray, memberMap)
        
            if (events.isEmpty()) {
                return
            }
        
        if (BuildConfig.DEBUG) {
            val firstIncoming = events.firstOrNull()
            val lastIncoming = events.lastOrNull()
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "Adding ${events.size} events from sync for room $roomId " +
                    "(first=${firstIncoming?.eventId}@${firstIncoming?.timestamp}, " +
                    "last=${lastIncoming?.eventId}@${lastIncoming?.timestamp})"
            )
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Adding ${events.size} events from sync for room $roomId")
        }
        
            // Get or create cache for this room
            val added = addEventsToCache(roomId, events)
            if (added == 0) {
                if (BuildConfig.DEBUG) Log.d(TAG, "All ${events.size} events already in cache, skipping")
                return
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Added $added new events to cache (total=${getCachedEventCount(roomId)})")
        }
    }
    
    /**
     * Get cached events for a room
     * Returns null if not enough events cached, otherwise returns the cached events
     */
    /**
     * Mark a room as accessed (updates lastAccessedAt for LRU eviction)
     * No event limits are enforced - all rooms have unlimited events
     * LRU eviction removes entire rooms when RAM threshold is exceeded
     */
    fun markRoomAccessed(roomId: String) {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return
            cache.lastAccessedAt = System.currentTimeMillis()
        }
        // Accessing the map also updates LinkedHashMap order for LRU
        // No event limits - rely on RAM-based LRU eviction of entire rooms
    }
    
    fun getCachedEvents(roomId: String): List<TimelineEvent>? {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return null
        
            // CRITICAL FIX: Ensure room is marked as opened BEFORE retrieving events
            // This prevents event eviction if the room was just opened but wasn't in the opened set yet
            // (e.g., due to race conditions or ordering issues)
            if (!isRoomOpened(roomId)) {
                addOpenedRoom(roomId)
                if (BuildConfig.DEBUG) Log.d(TAG, "getCachedEvents: Room $roomId was not in opened set, added it to prevent event eviction")
            }
        
            // Mark room as accessed for LRU eviction
            cache.lastAccessedAt = System.currentTimeMillis()
        
            // Return a copy of cached events if available (no minimum threshold)
            // If cache is empty, return null so caller can paginate
            return if (cache.events.isNotEmpty()) {
                val eventsCopy = cache.events.toList()
                if (BuildConfig.DEBUG) {
                    val firstEvent = eventsCopy.firstOrNull()
                    val lastEvent = eventsCopy.lastOrNull()
                    Log.d(
                        TAG,
                        "Cache hit for room $roomId: ${eventsCopy.size} events available. " +
                            "first=${firstEvent?.eventId}@${firstEvent?.timestamp}, last=${lastEvent?.eventId}@${lastEvent?.timestamp}"
                    )
                }
                eventsCopy
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "Cache miss for room $roomId: no events cached")
                null
            }
        }
    }
    
    /**
     * Get cached events for a room with lower threshold for notification scenarios
     * Returns cached events if available, null otherwise (same as getCachedEvents)
     */
    fun getCachedEventsForNotification(roomId: String): List<TimelineEvent>? {
        // Same behavior as getCachedEvents - no minimum threshold
        return getCachedEvents(roomId)
    }

    /**
     * Get all events needed for timeline/chain building, including redaction events.
     * Redaction events are required so buildTimelineFromChain can apply redactedBy to
     * targeted messages (renders "Removed by X for Y at Z").
     */
    fun getCachedEventsForTimeline(roomId: String): List<TimelineEvent> {
        val mainEvents = getCachedEvents(roomId).orEmpty()
        val redactionEvents = getRedactionEvents(roomId)
        return (mainEvents + redactionEvents).distinctBy { it.eventId }
    }
    
    data class CachedEventMetadata(
        val eventId: String,
        val timelineRowId: Long,
        val timestamp: Long
    )

    /**
     * Get the number of cached events for a room
     */
    fun getCachedEventCount(roomId: String): Int {
        synchronized(cacheLock) {
            return roomEventsCache[roomId]?.events?.size ?: 0
        }
    }

    /**
     * Get metadata for the most recent cached event in a room
     */
    fun getLatestCachedEventMetadata(roomId: String): CachedEventMetadata? {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return null
            val latest = cache.events.lastOrNull() ?: return null
            return CachedEventMetadata(
                eventId = latest.eventId,
                timelineRowId = latest.timelineRowid,
                timestamp = latest.timestamp
            )
        }
    }

    fun getOldestCachedEventMetadata(roomId: String): CachedEventMetadata? {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return null
            val oldest = cache.events.firstOrNull() ?: return null
            return CachedEventMetadata(
                eventId = oldest.eventId,
                timelineRowId = oldest.timelineRowid,
                timestamp = oldest.timestamp
            )
        }
    }
    
    /**
     * Get the second-oldest cached event's timeline_rowid for pagination
     * This helps avoid gaps when the oldest event might be in a sparse region
     * Returns -1 if no second event available
     */
    fun getSecondOldestCachedEventRowId(roomId: String): Long {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return -1L
            if (cache.events.size < 2) return -1L
            
            // Ensure cache is sorted
            cache.events.sortWith { a, b ->
                if (a == null || b == null) {
                    return@sortWith if (a == null && b == null) 0 else if (a == null) 1 else -1
                }
                val rowIdCompare = a.timelineRowid.compareTo(b.timelineRowid)
                if (rowIdCompare != 0) {
                    rowIdCompare
                } else {
                    val tsCompare = a.timestamp.compareTo(b.timestamp)
                    if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                }
            }
            
            val secondOldest = cache.events.getOrNull(1)
            return secondOldest?.timelineRowid ?: -1L
        }
    }
    
    /**
     * Get the oldest cached event's timeline_rowid for pagination
     * Returns the actual oldest event's timelineRowid (can be negative - that's valid!)
     * Returns -1 if no cached events
     */
    fun getOldestCachedEventRowId(roomId: String): Long {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: run {
                if (BuildConfig.DEBUG) Log.d(TAG, "getOldestCachedEventRowId: No cache for room $roomId")
                return -1L
            }
            
            if (cache.events.isEmpty()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "getOldestCachedEventRowId: Cache is empty for room $roomId")
                return -1L
            }
            
            // Ensure cache is sorted correctly before retrieving oldest event
            // This is important because the cache might have been created before the sorting fix
            cache.events.sortWith { a, b ->
                if (a == null || b == null) {
                    return@sortWith if (a == null && b == null) 0 else if (a == null) 1 else -1
                }
                val rowIdCompare = a.timelineRowid.compareTo(b.timelineRowid)
                if (rowIdCompare != 0) {
                    rowIdCompare
                } else {
                    val tsCompare = a.timestamp.compareTo(b.timestamp)
                    if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                }
            }
            
            // Cache is sorted by timelineRowid ASC, so first event is the oldest
            // Note: timelineRowid can be negative (for state events or certain syncs)
            val oldestEvent = cache.events.firstOrNull()
            val result = oldestEvent?.timelineRowid ?: -1L
            
            // Debug: Show range of timelineRowid values in cache
            if (BuildConfig.DEBUG) {
                val minRowId = cache.events.minOfOrNull { it.timelineRowid } ?: -1L
                val maxRowId = cache.events.maxOfOrNull { it.timelineRowid } ?: -1L
                val negativeCount = cache.events.count { it.timelineRowid < 0 }
                val positiveCount = cache.events.count { it.timelineRowid > 0 }
                val firstFew = cache.events.take(5).map { "${it.eventId.take(20)}... (rowId=${it.timelineRowid})" }.joinToString(", ")
                Log.d(TAG, "getOldestCachedEventRowId for $roomId: result=$result, cache has ${cache.events.size} events, rowId range: $minRowId to $maxRowId (negative=$negativeCount, positive=$positiveCount)")
                Log.d(TAG, "getOldestCachedEventRowId: First 5 events: $firstFew")
            }
            return result
        }
    }
    
    /**
     * Get the oldest cached event's timeline_rowid that is positive (> 0) for pagination
     * Negative timeline_rowid values cannot be used for pagination (backend requires positive values)
     * Returns -1 if no cached events or no positive timeline_rowid found
     */
    fun getOldestPositiveCachedEventRowId(roomId: String): Long {
        val cache = roomEventsCache[roomId] ?: run {
            if (BuildConfig.DEBUG) Log.d(TAG, "getOldestPositiveCachedEventRowId: No cache for room $roomId")
            return -1L
        }
        
        if (cache.events.isEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "getOldestPositiveCachedEventRowId: Cache is empty for room $roomId")
            return -1L
        }
        
        // Ensure cache is sorted correctly before retrieving oldest event
        cache.events.sortWith { a, b ->
            if (a == null || b == null) {
                return@sortWith if (a == null && b == null) 0 else if (a == null) 1 else -1
            }
            val rowIdCompare = a.timelineRowid.compareTo(b.timelineRowid)
            if (rowIdCompare != 0) {
                rowIdCompare
            } else {
                val tsCompare = a.timestamp.compareTo(b.timestamp)
                if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
            }
        }
        
        // Find the oldest event with a positive timeline_rowid
        // Negative values cannot be used for pagination (backend requires positive max_timeline_id)
        val oldestPositiveEvent = cache.events.firstOrNull { it.timelineRowid > 0 }
        val result = oldestPositiveEvent?.timelineRowid ?: -1L
        
        if (BuildConfig.DEBUG) {
            val positiveCount = cache.events.count { it.timelineRowid > 0 }
            val negativeCount = cache.events.count { it.timelineRowid < 0 }
            Log.d(TAG, "getOldestPositiveCachedEventRowId for $roomId: result=$result, positive events=$positiveCount, negative events=$negativeCount")
        }
        return result
    }
    
    /**
     * Check if we have some cached events (deprecated - no longer used with unlimited cache)
     * Always returns false since we no longer have partial cache concept
     */
    @Deprecated("No longer used - all cached events are available, paginate if cache is empty")
    fun hasPartialCache(roomId: String): Boolean {
        // No partial cache concept - either we have events or we don't
        return false
    }
    
    /**
     * Mark a room as initialized (received its first paginate response)
     * This helps us know which rooms have full initial data
     */
    fun markRoomInitialized(roomId: String) {
        roomsInitialized.add(roomId)
        if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId marked as initialized")
    }
    
    /**
     * Check if a room has been initialized
     */
    fun isRoomInitialized(roomId: String): Boolean {
        return roomsInitialized.contains(roomId)
    }
    
    /**
     * Replace cache with paginated events (when we fetch initial timeline)
     * This seeds the cache with the full initial paginate response
     */
    fun seedCacheWithPaginatedEvents(roomId: String, events: List<TimelineEvent>) {
        synchronized(cacheLock) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Seeding cache for room $roomId with ${events.size} paginated events")
            
            val cache = roomEventsCache.getOrPut(roomId) { RoomCache() }
            cache.events.clear()
            cache.eventIds.clear()
            addEventsToCache(roomId, events)

            // Mark as initialized
            markRoomInitialized(roomId)
            // PROACTIVE CACHE MANAGEMENT: Mark room as actively cached when paginated
            markRoomAsCached(roomId)
            if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId cache seeded and initialized with ${getCachedEventCount(roomId)} events (marked as actively cached)")
        }
    }
    
    /**
     * Merge new paginated events with existing cache (for "load more" operations)
     * Also marks room as actively cached if not already marked
     */
    fun mergePaginatedEvents(roomId: String, newEvents: List<TimelineEvent>): Int {
        synchronized(cacheLock) {
            if (newEvents.isEmpty()) {
                return 0
            }
            
            val reactionEventsCount = newEvents.count { it.type == "m.reaction" }
            val minRowId = newEvents.mapNotNull { it.timelineRowid.takeIf { row -> row > 0 } }.minOrNull()
            val maxRowId = newEvents.mapNotNull { it.timelineRowid.takeIf { row -> row > 0 } }.maxOrNull()
            val minRowDisplay = minRowId?.toString() ?: "n/a"
            val maxRowDisplay = maxRowId?.toString() ?: "n/a"
            
            val cacheBefore = getCachedEventCount(roomId)
            val oldestRowIdBefore = getOldestCachedEventRowId(roomId)
            val cache = roomEventsCache[roomId]
            val reactionEventsBefore = cache?.reactionEvents?.size ?: 0

            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "Merging ${newEvents.size} events for room $roomId (${reactionEventsCount} reactions) - rowId range: $minRowDisplay to $maxRowDisplay (cache before: $cacheBefore events, $reactionEventsBefore reactions, oldestRowId: $oldestRowIdBefore)"
            )
            
            val added = addEventsToCache(roomId, newEvents)
            val reactionEventsAfter = roomEventsCache[roomId]?.reactionEvents?.size ?: 0
            if (BuildConfig.DEBUG && reactionEventsCount > 0) {
                Log.d(TAG, "After merge: room $roomId now has $reactionEventsAfter reaction events (added ${reactionEventsAfter - reactionEventsBefore} reactions)")
            }
            val cacheAfter = getCachedEventCount(roomId)
            val oldestRowIdAfter = getOldestCachedEventRowId(roomId)
            
            // PROACTIVE CACHE MANAGEMENT: Mark room as actively cached when receiving paginated events
            // This ensures SyncIngestor knows to update this room with events from sync_complete
            if (!isRoomActivelyCached(roomId)) {
                markRoomAsCached(roomId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId marked as actively cached after receiving paginated events")
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId cache after merge: $cacheAfter events (added $added, was $cacheBefore). OldestRowId: $oldestRowIdBefore -> $oldestRowIdAfter")
            
            if (added == 0 && newEvents.isNotEmpty()) {
                // Log detailed information about why all events were duplicates
                val eventIds = newEvents.take(5).map { "${it.eventId} (rowId=${it.timelineRowid})" }.joinToString(", ")
                val allNegative = newEvents.all { it.timelineRowid < 0 }
                Log.w(TAG, "Room $roomId: All ${newEvents.size} events were duplicates! Requested with max_timeline_id around $oldestRowIdBefore, got events with rowId range $minRowDisplay-$maxRowDisplay (allNegative=$allNegative)")
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Room $roomId: Sample duplicate event IDs: $eventIds")
                    Log.w(TAG, "Room $roomId: Cache currently has ${cacheAfter} events, oldestRowId=$oldestRowIdAfter")
                }
            }
            
            return added
        }
    }
    
    /**
     * Save processed timeline state (eventChainMap, editEventsMap, and redactionEventsMap) for a room
     * Used for quick room switching - stores processed state alongside raw events
     */
    fun saveProcessedTimelineState(
        roomId: String,
        eventChainMap: Map<String, AppViewModel.EventChainEntry>,
        editEventsMap: Map<String, TimelineEvent>,
        redactionEventsMap: Map<String, TimelineEvent>? = null,
        redactionMapping: Map<String, String>? = null
    ) {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return
            synchronized(cacheStateLock) {
                cache.processedState.eventChainMap.clear()
                cache.processedState.eventChainMap.putAll(eventChainMap)
                cache.processedState.editEventsMap.clear()
                cache.processedState.editEventsMap.putAll(editEventsMap)
            
                // Save redaction events and mapping if provided
                if (redactionEventsMap != null) {
                    cache.processedState.redactionEventsMap.clear()
                    cache.processedState.redactionEventsMap.putAll(redactionEventsMap)
                }
                if (redactionMapping != null) {
                    cache.processedState.redactionMapping.clear()
                    cache.processedState.redactionMapping.putAll(redactionMapping)
                }
            
                cache.processedState.lastAccessedAt = System.currentTimeMillis()
                cache.lastAccessedAt = System.currentTimeMillis()
            }
            val redactionCount = redactionEventsMap?.size ?: 0
            if (BuildConfig.DEBUG) Log.d(TAG, "Saved processed timeline state for room $roomId (${eventChainMap.size} chains, ${editEventsMap.size} edits, $redactionCount redactions)")
        }
    }
    
    /**
     * Get processed timeline state (eventChainMap, editEventsMap, redactionEventsMap, redactionMapping) for a room
     * Returns null if room not cached or no processed state available
     */
    data class ProcessedTimelineStateResult(
        val eventChainMap: Map<String, AppViewModel.EventChainEntry>,
        val editEventsMap: Map<String, TimelineEvent>,
        val redactionEventsMap: Map<String, TimelineEvent>,
        val redactionMapping: Map<String, String>
    )
    
    fun getProcessedTimelineState(roomId: String): ProcessedTimelineStateResult? {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return null
            return synchronized(cacheStateLock) {
                if (cache.processedState.eventChainMap.isEmpty() && 
                    cache.processedState.editEventsMap.isEmpty() &&
                    cache.processedState.redactionEventsMap.isEmpty()) {
                    null
                } else {
                    ProcessedTimelineStateResult(
                        eventChainMap = cache.processedState.eventChainMap.toMap(),
                        editEventsMap = cache.processedState.editEventsMap.toMap(),
                        redactionEventsMap = cache.processedState.redactionEventsMap.toMap(),
                        redactionMapping = cache.processedState.redactionMapping.toMap()
                    )
                }
            }
        }
    }
    
    /**
     * Clear processed timeline state for a room (keeps raw events)
     */
    fun clearProcessedTimelineState(roomId: String) {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return
            synchronized(cacheStateLock) {
                cache.processedState.eventChainMap.clear()
                cache.processedState.editEventsMap.clear()
                cache.processedState.redactionEventsMap.clear()
                cache.processedState.redactionMapping.clear()
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Cleared processed timeline state for room $roomId")
        }
    }
    
    /**
     * Add a redaction event to the cache (stored separately from main events)
     */
    fun addRedactionEvent(roomId: String, redactionEvent: TimelineEvent) {
        synchronized(cacheLock) {
            val cache = roomEventsCache.getOrPut(roomId) { RoomCache() }
            synchronized(cacheStateLock) {
                // Store redaction event separately
                if (cache.redactionEvents.none { it.eventId == redactionEvent.eventId }) {
                    cache.redactionEvents.add(redactionEvent)
                }
            
                // Extract the event ID being redacted
                val redactsString = redactionEvent.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                val redactsObject = redactionEvent.content?.optJSONObject("redacts")?.optString("event_id")?.takeIf { it.isNotBlank() }
                val originalEventId = redactsString ?: redactsObject
            
                if (originalEventId != null) {
                    // Store mapping: originalEventId -> redactionEventId
                    cache.processedState.redactionMapping[originalEventId] = redactionEvent.eventId
                    cache.processedState.redactionEventsMap[redactionEvent.eventId] = redactionEvent
                    if (BuildConfig.DEBUG) Log.d(TAG, "Added redaction event ${redactionEvent.eventId} for room $roomId (redacts: $originalEventId)")
                } else {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Added redaction event ${redactionEvent.eventId} for room $roomId but could not extract original event ID")
                }
            }
        }
    }
    
    /**
     * Get all redaction events for a room
     */
    fun getRedactionEvents(roomId: String): List<TimelineEvent> {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return emptyList()
            return synchronized(cacheStateLock) {
                cache.redactionEvents.toList()
            }
        }
    }
    
    /**
     * Get redaction event for a specific original event
     */
    fun getRedactionEventForOriginal(roomId: String, originalEventId: String): TimelineEvent? {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return null
            return synchronized(cacheStateLock) {
                val redactionEventId = cache.processedState.redactionMapping[originalEventId]
                redactionEventId?.let { cache.processedState.redactionEventsMap[it] }
            }
        }
    }
    
    /**
     * Get all cached reaction events for a room
     * Used to restore reactions when reopening a room
     */
    fun getCachedReactionEvents(roomId: String): List<TimelineEvent> {
        synchronized(cacheLock) {
            val cache = roomEventsCache[roomId] ?: return emptyList()
            return cache.reactionEvents.toList()
        }
    }
    
    /**
     * Clear cache for a specific room (useful when leaving a room)
     */
    fun clearRoomCache(roomId: String) {
        synchronized(cacheLock) {
            // Capture eventIds before removing the cache entry
            val roomCache = roomEventsCache[roomId]
            val eventIds = roomCache?.eventIds?.toSet() ?: emptySet()
            
            synchronized(cacheStateLock) {
                roomEventsCache.remove(roomId)
                roomsInitialized.remove(roomId)
                activelyCachedRooms.remove(roomId)
            }
            
            // Clean up all related caches for this room
            // 1. Profile cache (room-specific profiles)
            ProfileCache.clearRoom(roomId)
            
            // 2. Room member cache
            RoomMemberCache.clearRoom(roomId)
            
            // 3. Read receipts (by roomId and by eventIds for thorough cleanup)
            ReadReceiptCache.clearRoom(roomId)
            if (eventIds.isNotEmpty()) {
                ReadReceiptCache.clearForEventIds(eventIds)
            }
            
            // 4. Message reactions (by eventIds)
            if (eventIds.isNotEmpty()) {
                MessageReactionsCache.clearForEventIds(eventIds)
            }
            
            // 5. Message versions (by eventIds)
            if (eventIds.isNotEmpty()) {
                MessageVersionsCache.clearForEventIds(eventIds)
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Cleared cache for room $roomId and marked as not cached (also cleared profiles, members, receipts, reactions, versions)")
        }
    }
    
    /**
     * Clear all caches (useful on logout or WebSocket reconnect)
     */
    fun clearAllCaches() {
        synchronized(cacheLock) {
            val allRoomIds = roomEventsCache.keys.toSet()
            roomEventsCache.clear()
            roomsInitialized.clear()
            
            // Clear all related caches completely
            allRoomIds.forEach { ProfileCache.clearRoom(it) }
            RoomMemberCache.clear()
            ReadReceiptCache.clear()
            MessageReactionsCache.clear()
            MessageVersionsCache.clear()
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Cleared all room caches (also cleared all profiles, members, receipts, reactions, versions)")
        }
    }
    
    /**
     * Clear all caches and mark all rooms as needing pagination
     * Called on WebSocket connect/reconnect to mark all caches as stale
     * EXCEPTION: Currently opened rooms are preserved (exempt from cache clearing)
     */
    fun clearAll() {
        synchronized(cacheLock) {
            synchronized(cacheStateLock) {
                val openedRooms = getOpenedRooms()
            
                if (openedRooms.isEmpty()) {
                    // No opened rooms - clear everything
                    val allRoomIds = roomEventsCache.keys.toSet()
                    // Capture eventIds for all rooms before clearing
                    val allEventIds = roomEventsCache.values.flatMap { it.eventIds }.toSet()
                    
                    roomEventsCache.clear()
                    roomsInitialized.clear()
                    activelyCachedRooms.clear()
                    
                    // Clear all related caches for all rooms
                    allRoomIds.forEach { ProfileCache.clearRoom(it) }
                    RoomMemberCache.clear()
                    ReadReceiptCache.clear()
                    if (allEventIds.isNotEmpty()) {
                        MessageReactionsCache.clearForEventIds(allEventIds)
                        MessageVersionsCache.clearForEventIds(allEventIds)
                    }
                    
                    if (BuildConfig.DEBUG) Log.d(TAG, "Cleared all room caches and marked all rooms as needing pagination (no opened rooms, also cleared all profiles, members, receipts, reactions, versions)")
                } else {
                    // Preserve caches for currently opened rooms
                    val roomsToClear = roomEventsCache.keys.filter { it !in openedRooms }.toSet()
                    val roomsToPreserve = roomEventsCache.keys.filter { it in openedRooms }.toSet()
                
                    // Collect eventIds for rooms being cleared
                    val eventIdsToClear = roomsToClear.flatMap { roomId ->
                        roomEventsCache[roomId]?.eventIds ?: emptySet()
                    }.toSet()
                
                    // Clear caches for non-opened rooms
                    for (roomId in roomsToClear) {
                        roomEventsCache.remove(roomId)
                        roomsInitialized.remove(roomId)
                        activelyCachedRooms.remove(roomId)
                        
                        // Clear all related caches for this room
                        ProfileCache.clearRoom(roomId)
                        RoomMemberCache.clearRoom(roomId)
                        ReadReceiptCache.clearRoom(roomId)
                    }
                    
                    // Clear event-specific caches for all cleared rooms' events
                    if (eventIdsToClear.isNotEmpty()) {
                        ReadReceiptCache.clearForEventIds(eventIdsToClear)
                        MessageReactionsCache.clearForEventIds(eventIdsToClear)
                        MessageVersionsCache.clearForEventIds(eventIdsToClear)
                    }
                
                    // Preserve opened rooms:
                    // - Keep cache (roomEventsCache) - already preserved by not removing
                    // - Keep roomsInitialized flag - already preserved by not removing
                    // - Keep activelyCachedRooms - already preserved by not removing
                    // Opened rooms continue receiving events from sync_complete and remain actively cached
                
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Cleared caches for ${roomsToClear.size} rooms, preserved ${roomsToPreserve.size} opened rooms: ${roomsToPreserve.joinToString(", ")}")
                    }
                }
            }
        }
    }
    
    /**
     * Mark a room as actively cached (should receive events from sync_complete)
     * Called when a room is opened and paginated
     */
    fun markRoomAsCached(roomId: String) {
        synchronized(cacheLock) {
            synchronized(cacheStateLock) {
                activelyCachedRooms.add(roomId)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Marked room $roomId as actively cached (will receive events from sync_complete)")
        }
    }
    
    /**
     * Mark a room as no longer cached (should not receive events from sync_complete)
     * Called when leaving a room or when cache is cleared
     */
    fun markRoomAsNotCached(roomId: String) {
        synchronized(cacheLock) {
            synchronized(cacheStateLock) {
                activelyCachedRooms.remove(roomId)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Marked room $roomId as not cached (will not receive events from sync_complete)")
        }
    }
    
    /**
     * Get the set of rooms that are actively cached and should receive events from sync_complete
     * Used by SyncIngestor to determine which rooms to update
     */
    fun getActivelyCachedRoomIds(): Set<String> {
        synchronized(cacheLock) {
            synchronized(cacheStateLock) {
                return activelyCachedRooms.toSet()
            }
        }
    }
    
    /**
     * Check if a room is actively cached (should receive events from sync_complete)
     */
    fun isRoomActivelyCached(roomId: String): Boolean {
        synchronized(cacheLock) {
            synchronized(cacheStateLock) {
                return activelyCachedRooms.contains(roomId)
            }
        }
    }
    
    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): Map<String, Any> {
        synchronized(cacheLock) {
            return mapOf(
                "total_rooms_cached" to roomEventsCache.size,
                "total_rooms_initialized" to roomsInitialized.size,
                "total_events_cached" to roomEventsCache.values.sumOf { it.events.size },
                "cache_details" to roomEventsCache.mapValues { it.value.events.size }
            )
        }
    }
    
    /**
     * Parse events from JSONArray into TimelineEvent objects
     */
    private fun parseEventsFromArray(eventsArray: JSONArray, memberMap: Map<String, MemberProfile>): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()
        var filteredCount = 0
        var filteredReasons = mutableMapOf<String, Int>()
        
        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i) ?: continue
            
            try {
                val event = TimelineEvent.fromJson(eventJson)
                
                // Define allowed event types that should appear in timeline
                // These match the allowedEventTypes in RoomTimelineScreen and BubbleTimelineScreen
                val allowedEventTypes = setOf(
                    "m.room.message",
                    "m.room.encrypted",
                    "m.room.member",
                    "m.room.name",
                    "m.room.topic",
                    "m.room.avatar",
                    "m.room.pinned_events",
                    "m.sticker",
                    "io.element.call.encryption_keys"
                )
                
                // Check if this is a kick (leave event where sender != state_key)
                // Kicks should appear in timeline even with negative timelineRowid
                val isKick = event.type == "m.room.member" && 
                            event.timelineRowid < 0 && 
                            event.stateKey != null &&
                            event.sender != event.stateKey &&
                            event.content?.optString("membership") == "leave"
                
                // Filtering logic:
                // 1. Store reaction events separately (they're filtered from timeline but needed to restore reactions)
                // 2. Filter out member state events (timelineRowid < 0) UNLESS they're kicks
                // 3. Store redaction events separately (they're needed to show deleted messages)
                // 4. Allow all other allowed event types regardless of timelineRowid
                //    (timelineRowid can be negative for many valid timeline events, including messages)
                val shouldCache = when {
                    event.type == "m.reaction" -> {
                        // Store reaction events separately - they're needed to restore reactions when reopening a room
                        true // Will be handled separately in addEventsToCache
                    }
                    event.type == "m.room.member" && event.timelineRowid < 0 && !isKick -> false
                    event.type == "m.room.redaction" -> {
                        // Store redaction events separately - they're needed to show deleted messages
                        // Similar to how we store edit events
                        true // Will be handled separately
                    }
                    allowedEventTypes.contains(event.type) -> true  // Allow all allowed types regardless of timelineRowid
                    else -> false
                }
                
                if (shouldCache) {
                    // Redaction events are handled separately in addEventsToCache
                    // They're added here so they get processed
                    events.add(event)
                    if (BuildConfig.DEBUG) {
                        if (isKick) {
                            Log.d(TAG, "Cached kick event: ${event.eventId} type=${event.type} sender=${event.sender} stateKey=${event.stateKey} timelineRowid=${event.timelineRowid}")
                        } else if (event.type == "m.room.redaction") {
                            val redactsId = event.content?.optString("redacts") ?: event.content?.optJSONObject("redacts")?.optString("event_id")
                            Log.d(TAG, "Cached redaction event: ${event.eventId} type=${event.type} sender=${event.sender} redacts=${redactsId} timelineRowid=${event.timelineRowid}")
                        } else {
                            Log.d(TAG, "Cached event: ${event.eventId} type=${event.type} sender=${event.sender} timelineRowid=${event.timelineRowid}")
                        }
                    }
                } else {
                    // Log why event was filtered
                    val reason = when {
                        event.type == "m.reaction" -> "type = m.reaction"
                        event.type == "m.room.member" && event.timelineRowid < 0 -> "type = m.room.member (state event, not a kick)"
                        else -> "type not in allowed event types"
                    }
                    filteredCount++
                    filteredReasons[reason] = (filteredReasons[reason] ?: 0) + 1
                    if (BuildConfig.DEBUG) Log.d(TAG, "Filtered event: ${event.eventId} type=${event.type} sender=${event.sender} timelineRowid=${event.timelineRowid} reason=[$reason]")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse event from sync: ${e.message}")
            }
        }
        
        if (filteredCount > 0) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Filtered $filteredCount events from cache. Reasons: $filteredReasons")
        }
        
        return events
    }
}

