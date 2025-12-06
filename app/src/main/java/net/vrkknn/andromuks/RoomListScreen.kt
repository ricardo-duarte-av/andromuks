package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsOff
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ExpressiveStatusRow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.vrkknn.andromuks.utils.RoomJoinerScreen
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.database.AndromuksDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun RoomListScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val sharedPreferences = remember(context) { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val authToken = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    
    // CRITICAL FIX #1: Check if profile is loaded before showing UI
    val me = appViewModel.currentUserProfile
    val profileLoaded = me != null || appViewModel.currentUserId.isBlank()
    
    // COLD START FIX: Track profile loading state with timeout
    var profileLoadStartTime by remember { mutableStateOf<Long?>(null) }
    var profileLoadTimeout by remember { mutableStateOf(false) }
    
    // Attempt to load profile if missing (on cold start, onAppBecameVisible might not be called yet)
    LaunchedEffect(appViewModel.currentUserId, me) {
        if (me == null && appViewModel.currentUserId.isNotBlank()) {
            if (profileLoadStartTime == null) {
                profileLoadStartTime = System.currentTimeMillis()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "RoomListScreen: Profile missing, attempting to load - userId: ${appViewModel.currentUserId}")
                }
                // Try to load profile (this will check cache first, then request from server)
                appViewModel.ensureCurrentUserProfileLoaded()
            }
        } else if (me != null) {
            // Profile loaded, reset timeout
            profileLoadStartTime = null
            profileLoadTimeout = false
        }
    }
    
    // COLD START FIX: Add timeout fallback for profile loading (5 seconds)
    // If profile doesn't load within 5 seconds, show UI anyway to prevent infinite loading
    LaunchedEffect(profileLoadStartTime) {
        if (profileLoadStartTime != null && me == null) {
            kotlinx.coroutines.delay(5000) // 5 second timeout
            if (appViewModel.currentUserProfile == null) {
                android.util.Log.w("Andromuks", "RoomListScreen: Profile loading timeout (5s) - allowing UI to show anyway")
                profileLoadTimeout = true
            }
        }
    }
    
    // Use timeout override: if profile timeout expired, treat as loaded to prevent infinite loading
    val effectiveProfileLoaded = profileLoaded || profileLoadTimeout
    
    // CRITICAL FIX #2: Wait for pending items to be processed before showing RoomListScreen
    // This ensures database has the latest messages when we query for room summaries
    // Use a local state that tracks both the flag and a timeout override
    var shouldBlockForPending by remember { mutableStateOf(appViewModel.isProcessingPendingItems) }
    var processingStartTime by remember { mutableStateOf<Long?>(null) }
    
    // Observe changes to isProcessingPendingItems from AppViewModel
    LaunchedEffect(appViewModel.isProcessingPendingItems) {
        val currentlyProcessing = appViewModel.isProcessingPendingItems
        shouldBlockForPending = currentlyProcessing
        if (currentlyProcessing) {
            processingStartTime = System.currentTimeMillis()
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Pending processing started")
            }
        } else {
            processingStartTime = null
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Pending processing completed")
            }
        }
    }
    
    // CRITICAL: Add timeout fallback - don't block forever if something goes wrong
    // If processing takes more than 3 seconds, allow UI to show anyway (safety mechanism)
    LaunchedEffect(shouldBlockForPending, processingStartTime) {
        if (shouldBlockForPending && processingStartTime != null) {
            kotlinx.coroutines.delay(3000) // 3 second timeout
            val elapsed = System.currentTimeMillis() - (processingStartTime ?: 0L)
            if (appViewModel.isProcessingPendingItems && elapsed >= 3000) {
                android.util.Log.w("Andromuks", "RoomListScreen: Pending items processing timeout (3s) - allowing UI to show anyway")
                shouldBlockForPending = false // Override to allow UI to show
            }
        }
    }
    
    // CRITICAL FIX #3: Wait for spaces to be loaded before showing room list
    // This ensures roomMap is populated and getCurrentRoomSection() returns complete data
    // Prevents empty tabs (Favs, Direct) when AppViewModel was destroyed and recreated
    var spacesLoadStartTime by remember { mutableStateOf<Long?>(null) }
    var spacesLoadTimeout by remember { mutableStateOf(false) }
    
    // Track if we've started monitoring spaces loading
    var spacesMonitoringStarted by remember { mutableStateOf(false) }
    
    // Start monitoring spaces loading on first render
    LaunchedEffect(Unit) {
        if (!spacesMonitoringStarted) {
            spacesMonitoringStarted = true
            if (!appViewModel.spacesLoaded) {
                spacesLoadStartTime = System.currentTimeMillis()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "RoomListScreen: Spaces not loaded on first render, starting timeout...")
                }
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "RoomListScreen: Spaces already loaded on first render")
                }
            }
        }
    }
    
    // Observe changes to spacesLoaded from AppViewModel
    LaunchedEffect(appViewModel.spacesLoaded) {
        if (!appViewModel.spacesLoaded) {
            if (spacesLoadStartTime == null) {
                spacesLoadStartTime = System.currentTimeMillis()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "RoomListScreen: Spaces not loaded yet, waiting...")
                }
            }
        } else {
            // Spaces loaded, reset timeout
            spacesLoadStartTime = null
            spacesLoadTimeout = false
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Spaces loaded - room list ready")
            }
        }
    }
    
    // Add timeout fallback for spaces loading (10 seconds)
    // If spaces don't load within 10 seconds, show UI anyway to prevent infinite loading
    // This handles cases where WebSocket never connects or init_complete never arrives
    // CRITICAL: Use a separate LaunchedEffect that only depends on spacesLoadStartTime to prevent cancellation
    LaunchedEffect(spacesLoadStartTime) {
        if (spacesLoadStartTime != null && !spacesLoadTimeout) {
            val startTime = spacesLoadStartTime!!
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Starting 10s timeout for spaces loading (startTime: $startTime)")
            }
            kotlinx.coroutines.delay(10000) // 10 second timeout
            // Check again after delay - only trigger timeout if still waiting and startTime hasn't changed
            if (spacesLoadStartTime == startTime && !appViewModel.spacesLoaded && !spacesLoadTimeout) {
                android.util.Log.w("Andromuks", "RoomListScreen: Spaces loading timeout (10s) - allowing UI to show anyway")
                spacesLoadTimeout = true
            } else if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Timeout completed but spaces already loaded or timeout cancelled (spacesLoaded: ${appViewModel.spacesLoaded}, timeout: $spacesLoadTimeout, startTime match: ${spacesLoadStartTime == startTime})")
            }
        }
    }
    
    // Use timeout override: if spaces timeout expired, treat as loaded to prevent infinite loading
    val effectiveSpacesLoaded = appViewModel.spacesLoaded || spacesLoadTimeout
    
    // CRITICAL FIX #5: Wait for initial sync to complete before showing UI
    // This ensures all initial sync_complete messages (received before init_complete) are processed
    // This way the room list will have: avatars, proper order, unread badges, last message time, sender, and message
    var initialSyncComplete by remember { mutableStateOf(appViewModel.initialSyncComplete) }
    var initialSyncWaitStartTime by remember { mutableStateOf<Long?>(null) }
    var initialSyncWaitTimeout by remember { mutableStateOf(false) }
    
    // Observe initial sync completion status
    LaunchedEffect(appViewModel.initialSyncComplete) {
        initialSyncComplete = appViewModel.initialSyncComplete
        if (initialSyncComplete) {
            initialSyncWaitStartTime = null
            initialSyncWaitTimeout = false
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Initial sync complete - UI ready with all room data")
            }
        }
    }
    
    // Start timeout tracking if initial sync is not complete
    LaunchedEffect(Unit) {
        if (!initialSyncComplete && initialSyncWaitStartTime == null) {
            initialSyncWaitStartTime = System.currentTimeMillis()
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Waiting for initial sync to complete...")
            }
        }
    }
    
    // Add timeout fallback for initial sync completion (15 seconds)
    // If initial sync doesn't complete within 15 seconds, show UI anyway to prevent infinite loading
    LaunchedEffect(initialSyncWaitStartTime) {
        if (initialSyncWaitStartTime != null && !initialSyncComplete && !initialSyncWaitTimeout) {
            val startTime = initialSyncWaitStartTime!!
            kotlinx.coroutines.delay(15000) // 15 second timeout
            if (initialSyncWaitStartTime == startTime && !appViewModel.initialSyncComplete && !initialSyncWaitTimeout) {
                android.util.Log.w("Andromuks", "RoomListScreen: Initial sync completion timeout (15s) - allowing UI to show anyway")
                initialSyncWaitTimeout = true
                initialSyncComplete = true // Allow UI to show
            }
        }
    }
    
    // Show loading screen if profile is missing, pending items are being processed, spaces are not loaded, or initial sync is not complete (unless timeout expired)
    if (!effectiveProfileLoaded || shouldBlockForPending || !effectiveSpacesLoaded || (!initialSyncComplete && !initialSyncWaitTimeout)) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ExpressiveLoadingIndicator(modifier = Modifier.size(96.dp))
                Spacer(modifier = Modifier.height(24.dp))
                if (!effectiveProfileLoaded) {
                    Text(
                        text = "Loading profile...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (shouldBlockForPending) {
                    if (effectiveProfileLoaded) {
                        Text(
                            text = "Catching up on messages...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Text(
                            text = "Loading profile...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!effectiveSpacesLoaded && effectiveProfileLoaded && !shouldBlockForPending) {
                    Text(
                        text = "Loading rooms...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (shouldBlockForPending) 8.dp else 0.dp)
                    )
                }
                if (!initialSyncComplete && !initialSyncWaitTimeout && effectiveProfileLoaded && !shouldBlockForPending && effectiveSpacesLoaded) {
                    Text(
                        text = "Loading rooms...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (shouldBlockForPending || !effectiveSpacesLoaded) 8.dp else 0.dp)
                    )
                }
            }
        }
        return
    }
    
    val inlineSyncInProgress = appViewModel.isProcessingPendingItems || !appViewModel.initialSyncComplete || !appViewModel.spacesLoaded
    val inlineSyncMessage = when {
        appViewModel.isProcessingPendingItems -> "Processing new events..."
        !appViewModel.initialSyncComplete -> "Finalizing sync..."
        !appViewModel.spacesLoaded -> "Loading spaces..."
        else -> "Refreshing rooms..."
    }
    val showNotificationActionIndicator = appViewModel.notificationActionInProgress
    
    // PERFORMANCE: Only recompose when room list actually changes
    // Use derivedStateOf to prevent recomposition when counter increments but rooms are identical
    val roomListUpdateCounter = appViewModel.roomListUpdateCounter
    var stableSection by remember { mutableStateOf(appViewModel.getCurrentRoomSection()) }
    var previousSectionType by remember { mutableStateOf(stableSection.type) }
    var sectionAnimationDirection by remember { mutableStateOf(0) }
    
    // BATTERY OPTIMIZATION: Query database for last messages directly in RoomListScreen
    // This ensures fresh data and removes dependency on sync parsing
    var roomsWithSummaries by remember { mutableStateOf<Map<String, Pair<String?, String?>>>(emptyMap()) } // roomId -> (messagePreview, messageSender)
    
    // Enrich stableSection with database summaries
            // ANTI-FLICKER FIX: Preserve RoomItem instances when only order changes, not data
    val enrichedSection = remember(stableSection, roomsWithSummaries) {
        val enrichedRooms = stableSection.rooms.map { room ->
            val (messagePreview, messageSender) = roomsWithSummaries[room.id] ?: (null to null)
            
            // Only create new copy if preview/sender actually changed
            val currentPreview = messagePreview ?: room.messagePreview
            val currentSender = messageSender ?: room.messageSender
            
            if (currentPreview != room.messagePreview || currentSender != room.messageSender) {
                room.copy(
                    messagePreview = currentPreview,
                    messageSender = currentSender
                )
            } else {
                // Return same instance if unchanged - prevents unnecessary recomposition and avatar flicker
                room
            }
        }
        
        // ANTI-FLICKER FIX: Only create new RoomSection if any room items actually changed
        // Check if all room references are the same (no items were copied)
        val roomsChanged = enrichedRooms.zip(stableSection.rooms).any { (enriched, original) ->
            enriched !== original // Reference inequality means item was copied
        }
        
        if (!roomsChanged && enrichedRooms.size == stableSection.rooms.size) {
            // No data changed - return same instance even if order might have changed
            // Instance preservation prevents avatar flicker during reordering
            stableSection
        } else {
            stableSection.copy(rooms = enrichedRooms)
        }
    }

    // PERFORMANCE: Only update section if data actually changed
    // ANTI-FLICKER FIX: Preserve room instances when only order changes
    LaunchedEffect(roomListUpdateCounter) {
        val newSection = appViewModel.getCurrentRoomSection()
        
        // FIX: Compare only meaningful fields, ignore micro timestamp changes
        // Compare room order (by ID sequence), unread counts, and names
        // Don't compare exact sortingTimestamp (millisecond differences don't matter visually)
        val oldRoomSignature = stableSection.rooms.map { "${it.id}:${it.unreadCount}:${it.highlightCount}:${it.name}" }
        val newRoomSignature = newSection.rooms.map { "${it.id}:${it.unreadCount}:${it.highlightCount}:${it.name}" }
        val roomsChanged = oldRoomSignature != newRoomSignature
        
        // Also check if room order changed (by ID sequence)
        val oldRoomIds = stableSection.rooms.map { it.id }
        val newRoomIds = newSection.rooms.map { it.id }
        val orderChanged = oldRoomIds != newRoomIds
        
        val spacesChanged = stableSection.spaces.map { "${it.id}:${it.name}" } != 
                           newSection.spaces.map { "${it.id}:${it.name}" }
        
        if (newSection.type != stableSection.type) {
            // Section type changed - update with animation
            val oldIndex = RoomSectionType.values().indexOf(previousSectionType)
            val newIndex = RoomSectionType.values().indexOf(newSection.type)
            sectionAnimationDirection = when {
                newIndex > oldIndex -> 1
                newIndex < oldIndex -> -1
                else -> 0
            }
            previousSectionType = stableSection.type
            stableSection = newSection
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Section type changed from ${previousSectionType} to ${newSection.type}")
        } else if (roomsChanged || spacesChanged) {
            // Same section type but data changed - update without animation
            // ANTI-FLICKER: If only order changed (not data), preserve room instances from old section
            if (orderChanged && !roomsChanged) {
                // Only order changed - preserve room instances to prevent avatar flicker
                val oldRoomsById = stableSection.rooms.associateBy { it.id }
                val reorderedRooms = newSection.rooms.map { oldRoomsById[it.id] ?: it }
                stableSection = newSection.copy(rooms = reorderedRooms)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Order changed only - preserved ${reorderedRooms.size} room instances")
            } else {
                // Data changed - update normally
                stableSection = newSection
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Section data changed - rooms:$roomsChanged order:$orderChanged spaces:$spacesChanged")
            }
            sectionAnimationDirection = 0
        }
        // If nothing changed, skip update - prevents unnecessary recomposition and avatar flashing
    }
    
    // CRITICAL FIX: Always query database for last message per room
    // The DB is the source of truth and may have newer messages than what's in JSON parsing
    // Use stable key to prevent unnecessary re-queries on every recomposition
    val roomIdsKey = remember(stableSection.rooms) { 
        stableSection.rooms.map { it.id }.sorted().joinToString(",") 
    }
    
    LaunchedEffect(roomIdsKey, appViewModel.roomSummaryUpdateCounter) {
        val roomIds = stableSection.rooms.map { it.id }
        if (roomIds.isEmpty()) {
            roomsWithSummaries = emptyMap()
            return@LaunchedEffect
        }
        
        // FIX: Use withContext instead of coroutineScope.launch to avoid "rememberCoroutineScope left the composition" errors
        // LaunchedEffect already provides a coroutine scope, so we can switch dispatchers directly
        withContext(Dispatchers.IO) {
            try {
                val database = AndromuksDatabase.getInstance(context)
                val eventDao = database.eventDao()
                val summaryMap = mutableMapOf<String, Pair<String?, String?>>()
                
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "RoomListScreen: Querying DB for last messages for all ${roomIds.size} rooms")
                }
                
                // CRITICAL FIX: Always query DB for all rooms - DB is the source of truth
                // This ensures we always show the latest message, even if JSON parsing has stale data
                for (roomId in roomIds) {
                        try {
                            val lastEvent = eventDao.getLastMessageForRoom(roomId)
                            if (lastEvent != null) {
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("Andromuks", "RoomListScreen: Last message for $roomId - eventId: ${lastEvent.eventId}, timestamp: ${lastEvent.timestamp}, timelineRowId: ${lastEvent.timelineRowId}, type: ${lastEvent.type}, sender: ${lastEvent.sender}")
                                }
                                var messagePreview: String? = null
                                val messageSender = lastEvent.sender
                                
                                // Extract message preview from rawJson (same logic as SyncIngestor)
                                try {
                                    val eventJson = org.json.JSONObject(lastEvent.rawJson)
                                    
                                    // E2EE: For encrypted messages (type='m.room.encrypted'), body is in decrypted.body
                                    // For regular messages (type='m.room.message'), body is in content.body
                                    if (lastEvent.type == "m.room.encrypted") {
                                        // Encrypted message - check decryptedType first (like SpaceRoomParser does)
                                        val decryptedType = lastEvent.decryptedType ?: eventJson.optString("decrypted_type")
                                        if (decryptedType == "m.room.message" || decryptedType == "m.text") {
                                            val decrypted = eventJson.optJSONObject("decrypted")
                                            
                                            // Check if this is an edit (show new content instead of original)
                                            val relatesTo = decrypted?.optJSONObject("m.relates_to")
                                            val isEdit = relatesTo?.optString("rel_type") == "m.replace"
                                            
                                            if (isEdit) {
                                                // This is an edit - get body from m.new_content
                                                val newContent = decrypted.optJSONObject("m.new_content")
                                                messagePreview = newContent?.optString("body")?.takeIf { it.isNotBlank() }
                                            } else {
                                                // Regular encrypted message
                                                messagePreview = decrypted?.optString("body")?.takeIf { it.isNotBlank() }
                                            }
                                            
                                            if (BuildConfig.DEBUG && messagePreview.isNullOrBlank()) {
                                                val decryptedKeys = decrypted?.keys()?.asSequence()?.toList()?.joinToString() ?: "null"
                                                android.util.Log.w("Andromuks", "RoomListScreen: Encrypted message ${lastEvent.eventId} has no body - decrypted keys: $decryptedKeys, isEdit: $isEdit")
                                            }
                                        }
                                    } else if (lastEvent.type == "m.room.message") {
                                        // Regular message - check if it's an edit first
                                        val content = eventJson.optJSONObject("content")
                                        val relatesTo = content?.optJSONObject("m.relates_to")
                                        val isEdit = relatesTo?.optString("rel_type") == "m.replace"
                                        
                                        if (isEdit) {
                                            // This is an edit - get body from m.new_content
                                            val newContent = content.optJSONObject("m.new_content")
                                            messagePreview = newContent?.optString("body")?.takeIf { it.isNotBlank() }
                                        } else {
                                            // Regular message - get body from content
                                            messagePreview = content?.optString("body")?.takeIf { it.isNotBlank() }
                                        }
                                        
                                        // Handle non-text messages (images, files, etc.) that might have a fallback
                                        if (messagePreview.isNullOrBlank()) {
                                            val msgtype = content?.optString("msgtype", "")
                                            when {
                                                msgtype == "m.image" -> messagePreview = "📷 Image"
                                                msgtype == "m.video" -> messagePreview = "🎥 Video"
                                                msgtype == "m.audio" -> messagePreview = "🎵 Audio"
                                                msgtype == "m.file" -> messagePreview = "📎 File"
                                                msgtype == "m.location" -> messagePreview = "📍 Location"
                                            }
                                        }
                                        
                                        if (BuildConfig.DEBUG && messagePreview.isNullOrBlank()) {
                                            val contentKeys = content?.keys()?.asSequence()?.toList()?.joinToString() ?: "null"
                                            android.util.Log.w("Andromuks", "RoomListScreen: Message ${lastEvent.eventId} has no body - content keys: $contentKeys, msgtype: ${content?.optString("msgtype")}, isEdit: $isEdit")
                                        }
                                    }
                                    
                                    // DEBUG: Log full JSON structure if extraction fails
                                    if (BuildConfig.DEBUG && messagePreview.isNullOrBlank()) {
                                        val jsonKeys = eventJson.keys().asSequence().toList().joinToString()
                                        android.util.Log.d("Andromuks", "RoomListScreen: Failed to extract body for event ${lastEvent.eventId} (type=${lastEvent.type}, decryptedType=${lastEvent.decryptedType}) - rawJson keys: $jsonKeys")
                                        android.util.Log.d("Andromuks", "RoomListScreen: rawJson sample (first 500 chars): ${lastEvent.rawJson.take(500)}")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("Andromuks", "RoomListScreen: Failed to extract message preview from event ${lastEvent.eventId}: ${e.message}", e)
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("Andromuks", "RoomListScreen: Exception details - rawJson length: ${lastEvent.rawJson.length}, type: ${lastEvent.type}")
                                    }
                                }
                                
                                summaryMap[roomId] = messagePreview to messageSender
                                if (BuildConfig.DEBUG && appViewModel.roomSummaryUpdateCounter > 0) {
                                    android.util.Log.d("Andromuks", "RoomListScreen: Found last message for room $roomId - sender: $messageSender, preview: ${messagePreview?.take(50)}")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("Andromuks", "RoomListScreen: Error querying last message for room $roomId: ${e.message}")
                        }
                    }
                    
                    // FIX: Only update roomsWithSummaries if values actually changed
                    val hasChanges = summaryMap.any { entry ->
                        val roomId = entry.key
                        val (newPreview, newSender) = entry.value
                        val (oldPreview, oldSender) = roomsWithSummaries[roomId] ?: (null to null)
                        newPreview != oldPreview || newSender != oldSender
                    }
                    
                    if (hasChanges) {
                        // Merge new summaries with existing ones
                        // DB results overwrite any JSON-parsed previews (DB is source of truth)
                        // Rooms without messages in DB will keep their JSON-parsed previews as fallback
                        roomsWithSummaries = roomsWithSummaries.toMutableMap().apply {
                            putAll(summaryMap) // Overwrite with new DB results
                        }
                        
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "RoomListScreen: Updated summaries for ${summaryMap.size} rooms")
                        }
                    } else {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "RoomListScreen: Summaries unchanged, skipping update (queried ${summaryMap.size}/${roomIds.size} rooms)")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "RoomListScreen: Error querying last messages from events: ${e.message}", e)
                }
            }
    }
    
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: stableSection = ${stableSection.type}, roomListUpdateCounter = $roomListUpdateCounter, rooms.size = ${stableSection.rooms.size}")
    
    // Always show the interface, even if rooms/spaces are empty
    var searchQuery by remember { mutableStateOf("") }
    // Note: me is already declared above in the loading check
    
    // State for showing RoomJoinerScreen for invites
    var showRoomJoiner by remember { mutableStateOf(false) }
    var inviteToJoin by remember { mutableStateOf<RoomInvite?>(null) }
    
    // Handle back button - suspend app and move to background
    // When user presses back from room list, app suspends and moves to background
    // A 15-second timer starts to close the websocket for resource management
    BackHandler {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Back button pressed, suspending app")
        appViewModel.suspendApp() // Start 15-second timer to close websocket
        // Move app to background instead of closing completely
        (context as? ComponentActivity)?.moveTaskToBack(true)
    }
    
    // Clear current room ID when room list is shown - allows notifications to resume for previously open rooms
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Clearing current room ID - user is viewing room list, not a specific room")
        appViewModel.clearCurrentRoomId()
        
        // PERFORMANCE: Force immediate sort when returning to RoomListScreen
        // This ensures the list is properly sorted when the user navigates back
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Returning to room list - forcing immediate sort")
        appViewModel.forceRoomListSort()
    }
    
    // CRITICAL FIX #2: Wait for WebSocket connection and spacesLoaded before navigating from shortcuts/notifications
    // This ensures proper state before showing room timeline (prevents "only last message" issue)
    // Check on first render if spacesLoaded is already true
    LaunchedEffect(Unit) {
        val directRoomId = appViewModel.getDirectRoomNavigation()
        val pendingRoomId = appViewModel.getPendingRoomNavigation()
        
        if ((directRoomId != null || pendingRoomId != null) && appViewModel.spacesLoaded) {
            // Poll WebSocket connection status (check every 100ms, max 100 times = 10 seconds)
            var websocketConnected = appViewModel.isWebSocketConnected()
            var pollCount = 0
            while (!websocketConnected && pollCount < 100) {
                kotlinx.coroutines.delay(100) // Check every 100ms
                websocketConnected = appViewModel.isWebSocketConnected()
                pollCount++
            }
            
            if (websocketConnected || pollCount >= 100) {
                // WebSocket connected OR timeout - proceed with navigation
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: CRITICAL FIX #2 - WebSocket connected=$websocketConnected (pollCount=$pollCount) and spaces loaded, navigating")
                
                if (directRoomId != null) {
                    // Get notification timestamp if available
                    val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                    // CRITICAL FIX: Clear direct navigation state BEFORE navigating to prevent state confusion
                    appViewModel.clearDirectRoomNavigation()
                    // Navigate with cache (use notification timestamp if available)
                    if (notificationTimestamp != null) {
                        appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                    } else {
                        appViewModel.navigateToRoomWithCache(directRoomId)
                    }
                    // Navigate directly to the room
                    navController.navigate("room_timeline/$directRoomId")
                } else if (pendingRoomId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: CRITICAL FIX #2 - WebSocket connected and spaces loaded, navigating to pending room: $pendingRoomId")
                    // CRITICAL FIX: Clear pending navigation state BEFORE navigating to prevent state confusion
                    appViewModel.clearPendingRoomNavigation()
                    // OPTIMIZATION #4: Use cache-first navigation for pending navigation too
                    appViewModel.navigateToRoomWithCache(pendingRoomId)
                    // Navigate to the pending room
                    navController.navigate("room_timeline/$pendingRoomId")
                }
            }
        }
    }
    
    // Also check when spacesLoaded changes to true
    LaunchedEffect(appViewModel.spacesLoaded) {
        val directRoomId = appViewModel.getDirectRoomNavigation()
        val pendingRoomId = appViewModel.getPendingRoomNavigation()
        
        if ((directRoomId != null || pendingRoomId != null) && appViewModel.spacesLoaded) {
            // Poll WebSocket connection status (check every 100ms, max 100 times = 10 seconds)
            var websocketConnected = appViewModel.isWebSocketConnected()
            var pollCount = 0
            while (!websocketConnected && pollCount < 100) {
                kotlinx.coroutines.delay(100) // Check every 100ms
                websocketConnected = appViewModel.isWebSocketConnected()
                pollCount++
            }
            
            if (websocketConnected || pollCount >= 100) {
                // WebSocket connected OR timeout - proceed with navigation
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: CRITICAL FIX #2 - WebSocket connected=$websocketConnected (pollCount=$pollCount) and spaces loaded, navigating")
                
                if (directRoomId != null) {
                    // Get notification timestamp if available
                    val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                    // CRITICAL FIX: Clear direct navigation state BEFORE navigating to prevent state confusion
                    appViewModel.clearDirectRoomNavigation()
                    // Navigate with cache (use notification timestamp if available)
                    if (notificationTimestamp != null) {
                        appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                    } else {
                        appViewModel.navigateToRoomWithCache(directRoomId)
                    }
                    // Navigate directly to the room
                    navController.navigate("room_timeline/$directRoomId")
                } else if (pendingRoomId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: CRITICAL FIX #2 - WebSocket connected and spaces loaded, navigating to pending room: $pendingRoomId")
                    // CRITICAL FIX: Clear pending navigation state BEFORE navigating to prevent state confusion
                    appViewModel.clearPendingRoomNavigation()
                    // OPTIMIZATION #4: Use cache-first navigation for pending navigation too
                    appViewModel.navigateToRoomWithCache(pendingRoomId)
                    // Navigate to the pending room
                    navController.navigate("room_timeline/$pendingRoomId")
                }
            }
        }
    }
    
    // CRITICAL FIX #2: Timeout fallback - navigate after 10 seconds even if WebSocket never connects
    // This prevents infinite waiting when WebSocket can't connect (e.g., airplane mode)
    LaunchedEffect(Unit) {
        val directRoomId = appViewModel.getDirectRoomNavigation()
        val pendingRoomId = appViewModel.getPendingRoomNavigation()
        
        if (directRoomId != null || pendingRoomId != null) {
            kotlinx.coroutines.delay(10000) // 10 second timeout
            
            // Check if navigation still pending (WebSocket didn't connect in time)
            val stillDirectRoomId = appViewModel.getDirectRoomNavigation()
            val stillPendingRoomId = appViewModel.getPendingRoomNavigation()
            
            if (stillDirectRoomId != null) {
                android.util.Log.w("Andromuks", "RoomListScreen: CRITICAL FIX #2 - Navigation timeout (10s) for $stillDirectRoomId - WebSocket may not be connected, navigating anyway")
                val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                appViewModel.clearDirectRoomNavigation()
                if (notificationTimestamp != null) {
                    appViewModel.navigateToRoomWithCache(stillDirectRoomId, notificationTimestamp)
                } else {
                    appViewModel.navigateToRoomWithCache(stillDirectRoomId)
                }
                navController.navigate("room_timeline/$stillDirectRoomId")
            } else if (stillPendingRoomId != null) {
                android.util.Log.w("Andromuks", "RoomListScreen: CRITICAL FIX #2 - Navigation timeout (10s) for pending room $stillPendingRoomId - WebSocket may not be connected, navigating anyway")
                appViewModel.clearPendingRoomNavigation()
                appViewModel.navigateToRoomWithCache(stillPendingRoomId)
                navController.navigate("room_timeline/$stillPendingRoomId")
            }
        }
    }
    
    // Get timestamp update counter from AppViewModel
    val timestampUpdateTrigger = appViewModel.timestampUpdateCounter
    
    // Pull-to-refresh state
    var refreshing by remember { mutableStateOf(false) }
    
    val refreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = {
            refreshing = true
            // FORCE REFRESH: Reset state, clear last_received_id, and get complete payload
            appViewModel.performFullRefresh()
        }
    )
    
    // Handle refreshing state reset
    // Wait for spacesLoaded to become true after full refresh
    LaunchedEffect(appViewModel.spacesLoaded, refreshing) {
        if (refreshing && appViewModel.spacesLoaded) {
            delay(500) // Short delay to show the refresh animation
            refreshing = false
        }
    }
    
    // Listen for foreground refresh broadcast
    DisposableEffect(Unit) {
        val foregroundRefreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "net.vrkknn.andromuks.FOREGROUND_REFRESH") {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Received FOREGROUND_REFRESH broadcast, refreshing UI from cache")
                    // Lightweight refresh from cached sync data (no WebSocket restart needed)
                    appViewModel.refreshUIFromCache()
                }
            }
        }
        
        val filter = IntentFilter("net.vrkknn.andromuks.FOREGROUND_REFRESH")
        context.registerReceiver(foregroundRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Registered FOREGROUND_REFRESH broadcast receiver")
        
        onDispose {
            try {
                context.unregisterReceiver(foregroundRefreshReceiver)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Unregistered FOREGROUND_REFRESH broadcast receiver")
            } catch (e: Exception) {
                android.util.Log.w("Andromuks", "RoomListScreen: Error unregistering foreground refresh receiver", e)
            }
        }
    }
    
    // OPPORTUNISTIC PROFILE LOADING: Request missing profiles for message senders in visible rooms
    // This ensures proper display names and avatars are loaded for room list message previews
    // CRITICAL FIX: Only request profiles when initial sync is complete (WebSocket is connected and all initial data is loaded)
    // PERFORMANCE FIX: Track processed message senders to avoid duplicate requests and excessive logging
    val processedMessageSenders = remember { mutableSetOf<String>() }
    
    // Create a stable key based on actual message senders (not rooms list) to prevent excessive re-runs
    val messageSendersKey = remember(stableSection.rooms) {
        stableSection.rooms.take(50)
            .mapNotNull { room -> room.messageSender }
            .distinct()
            .filter { sender -> sender != appViewModel.currentUserId }
            .sorted()
            .joinToString(",")
    }
    
    LaunchedEffect(messageSendersKey, initialSyncComplete) {
        // Wait for initial sync completion before requesting profiles
        if (!initialSyncComplete) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: OPPORTUNISTIC PROFILE LOADING - Deferred until initial sync completes")
            }
            return@LaunchedEffect
        }
        
        // Parse message senders from stable key
        val messageSenders = if (messageSendersKey.isNotEmpty()) {
            messageSendersKey.split(",").filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        
        // Only process if we have new message senders that haven't been processed yet
        val newSenders = messageSenders.filter { it !in processedMessageSenders }
        
        if (newSenders.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "RoomListScreen: OPPORTUNISTIC PROFILE LOADING - Requesting profiles for ${newSenders.size} new message senders (${messageSenders.size} total, ${processedMessageSenders.size} already processed)"
            )
            
            // Request profiles for each new message sender
            newSenders.forEach { sender ->
                // Find the room where this sender appears (for room context)
                val roomWithSender = stableSection.rooms.find { it.messageSender == sender }
                if (roomWithSender != null) {
                    appViewModel.requestUserProfileOnDemand(sender, roomWithSender.id)
                }
            }
            
            // Mark as processed
            processedMessageSenders.addAll(newSenders)
        }
        
        // Clean up processed senders that are no longer in the current list
        processedMessageSenders.removeAll { it !in messageSenders }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pullRefresh(refreshState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .imePadding()
        ) {
            // Compact header with our avatar and name (no colored area)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                net.vrkknn.andromuks.ui.components.AvatarImage(
                    mxcUrl = me?.avatarUrl,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = authToken,
                    fallbackText = me?.displayName ?: appViewModel.currentUserId,
                    size = 40.dp,
                    userId = appViewModel.currentUserId,
                    displayName = me?.displayName
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = me?.displayName ?: appViewModel.currentUserId.ifBlank { "Profile" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!me?.displayName.isNullOrBlank() && appViewModel.currentUserId.isNotBlank()) {
                        Text(
                            text = appViewModel.currentUserId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                // Settings button
                IconButton(
                    onClick = { 
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        navController.navigate("settings") 
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            
            // Search box with rounded look and trailing search icon
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp) // pick your pill height
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { 
                        Text(
                            "Search rooms…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ) 
                    },
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        focusedIndicatorColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
            }
            
            AnimatedVisibility(inlineSyncInProgress) {
                ExpressiveStatusRow(
                    text = inlineSyncMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    indicatorColor = MaterialTheme.colorScheme.primary
                )
            }
            
            AnimatedVisibility(showNotificationActionIndicator) {
                ExpressiveStatusRow(
                    text = "Completing notification action...",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
            
            // Room list in elevated frame
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 24.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                ),
                tonalElevation = 2.dp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                AnimatedContent(
                    targetState = sectionAnimationDirection to enrichedSection,
                    transitionSpec = {
                        val direction = targetState.first
                        val enter = if (direction > 0) {
                            slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                            )
                        } else if (direction < 0) {
                            slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                            )
                        } else {
                            fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
                        }
                        val exit = if (direction > 0) {
                            slideOutHorizontally(
                                targetOffsetX = { -it },
                                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                            )
                        } else if (direction < 0) {
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                            )
                        } else {
                            fadeOut(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
                        }
                        enter togetherWith exit
                    },
                    label = "SectionTransition"
                ) { (_, targetSection) ->
                    when (targetSection.type) {
                        RoomSectionType.HOME -> {
                            RoomListContent(
                                rooms = targetSection.rooms,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController,
                                timestampUpdateTrigger = timestampUpdateTrigger,
                                hapticFeedback = hapticFeedback,
                                onInviteClick = { invite ->
                                    inviteToJoin = invite
                                    showRoomJoiner = true
                                }
                            )
                        }
                        RoomSectionType.SPACES -> {
                            if (appViewModel.currentSpaceId != null) {
                                RoomListContent(
                                    rooms = targetSection.rooms,
                                    searchQuery = searchQuery,
                                    appViewModel = appViewModel,
                                    authToken = authToken,
                                    navController = navController,
                                    timestampUpdateTrigger = timestampUpdateTrigger,
                                    hapticFeedback = hapticFeedback,
                                    onInviteClick = { invite ->
                                        inviteToJoin = invite
                                        showRoomJoiner = true
                                    }
                                )
                            } else {
                                SpacesListContent(
                                    spaces = targetSection.spaces,
                                    searchQuery = searchQuery,
                                    appViewModel = appViewModel,
                                    authToken = authToken,
                                    navController = navController
                                )
                            }
                        }
                        RoomSectionType.DIRECT_CHATS -> {
                            RoomListContent(
                                rooms = targetSection.rooms,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController,
                                timestampUpdateTrigger = timestampUpdateTrigger,
                                hapticFeedback = hapticFeedback,
                                onInviteClick = { invite ->
                                    inviteToJoin = invite
                                    showRoomJoiner = true
                                }
                            )
                        }
                        RoomSectionType.UNREAD -> {
                            RoomListContent(
                                rooms = targetSection.rooms,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController,
                                timestampUpdateTrigger = timestampUpdateTrigger,
                                hapticFeedback = hapticFeedback,
                                onInviteClick = { invite ->
                                    inviteToJoin = invite
                                    showRoomJoiner = true
                                }
                            )
                        }
                        RoomSectionType.FAVOURITES -> {
                            RoomListContent(
                                rooms = targetSection.rooms,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController,
                                timestampUpdateTrigger = timestampUpdateTrigger,
                                hapticFeedback = hapticFeedback,
                                onInviteClick = { invite ->
                                    inviteToJoin = invite
                                    showRoomJoiner = true
                                }
                            )
                        }
                    }
                }
            }
            
            // Tab bar at the bottom (outside the Surface)
            TabBar(
                currentSection = enrichedSection,
                onSectionSelected = { section ->
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    appViewModel.changeSelectedSection(section)
                },
                appViewModel = appViewModel
            )
        }
        
        // Pull-to-refresh indicator
        val pullProgress = refreshState.progress
        if (refreshing || pullProgress > 0f) {
            val progressForAlpha = if (refreshing) 1f else pullProgress.coerceIn(0f, 1f)
            ExpressiveLoadingIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(72.dp)
                    .graphicsLayer {
                        alpha = progressForAlpha
                        val scale = 0.7f + 0.3f * progressForAlpha
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
        
        // RoomJoinerScreen for invites
        if (showRoomJoiner && inviteToJoin != null) {
            val invite = inviteToJoin!!
            // Create RoomLink from invite
            val roomLink = RoomLink(
                roomIdOrAlias = invite.roomId,
                viaServers = emptyList(), // Invites don't need via servers
                displayText = invite.roomName ?: invite.inviterDisplayName ?: invite.roomId
            )
            
            RoomJoinerScreen(
                roomLink = roomLink,
                homeserverUrl = appViewModel.homeserverUrl,
                authToken = authToken,
                appViewModel = appViewModel,
                onDismiss = {
                    showRoomJoiner = false
                    inviteToJoin = null
                },
                onJoinSuccess = { joinedRoomId ->
                    showRoomJoiner = false
                    inviteToJoin = null
                    // Don't navigate - let the room appear at the top of the list
                    // The room will appear in sync_complete and be sorted to the top
                },
                inviteId = invite.roomId // Pass invite ID so RoomJoinerScreen uses acceptRoomInvite/refuseRoomInvite
            )
        }
    }
}

@Composable
fun SpaceListItem(
    space: SpaceItem, 
    isSelected: Boolean, 
    onClick: () -> Unit,
    homeserverUrl: String,
    authToken: String
) {
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "SpaceListItem: Called for space: ${space.name}")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "SpaceListItem: Using homeserver URL: $homeserverUrl")
    
    // Calculate unread counts and highlights outside the Row
    val totalRooms = space.rooms.size
    val unreadRooms = space.rooms.count { it.unreadCount != null && it.unreadCount > 0 }
    val highlightRooms = space.rooms.count { it.highlightCount != null && it.highlightCount > 0 }
    val totalUnreadMessages = space.rooms.sumOf { it.unreadCount ?: 0 }
    val totalHighlights = space.rooms.sumOf { it.highlightCount ?: 0 }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        AvatarImage(
            mxcUrl = space.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = space.name,
            size = 48.dp,
            userId = space.id,
            displayName = space.name
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = space.name,
                style = when {
                    isSelected -> MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary)
                    highlightRooms > 0 -> MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    unreadRooms > 0 -> MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    else -> MaterialTheme.typography.titleMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            if (totalRooms > 0) {
                Text(
                    text = "$totalRooms rooms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        
        // Unread/highlight badge - shows number of rooms with highlights or unreads
        if (highlightRooms > 0) {
            // Highlight badge - more prominent color (error/attention)
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.error,
                        CircleShape
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (highlightRooms > 99) "99+" else "$highlightRooms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError
                )
            }
        } else if (unreadRooms > 0) {
            // Regular unread badge - primary color
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (unreadRooms > 99) "99+" else "$unreadRooms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
fun RoomListItem(
    room: RoomItem,
    homeserverUrl: String,
    authToken: String,
    onRoomClick: (RoomItem) -> Unit,
    onRoomLongClick: ((RoomItem) -> Unit)? = null,
    timestampUpdateTrigger: Int = 0,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true
) {
    val context = LocalContext.current
    var showContextMenu by remember { mutableStateOf(false) }
    
    // PERFORMANCE: Remember computed timestamp to avoid recalculation unless it actually changes
    val timeAgo = remember(room.sortingTimestamp, timestampUpdateTrigger) {
        formatTimeAgo(room.sortingTimestamp)
    }
    
    // PERFORMANCE: Cache sender profile lookup to avoid expensive operations on every recomposition
    val senderDisplayName = remember(room.messageSender, room.id, appViewModel.memberUpdateCounter) {
        if (room.messageSender != null) {
            val senderProfile = appViewModel.getUserProfile(room.messageSender, room.id)
            senderProfile?.displayName ?: room.messageSender
        } else {
            null
        }
    }
    
    // Wrapping box for the entire item
    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = isEnabled,
                    onClick = { onRoomClick(room) },
                    onLongClick = { 
                        showContextMenu = true
                    }
                )
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
        // Room avatar
        net.vrkknn.andromuks.ui.components.AvatarImage(
            mxcUrl = room.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = room.name,
            size = 48.dp,
            userId = room.id,
            displayName = room.name,
            // AVATAR LOADING OPTIMIZATION: Enable lazy loading for room list performance
            isVisible = true // Room list items are visible when rendered in LazyColumn
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Room info with time and unread badge
        Box(
            modifier = Modifier.weight(1f)
        ) {
            Column {
                // Room name and unread badge row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = room.name,
                        style = if (room.highlightCount != null && room.highlightCount > 0) {
                            // Highlights have highest priority - bold styling
                            MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        } else if (room.unreadCount != null && room.unreadCount > 0) {
                            // Regular unreads - bold styling
                            MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        } else {
                            // No unreads - normal styling
                            MaterialTheme.typography.titleMedium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Right side: unread/highlight pill (top) and time (below)
                    // FIX: Always reserve space for unread badge to prevent height changes
                    Column(horizontalAlignment = Alignment.End) {
                        // Always show a Box to reserve space, but make it invisible when no unreads
                        Box(
                            modifier = Modifier
                                .background(
                                    if (room.highlightCount != null && room.highlightCount > 0) {
                                        MaterialTheme.colorScheme.error
                                    } else if (room.unreadCount != null && room.unreadCount > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        // Transparent to reserve space but remain invisible
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                    CircleShape
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            // Only show text if there are unreads
                            if (room.highlightCount != null && room.highlightCount > 0) {
                                Text(
                                    text = room.highlightCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onError
                                )
                            } else if (room.unreadCount != null && room.unreadCount > 0) {
                                Text(
                                    text = room.unreadCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                // Invisible placeholder to maintain consistent height
                                Text(
                                    text = "0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color.Transparent
                                )
                            }
                        }

                        // Use pre-computed timestamp
                        if (timeAgo.isNotEmpty()) {
                            Text(
                                text = timeAgo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                
                // Enhanced message preview with sender avatar and display name
                if (room.messagePreview != null && room.messageSender != null) {
                    // PERFORMANCE: Use cached senderDisplayName instead of expensive profile lookup on every recomposition
                    val displayNameToUse = senderDisplayName ?: room.messageSender
                    
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // PERFORMANCE: Removed mini sender avatar to reduce image loading by 50%
                        // The room avatar is more important, and loading 2 avatars per room is expensive
                        
                        // Sender name and message
                        Text(
                            text = "$displayNameToUse: ${room.messagePreview}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // FIX: Show silenced icon to the right of the message preview (not below time)
                        if (room.isLowPriority) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.NotificationsOff,
                                contentDescription = "Low Priority - Notifications Disabled",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else if (room.messagePreview != null) {
                    // Fallback for when messageSender is null
                    android.util.Log.w("Andromuks", "RoomListScreen: WARNING - No messageSender for room ${room.name}")
                    android.util.Log.w("Andromuks", "RoomListScreen: Room details - ID: ${room.id}, Preview: '${room.messagePreview}', Sender: '${room.messageSender}'")
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = room.messagePreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // FIX: Show silenced icon to the right of the message preview
                        if (room.isLowPriority) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.NotificationsOff,
                                contentDescription = "Low Priority - Notifications Disabled",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else if (room.messageSender != null) {
                    // Fallback: Sender available but no message preview (shouldn't happen normally since backend decrypts)
                    // This is a safety fallback in case of edge cases
                    val displayNameToUse = senderDisplayName ?: room.messageSender
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$displayNameToUse: (message preview unavailable)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // FIX: Show silenced icon to the right of the message preview
                        if (room.isLowPriority) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.NotificationsOff,
                                contentDescription = "Low Priority - Notifications Disabled",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        }
        
        // Context menu dialog with blur effect
        if (showContextMenu) {
            val coroutineScope = rememberCoroutineScope()
            var menuVisible by remember { mutableStateOf(false) }
            var menuDismissing by remember { mutableStateOf(false) }
            val enterDuration = 220
            val exitDuration = 160

            LaunchedEffect(Unit) {
                menuDismissing = false
                menuVisible = true
            }

            fun dismissMenu(afterDismiss: () -> Unit = {}) {
                if (menuDismissing) return
                menuDismissing = true
                coroutineScope.launch {
                    menuVisible = false
                    delay(exitDuration.toLong())
                    showContextMenu = false
                    afterDismiss()
                }
            }

            Dialog(
                onDismissRequest = { dismissMenu() },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false
                )
            ) {
                AnimatedVisibility(
                    visible = menuVisible,
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
                    // Darkened scrim overlay that simulates blur by dimming background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { dismissMenu() },
                        contentAlignment = Alignment.Center
                    ) {
                        // Menu card with strong elevation and Material Design styling
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.75f),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = 16.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                // Menu header with room name
                                Text(
                                    text = room.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                
                                // Room Info menu item with ripple effect
                                Surface(
                                    onClick = {
                                        dismissMenu {
                                            onRoomLongClick?.invoke(room)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Info,
                                            contentDescription = "Room Info",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = "Room Info",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatTimeAgo(timestamp: Long?): String {
    if (timestamp == null) return ""
    
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "${diff / 1000}s" // Less than 1 minute: show seconds
        diff < 3_600_000 -> "${diff / 60_000}m" // Less than 1 hour: show minutes
        diff < 86_400_000 -> "${diff / 3_600_000}h" // Less than 1 day: show hours
        diff < 604_800_000 -> "${diff / 86_400_000}d" // Less than 1 week: show days
        else -> "${diff / 604_800_000}w" // More than 1 week: show weeks
    }
}

@Composable
fun TabBar(
    currentSection: RoomSection,
    onSectionSelected: (RoomSectionType) -> Unit,
    appViewModel: AppViewModel
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp  // Use tonalElevation for dark mode visibility
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TabButton(
                icon = Icons.Filled.Home,
                label = "Home",
                isSelected = currentSection.type == RoomSectionType.HOME,
                onClick = {
                    onSectionSelected(RoomSectionType.HOME)
                }
            )
            
            TabButton(
                icon = Icons.Filled.Place,
                label = "Spaces",
                isSelected = currentSection.type == RoomSectionType.SPACES,
                onClick = {
                    onSectionSelected(RoomSectionType.SPACES)
                }
            )
            
            TabButton(
                icon = Icons.Filled.Person,
                label = "Direct",
                isSelected = currentSection.type == RoomSectionType.DIRECT_CHATS,
                onClick = {
                    onSectionSelected(RoomSectionType.DIRECT_CHATS)
                },
                badgeCount = appViewModel.getDirectChatsUnreadCount(),
                hasHighlights = appViewModel.hasDirectChatsHighlights()
            )
            
            TabButton(
                icon = Icons.Filled.Notifications,
                label = "Unread",
                isSelected = currentSection.type == RoomSectionType.UNREAD,
                onClick = {
                    onSectionSelected(RoomSectionType.UNREAD)
                },
                badgeCount = appViewModel.getUnreadCount()
            )
            
            TabButton(
                icon = Icons.Filled.Favorite,
                label = "Favs",
                isSelected = currentSection.type == RoomSectionType.FAVOURITES,
                onClick = {
                    onSectionSelected(RoomSectionType.FAVOURITES)
                },
                badgeCount = appViewModel.getFavouritesUnreadCount(),
                hasHighlights = appViewModel.hasFavouritesHighlights()
            )
            
        }
    }
}

@Composable
fun TabButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0,
    hasHighlights: Boolean = false
) {
    val content = @Composable {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) MaterialTheme.colorScheme.primary 
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary 
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    
    if (badgeCount > 0) {
        BadgedBox(
            badge = { 
                Badge(
                    containerColor = if (hasHighlights) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (hasHighlights) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                ) { 
                    Text("$badgeCount") 
                } 
            }
        ) {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
            ) {
                content()
            }
        }
    } else {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListContent(
    rooms: List<RoomItem>,
    searchQuery: String,
    appViewModel: AppViewModel,
    authToken: String,
    navController: NavController,
    timestampUpdateTrigger: Int,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onInviteClick: (RoomInvite) -> Unit
) {
    // Handle Android back key when inside a space
    androidx.activity.compose.BackHandler(enabled = appViewModel.currentSpaceId != null) {
        if (appViewModel.currentSpaceId != null) {
            appViewModel.exitSpace()
        }
    }
    
    // PERFORMANCE: Cache filtered rooms to avoid recalculation on every recomposition
    val filteredRooms = remember(rooms, searchQuery) {
        if (searchQuery.isBlank()) {
            rooms
        } else {
            rooms.filter { room ->
                room.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    // NAVIGATION PERFORMANCE: Add scroll state for prefetching
    val listState = rememberLazyListState()
    
    // NAVIGATION PERFORMANCE: Observe scroll state and trigger prefetching for visible rooms
    LaunchedEffect(listState, filteredRooms) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            listState.firstVisibleItemIndex to layoutInfo.visibleItemsInfo.map { it.index }
        }
            .debounce(150)
            .collectLatest { (firstVisibleIndex, visibleIndices) ->
                if (filteredRooms.isEmpty()) return@collectLatest
                
                val visibleRoomIds = visibleIndices
                    .filter { it in filteredRooms.indices }
                    .map { filteredRooms[it].id }
                
                val nearbyRangeStart = (firstVisibleIndex - 3).coerceAtLeast(0)
                val nearbyRangeEnd = (firstVisibleIndex + visibleIndices.size + 3).coerceAtMost(filteredRooms.size - 1)
                val nearbyRoomIds = (nearbyRangeStart..nearbyRangeEnd)
                    .filter { it in filteredRooms.indices }
                    .map { filteredRooms[it].id }
                    .distinct()
                
                if (nearbyRoomIds.isNotEmpty()) {
                    appViewModel.prefetchRoomData(nearbyRoomIds, firstVisibleIndex)
                    if (BuildConfig.DEBUG) android.util.Log.d(
                        "Andromuks",
                        "RoomListScreen: NAVIGATION OPTIMIZATION - Triggered prefetch for ${nearbyRoomIds.size} nearby rooms"
                    )
                }
            }
    }
    
    // CRITICAL: Observe roomListUpdateCounter to recompose when invites are added
    val roomListUpdateCounter = appViewModel.roomListUpdateCounter
    val pendingInvites = remember(roomListUpdateCounter) { appViewModel.getPendingInvites() }
    val coroutineScope = rememberCoroutineScope()
    var roomOpenInProgress by remember { mutableStateOf<String?>(null) }
    
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            bottom = 8.dp
        )
    ) {
        // Show pending invites at the top
        if (pendingInvites.isNotEmpty()) {
            item {
                Text(
                    text = "Room Invitations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            
            items(pendingInvites.size) { idx ->
                val invite = pendingInvites[idx]
                InviteListItem(
                    invite = invite,
                    onClick = {
                        // Show RoomJoinerScreen for invite
                        onInviteClick(invite)
                    },
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = authToken
                )
            }
            
            // Separator
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        
        // Use the cached filteredRooms from the remember block above
        items(
            count = filteredRooms.size,
            key = { index -> filteredRooms[index].id }, // PERFORMANCE: Stable key - room ID stays constant
            contentType = { "room_item" } // Fixed: Use constant content type
        ) { index ->
            val room = filteredRooms[index]
            // PERFORMANCE FIX: Removed AnimatedVisibility wrapper that caused animation overhead
            // The items() already handles insertions/deletions efficiently
            // CRITICAL FIX: Capture room.id OUTSIDE the lambda to prevent wrong room navigation
            val roomIdForNavigation = room.id
            
            Column {
                RoomListItem(
                    room = room,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = authToken,
                    onRoomClick = { 
                        if (roomOpenInProgress != null) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Room open already in progress, ignoring tap on ${room.id}")
                            return@RoomListItem
                        }
                        roomOpenInProgress = roomIdForNavigation
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        coroutineScope.launch {
                            try {
                                // CRITICAL FIX: Set currentRoomId immediately when navigating from room list
                                // This ensures state is consistent across all navigation paths
                                appViewModel.setCurrentRoomIdForTimeline(roomIdForNavigation)
                                
                                val prefetchSuccess = appViewModel.prefetchRoomSnapshot(roomIdForNavigation)
                                if (!prefetchSuccess) {
                                    android.util.Log.w(
                                        "Andromuks",
                                        "RoomListScreen: Prefetch snapshot failed or timed out for $roomIdForNavigation, falling back to existing cache"
                                    )
                                }
                                appViewModel.navigateToRoomWithCache(roomIdForNavigation)
                                navController.navigate("room_timeline/$roomIdForNavigation")
                            } finally {
                                roomOpenInProgress = null
                            }
                        }
                    },
                    onRoomLongClick = { selectedRoom ->
                        // Navigate to room info on long press
                        // selectedRoom parameter is still safe to use here
                        navController.navigate("room_info/${selectedRoom.id}")
                    },
                    timestampUpdateTrigger = timestampUpdateTrigger,
                    appViewModel = appViewModel,
                    modifier = Modifier.animateContentSize(),
                    isEnabled = roomOpenInProgress == null || roomOpenInProgress == roomIdForNavigation
                )
                
                // Material 3 divider between rooms (except after the last item)
                if (index < filteredRooms.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
fun SpacesListContent(
    spaces: List<SpaceItem>,
    searchQuery: String,
    appViewModel: AppViewModel,
    authToken: String,
    navController: NavController
) {
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "SpacesListContent: Displaying ${spaces.size} spaces")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            bottom = 8.dp
        )
    ) {
        val filteredSpaces = if (searchQuery.isBlank()) {
            spaces
        } else {
            spaces.filter { space ->
                space.name.contains(searchQuery, ignoreCase = true)
            }
        }
        
        items(filteredSpaces.size) { idx ->
            val space = filteredSpaces[idx]
            SpaceListItem(
                space = space,
                isSelected = false,
                onClick = { 
                    appViewModel.enterSpace(space.id)
                },
                homeserverUrl = appViewModel.homeserverUrl,
                authToken = authToken
            )
        }
    }
}

@Composable
fun InviteListItem(
    invite: RoomInvite,
    onClick: () -> Unit,
    homeserverUrl: String,
    authToken: String
) {
    // For DMs, use inviter display name instead of room name
    val displayName = if (invite.isDirectMessage && invite.roomName.isNullOrBlank()) {
        invite.inviterDisplayName ?: invite.inviterUserId
    } else {
        invite.roomName ?: (if (invite.isDirectMessage) invite.inviterDisplayName ?: invite.inviterUserId else "Unknown Room")
    }
    
    // Use inviter avatar for DMs if room avatar is not available
    val avatarUrl = if (invite.isDirectMessage && invite.roomAvatar.isNullOrBlank()) {
        null // Will use fallback with display name
    } else {
        invite.roomAvatar
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Room/inviter avatar
            AvatarImage(
                mxcUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                fallbackText = displayName.take(1),
                size = 48.dp,
                userId = if (invite.isDirectMessage && invite.roomAvatar.isNullOrBlank()) invite.inviterUserId else invite.roomId,
                displayName = displayName
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Room info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // INVITE badge
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "INVITE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Inviter info (only show if not a DM or if room name exists)
                if (!invite.isDirectMessage || invite.roomName != null) {
                    Text(
                        text = "Invited by ${invite.inviterDisplayName ?: invite.inviterUserId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Room topic if available
                invite.roomTopic?.let { topic ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
