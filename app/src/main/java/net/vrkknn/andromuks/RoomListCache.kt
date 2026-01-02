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
        return synchronized(cacheLock) {
            HashMap(roomCache) // Return a copy to avoid concurrent modification
        }
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
     * Clear all rooms from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            roomCache.clear()
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

