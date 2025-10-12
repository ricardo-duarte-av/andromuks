package net.vrkknn.andromuks.utils

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReadReceipt
import net.vrkknn.andromuks.ui.components.AvatarImage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility functions for handling read receipts in Matrix rooms.
 */
object ReceiptFunctions {
    
    /**
     * Processes read receipts from a JSON response.
     * 
     * Read receipts represent "User has read the room up to event X", so when a user
     * reads a newer message, we must remove their receipt from all older messages
     * and only show it on the latest message they've read.
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
        
        // Track the latest read receipt for each user
        val userLatestReceipts = mutableMapOf<String, ReadReceipt>()
        
        // First pass: collect all receipts and find the latest for each user
        while (keys.hasNext()) {
            val eventId = keys.next()
            val receiptsArray = receiptsJson.optJSONArray(eventId)
            if (receiptsArray != null) {
                for (i in 0 until receiptsArray.length()) {
                    val receiptJson = receiptsArray.optJSONObject(i)
                    if (receiptJson != null) {
                        val receipt = ReadReceipt(
                            userId = receiptJson.optString("user_id", ""),
                            eventId = receiptJson.optString("event_id", ""),
                            timestamp = receiptJson.optLong("timestamp", 0),
                            receiptType = receiptJson.optString("receipt_type", "")
                        )
                        
                        // Track the latest receipt for this user
                        val existingLatest = userLatestReceipts[receipt.userId]
                        if (existingLatest == null || receipt.timestamp > existingLatest.timestamp) {
                            userLatestReceipts[receipt.userId] = receipt
                        }
                        
                        Log.d("Andromuks", "ReceiptFunctions: Processed read receipt: ${receipt.userId} read ${receipt.eventId} at ${receipt.timestamp}")
                        totalReceipts++
                    }
                }
            }
        }
        
        // Second pass: remove all existing receipts for users who have new receipts
        val usersWithNewReceipts = userLatestReceipts.keys
        for (eventId in readReceiptsMap.keys.toList()) {
            val receiptsList = readReceiptsMap[eventId]
            if (receiptsList != null) {
                // Remove receipts for users who have new receipts
                val filteredReceipts = receiptsList.filter { it.userId !in usersWithNewReceipts }
                if (filteredReceipts.isEmpty()) {
                    readReceiptsMap.remove(eventId)
                    Log.d("Andromuks", "ReceiptFunctions: Removed all receipts for event: $eventId")
                } else {
                    readReceiptsMap[eventId] = filteredReceipts.toMutableList()
                    Log.d("Andromuks", "ReceiptFunctions: Updated receipts for event: $eventId (removed ${receiptsList.size - filteredReceipts.size} receipts)")
                }
            }
        }
        
        // Third pass: add the latest receipts to their respective events
        for ((userId, latestReceipt) in userLatestReceipts) {
            val eventId = latestReceipt.eventId
            val receiptsList = readReceiptsMap.getOrPut(eventId) { mutableListOf() }
            receiptsList.add(latestReceipt)
            Log.d("Andromuks", "ReceiptFunctions: Added latest receipt for user $userId to event: $eventId")
        }
        
        Log.d("Andromuks", "ReceiptFunctions: processReadReceipts completed - processed $totalReceipts total receipts, ${userLatestReceipts.size} unique users, triggering UI update")
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
        readReceiptsMap: Map<String, List<ReadReceipt>>
    ): List<ReadReceipt> {
        //Log.d("Andromuks", "ReceiptFunctions: getReadReceipts called for eventId: $eventId")
        //Log.d("Andromuks", "ReceiptFunctions: readReceiptsMap contains ${readReceiptsMap.size} events")
        //Log.d("ReceiptFunctions", "ReceiptFunctions: Available event IDs: ${readReceiptsMap.keys.joinToString(", ")}")
        
        val receipts = readReceiptsMap[eventId] ?: emptyList()
        //Log.d("Andromuks", "ReceiptFunctions: getReadReceipts($eventId) -> ${receipts.size} receipts")
        
        //if (receipts.isEmpty()) {
        //    Log.d("Andromuks", "ReceiptFunctions: Available receipt event IDs: ${readReceiptsMap.keys.joinToString(", ")}")
        //}
        
        return receipts
    }
    
    /**
     * Removes read receipts for a specific user from all events.
     * This is used when a user reads a newer message, so their receipt
     * should only appear on the latest message they've read.
     * 
     * @param userId The user ID to remove receipts for
     * @param readReceiptsMap Map containing the read receipts
     * @return Number of receipts removed
     */
    fun removeUserReceipts(
        userId: String,
        readReceiptsMap: MutableMap<String, MutableList<ReadReceipt>>
    ): Int {
        Log.d("Andromuks", "ReceiptFunctions: removeUserReceipts called for userId: $userId")
        var removedCount = 0
        
        for (eventId in readReceiptsMap.keys.toList()) {
            val receiptsList = readReceiptsMap[eventId]
            if (receiptsList != null) {
                val originalSize = receiptsList.size
                receiptsList.removeAll { it.userId == userId }
                val newSize = receiptsList.size
                val removed = originalSize - newSize
                
                if (removed > 0) {
                    removedCount += removed
                    Log.d("Andromuks", "ReceiptFunctions: Removed $removed receipts for user $userId from event: $eventId")
                    
                    // Remove the event entry if no receipts remain
                    if (receiptsList.isEmpty()) {
                        readReceiptsMap.remove(eventId)
                        Log.d("Andromuks", "ReceiptFunctions: Removed empty event entry: $eventId")
                    }
                }
            }
        }
        
        Log.d("Andromuks", "ReceiptFunctions: removeUserReceipts completed - removed $removedCount total receipts for user: $userId")
        return removedCount
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
    messageSender: String,
    onUserClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var showReceiptDialog by remember { mutableStateOf(false) }
    
    Log.d("Andromuks", "InlineReadReceiptAvatars: Called with ${receipts.size} receipts for sender: $messageSender")
    
    // Filter out the message sender from read receipts
    val filteredReceipts = receipts.filter { it.userId != messageSender }
    
    Log.d("Andromuks", "InlineReadReceiptAvatars: Filtered to ${filteredReceipts.size} receipts (excluded sender)")
    
    // Show read receipt details dialog
    if (showReceiptDialog) {
        ReadReceiptDetailsDialog(
            receipts = filteredReceipts,
            userProfileCache = userProfileCache,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            onDismiss = { showReceiptDialog = false },
            onUserClick = { userId ->
                showReceiptDialog = false
                onUserClick(userId)
            }
        )
    }
    
    if (filteredReceipts.isNotEmpty()) {
        Log.d("Andromuks", "InlineReadReceiptAvatars: Rendering ${filteredReceipts.size} read receipt avatars")
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 4.dp) // Align with the top of message bubble (which also has top = 4.dp)
        ) {
            // Show up to 3 avatars, with a "+X" indicator if there are more
            val maxAvatars = 3
            val avatarsToShow = filteredReceipts.take(maxAvatars)
            val remainingCount = filteredReceipts.size - maxAvatars
            
            avatarsToShow.forEach { receipt ->
                val userProfile = userProfileCache[receipt.userId]
                val avatarUrl = userProfile?.avatarUrl
                val displayName = userProfile?.displayName
                
                Log.d("Andromuks", "InlineReadReceiptAvatars: Rendering avatar for user: ${receipt.userId}")
                
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { 
                            Log.d("Andromuks", "Read receipt avatar clicked for user: ${receipt.userId}")
                            showReceiptDialog = true
                        }
                ) {
                    AvatarImage(
                        mxcUrl = avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = (displayName ?: receipt.userId).take(1),
                        size = 16.dp,
                        userId = receipt.userId,
                        displayName = displayName
                    )
                }
            }
            
            // Show "+" indicator if there are more than maxAvatars
            if (remainingCount > 0) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            Log.d("Andromuks", "Read receipt + indicator clicked")
                            showReceiptDialog = true
                        }
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Composable for showing read receipt details in a floating dialog
 */
@Composable
fun ReadReceiptDetailsDialog(
    receipts: List<ReadReceipt>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Read by",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    receipts.forEach { receipt ->
                        ReadReceiptItem(
                            receipt = receipt,
                            userProfile = userProfileCache[receipt.userId],
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onUserClick = onUserClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadReceiptItem(
    receipt: ReadReceipt,
    userProfile: MemberProfile?,
    homeserverUrl: String,
    authToken: String,
    onUserClick: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(receipt.userId) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with automatic fallback
        AvatarImage(
            mxcUrl = userProfile?.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = (userProfile?.displayName ?: receipt.userId).take(1),
            size = 40.dp,
            userId = receipt.userId,
            displayName = userProfile?.displayName
        )
        
        // User info and timestamp
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        ) {
            Text(
                text = userProfile?.displayName ?: receipt.userId.substringAfterLast(":"),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Text(
                text = formatReadReceiptTime(receipt.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

private fun formatReadReceiptTime(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(date)
}