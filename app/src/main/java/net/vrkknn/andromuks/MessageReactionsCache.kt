package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * MessageReactionsCache - Singleton cache for message reactions
 * 
 * This singleton stores message reactions received via sync_complete and pagination.
 * It allows any AppViewModel instance to access reaction data, even when opening from notifications.
 * 
 * Structure: eventId -> List<MessageReaction>
 */
object MessageReactionsCache {
    private const val TAG = "MessageReactionsCache"
    
    // Thread-safe map storing reactions: eventId -> List<MessageReaction>
    private val reactionsCache = ConcurrentHashMap<String, List<MessageReaction>>()
    private val cacheLock = Any()
    
    /**
     * Update or add reactions for an event
     */
    fun updateReactions(eventId: String, reactions: List<MessageReaction>) {
        synchronized(cacheLock) {
            if (reactions.isEmpty()) {
                reactionsCache.remove(eventId)
            } else {
                reactionsCache[eventId] = reactions
            }
        }
    }
    
    /**
     * Update all reactions from a map
     */
    fun setAll(reactionsMap: Map<String, List<MessageReaction>>) {
        synchronized(cacheLock) {
            reactionsCache.clear()
            reactionsCache.putAll(reactionsMap)
            if (BuildConfig.DEBUG) Log.d(TAG, "MessageReactionsCache: setAll - updated cache with ${reactionsMap.size} events")
        }
    }
    
    /**
     * Get reactions for a specific event
     */
    fun getReactions(eventId: String): List<MessageReaction> {
        return synchronized(cacheLock) {
            reactionsCache[eventId] ?: emptyList()
        }
    }
    
    /**
     * Get all reactions from the cache
     */
    fun getAllReactions(): Map<String, List<MessageReaction>> {
        return synchronized(cacheLock) {
            HashMap(reactionsCache) // Return a copy to avoid concurrent modification
        }
    }
    
    /**
     * Get the number of events with reactions
     */
    fun getEventCount(): Int {
        return synchronized(cacheLock) {
            reactionsCache.size
        }
    }
    
    /**
     * Clear all reactions from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            reactionsCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "MessageReactionsCache: Cleared all reactions")
        }
    }
    
    /**
     * Clear reactions for a specific room (by checking eventIds)
     * Note: This is a simple implementation - in practice, you might want to track roomId -> eventIds
     */
    fun clearForRoom(roomId: String) {
        // This would require tracking roomId -> eventIds mapping
        // For now, we'll just clear all (caller should handle room-specific clearing)
        if (BuildConfig.DEBUG) Log.d(TAG, "MessageReactionsCache: clearForRoom called for $roomId (clearing all - room tracking not implemented)")
        clear()
    }
}

