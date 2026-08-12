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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

@Composable
fun AuthCheckScreen(navController: NavController, modifier: Modifier, appViewModel: AppViewModel) {
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
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true // Auto-granted on Android 12 and below
            }

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val hasBatteryOptimization = powerManager.isIgnoringBatteryOptimizations(context.packageName)

            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Permissions check - notifications: $hasNotificationPermission, battery: $hasBatteryOptimization")

            // Only require notification permission (battery exemption is optional since FCM can handle notifications)
            if (!hasNotificationPermission) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "AuthCheckScreen",
                        "Notification permission not granted, navigating to permissions screen",
                    )
                }
                appViewModel.isLoading = false
                navController.navigate("permissions") {
                    popUpTo("auth_check") { inclusive = true }
                }
                return@LaunchedEffect
            }

            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Notification permission granted. Attempting auto WebSocket connect.")

            // Set homeserver URL and auth token in ViewModel for avatar loading. These are
            // pure in-memory state writes and must happen before the navigation LaunchedEffect
            // runs to avoid avatar fallback flicker, so they stay inline here rather than waiting
            // on the hydration job below (which also sets them, idempotently).
            appViewModel.updateHomeserverUrl(homeserverUrl)
            appViewModel.updateAuthToken(token)

            // Disk hydration (state, profiles, settings, then roomMap/spaces) runs on
            // viewModelScope, NOT in this LaunchedEffect. It used to be inline here, behind a
            // ~100–300ms SQLite read; navigating away (a share intent pushing simple_room_list)
            // disposes auth_check and cancelled it mid-read, leaving roomMap empty. MainActivity
            // already kicked this off in onCreate — this call is the idempotent no-op that keeps
            // AuthCheck working if it ever composes first. spacesLoaded flipping is what drives
            // the navigation effect below, so nothing here needs to await it.
            appViewModel.ensureColdStartHydration(context)

            // Run FCM init, navigation-callback setup, and the WebSocket connection on the
            // ViewModel scope — NOT this LaunchedEffect. The hydration job above flips
            // `spacesLoaded`, which fires the cache-driven
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
                // Never redirects off an already-active room_timeline / chat_bubble / share picker.
                //
                // This used to take a forceIfOnTimeline flag that *did* redirect off a live timeline,
                // for the "normal app-icon open after a previous session left a room open" case. That
                // case cannot arise here — on a cold start the back stack begins at auth_check, so by
                // the time this callback runs at init_complete any timeline on the stack was opened
                // deliberately this session (AppNavigation's collector, RoomTimelineScreen's navTrigger,
                // or the share flow). Redirecting was therefore always wrong by the time it fired, and
                // needed openedViaDirectNotification / openedViaShare to suppress it — the "tap yanked
                // back to the list" symptom was that suppression failing. Removing the branch removes
                // the bug source and both flags.
                fun navigateToRoomListIfNeeded() {
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
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "AuthCheckScreen",
                            "navigateToRoomListIfNeeded called (currentRoute=$currentRoute)",
                        )
                    }

                    if (currentRoute != null) {
                        if (currentRoute == "room_list") {
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    "AuthCheckScreen",
                                    "Already on room_list, ensuring isLoading is false",
                                )
                            }
                            appViewModel.isLoading = false
                            return
                        }

                        if (currentRoute == "simple_room_list" ||
                            currentRoute.startsWith("room_timeline/") ||
                            currentRoute.startsWith("chat_bubble/")
                        ) {
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    "AuthCheckScreen",
                                    "Skipping navigation to room_list because currentRoute=$currentRoute",
                                )
                            }
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
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AuthCheck: Navigation callback - directRoomId: ${appViewModel.getDirectRoomNavigation()}, pendingRoomId: ${appViewModel.getPendingRoomNavigation()}",
                        )
                    }
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
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "AuthCheck: Pending share needs room selection, navigating to simple_room_list",
                                )
                            }
                            navController.navigate("simple_room_list") { launchSingleTop = true }
                            appViewModel.markPendingShareNavigationHandled()
                        } else {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "AuthCheck: Pending share in progress (navigation already handled) — skipping redirect",
                                )
                            }
                        }
                        return@setNavigationCallback
                    }

                    // Direct room navigation (from notifications) is NOT handled here. AppNavigation's
                    // roomNavigationRequests collector owns every external room open — it polls for
                    // readiness (or takes the cache-first fast path when the room is already in
                    // roomMap), then claims directRoomNavigation atomically and calls
                    // executeRoomNavigation. AuthCheck used to navigate here as well, which is what
                    // made two navigators race for one tap; all this callback has to do is stay out
                    // of the way and not force-redirect to room_list while a target is still pending.
                    // (navigateToRoomListIfNeeded's getDirectRoomNavigation() != null guard does that.)
                    val directRoomId = appViewModel.getDirectRoomNavigation()
                    if (directRoomId != null) {
                        Androlog(
                            "FCMOpen",
                            "AuthCheck post-init callback: direct room=$directRoomId pending — deferring to AppNavigation collector",
                        )
                        appViewModel.isLoading = false
                        return@setNavigationCallback
                    }

                    // Check for bubble navigation (from ChatBubbleActivity)
                    val pendingBubbleId = appViewModel.getPendingBubbleNavigation()
                    if (pendingBubbleId != null) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "AuthCheck: Navigating to pending bubble: $pendingBubbleId",
                            )
                        }
                        appViewModel.clearPendingBubbleNavigation()
                        navController.navigate("chat_bubble/$pendingBubbleId")
                        return@setNavigationCallback
                    }

                    // Shortcut navigation: navigate to room_list; the channel consumer in
                    // RoomListScreen handles the actual executeRoomNavigation once it's active.
                    // Toast if the room clearly doesn't exist (may be a stale shortcut).
                    val pendingRoomId = appViewModel.getPendingRoomNavigation()
                    if (pendingRoomId != null && appViewModel.getRoomById(pendingRoomId) == null) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "AuthCheck: Shortcut room $pendingRoomId not found in room list",
                            )
                        }
                        appViewModel.clearPendingRoomNavigation()
                        android.widget.Toast.makeText(
                            context,
                            "Room $pendingRoomId not found. Please try again later.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }

                    // Check for pending user info navigation (from matrix:u/ URIs)
                    val pendingUserId = appViewModel.getPendingUserInfoNavigation()
                    if (pendingUserId != null) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "AuthCheck: Navigating to user info for: $pendingUserId",
                            )
                        }
                        appViewModel.clearPendingUserInfoNavigation()
                        val encodedUserId = java.net.URLEncoder.encode(pendingUserId, "UTF-8")
                        navController.navigate("user_info/$encodedUserId") {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                        return@setNavigationCallback
                    }

                    navigateToRoomListIfNeeded()
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

                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AuthCheckScreen: WebSocket connection check - isPrimary: $isPrimary, isAlreadyConnected: $isAlreadyConnected",
                    )
                }

                if (isAlreadyConnected) {
                    // WebSocket is already connected (from primary AppViewModel instance), just attach to it
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AuthCheckScreen: WebSocket already connected, attaching to existing connection",
                        )
                    }
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
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AuthCheckScreen: Primary instance - delegating WebSocket connection to AppViewModel (survives activity recreation)",
                        )
                    }
                    appViewModel.initializeWebSocketConnection(homeserverUrl, token)
                } else {
                    // Non-primary instance and no connection exists - wait for primary to connect
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AuthCheckScreen: Non-primary instance - waiting for primary instance to establish WebSocket connection",
                        )
                    }

                    // Wait for primary instance to connect (with timeout)
                    var waitCount = 0
                    val maxWaitAttempts = 50 // Wait up to 5 seconds (50 * 100ms) - shorter timeout for better UX
                    while (!WebSocketService.isWebSocketConnected() && waitCount < maxWaitAttempts) {
                        kotlinx.coroutines.delay(100)
                        waitCount++
                    }

                    if (WebSocketService.isWebSocketConnected()) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "AuthCheckScreen: Primary instance connected, attaching to WebSocket",
                            )
                        }
                        appViewModel.attachToExistingWebSocketIfAvailable()
                        appViewModel.isLoading = false
                        appViewModel.registerFCMNotifications()
                        // Navigation is handled by the sentinel callback in populateFromCacheAndNavigateAfterAttach.
                    } else {
                        // FALLBACK: If no primary instance exists (app was closed) and no connection exists,
                        // allow this non-primary instance to create the connection
                        // This is a fallback scenario when opening via notification/shortcut with app closed
                        android.util.Log.w(
                            "Andromuks",
                            "AuthCheckScreen: Primary instance did not connect within timeout - using fallback: non-primary will create connection",
                        )
                        // REFACTORING: Delegate connection to service (service handles backend health check)
                        WebSocketService.connectWebSocket(
                            homeserverUrl,
                            token,
                            appViewModel,
                            trigger = ReconnectTrigger.Unclassified("AuthCheck fallback connection"),
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

            // Pinned shortcut / conversation widget / FCM: MainActivity stored a direct room target.
            // AuthCheck does NOT open it — AppNavigation's roomNavigationRequests collector owns
            // every external room open, including the CACHE-FIRST path this block used to perform
            // (roomMap hit ⇒ paint the header immediately without waiting for the socket; see
            // docs/AUTHCHECK.md). Two navigators for one tap was the race. All we do here is bail so
            // we don't send the user to room_list while the collector is still working; the target
            // stays in directRoomNavigation until the collector claims it atomically.
            val directRoomId = appViewModel.getDirectRoomNavigation()
            if (directRoomId != null) {
                Androlog(
                    "FCMOpen",
                    "AuthCheck spacesLoaded: direct room=$directRoomId pending — deferring to AppNavigation collector",
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "AuthCheckScreen",
                        "Direct room target set ($directRoomId) — AppNavigation collector owns the open, skipping room_list",
                    )
                }
                appViewModel.isLoading = false
                return@LaunchedEffect
            }

            // Either WebSocket is connected, or we are offline — in both cases it’s safe to
            // proceed to room_list using cached data.
            if (BuildConfig.DEBUG) {
                Log.d(
                    "AuthCheckScreen",
                    "Spaces loaded from cache - navigating to room_list (isWebSocketConnected=$isWebSocketConnected, network=$currentNetworkType)",
                )
            }
            appViewModel.isLoading = false
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != null && currentRoute != "room_list" &&
                currentRoute != "simple_room_list" &&
                !currentRoute.startsWith("room_timeline/") &&
                !currentRoute.startsWith("chat_bubble/")
            ) {
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
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "AuthCheckScreen",
                            "Spaces loaded during timeout delay - navigating to room_list",
                        )
                    }
                } else {
                    // Spaces not loaded but timeout expired - navigate anyway
                    android.util.Log.w(
                        "AuthCheckScreen",
                        "Navigation timeout (10s) - WebSocket may not be connected, navigating anyway",
                    )
                }
                // A still-pending directRoomNavigation here means the notification target
                // was never opened (uncached room + WebSocket/init that never completed
                // within 10s): this timeout fallback is about to strand the user on
                // room_list instead of the room they tapped. Record the full state so the
                // "tap landed on the list" failure is diagnosable from the Androlog.
                val pendingDirect = appViewModel.getDirectRoomNavigation()
                if (pendingDirect != null) {
                    Androlog(
                        "FCMOpen",
                        "AuthCheck TIMEOUT (10s) with directRoomNavigation STILL PENDING room=$pendingDirect — stranding on room_list. " +
                            "spacesLoaded=${appViewModel.spacesLoaded} wsConn=${WebSocketService.isWebSocketConnected()} " +
                            "stuck=${WebSocketService.isConnectionStuck()} cached=${appViewModel.getRoomById(
                                pendingDirect,
                            ) != null}",
                    )
                }
                appViewModel.isLoading = false
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute != null && currentRoute != "simple_room_list" &&
                    !currentRoute.startsWith("room_timeline/") &&
                    !currentRoute.startsWith("chat_bubble/")
                ) {
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
