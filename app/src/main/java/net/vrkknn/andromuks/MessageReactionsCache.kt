package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

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

    // Reaction state is not Compose state, so every AppViewModel registers here and bumps its own
    // reactionUpdateCounter when the cache changes. Without this, mutations made by callers that
    // don't go through ReactionCoordinator — the room-eviction clears in RoomTimelineCache,
    // TimelineCacheCoordinator and SyncRoomsCoordinator — left stale badges painted until some
    // unrelated reaction happened to trigger a repaint.
    private val changeListeners = CopyOnWriteArraySet<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    /** Always called outside [cacheLock] so a listener can never invert the lock order. */
    private fun notifyChanged() {
        changeListeners.forEach { it() }
    }

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
        notifyChanged()
    }

    /**
     * Atomically read-modify-write the bucket for a single event.
     *
     * This is the only safe way to apply an incremental reaction change. Callers must NOT read
     * [getAllReactions], transform the copy and hand it back to [setAll]: `sync_complete` batches
     * apply several reactions in a row and defer their writes to the Main thread, so every one of
     * them would read the same pre-batch snapshot and the last write would clobber the rest (and
     * [setAll] clears the map first, so the losers are deleted outright, not merely skipped).
     *
     * @return true when [transform] actually changed the bucket, i.e. the UI needs a repaint.
     */
    fun mutate(eventId: String, transform: (List<MessageReaction>) -> List<MessageReaction>): Boolean {
        val changed = synchronized(cacheLock) {
            val before = reactionsCache[eventId] ?: emptyList()
            val after = transform(before)
            if (after == before) {
                false
            } else {
                if (after.isEmpty()) reactionsCache.remove(eventId) else reactionsCache[eventId] = after
                true
            }
        }
        if (changed) notifyChanged()
        return changed
    }

    /**
     * Atomically merge a batch of incoming buckets, one [transform] call per event, under a single
     * lock acquisition. Used by the aggregated-reactions repair path, which touches many events at
     * once and must not race the live `sync_complete` ingest.
     *
     * @return true when at least one bucket changed.
     */
    fun merge(
        incoming: Map<String, List<MessageReaction>>,
        transform: (existing: List<MessageReaction>, incoming: List<MessageReaction>) -> List<MessageReaction>,
    ): Boolean {
        val changed = synchronized(cacheLock) {
            var any = false
            for ((eventId, incomingReactions) in incoming) {
                val before = reactionsCache[eventId] ?: emptyList()
                val after = transform(before, incomingReactions)
                if (after == before) continue
                if (after.isEmpty()) reactionsCache.remove(eventId) else reactionsCache[eventId] = after
                any = true
            }
            any
        }
        if (changed) notifyChanged()
        return changed
    }

    /**
     * Update all reactions from a map.
     *
     * Wholesale replacement — only valid for resets (`emptyMap()`) and cold hydration. Incremental
     * changes must go through [mutate] or [merge]; see the note there.
     */
    fun setAll(reactionsMap: Map<String, List<MessageReaction>>) {
        synchronized(cacheLock) {
            reactionsCache.clear()
            reactionsCache.putAll(reactionsMap)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "MessageReactionsCache: setAll - updated cache with ${reactionsMap.size} events",
                )
            }
        }
        notifyChanged()
    }

    /**
     * Get reactions for a specific event
     */
    fun getReactions(eventId: String): List<MessageReaction> = synchronized(cacheLock) {
        reactionsCache[eventId] ?: emptyList()
    }

    /**
     * Get all reactions from the cache
     */
    fun getAllReactions(): Map<String, List<MessageReaction>> = synchronized(cacheLock) {
        HashMap(reactionsCache) // Return a copy to avoid concurrent modification
    }

    /**
     * Get the number of events with reactions
     */
    fun getEventCount(): Int = synchronized(cacheLock) {
        reactionsCache.size
    }

    /**
     * Clear all reactions from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            reactionsCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "MessageReactionsCache: Cleared all reactions")
        }
        notifyChanged()
    }

    /**
     * Clear reactions for a specific room (by checking eventIds)
     * Note: This is a simple implementation - in practice, you might want to track roomId -> eventIds
     */
    fun clearForRoom(roomId: String) {
        // This would require tracking roomId -> eventIds mapping
        // For now, we'll just clear all (caller should handle room-specific clearing)
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "MessageReactionsCache: clearForRoom called for $roomId (clearing all - room tracking not implemented)",
            )
        }
        clear()
    }

    /**
     * Clear reactions for specific event IDs (used when evicting a room)
     */
    fun clearForEventIds(eventIds: Set<String>) {
        val removedCount = synchronized(cacheLock) {
            var removed = 0
            eventIds.forEach { eventId ->
                if (reactionsCache.remove(eventId) != null) {
                    removed++
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "MessageReactionsCache: Cleared reactions for $removed events (out of ${eventIds.size} requested)",
                )
            }
            removed
        }
        if (removedCount > 0) notifyChanged()
    }
}
