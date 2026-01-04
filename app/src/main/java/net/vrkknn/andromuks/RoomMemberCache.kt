package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * RoomMemberCache - Singleton cache for room member profiles
 * 
 * This singleton stores member profiles organized by room and user.
 * It allows any AppViewModel instance to access member profiles, even when opening from notifications.
 * 
 * Structure: roomId -> (userId -> MemberProfile)
 */
object RoomMemberCache {
    private const val TAG = "RoomMemberCache"
    
    // Thread-safe nested map: roomId -> (userId -> MemberProfile)
    private val memberCache = ConcurrentHashMap<String, ConcurrentHashMap<String, MemberProfile>>()
    private val cacheLock = Any()
    
    /**
     * Update or add a member profile
     */
    fun updateMember(roomId: String, userId: String, profile: MemberProfile) {
        synchronized(cacheLock) {
            val roomMembers = memberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
            roomMembers[userId] = profile
        }
    }
    
    /**
     * Update multiple members for a room
     */
    fun updateMembers(roomId: String, members: Map<String, MemberProfile>) {
        synchronized(cacheLock) {
            val roomMembers = memberCache.computeIfAbsent(roomId) { ConcurrentHashMap() }
            roomMembers.putAll(members)
        }
    }
    
    /**
     * Get a member profile
     */
    fun getMember(roomId: String, userId: String): MemberProfile? {
        return synchronized(cacheLock) {
            memberCache[roomId]?.get(userId)
        }
    }
    
    /**
     * Get all members for a room
     */
    fun getRoomMembers(roomId: String): Map<String, MemberProfile> {
        return synchronized(cacheLock) {
            memberCache[roomId]?.toMap() ?: emptyMap()
        }
    }
    
    /**
     * Get all members from all rooms
     */
    fun getAllMembers(): Map<String, Map<String, MemberProfile>> {
        return synchronized(cacheLock) {
            memberCache.mapValues { it.value.toMap() }.toMap()
        }
    }
    
    /**
     * Remove a member from a room
     */
    fun removeMember(roomId: String, userId: String) {
        synchronized(cacheLock) {
            memberCache[roomId]?.remove(userId)
        }
    }
    
    /**
     * Clear all members for a room
     */
    fun clearRoom(roomId: String) {
        synchronized(cacheLock) {
            memberCache.remove(roomId)
        }
    }
    
    /**
     * Get the number of rooms with cached members
     */
    fun getRoomCount(): Int {
        return synchronized(cacheLock) {
            memberCache.size
        }
    }
    
    /**
     * Clear all members from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            memberCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "RoomMemberCache: Cleared all members")
        }
    }
}

