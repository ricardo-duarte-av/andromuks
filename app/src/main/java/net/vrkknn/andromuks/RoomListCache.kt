package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * RoomListCache - Singleton cache for room list data (RoomItem objects)
 * 
 * This singleton stores room data received via sync_complete messages.
 * It allows any AppViewModel instance to access the complete room list,
 * even when opening from notifications (which creates new AppViewModel instances).
 * 
 * Benefits:
 * - Persistent across AppViewModel instances (crucial for notification navigation)
 * - Single source of truth for room list data
 * - Updated by SpaceRoomParser via AppViewModel.processParsedSyncResult
 * - Accessible by any AppViewModel to populate its roomMap
 */
object RoomListCache {
    private const val TAG = "RoomListCache"

    // Thread-safe map storing room data: roomId -> RoomItem
    private val roomCache = ConcurrentHashMap<String, RoomItem>()
    private val cacheLock = Any()

    // Latest known event per room: roomId -> (eventId, timestamp)
    // Updated from every sync_complete and paginate response so mark_read always has a target.
    private val latestEventCache = ConcurrentHashMap<String, Pair<String, Long>>()
    
    /**
     * Update or add a room to the cache
     */
    fun updateRoom(room: RoomItem) {
        synchronized(cacheLock) {
            roomCache[room.id] = room
        }
    }
    
    /**
     * Update multiple rooms in the cache
     */
    fun updateRooms(rooms: Map<String, RoomItem>) {
        synchronized(cacheLock) {
            roomCache.putAll(rooms)
        }
    }
    
    /**
     * Remove a room from the cache
     */
    fun removeRoom(roomId: String) {
        synchronized(cacheLock) {
            roomCache.remove(roomId)
        }
    }
    
    /**
     * Get a room from the cache
     */
    fun getRoom(roomId: String): RoomItem? {
        return synchronized(cacheLock) {
            roomCache[roomId]
        }
    }
    
    /**
     * Get all rooms from the cache
     */
    fun getAllRooms(): Map<String, RoomItem> {
        // ConcurrentHashMap is already thread-safe; return an unmodifiable view to prevent
        // callers from mutating the cache without paying the O(N) copy cost.
        return java.util.Collections.unmodifiableMap(roomCache)
    }
    
    /**
     * Get the number of rooms in the cache
     */
    fun getRoomCount(): Int {
        return synchronized(cacheLock) {
            roomCache.size
        }
    }
    
    /**
     * Record the latest event seen for a room. Only advances forward (higher timestamp wins).
     */
    fun updateLatestEvent(roomId: String, eventId: String, timestamp: Long) {
        val current = latestEventCache[roomId]
        if (current == null || timestamp > current.second) {
            latestEventCache[roomId] = Pair(eventId, timestamp)
        }
    }

    /**
     * Return the event_id of the most recent event seen for [roomId], or null if unknown.
     */
    fun getLatestEventId(roomId: String): String? = latestEventCache[roomId]?.first

    /**
     * Clear all rooms from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            roomCache.clear()
            latestEventCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "RoomListCache: Cleared all rooms")
        }
    }
    
    /**
     * Check if cache is empty or suspiciously small (e.g., only 1 room)
     */
    fun isSuspiciouslySmall(): Boolean {
        val count = getRoomCount()
        return count <= 1
    }
}

