package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * SpaceListCache - Singleton cache for space list data (SpaceItem objects and space_edges)
 * 
 * This singleton stores space data received via sync_complete messages.
 * It allows any AppViewModel instance to access the complete space list,
 * even when opening from notifications (which creates new AppViewModel instances).
 * 
 * Benefits:
 * - Persistent across AppViewModel instances (crucial for notification navigation)
 * - Single source of truth for space list data
 * - Updated by SpaceRoomParser via AppViewModel.updateAllSpaces
 * - Accessible by any AppViewModel to populate its allSpaces
 */
object SpaceListCache {
    private const val TAG = "SpaceListCache"
    
    // Thread-safe map storing space data: spaceId -> SpaceItem
    private val spaceCache = ConcurrentHashMap<String, SpaceItem>()
    
    // Thread-safe storage for space_edges JSONObject
    private var cachedSpaceEdges: JSONObject? = null
    
    private val cacheLock = Any()
    
    /**
     * Update or add spaces to the cache
     */
    fun updateSpaces(spaces: List<SpaceItem>) {
        synchronized(cacheLock) {
            // Clear existing spaces and add new ones
            spaceCache.clear()
            spaces.forEach { space ->
                spaceCache[space.id] = space
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SpaceListCache: Updated ${spaces.size} spaces in cache")
            }
        }
    }
    
    /**
     * Update or add a single space to the cache
     */
    fun updateSpace(space: SpaceItem) {
        synchronized(cacheLock) {
            spaceCache[space.id] = space
        }
    }
    
    /**
     * Remove a space from the cache
     */
    fun removeSpace(spaceId: String) {
        synchronized(cacheLock) {
            spaceCache.remove(spaceId)
        }
    }
    
    /**
     * Get a space from the cache
     */
    fun getSpace(spaceId: String): SpaceItem? {
        return synchronized(cacheLock) {
            spaceCache[spaceId]
        }
    }
    
    /**
     * Get all spaces from the cache
     */
    fun getAllSpaces(): List<SpaceItem> {
        return synchronized(cacheLock) {
            spaceCache.values.toList()
        }
    }
    
    /**
     * Get the number of spaces in the cache
     */
    fun getSpaceCount(): Int {
        return synchronized(cacheLock) {
            spaceCache.size
        }
    }
    
    /**
     * Store space_edges JSONObject
     */
    fun setSpaceEdges(spaceEdges: JSONObject?) {
        synchronized(cacheLock) {
            cachedSpaceEdges = spaceEdges?.let { JSONObject(it.toString()) } // Deep copy
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SpaceListCache: Updated space_edges (${if (spaceEdges != null) "present" else "null"})")
            }
        }
    }
    
    /**
     * Get space_edges JSONObject
     */
    fun getSpaceEdges(): JSONObject? {
        return synchronized(cacheLock) {
            cachedSpaceEdges?.let { JSONObject(it.toString()) } // Return a copy
        }
    }
    
    /**
     * Clear all spaces and space_edges from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            spaceCache.clear()
            cachedSpaceEdges = null
            if (BuildConfig.DEBUG) Log.d(TAG, "SpaceListCache: Cleared all spaces and space_edges")
        }
    }
    
    /**
     * Check if cache is empty
     */
    fun isEmpty(): Boolean {
        return synchronized(cacheLock) {
            spaceCache.isEmpty()
        }
    }
}

