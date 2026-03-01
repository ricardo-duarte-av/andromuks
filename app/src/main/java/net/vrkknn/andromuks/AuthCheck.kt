package net.vrkknn.andromuks

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import net.vrkknn.andromuks.ui.components.StartupLoadingScreen
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.connectToWebsocket
import net.vrkknn.andromuks.utils.waitForBackendHealth
import net.vrkknn.andromuks.BuildConfig

import okhttp3.OkHttpClient
import androidx.compose.ui.Modifier
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicatorDefaults

private const val STARTUP_SHARED_AVATAR_KEY = "startup-current-user-avatar"

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun rememberMorphingStartupAvatarMaskModifier(): Modifier {
    val shapes = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
    if (shapes.size < 2) return Modifier

    val infiniteTransition = rememberInfiniteTransition(label = "startup_avatar_morph_transition")
    val morphCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "startup_avatar_morph_cycle"
    )
    val shapeRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "startup_avatar_mask_rotation"
    )

    val segmentCount = shapes.size
    val scaled = morphCycle * segmentCount
    val fromIndex = kotlin.math.floor(scaled).toInt().mod(segmentCount)
    val toIndex = (fromIndex + 1).mod(segmentCount)
    val localProgress = scaled - kotlin.math.floor(scaled).toFloat()
    val morph = remember(fromIndex, toIndex) { Morph(shapes[fromIndex], shapes[toIndex]) }
    val rawPath = remember(morph, localProgress) {
        morph.toPath(progress = localProgress).asComposePath()
    }

    return Modifier.drawWithContent {
        val bounds = rawPath.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val scale = kotlin.math.min(size.width / bounds.width, size.height / bounds.height)
        val dx = (size.width - bounds.width * scale) / 2f - bounds.left * scale
        val dy = (size.height - bounds.height * scale) / 2f - bounds.top * scale

        val transformedPath = Path().apply {
            addPath(rawPath)
            transform(
                Matrix().apply {
                    translate(dx, dy)
                    scale(scale, scale)
                }
            )
            val cx = size.width / 2f
            val cy = size.height / 2f
            transform(
                Matrix().apply {
                    translate(cx, cy)
                    rotateZ(shapeRotation)
                    translate(-cx, -cy)
                }
            )
        }

        clipPath(transformedPath) {
            this@drawWithContent.drawContent()
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AuthCheckScreen(
    navController: NavController,
    modifier: Modifier,
    appViewModel: AppViewModel,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val client = remember { OkHttpClient.Builder().build() }
    val scope = rememberCoroutineScope()
    val storedAuthToken = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val storedHomeserverUrl = remember(sharedPreferences) { sharedPreferences.getString("homeserver_url", "") ?: "" }
    val candidateStartupAvatarMxc = remember(
        appViewModel.currentUserId,
        appViewModel.currentUserProfile?.avatarUrl
    ) {
        val fromCurrentProfile = appViewModel.currentUserProfile?.avatarUrl?.takeIf { it.isNotBlank() }
        val fromGlobalCache = appViewModel.currentUserId
            .takeIf { it.isNotBlank() }
            ?.let { appViewModel.getUserProfile(it, null)?.avatarUrl }
            ?.takeIf { it.isNotBlank() }
        val fromPrefs = sharedPreferences.getString("current_user_avatar_mxc", null)?.takeIf { it.isNotBlank() }
        fromCurrentProfile ?: fromGlobalCache ?: fromPrefs
    }
    var startupAvatarMxc by remember {
        mutableStateOf(
            sharedPreferences.getString("current_user_avatar_mxc", null)?.takeIf { it.isNotBlank() }
        )
    }
    LaunchedEffect(candidateStartupAvatarMxc) {
        // Keep avatar source sticky while AuthCheck is visible to prevent fallback
        // spinner flicker when profile/prefs transiently report null.
        if (!candidateStartupAvatarMxc.isNullOrBlank()) {
            startupAvatarMxc = candidateStartupAvatarMxc
        }
    }
    var hasDiskCachedAvatar by remember { mutableStateOf(false) }
    LaunchedEffect(startupAvatarMxc) {
        hasDiskCachedAvatar = startupAvatarMxc?.let {
            IntelligentMediaCache.getCachedFile(context, it)?.exists() == true
        } ?: false
    }
    LaunchedEffect(startupAvatarMxc) {
        val prefsAvatar = sharedPreferences.getString("current_user_avatar_mxc", null)?.takeIf { it.isNotBlank() }
        if (startupAvatarMxc != null && startupAvatarMxc != prefsAvatar) {
            sharedPreferences.edit().putString("current_user_avatar_mxc", startupAvatarMxc).apply()
        }
    }
    
    // SHARED ELEMENT: showStartupMorphOverlay is at composable level so navigation
    // functions (inside LaunchedEffect) can reset it to false before navigating.
    // When it's true, the base shared-element avatar has alpha=0 and the morph overlay
    // covers it. Resetting to false right before navigation makes the shared element
    // visible (alpha=1) so the flight animation is actually seen by the user.
    var showStartupMorphOverlay by remember(startupAvatarMxc) { mutableStateOf(false) }
    LaunchedEffect(startupAvatarMxc) {
        if (startupAvatarMxc != null) {
            showStartupMorphOverlay = false
            kotlinx.coroutines.delay(220)
            showStartupMorphOverlay = true
        } else {
            showStartupMorphOverlay = false
        }
    }

    // CRITICAL FIX: Track navigation state to prevent duplicate navigation
    var navigationHandled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appViewModel.isLoading = true
        appViewModel.addStartupProgressMessage("Starting...")
        appViewModel.addStartupProgressMessage("Checking stored auth....")
        val token = sharedPreferences.getString("gomuks_auth_token", null)
        val homeserverUrl = sharedPreferences.getString("homeserver_url", null)

        if (token != null && homeserverUrl != null) {
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Token and server URL found.")
            
            // CRITICAL FIX: Check if WebSocket is already connected BEFORE adding "Connecting..." message
            val isAlreadyConnected = WebSocketService.isWebSocketConnected()
            if (!isAlreadyConnected) {
                appViewModel.addStartupProgressMessage("Connecting to WebSocket...")
            } else {
                appViewModel.addStartupProgressMessage("Attaching to existing WebSocket...")
            }
            
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
            
            // Try to load cached state first for instant UI
            val hasCachedState = appViewModel.loadStateFromStorage(context)
            if (hasCachedState) {
                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Loaded cached state - showing UI immediately")
                // Cached state loaded, UI will show rooms immediately
                // WebSocket will reconnect with run_id and last_received_id to get only missing events
            } else {
                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "No cached state available - will load full initial payload")
            }
            
            // Initialize FCM with homeserver URL and auth token
            appViewModel.initializeFCM(context, homeserverUrl, token)
            // Set homeserver URL and auth token in ViewModel for avatar loading
            appViewModel.updateHomeserverUrl(homeserverUrl)
            appViewModel.updateAuthToken(token)
            fun navigateToRoomListIfNeeded(reason: String) {
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "navigateToRoomListIfNeeded called with reason: $reason, currentRoute: $currentRoute")
                
                // CRITICAL FIX: When opening via app shortcut (not pinned shortcut), we should always navigate to room_list
                // even if we're currently on room_timeline/... from a previous session.
                // The reason "websocket already connected" indicates we're opening the app normally, not via shortcut.
                val shouldForceNavigation = reason == "websocket already connected" || reason == "default flow"
                
                if (currentRoute != null) {
                    // If we're already on "room_list", ensure isLoading is false and return
                    // (don't navigate again to avoid navigation loops)
                    if (currentRoute == "room_list") {
                        if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Already on room_list, ensuring isLoading is false")
                        appViewModel.isLoading = false
                        return
                    }
                    
                    // If we're on room_timeline/... or chat_bubble/... and we should force navigation,
                    // navigate to room_list anyway (user opened app via app shortcut, not pinned shortcut)
                    if (shouldForceNavigation && (currentRoute.startsWith("room_timeline/") || currentRoute.startsWith("chat_bubble/"))) {
                        if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Force navigating to room_list ($reason) - clearing previous navigation stack (currentRoute: $currentRoute)")
                        appViewModel.isLoading = false
                        showStartupMorphOverlay = false
                        navController.navigate("room_list") {
                            // Clear entire back stack and start fresh at room_list
                            popUpTo(0) { inclusive = true }
                        }
                        return
                    }
                    
                    // Skip navigation if we're on simple_room_list or other screens (unless forcing)
                    if (!shouldForceNavigation && (currentRoute == "simple_room_list" ||
                        currentRoute.startsWith("room_timeline/") ||
                        currentRoute.startsWith("chat_bubble/")
                    )) {
                        if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Skipping navigation to room_list ($reason) because current route is $currentRoute")
                        appViewModel.isLoading = false
                        return
                    }
                }
                
                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Navigating to room_list (reason: $reason, currentRoute: $currentRoute)")
                appViewModel.isLoading = false
                // Reset morph overlay so the shared element (base avatar) is visible (alpha=1)
                // during the flight. Without this, if showStartupMorphOverlay==true the base
                // avatar is at alpha=0 and the flight is invisible to the user.
                // Add a small delay to ensure Compose recomposes with the new state before navigation.
                showStartupMorphOverlay = false
                // Do NOT use popUpTo here — popping auth_check from the back stack before the
                // shared element flight completes prevents SharedTransitionLayout from keeping
                // both composables in composition simultaneously. The RoomListScreen BackHandler
                // already prevents the user from navigating back to auth_check.
                navController.navigate("room_list")
            }
            
            // Set up navigation callback BEFORE connecting websocket
            appViewModel.setNavigationCallback {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigation callback triggered")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigation callback - directRoomId: ${appViewModel.getDirectRoomNavigation()}, pendingRoomId: ${appViewModel.getPendingRoomNavigation()}")
                appViewModel.isLoading = false
                // Register FCM notifications after successful auth
                appViewModel.registerFCMNotifications()
                
                // Check for direct room navigation first (from notifications)
                val directRoomId = appViewModel.getDirectRoomNavigation()
                if (directRoomId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Direct room navigation detected (notification), navigating directly to room_timeline: $directRoomId")
                    // Navigate directly to room timeline (like ShortcutActivity does)
                    // This bypasses RoomListScreen to avoid delays and missing rooms
                    val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                    appViewModel.clearDirectRoomNavigation()
                    val encodedRoomId = java.net.URLEncoder.encode(directRoomId, "UTF-8")
                    
                    // Set current room ID and navigate to room with cache
                    appViewModel.setCurrentRoomIdForTimeline(directRoomId)
                    if (notificationTimestamp != null) {
                        appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                    } else {
                        appViewModel.navigateToRoomWithCache(directRoomId)
                    }
                    
                    navController.navigate("room_timeline/$encodedRoomId")
                    return@setNavigationCallback
                }
                
                // Check if we need to navigate to a specific room (from shortcut or bubble)
                val pendingRoomId = appViewModel.getPendingRoomNavigation()
                val pendingBubbleId = appViewModel.getPendingBubbleNavigation()
                
                if (pendingBubbleId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigating to pending bubble: $pendingBubbleId")
                    appViewModel.clearPendingBubbleNavigation()
                    
                    // Check if the room exists in our room list
                    val roomExists = appViewModel.getRoomById(pendingBubbleId) != null
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Bubble room exists check - roomExists: $roomExists, roomId: $pendingBubbleId")
                    if (roomExists) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Room exists, navigating to chat bubble")
                        navController.navigate("chat_bubble/$pendingBubbleId")
                    } else {
                        android.util.Log.w("Andromuks", "AuthCheck: Bubble room $pendingBubbleId not found in room list, staying in bubble mode")
                        // In bubble mode, don't navigate away - just show the bubble with empty state
                        navController.navigate("chat_bubble/$pendingBubbleId")
                    }
                } else if (pendingRoomId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigating to pending room: $pendingRoomId")
                    // Don't clear yet - let RoomListScreen handle the pending navigation
                    
                    // Check if the room exists in our room list
                    val roomExists = appViewModel.getRoomById(pendingRoomId) != null
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Room exists check - roomExists: $roomExists, roomId: $pendingRoomId")
                    if (roomExists) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Room exists, navigating to room_list first (pending room will auto-navigate)")
                        // Navigate to room_list first to establish proper back stack
                        // RoomListScreen will detect pending navigation and auto-navigate to room
                        navigateToRoomListIfNeeded("pendingRoomId exists")
                    } else {
                        android.util.Log.w("Andromuks", "AuthCheck: Room $pendingRoomId not found in room list, showing toast and going to room list")
                        // Show toast and navigate to room list
                        appViewModel.clearPendingRoomNavigation()
                        android.widget.Toast.makeText(context, "Room $pendingRoomId not found. Please try again later.", android.widget.Toast.LENGTH_LONG).show()
                        navigateToRoomListIfNeeded("pendingRoomId missing")
                    }
                } else {
                    navigateToRoomListIfNeeded("default flow")
                }
            }
            if (BuildConfig.DEBUG) Log.d("Andromuks", "AuthCheckScreen: appViewModel instance: $appViewModel")

            // CRITICAL FIX: Only primary AppViewModel instance should create WebSocket connections
            // Non-primary instances should attach to existing connection or wait for primary to connect
            // Note: isAlreadyConnected was already checked above for the progress message
            val isPrimary = appViewModel.isPrimaryInstance()
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket connection check - isPrimary: $isPrimary, isAlreadyConnected: $isAlreadyConnected")
            
            if (isAlreadyConnected) {
                // WebSocket is already connected (from primary AppViewModel instance), just attach to it
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket already connected, attaching to existing connection")
                appViewModel.attachToExistingWebSocketIfAvailable()
                
                // CRITICAL FIX: Since WebSocket is already connected, the navigation callback won't fire
                // We need to set isLoading = false and register FCM notifications here
                appViewModel.isLoading = false
                appViewModel.registerFCMNotifications()
                
                // Small delay to ensure state updates are processed before navigation
                kotlinx.coroutines.delay(50)
                
                // If we have direct room navigation (from notification), navigate immediately
                // The websocket is already connected, so we don't need to wait for navigation callback
                val directRoomId = appViewModel.getDirectRoomNavigation()
                if (directRoomId != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket already connected, navigating directly to room: $directRoomId")
                    val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                    appViewModel.clearDirectRoomNavigation()
                    val encodedRoomId = java.net.URLEncoder.encode(directRoomId, "UTF-8")
                    
                    // Set current room ID and navigate to room with cache
                    appViewModel.setCurrentRoomIdForTimeline(directRoomId)
                    if (notificationTimestamp != null) {
                        appViewModel.navigateToRoomWithCache(directRoomId, notificationTimestamp)
                    } else {
                        appViewModel.navigateToRoomWithCache(directRoomId)
                    }
                    
                    navController.navigate("room_timeline/$encodedRoomId")
                } else {
                    // No direct navigation - navigate to room_list normally
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket already connected, navigating to room_list")
                    try {
                        navigateToRoomListIfNeeded("websocket already connected")
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AuthCheckScreen: Error navigating to room_list", e)
                        // Fallback: try direct navigation
                        try {
                            showStartupMorphOverlay = false
                            navController.navigate("room_list") {
                                popUpTo(0) { inclusive = true }
                            }
                        } catch (e2: Exception) {
                            android.util.Log.e("Andromuks", "AuthCheckScreen: Error in fallback navigation", e2)
                        }
                    }
                }
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
                    // CRITICAL FIX: Since WebSocket is already connected, set isLoading = false and navigate
                    appViewModel.isLoading = false
                    appViewModel.registerFCMNotifications()
                    navigateToRoomListIfNeeded("non-primary attached to existing connection")
                } else {
                    // FALLBACK: If no primary instance exists (app was closed) and no connection exists,
                    // allow this non-primary instance to create the connection
                    // This is a fallback scenario when opening via notification/shortcut with app closed
                    android.util.Log.w("Andromuks", "AuthCheckScreen: Primary instance did not connect within timeout - using fallback: non-primary will create connection")
                    // REFACTORING: Delegate connection to service (service handles backend health check)
                    WebSocketService.connectWebSocket(homeserverUrl, token, appViewModel, reason = "AuthCheck fallback connection")
                }
            }
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
        val token = prefs.getString("gomuks_auth_token", null)
        val homeserverUrl = prefs.getString("homeserver_url", null)
        token != null && homeserverUrl != null
    }
    
    LaunchedEffect(appViewModel.spacesLoaded, hasCredentials) {
        if (hasCredentials && appViewModel.spacesLoaded && !navigationHandled) {
            // Spaces loaded from cache - navigate immediately
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Spaces loaded from cache - navigating to room_list (WebSocket may not be connected)")
            appViewModel.isLoading = false
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != null && currentRoute != "simple_room_list" && 
                !currentRoute.startsWith("room_timeline/") && 
                !currentRoute.startsWith("chat_bubble/")) {
                showStartupMorphOverlay = false
                navController.navigate("room_list")
            }
            navigationHandled = true
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
                    showStartupMorphOverlay = false
                    kotlinx.coroutines.delay(16) // One frame to ensure state update is visible
                    navController.navigate("room_list")
                }
                navigationHandled = true
            }
        }
    }

    AndromuksTheme {
        // auth_check is always a loading screen — unconditionally render it.
        // The nav transition's exit animation handles fading it out.
        // CRITICAL: Do NOT conditionally remove this based on the current nav route.
        // If we remove the shared element from composition the instant navController.navigate("room_list")
        // fires (which makes currentBackStackEntry report "room_list" immediately), the shared element
        // disappears before the flight animation can start, resulting in no transition.
        // NOTE: showStartupMorphOverlay is declared at composable scope (above) so navigation
        // functions can reset it to false before navigating, making the flight visible.
        StartupLoadingScreen(
                progressMessages = appViewModel.startupProgressMessages,
                modifier = modifier,
                topContent = {
                    val displayName = appViewModel.currentUserProfile?.displayName ?: appViewModel.currentUserId
                    val effectiveHomeserverUrl = appViewModel.homeserverUrl.takeIf { it.isNotBlank() } ?: storedHomeserverUrl
                    val effectiveAuthToken = appViewModel.authToken.takeIf { it.isNotBlank() } ?: storedAuthToken
                    val canShowAvatar = startupAvatarMxc != null
                    if (canShowAvatar) {
                        val morphMaskModifier = rememberMorphingStartupAvatarMaskModifier()
                        Box {
                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                with(sharedTransitionScope) {
                                    AvatarImage(
                                        mxcUrl = startupAvatarMxc,
                                        homeserverUrl = effectiveHomeserverUrl,
                                        authToken = effectiveAuthToken,
                                        fallbackText = displayName,
                                        size = 72.dp,
                                        userId = appViewModel.currentUserId,
                                        displayName = displayName,
                                        modifier = Modifier.sharedElement(
                                            rememberSharedContentState(key = STARTUP_SHARED_AVATAR_KEY),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            boundsTransform = { _, _ ->
                                                tween(durationMillis = 380, easing = LinearEasing)
                                            },
                                            renderInOverlayDuringTransition = true,
                                            zIndexInOverlay = 1f
                                        ).graphicsLayer {
                                            alpha = if (showStartupMorphOverlay) 0f else 1f
                                        }
                                    )
                                }
                            } else {
                                AvatarImage(
                                    mxcUrl = startupAvatarMxc,
                                    homeserverUrl = effectiveHomeserverUrl,
                                    authToken = effectiveAuthToken,
                                    fallbackText = displayName,
                                    size = 72.dp,
                                    userId = appViewModel.currentUserId,
                                    displayName = displayName,
                                    modifier = Modifier.graphicsLayer {
                                        alpha = if (showStartupMorphOverlay) 0f else 1f
                                    }
                                )
                            }
                            if (showStartupMorphOverlay) {
                                AvatarImage(
                                    mxcUrl = startupAvatarMxc,
                                    homeserverUrl = effectiveHomeserverUrl,
                                    authToken = effectiveAuthToken,
                                    fallbackText = displayName,
                                    size = 72.dp,
                                    userId = appViewModel.currentUserId,
                                    displayName = displayName,
                                    modifier = Modifier.then(morphMaskModifier)
                                )
                            }
                        }
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "AuthCheckScreen",
                                "Startup avatar unavailable, falling back to spinner (mxc=$startupAvatarMxc, cached=$hasDiskCachedAvatar, hs=${effectiveHomeserverUrl.isNotBlank()}, token=${effectiveAuthToken.isNotBlank()})"
                            )
                        }
                        ExpressiveLoadingIndicator(
                            modifier = Modifier.size(72.dp)
                        )
                    }
                }
            )
    }
}