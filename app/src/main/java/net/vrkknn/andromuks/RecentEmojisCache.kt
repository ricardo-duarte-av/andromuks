package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * RecentEmojisCache - Singleton cache for recently used emojis
 * 
 * This singleton stores the list of recently used emojis for reactions.
 * It allows any AppViewModel instance to access recent emojis, even when opening from notifications.
 * 
 * Structure: List<String> (emoji strings, ordered by frequency)
 */
object RecentEmojisCache {
    private const val TAG = "RecentEmojisCache"
    
    // Thread-safe list storing recent emojis
    private val emojisList = mutableListOf<String>()
    private val cacheLock = Any()
    
    /**
     * Set the list of recent emojis
     */
    fun set(emojis: List<String>) {
        synchronized(cacheLock) {
            emojisList.clear()
            emojisList.addAll(emojis)
            if (BuildConfig.DEBUG) Log.d(TAG, "RecentEmojisCache: set - updated cache with ${emojis.size} emojis")
        }
    }
    
    /**
     * Get all recent emojis
     */
    fun getAll(): List<String> {
        return synchronized(cacheLock) {
            emojisList.toList() // Return a copy
        }
    }
    
    /**
     * Get the number of recent emojis
     */
    fun getCount(): Int {
        return synchronized(cacheLock) {
            emojisList.size
        }
    }
    
    /**
     * Clear all emojis from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            emojisList.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "RecentEmojisCache: Cleared all emojis")
        }
    }
}

