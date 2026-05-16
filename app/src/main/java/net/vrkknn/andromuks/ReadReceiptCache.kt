package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * ReadReceiptCache - Singleton cache for read receipts, partitioned by room.
 *
 * Structure:
 *   Forward index : roomId → eventId → List<ReadReceipt>
 *   Inverted index: roomId → userId  → eventId   (O(1) "where is this user's receipt?" lookup)
 *
 * The inverted index eliminates the O(events × users) scan that previously occurred every time
 * a receipt moved to a new event during sync processing.
 */
object ReadReceiptCache {
    private const val TAG = "ReadReceiptCache"

    // roomId → eventId → receipts
    private val receiptCache = ConcurrentHashMap<String, HashMap<String, MutableList<ReadReceipt>>>()
    // roomId → userId → eventId  (inverted index)
    private val userEventIndex = ConcurrentHashMap<String, HashMap<String, String>>()
    private val cacheLock = Any()

    /**
     * Replace a single room's receipts and rebuild its inverted index from [userIndex].
     * Other rooms are untouched.
     */
    fun setForRoom(
        roomId: String,
        receiptsMap: Map<String, List<ReadReceipt>>,
        userIndex: Map<String, String>
    ) {
        synchronized(cacheLock) {
            val roomCache = HashMap<String, MutableList<ReadReceipt>>(receiptsMap.size)
            receiptsMap.forEach { (eventId, receipts) ->
                if (receipts.isNotEmpty()) roomCache[eventId] = receipts.toMutableList()
            }
            receiptCache[roomId] = roomCache
            userEventIndex[roomId] = HashMap(userIndex)
            if (BuildConfig.DEBUG) Log.d(TAG, "setForRoom $roomId: ${roomCache.size} events, ${userIndex.size} indexed users")
        }
    }

    /**
     * Get all receipts for a single room (eventId → immutable list).
     */
    fun getForRoom(roomId: String): Map<String, List<ReadReceipt>> {
        return synchronized(cacheLock) {
            receiptCache[roomId]?.mapValues { it.value.toList() } ?: emptyMap()
        }
    }

    /**
     * O(1) lookup: which eventId does [userId] have a receipt on in [roomId]?
     */
    fun getUserEventId(roomId: String, userId: String): String? {
        return synchronized(cacheLock) {
            userEventIndex[roomId]?.get(userId)
        }
    }

    /**
     * IDs of all rooms that have cached receipts.
     */
    fun getRoomIds(): Set<String> {
        return synchronized(cacheLock) { receiptCache.keys.toSet() }
    }

    /**
     * Clear all receipts for [roomId] — O(1).
     */
    fun clearRoom(roomId: String) {
        synchronized(cacheLock) {
            receiptCache.remove(roomId)
            userEventIndex.remove(roomId)
            if (BuildConfig.DEBUG) Log.d(TAG, "clearRoom $roomId")
        }
    }

    /**
     * Remove specific events from a room's cache. Also removes their users from the inverted index.
     */
    fun clearForEventIds(roomId: String, eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        synchronized(cacheLock) {
            val roomCache = receiptCache[roomId] ?: return@synchronized
            val roomIndex = userEventIndex[roomId]
            var removed = 0
            eventIds.forEach { eventId ->
                val receipts = roomCache.remove(eventId)
                if (receipts != null) {
                    removed++
                    // Remove from inverted index only if the index still points to this event
                    if (roomIndex != null) {
                        receipts.forEach { r ->
                            if (roomIndex[r.userId] == eventId) roomIndex.remove(r.userId)
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "clearForEventIds $roomId: removed $removed/${eventIds.size} events")
        }
    }

    /**
     * Clear the entire cache (all rooms).
     */
    fun clear() {
        synchronized(cacheLock) {
            receiptCache.clear()
            userEventIndex.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "Cleared all receipts")
        }
    }
}
