package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * PendingInvitesCache - Singleton cache for pending room invites
 * 
 * This singleton stores room invites received via sync_complete.
 * It allows any AppViewModel instance to access pending invites, even when opening from notifications.
 * 
 * Structure: roomId -> RoomInvite
 */
object PendingInvitesCache {
    private const val TAG = "PendingInvitesCache"
    
    // Thread-safe map storing invites: roomId -> RoomInvite
    private val invitesCache = ConcurrentHashMap<String, RoomInvite>()
    private val cacheLock = Any()
    
    /**
     * Update or add an invite to the cache
     */
    fun updateInvite(invite: RoomInvite) {
        synchronized(cacheLock) {
            invitesCache[invite.roomId] = invite
        }
    }
    
    /**
     * Update multiple invites
     */
    fun updateInvites(invites: Map<String, RoomInvite>) {
        synchronized(cacheLock) {
            invitesCache.putAll(invites)
        }
    }
    
    /**
     * Remove an invite from the cache
     */
    fun removeInvite(roomId: String) {
        synchronized(cacheLock) {
            invitesCache.remove(roomId)
        }
    }
    
    /**
     * Get an invite from the cache
     */
    fun getInvite(roomId: String): RoomInvite? {
        return synchronized(cacheLock) {
            invitesCache[roomId]
        }
    }
    
    /**
     * Get all invites from the cache
     */
    fun getAllInvites(): Map<String, RoomInvite> {
        return synchronized(cacheLock) {
            HashMap(invitesCache) // Return a copy to avoid concurrent modification
        }
    }
    
    /**
     * Get the number of invites in the cache
     */
    fun getInviteCount(): Int {
        return synchronized(cacheLock) {
            invitesCache.size
        }
    }
    
    /**
     * Clear all invites from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            invitesCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "PendingInvitesCache: Cleared all invites")
        }
    }
}

