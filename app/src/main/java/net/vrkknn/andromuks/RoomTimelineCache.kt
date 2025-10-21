package net.vrkknn.andromuks

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * RoomTimelineCache - Caches timeline events from sync_complete messages
 * 
 * This class stores timeline events received via sync_complete for all rooms.
 * When opening a room, if we have enough cached events (>= target count),
 * we can render immediately without waiting for a paginate request.
 * 
 * Benefits:
 * - Instant room opening if events are already in cache
 * - Reduces unnecessary paginate requests
 * - Better UX with always-on WebSocket
 */
class RoomTimelineCache {
    companion object {
        private const val TAG = "RoomTimelineCache"
        private const val MAX_EVENTS_PER_ROOM = 150 // Keep more than paginate limit (100) for safety
        private const val TARGET_EVENTS_FOR_INSTANT_RENDER = 100 // Minimum events to skip paginate
    }
    
    private data class RoomCache(
        val events: MutableList<TimelineEvent> = mutableListOf(),
        val eventIds: MutableSet<String> = mutableSetOf()
    )

    // Per-room cache: roomId -> RoomCache
    private val roomEventsCache = mutableMapOf<String, RoomCache>()
    
    // Track which rooms have received their initial paginate (to avoid duplicate caching)
    private val roomsInitialized = mutableSetOf<String>()

    /**
     * Adds events into the in-memory cache for a room. Performs deduplication, keeps events ordered
     * by timeline_rowid (and timestamp fallback), and enforces max size limits.
     */
    private fun addEventsToCache(roomId: String, incomingEvents: List<TimelineEvent>): Int {
        if (incomingEvents.isEmpty()) return 0

        val cache = roomEventsCache.getOrPut(roomId) { RoomCache() }

        var addedCount = 0
        for (event in incomingEvents) {
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
        cache.events.sortWith { a, b ->
            when {
                a.timelineRowid > 0 && b.timelineRowid > 0 -> a.timelineRowid.compareTo(b.timelineRowid)
                a.timelineRowid > 0 -> -1
                b.timelineRowid > 0 -> 1
                else -> {
                    val tsCompare = a.timestamp.compareTo(b.timestamp)
                    if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
                }
            }
        }

        // Trim to max size (keep newest events)
        if (cache.events.size > MAX_EVENTS_PER_ROOM) {
            val toRemove = cache.events.size - MAX_EVENTS_PER_ROOM
            Log.d(TAG, "Trimming cache for room $roomId: removing $toRemove old events")
            val removed = cache.events.subList(0, toRemove)
            removed.forEach { cache.eventIds.remove(it.eventId) }
            removed.clear()
        }

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
        
        Log.d(TAG, "Adding ${events.size} events from sync for room $roomId")
        
        // Get or create cache for this room
        val added = addEventsToCache(roomId, events)
        if (added == 0) {
            Log.d(TAG, "All ${events.size} events already in cache, skipping")
            return
        }
        Log.d(TAG, "Added $added new events to cache (total=${getCachedEventCount(roomId)})")
    }
    
    /**
     * Get cached events for a room
     * Returns null if not enough events cached, otherwise returns the cached events
     */
    fun getCachedEvents(roomId: String): List<TimelineEvent>? {
        val cache = roomEventsCache[roomId] ?: return null
        
        return if (cache.events.size >= TARGET_EVENTS_FOR_INSTANT_RENDER) {
            Log.d(TAG, "Cache hit for room $roomId: ${cache.events.size} events available (>= $TARGET_EVENTS_FOR_INSTANT_RENDER)")
            cache.events.toList() // Return a copy
        } else {
            Log.d(TAG, "Cache miss for room $roomId: only ${cache.events.size} events (need >= $TARGET_EVENTS_FOR_INSTANT_RENDER)")
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
            Log.d(TAG, "Cache hit for notification room $roomId: ${cache.events.size} events available (>= 10)")
            cache.events.toList() // Return a copy
        } else {
            Log.d(TAG, "Cache miss for notification room $roomId: only ${cache.events.size} events (need >= 10)")
            null
        }
    }
    
    /**
     * Get the number of cached events for a room
     */
    fun getCachedEventCount(roomId: String): Int {
        return roomEventsCache[roomId]?.events?.size ?: 0
    }
    
    /**
     * Get the oldest cached event's timeline_rowid for pagination
     * Returns -1 if no cached events or if the oldest event has no timeline_rowid
     */
    fun getOldestCachedEventRowId(roomId: String): Long {
        val cache = roomEventsCache[roomId] ?: return -1L
        if (cache.events.isEmpty()) return -1L
        
        return cache.events.firstOrNull { it.timelineRowid > 0 }?.timelineRowid ?: -1L
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
        Log.d(TAG, "Room $roomId marked as initialized")
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
        Log.d(TAG, "Seeding cache for room $roomId with ${events.size} paginated events")
        
        val cache = roomEventsCache.getOrPut(roomId) { RoomCache() }
        cache.events.clear()
        cache.eventIds.clear()
        addEventsToCache(roomId, events)

        // Mark as initialized
        markRoomInitialized(roomId)
        Log.d(TAG, "Room $roomId cache seeded and initialized with ${getCachedEventCount(roomId)} events")
    }
    
    /**
     * Merge new paginated events with existing cache (for "load more" operations)
     */
    fun mergePaginatedEvents(roomId: String, newEvents: List<TimelineEvent>) {
        if (newEvents.isEmpty()) {
            return
        }
        
        Log.d(TAG, "Merging ${newEvents.size} paginated events into cache for room $roomId")
        
        val added = addEventsToCache(roomId, newEvents)
        Log.d(TAG, "Room $roomId cache after merge: ${getCachedEventCount(roomId)} events (added $added)")
    }
    
    /**
     * Clear cache for a specific room (useful when leaving a room)
     */
    fun clearRoomCache(roomId: String) {
        roomEventsCache.remove(roomId)
        roomsInitialized.remove(roomId)
        Log.d(TAG, "Cleared cache for room $roomId")
    }
    
    /**
     * Clear all caches (useful on logout)
     */
    fun clearAllCaches() {
        roomEventsCache.clear()
        roomsInitialized.clear()
        Log.d(TAG, "Cleared all room caches")
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
                    Log.d(TAG, "Cached event: ${event.eventId} type=${event.type} sender=${event.sender} timelineRowid=${event.timelineRowid}")
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
                    Log.d(TAG, "Filtered event: ${event.eventId} type=${event.type} sender=${event.sender} reason=[$reason]")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse event from sync: ${e.message}")
            }
        }
        
        if (filteredCount > 0) {
            Log.d(TAG, "Filtered $filteredCount events from cache. Reasons: $filteredReasons")
        }
        
        return events
    }
}

