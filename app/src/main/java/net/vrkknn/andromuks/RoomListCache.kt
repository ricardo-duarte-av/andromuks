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
     * Update or add a room to the cache.
     *
     * [persist] = false skips the metadata write — used by the sync_complete apply loop, which
     * mutates the cache per-room but defers persistence to a single [persistMetadataBatch] flush
     * at the end of the batch (see SyncRoomsCoordinator.processParsedSyncResult). All other
     * callers leave it true and persist immediately.
     */
    fun updateRoom(room: RoomItem, persist: Boolean = true) {
        synchronized(cacheLock) {
            roomCache[room.id] = room
        }
        if (persist) persistMetadata(room)
    }

    /**
     * Update multiple rooms in the cache
     */
    fun updateRooms(rooms: Map<String, RoomItem>) {
        synchronized(cacheLock) {
            roomCache.putAll(rooms)
        }
        persistMetadataBatch(rooms.values)
    }

    /**
     * Build the persistable metadata for a room, or null if there's nothing worth persisting.
     *
     * Skips the name when the parser couldn't resolve a real one (it falls back to the raw
     * roomId in that case — persisting that would be misleading). The sorting timestamp lets the
     * cached room list render in the correct order on cold start; [RoomMetadataStore] applies the
     * forward-only guard so a stale value is dropped there.
     */
    private fun metaUpdateFor(room: RoomItem): RoomMetadataStore.MetaUpdate? {
        val name = room.name.takeIf { it.isNotBlank() && it != room.id }
        val avatar = room.avatarUrl
        val sortTs = room.sortingTimestamp?.takeIf { it > 0L }
        if (name == null && avatar == null && sortTs == null) return null
        return RoomMetadataStore.MetaUpdate(room.id, name, avatar, sortTs)
    }

    /**
     * Mirror one room's name/avatar/sort-ts into the persistent metadata store so cold-start UIs
     * (notifications, bubbles, shortcuts) can render something before the first sync_complete.
     */
    private fun persistMetadata(room: RoomItem) {
        val update = metaUpdateFor(room) ?: return
        RoomMetadataStore.upsertMetadataBatchAsync(listOf(update))
    }

    /**
     * Persist metadata for many rooms in a single background SQLite transaction. Lets the
     * sync_complete apply loop coalesce ~500 per-room writes into one transaction on reconnect.
     */
    fun persistMetadataBatch(rooms: Collection<RoomItem>) {
        if (rooms.isEmpty()) return
        val updates = rooms.mapNotNull { metaUpdateFor(it) }
        if (updates.isNotEmpty()) RoomMetadataStore.upsertMetadataBatchAsync(updates)
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

