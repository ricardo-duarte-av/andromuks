package net.vrkknn.andromuks

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import net.vrkknn.andromuks.BuildConfig

/**
 * RoomTimelineCache - Singleton cache for timeline events from sync_complete messages
 * 
 * This singleton stores timeline events received via sync_complete for all rooms.
 * When opening a room, if we have enough cached events (>= target count),
 * we can render immediately without waiting for a paginate request.
 * 
 * Benefits:
 * - Instant room opening if events are already in cache
 * - Reduces unnecessary paginate requests
 * - Better UX with always-on WebSocket
 * - Persistent across AppViewModel instances (crucial for shortcut navigation)
 */
object RoomTimelineCache {
    private const val TAG = "RoomTimelineCache"
    // No limit on events per room - all events are kept in cache
    // LRU eviction based on room access times handles memory management
    private const val TARGET_EVENTS_FOR_INSTANT_RENDER = 50 // Minimum events to skip paginate
    private const val MAX_ROOMS_IN_CACHE = 30 // Limit number of rooms kept in RAM at once
    
    private data class RoomCache(
        val events: MutableList<TimelineEvent> = mutableListOf(),
        val eventIds: MutableSet<String> = mutableSetOf(),
        var lastAccessedAt: Long = System.currentTimeMillis()
    )

