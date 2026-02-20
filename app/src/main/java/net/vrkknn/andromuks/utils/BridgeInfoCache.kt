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
    private const val BRIDGE_DISPLAY_NAME_PREFIX = "bridge_displayname_"
    
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
     * Get bridge protocol display name for a room from cache
     * @return Display name (e.g., "WhatsApp", "Telegram") if room is bridged, null if not cached or not bridged
     */
    fun getBridgeDisplayName(context: Context, roomId: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = BRIDGE_DISPLAY_NAME_PREFIX + roomId
        
        if (!prefs.contains(key)) {
            return null // Not cached
        }
        
        val displayName = prefs.getString(key, null) ?: ""
        return displayName.takeIf { it.isNotEmpty() } // Return null if empty (not bridged)
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
        
    }
    
    /**
     * Save bridge protocol display name for a room
     * @param displayName Display name (e.g., "WhatsApp", "Telegram") if room is bridged, empty string if not bridged
     */
    fun saveBridgeDisplayName(context: Context, roomId: String, displayName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = BRIDGE_DISPLAY_NAME_PREFIX + roomId
        
        val editor = prefs.edit()
        editor.putString(key, displayName)
        editor.apply() // Use apply() for async write (not critical path)
        
    }
    
    /**
     * Remove bridge info for a room (e.g., when room is left)
     */
    fun removeBridgeInfo(context: Context, roomId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val avatarKey = BRIDGE_INFO_PREFIX + roomId
        val displayNameKey = BRIDGE_DISPLAY_NAME_PREFIX + roomId
        
        val editor = prefs.edit()
        editor.remove(avatarKey)
        editor.remove(displayNameKey)
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
        
        // Get all keys with bridge prefix (both avatar and display name)
        val allKeys = prefs.all.keys.filter { 
            it.startsWith(BRIDGE_INFO_PREFIX) || it.startsWith(BRIDGE_DISPLAY_NAME_PREFIX) 
        }
        allKeys.forEach { key ->
            editor.remove(key)
        }
        
        editor.apply()
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "BridgeInfoCache: Cleared ${allKeys.size} bridge info entries")
        }
    }
}

