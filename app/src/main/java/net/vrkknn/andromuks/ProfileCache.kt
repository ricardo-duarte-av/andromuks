package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * ProfileCache - Singleton cache for user profiles
 * 
 * This singleton stores user profiles in three caches:
 * - globalProfileCache: Global profiles (canonical profiles from explicit profile requests)
 * - flattenedMemberCache: Room-specific profiles (when profile differs from global)
 * - roomMemberIndex: Index of which users have profiles for each room
 * 
 * It allows any AppViewModel instance to access profiles, ensuring consistency across the app.
 */
object ProfileCache {
    private const val TAG = "ProfileCache"
    
    // Global user profile cache with access timestamps for LRU-style cleanup
    data class CachedProfileEntry(var profile: MemberProfile, var lastAccess: Long)
    
    // Thread-safe caches
    private val globalProfileCache = ConcurrentHashMap<String, CachedProfileEntry>()
    private val flattenedMemberCache = ConcurrentHashMap<String, MemberProfile>() // Key: "roomId:userId"
    private val roomMemberIndex = ConcurrentHashMap<String, MutableSet<String>>() // Key: roomId, Value: Set of userIds
    private val cacheLock = Any()
    
    // Constants for cache limits
    private const val MAX_MEMBER_CACHE_SIZE = 5000
    
    /**
     * Get a global profile entry
     */
    fun getGlobalProfile(userId: String): CachedProfileEntry? {
        return globalProfileCache[userId]
    }

    /**
     * Set a global profile entry
     */
    fun setGlobalProfile(userId: String, entry: CachedProfileEntry) {
        globalProfileCache[userId] = entry
    }

    /**
     * Get a global profile (returns the profile, not the entry)
     */
    fun getGlobalProfileProfile(userId: String): MemberProfile? {
        return globalProfileCache[userId]?.profile
    }
    
    /**
     * Update last access time for a global profile
     */
    fun updateGlobalProfileAccess(userId: String) {
        synchronized(cacheLock) {
            globalProfileCache[userId]?.lastAccess = System.currentTimeMillis()
        }
    }
    
    /**
     * Get a room-specific profile from flattened cache
     */
    fun getFlattenedProfile(roomId: String, userId: String): MemberProfile? {
        return flattenedMemberCache["$roomId:$userId"]
    }

    /**
     * Set a room-specific profile in flattened cache
     */
    fun setFlattenedProfile(roomId: String, userId: String, profile: MemberProfile) {
        flattenedMemberCache["$roomId:$userId"] = profile
    }

    /**
     * Remove a room-specific profile from flattened cache
     */
    fun removeFlattenedProfile(roomId: String, userId: String) {
        flattenedMemberCache.remove("$roomId:$userId")
    }

    /**
     * Check if a flattened profile exists
     */
    fun hasFlattenedProfile(roomId: String, userId: String): Boolean {
        return flattenedMemberCache.containsKey("$roomId:$userId")
    }

    /**
     * Get all user IDs for a room from the index
     */
    fun getRoomUserIds(roomId: String): Set<String>? {
        return roomMemberIndex[roomId]
    }

    /**
     * Add a user ID to a room's index
     */
    fun addToRoomIndex(roomId: String, userId: String) {
        roomMemberIndex.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
    }

    /**
     * Remove a user ID from a room's index
     */
    fun removeFromRoomIndex(roomId: String, userId: String) {
        roomMemberIndex[roomId]?.remove(userId)
    }
    
    /**
     * Get the size of the flattened cache
     */
    fun getFlattenedCacheSize(): Int = flattenedMemberCache.size

    /**
     * Get the size of the global cache
     */
    fun getGlobalCacheSize(): Int = globalProfileCache.size
    
    /**
     * Cleanup old global profiles (LRU-style)
     */
    fun cleanupGlobalProfiles(maxSize: Int = MAX_MEMBER_CACHE_SIZE) {
        synchronized(cacheLock) {
            if (globalProfileCache.size > maxSize) {
                val overflow = globalProfileCache.size - maxSize
                val oldestKeys = globalProfileCache.entries
                    .sortedBy { it.value.lastAccess }
                    .take(overflow)
                    .map { it.key }
                
                oldestKeys.forEach { globalProfileCache.remove(it) }
                if (BuildConfig.DEBUG) Log.d(TAG, "ProfileCache: Cleaned up $overflow old global profiles")
            }
        }
    }
    
    /**
     * Cleanup old flattened profiles (LRU-style)
     */
    fun cleanupFlattenedProfiles(maxSize: Int = MAX_MEMBER_CACHE_SIZE) {
        synchronized(cacheLock) {
            if (flattenedMemberCache.size > maxSize) {
                // Build the exact set of valid keys from the index — avoids flatten() overhead
                // and correctly handles Matrix IDs that contain colons (e.g. @user:server.com).
                val validKeys = buildSet<String> {
                    for ((roomId, userIds) in roomMemberIndex) {
                        for (userId in userIds) add("$roomId:$userId")
                    }
                }
                val keysToRemove = flattenedMemberCache.keys.filter { it !in validKeys }
                keysToRemove.take(maxSize / 2).forEach { flattenedMemberCache.remove(it) }
                if (BuildConfig.DEBUG) Log.d(TAG, "ProfileCache: Cleaned up ${keysToRemove.size} old flattened profiles")
            }
        }
    }
    
    /**
     * Remove all room-specific entries for a user that now match the global profile
     */
    fun cleanupMatchingRoomProfiles(userId: String, globalProfile: MemberProfile) {
        synchronized(cacheLock) {
            val keysToRemove = mutableListOf<String>()
            for ((key, roomProfile) in flattenedMemberCache) {
                if (key.endsWith(":$userId")) {
                    // Check if room profile now matches the global profile
                    if (roomProfile.displayName == globalProfile.displayName &&
                        roomProfile.avatarUrl == globalProfile.avatarUrl) {
                        keysToRemove.add(key)
                        // Also remove from index
                        val roomId = key.substringBefore(":")
                        roomMemberIndex[roomId]?.remove(userId)
                    }
                }
            }
            keysToRemove.forEach { flattenedMemberCache.remove(it) }
        }
    }
    
    /**
     * Clear all caches
     */
    fun clear() {
        synchronized(cacheLock) {
            globalProfileCache.clear()
            flattenedMemberCache.clear()
            roomMemberIndex.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "ProfileCache: Cleared all caches")
        }
    }
    
    /**
     * Clear all profiles for a specific room
     */
    fun clearRoom(roomId: String) {
        synchronized(cacheLock) {
            roomMemberIndex.remove(roomId)?.forEach { userId ->
                flattenedMemberCache.remove("$roomId:$userId")
            }
        }
    }
    
    /**
     * Get all flattened profiles (for iteration)
     */
    fun getAllFlattenedProfiles(): Map<String, MemberProfile> {
        return synchronized(cacheLock) {
            flattenedMemberCache.toMap()
        }
    }
    
    /**
     * Get all global profiles (for iteration)
     */
    fun getAllGlobalProfiles(): Map<String, CachedProfileEntry> {
        return synchronized(cacheLock) {
            globalProfileCache.toMap()
        }
    }
}

