package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Utility functions for handling read receipts in Matrix rooms.
 */
object ReceiptFunctions {
    
    /**
     * Processes read receipts from a pagination response (authoritative - accepts as-is).
     * 
     * Pagination responses are authoritative - whatever the server sends is the source of truth.
     * If the server says event $123 has 10 receipts, we accept and display all 10.
     * 
     * @param receiptsJson JSON object containing read receipts from pagination
     * @param readReceiptsMap Mutable map to store the processed receipts (eventId -> list of receipts)
     * @param updateCounter Counter to trigger UI updates (will be incremented only if changes occurred)
     * @return true if receipts were modified, false otherwise
     */
    fun processReadReceiptsFromPaginate(
        receiptsJson: JSONObject,
        readReceiptsMap: MutableMap<String, MutableList<ReadReceipt>>,
        updateCounter: () -> Unit
    ): Boolean {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: processReadReceiptsFromPaginate called with ${receiptsJson.length()} event receipts")
        
        // PAGINATE IS AUTHORITATIVE: Accept all receipts as-is from the server, no deduplication needed
        // The server's paginate response is the source of truth - if it says event X has 6 receipts, we show 6 receipts
        val authoritativeReceipts = mutableMapOf<String, MutableList<ReadReceipt>>()
        val keys = receiptsJson.keys()
        
        while (keys.hasNext()) {
            val eventId = keys.next()
            val receiptsArray = receiptsJson.optJSONArray(eventId)
            if (receiptsArray != null) {
                val receiptsForEvent = mutableListOf<ReadReceipt>()
                
                for (i in 0 until receiptsArray.length()) {
                    val receiptJson = receiptsArray.optJSONObject(i)
                    if (receiptJson != null) {
                        val receipt = ReadReceipt(
                            userId = receiptJson.optString("user_id", ""),
                            eventId = receiptJson.optString("event_id", ""),
                            timestamp = receiptJson.optLong("timestamp", 0),
                            receiptType = receiptJson.optString("receipt_type", ""),
                            roomId = "" // Paginate receipts don't have room context, but that's OK - they're authoritative
                        )
                        
                        // Validate receipt has required fields and eventId matches
                        if (receipt.userId.isNotBlank() && receipt.eventId.isNotBlank() && receipt.eventId == eventId) {
                            receiptsForEvent.add(receipt)
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Adding receipt from paginate - eventId=$eventId, userId=${receipt.userId}, timestamp=${receipt.timestamp}")
                        } else {
                            if (BuildConfig.DEBUG) Log.w("Andromuks", "ReceiptFunctions: Skipping invalid receipt - eventId=$eventId, userId=${receipt.userId}, receiptEventId=${receipt.eventId}")
                        }
                    }
                }
                
                // Store all receipts for this event (empty list means remove)
                authoritativeReceipts[eventId] = receiptsForEvent
            } else {
                // Event has no receipts array - mark as empty (remove existing receipts)
                authoritativeReceipts[eventId] = mutableListOf()
            }
        }
        
        // Apply all changes atomically
        var hasChanges = false
        authoritativeReceipts.forEach { (eventId, receipts) ->
            val existingReceipts = readReceiptsMap[eventId]
            val receiptsChanged = existingReceipts == null || 
                existingReceipts.size != receipts.size ||
                existingReceipts.any { existing ->
                    !receipts.any { auth ->
                        auth.userId == existing.userId && 
                        auth.timestamp == existing.timestamp &&
                        auth.eventId == existing.eventId
                    }
                } ||
                receipts.any { auth ->
                    !existingReceipts.any { existing ->
                        existing.userId == auth.userId && 
                        existing.timestamp == auth.timestamp &&
                        existing.eventId == auth.eventId
                    }
                }
            
            if (receiptsChanged) {
                if (receipts.isEmpty()) {
                    // Server says no receipts for this event - remove it
                    readReceiptsMap.remove(eventId)
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Removed all receipts for eventId=$eventId (server says none)")
                } else {
                    // Replace with authoritative list
                    readReceiptsMap[eventId] = receipts.toMutableList()
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Replaced receipts for eventId=$eventId with ${receipts.size} receipts from paginate")
                }
                hasChanges = true
            }
        }
        
        val totalReceipts = authoritativeReceipts.values.sumOf { it.size }
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: processReadReceiptsFromPaginate completed - processed $totalReceipts total receipts, hasChanges: $hasChanges")
        
        if (hasChanges) {
            updateCounter()
        }
        
        return hasChanges
    }
    
