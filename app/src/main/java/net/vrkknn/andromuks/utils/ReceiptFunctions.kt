package net.vrkknn.andromuks.utils

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
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

/**
 * Composable function to display read receipt avatars inline with messages.
 * 
 * @param receipts List of read receipts to display
 * @param userProfileCache Cache of user profiles for avatar display
 * @param homeserverUrl URL of the Matrix homeserver
 * @param authToken Authentication token for API requests
 * @param appViewModel ViewModel for additional functionality
 * @param messageSender The sender of the message (to exclude from read receipts)
 */
@Composable
fun InlineReadReceiptAvatars(
    receipts: List<ReadReceipt>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    messageSender: String
) {
    val context = LocalContext.current
    
    // Filter out the message sender from read receipts
    val filteredReceipts = receipts.filter { it.userId != messageSender }
    
    if (filteredReceipts.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Show up to 3 avatars, with a "+X" indicator if there are more
            val maxAvatars = 3
            val avatarsToShow = filteredReceipts.take(maxAvatars)
            val remainingCount = filteredReceipts.size - maxAvatars
            
            avatarsToShow.forEach { receipt ->
                val userProfile = userProfileCache[receipt.userId]
                val avatarUrl = userProfile?.avatarUrl
                
                IconButton(
                    onClick = { 
                        // TODO: Handle avatar click - maybe show user profile or read receipt details
                        Log.d("Andromuks", "Read receipt avatar clicked for user: ${receipt.userId}")
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    if (avatarUrl != null && avatarUrl.isNotEmpty()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(avatarUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Avatar for ${userProfile.displayName ?: receipt.userId}",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        // Fallback to a default avatar icon
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Default avatar",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Show "+X" indicator if there are more than maxAvatars
            if (remainingCount > 0) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "+$remainingCount more",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}