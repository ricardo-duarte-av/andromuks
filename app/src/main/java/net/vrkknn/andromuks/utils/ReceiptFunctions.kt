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
import androidx.compose.foundation.layout.width
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
import androidx.compose.animation.slideInVertically
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
        userIndex: MutableMap<String, String>, // userId → eventId — maintained in-place (O(1) lookup)
        updateCounter: () -> Unit,
        onMovementDetected: ((String, String?, String) -> Unit)? = null,
        roomId: String = ""
    ): Boolean {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: processReadReceiptsFromSyncComplete called with ${receiptsJson.length()} event receipts for roomId=$roomId")

        var hasChanges = false
        val keys = receiptsJson.keys()

        while (keys.hasNext()) {
            val eventId = keys.next()
            val receiptsArray = receiptsJson.optJSONArray(eventId) ?: continue
            for (i in 0 until receiptsArray.length()) {
                val receiptJson = receiptsArray.optJSONObject(i) ?: continue
                val receipt = ReadReceipt(
                    userId = receiptJson.optString("user_id", ""),
                    eventId = receiptJson.optString("event_id", ""),
                    timestamp = receiptJson.optLong("timestamp", 0),
                    receiptType = receiptJson.optString("receipt_type", ""),
                    roomId = roomId
                )
                if (receipt.userId.isBlank() || receipt.eventId.isBlank() || receipt.eventId != eventId) continue

                // O(1) lookup via inverted index: find where this user's receipt currently lives
                val previousEventId = userIndex[receipt.userId]

                if (previousEventId != null && previousEventId != receipt.eventId) {
                    // User moved to a new event — remove from old event
                    val prevList = readReceiptsMap[previousEventId]
                    if (prevList != null) {
                        val old = prevList.find { it.userId == receipt.userId }
                        if (old != null) {
                            prevList.remove(old)
                            if (prevList.isEmpty()) readReceiptsMap.remove(previousEventId)
                            hasChanges = true
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Moved ${receipt.userId} from $previousEventId → ${receipt.eventId} in $roomId")
                        }
                    }
                    onMovementDetected?.invoke(receipt.userId, previousEventId, receipt.eventId)
                } else if (previousEventId == receipt.eventId) {
                    // Same event — only act if timestamp changed
                    val existingList = readReceiptsMap[receipt.eventId]
                    val existing = existingList?.find { it.userId == receipt.userId }
                    if (existing != null) {
                        if (existing.timestamp == receipt.timestamp) continue // no change
                        existingList.remove(existing)
                        hasChanges = true
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Updated timestamp for ${receipt.userId} on ${receipt.eventId} in $roomId")
                    }
                }

                // Place receipt on the target event
                val list = readReceiptsMap.getOrPut(receipt.eventId) { mutableListOf() }
                val existing = list.find { it.userId == receipt.userId }
                if (existing == null) {
                    list.add(receipt)
                    hasChanges = true
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: Added ${receipt.userId} to ${receipt.eventId} in $roomId")
                } else if (existing.timestamp != receipt.timestamp) {
                    list.remove(existing)
                    list.add(receipt)
                    hasChanges = true
                }

                // Keep inverted index current
                userIndex[receipt.userId] = receipt.eventId
            }
        }

        if (BuildConfig.DEBUG) Log.d("Andromuks", "ReceiptFunctions: processReadReceiptsFromSyncComplete completed - hasChanges: $hasChanges")
        if (hasChanges) updateCounter()
        return hasChanges
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
    // Read memberUpdateCounter in the composable body so Compose recomposes when profiles arrive
    @Suppress("UNUSED_VARIABLE")
    val memberUpdateCounter = appViewModel?.memberUpdateCounter

    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Called with ${receipts.size} receipts for sender: $messageSender")
    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Receipt users: ${receipts.map { it.userId }}")
    
    // Debug: Check if any receipt user matches the message sender
    val senderMatches = receipts.any { it.userId == messageSender }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Any receipt matches sender: $senderMatches")
    
    // Filter out the message sender from read receipts
    val filteredReceipts = receipts.filter { it.userId != messageSender }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: After filtering: ${filteredReceipts.size} receipts, users: ${filteredReceipts.map { it.userId }}")

    val receiptUserIds = remember(filteredReceipts) {
        filteredReceipts.map { it.userId }.toSet()
    }

    // OPPORTUNISTIC PROFILE LOADING: Request profiles for read receipt users
    // Key only on user IDs (not memberUpdateCounter) to avoid re-running on every sync_complete
    LaunchedEffect(receiptUserIds, roomId) {
        if (appViewModel != null && roomId != null && receiptUserIds.isNotEmpty()) {
            receiptUserIds.forEach { userId ->
                val existingProfile = appViewModel.getUserProfile(userId, roomId)
                if (existingProfile == null) {
                    appViewModel.requestUserProfileOnDemand(userId, roomId)
                }
            }
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
        val totalItems = avatarsToShow.size + (if (remainingCount > 0) 1 else 0)
        if (isMine) {
            // For "my messages": avatar0 is rightmost (closest to bubble), "+" is leftmost (index 0), drawn last = on top.
            // Higher index = further right. Avatar0 gets the highest index so it sits closest to the bubble.
            avatarsToShow.forEachIndexed { i, receipt ->
                itemsToDisplay.add(Pair(receipt, totalItems - 1 - i))
            }
            if (remainingCount > 0) {
                itemsToDisplay.add(Pair(null, 0)) // "+" at leftmost, drawn last = on top
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
        // Reserve exact width so the Row allocates correct space and items don't overflow into the bubble
        val totalWidth = overlap * maxOf(0f, (totalItems - 1).toFloat()) + circleSize

        Box(
            modifier = Modifier
                .width(totalWidth)
                .padding(top = 4.dp) // Align with the top of message bubble
        ) {
            itemsToDisplay.forEachIndexed { displayIndex, (receipt, originalIndex) ->
                val offsetX = (originalIndex * overlap.value).dp
                
                if (receipt != null) {
                    val userProfile = remember(receipt.userId, memberUpdateCounter, roomId) {
                        appViewModel?.getUserProfile(receipt.userId, roomId) ?: userProfileCache[receipt.userId]
                    }
                    val avatarUrl = userProfile?.avatarUrl
                    val displayName = userProfile?.displayName

                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Rendering avatar for user: ${receipt.userId}")
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineReadReceiptAvatars: Profile - displayName: $displayName, avatarUrl: $avatarUrl")

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
    // Read memberUpdateCounter in the composable body so Compose recomposes when profiles arrive
    @Suppress("UNUSED_VARIABLE")
    val memberUpdateCounter = appViewModel?.memberUpdateCounter

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
        val totalItems = avatarsToShow.size + (if (remainingCount > 0) 1 else 0)
        if (isMine) {
            // For "my messages": avatar0 is rightmost (closest to bubble), "+" is leftmost (index 0), drawn last = on top.
            // Higher index = further right. Avatar0 gets the highest index so it sits closest to the bubble.
            avatarsToShow.forEachIndexed { i, receipt ->
                itemsToDisplay.add(Pair(receipt, totalItems - 1 - i))
            }
            if (remainingCount > 0) {
                itemsToDisplay.add(Pair(null, 0)) // "+" at leftmost, drawn last = on top
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
        // Reserve exact width so the Row allocates correct space and items don't overflow into the bubble
        val totalWidth = overlap * maxOf(0f, (totalItems - 1).toFloat()) + circleSize

        Box(
            modifier = Modifier
                .width(totalWidth)
                .padding(top = 4.dp) // Align with the top of message bubble
        ) {
            itemsToDisplay.forEachIndexed { displayIndex, (receipt, originalIndex) ->
                val offsetX = (originalIndex * overlap.value).dp
                
                if (receipt != null) {
                    val userProfile = remember(receipt.userId, memberUpdateCounter, roomId) {
                        appViewModel?.getUserProfile(receipt.userId, roomId) ?: userProfileCache[receipt.userId]
                    }
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
                                .clickable { showReceiptDialog = true },
                            contentAlignment = if (isMine) Alignment.TopEnd else Alignment.TopStart
                        ) {
                            // Check if this receipt just moved to this message
                            val isNewlyMoved = remember(receipt.userId, eventId, animationTrigger) {
                                receiptMovementsToNewEvent.containsKey(receipt.userId)
                            }

                            // Control visibility to trigger animation
                            var isVisible by remember(receipt.userId, eventId) { 
                                mutableStateOf(!isNewlyMoved)  // Start hidden if newly moved
                            }

                            // Delay so message row entrance (fade/slide) can start first — avoids
                            // receipt scale/fade running while parent is still alpha 0 / off-screen.
                            LaunchedEffect(receipt.userId, eventId, isNewlyMoved) {
                                if (isNewlyMoved) {
                                    delay(180)
                                    isVisible = true
                                }
                            }
                            
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = if (isNewlyMoved) {
                                    fadeIn(tween(600, easing = FastOutSlowInEasing)) + 
                                    scaleIn(initialScale = 0.5f, animationSpec = tween(600, easing = FastOutSlowInEasing)) //+ 
                                    //slideInVertically(
                                    //    initialOffsetY = { -it / 2 },
                                    //    animationSpec = tween(600, easing = FastOutSlowInEasing)
                                    //)
                                } else {
                                    fadeIn(tween(200))
                                },
                                exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.5f, animationSpec = tween(200))
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
    @Suppress("UNUSED_VARIABLE")
    val memberUpdateCounter = appViewModel?.memberUpdateCounter
    val receiptUserIds = remember(receipts) { receipts.map { it.userId }.toSet() }

    // OPPORTUNISTIC PROFILE LOADING: Request profiles for read receipt users when dialog opens
    LaunchedEffect(receiptUserIds, roomId) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = true) { dismissWithAnimation() },
            contentAlignment = Alignment.Center
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
                        .padding(16.dp)
                        .clickable { }, // Consume clicks on content to prevent dismissal
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
                            val userProfile = remember(receipt.userId, memberUpdateCounter, roomId) {
                                appViewModel?.getUserProfile(receipt.userId, roomId) ?: userProfileCache[receipt.userId]
                            }
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
        val displayName = userProfile?.displayName?.takeIf { it.isNotBlank() }
            ?: receipt.userId.substringAfter("@").substringBefore(":")

        // Avatar with automatic fallback
        AvatarImage(
            mxcUrl = userProfile?.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = displayName.take(1),
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
                text = displayName,
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