    /**
     * Processes read receipts from a sync_complete response (incremental updates - moves receipts).
     * 
     * Sync_complete receipts are incremental updates. When we receive a receipt for a user,
     * we must:
     * 1. Find if that user already has a receipt on any other event in the timeline
     * 2. If yes, remove the old receipt and add the new one
     * 3. If no, just add the new receipt
     * 
     * @param receiptsJson JSON object containing read receipts from sync_complete
     * @param readReceiptsMap Mutable map to store the processed receipts (eventId -> list of receipts)
     * @param updateCounter Counter to trigger UI updates (will be incremented only if changes occurred)
     * @param onMovementDetected Optional callback to track receipt movements for animation (userId, previousEventId, newEventId)
     * @return true if receipts were modified, false otherwise
     */
    fun processReadReceiptsFromSyncComplete(
        receiptsJson: JSONObject,
        readReceiptsMap: MutableMap<String, MutableList<ReadReceipt>>,
        updateCounter: () -> Unit,
        onMovementDetected: ((String, String?, String) -> Unit)? = null,
        roomId: String = "" // Room ID to prevent cross-room receipt corruption
    ): Boolean {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: processReadReceiptsFromSyncComplete called with ${receiptsJson.length()} event receipts for roomId=$roomId")
        
        // Process each receipt individually: for user @aaa:bbb.com on event !abcdefghi in room !roomId
        // Check if @aaa:bbb.com has a receipt on any other event IN THE SAME ROOM. If yes, remove it. Then add to !abcdefghi.
        // CRITICAL: Only remove receipts from the same room to prevent cross-room corruption
        var hasChanges = false
        val keys = receiptsJson.keys()
        
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
                            receiptType = receiptJson.optString("receipt_type", ""),
                            roomId = roomId // Store room ID to prevent cross-room corruption
                        )
                        
