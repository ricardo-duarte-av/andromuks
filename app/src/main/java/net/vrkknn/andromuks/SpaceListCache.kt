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
    
    // Order of space IDs (from top_level_spaces) so list matches server order
    private var spaceOrder = listOf<String>()
    
    // Store space_edges as a serialized string to avoid JSONObject deep-copy overhead.
    // JSONObject.toString() is cheap at write time; we parse back to JSONObject on demand at read time.
    private var cachedSpaceEdgesJson: String? = null
    
    private val cacheLock = Any()
    
    /**
     * Update or add spaces to the cache.
     * List order is preserved (should match top_level_spaces from sync).
     */
    fun updateSpaces(spaces: List<SpaceItem>) {
        synchronized(cacheLock) {
            spaceCache.clear()
            spaceOrder = spaces.map { it.id }
            spaces.forEach { space ->
                spaceCache[space.id] = space
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SpaceListCache: Updated ${spaces.size} spaces in cache (order preserved)")
            }
        }
    }
    
    /**
     * Update or add a single space to the cache.
     * Does not change space order; new space is appended for ordering purposes if not in spaceOrder.
     */
    fun updateSpace(space: SpaceItem) {
        synchronized(cacheLock) {
            spaceCache[space.id] = space
            if (space.id !in spaceOrder) {
                spaceOrder = spaceOrder + space.id
            }
        }
    }
    
    /**
     * Remove a space from the cache
     */
    fun removeSpace(spaceId: String) {
        synchronized(cacheLock) {
            spaceCache.remove(spaceId)
            spaceOrder = spaceOrder.filter { it != spaceId }
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
     * Get all spaces from the cache in top_level_spaces order.
     */
    fun getAllSpaces(): List<SpaceItem> {
        return synchronized(cacheLock) {
            spaceOrder.mapNotNull { spaceCache[it] }
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
            cachedSpaceEdgesJson = spaceEdges?.toString()
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
            cachedSpaceEdgesJson?.let { JSONObject(it) }
        }
    }
    
    /**
     * Clear all spaces and space_edges from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            spaceCache.clear()
            spaceOrder = emptyList()
            cachedSpaceEdgesJson = null
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

