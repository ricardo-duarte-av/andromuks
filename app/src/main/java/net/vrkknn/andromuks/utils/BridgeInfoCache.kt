package net.vrkknn.andromuks.utils

import android.content.Context
import android.content.SharedPreferences
import net.vrkknn.andromuks.BuildConfig

/**
 * Cache for bridge protocol avatar URLs in SharedPreferences
 * 
 * Stores bridge info per room_id:
 * - If room is bridged: stores the mxc:// URL of the bridge protocol avatar
 * - If room is not bridged: stores empty string ""
 * 
 * This allows us to skip get_room_state requests for rooms we already know about.
 */
object BridgeInfoCache {
    private const val PREFS_NAME = "AndromuksAppPrefs"
    private const val BRIDGE_INFO_PREFIX = "bridge_avatar_"
    
    /**
     * Get bridge protocol avatar URL for a room from cache
     * @return mxc:// URL if room is bridged, empty string if not bridged, null if not cached
     */
    fun getBridgeAvatarUrl(context: Context, roomId: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = BRIDGE_INFO_PREFIX + roomId
        
        // Check if key exists (to distinguish between "not bridged" and "not cached")
        if (!prefs.contains(key)) {
            return null // Not cached
        }
        
        val avatarUrl = prefs.getString(key, null) ?: ""
        return avatarUrl // Empty string means "not bridged", non-empty means "bridged with this avatar"
    }
    
    /**
     * Check if a room's bridge info is cached
     */
    fun isCached(context: Context, roomId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = BRIDGE_INFO_PREFIX + roomId
        return prefs.contains(key)
    }
    
    /**
     * Save bridge protocol avatar URL for a room
     * @param avatarUrl mxc:// URL if room is bridged, empty string if not bridged
     */
    fun saveBridgeAvatarUrl(context: Context, roomId: String, avatarUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = BRIDGE_INFO_PREFIX + roomId
        
        val editor = prefs.edit()
        editor.putString(key, avatarUrl)
        editor.apply() // Use apply() for async write (not critical path)
        
        if (BuildConfig.DEBUG) {
            if (avatarUrl.isNotEmpty()) {
                android.util.Log.d("Andromuks", "BridgeInfoCache: Saved bridge avatar for $roomId: $avatarUrl")
            } else {
                android.util.Log.d("Andromuks", "BridgeInfoCache: Saved 'not bridged' for $roomId")
            }
        }
    }
    
    /**
     * Remove bridge info for a room (e.g., when room is left)
     */
    fun removeBridgeInfo(context: Context, roomId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = BRIDGE_INFO_PREFIX + roomId
        
        val editor = prefs.edit()
        editor.remove(key)
        editor.apply()
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "BridgeInfoCache: Removed bridge info for $roomId")
        }
    }
    
    /**
     * Clear all bridge info (e.g., on logout)
     */
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Get all keys with bridge prefix
        val allKeys = prefs.all.keys.filter { it.startsWith(BRIDGE_INFO_PREFIX) }
        allKeys.forEach { key ->
            editor.remove(key)
        }
        
        editor.apply()
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "BridgeInfoCache: Cleared ${allKeys.size} bridge info entries")
        }
    }
}

