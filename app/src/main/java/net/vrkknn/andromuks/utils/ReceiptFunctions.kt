package net.vrkknn.andromuks.utils

import android.util.Log
import net.vrkknn.andromuks.ReadReceipt
import org.json.JSONObject

/**
 * Utility functions for handling read receipts in Matrix rooms.
 */
object ReceiptFunctions {
    
    /**
     * Processes read receipts from a JSON response.
     * 
     * @param receiptsJson JSON object containing read receipts
     * @param readReceiptsMap Mutable map to store the processed receipts (eventId -> list of receipts)
     * @param updateCounter Counter to trigger UI updates (will be incremented)
     */
    fun processReadReceipts(
        receiptsJson: JSONObject,
        readReceiptsMap: MutableMap<String, MutableList<ReadReceipt>>,
        updateCounter: () -> Unit
    ) {
        Log.d("Andromuks", "ReceiptFunctions: processReadReceipts called with ${receiptsJson.length()} event receipts")
        val keys = receiptsJson.keys()
        var totalReceipts = 0
        
        while (keys.hasNext()) {
            val eventId = keys.next()
            val receiptsArray = receiptsJson.optJSONArray(eventId)
            if (receiptsArray != null) {
                val receiptsList = mutableListOf<ReadReceipt>()
                for (i in 0 until receiptsArray.length()) {
                    val receiptJson = receiptsArray.optJSONObject(i)
                    if (receiptJson != null) {
                        val receipt = ReadReceipt(
                            userId = receiptJson.optString("user_id", ""),
                            eventId = receiptJson.optString("event_id", ""),
                            timestamp = receiptJson.optLong("timestamp", 0),
                            receiptType = receiptJson.optString("receipt_type", "")
                        )
                        receiptsList.add(receipt)
                        Log.d("Andromuks", "ReceiptFunctions: Processed read receipt: ${receipt.userId} read ${receipt.eventId}")
                        totalReceipts++
                    }
                }
                if (receiptsList.isNotEmpty()) {
                    readReceiptsMap[eventId] = receiptsList
                    Log.d("Andromuks", "ReceiptFunctions: Added ${receiptsList.size} read receipts for event: $eventId")
                }
            }
        }
        Log.d("Andromuks", "ReceiptFunctions: processReadReceipts completed - processed $totalReceipts total receipts, triggering UI update")
        updateCounter()
    }
    
    /**
     * Gets read receipts for a specific event.
     * 
     * @param eventId The event ID to get receipts for
     * @param readReceiptsMap Map containing the read receipts
     * @return List of read receipts for the event, or empty list if none found
     */
    fun getReadReceipts(
        eventId: String,
        readReceiptsMap: Map<String, MutableList<ReadReceipt>>
    ): List<ReadReceipt> {
        return readReceiptsMap[eventId]?.toList() ?: emptyList()
    }
}