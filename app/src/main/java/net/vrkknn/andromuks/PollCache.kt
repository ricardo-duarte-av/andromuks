package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.utils.PollResults
import java.util.concurrent.ConcurrentHashMap

/**
 * PollCache - Singleton cache for aggregated poll state (MSC3381).
 *
 * Holds the *computed* results for each poll, so any AppViewModel instance (main activity, chat
 * bubble, notification-opened room) renders the same vote counts without re-deriving them.
 *
 * Structure: poll start eventId -> [PollResults]
 *
 * The raw response/end events these are derived from live in `RoomTimelineCache.pollEvents`; this
 * cache is the derived view, exactly as [MessageReactionsCache] is for reactions.
 */
object PollCache {
    private const val TAG = "PollCache"

    private val pollsCache = ConcurrentHashMap<String, PollResults>()
    private val cacheLock = Any()

    /** Store or replace the aggregated results for one poll. */
    fun updatePoll(pollStartEventId: String, results: PollResults) {
        synchronized(cacheLock) {
            pollsCache[pollStartEventId] = results
        }
    }

    /** Replace the whole cache (used when rebuilding a room's polls from scratch). */
    fun setAll(polls: Map<String, PollResults>) {
        synchronized(cacheLock) {
            pollsCache.clear()
            pollsCache.putAll(polls)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "PollCache: setAll - updated cache with ${polls.size} polls")
            }
        }
    }

    /** Merge the given polls in, leaving other rooms' entries untouched. */
    fun putAll(polls: Map<String, PollResults>) {
        if (polls.isEmpty()) return
        synchronized(cacheLock) {
            pollsCache.putAll(polls)
        }
    }

    fun getPoll(pollStartEventId: String): PollResults? = synchronized(cacheLock) {
        pollsCache[pollStartEventId]
    }

    fun getAllPolls(): Map<String, PollResults> = synchronized(cacheLock) {
        HashMap(pollsCache) // Copy to avoid concurrent modification by callers
    }

    fun getPollCount(): Int = synchronized(cacheLock) { pollsCache.size }

    fun clear() {
        synchronized(cacheLock) {
            pollsCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "PollCache: Cleared all polls")
        }
    }

    /**
     * Clear polls for the given event IDs (used when a room is evicted or trimmed).
     *
     * The keys here are poll *start* event IDs, which are ordinary timeline events, so the same
     * evicted-event-id set used for [MessageReactionsCache.clearForEventIds] applies directly.
     */
    fun clearForEventIds(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        synchronized(cacheLock) {
            var removedCount = 0
            eventIds.forEach { eventId ->
                if (pollsCache.remove(eventId) != null) {
                    removedCount++
                }
            }
            if (BuildConfig.DEBUG && removedCount > 0) {
                Log.d(TAG, "PollCache: Cleared $removedCount polls (out of ${eventIds.size} event IDs requested)")
            }
        }
    }
}