                        if (receipt.userId.isNotBlank() && receipt.eventId.isNotBlank() && receipt.eventId == eventId) {
                            // Find if this user has a receipt on any other event IN THE SAME ROOM
                            // CRITICAL: Collect items to remove first to avoid ConcurrentModificationException
                            // CRITICAL: Only remove receipts from the same room to prevent cross-room corruption
                            val receiptsToRemove = mutableListOf<Pair<String, ReadReceipt>>() // (eventId, receipt)
                            val eventsToRemoveIfEmpty = mutableSetOf<String>()
                            var previousEventIdForAnimation: String? = null
                            var previousTimestampForAnimation: Long = 0L
                            
                            // Search all events for existing receipt for this user IN THE SAME ROOM
                            // Iterate over a copy of keys to avoid ConcurrentModificationException
                            for (existingEventId in readReceiptsMap.keys.toList()) {
                                val receiptsList = readReceiptsMap[existingEventId] ?: continue
                                // CRITICAL FIX: Only find receipts for this user in the SAME ROOM
                                // This prevents cross-room receipt corruption (e.g., receipt in room1 removing receipt in room2)
                                // Logic:
                                // - If roomId is provided, only match receipts with the same roomId OR no roomId (old data for backward compatibility)
                                // - This ensures we never remove receipts from different rooms
                                val existingReceipt = receiptsList.find { existing ->
                                    existing.userId == receipt.userId && if (roomId.isNotBlank()) {
                                        // Room ID provided - only match receipts from same room or old receipts without roomId
                                        existing.roomId.isBlank() || existing.roomId == roomId
                                    } else {
                                        // No room ID provided - match any (backward compatibility, but less safe)
                                        true
                                    }
                                }
                                if (existingReceipt != null) {
                                    if (existingEventId != receipt.eventId) {
                                        // Found receipt on different event - mark for removal
                                        receiptsToRemove.add(Pair(existingEventId, existingReceipt))
                                        hasChanges = true
                                        
                                        // Track for animation
                                        if (previousEventIdForAnimation == null || 
                                            existingReceipt.timestamp > previousTimestampForAnimation) {
                                            previousEventIdForAnimation = existingEventId
                                            previousTimestampForAnimation = existingReceipt.timestamp
                                        }
                                        
                                        // Mark event for removal if it will be empty after removing this receipt
                                        if (receiptsList.size == 1) {
                                            eventsToRemoveIfEmpty.add(existingEventId)
                                        }
                                        
                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Marked old receipt for removal - user ${receipt.userId} from event $existingEventId (moving to ${receipt.eventId}) in roomId=$roomId")
                                    } else {
                                        // Same event - check if timestamp changed
                                        if (existingReceipt.timestamp != receipt.timestamp) {
                                            receiptsToRemove.add(Pair(existingEventId, existingReceipt))
                                            hasChanges = true
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Marked receipt for timestamp update - user ${receipt.userId} on event ${receipt.eventId} in roomId=$roomId")
                                        }
                                    }
                                }
                            }
                            
                            // Now remove all marked receipts (after iteration is complete)
                            receiptsToRemove.forEach { (eventIdToRemove, receiptToRemove) ->
                                val receiptsList = readReceiptsMap[eventIdToRemove]
                                if (receiptsList != null) {
                                    receiptsList.remove(receiptToRemove)
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Removed old receipt for user ${receipt.userId} from event $eventIdToRemove in roomId=$roomId")
                                }
                            }
                            
                            // Remove event entries that are now empty
                            eventsToRemoveIfEmpty.forEach { eventIdToRemove ->
                                val receiptsList = readReceiptsMap[eventIdToRemove]
                                if (receiptsList != null && receiptsList.isEmpty()) {
                                    readReceiptsMap.remove(eventIdToRemove)
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Removed empty event entry $eventIdToRemove in roomId=$roomId")
                                }
                            }
                            
                            // Notify about movement for animation (only if moving to a different event)
                            if (onMovementDetected != null && previousEventIdForAnimation != null && previousEventIdForAnimation != receipt.eventId) {
                                onMovementDetected(receipt.userId, previousEventIdForAnimation, receipt.eventId)
                            }
                            
                            // Add the new receipt to the target event
                            val receiptsList = readReceiptsMap.getOrPut(receipt.eventId) { mutableListOf() }
                            val existingReceiptOnEvent = receiptsList.find { it.userId == receipt.userId }
                            
                            if (existingReceiptOnEvent == null) {
                                // No existing receipt on this event, add it
                                receiptsList.add(receipt)
                                hasChanges = true
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Added receipt for user ${receipt.userId} to event ${receipt.eventId} (timestamp=${receipt.timestamp}) in roomId=$roomId")
                            } else if (existingReceiptOnEvent.timestamp != receipt.timestamp) {
                                // Receipt exists but timestamp changed, replace it
                                receiptsList.remove(existingReceiptOnEvent)
                                receiptsList.add(receipt)
                                hasChanges = true
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Updated receipt for user ${receipt.userId} on event ${receipt.eventId} (old timestamp=${existingReceiptOnEvent.timestamp}, new timestamp=${receipt.timestamp}) in roomId=$roomId")
                            }
                            // If receipt already exists with same timestamp, no change needed
                        }
                    }
                }
            }
        }
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: processReadReceiptsFromSyncComplete completed - hasChanges: $hasChanges")
        
        // Only trigger UI update if receipts actually changed
        if (hasChanges) {
            updateCounter()
        }
        
        return hasChanges
    }
    
    /**
     * Legacy function name - redirects to processReadReceiptsFromSyncComplete for backward compatibility.
     * @deprecated Use processReadReceiptsFromSyncComplete or processReadReceiptsFromPaginate explicitly
     */
    @Deprecated("Use processReadReceiptsFromSyncComplete or processReadReceiptsFromPaginate explicitly")
    fun processReadReceipts(
        receiptsJson: JSONObject,
        readReceiptsMap: MutableMap<String, MutableList<ReadReceipt>>,
        updateCounter: () -> Unit,
        onMovementDetected: ((String, String?, String) -> Unit)? = null
    ): Boolean {
        return processReadReceiptsFromSyncComplete(receiptsJson, readReceiptsMap, updateCounter, onMovementDetected)
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
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: removeUserReceipts called for userId: $userId")
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
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Removed $removed receipts for user $userId from event: $eventId")
                    
                    // Remove the event entry if no receipts remain
                    if (receiptsList.isEmpty()) {
                        readReceiptsMap.remove(eventId)
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Removed empty event entry: $eventId")
                    }
                }
            }
        }
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: removeUserReceipts completed - removed $removedCount total receipts for user: $userId")
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
    roomId: String? = null,
    onUserClick: (String) -> Unit = {},
    isMine: Boolean = false
) {
    val context = LocalContext.current
    var showReceiptDialog by remember { mutableStateOf(false) }
    
    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Called with ${receipts.size} receipts for sender: $messageSender")
    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Receipt users: ${receipts.map { it.userId }}")
    
    // Debug: Check if any receipt user matches the message sender
    val senderMatches = receipts.any { it.userId == messageSender }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Any receipt matches sender: $senderMatches")
    
    // Filter out the message sender from read receipts
    val filteredReceipts = receipts.filter { it.userId != messageSender }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: After filtering: ${filteredReceipts.size} receipts, users: ${filteredReceipts.map { it.userId }}")
    
    // OPPORTUNISTIC PROFILE LOADING: Request profiles for read receipt users
    LaunchedEffect(filteredReceipts.map { it.userId }, roomId, appViewModel?.memberUpdateCounter) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: LaunchedEffect triggered - receipts: ${filteredReceipts.size}, roomId: $roomId, memberUpdateCounter: ${appViewModel?.memberUpdateCounter}")
        
        if (appViewModel != null && roomId != null && filteredReceipts.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Requesting profiles for ${filteredReceipts.size} read receipt users")
            filteredReceipts.forEach { receipt ->
                if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Processing receipt for user: ${receipt.userId}")
                
                // Check if profile is already cached to avoid unnecessary requests
                val existingProfile = appViewModel.getUserProfile(receipt.userId, roomId)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Profile check for ${receipt.userId} - cached: ${existingProfile != null}, displayName: ${existingProfile?.displayName}")
                
                if (existingProfile == null) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Profile not cached for ${receipt.userId}, requesting...")
                    appViewModel.requestUserProfileOnDemand(receipt.userId, roomId)
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Profile request sent for ${receipt.userId}")
                } else {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Profile already cached for ${receipt.userId} - displayName: ${existingProfile.displayName}")
                }
            }
        } else {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Skipping profile requests - appViewModel: ${appViewModel != null}, roomId: $roomId, receipts: ${filteredReceipts.size}")
        }
    }
    
    // Show read receipt details dialog
    if (showReceiptDialog) {
        ReadReceiptDetailsDialog(
            receipts = filteredReceipts,
            userProfileCache = userProfileCache,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            onDismiss = { showReceiptDialog = false },
            onUserClick = onUserClick,
            appViewModel = appViewModel,
            roomId = roomId
        )
    }
    
    if (filteredReceipts.isNotEmpty()) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Rendering ${filteredReceipts.size} read receipt avatars")
        // Show up to 3 avatars, with a "+X" indicator if there are more
        val maxAvatars = 3
        val avatarsToShow = filteredReceipts.take(maxAvatars)
        val remainingCount = filteredReceipts.size - maxAvatars
        
        // Build list of items to display (avatars + optional + indicator)
        // IMPORTANT: + indicator must be last in the list so it's drawn on top
        val itemsToDisplay = mutableListOf<Pair<ReadReceipt?, Int>>() // (receipt, index) or (null, index) for + indicator
        if (isMine) {
            // For "my messages": show + on the left (index 0), then avatars
            // But draw + last so it appears on top
            avatarsToShow.forEachIndexed { index, receipt ->
                itemsToDisplay.add(Pair(receipt, index + 1)) // Avatars start at index 1
            }
            if (remainingCount > 0) {
                itemsToDisplay.add(Pair(null, 0)) // + at index 0, but added last to draw on top
            }
        } else {
            // For "others' messages": show avatars first, then + on the right (last index)
            // Draw + last so it appears on top
            avatarsToShow.reversed().forEachIndexed { index, receipt ->
                itemsToDisplay.add(Pair(receipt, index))
            }
            if (remainingCount > 0) {
                itemsToDisplay.add(Pair(null, avatarsToShow.size)) // + at last index, added last to draw on top
            }
        }
        
        // Circle diameter and overlap calculation
        val circleSize = 22.dp // Reduced from 24.dp to make fallback text fit better
        val avatarSize = 14.dp // Reduced from 16.dp to make fallback text fit better
        val plusCircleSize = 16.dp // Smaller circle for + indicator
        val overlap = circleSize * 0.4f // More compact overlap (8.8.dp)
        
        Box(
            modifier = Modifier.padding(top = 4.dp) // Align with the top of message bubble
        ) {
            itemsToDisplay.forEachIndexed { displayIndex, (receipt, originalIndex) ->
                val offsetX = (originalIndex * overlap.value).dp
                
                if (receipt != null) {
                    // Get profile from AppViewModel directly to ensure we get the latest data
                    val userProfile = appViewModel?.getUserProfile(receipt.userId, roomId) ?: userProfileCache[receipt.userId]
                    val avatarUrl = userProfile?.avatarUrl
                    val displayName = userProfile?.displayName
                    
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Rendering avatar for user: ${receipt.userId}")
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Profile source - AppViewModel: ${appViewModel?.getUserProfile(receipt.userId, roomId) != null}, Cache: ${userProfileCache[receipt.userId] != null}")
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Final profile - displayName: $displayName, avatarUrl: $avatarUrl")
                    
                    Box(
                        modifier = Modifier
                            .size(circleSize)
                            .offset(x = offsetX, y = 0.dp)
                            .clickable { 
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "Read receipt avatar clicked for user: ${receipt.userId}")
                                showReceiptDialog = true
                            }
                    ) {
                        AvatarImage(
                            mxcUrl = avatarUrl,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            fallbackText = (displayName ?: receipt.userId).take(1),
                            size = avatarSize,
                            userId = receipt.userId,
                            displayName = displayName
                        )
                    }
                } else {
                    // "+" indicator
                    Box(
                        modifier = Modifier
                            .size(plusCircleSize)
                            .offset(x = offsetX, y = 0.dp)
                            .clickable {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "Read receipt + indicator clicked")
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
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated version of read receipt avatars that smoothly animates when receipts move between messages.
 * 
 * @param receipts List of read receipts to display
 * @param userProfileCache Cache of user profiles for avatar display
 * @param homeserverUrl URL of the Matrix homeserver
 * @param authToken Authentication token for API requests
 * @param appViewModel ViewModel for additional functionality
 * @param messageSender The sender of the message (to exclude from read receipts)
 * @param eventId The event ID for this message (to detect animations)
 */
@Composable
fun AnimatedInlineReadReceiptAvatars(
    receipts: List<ReadReceipt>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    messageSender: String,
    eventId: String,
    roomId: String? = null,
    onUserClick: (String) -> Unit = {},
    isMine: Boolean = false
) {
    val context = LocalContext.current
    var showReceiptDialog by remember { mutableStateOf(false) }
    
    // Get receipt movements for animation
    val receiptMovements = appViewModel?.getReceiptMovements() ?: emptyMap()
    val animationTrigger = appViewModel?.receiptAnimationTrigger ?: 0L
    
    // Filter out the message sender from read receipts (cached to avoid recomputation)
    val filteredReceipts = remember(receipts, messageSender) {
        receipts.filter { it.userId != messageSender }
    }
    
    // Cache user IDs outside LaunchedEffect to prevent recomputation
    val receiptUserIds = remember(filteredReceipts) {
        filteredReceipts.map { it.userId }.toSet()
    }
    
    // OPPORTUNISTIC PROFILE LOADING: Request profiles for read receipt users
    // Only trigger when user IDs actually change (not on every recomposition)
    LaunchedEffect(receiptUserIds, roomId) {
        if (appViewModel != null && roomId != null && receiptUserIds.isNotEmpty()) {
            receiptUserIds.forEach { userId ->
                // Check if profile is already cached to avoid unnecessary requests
                val existingProfile = appViewModel.getUserProfile(userId, roomId)
                
                if (existingProfile == null) {
                    appViewModel.requestUserProfileOnDemand(userId, roomId)
                }
            }
        }
    }
    
    // Check which receipts are newly appearing (should animate in) or moving away (should animate out)
    val receiptMovementsToNewEvent = receiptMovements.filter { (_, movement) ->
        movement.second == eventId // Moving TO this event
    }
    
    val receiptMovementsFromThisEvent = receiptMovements.filter { (_, movement) ->
        movement.first == eventId // Moving FROM this event
    }
    
    // Show read receipt details dialog
    if (showReceiptDialog) {
        ReadReceiptDetailsDialog(
            receipts = filteredReceipts,
            userProfileCache = userProfileCache,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            onDismiss = { showReceiptDialog = false },
            onUserClick = onUserClick,
            appViewModel = appViewModel,
            roomId = roomId
        )
    }
    
    if (filteredReceipts.isNotEmpty()) {
        // Show up to 3 avatars, with a "+X" indicator if there are more
        val maxAvatars = 3
        val avatarsToShow = filteredReceipts.take(maxAvatars)
        val remainingCount = filteredReceipts.size - maxAvatars
        
        // Build list of items to display (avatars + optional + indicator)
        // IMPORTANT: + indicator must be last in the list so it's drawn on top
        val itemsToDisplay = mutableListOf<Pair<ReadReceipt?, Int>>() // (receipt, index) or (null, index) for + indicator
        if (isMine) {
            // For "my messages": show + on the left (index 0), then avatars
            // But draw + last so it appears on top
            avatarsToShow.forEachIndexed { index, receipt ->
                itemsToDisplay.add(Pair(receipt, index + 1)) // Avatars start at index 1
            }
            if (remainingCount > 0) {
                itemsToDisplay.add(Pair(null, 0)) // + at index 0, but added last to draw on top
            }
        } else {
            // For "others' messages": show avatars first, then + on the right (last index)
            // Draw + last so it appears on top
            avatarsToShow.reversed().forEachIndexed { index, receipt ->
                itemsToDisplay.add(Pair(receipt, index))
            }
            if (remainingCount > 0) {
                itemsToDisplay.add(Pair(null, avatarsToShow.size)) // + at last index, added last to draw on top
            }
        }
        
        // Circle diameter and overlap calculation
        val circleSize = 22.dp // Reduced from 24.dp to make fallback text fit better
        val avatarSize = 14.dp // Reduced from 16.dp to make fallback text fit better
        val plusCircleSize = 16.dp // Smaller circle for + indicator
        val overlap = circleSize * 0.4f // More compact overlap (8.8.dp)
        
        Box(
            modifier = Modifier.padding(top = 4.dp) // Align with the top of message bubble
        ) {
            itemsToDisplay.forEachIndexed { displayIndex, (receipt, originalIndex) ->
                val offsetX = (originalIndex * overlap.value).dp
                
                if (receipt != null) {
                    // Get profile from AppViewModel directly to ensure we get the latest data
                    val userProfile = appViewModel?.getUserProfile(receipt.userId, roomId) ?: userProfileCache[receipt.userId]
                    val avatarUrl = userProfile?.avatarUrl
                    val displayName = userProfile?.displayName
                    
                    // Check if this receipt is animating in from another message
                    val isAnimatingIn = receiptMovementsToNewEvent.containsKey(receipt.userId)
                    
                    // Use simple animated visibility with enhanced enter animation for moved receipts
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(durationMillis = if (isAnimatingIn) 500 else 200)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 200))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(circleSize)
                                .offset(x = offsetX, y = 0.dp)
                                .clickable { 
                                    showReceiptDialog = true
                                }
                        ) {
                            AvatarImage(
                                mxcUrl = avatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                fallbackText = (displayName ?: receipt.userId).take(1),
                                size = avatarSize,
                                userId = receipt.userId,
                                displayName = displayName
                            )
                        }
                    }
                } else {
                    // "+" indicator
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(200))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(plusCircleSize)
                                .offset(x = offsetX, y = 0.dp)
                                .clickable {
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
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                lineHeight = 12.sp
                            )
                        }
                    }
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
    onUserClick: (String) -> Unit = {},
    appViewModel: AppViewModel? = null,
    roomId: String? = null
) {
    // OPPORTUNISTIC PROFILE LOADING: Request profiles for read receipt users when dialog opens
    LaunchedEffect(receipts.map { it.userId }, roomId, appViewModel?.memberUpdateCounter) {
        if (appViewModel != null && roomId != null && receipts.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: LaunchedEffect triggered - receipts: ${receipts.size}, roomId: $roomId, memberUpdateCounter: ${appViewModel.memberUpdateCounter}")
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: Requesting profiles for ${receipts.size} read receipt users")
            receipts.forEach { receipt ->
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: Processing receipt for user: ${receipt.userId}")
                val existingProfile = appViewModel.getUserProfile(receipt.userId, roomId)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: Profile check for ${receipt.userId} - cached: ${existingProfile != null}, displayName: ${existingProfile?.displayName}")
                if (existingProfile == null) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: Profile not cached for ${receipt.userId}, requesting...")
                    appViewModel.requestUserProfileOnDemand(receipt.userId, roomId)
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: Profile request sent for ${receipt.userId}")
                } else {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: Profile already cached for ${receipt.userId} - displayName: ${existingProfile.displayName}")
                }
            }
        } else {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ReadReceiptDetailsDialog: Skipping profile requests - appViewModel: ${appViewModel != null}, roomId: $roomId, receipts: ${receipts.size}")
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var isVisible by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val enterDuration = 220
    val exitDuration = 160

    LaunchedEffect(Unit) {
        isDismissing = false
        isVisible = true
    }

    fun dismissWithAnimation(afterDismiss: () -> Unit = {}) {
        if (isDismissing) return
        isDismissing = true
        coroutineScope.launch {
            isVisible = false
            delay(exitDuration.toLong())
            onDismiss()
            afterDismiss()
        }
    }

    Dialog(
        onDismissRequest = { dismissWithAnimation() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = enterDuration, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.85f,
                    animationSpec = tween(durationMillis = enterDuration, easing = FastOutSlowInEasing),
                    transformOrigin = TransformOrigin.Center
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = exitDuration, easing = FastOutSlowInEasing)) +
                scaleOut(
                    targetScale = 0.85f,
                    animationSpec = tween(durationMillis = exitDuration, easing = FastOutSlowInEasing),
                    transformOrigin = TransformOrigin.Center
                )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp  // Use tonalElevation for dark mode visibility
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
                    
                    // Use LazyColumn for scrollable list of receipts
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp) // Limit max height to ensure dialog doesn't take full screen
                    ) {
                        items(
                            items = receipts,
                            key = { receipt -> receipt.userId } // Use userId as stable key
                        ) { receipt ->
                            // Prioritize AppViewModel profile over cache
                            val userProfile = appViewModel?.getUserProfile(receipt.userId, roomId) ?: userProfileCache[receipt.userId]
                            ReadReceiptItem(
                                receipt = receipt,
                                userProfile = userProfile,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                onUserClick = { userId ->
                                    dismissWithAnimation {
                                        onUserClick(userId)
                                    }
                                }
                            )
                        }
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
                text = userProfile?.displayName ?: receipt.userId,
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