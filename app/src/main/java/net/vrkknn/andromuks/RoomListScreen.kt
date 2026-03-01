package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.Surface
import net.vrkknn.andromuks.ui.components.rememberRoomListUiState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.key
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Link
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
import net.vrkknn.andromuks.utils.navigateToUserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.FlowPreview
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import coil.request.ImageRequest
import coil.request.CachePolicy

private const val ROOM_LIST_VERBOSE_LOGGING = false
private const val STARTUP_SHARED_AVATAR_KEY = "startup-current-user-avatar"
private fun usernameFromMatrixId(userId: String): String =
    userId.removePrefix("@").substringBefore(":")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RoomListScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val sharedPreferences = remember(context) { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val authToken = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val uiState by appViewModel.rememberRoomListUiState()
    // CRASH FIX: Observe "rush" / batch flush state once and reuse everywhere (TabBar + header indicator).
    val isProcessingBatch by appViewModel.isProcessingSyncBatch.collectAsState()
    val imageToken = uiState.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    var coldStartRefreshing by remember { mutableStateOf(false) }
    var initialLoadComplete by remember { mutableStateOf(false) }
    val listStates = remember { mutableMapOf<RoomSectionType, LazyListState>() }
    var missingTimestampsHydrated by remember { mutableStateOf(false) }
    
    // PERFORMANCE FIX: Smart timestamp updates - only update when displayed unit changes
    // New format: Today shows hh:mm, Yesterday shows "Yesterday", older shows "2d ago", "1w ago", "1y ago"
    // Update intervals:
    //   - Every 1 minute for today's messages (hh:mm format changes every minute)
    //   - Every 1 day for yesterday and older messages (transitions happen daily)
    // This dramatically reduces recompositions while keeping timestamps reasonably fresh.
    // Declared early so it can be used in LaunchedEffect blocks below
    var smartTimestampUpdateCounter by remember { mutableStateOf(0) }

    // Ensure cached data is applied after cold start/activity recreation.
    // CRITICAL FIX: Don't call refreshUIFromCache() here - it rebuilds allRooms from roomMap
    // which may have stale unread counts. We load from in-memory state via buildSectionSnapshot().
    // NOTE: checkAndProcessPendingItemsOnStartup is deprecated (no DB, all data from WebSocket)
    // CRITICAL FIX: Add timeout to prevent getting stuck on "Refreshing rooms..." if operations hang
    LaunchedEffect(Unit) {
        coldStartRefreshing = true
        try {
            // Use withTimeoutOrNull to prevent hanging - max 3 seconds for startup operations
            // These are lightweight operations (cache lookups, in-memory sorting)
            kotlinx.coroutines.withTimeoutOrNull(3000L) {
                // Initialize syncIngestor (lightweight - just ensures it exists)
                appViewModel.checkAndProcessPendingItemsOnStartup(context)
                // CRITICAL FIX: Ensure profile is loaded on cold start to prevent "Loading profile..." stall
                appViewModel.ensureCurrentUserProfileLoaded()
                // REMOVED: appViewModel.refreshUIFromCache() - this rebuilds allRooms from stale roomMap
                // Instead, we load fresh data from in-memory state via buildSectionSnapshot() below
                // Ensure a sort after cache restore/cold start so room order reflects latest data.
                appViewModel.forceRoomListSort()
            } ?: run {
                // Timeout occurred - log warning but continue
                if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "RoomListScreen: Startup operations timed out after 3 seconds, continuing anyway")
            }
        } catch (e: Exception) {
            // Catch any exceptions to prevent getting stuck
            if (BuildConfig.DEBUG) android.util.Log.e("Andromuks", "RoomListScreen: Error during startup operations", e)
        } finally {
            // Always clear the refreshing flag, even if operations failed or timed out
            // This prevents getting stuck on "Refreshing rooms..." message
            coldStartRefreshing = false
        }
    }
    
    // CRITICAL FIX #1: Profile loading is non-blocking - UI can show with fallback (userId)
    // The profile will load in the background and update when available
    val me = uiState.currentUserProfile
    // Profile is considered "loaded" if we have it OR if userId is blank (not logged in)
    // But we don't block UI if profile is missing - we show userId as fallback
    val profileLoaded = me != null || uiState.currentUserId.isBlank()
    
    // Simplified gating: rely on actual states, no timeout fallbacks.
    // CRITICAL FIX: Don't block UI for profile - it can load in background
    val effectiveProfileLoaded = true // Always true - profile loading is non-blocking
    val shouldBlockForPending = uiState.isProcessingPendingItems
    val effectiveSpacesLoaded = uiState.spacesLoaded
    val effectiveInitialSyncComplete = uiState.initialSyncComplete
    
    // Prepare room list data while the loading screen is visible so we avoid flicker
    val roomListUpdateCounter = uiState.roomListUpdateCounter
    // Seed from in-memory snapshot to avoid empty UI after clear_state/cold start
    // CRITICAL FIX: Initialize synchronously with current data to avoid delay when returning from RoomTimelineScreen
    // This ensures rooms appear immediately instead of waiting for LaunchedEffect
    var stableSection by remember { 
        mutableStateOf(appViewModel.getCurrentRoomSection())
    }
    
    // SAFETY NET: After initial sync completes, ensure stableSection is populated at least once.
    // In rare race conditions, it's possible for RoomListScreen to compose while stableSection
    // is still empty even though AppViewModel has room data. If that happens, force a one-time
    // resync from AppViewModel once initial sync processing is fully complete.
    LaunchedEffect(
        uiState.initialSyncComplete,
        appViewModel.initialSyncProcessingComplete,
        appViewModel.isStartupComplete
    ) {
        if (uiState.initialSyncComplete &&
            appViewModel.initialSyncProcessingComplete &&
            appViewModel.isStartupComplete &&
            stableSection.rooms.isEmpty() &&
            stableSection.spaces.isEmpty()
        ) {
            val latestSection = appViewModel.getCurrentRoomSection()
            if (latestSection.rooms.isNotEmpty() || latestSection.spaces.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "RoomListScreen: SAFETY NET - stableSection was empty after initial sync; " +
                            "reloading from AppViewModel (rooms=${latestSection.rooms.size}, spaces=${latestSection.spaces.size})"
                    )
                }
                stableSection = latestSection
            }
        }
    }
    
    // DEBUG: Log state changes to identify why "Refreshing rooms..." is stuck
    LaunchedEffect(coldStartRefreshing, shouldBlockForPending, effectiveSpacesLoaded, effectiveInitialSyncComplete, stableSection.rooms.size) {
        android.util.Log.d("Andromuks", "🔴 RoomListScreen: Loading state - coldStartRefreshing=$coldStartRefreshing, shouldBlockForPending=$shouldBlockForPending, effectiveSpacesLoaded=$effectiveSpacesLoaded, effectiveInitialSyncComplete=$effectiveInitialSyncComplete, hasRooms=${stableSection.rooms.isNotEmpty()}, roomCount=${stableSection.rooms.size}")
    }
    // DEBUG: Prove whether the UI is actually seeing the batch/rush state flip.
    LaunchedEffect(isProcessingBatch) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "RoomListScreen: isProcessingBatch=$isProcessingBatch")
        }
    }
    // CRASH FIX: Track animation state to prevent stableSection updates during tab animations.
    // animationGeneration increments on each type-change so the guard LaunchedEffect restarts,
    // correctly handling rapid tab switches (previous delay is cancelled, new one starts).
    var isAnimationInProgress by remember { mutableStateOf(false) }
    var animationGeneration by remember { mutableStateOf(0) }
    var pendingSectionUpdate by remember { mutableStateOf<RoomSection?>(null) }
    // Room summaries are now in-memory only - no need for separate summary tracking
    var lastRoomSectionSignature by remember { mutableStateOf("") }

    // ──────────────────────────────────────────────────────────────────────────
    // SINGLE stableSection updater (merged from two formerly competing effects).
    // Keyed on every trigger that can change the room section: update counters,
    // summary counter, space/bridge selection.  Only ONE call to
    // getCurrentRoomSection() per trigger, eliminating the race condition where
    // two LaunchedEffects would write stableSection concurrently.
    // ──────────────────────────────────────────────────────────────────────────
    LaunchedEffect(roomListUpdateCounter, uiState.roomSummaryUpdateCounter, uiState.currentSpaceId, appViewModel.currentBridgeId) {
        // During initial sync, keep existing content to avoid flicker.
        if (!uiState.initialSyncComplete) {
            val hadContent = stableSection.rooms.isNotEmpty() || stableSection.spaces.isNotEmpty()
            if (hadContent) return@LaunchedEffect
        }

        val newSection = appViewModel.getCurrentRoomSection()

        // ── Section TYPE changed (tab switch) ───────────────────────────────
        if (newSection.type != stableSection.type) {
            isAnimationInProgress = true
            animationGeneration++ // Restarts the guard LaunchedEffect below
            stableSection = newSection
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Section type changed to ${newSection.type}, animation started (gen=$animationGeneration)")
            return@LaunchedEffect
        }

        // ── Mid-animation data update (same type) → queue ──────────────────
        if (isAnimationInProgress) {
            pendingSectionUpdate = newSection
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Animation in progress, queuing section update (rooms:${newSection.rooms.size})")
            return@LaunchedEffect
        }

        // ── Rooms added or removed → always apply immediately ───────────────
        val oldRoomIds = stableSection.rooms.map { it.id }.toSet()
        val newRoomIds = newSection.rooms.map { it.id }.toSet()
        val roomsAdded = newRoomIds - oldRoomIds
        val roomsRemoved = oldRoomIds - newRoomIds

        if (roomsAdded.isNotEmpty() || roomsRemoved.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Rooms added/removed – added:${roomsAdded.size} removed:${roomsRemoved.size}")
            stableSection = newSection
            return@LaunchedEffect
        }

        // ── Anti-flicker: don't replace populated list with empty one ───────
        if (stableSection.rooms.isNotEmpty() && newSection.rooms.isEmpty() && uiState.initialSyncComplete) {
            return@LaunchedEffect
        }

        // ── Fine-grained diff: unread counts, names, order, spaces ──────────
        val oldSig = stableSection.rooms.map { "${it.id}:${it.unreadCount}:${it.highlightCount}:${it.name}" }
        val newSig = newSection.rooms.map { "${it.id}:${it.unreadCount}:${it.highlightCount}:${it.name}" }
        val roomsChanged = oldSig != newSig
        val orderChanged = stableSection.rooms.map { it.id } != newSection.rooms.map { it.id }
        val spacesChanged = stableSection.spaces.map { "${it.id}:${it.name}" } !=
                           newSection.spaces.map { "${it.id}:${it.name}" }

        if (roomsChanged || spacesChanged) {
            if (orderChanged && !roomsChanged) {
                // ANTI-FLICKER: Only order changed – reuse existing room instances to avoid avatar flicker
                val oldRoomsById = stableSection.rooms.associateBy { it.id }
                val reorderedRooms = newSection.rooms.map { oldRoomsById[it.id] ?: it }
                stableSection = newSection.copy(rooms = reorderedRooms)
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Order changed only – preserved ${reorderedRooms.size} room instances")
            } else {
                stableSection = newSection
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Section data changed – rooms:$roomsChanged order:$orderChanged spaces:$spacesChanged")
            }
        } else if (stableSection.rooms.isEmpty() && stableSection.spaces.isEmpty()) {
            // Both old and new are empty – safe to apply (e.g. initial population)
            stableSection = newSection
        }
        // Otherwise nothing meaningful changed – skip update to avoid avatar flashing
    }

    // Room summaries are now in-memory only - data is already in stableSection.rooms
    // No need for separate summary tracking

    // Track room section signature for potential analytics / future settling logic
    LaunchedEffect(stableSection.rooms) {
        lastRoomSectionSignature = stableSection.rooms.joinToString("|") { room ->
            "${room.id}:${room.unreadCount}:${room.highlightCount}:${room.messagePreview}:${room.sortingTimestamp}"
        }
    }

    // Track when initial load is complete for optimization purposes
    LaunchedEffect(effectiveProfileLoaded, shouldBlockForPending, effectiveSpacesLoaded, effectiveInitialSyncComplete, coldStartRefreshing) {
        if (effectiveProfileLoaded && !shouldBlockForPending && effectiveSpacesLoaded && effectiveInitialSyncComplete && !coldStartRefreshing) {
            if (!initialLoadComplete) {
                initialLoadComplete = true
            }
        }
    }
    
    // Use inline status row instead of full-screen loading overlay
    // Show inline status during initial load or ongoing sync operations
    // CRITICAL FIX: Profile loading is non-blocking - don't show "Loading profile..." message
    // CRITICAL FIX: If we have rooms in the section, don't show "Loading spaces..." even if spacesLoaded is false
    // This handles the case where we navigate back from room_timeline (opened from notification)
    // and rooms are already loaded but spacesLoaded hasn't been set yet
    val hasRooms = stableSection.rooms.isNotEmpty()
    val shouldShowSpacesLoading = !effectiveSpacesLoaded && !hasRooms // Only show if we have no rooms
    val inlineSyncInProgress = coldStartRefreshing || 
        shouldBlockForPending || 
        shouldShowSpacesLoading || 
        !effectiveInitialSyncComplete
    
    val inlineSyncMessage = when {
        shouldBlockForPending -> "Catching up on messages..."
        shouldShowSpacesLoading -> "Loading spaces..."
        !effectiveInitialSyncComplete -> {
            // Show sync progress counter if available
            if (uiState.pendingSyncCompleteCount > 0) {
                "${uiState.processedSyncCompleteCount} / ${uiState.pendingSyncCompleteCount} messages"
            } else {
                "Finalizing sync..."
            }
        }
        coldStartRefreshing -> "Refreshing rooms..."
        else -> "Refreshing rooms..."
    }
    val showNotificationActionIndicator = uiState.notificationActionInProgress
    
    // PERFORMANCE: allRooms is already sorted by performRoomReorder() and filtered
    // subsets (DMs, Unread, Favourites) inherit that order — no re-sort needed.
    val displayedSection = stableSection

    // One-time hydration: if any rooms lack timestamps, prefetch a small batch from server for all of them.
    LaunchedEffect(initialLoadComplete, displayedSection.rooms.map { it.id to it.sortingTimestamp }) {
        if (!initialLoadComplete || missingTimestampsHydrated) return@LaunchedEffect

        val missingTsRooms = displayedSection.rooms
            .filter { (it.sortingTimestamp ?: 0L) <= 0L }

        if (missingTsRooms.isNotEmpty()) {
            missingTimestampsHydrated = true
            // Run sequentially to avoid hammering; this is a one-time bootstrap hydrate.
            // Disabled, did not solve the problem and is expensive
            //withContext(Dispatchers.IO) {
            //    for (room in missingTsRooms) {
            //        runCatching {
            //            appViewModel.prefetchRoomSnapshot(room.id, limit = 20, timeoutMs = 4000L)
            //        }
            //    }
            //}
        }
    }
    
    // PERFORMANCE: Preemptively load the first 100 room avatars into memory cache
    // This makes scrolling faster since avatars are already loaded when they come into view
    var avatarsPreloaded by remember { mutableStateOf(false) }
    LaunchedEffect(initialLoadComplete, displayedSection.rooms.size, displayedSection.type) {
        // Only preload once per section when room list is ready and has rooms
        if (!initialLoadComplete || avatarsPreloaded || displayedSection.rooms.isEmpty()) return@LaunchedEffect
        
        // Preload avatars in background to avoid blocking UI
        withContext(Dispatchers.IO) {
            val imageLoader = ImageLoaderSingleton.get(context)
            val roomsToPreload = displayedSection.rooms.take(100)
            
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Preloading ${roomsToPreload.size} avatars for faster scrolling")
            }
            
            // Preload avatars for the first 100 rooms
            roomsToPreload.forEach { room ->
                if (room.avatarUrl != null) {
                    try {
                        // Get avatar URL (same logic as AvatarImage component)
                        val avatarHttpUrl = AvatarUtils.mxcToHttpUrl(
                            room.avatarUrl,
                            appViewModel.homeserverUrl
                        )
                        
                        if (avatarHttpUrl != null) {
                            // Create ImageRequest and enqueue it to preload into memory cache
                            val request = ImageRequest.Builder(context)
                                .data(avatarHttpUrl)
                                .size(256) // Same size as used in AvatarImage for room list
                                .addHeader("Cookie", "gomuks_auth=$authToken")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                            
                            // Enqueue the request to preload into memory cache
                            // This doesn't block and loads images into Coil's memory cache
                            imageLoader.enqueue(request)
                        }
                    } catch (e: Exception) {
                        // Silently ignore errors - preloading is best-effort
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "RoomListScreen: Failed to preload avatar for room ${room.id}: ${e.message}")
                        }
                    }
                }
            }
            
            avatarsPreloaded = true
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "RoomListScreen: Finished preloading ${roomsToPreload.size} avatars")
            }
        }
    }
    
    // Reset preload flag when section type changes (switching tabs)
    LaunchedEffect(displayedSection.type) {
        avatarsPreloaded = false
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Animation guard: waits for the tab-switch animation to finish before
    // applying any queued data updates.  Keyed on animationGeneration so rapid
    // tab switches cancel the previous delay and start a fresh one (the old
    // delay(1000) could expire mid-animation if two tabs were tapped quickly).
    // Duration matches the actual AnimatedContent transitionSpec: 420ms enter +
    // 30ms safety buffer = 450ms.
    // ──────────────────────────────────────────────────────────────────────────
    LaunchedEffect(animationGeneration) {
        if (isAnimationInProgress) {
            delay(450) // 420ms animation + 30ms buffer
            isAnimationInProgress = false
            pendingSectionUpdate?.let { pending ->
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Animation completed (gen=$animationGeneration), applying pending section update (${pending.rooms.size} rooms)")
                stableSection = pending
                pendingSectionUpdate = null
            }
        }
    }
    
    // Always show the interface, even if rooms/spaces are empty
    var searchQuery by remember { mutableStateOf("") }
    // Note: me is already declared above in the loading check
    
    // Observe invites in RoomListScreen so we can display them in a separate section
    val roomListUpdateCounterForInvites = uiState.roomListUpdateCounter
    val pendingInvites = remember(roomListUpdateCounterForInvites) {
        appViewModel.getPendingInvites()
    }
    
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
        
        // CRITICAL FIX: When RoomListScreen is created for the first time via back navigation from notification/shortcut,
        // roomMap might be empty or only have 1 room (because a new AppViewModel instance was created with empty roomMap).
        // Check if singleton cache has more rooms than roomMap, and if so, populate from cache.
        appViewModel.forceRoomListSort() // Update allRooms from roomMap first
        val initialSnapshot = appViewModel.getCurrentRoomSection()
        val initialRoomCount = initialSnapshot.rooms.size
        val cacheRoomCount = net.vrkknn.andromuks.RoomListCache.getRoomCount()
        
        if (initialRoomCount < cacheRoomCount && cacheRoomCount > 1) {
            // Suspicious - roomMap has fewer rooms than cache, likely from notification/shortcut navigation
            // Load rooms from singleton cache to populate roomMap
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: RoomMap has $initialRoomCount rooms but cache has $cacheRoomCount rooms (likely from notification/shortcut navigation), loading from singleton cache")
            appViewModel.populateRoomMapFromCache()
            appViewModel.populateSpacesFromCache()
            appViewModel.forceRoomListSort() // Update allRooms after cache load
            val loadedSnapshot = appViewModel.getCurrentRoomSection()
            stableSection = loadedSnapshot
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Loaded ${loadedSnapshot.rooms.size} rooms from singleton cache")
        } else {
            // Normal case - use existing roomMap
            stableSection = initialSnapshot
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Using existing roomMap with ${initialRoomCount} rooms (cache has $cacheRoomCount rooms)")
        }
        
        // Force timestamp update when returning to room list or starting app
        // This ensures timestamps are immediately up-to-date when user views the list
        smartTimestampUpdateCounter++
    }
    
    // CRITICAL FIX #2: Wait for WebSocket connection and spacesLoaded before navigating from shortcuts/notifications
    // OPTIMIZATION: If room is cached (from preemptive pagination), navigate immediately without waiting for spacesLoaded
    // This ensures proper state before showing room timeline (prevents "only last message" issue)
    // Check on first render if spacesLoaded is already true OR if room is cached
    LaunchedEffect(Unit) {
        val directRoomId = appViewModel.getDirectRoomNavigation()
        val pendingRoomId = appViewModel.getPendingRoomNavigation()
        
        if (directRoomId != null || pendingRoomId != null) {
            val roomIdToCheck = directRoomId ?: pendingRoomId
            if (roomIdToCheck != null) {
                // Check if room is cached (from preemptive pagination) - if so, navigate immediately after WebSocket connects
                val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomIdToCheck)
                val isRoomCached = cachedEventCount >= 10 || RoomTimelineCache.isRoomActivelyCached(roomIdToCheck)
                
                if (isRoomCached) {
                    // Room is cached - wait for WebSocket connection, then navigate immediately (don't wait for spacesLoaded)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Room $roomIdToCheck is cached ($cachedEventCount events), waiting for WebSocket connection then navigating immediately")
                    
                    var websocketConnected = appViewModel.isWebSocketConnected()
                    var pollCount = 0
                    while (!websocketConnected && pollCount < 50) { // Max 5 seconds for cached rooms
                        kotlinx.coroutines.delay(100) // Check every 100ms
                        websocketConnected = appViewModel.isWebSocketConnected()
                        pollCount++
                    }
                    
                    if (websocketConnected || pollCount >= 50) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Room $roomIdToCheck is cached, WebSocket connected=$websocketConnected (pollCount=$pollCount), navigating immediately")
                        
                        if (directRoomId != null) {
                            val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                            appViewModel.clearDirectRoomNavigation()
                            // CRITICAL FIX: Flush buffered sync_complete messages BEFORE navigating
                            // Without this, navController.navigate() fires before the async flush
                            // inside navigateToRoomWithCache completes, so the RoomTimelineScreen
                            // renders from stale cache (missing the latest events).
                            appViewModel.flushSyncBatchForRoom(directRoomId)
                            if (notificationTimestamp != null) {
                                appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                            } else {
                                appViewModel.navigateToRoomWithCache(directRoomId)
                            }
                            navController.navigate("room_timeline/$directRoomId")
                        } else if (pendingRoomId != null) {
                            appViewModel.clearPendingRoomNavigation()
                            appViewModel.flushSyncBatchForRoom(pendingRoomId)
                            appViewModel.navigateToRoomWithCache(pendingRoomId)
                            navController.navigate("room_timeline/$pendingRoomId")
                        }
                    }
                } else {
                    // Room not cached - wait for spacesLoaded and WebSocket connection
                    if (uiState.spacesLoaded) {
                        var websocketConnected = appViewModel.isWebSocketConnected()
                        var pollCount = 0
                        while (!websocketConnected && pollCount < 100) {
                            kotlinx.coroutines.delay(100) // Check every 100ms
                            websocketConnected = appViewModel.isWebSocketConnected()
                            pollCount++
                        }
                        
                        if (websocketConnected || pollCount >= 100) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: CRITICAL FIX #2 - WebSocket connected=$websocketConnected (pollCount=$pollCount) and spaces loaded, navigating")
                            
                            if (directRoomId != null) {
                                val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                                appViewModel.clearDirectRoomNavigation()
                                appViewModel.flushSyncBatchForRoom(directRoomId)
                                if (notificationTimestamp != null) {
                                    appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                                } else {
                                    appViewModel.navigateToRoomWithCache(directRoomId)
                                }
                                navController.navigate("room_timeline/$directRoomId")
                            } else if (pendingRoomId != null) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: CRITICAL FIX #2 - WebSocket connected and spaces loaded, navigating to pending room: $pendingRoomId")
                                appViewModel.clearPendingRoomNavigation()
                                appViewModel.flushSyncBatchForRoom(pendingRoomId)
                                appViewModel.navigateToRoomWithCache(pendingRoomId)
                                navController.navigate("room_timeline/$pendingRoomId")
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Also check when spacesLoaded changes to true (fallback for when room is not cached)
    LaunchedEffect(uiState.spacesLoaded) {
        val directRoomId = appViewModel.getDirectRoomNavigation()
        val pendingRoomId = appViewModel.getPendingRoomNavigation()
        
        if ((directRoomId != null || pendingRoomId != null) && uiState.spacesLoaded) {
            val roomIdToCheck = directRoomId ?: pendingRoomId
            if (roomIdToCheck != null) {
                // Double-check: if room is cached, navigate immediately (handled by first LaunchedEffect, but check again in case it wasn't)
                val cachedEventCount = RoomTimelineCache.getCachedEventCount(roomIdToCheck)
                val isRoomCached = cachedEventCount >= 10 || RoomTimelineCache.isRoomActivelyCached(roomIdToCheck)
                
                if (isRoomCached && appViewModel.isWebSocketConnected()) {
                    // Room is cached - navigate immediately (should have been handled by first LaunchedEffect, but handle it here too)
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Room $roomIdToCheck is cached ($cachedEventCount events) and spaces loaded, navigating immediately")
                    
                    if (directRoomId != null) {
                        val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                        appViewModel.clearDirectRoomNavigation()
                        appViewModel.flushSyncBatchForRoom(directRoomId)
                        if (notificationTimestamp != null) {
                            appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                        } else {
                            appViewModel.navigateToRoomWithCache(directRoomId)
                        }
                        navController.navigate("room_timeline/$directRoomId")
                    } else if (pendingRoomId != null) {
                        appViewModel.clearPendingRoomNavigation()
                        appViewModel.flushSyncBatchForRoom(pendingRoomId)
                        appViewModel.navigateToRoomWithCache(pendingRoomId)
                        navController.navigate("room_timeline/$pendingRoomId")
                    }
                } else {
                    // Room not cached - wait for WebSocket connection
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
                            val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                            appViewModel.clearDirectRoomNavigation()
                            appViewModel.flushSyncBatchForRoom(directRoomId)
                            if (notificationTimestamp != null) {
                                appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                            } else {
                                appViewModel.navigateToRoomWithCache(directRoomId)
                            }
                            navController.navigate("room_timeline/$directRoomId")
                        } else if (pendingRoomId != null) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: CRITICAL FIX #2 - WebSocket connected and spaces loaded, navigating to pending room: $pendingRoomId")
                            appViewModel.clearPendingRoomNavigation()
                            appViewModel.flushSyncBatchForRoom(pendingRoomId)
                            appViewModel.navigateToRoomWithCache(pendingRoomId)
                            navController.navigate("room_timeline/$pendingRoomId")
                        }
                    }
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
                appViewModel.flushSyncBatchForRoom(stillDirectRoomId)
                if (notificationTimestamp != null) {
                    appViewModel.navigateToRoomWithCache(stillDirectRoomId, notificationTimestamp)
                } else {
                    appViewModel.navigateToRoomWithCache(stillDirectRoomId)
                }
                navController.navigate("room_timeline/$stillDirectRoomId")
            } else if (stillPendingRoomId != null) {
                android.util.Log.w("Andromuks", "RoomListScreen: CRITICAL FIX #2 - Navigation timeout (10s) for pending room $stillPendingRoomId - WebSocket may not be connected, navigating anyway")
                appViewModel.clearPendingRoomNavigation()
                appViewModel.flushSyncBatchForRoom(stillPendingRoomId)
                appViewModel.navigateToRoomWithCache(stillPendingRoomId)
                navController.navigate("room_timeline/$stillPendingRoomId")
            }
        }
    }
    
    // CRITICAL FIX: Reactive navigation for onNewIntent (notification tap on existing Activity).
    // The LaunchedEffect(Unit) blocks above only run once on first composition.
    // When FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP reuses the existing Activity,
    // onNewIntent() sets directRoomNavigation, but nothing re-fires. This LaunchedEffect
    // observes the trigger counter and handles the navigation.
    val navigationTrigger = appViewModel.directRoomNavigationTrigger
    LaunchedEffect(navigationTrigger) {
        if (navigationTrigger == 0) return@LaunchedEffect // Skip initial composition (handled by Unit blocks above)
        
        val directRoomId = appViewModel.getDirectRoomNavigation() ?: return@LaunchedEffect
        val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: onNewIntent reactive navigation - room=$directRoomId, trigger=$navigationTrigger")
        
        appViewModel.clearDirectRoomNavigation()
        appViewModel.flushSyncBatchForRoom(directRoomId)
        if (notificationTimestamp != null) {
            appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
        } else {
            appViewModel.navigateToRoomWithCache(directRoomId)
        }
        navController.navigate("room_timeline/$directRoomId")
    }
    
    // Determine update interval based on the newest message in the current section
    val updateInterval = remember(displayedSection.rooms) {
        val now = System.currentTimeMillis()
        val today = java.util.Date(now)
        val todayCalendar = java.util.Calendar.getInstance()
        todayCalendar.time = today
        
        val newestTimestamp = displayedSection.rooms
            .mapNotNull { it.sortingTimestamp }
            .filter { it > 0 }
            .maxOrNull() ?: 0L
        
        if (newestTimestamp == 0L) {
            86_400_000L // Default to daily if no timestamps
        } else {
            val eventDate = java.util.Date(newestTimestamp)
            val eventCalendar = java.util.Calendar.getInstance()
            eventCalendar.time = eventDate
            
            // Check if newest message is from today
            val isToday = eventCalendar.get(java.util.Calendar.YEAR) == todayCalendar.get(java.util.Calendar.YEAR) &&
                          eventCalendar.get(java.util.Calendar.DAY_OF_YEAR) == todayCalendar.get(java.util.Calendar.DAY_OF_YEAR)
            
            if (isToday) {
                60_000L // Update every minute for today's messages (hh:mm format)
            } else {
                86_400_000L // Update daily for yesterday and older messages
            }
        }
    }
    
    // Single shared timer that updates at the appropriate interval
    // When rooms change, the interval recalculates and timer restarts
    LaunchedEffect(displayedSection.rooms, updateInterval) {
        while (true) {
            delay(updateInterval)
            smartTimestampUpdateCounter++
        }
    }
    
    // Force timestamp update when section type changes (tab switch)
    // This ensures timestamps are immediately refreshed when switching between Home/Spaces/Direct/etc.
    LaunchedEffect(displayedSection.type) {
        smartTimestampUpdateCounter++
    }
    
    // Pull-to-refresh state
    var refreshing by remember { mutableStateOf(false) }
    var showRefreshConfirmation by remember { mutableStateOf(false) }
    
    // Track if we're at the top of the current section's list
    // This ensures pull-to-refresh only works when gesture starts at the top
    var isAtTopOfList by remember { mutableStateOf(true) }
    var currentListStateForPullRefresh by remember { mutableStateOf<LazyListState?>(null) }
    
    // Update isAtTopOfList when section or scroll position changes
    LaunchedEffect(displayedSection.type, listStates[displayedSection.type]) {
        val listState = listStates[displayedSection.type]
        currentListStateForPullRefresh = listState
        
        // Check if we're at the top (first item visible and no scroll offset)
        snapshotFlow {
            Pair(
                listState?.firstVisibleItemIndex ?: -1,
                listState?.firstVisibleItemScrollOffset ?: -1
            )
        }.collect { (firstIndex, scrollOffset) ->
            isAtTopOfList = firstIndex == 0 && scrollOffset == 0
        }
    }
    
    // Function to perform the actual refresh
    fun performRefresh() {
        refreshing = true
        // CRITICAL FIX: Use performFullRefresh() to properly clear all state and reset lastReceivedRequestId
        // This ensures we reconnect without last_received_id to get a full payload (like cold start)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Pull-to-refresh confirmed - performing full refresh")
        // Clear lastReceivedRequestId from SharedPreferences so reconnect doesn't use it
        net.vrkknn.andromuks.WebSocketService.clearLastReceivedRequestId(context)
        // Perform full refresh which clears all state and triggers reconnection
        appViewModel.performFullRefresh()
        // Navigate to auth_check which will handle WebSocket reconnection and show StartupLoadingScreen
        navController.navigate("auth_check") {
            // Clear back stack so user doesn't go back to stale room_list
            popUpTo(0) { inclusive = true }
        }
        // Reset refreshing state immediately since navigation will show loading screen
        refreshing = false
    }
    
    val refreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = {
            // Double-check we're at the top before allowing refresh
            val listState = currentListStateForPullRefresh
            val actuallyAtTop = listState?.firstVisibleItemIndex == 0 && 
                               listState.firstVisibleItemScrollOffset == 0
            
            if (!actuallyAtTop) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Pull-to-refresh blocked - not at top of list")
                return@rememberPullRefreshState
            }
            
            // Show confirmation dialog instead of immediately refreshing
            showRefreshConfirmation = true
            refreshing = false // Reset refreshing state since we're showing dialog
        }
    )
    
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
    val processedMessageSenders = remember { mutableStateMapOf<String, Boolean>() }
    
    // Create a stable key based on actual message senders (not rooms list) to prevent excessive re-runs
    val messageSendersKey = remember(stableSection.rooms) {
        stableSection.rooms.take(50)
            .mapNotNull { room -> room.messageSender }
            .distinct()
            .filter { sender -> sender != appViewModel.currentUserId }
            .sorted()
            .joinToString(",")
    }
    
    LaunchedEffect(messageSendersKey, effectiveInitialSyncComplete) {
        // Wait for initial sync completion before requesting profiles
        if (!effectiveInitialSyncComplete) {
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
        val newSenders = messageSenders.filter { sender -> processedMessageSenders[sender] != true }
        
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
            newSenders.forEach { sender -> processedMessageSenders[sender] = true }
        }
        
        // Clean up processed senders that are no longer in the current list
        val toRemove = processedMessageSenders.keys.filter { it !in messageSenders }
        toRemove.forEach { processedMessageSenders.remove(it) }
    }
    
    // Only enable pull-to-refresh when at the top of the list
    // This prevents accidental triggers when scrolling up from the middle
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                // Only apply pullRefresh when we're at the top of the list
                // This ensures the gesture must start at the top to trigger refresh
                if (isAtTopOfList) {
                    Modifier.pullRefresh(refreshState)
                } else {
                    Modifier
                }
            )
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
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = appViewModel.currentUserId.isNotBlank()) {
                            navController.navigateToUserInfo(appViewModel.currentUserId)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            net.vrkknn.andromuks.ui.components.AvatarImage(
                                mxcUrl = me?.avatarUrl,
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = authToken,
                                fallbackText = me?.displayName ?: appViewModel.currentUserId,
                                size = 40.dp,
                                userId = appViewModel.currentUserId,
                                displayName = me?.displayName,
                                modifier = Modifier.sharedElement(
                                    rememberSharedContentState(key = STARTUP_SHARED_AVATAR_KEY),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        tween(durationMillis = 380, easing = androidx.compose.animation.core.LinearEasing)
                                    },
                                    renderInOverlayDuringTransition = true,
                                    zIndexInOverlay = 1f
                                )
                            )
                        }
                    } else {
                        net.vrkknn.andromuks.ui.components.AvatarImage(
                            mxcUrl = me?.avatarUrl,
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = authToken,
                            fallbackText = me?.displayName ?: appViewModel.currentUserId,
                            size = 40.dp,
                            userId = appViewModel.currentUserId,
                            displayName = me?.displayName
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = me?.displayName ?: appViewModel.currentUserId.ifBlank { "Profile" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // PHASE 1: Pulsing processing indicator - show when sync is processing OR when room list is updating
                            // CRASH FIX: Show warning color when batch processing (rushing) to indicate tab switching is disabled
                            // Reuse isProcessingBatch from top level (line 183) - no need to collect twice
                            var showSyncIndicator by remember { mutableStateOf(false) }
                            LaunchedEffect(uiState.roomListUpdateCounter) {
                                if (uiState.roomListUpdateCounter > 0) {
                                    showSyncIndicator = true
                                    //if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Showing sync indicator (counter=${uiState.roomListUpdateCounter})")
                                    kotlinx.coroutines.delay(500) // Show for 0.5 seconds to be more visible
                                    showSyncIndicator = false
                                }
                            }
                            // DEBUG: Log batch processing state changes
                            LaunchedEffect(isProcessingBatch) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: isProcessingBatch changed to $isProcessingBatch")
                            }
                            // Normal sync indicator (blue, pulsing)
                            AnimatedVisibility(
                                visible = uiState.isProcessingPendingItems || showSyncIndicator || isProcessingBatch,
                                enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.5f, animationSpec = tween(200)),
                                exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.5f, animationSpec = tween(200))
                            ) {
                                val infiniteTransition = rememberInfiniteTransition(label = "sync_pulse")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.3f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "sync_pulse_alpha"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                                            CircleShape
                                        )
                                )
                            }
                            // "RUSH" text indicator for batch processing (error color, next to sync pill)
                            // No animation - appears instantly since batch processing is brief
                            if (isProcessingBatch) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: RUSH indicator visible - isProcessingBatch=true")
                                Text(
                                    text = "RUSH",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
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
                }
                
                // Mentions button (Bell icon)
                IconButton(
                    onClick = { 
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        navController.navigate("mentions") 
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Mentions",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
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
            
            // Extra spacing between the search bar and the room list/content for better visual separation
            Spacer(modifier = Modifier.height(8.dp))
            
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
            
            // Room invitations section - shown above room list for immediate visibility
            AnimatedVisibility(pendingInvites.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Room Invitations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    pendingInvites.forEach { invite ->
                        InviteListItem(
                            invite = invite,
                            onClick = {
                                inviteToJoin = invite
                                showRoomJoiner = true
                            },
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = authToken
                        )
                    }
                }
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
                targetState = displayedSection,
                // CRITICAL FIX: Use contentKey to only animate when section TYPE changes, not when data changes
                // This prevents unnecessary transitions when returning from RoomTimelineScreen
                // and ensures scrolling works immediately
                contentKey = { it.type },
                transitionSpec = {
                    val oldType = initialState.type
                    val newType = targetState.type
                    val direction = newType.ordinal - oldType.ordinal
                    val enter = when {
                        direction > 0 -> slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(360, easing = FastOutSlowInEasing))
                        direction < 0 -> slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(360, easing = FastOutSlowInEasing))
                        else -> EnterTransition.None
                    }
                    val exit = when {
                        direction > 0 -> slideOutHorizontally(
                            targetOffsetX = { -it / 2 },
                            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(320, easing = FastOutSlowInEasing))
                        direction < 0 -> slideOutHorizontally(
                            targetOffsetX = { it / 2 },
                            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(320, easing = FastOutSlowInEasing))
                        else -> ExitTransition.None
                    }
                    enter togetherWith exit
                },
                label = "SectionTransition"
            ) { targetSection ->
                    val currentListState = listStates.getOrPut(targetSection.type) { LazyListState() }
                    when (targetSection.type) {
                        RoomSectionType.HOME -> {
                            RoomListContent(
                                rooms = targetSection.rooms,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController,
                                timestampUpdateTrigger = smartTimestampUpdateCounter,
                                hapticFeedback = hapticFeedback,
                                listState = currentListState,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onInviteClick = { 
                                    // Invites are now handled in RoomListScreen, not here
                                }
                            )
                        }
                        RoomSectionType.SPACES -> {
                            val currentSpaceId = appViewModel.currentSpaceId
                            val isInSpace = currentSpaceId != null
                            
                            // CRITICAL: Get spaces directly from viewmodel, not from targetSection
                            // When entering a space, targetSection.spaces becomes empty, breaking the exit animation
                            // Get spaces from viewmodel so animation always has content
                            val spacesForAnimation = remember(appViewModel.allSpaces, isInSpace) {
                                // Always use spaces from viewmodel - they're stable and available even when in a space
                                appViewModel.allSpaces.map { space ->
                                    SpaceItem(
                                        id = space.id,
                                        name = space.name,
                                        avatarUrl = space.avatarUrl,
                                        rooms = space.rooms
                                    )
                                }
                            }
                            
                            // Use Box with AnimatedVisibility for both pieces so both can animate simultaneously
                            // This creates the "Zoom Through" / Container Transform effect
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Space list - visible when NOT in a space
                                // Use spacesForAnimation which is always populated from viewmodel
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = !isInSpace,
                                    exit = slideOutVertically(
                                        targetOffsetY = { -it }, // Slide up (negative offset = upward) when entering space
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    enter = slideInVertically(
                                        initialOffsetY = { -it }, // Slide down from top (negative offset = from above) when exiting space
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    label = "SpaceListTransition"
                                ) {
                                    SpacesListContent(
                                        spaces = spacesForAnimation,
                                        searchQuery = searchQuery,
                                        appViewModel = appViewModel,
                                        authToken = authToken,
                                        navController = navController,
                                        listState = currentListState
                                    )
                                }
                                
                                // Rooms - visible when IN a space
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isInSpace,
                                    exit = scaleOut(
                                        targetScale = 0.0f, // Zoom out completely when exiting space
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    enter = scaleIn(
                                        initialScale = 0.0f, // Zoom in from nothing when entering space
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    label = "RoomsTransition"
                                ) {
                                    RoomListContent(
                                        rooms = targetSection.rooms,
                                        searchQuery = searchQuery,
                                        appViewModel = appViewModel,
                                        authToken = authToken,
                                        navController = navController,
                                        timestampUpdateTrigger = smartTimestampUpdateCounter,
                                        hapticFeedback = hapticFeedback,
                                        listState = currentListState,
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        onInviteClick = { invite ->
                                            inviteToJoin = invite
                                            showRoomJoiner = true
                                        }
                                    )
                                }
                            }
                        }
                        RoomSectionType.DIRECT_CHATS -> {
                            RoomListContent(
                                rooms = targetSection.rooms,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController,
                                timestampUpdateTrigger = smartTimestampUpdateCounter,
                                hapticFeedback = hapticFeedback,
                                listState = currentListState,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onInviteClick = { 
                                    // Invites are now handled in RoomListScreen, not here
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
                                timestampUpdateTrigger = smartTimestampUpdateCounter,
                                hapticFeedback = hapticFeedback,
                                listState = currentListState,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onInviteClick = { 
                                    // Invites are now handled in RoomListScreen, not here
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
                                timestampUpdateTrigger = smartTimestampUpdateCounter,
                                hapticFeedback = hapticFeedback,
                                listState = currentListState,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onInviteClick = { 
                                    // Invites are now handled in RoomListScreen, not here
                                }
                            )
                        }
                        RoomSectionType.BRIDGES -> {
                            val currentBridgeId = appViewModel.currentBridgeId
                            val isInBridge = currentBridgeId != null
                            
                            // CRITICAL: Get bridges directly from viewmodel, not from targetSection
                            // When entering a bridge, targetSection.spaces becomes empty, breaking the exit animation
                            // Get bridges from viewmodel so animation always has content
                            val bridgesForAnimation = remember(targetSection.spaces, isInBridge) {
                                // Use bridges from targetSection - they're the pseudo-spaces we created
                                targetSection.spaces.map { bridge ->
                                    SpaceItem(
                                        id = bridge.id,
                                        name = bridge.name,
                                        avatarUrl = bridge.avatarUrl,
                                        rooms = bridge.rooms
                                    )
                                }
                            }
                            
                            // Use Box with AnimatedVisibility for both pieces so both can animate simultaneously
                            // This creates the "Zoom Through" / Container Transform effect (same as Spaces)
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Bridge list - visible when NOT in a bridge
                                // Use bridgesForAnimation which is always populated from targetSection
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = !isInBridge,
                                    exit = slideOutVertically(
                                        targetOffsetY = { -it }, // Slide up (negative offset = upward) when entering bridge
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    enter = slideInVertically(
                                        initialOffsetY = { -it }, // Slide down from top (negative offset = from above) when exiting bridge
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    label = "BridgeListTransition"
                                ) {
                                    BridgesListContent(
                                        bridges = bridgesForAnimation,
                                        searchQuery = searchQuery,
                                        appViewModel = appViewModel,
                                        authToken = authToken,
                                        navController = navController,
                                        listState = currentListState
                                    )
                                }
                                
                                // Rooms - visible when IN a bridge
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isInBridge,
                                    exit = scaleOut(
                                        targetScale = 0.0f, // Zoom out completely when exiting bridge
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    enter = scaleIn(
                                        initialScale = 0.0f, // Zoom in from nothing when entering bridge
                                        animationSpec = tween(1000, easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(1000, easing = FastOutSlowInEasing)),
                                    label = "BridgeRoomsTransition"
                                ) {
                                    RoomListContent(
                                        rooms = targetSection.rooms,
                                        searchQuery = searchQuery,
                                        appViewModel = appViewModel,
                                        authToken = authToken,
                                        navController = navController,
                                        timestampUpdateTrigger = smartTimestampUpdateCounter,
                                        hapticFeedback = hapticFeedback,
                                        listState = currentListState,
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        onInviteClick = { invite ->
                                            inviteToJoin = invite
                                            showRoomJoiner = true
                                        }
                                    )
                                }
                            }
                        }
                        RoomSectionType.MENTIONS -> {
                            // Mentions are accessed via the header button, not through tabs
                            // Show empty state (should not normally be reached)
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Use the bell icon in the header to view mentions",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            // Tab bar at the bottom (outside the Surface)
            // CRASH FIX: Disable tab switching during batch processing to prevent animation conflicts
            TabBar(
                currentSection = displayedSection,
                onSectionSelected = { section ->
                    // CRASH FIX: Prevent tab switching during batch processing
                    if (isProcessingBatch) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Tab switch blocked - batch processing in progress")
                        return@TabBar
                    }
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    appViewModel.changeSelectedSection(section)
                    // Force timestamp update when switching tabs
                    // This ensures timestamps are immediately up-to-date when user switches sections
                    smartTimestampUpdateCounter++
                },
                appViewModel = appViewModel,
                isProcessingBatch = isProcessingBatch
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
        
        // Pull-to-refresh confirmation dialog
        if (showRefreshConfirmation) {
            Dialog(
                onDismissRequest = { 
                    showRefreshConfirmation = false
                },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        // Title
                        Text(
                            text = "Reconnect?",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        // Message
                        Text(
                            text = "This will disconnect and reconnect to the server, refreshing all room data. Continue?",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        // Buttons row: OK on left, Cancel on right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // OK button on the left
                            Button(
                                onClick = {
                                    showRefreshConfirmation = false
                                    performRefresh()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("OK")
                            }
                            // Cancel button on the right
                            Button(
                                onClick = { 
                                    showRefreshConfirmation = false
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
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

@OptIn(ExperimentalSharedTransitionApi::class)
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
    isEnabled: Boolean = true,
    isScrollingFast: Boolean = false, // PERFORMANCE: Suspend avatar loading during fast scroll
    shouldLoadAvatar: Boolean = true, // PERFORMANCE: Load avatars for items below viewport
    sharedTransitionScope: SharedTransitionScope? = null, // SHARED TRANSITION: Scope for shared element animation
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null // SHARED TRANSITION: Scope for shared element animation
) {
    val context = LocalContext.current
    var showContextMenu by remember { mutableStateOf(false) }
    
    // PERFORMANCE: Remember computed timestamp to avoid recalculation unless it actually changes
    // The timestampUpdateTrigger now updates at smart intervals (1s for recent, 1m for older, etc.)
    // This prevents expensive recompositions every second for all rooms
    val timeAgo = remember(room.sortingTimestamp, timestampUpdateTrigger) {
        formatTimeAgo(room.sortingTimestamp)
    }
    
    // PERFORMANCE: Cache sender profile lookup to avoid expensive operations on every recomposition
    val senderDisplayName = remember(room.messageSender, room.id, appViewModel.memberUpdateCounter) {
        if (room.messageSender != null) {
            val senderProfile = appViewModel.getUserProfile(room.messageSender, room.id)
            senderProfile?.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(room.messageSender)
        } else {
            null
        }
    }
    // If we still don't have a profile display name, opportunistically request it so the
    // UI can re-render with the proper display name instead of the raw userId.
    val requestedProfile = remember(room.id, room.messageSender) { mutableStateOf(false) }
    LaunchedEffect(room.id, room.messageSender, senderDisplayName) {
        val senderId = room.messageSender
        if (senderId != null && senderDisplayName == senderId && !requestedProfile.value) {
            requestedProfile.value = true
            appViewModel.requestUserProfileOnDemand(senderId, room.id)
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
            // Room avatar with optional bridge protocol badge
            key(room.id) { // CRITICAL: Prevent "wrong avatar" flicker on shared transitions
                Box {
                    // Shared-element tagged avatar when transition scopes are available
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        val sharedKey = "avatar-${room.id}"
                        with(sharedTransitionScope) {
                            net.vrkknn.andromuks.ui.components.AvatarImage(
                                mxcUrl = room.avatarUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                fallbackText = room.name,
                                size = 48.dp,
                                userId = room.id,
                                displayName = room.name,
                                // AVATAR LOADING OPTIMIZATION: Load avatars for visible items and items below viewport
                                isVisible = shouldLoadAvatar, // Load for visible items + 25 items below viewport
                                // CIRCLE AVATAR CACHE: Use CircleAvatarCache for room avatars to avoid repeated clipping
                                useCircleCache = true,
                                // PERFORMANCE: Suspend avatar loading during fast scrolling
                                isScrollingFast = isScrollingFast,
                                modifier = Modifier
                                    .sharedElement(
                                        rememberSharedContentState(key = sharedKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        boundsTransform = { _, _ ->
                                            androidx.compose.animation.core.spring(
                                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                                            )
                                        },
                                        renderInOverlayDuringTransition = true,
                                        zIndexInOverlay = 1f
                                    )
                                    .clip(CircleShape)
                            )
                        }
                    } else {
                        // Fallback: regular clipped avatar without shared-element tag
                        net.vrkknn.andromuks.ui.components.AvatarImage(
                            mxcUrl = room.avatarUrl,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            fallbackText = room.name,
                            size = 48.dp,
                            userId = room.id,
                            displayName = room.name,
                            isVisible = shouldLoadAvatar,
                            useCircleCache = true,
                            isScrollingFast = isScrollingFast,
                            modifier = Modifier.clip(CircleShape)
                        )
                    }
                
                    // Bridge protocol avatar badge (bottom-right corner)
                    // PERFORMANCE: Use lightweight AsyncImage directly instead of full AvatarImage.
                    // Protocol icons are small static images — they don't need BlurHash, fallback
                    // letters, CircleAvatarCache, or scroll-awareness.
                    room.bridgeProtocolAvatarUrl?.let { protocolAvatarUrl ->
                        val badgeUrl = remember(protocolAvatarUrl) {
                            net.vrkknn.andromuks.utils.AvatarUtils.mxcToHttpUrl(protocolAvatarUrl, homeserverUrl)
                        }
                        if (badgeUrl != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(16.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        CircleShape
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                coil.compose.AsyncImage(
                                    model = coil.request.ImageRequest.Builder(context)
                                        .data(badgeUrl)
                                        .size(48) // Small icon, minimal decode
                                        .addHeader("Cookie", "gomuks_auth=$authToken")
                                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                        .build(),
                                    imageLoader = remember { net.vrkknn.andromuks.utils.ImageLoaderSingleton.get(context) },
                                    contentDescription = "Bridge protocol",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        
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

                    // Right side: (optionally) silenced icon and unread/highlight pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Silenced icon placed to the left of the unread badge when room is low priority
                        if (room.isLowPriority) {
                            Icon(
                                imageVector = Icons.Filled.NotificationsOff,
                                contentDescription = "Low Priority - Notifications Disabled",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

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
                            // PHASE 1: Animated badge count changes
                            if (room.highlightCount != null && room.highlightCount > 0) {
                                AnimatedContent(
                                    targetState = room.highlightCount,
                                    transitionSpec = {
                                        if (targetState > initialState) {
                                            // Count increased - slide up and fade in
                                            slideInVertically { height -> height } + fadeIn() togetherWith
                                            slideOutVertically { height -> -height } + fadeOut()
                                        } else {
                                            // Count decreased - slide down and fade out
                                            slideInVertically { height -> -height } + fadeIn() togetherWith
                                            slideOutVertically { height -> height } + fadeOut()
                                        }.using(
                                            SizeTransform(clip = false)
                                        )
                                    },
                                    label = "badge_count_highlight"
                                ) { count ->
                                    Text(
                                        text = count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError
                                    )
                                }
                            } else if (room.unreadCount != null && room.unreadCount > 0) {
                                AnimatedContent(
                                    targetState = room.unreadCount,
                                    transitionSpec = {
                                        if (targetState > initialState) {
                                            // Count increased - slide up and fade in
                                            slideInVertically { height -> height } + fadeIn() togetherWith
                                            slideOutVertically { height -> -height } + fadeOut()
                                        } else {
                                            // Count decreased - slide down and fade out
                                            slideInVertically { height -> -height } + fadeIn() togetherWith
                                            slideOutVertically { height -> height } + fadeOut()
                                        }.using(
                                            SizeTransform(clip = false)
                                        )
                                    },
                                    label = "badge_count_unread"
                                ) { count ->
                                    Text(
                                        text = count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            } else {
                                // Invisible placeholder to maintain consistent height
                                Text(
                                    text = "0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color.Transparent
                                )
                            }
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
                        // Sender name and message
                        Text(
                            text = "$displayNameToUse: ${room.messagePreview}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // Use pre-computed timestamp on the same line as the summary, to the right
                        if (timeAgo.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeAgo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

                        // Use pre-computed timestamp on the same line as the summary, to the right
                        if (timeAgo.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeAgo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

                        // Use pre-computed timestamp on the same line as the summary, to the right
                        if (timeAgo.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeAgo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    if (timestamp == null || timestamp <= 0L) return ""
    
    val now = System.currentTimeMillis()
    val eventDate = java.util.Date(timestamp)
    val today = java.util.Date(now)
    
    val eventCalendar = java.util.Calendar.getInstance()
    val todayCalendar = java.util.Calendar.getInstance()
    val yesterdayCalendar = java.util.Calendar.getInstance()
    
    eventCalendar.time = eventDate
    todayCalendar.time = today
    yesterdayCalendar.time = today
    yesterdayCalendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
    
    // Check if it's today
    val isToday = eventCalendar.get(java.util.Calendar.YEAR) == todayCalendar.get(java.util.Calendar.YEAR) &&
                  eventCalendar.get(java.util.Calendar.DAY_OF_YEAR) == todayCalendar.get(java.util.Calendar.DAY_OF_YEAR)
    
    // Check if it's yesterday
    val isYesterday = eventCalendar.get(java.util.Calendar.YEAR) == yesterdayCalendar.get(java.util.Calendar.YEAR) &&
                      eventCalendar.get(java.util.Calendar.DAY_OF_YEAR) == yesterdayCalendar.get(java.util.Calendar.DAY_OF_YEAR)
    
    return when {
        isToday -> {
            // Show time in hh:mm format for today
            val timeFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            timeFormatter.format(eventDate)
        }
        isYesterday -> "Yesterday"
        else -> {
            // Calculate days, weeks, or years ago
            val diff = now - timestamp
            val daysAgo = (diff / 86_400_000).toInt()
            val weeksAgo = daysAgo / 7
            val yearsAgo = daysAgo / 365
            
            when {
                yearsAgo > 0 -> "${yearsAgo}y ago"
                weeksAgo > 0 -> "${weeksAgo}w ago"
                else -> "${daysAgo}d ago"
            }
        }
    }
}

@Composable
fun TabBar(
    currentSection: RoomSection,
    onSectionSelected: (RoomSectionType) -> Unit,
    appViewModel: AppViewModel,
    isProcessingBatch: Boolean = false
) {
    val showAllRoomListTabs = appViewModel.showAllRoomListTabs

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
                },
                enabled = !isProcessingBatch
            )
            
            TabButton(
                icon = Icons.Filled.Place,
                label = "Spaces",
                isSelected = currentSection.type == RoomSectionType.SPACES,
                onClick = {
                    onSectionSelected(RoomSectionType.SPACES)
                },
                enabled = !isProcessingBatch
            )
            
            TabButton(
                icon = Icons.Filled.Person,
                label = "Direct",
                isSelected = currentSection.type == RoomSectionType.DIRECT_CHATS,
                onClick = {
                    onSectionSelected(RoomSectionType.DIRECT_CHATS)
                },
                badgeCount = appViewModel.getDirectChatsUnreadCount(),
                hasHighlights = appViewModel.hasDirectChatsHighlights(),
                enabled = !isProcessingBatch
            )
            
            TabButton(
                icon = Icons.Filled.Notifications,
                label = "Unread",
                isSelected = currentSection.type == RoomSectionType.UNREAD,
                onClick = {
                    onSectionSelected(RoomSectionType.UNREAD)
                },
                badgeCount = appViewModel.getUnreadCount(),
                enabled = !isProcessingBatch
            )

            if (showAllRoomListTabs) {
                TabButton(
                    icon = Icons.Filled.Favorite,
                    label = "Favs",
                    isSelected = currentSection.type == RoomSectionType.FAVOURITES,
                    onClick = {
                        onSectionSelected(RoomSectionType.FAVOURITES)
                    },
                    badgeCount = appViewModel.getFavouritesUnreadCount(),
                    hasHighlights = appViewModel.hasFavouritesHighlights(),
                    enabled = !isProcessingBatch
                )

                TabButton(
                    icon = Icons.Filled.Link,
                    label = "Bridges",
                    isSelected = currentSection.type == RoomSectionType.BRIDGES,
                    onClick = {
                        onSectionSelected(RoomSectionType.BRIDGES)
                    },
                    enabled = !isProcessingBatch
                )
            }
            
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
    hasHighlights: Boolean = false,
    enabled: Boolean = true
) {
    val content = @Composable {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    isSelected -> MaterialTheme.colorScheme.primary 
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    isSelected -> MaterialTheme.colorScheme.primary 
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
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
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
            ) {
                content()
            }
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RoomListContent(
    rooms: List<RoomItem>,
    searchQuery: String,
    appViewModel: AppViewModel,
    authToken: String,
    navController: NavController,
    timestampUpdateTrigger: Int,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    listState: LazyListState,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    onInviteClick: (RoomInvite) -> Unit
) {
    val context = LocalContext.current
    
    // PERFORMANCE: Detect fast scrolling to suspend avatar loading
    // During fast scrolling, images would be out of view before loading anyway
    // This prevents wasted decoding work and crashes from too many simultaneous loads
    var isScrollingFast by remember { mutableStateOf(false) }
    var lastScrollIndex by remember { mutableStateOf(0) }
    var lastScrollTime by remember { mutableStateOf(0L) }
    
    LaunchedEffect(listState.isScrollInProgress, listState.firstVisibleItemIndex) {
        val currentIndex = listState.firstVisibleItemIndex
        val currentTime = System.currentTimeMillis()
        val timeDelta = currentTime - lastScrollTime
        val indexDelta = kotlin.math.abs(currentIndex - lastScrollIndex)
        
        // Detect fast scrolling: >10tems in <100ms OR actively scrolling with >20tems/second
        val isCurrentlyScrolling = listState.isScrollInProgress
        val scrollSpeed = if (timeDelta > 0) indexDelta * 1000 / timeDelta else 0
        
        isScrollingFast = isCurrentlyScrolling && (scrollSpeed > 20) || (timeDelta < 100 && indexDelta > 10)
        
        lastScrollIndex = currentIndex
        lastScrollTime = currentTime
        
        // Reset fast scrolling flag after scrolling stops (with small delay to allow images to load)
        if (!isCurrentlyScrolling && isScrollingFast) {
            kotlinx.coroutines.delay(25) // Small delay to ensure scroll has stopped
            isScrollingFast = false
        }
    }
    
    // Handle Android back key when inside a space or bridge
    androidx.activity.compose.BackHandler(enabled = appViewModel.currentSpaceId != null || appViewModel.currentBridgeId != null) {
        if (appViewModel.currentSpaceId != null) {
            appViewModel.exitSpace()
        } else if (appViewModel.currentBridgeId != null) {
            appViewModel.exitBridge()
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
    
    // Debounced, meaningful-diff snapshot for the displayed rooms to reduce flicker.
    // We hash the fields that matter for rendering/sorting; unchanged hash => skip swap.
    // CRITICAL FIX: Include bridgeProtocolAvatarUrl in hash so UI updates when bridge badges arrive
    // CRITICAL FIX: Skip debounce when rooms go from empty to non-empty (entering a space) to make animation visible
    // CRITICAL FIX: Skip debounce when searchQuery changes to make search responsive
    var debouncedRooms by remember { mutableStateOf(filteredRooms) }
    var previousSearchQuery by remember { mutableStateOf(searchQuery) }
    // PERFORMANCE: Use a cheap structural hash instead of building a giant joinToString.
    // RoomItem is @Immutable data class, so its hashCode() is stable and fast.
    val targetHash = remember(filteredRooms) {
        var h = filteredRooms.size
        for (room in filteredRooms) {
            h = h * 31 + room.hashCode()
        }
        h
    }
    
    LaunchedEffect(targetHash, searchQuery) {
        val wasEmpty = debouncedRooms.isEmpty()
        val isNowPopulated = wasEmpty && filteredRooms.isNotEmpty()
        val searchQueryChanged = searchQuery != previousSearchQuery
        
        // Update previous search query
        previousSearchQuery = searchQuery
        
        if (isNowPopulated || searchQueryChanged) {
            // Rooms just populated (entering a space) OR search query changed - update immediately without debounce
            // This ensures the animation is visible when entering a space and search is responsive
            debouncedRooms = filteredRooms
        } else {
            // Normal case - debounce to coalesce rapid sync_complete updates
            kotlinx.coroutines.delay(1000L)
            debouncedRooms = filteredRooms
        }
    }
    
    // STICKY TOP: Whenever the 'top' room ID changes, snap to the top
    // Only snap if the user is already near the top (index <= 1)
    // This prevents "yanking" the user back if they are scrolling deep down
    val firstRoomId = remember(debouncedRooms) {
        debouncedRooms.firstOrNull()?.id
    }
    
    LaunchedEffect(firstRoomId) {
        if (listState.firstVisibleItemIndex <= 1) {
            // User is near the top - animate scroll to top when first room changes
            listState.animateScrollToItem(0)
        }
    }
    
    // NAVIGATION PERFORMANCE: Add scroll state for prefetching
    // NAVIGATION PERFORMANCE: Observe scroll state and trigger prefetching for visible rooms
    // CRITICAL FIX: Prefetch initially visible rooms when tab is first shown, not just on scroll
    LaunchedEffect(listState, filteredRooms) {
        val prefetchedIds = mutableSetOf<String>()
        
        // Prefetch initially visible rooms when tab is first shown or rooms change
        // This ensures rooms are prefetched even if user hasn't scrolled yet
        // Wait a bit for LazyColumn to layout before checking visible items
        kotlinx.coroutines.delay(100)
        
        val layoutInfo = listState.layoutInfo
        if (filteredRooms.isNotEmpty() && layoutInfo.visibleItemsInfo.isNotEmpty()) {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val visibleIndices = layoutInfo.visibleItemsInfo.map { it.index }
            
            val nearbyRangeStart = (firstVisibleIndex - 10).coerceAtLeast(0)
            val nearbyRangeEnd = (firstVisibleIndex + visibleIndices.size + 10).coerceAtMost(filteredRooms.size - 1)
            val initiallyVisibleRoomIds = (nearbyRangeStart..nearbyRangeEnd)
                .filter { it in filteredRooms.indices }
                .map { filteredRooms[it].id }
                .distinct()
            
            if (initiallyVisibleRoomIds.isNotEmpty()) {
                prefetchedIds.addAll(initiallyVisibleRoomIds)
                appViewModel.prefetchRoomData(initiallyVisibleRoomIds, firstVisibleIndex)
                if (ROOM_LIST_VERBOSE_LOGGING && BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "RoomListScreen: NAVIGATION OPTIMIZATION - Initial prefetch ${initiallyVisibleRoomIds.size} visible rooms (window ±10)"
                    )
                }
            }
        }
        
        // Also observe scroll changes to prefetch newly visible rooms
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            listState.firstVisibleItemIndex to layoutInfo.visibleItemsInfo.map { it.index }
        }
            .debounce(280)
            .collectLatest { (firstVisibleIndex, visibleIndices) ->
                if (filteredRooms.isEmpty()) return@collectLatest
                
                val nearbyRangeStart = (firstVisibleIndex - 10).coerceAtLeast(0)
                val nearbyRangeEnd = (firstVisibleIndex + visibleIndices.size + 10).coerceAtMost(filteredRooms.size - 1)
                val nearbyRoomIds = (nearbyRangeStart..nearbyRangeEnd)
                    .filter { it in filteredRooms.indices }
                    .map { filteredRooms[it].id }
                    .filter { it !in prefetchedIds }
                    .distinct()
                
                if (nearbyRoomIds.isNotEmpty()) {
                    prefetchedIds.addAll(nearbyRoomIds)
                    appViewModel.prefetchRoomData(nearbyRoomIds, firstVisibleIndex)
                    if (ROOM_LIST_VERBOSE_LOGGING && BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "RoomListScreen: NAVIGATION OPTIMIZATION - Prefetch ${nearbyRoomIds.size} rooms on scroll (window ±10, deduped)"
                        )
                    }
                }
            }
    }
    
    val coroutineScope = rememberCoroutineScope()
    var roomOpenInProgress by remember { mutableStateOf<String?>(null) }
    
    // PERFORMANCE: Hoist layoutInfo read outside items{} so it's computed once per frame,
    // not once per item during scroll.  derivedStateOf avoids redundant recompositions when
    // the value hasn't actually changed.
    val avatarLoadCutoff by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last + 25
        }
    }
    
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            bottom = 8.dp
        )
    ) {
        // Use the cached filteredRooms from the remember block above
        items(
            count = debouncedRooms.size,
            key = { index -> debouncedRooms[index].id }, // Stable key - room ID stays constant
            contentType = { "room" } // PERFORMANCE: Shared type enables composition recycling across items
        ) { index ->
            val room = debouncedRooms[index]
            // PERFORMANCE FIX: Removed AnimatedVisibility wrapper that caused animation overhead
            // The items() already handles insertions/deletions efficiently
            // CRITICAL FIX: Capture room.id OUTSIDE the lambda to prevent wrong room navigation
            val roomIdForNavigation = room.id
            
            // PERFORMANCE: Use hoisted cutoff instead of per-item layoutInfo read
            val shouldLoadAvatar = index <= avatarLoadCutoff
            
            Column(
                modifier = Modifier.animateItem()
            ) {
                RoomListItem(
                    room = room,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = authToken,
                    isScrollingFast = isScrollingFast, // PERFORMANCE: Pass fast scroll state
                    shouldLoadAvatar = shouldLoadAvatar, // PERFORMANCE: Load avatars for items below viewport
                    sharedTransitionScope = sharedTransitionScope, // SHARED TRANSITION: Pass scope for shared element animation
                    animatedVisibilityScope = animatedVisibilityScope, // SHARED TRANSITION: Pass scope for shared element animation
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
                                
                                // Navigate immediately; prefetch runs best-effort.
                                // NOTE: markRoomAsRead is handled by navigateToRoomWithCache, so we don't need to call it here
                                appViewModel.navigateToRoomWithCache(roomIdForNavigation)
                                navController.navigate("room_timeline/$roomIdForNavigation")

                                launch(Dispatchers.IO) {
                                    val prefetchSuccess = appViewModel.prefetchRoomSnapshot(roomIdForNavigation)
                                    if (!prefetchSuccess) {
                                        android.util.Log.w(
                                            "Andromuks",
                                            "RoomListScreen: Prefetch snapshot failed or timed out for $roomIdForNavigation, falling back to existing cache"
                                        )
                                    }
                                }
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
    navController: NavController,
    listState: LazyListState
) {
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "SpacesListContent: Displaying ${spaces.size} spaces")
    LazyColumn(
        state = listState,
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
fun BridgesListContent(
    bridges: List<SpaceItem>,
    searchQuery: String,
    appViewModel: AppViewModel,
    authToken: String,
    navController: NavController,
    listState: LazyListState
) {
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "BridgesListContent: Displaying ${bridges.size} bridges")
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            bottom = 8.dp
        )
    ) {
        val filteredBridges = if (searchQuery.isBlank()) {
            bridges
        } else {
            bridges.filter { bridge ->
                bridge.name.contains(searchQuery, ignoreCase = true)
            }
        }
        
        items(filteredBridges.size) { idx ->
            val bridge = filteredBridges[idx]
            SpaceListItem(
                space = bridge,
                isSelected = false,
                onClick = { 
                    appViewModel.enterBridge(bridge.id)
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
