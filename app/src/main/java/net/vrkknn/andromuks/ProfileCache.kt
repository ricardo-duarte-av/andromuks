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
        return synchronized(cacheLock) {
            globalProfileCache[userId]
        }
    }
    
    /**
     * Set a global profile entry
     */
    fun setGlobalProfile(userId: String, entry: CachedProfileEntry) {
        synchronized(cacheLock) {
            globalProfileCache[userId] = entry
        }
    }
    
    /**
     * Get a global profile (returns the profile, not the entry)
     */
    fun getGlobalProfileProfile(userId: String): MemberProfile? {
        return synchronized(cacheLock) {
            globalProfileCache[userId]?.profile
        }
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
        val key = "$roomId:$userId"
        return synchronized(cacheLock) {
            flattenedMemberCache[key]
        }
    }
    
    /**
     * Set a room-specific profile in flattened cache
     */
    fun setFlattenedProfile(roomId: String, userId: String, profile: MemberProfile) {
        val key = "$roomId:$userId"
        synchronized(cacheLock) {
            flattenedMemberCache[key] = profile
        }
    }
    
    /**
     * Remove a room-specific profile from flattened cache
     */
    fun removeFlattenedProfile(roomId: String, userId: String) {
        val key = "$roomId:$userId"
        synchronized(cacheLock) {
            flattenedMemberCache.remove(key)
        }
    }
    
    /**
     * Check if a flattened profile exists
     */
    fun hasFlattenedProfile(roomId: String, userId: String): Boolean {
        val key = "$roomId:$userId"
        return synchronized(cacheLock) {
            flattenedMemberCache.containsKey(key)
        }
    }
    
    /**
     * Get all user IDs for a room from the index
     */
    fun getRoomUserIds(roomId: String): Set<String>? {
        return synchronized(cacheLock) {
            roomMemberIndex[roomId]
        }
    }
    
    /**
     * Add a user ID to a room's index
     */
    fun addToRoomIndex(roomId: String, userId: String) {
        synchronized(cacheLock) {
            roomMemberIndex.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
        }
    }
    
    /**
     * Remove a user ID from a room's index
     */
    fun removeFromRoomIndex(roomId: String, userId: String) {
        synchronized(cacheLock) {
            roomMemberIndex[roomId]?.remove(userId)
        }
    }
    
    /**
     * Check if any flattened entries exist for a room
     */
    fun hasFlattenedEntriesForRoom(roomId: String): Boolean {
        return synchronized(cacheLock) {
            flattenedMemberCache.keys.any { it.startsWith("$roomId:") }
        }
    }
    
    /**
     * Get all flattened profiles for a room
     */
    fun getFlattenedProfilesForRoom(roomId: String): Map<String, MemberProfile> {
        val prefix = "$roomId:"
        return synchronized(cacheLock) {
            flattenedMemberCache.filterKeys { it.startsWith(prefix) }
                .mapKeys { it.key.removePrefix(prefix) }
        }
    }
    
    /**
     * Get the size of the flattened cache
     */
    fun getFlattenedCacheSize(): Int {
        return synchronized(cacheLock) {
            flattenedMemberCache.size
        }
    }
    
    /**
     * Get the size of the global cache
     */
    fun getGlobalCacheSize(): Int {
        return synchronized(cacheLock) {
            globalProfileCache.size
        }
    }
    
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
                // For flattened cache, we can't easily track access times
                // So we'll remove entries for rooms that are no longer indexed
                val indexedUserIds = roomMemberIndex.values.flatten().toSet()
                val keysToRemove = flattenedMemberCache.keys.filter { key ->
                    val userId = key.substringAfter(":")
                    !indexedUserIds.contains(userId)
                }
                
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
            // Remove from flattened cache
            val keysToRemove = flattenedMemberCache.keys.filter { it.startsWith("$roomId:") }
            keysToRemove.forEach { flattenedMemberCache.remove(it) }
            
            // Remove from index
            roomMemberIndex.remove(roomId)
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

