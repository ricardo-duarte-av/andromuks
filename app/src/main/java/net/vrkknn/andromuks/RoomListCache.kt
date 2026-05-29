package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.RoomMetadataStore
import java.util.concurrent.ConcurrentHashMap

/**
 * RoomListCache - Singleton cache for room list data (RoomItem objects)
 * 
 * This singleton stores room data received via sync_complete messages.
 * It allows any AppViewModel instance to access the complete room list,
 * even when opening from notifications (which creates new AppViewModel instances).
 * 
 * Benefits:
 * - Persistent across AppViewModel instances (crucial for notification navigation)
 * - Single source of truth for room list data
 * - Updated by SpaceRoomParser via AppViewModel.processParsedSyncResult
 * - Accessible by any AppViewModel to populate its roomMap
 */
object RoomListCache {
    private const val TAG = "RoomListCache"

    // Thread-safe map storing room data: roomId -> RoomItem
    private val roomCache = ConcurrentHashMap<String, RoomItem>()
    private val cacheLock = Any()

    // Latest known event per room: roomId -> (eventId, timestamp)
    // Updated from every sync_complete and paginate response so mark_read always has a target.
    private val latestEventCache = ConcurrentHashMap<String, Pair<String, Long>>()
    
    /**
     * Update or add a room to the cache
     */
    fun updateRoom(room: RoomItem) {
        synchronized(cacheLock) {
            roomCache[room.id] = room
        }
        persistMetadata(room)
    }

    /**
     * Update multiple rooms in the cache
     */
    fun updateRooms(rooms: Map<String, RoomItem>) {
        synchronized(cacheLock) {
            roomCache.putAll(rooms)
        }
        rooms.values.forEach { persistMetadata(it) }
    }

    /**
     * Mirror room display name + avatar into the persistent metadata store so that
     * cold-start UIs (notifications, bubbles, shortcuts) can render something
     * meaningful before the first sync_complete arrives.
     *
     * Skips the write when the parser couldn't resolve a real name (it falls back
     * to the raw roomId in that case — persisting that would be misleading).
     */
    private fun persistMetadata(room: RoomItem) {
        val name = room.name.takeIf { it.isNotBlank() && it != room.id }
        val avatar = room.avatarUrl
        if (name != null || avatar != null) {
            RoomMetadataStore.upsertNameAvatar(room.id, name, avatar)
        }
        // Always try to persist the sorting timestamp — it lets the cached room list
        // render in the correct order on cold start. upsertSortTs is a no-op for
        // null/zero/older values, so this is safe to call unconditionally.
        room.sortingTimestamp?.let { ts ->
            if (ts > 0L) RoomMetadataStore.upsertSortTs(room.id, ts)
        }
    }
    
    /**
     * Remove a room from the cache
     */
    fun removeRoom(roomId: String) {
        synchronized(cacheLock) {
            roomCache.remove(roomId)
        }
        // Symmetric with updateRoom -> persistMetadata: a removed room must also drop its
        // persisted row, or hydrateFromDisk resurrects it on the next cold start. Without this,
        // a room left while the app is open reappears (briefly) in the cached list on next launch
        // until the clear_state diff-prune catches it.
        RoomMetadataStore.remove(roomId)
    }
    
    /**
     * Get a room from the cache
     */
    fun getRoom(roomId: String): RoomItem? {
        return synchronized(cacheLock) {
            roomCache[roomId]
        }
    }
    
    /**
     * Get all rooms from the cache
     */
    fun getAllRooms(): Map<String, RoomItem> {
        // ConcurrentHashMap is already thread-safe; return an unmodifiable view to prevent
        // callers from mutating the cache without paying the O(N) copy cost.
        return java.util.Collections.unmodifiableMap(roomCache)
    }
    
    /**
     * Get the number of rooms in the cache
     */
    fun getRoomCount(): Int {
        return synchronized(cacheLock) {
            roomCache.size
        }
    }
    
    /**
     * Record the latest event seen for a room. Only advances forward (higher timestamp wins).
     */
    fun updateLatestEvent(roomId: String, eventId: String, timestamp: Long) {
        val current = latestEventCache[roomId]
        if (current == null || timestamp > current.second) {
            latestEventCache[roomId] = Pair(eventId, timestamp)
        }
    }

    /**
     * Return the event_id of the most recent event seen for [roomId], or null if unknown.
     */
    fun getLatestEventId(roomId: String): String? = latestEventCache[roomId]?.first

    /**
     * Seed the cache from [RoomMetadataStore] at process start so cold-start UIs render
     * something before sync_complete arrives. Only fills slots that aren't already
     * populated by an in-memory RoomItem (a warm AppViewModel survives ahead of us).
     *
     * The resulting stub RoomItems carry only id / name / avatarUrl / bridgeProtocolAvatarUrl;
     * preview, sender, unread counts, and sortingTimestamp are intentionally left null/zero —
     * sync_complete will overwrite them within a second or two.
     */
    fun hydrateFromDisk() {
        val rows = try {
            RoomMetadataStore.loadAll()
        } catch (t: Throwable) {
            Log.w(TAG, "hydrateFromDisk: loadAll failed", t)
            return
        }
        if (rows.isEmpty()) return
        var seeded = 0
        synchronized(cacheLock) {
            for ((roomId, row) in rows) {
                if (roomCache.containsKey(roomId)) continue
                val bridgeAvatar = row.bridgeAvatarMxc?.takeIf { it.isNotEmpty() }
                roomCache[roomId] = RoomItem(
                    id = roomId,
                    name = row.name ?: roomId,
                    messagePreview = null,
                    messageSender = null,
                    unreadCount = null,
                    highlightCount = null,
                    avatarUrl = row.avatarMxc?.takeIf { it.isNotEmpty() },
                    sortingTimestamp = row.sortTs.takeIf { it > 0L },
                    bridgeProtocolAvatarUrl = bridgeAvatar,
                )
                seeded++
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "hydrateFromDisk: seeded $seeded room(s) from disk")
    }

    /**
     * Clear all rooms from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            roomCache.clear()
            latestEventCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "RoomListCache: Cleared all rooms")
        }
    }
    
    /**
     * Check if cache is empty or suspiciously small (e.g., only 1 room)
     */
    fun isSuspiciouslySmall(): Boolean {
        val count = getRoomCount()
        return count <= 1
    }
}

