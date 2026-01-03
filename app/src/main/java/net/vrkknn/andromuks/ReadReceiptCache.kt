package net.vrkknn.andromuks

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * ReadReceiptCache - Singleton cache for read receipts
 * 
 * This singleton stores read receipts received via sync_complete messages.
 * It allows any AppViewModel instance to access the complete read receipt data,
 * even when opening from notifications (which creates new AppViewModel instances).
 * 
 * Benefits:
 * - Persistent across AppViewModel instances (crucial for notification navigation)
 * - Single source of truth for read receipt data
 * - Updated when receipts are processed from sync_complete
 * - Accessible by any AppViewModel to populate its readReceipts map
 * 
 * Structure: eventId -> List<ReadReceipt>
 */
object ReadReceiptCache {
    private const val TAG = "ReadReceiptCache"
    
    // Thread-safe map storing read receipts: eventId -> List<ReadReceipt>
    private val receiptCache = ConcurrentHashMap<String, MutableList<ReadReceipt>>()
    private val cacheLock = Any()
    
    /**
     * Replace the entire cache with new data
     * Used when updating cache after processing receipts from sync_complete
     * The receiptsMap should already be processed by ReceiptFunctions.processReadReceipts
     */
    fun setAll(receiptsMap: Map<String, List<ReadReceipt>>) {
        synchronized(cacheLock) {
            receiptCache.clear()
            receiptsMap.forEach { (eventId, receipts) ->
                if (receipts.isNotEmpty()) {
                    receiptCache[eventId] = receipts.toMutableList()
                }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "ReadReceiptCache: setAll - updated cache with ${receiptsMap.size} events")
        }
    }
    
    /**
     * Get all receipts from the cache
     */
    fun getAllReceipts(): Map<String, List<ReadReceipt>> {
        return synchronized(cacheLock) {
            receiptCache.mapValues { it.value.toList() }.toMap()
        }
    }
    
    /**
     * Get receipts for a specific event
     */
    fun getReceiptsForEvent(eventId: String): List<ReadReceipt> {
        return synchronized(cacheLock) {
            receiptCache[eventId]?.toList() ?: emptyList()
        }
    }
    
    /**
     * Get the number of events with receipts
     */
    fun getEventCount(): Int {
        return synchronized(cacheLock) {
            receiptCache.size
        }
    }
    
    /**
     * Clear all receipts from the cache
     */
    fun clear() {
        synchronized(cacheLock) {
            receiptCache.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "ReadReceiptCache: Cleared all receipts")
        }
    }
}

