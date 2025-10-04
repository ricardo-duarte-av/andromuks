package net.vrkknn.andromuks.utils

import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.MoreVert
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReadReceipt
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
        Log.d("Andromuks", "ReceiptFunctions: getReadReceipts called for eventId: $eventId")
        Log.d("Andromuks", "ReceiptFunctions: readReceiptsMap contains ${readReceiptsMap.size} events")
        Log.d("ReceiptFunctions", "ReceiptFunctions: Available event IDs: ${readReceiptsMap.keys.joinToString(", ")}")
        
        val receipts = readReceiptsMap[eventId]?.toList() ?: emptyList()
        Log.d("Andromuks", "ReceiptFunctions: getReadReceipts($eventId) -> ${receipts.size} receipts")
        
        return receipts
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
            onDismiss = { showReceiptDialog = false }
        )
    }
    
    if (filteredReceipts.isNotEmpty()) {
        Log.d("Andromuks", "InlineReadReceiptAvatars: Rendering ${filteredReceipts.size} read receipt avatars")
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            //modifier = Modifier
            //    .background(androidx.compose.material3.MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
            //    .padding(2.dp)
        ) {
            // Show up to 3 avatars, with a "+X" indicator if there are more
            val maxAvatars = 3
            val avatarsToShow = filteredReceipts.take(maxAvatars)
            val remainingCount = filteredReceipts.size - maxAvatars
            
            avatarsToShow.forEach { receipt ->
                val userProfile = userProfileCache[receipt.userId]
                val avatarUrl = userProfile?.avatarUrl
                
                Log.d("Andromuks", "InlineReadReceiptAvatars: Rendering avatar for user: ${receipt.userId}")
                Log.d("Andromuks", "InlineReadReceiptAvatars: userProfile: $userProfile")
                Log.d("Andromuks", "InlineReadReceiptAvatars: avatarUrl: $avatarUrl")
                Log.d("Andromuks", "InlineReadReceiptAvatars: userProfileCache keys: ${userProfileCache.keys.joinToString(", ")}")
                
                IconButton(
                    onClick = { 
                        Log.d("Andromuks", "Read receipt avatar clicked for user: ${receipt.userId}")
                        showReceiptDialog = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    if (avatarUrl != null && avatarUrl.isNotEmpty()) {
                        // Convert MXC URL to HTTP URL if needed
                        val httpUrl = when {
                            avatarUrl.startsWith("mxc://") -> {
                                net.vrkknn.andromuks.utils.AvatarUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
                            }
                            avatarUrl.startsWith("_gomuks/") -> {
                                "$homeserverUrl/$avatarUrl"
                            }
                            else -> {
                                avatarUrl
                            }
                        }
                        
                        Log.d("Andromuks", "InlineReadReceiptAvatars: Loading avatar from URL: $avatarUrl -> $httpUrl")
                        
                        if (httpUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(httpUrl)
                                    .crossfade(true)
                                    .build(),
                            contentDescription = "Avatar for ${userProfile.displayName ?: receipt.userId}",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                            onSuccess = { 
                                Log.d("Andromuks", "InlineReadReceiptAvatars: Avatar loaded successfully for user: ${receipt.userId}")
                            },
                            onError = { state ->
                                Log.e("Andromuks", "InlineReadReceiptAvatars: Avatar loading failed for user: ${receipt.userId}, error: ${state.result.throwable}")
                            }
                        )
                        } else {
                            Log.w("Andromuks", "InlineReadReceiptAvatars: Failed to convert avatar URL to HTTP: $avatarUrl")
                            // Fallback to default avatar icon
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Default avatar",
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape),
                                //tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Log.d("Andromuks", "InlineReadReceiptAvatars: Using default avatar for user: ${receipt.userId}")
                        // Fallback to a default avatar icon
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Default avatar",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                            //tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Show "+X" indicator if there are more than maxAvatars
            if (remainingCount > 0) {
                IconButton(
                    onClick = {
                        Log.d("Andromuks", "Read receipt +X indicator clicked")
                        showReceiptDialog = true
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "+$remainingCount more",
                        modifier = Modifier.size(16.dp),
                        //tint = MaterialTheme.colorScheme.onSurfaceVariant
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
    onDismiss: () -> Unit
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
                            homeserverUrl = homeserverUrl
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
    homeserverUrl: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        val avatarUrl = userProfile?.avatarUrl
        val httpUrl = when {
            avatarUrl?.startsWith("mxc://") == true -> {
                AvatarUtils.mxcToHttpUrl(avatarUrl, homeserverUrl)
            }
            avatarUrl?.startsWith("_gomuks/") == true -> {
                "$homeserverUrl/$avatarUrl"
            }
            else -> {
                avatarUrl
            }
        }
        
        if (httpUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(httpUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Avatar for ${userProfile?.displayName ?: receipt.userId}",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Default avatar",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
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