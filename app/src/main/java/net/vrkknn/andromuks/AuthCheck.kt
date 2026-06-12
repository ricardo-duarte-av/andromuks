package net.vrkknn.andromuks

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.BuildConfig

@Composable
fun AuthCheckScreen(
    navController: NavController,
    modifier: Modifier,
    appViewModel: AppViewModel,
) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }

    // CRITICAL FIX: Track navigation state to prevent duplicate navigation
    var navigationHandled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appViewModel.isLoading = true
        val token = net.vrkknn.andromuks.utils.CredentialStore.getAuthToken(sharedPreferences).ifBlank { null }
        val homeserverUrl = sharedPreferences.getString("homeserver_url", null)

        if (token != null && homeserverUrl != null) {
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Token and server URL found.")
            
            // Check if permissions are granted
            val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true // Auto-granted on Android 12 and below
            }
            
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val hasBatteryOptimization = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Permissions check - notifications: $hasNotificationPermission, battery: $hasBatteryOptimization")
            
            // Only require notification permission (battery exemption is optional since FCM can handle notifications)
            if (!hasNotificationPermission) {
                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Notification permission not granted, navigating to permissions screen")
                appViewModel.isLoading = false
                navController.navigate("permissions") {
                    popUpTo("auth_check") { inclusive = true }
                }
                return@LaunchedEffect
            }
            
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Notification permission granted. Attempting auto WebSocket connect.")
            
            appViewModel.loadStateFromStorage(context)

            // Hydrate disk caches off the main thread — these open SQLite and read row sets
            // that can easily take 100–300ms and would otherwise block Compose from painting
            // the cached room list.
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                appViewModel.loadCachedProfiles(context)
            }

            // populateRoomMapFromCache MUST run on Main: it mutates roomMap and flips the
            // Compose-observable spacesLoaded state. Cheap — just an in-memory copy.
            appViewModel.populateRoomMapFromCache()
            appViewModel.populateSpacesFromCache()

            // Set homeserver URL and auth token in ViewModel for avatar loading. These are
            // pure in-memory state writes and must happen before the navigation LaunchedEffect
            // runs to avoid avatar fallback flicker.
            appViewModel.updateHomeserverUrl(homeserverUrl)
            appViewModel.updateAuthToken(token)

            // Run FCM init, navigation-callback setup, and the WebSocket connection on the
            // ViewModel scope — NOT this LaunchedEffect. populateRoomMapFromCache /
            // populateSpacesFromCache above flip `spacesLoaded`, which fires the cache-driven
            // navigation LaunchedEffect below; that pops auth_check (popUpTo inclusive) and
            // disposes this composable, cancelling this LaunchedEffect at its first suspension
            // point (the frame delay / FCM IO). If the connection were initiated inline here it
            // would be cancelled before initializeWebSocketConnection ever runs, leaving the app
            // stuck on RoomListScreen with the red "disconnected" icon and no foreground service
            // armed to reconnect (observed after a battery-saver teardown / cold start). The
            // viewModelScope job outlives the composable, so the connection always completes.
            // The body below is intentionally left at its original indentation to keep the diff
            // reviewable.
            appViewModel.viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // Wait for at least one Compose frame to paint before kicking off heavy work.
            // 32ms = ~2 frames at 60Hz, enough slack even on slow devices.
            kotlinx.coroutines.delay(32)

            // Initialize FCM after the first paint — it does disk + network work that we
            // don't want competing with the room list's first frame.
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                appViewModel.initializeFCM(context, homeserverUrl, token)
            }
            // forceIfOnTimeline=true: navigate to room_list even when the back stack already has
            // room_timeline (normal app-icon open after a previous session left a room open).
            // forceIfOnTimeline=false: skip navigation when room_timeline/chat_bubble is active
            // (shortcut cold-start — the channel consumer handles the actual room navigation).
            fun navigateToRoomListIfNeeded(forceIfOnTimeline: Boolean = false) {
                // Don't redirect while a share-to-room flow is active. The user is on
                // simple_room_list picking a destination; force-navigating to room_list would
                // discard their in-progress share.
                if (appViewModel.pendingShare != null) {
                    appViewModel.isLoading = false
                    return
                }
                if (appViewModel.getDirectRoomNavigation() != null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "AuthCheckScreen",
                            "navigateToRoomListIfNeeded skipped: direct room navigation will open room_timeline from WebSocket callback",
                        )
                    }
                    return
                }
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "navigateToRoomListIfNeeded called (forceIfOnTimeline=$forceIfOnTimeline, currentRoute=$currentRoute)")

                if (currentRoute != null) {
                    if (currentRoute == "room_list") {
                        if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Already on room_list, ensuring isLoading is false")
                        appViewModel.isLoading = false
                        return
                    }

                    if (forceIfOnTimeline && (currentRoute.startsWith("room_timeline/") || currentRoute.startsWith("chat_bubble/"))) {
                        // Do NOT force-navigate if the user arrived here via a direct notification tap.
                        // AuthCheck already consumed directRoomNavigation and navigated to room_timeline —
                        // navigating back to room_list would undo that work.
                        if (appViewModel.openedViaDirectNotification) {
                            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Skipping force navigation — user arrived via direct notification tap")
                            appViewModel.isLoading = false
                            return
                        }
                        if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Force navigating to room_list - clearing previous navigation stack (currentRoute=$currentRoute)")
                        appViewModel.isLoading = false
                        navController.navigate("room_list") {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                        return
                    }

                    if (!forceIfOnTimeline && (currentRoute == "simple_room_list" ||
                        currentRoute.startsWith("room_timeline/") ||
                        currentRoute.startsWith("chat_bubble/")
                    )) {
                        if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Skipping navigation to room_list because currentRoute=$currentRoute")
                        appViewModel.isLoading = false
                        return
                    }
                }

                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Navigating to room_list (currentRoute=$currentRoute)")
                appViewModel.isLoading = false
                // Remove auth_check from the back stack so RoomListScreen's subsequent
                // popBackStack("auth_check", inclusive=true) is a safe no-op. The exitTransition
                // (fadeOut 600ms) keeps auth_check in composition long enough for the shared-element
                // flight to complete even after it is popped.
                navController.navigate("room_list") {
                    popUpTo("auth_check") { inclusive = true }
                }
            }
            
            // Set up navigation callback BEFORE connecting websocket
            appViewModel.setNavigationCallback {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigation callback triggered")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigation callback - directRoomId: ${appViewModel.getDirectRoomNavigation()}, pendingRoomId: ${appViewModel.getPendingRoomNavigation()}")
                appViewModel.isLoading = false
                // Register FCM notifications after successful auth
                appViewModel.registerFCMNotifications()
                
                // When user shared media (single or multiple) without picking a room, go to room picker first.
                // This must run before direct room so multi-file share doesn't land on RoomTimelineScreen and crash.
                // Always return early when pendingShare != null — MainActivity's LaunchedEffect may have already
                // consumed pendingShareNavigationRequested (set it to false) before this callback fires, so we
                // cannot rely on pendingShareNavigationRequested being true here. Returning unconditionally prevents
                // the callback from falling through to navigateToRoomListIfNeeded and redirecting away from
                // simple_room_list while the user is picking a room to share to.
                if (appViewModel.pendingShare != null) {
                    if (appViewModel.pendingShareNavigationRequested) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Pending share needs room selection, navigating to simple_room_list")
                        navController.navigate("simple_room_list") { launchSingleTop = true }
                        appViewModel.markPendingShareNavigationHandled()
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Pending share in progress (navigation already handled) — skipping redirect")
                    }
                    return@setNavigationCallback
                }
                
                // Check for direct room navigation first (from notifications)
                //
                // NOTE: on a cache hit the spaces-loaded LaunchedEffect below has already opened
                // room_timeline cache-first (header paints during the init parse) and cleared
                // directRoomNavigation, so this block runs ONLY for the cache-miss fallback —
                // a room not yet in the hydrated roomMap (e.g. a brand-new room from the push),
                // where we must wait for sync data before we can render a real header.
                val directRoomId = appViewModel.getDirectRoomNavigation()
                if (directRoomId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Direct room navigation detected (notification), navigating directly to room_timeline: $directRoomId")
                    // Navigate directly to room timeline (like ShortcutActivity does)
                    // This bypasses RoomListScreen to avoid delays and missing rooms
                    val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                    val encodedRoomId = java.net.URLEncoder.encode(directRoomId, "UTF-8")

                    // Idempotency guard: if the cache-first effect already navigated to this room's
                    // timeline, don't re-navigate (would push a duplicate entry / reload). Just
                    // settle flags and bail. Defence-in-depth for the race where this callback
                    // fires before the cache-first effect clears directRoomNavigation.
                    val routeNow = navController.currentBackStackEntry?.destination?.route
                    if (routeNow == "room_timeline/$encodedRoomId") {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: room_timeline/$directRoomId already open (cache-first) — skipping re-navigation")
                        appViewModel.openedViaDirectNotification = true
                        appViewModel.isLoading = false
                        appViewModel.clearDirectRoomNavigation()
                        return@setNavigationCallback
                    }

                    // Set current room ID and navigate to room with cache
                    appViewModel.setCurrentRoomIdForTimeline(directRoomId)
                    if (notificationTimestamp != null) {
                        appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                    } else {
                        appViewModel.navigateToRoomWithCache(directRoomId)
                    }

                    // WAIT for room data readiness BEFORE navigating.
                    // The callback is a plain lambda so we launch on the ViewModel's scope,
                    // which is always available and tied to the ViewModel's lifecycle.
                    //
                    // IMPORTANT: do NOT clear directRoomNavigation here. The spaces-loaded
                    // LaunchedEffect below races this coroutine and uses directRoomNavigation
                    // as its "don't navigate to room_list" gate — clearing early lets it fire
                    // a stray navigate("room_list"), which then mounts a second RoomTimelineScreen
                    // whose dispose wipes the cache. We clear it right before navController.navigate.
                    appViewModel.viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        val isReady = appViewModel.awaitRoomDataReadiness(
                            timeoutMs = 15_000L,
                            roomId = directRoomId,
                        )
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Readiness check completed (isReady=$isReady) for $directRoomId")

                        // Synthesize a [room_list, room_timeline] back stack so Back returns to the
                        // room list. An FCM tap can come from anywhere (not just the launcher), so the
                        // room list is the natural parent — unlike ShortcutActivity, which exits to the
                        // launcher. auth_check is removed; room_list becomes the single base, then the
                        // timeline on top (room_list is not composed while covered).
                        appViewModel.clearDirectRoomNavigation()
                        navController.navigate("room_list") {
                            popUpTo("auth_check") { inclusive = true }
                        }
                        // launchSingleTop: defence-in-depth against a duplicate room_timeline entry
                        // if the cache-first effect raced us onto the same room (see bug #10).
                        navController.navigate("room_timeline/$encodedRoomId") { launchSingleTop = true }
                        appViewModel.openedViaDirectNotification = true
                    }
                    return@setNavigationCallback
                }
                
                // Check for bubble navigation (from ChatBubbleActivity)
                val pendingBubbleId = appViewModel.getPendingBubbleNavigation()
                if (pendingBubbleId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigating to pending bubble: $pendingBubbleId")
                    appViewModel.clearPendingBubbleNavigation()
                    navController.navigate("chat_bubble/$pendingBubbleId")
                    return@setNavigationCallback
                }

                // Shortcut navigation: navigate to room_list; the channel consumer in
                // RoomListScreen handles the actual executeRoomNavigation once it's active.
                // Toast if the room clearly doesn't exist (may be a stale shortcut).
                val pendingRoomId = appViewModel.getPendingRoomNavigation()
                if (pendingRoomId != null && appViewModel.getRoomById(pendingRoomId) == null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Shortcut room $pendingRoomId not found in room list")
                    appViewModel.clearPendingRoomNavigation()
                    android.widget.Toast.makeText(context, "Room $pendingRoomId not found. Please try again later.", android.widget.Toast.LENGTH_LONG).show()
                }

                // Check for pending user info navigation (from matrix:u/ URIs)
                val pendingUserId = appViewModel.getPendingUserInfoNavigation()
                if (pendingUserId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigating to user info for: $pendingUserId")
                    appViewModel.clearPendingUserInfoNavigation()
                    val encodedUserId = java.net.URLEncoder.encode(pendingUserId, "UTF-8")
                    navController.navigate("user_info/$encodedUserId") {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                    return@setNavigationCallback
                }

                navigateToRoomListIfNeeded(forceIfOnTimeline = true)
            }
            if (BuildConfig.DEBUG) Log.d("Andromuks", "AuthCheckScreen: appViewModel instance: $appViewModel")

            val isAlreadyConnected = WebSocketService.isWebSocketConnected()
            val directForFastPath = appViewModel.getDirectRoomNavigation()
            if (directForFastPath != null && isAlreadyConnected) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "AuthCheckScreen",
                        "Fast path: WebSocket already up — skipping verbose startup checklist (deep link / shortcut)",
                    )
                }
                appViewModel.clearStartupProgressMessages()
                appViewModel.attachToExistingWebSocketIfAvailable()
                appViewModel.isLoading = false
                appViewModel.registerFCMNotifications()
                // Navigation is handled by the sentinel callback in populateFromCacheAndNavigateAfterAttach,
                // which fires the navigation callback set above. The callback checks directRoomNavigation
                // and routes accordingly, so no direct navigation is needed here.
                return@launch
            }

            // Verbose cold-start checklist (WebSocket not connected yet, or no deep link while connected)
            appViewModel.addStartupProgressMessage("Starting...")
            appViewModel.addStartupProgressMessage("Checking stored auth....")
            if (!isAlreadyConnected) {
                appViewModel.addStartupProgressMessage("Connecting to WebSocket...")
            } else {
                appViewModel.addStartupProgressMessage("Attaching to existing WebSocket...")
            }

            // CRITICAL FIX: Only primary AppViewModel instance should create WebSocket connections
            // Non-primary instances should attach to existing connection or wait for primary to connect
            val isPrimary = appViewModel.isPrimaryInstance()
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket connection check - isPrimary: $isPrimary, isAlreadyConnected: $isAlreadyConnected")
            
            if (isAlreadyConnected) {
                // WebSocket is already connected (from primary AppViewModel instance), just attach to it
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket already connected, attaching to existing connection")
                appViewModel.attachToExistingWebSocketIfAvailable()
                appViewModel.isLoading = false
                appViewModel.registerFCMNotifications()
                // Navigation is handled by the sentinel callback in populateFromCacheAndNavigateAfterAttach,
                // which fires the navigation callback set above. Navigating directly here races with the
                // sentinel and causes rooms to pop in one-by-one as buffered sync_completes are processed.
                // Don't call connectToWebsocket - we're already connected
            } else if (isPrimary) {
                // This is the primary instance and no connection exists - create the connection
                // The Foreground service will maintain this connection
                // PHASE 1.4 FIX: Use AppViewModel's initializeWebSocketConnection() which uses viewModelScope
                // This ensures the connection attempt survives activity recreation
                // All setup (service start, reconnection params, health check, connection) is handled there
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Primary instance - delegating WebSocket connection to AppViewModel (survives activity recreation)")
                appViewModel.initializeWebSocketConnection(homeserverUrl, token)
            } else {
                // Non-primary instance and no connection exists - wait for primary to connect
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Non-primary instance - waiting for primary instance to establish WebSocket connection")
                
                // Wait for primary instance to connect (with timeout)
                var waitCount = 0
                val maxWaitAttempts = 50 // Wait up to 5 seconds (50 * 100ms) - shorter timeout for better UX
                while (!WebSocketService.isWebSocketConnected() && waitCount < maxWaitAttempts) {
                    kotlinx.coroutines.delay(100)
                    waitCount++
                }
                
                if (WebSocketService.isWebSocketConnected()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Primary instance connected, attaching to WebSocket")
                    appViewModel.attachToExistingWebSocketIfAvailable()
                    appViewModel.isLoading = false
                    appViewModel.registerFCMNotifications()
                    // Navigation is handled by the sentinel callback in populateFromCacheAndNavigateAfterAttach.
                } else {
                    // FALLBACK: If no primary instance exists (app was closed) and no connection exists,
                    // allow this non-primary instance to create the connection
                    // This is a fallback scenario when opening via notification/shortcut with app closed
                    android.util.Log.w("Andromuks", "AuthCheckScreen: Primary instance did not connect within timeout - using fallback: non-primary will create connection")
                    // REFACTORING: Delegate connection to service (service handles backend health check)
                    WebSocketService.connectWebSocket(
                        homeserverUrl,
                        token,
                        appViewModel,
                        trigger = ReconnectTrigger.Unclassified("AuthCheck fallback connection")
                    )
                }
            }
            } // end appViewModel.viewModelScope.launch — connection work decoupled from auth_check lifecycle
        } else {
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "No token or server URL found. Going to login.")
            appViewModel.isLoading = false
            navController.navigate("login")
        }
    }
    
    // CRITICAL FIX: Add fallback navigation if spacesLoaded becomes true from cache
    // or after timeout, even if WebSocket never connects (e.g., airplane mode)
    // This prevents infinite spinner when WebSocket can't connect
    // Only apply this if we have token and homeserver (i.e., not on login screen)
    val hasCredentials = remember {
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val token = net.vrkknn.andromuks.utils.CredentialStore.getAuthToken(prefs).ifBlank { null }
        val homeserverUrl = prefs.getString("homeserver_url", null)
        token != null && homeserverUrl != null
    }
    
    LaunchedEffect(appViewModel.spacesLoaded, appViewModel.isStartupComplete, hasCredentials) {
        if (hasCredentials && appViewModel.spacesLoaded && !navigationHandled) {
            // Fast-path navigation from cache: as soon as we have spaces from persisted state,
            // jump to room_list — we don't wait for the WebSocket or for isStartupComplete.
            // The user sees their cached room list immediately. The init payload arriving later
            // updates the list in place (RoomListScreen guards stableSection until
            // initialSyncProcessingComplete=true to avoid live re-sort flicker).
            val isWebSocketConnected = WebSocketService.isWebSocketConnected()
            val currentNetworkType = WebSocketService.getCurrentNetworkType()

            // Pinned shortcut / conversation widget / FCM: MainActivity stores direct room navigation.
            // Open the target room's timeline CACHE-FIRST — paint its header (name/avatar/bridge
            // from the hydrated roomMap + BridgeInfoCache) immediately, with a "Room loading..."
            // body, instead of holding on the startup overlay until the WebSocket finishes the init
            // parse. The body fills via RoomTimelineScreen's own load (empty cache -> requestRoomTimeline
            // -> WS-down defer into roomsAwaitingInitCompletePaginate -> fired on onInitComplete).
            val directRoomId = appViewModel.getDirectRoomNavigation()
            if (directRoomId != null) {
                // Only open cache-first when we can render a real header. On a cache miss (room not
                // yet in the hydrated roomMap — e.g. a brand-new room from the push) fall back to the
                // post-init WebSocket callback, which has full sync data. Leave directRoomNavigation
                // set in that case so the callback runs its direct block.
                if (appViewModel.getRoomById(directRoomId) != null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "AuthCheckScreen",
                            "Direct room target set + cached — opening room_timeline cache-first: $directRoomId",
                        )
                    }
                    val ts = appViewModel.getDirectRoomNavigationTimestamp()
                    val encodedRoomId = java.net.URLEncoder.encode(directRoomId, "UTF-8")
                    // Set currentRoomId BEFORE navigate: the onInitComplete deferred-paginate drain
                    // only re-fires when currentRoomId == roomId (otherwise it bails, leaving
                    // isTimelineLoading stuck). Kick off the load via navigateToRoomWithCache.
                    appViewModel.setCurrentRoomIdForTimeline(directRoomId)
                    if (ts != null) {
                        appViewModel.navigateToRoomWithCache(directRoomId, ts)
                    } else {
                        appViewModel.navigateToRoomWithCache(directRoomId)
                    }
                    // openedViaDirectNotification: makes navigateToRoomListIfNeeded bail instead of
                    // redirecting back to room_list (bug #7).
                    appViewModel.openedViaDirectNotification = true
                    appViewModel.isLoading = false
                    // Clear right before navigate (never earlier — see the post-init callback's note
                    // on the stray-room_list race). Clearing here also makes the later post-init
                    // callback see null and skip its own direct block, so it won't double-navigate.
                    appViewModel.clearDirectRoomNavigation()
                    // Synthesize [room_list, room_timeline] so Back returns to the room list.
                    navController.navigate("room_list") {
                        popUpTo("auth_check") { inclusive = true }
                    }
                    navController.navigate("room_timeline/$encodedRoomId") { launchSingleTop = true }
                    navigationHandled = true
                } else if (BuildConfig.DEBUG) {
                    Log.d(
                        "AuthCheckScreen",
                        "Direct room target set but not cached — deferring to WebSocket callback: $directRoomId",
                    )
                }
                return@LaunchedEffect
            }
            
            // Either WebSocket is connected, or we are offline — in both cases it’s safe to
            // proceed to room_list using cached data.
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Spaces loaded from cache - navigating to room_list (isWebSocketConnected=$isWebSocketConnected, network=$currentNetworkType)")
            appViewModel.isLoading = false
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != null && currentRoute != "room_list" &&
                currentRoute != "simple_room_list" &&
                !currentRoute.startsWith("room_timeline/") &&
                !currentRoute.startsWith("chat_bubble/")) {
                navController.navigate("room_list") {
                    popUpTo("auth_check") { inclusive = true }
                }
                navigationHandled = true
            } else if (currentRoute == "room_list") {
                // Navigation callback already navigated here; just mark handled.
                navigationHandled = true
            }
        }
    }

    // Timeout fallback: Navigate after 10 seconds even if WebSocket never connects
    LaunchedEffect(hasCredentials) {
        if (hasCredentials) {
            kotlinx.coroutines.delay(10000) // 10 second timeout
            if (!navigationHandled) {
                if (appViewModel.spacesLoaded) {
                    // Spaces loaded during timeout delay - navigate now
                    if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Spaces loaded during timeout delay - navigating to room_list")
                } else {
                    // Spaces not loaded but timeout expired - navigate anyway
                    android.util.Log.w("AuthCheckScreen", "Navigation timeout (10s) - WebSocket may not be connected, navigating anyway")
                }
                appViewModel.isLoading = false
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute != null && currentRoute != "simple_room_list" && 
                    !currentRoute.startsWith("room_timeline/") && 
                    !currentRoute.startsWith("chat_bubble/")) {
                    kotlinx.coroutines.delay(16) // One frame to ensure state update is visible
                    navController.navigate("room_list")
                }
                navigationHandled = true
            }
        }
    }

    AndromuksTheme {
        // AuthCheck is a logic-only screen: MainActivity owns the visible loading overlay,
        // so AuthCheck renders a blank Box and navigates to room_list as soon as the cache
        // has populated.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}