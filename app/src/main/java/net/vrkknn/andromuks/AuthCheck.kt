package net.vrkknn.andromuks

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.connectToWebsocket
import net.vrkknn.andromuks.utils.waitForBackendHealth
import net.vrkknn.andromuks.BuildConfig

import okhttp3.OkHttpClient
import androidx.compose.ui.Modifier

@Composable
fun AuthCheckScreen(navController: NavController, modifier: Modifier, appViewModel: AppViewModel) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val client = remember { OkHttpClient.Builder().build() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        appViewModel.isLoading = true
        val token = sharedPreferences.getString("gomuks_auth_token", null)
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
            
            // If permissions not granted, navigate to permissions screen
            if (!hasNotificationPermission || !hasBatteryOptimization) {
                if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Permissions not granted, navigating to permissions screen")
                appViewModel.isLoading = false
                navController.navigate("permissions") {
                    popUpTo("auth_check") { inclusive = true }
                }
                return@LaunchedEffect
            }
            
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "All permissions granted. Attempting auto WebSocket connect.")
            
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
                if (currentRoute != null) {
                    if (currentRoute == "simple_room_list" ||
                        currentRoute.startsWith("room_timeline/") ||
                        currentRoute.startsWith("chat_bubble/")
                    ) {
                        if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "Skipping navigation to room_list ($reason) because current route is $currentRoute")
                        return
                    }
                }
                navController.navigate("room_list")
            }
            
            // Set up navigation callback BEFORE connecting websocket
            appViewModel.setNavigationCallback {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigation callback triggered - navigating to room_list")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheck: Navigation callback - pendingRoomId: ${appViewModel.getPendingRoomNavigation()}")
                appViewModel.isLoading = false
                // Register FCM notifications after successful auth
                appViewModel.registerFCMNotifications()
                
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
            
            // Set reconnection parameters in service BEFORE starting service (run_id is in SharedPreferences)
            val lastReceivedId = appViewModel.getLastReceivedId()
            if (lastReceivedId != 0) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Setting reconnection parameters in service - lastReceivedId: $lastReceivedId (run_id from SharedPreferences)")
                WebSocketService.setReconnectionState(lastReceivedId)
            }
            
            // Start WebSocket service BEFORE connecting websocket
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Starting WebSocket service before connection")
            appViewModel.startWebSocketService()
            
            // Set app as visible since we're starting the app
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Setting app as visible")
            WebSocketService.setAppVisibility(true)

            // CRITICAL FIX: Only primary AppViewModel instance should create WebSocket connections
            // Non-primary instances should attach to existing connection or wait for primary to connect
            val isPrimary = appViewModel.isPrimaryInstance()
            val isAlreadyConnected = WebSocketService.isWebSocketConnected()
            
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket connection check - isPrimary: $isPrimary, isAlreadyConnected: $isAlreadyConnected")
            
            if (isAlreadyConnected) {
                // WebSocket is already connected (from primary AppViewModel instance), just attach to it
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: WebSocket already connected, attaching to existing connection")
                appViewModel.attachToExistingWebSocketIfAvailable()
                // Don't call connectToWebsocket - we're already connected
            } else if (isPrimary) {
                // This is the primary instance and no connection exists - create the connection
                // The Foreground service will maintain this connection
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Primary instance - creating WebSocket connection (will be maintained by Foreground service)")
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Verifying backend health before opening WebSocket")
                waitForBackendHealth(homeserverUrl, loggerTag = "AuthCheckScreen")
                
                // Connect websocket - service is now ready to receive the connection
                connectToWebsocket(homeserverUrl, client, token, appViewModel)
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
                } else {
                    // FALLBACK: If no primary instance exists (app was closed) and no connection exists,
                    // allow this non-primary instance to create the connection
                    // This is a fallback scenario when opening via notification/shortcut with app closed
                    android.util.Log.w("Andromuks", "AuthCheckScreen: Primary instance did not connect within timeout - using fallback: non-primary will create connection")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AuthCheckScreen: Verifying backend health before opening WebSocket (fallback)")
                    waitForBackendHealth(homeserverUrl, loggerTag = "AuthCheckScreen")
                    
                    // Create connection as fallback (Foreground service will maintain it)
                    connectToWebsocket(homeserverUrl, client, token, appViewModel)
                }
            }
            // Do not navigate yet; wait for spacesLoaded
        } else {
            if (BuildConfig.DEBUG) Log.d("AuthCheckScreen", "No token or server URL found. Going to login.")
            appViewModel.isLoading = false
            navController.navigate("login")
        }
    }

    AndromuksTheme {
        Surface {
            Column(
                modifier = modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (appViewModel.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                }
            }
        }
    }
}