    // Per-room cache: roomId -> RoomCache
    private val roomEventsCache = object : LinkedHashMap<String, RoomCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RoomCache>?): Boolean {
            val shouldRemove = size > MAX_ROOMS_IN_CACHE
            if (shouldRemove && eldest != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Evicting room ${eldest.key} from timeline cache (max rooms $MAX_ROOMS_IN_CACHE)")
                roomsInitialized.remove(eldest.key)
                eldest.value.events.clear()
                eldest.value.eventIds.clear()
            }
            return shouldRemove
        }
    }
    
    // Track which rooms have received their initial paginate (to avoid duplicate caching)
    private val roomsInitialized = mutableSetOf<String>()

    /**
     * Adds events into the in-memory cache for a room. Performs deduplication, keeps events ordered
     * by timeline_rowid (and timestamp fallback), and enforces max size limits.
     */
    private fun addEventsToCache(roomId: String, incomingEvents: List<TimelineEvent>): Int {
        if (incomingEvents.isEmpty()) return 0

        val cache = roomEventsCache.getOrPut(roomId) { RoomCache() }
        // Update access time when events are added (for LRU eviction)
        cache.lastAccessedAt = System.currentTimeMillis()

        var addedCount = 0
        for (event in incomingEvents) {
            // CRASH FIX: Filter out null events before processing
            if (event == null) {
                Log.w(TAG, "Null event found in incomingEvents for room $roomId, skipping")
                continue
            }
            if (event.eventId.isBlank()) continue
            if (cache.eventIds.add(event.eventId)) {
                cache.events.add(event)
                addedCount++
            }
        }

        if (addedCount == 0) {
            return 0
        }

        // Ensure deterministic ordering: primary by timeline_rowid (ascending), fallback to timestamp, then eventId
        // Note: timelineRowid can be negative, and negative numbers are naturally smaller than positive ones
        // So we can just compare timelineRowid directly - no need for special handling
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

        // No limit on events per room - all events are kept
        // LRU eviction of entire rooms handles memory management
        // (Event limit removed - keep all events in cache)

        return addedCount
    }
    
    /**
     * Add events from sync_complete to the cache for a specific room
     * Includes deduplication to prevent duplicate events
     */
    fun addEventsFromSync(roomId: String, eventsArray: JSONArray, memberMap: Map<String, MemberProfile>) {
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
    
    /**
     * Get cached events for a room
     * Returns null if not enough events cached, otherwise returns the cached events
     */
    /**
     * Mark a room as accessed (updates lastAccessedAt for LRU eviction)
     */
    fun markRoomAccessed(roomId: String) {
        roomEventsCache[roomId]?.lastAccessedAt = System.currentTimeMillis()
        // Accessing the map also updates LinkedHashMap order for LRU
        roomEventsCache[roomId]
    }
    
    fun getCachedEvents(roomId: String): List<TimelineEvent>? {
        val cache = roomEventsCache[roomId] ?: return null
        
        // Mark room as accessed for LRU eviction
        cache.lastAccessedAt = System.currentTimeMillis()
        
        return if (cache.events.size >= TARGET_EVENTS_FOR_INSTANT_RENDER) {
            if (BuildConfig.DEBUG) {
                val firstEvent = cache.events.firstOrNull()
                val lastEvent = cache.events.lastOrNull()
                if (BuildConfig.DEBUG) Log.d(
                    TAG,
                    "Cache hit for room $roomId: ${cache.events.size} events available (>= $TARGET_EVENTS_FOR_INSTANT_RENDER). " +
                    "first=${firstEvent?.eventId}@${firstEvent?.timestamp}, last=${lastEvent?.eventId}@${lastEvent?.timestamp}"
                )
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "Cache hit for room $roomId: ${cache.events.size} events available (>= $TARGET_EVENTS_FOR_INSTANT_RENDER)")
            }
            cache.events.toList() // Return a copy
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Cache miss for room $roomId: only ${cache.events.size} events (need >= $TARGET_EVENTS_FOR_INSTANT_RENDER)")
            null
        }
    }
    
    /**
     * Get cached events for a room with lower threshold for notification scenarios
     * Returns cached events if there are at least 10 events, null otherwise
     * This helps avoid loading spinners when opening rooms from notifications
     */
    fun getCachedEventsForNotification(roomId: String): List<TimelineEvent>? {
        val cache = roomEventsCache[roomId] ?: return null
        
        return if (cache.events.size >= 10) { // Lower threshold for notification scenarios
            if (BuildConfig.DEBUG) Log.d(TAG, "Cache hit for notification room $roomId: ${cache.events.size} events available (>= 10)")
            cache.events.toList() // Return a copy
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Cache miss for notification room $roomId: only ${cache.events.size} events (need >= 10)")
            null
        }
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
        return roomEventsCache[roomId]?.events?.size ?: 0
    }

    /**
     * Get metadata for the most recent cached event in a room
     */
    fun getLatestCachedEventMetadata(roomId: String): CachedEventMetadata? {
        val cache = roomEventsCache[roomId] ?: return null
        val latest = cache.events.lastOrNull() ?: return null
        return CachedEventMetadata(
            eventId = latest.eventId,
            timelineRowId = latest.timelineRowid,
            timestamp = latest.timestamp
        )
    }

    fun getOldestCachedEventMetadata(roomId: String): CachedEventMetadata? {
        val cache = roomEventsCache[roomId] ?: return null
        val oldest = cache.events.firstOrNull() ?: return null
        return CachedEventMetadata(
            eventId = oldest.eventId,
            timelineRowId = oldest.timelineRowid,
            timestamp = oldest.timestamp
        )
    }
    
    /**
     * Get the oldest cached event's timeline_rowid for pagination
     * Returns the actual oldest event's timelineRowid (can be negative - that's valid!)
     * Returns -1 if no cached events
     */
    fun getOldestCachedEventRowId(roomId: String): Long {
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
    
    /**
     * Check if we have some cached events but not enough for instant render
     */
    fun hasPartialCache(roomId: String): Boolean {
        val count = getCachedEventCount(roomId)
        return count in 10 until TARGET_EVENTS_FOR_INSTANT_RENDER
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
        if (BuildConfig.DEBUG) Log.d(TAG, "Seeding cache for room $roomId with ${events.size} paginated events")
        
        val cache = roomEventsCache.getOrPut(roomId) { RoomCache() }
        cache.events.clear()
        cache.eventIds.clear()
        addEventsToCache(roomId, events)

        // Mark as initialized
        markRoomInitialized(roomId)
        if (BuildConfig.DEBUG) Log.d(TAG, "Room $roomId cache seeded and initialized with ${getCachedEventCount(roomId)} events")
    }
    
    /**
     * Merge new paginated events with existing cache (for "load more" operations)
     */
    fun mergePaginatedEvents(roomId: String, newEvents: List<TimelineEvent>) {
        if (newEvents.isEmpty()) {
            return
        }
        
        val minRowId = newEvents.mapNotNull { it.timelineRowid.takeIf { row -> row > 0 } }.minOrNull()
        val maxRowId = newEvents.mapNotNull { it.timelineRowid.takeIf { row -> row > 0 } }.maxOrNull()
        val minRowDisplay = minRowId?.toString() ?: "n/a"
        val maxRowDisplay = maxRowId?.toString() ?: "n/a"
        
        val cacheBefore = getCachedEventCount(roomId)
        val oldestRowIdBefore = getOldestCachedEventRowId(roomId)

        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "Merging ${newEvents.size} events for room $roomId - rowId range: $minRowDisplay to $maxRowDisplay (cache before: $cacheBefore, oldestRowId: $oldestRowIdBefore)"
        )
        
        val added = addEventsToCache(roomId, newEvents)
        val cacheAfter = getCachedEventCount(roomId)
        val oldestRowIdAfter = getOldestCachedEventRowId(roomId)
        
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
    }
    
    /**
     * Clear cache for a specific room (useful when leaving a room)
     */
    fun clearRoomCache(roomId: String) {
        roomEventsCache.remove(roomId)
        roomsInitialized.remove(roomId)
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleared cache for room $roomId")
    }
    
    /**
     * Clear all caches (useful on logout)
     */
    fun clearAllCaches() {
        roomEventsCache.clear()
        roomsInitialized.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "Cleared all room caches")
    }
    
    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "total_rooms_cached" to roomEventsCache.size,
            "total_rooms_initialized" to roomsInitialized.size,
            "total_events_cached" to roomEventsCache.values.sumOf { it.events.size },
            "cache_details" to roomEventsCache.mapValues { it.value.events.size }
        )
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
                
                // Cache message events regardless of timelineRowid (to catch pending/failed sends)
                // Only filter out reactions and state member events (timelineRowid = -1)
                val shouldCache = when {
                    event.type == "m.reaction" -> false
                    event.type == "m.room.member" && event.timelineRowid < 0 -> false
                    event.type == "m.room.message" || event.type == "m.room.encrypted" || event.type == "m.sticker" -> true
                    event.timelineRowid >= 0 -> true  // Any other event with valid timelineRowid
                    else -> false
                }
                
                if (shouldCache) {
                    events.add(event)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Cached event: ${event.eventId} type=${event.type} sender=${event.sender} timelineRowid=${event.timelineRowid}")
                } else {
                    // Log why event was filtered
                    val reason = when {
                        event.type == "m.reaction" -> "type = m.reaction"
                        event.type == "m.room.member" && event.timelineRowid < 0 -> "type = m.room.member (state event)"
                        event.timelineRowid < 0 -> "timelineRowid < 0 AND not a message type"
                        else -> "unknown"
                    }
                    filteredCount++
                    filteredReasons[reason] = (filteredReasons[reason] ?: 0) + 1
                    if (BuildConfig.DEBUG) Log.d(TAG, "Filtered event: ${event.eventId} type=${event.type} sender=${event.sender} reason=[$reason]")
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

