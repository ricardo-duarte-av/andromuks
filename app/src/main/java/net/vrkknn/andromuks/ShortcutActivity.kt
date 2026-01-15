package net.vrkknn.andromuks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.BuildConfig
import androidx.activity.compose.BackHandler


/**
 * ShortcutActivity - Direct room navigation for shortcuts
 * 
 * This activity bypasses MainActivity entirely for shortcut navigation.
 * It goes directly to the room timeline, eliminating:
 * - MainActivity lifecycle overhead
 * - Navigation through RoomListScreen
 * - Intent processing in MainActivity
 * - Compose navigation setup time
 * 
 * Benefits:
 * - Faster shortcut navigation
 * - Direct room access
 * - Reduced app startup time
 * - Optimized for single-room focus
 */
class ShortcutActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize crash handler
        net.vrkknn.andromuks.utils.CrashHandler.initialize(this)
        
        // Extract room ID from intent
        val roomId = extractRoomIdFromIntent(intent)
        if (roomId == null) {
            android.util.Log.e("Andromuks", "ShortcutActivity: No room ID found in intent")
            finish()
            return
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: OPTIMIZATION #3 - Direct shortcut navigation to room: $roomId")
        
        setContent {
            AndromuksTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ShortcutNavigation(roomId = roomId)
                }
            }
        }
    }
    
    private fun extractRoomIdFromIntent(intent: Intent): String? {
        // OPTIMIZATION #3: Fast path - check for direct room_id first
        val directRoomId = intent.getStringExtra("room_id")
        if (directRoomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: OPTIMIZATION #3 - Using direct room_id: $directRoomId")
            return directRoomId
        }
        
        // Fallback to URI parsing for legacy shortcuts
        val data = intent.data
        if (data != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: OPTIMIZATION #3 - Parsing URI: $data")
            return extractRoomIdFromMatrixUri(data.toString())
        }
        
        return null
    }
    
    private fun extractRoomIdFromMatrixUri(uri: String): String? {
        return try {
            when {
                uri.startsWith("matrix://bubble/") -> {
                    val roomId = uri.substringAfter("matrix://bubble/")
                    if (roomId.startsWith("!")) roomId else "!$roomId"
                }
                uri.startsWith("matrix:roomid/") -> {
                    val roomId = uri.substringAfter("matrix:roomid/")
                    if (roomId.startsWith("!")) roomId else "!$roomId"
                }
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ShortcutActivity: Error parsing Matrix URI: $uri", e)
            null
        }
    }
}

@Composable
fun ShortcutNavigation(roomId: String) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // CRITICAL FIX #3: Track navigation state to prevent multiple navigations
    var hasNavigated by remember { mutableStateOf(false) }
    var showLoading by remember { mutableStateOf(true) }
    
    // Initialize AppViewModel like MainActivity does
    LaunchedEffect(Unit) {
        // Get homeserver URL and auth token from SharedPreferences (needed for initializeFCM)
        val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
        
        // Initialize FCM to set appContext (required for cache access)
        // This is critical for loading events when RAM cache is empty
        appViewModel.initializeFCM(context, homeserverUrl, authToken)
        
        // CRITICAL: Do NOT mark as primary - only MainActivity's AppViewModel should be primary
        // This ensures only AppViewModel_0 creates WebSocket connections, which are then maintained by the Foreground service
        // ShortcutActivity instances should attach to existing connections created by the primary instance
        // appViewModel.markAsPrimaryInstance() // REMOVED - ShortcutActivity is secondary
        
        // Load cached user profiles on app startup
        // This restores previously saved user profile data from disk
        appViewModel.loadCachedProfiles(context)
        
        // Load app settings from SharedPreferences
        appViewModel.loadSettings(context)
        
        // Re-attach to existing WebSocket connection if the service already has one
        appViewModel.attachToExistingWebSocketIfAvailable()
        
        // CRITICAL FIX #3: Load spaces from storage if not already loaded
        // This ensures spacesLoaded is true even if primary AppViewModel hasn't loaded yet
        if (!appViewModel.spacesLoaded) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: Spaces not loaded, loading from storage...")
            appViewModel.loadStateFromStorage(context)
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: AppViewModel initialized with profiles, settings, and FCM")
    }
    
    // CRITICAL FIX #3: Wait for WebSocket connection and spacesLoaded before navigating
    // This ensures proper state before showing room timeline (prevents unpredictable behavior)
    LaunchedEffect(appViewModel.spacesLoaded) {
        if (hasNavigated) return@LaunchedEffect
        
        val spacesReady = appViewModel.spacesLoaded
        
        if (spacesReady) {
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
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: CRITICAL FIX #3 - WebSocket connected=$websocketConnected (pollCount=$pollCount) and spaces loaded, navigating to: $roomId")
                
                // CRITICAL FIX: Set currentRoomId immediately for shortcut navigation
                // This ensures state is consistent even though ShortcutActivity uses a separate AppViewModel instance
                // The state will be synchronized via SharedPreferences
                appViewModel.setCurrentRoomIdForTimeline(roomId)
                
                // Use cache-first navigation for instant loading
                // This will fall back to network loading if RAM cache is empty
                appViewModel.navigateToRoomWithCache(roomId)
                
                // Navigate directly to room timeline
                navController.navigate("room_timeline/$roomId")
                hasNavigated = true
                showLoading = false
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: CRITICAL FIX #3 - WebSocket not connected after polling, will use timeout fallback")
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: CRITICAL FIX #3 - Waiting for spacesLoaded before navigating")
        }
    }
    
    // CRITICAL FIX #3: Timeout fallback - navigate after 10 seconds even if WebSocket never connects
    // This prevents infinite waiting when WebSocket can't connect (e.g., airplane mode)
    LaunchedEffect(Unit) {
        if (hasNavigated) return@LaunchedEffect
        
        kotlinx.coroutines.delay(10000) // 10 second timeout
        
        if (!hasNavigated) {
            android.util.Log.w("Andromuks", "ShortcutActivity: CRITICAL FIX #3 - Navigation timeout (10s) for $roomId - WebSocket may not be connected, navigating anyway")
            
            // CRITICAL FIX: Set currentRoomId immediately for shortcut navigation
            appViewModel.setCurrentRoomIdForTimeline(roomId)
            
            // Use cache-first navigation for instant loading
            appViewModel.navigateToRoomWithCache(roomId)
            
            // Navigate directly to room timeline
            navController.navigate("room_timeline/$roomId")
            hasNavigated = true
            showLoading = false
        }
    }
    
    // Show loading screen while waiting for WebSocket/spaces
    if (showLoading && !hasNavigated) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }
    
    NavHost(
        navController = navController,
        startDestination = "room_timeline/$roomId"
    ) {
        composable("room_list") {
            // CRITICAL: Always populate roomMap from singleton cache when RoomListScreen is created in ShortcutActivity
            // This ensures shortcuts have access to all rooms even when AppViewModel is new
            // We do this BEFORE RoomListScreen is composed so roomMap is populated when RoomListScreen's LaunchedEffect runs
            LaunchedEffect(Unit) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: Populating roomMap from singleton cache before RoomListScreen initialization")
                appViewModel.populateRoomMapFromCache()
            }
            
            RoomListScreen(
                navController = navController,
                appViewModel = appViewModel
            )
            
            // Override back handler for ShortcutActivity - finish activity instead of moving to background
            // This BackHandler is composed AFTER RoomListScreen, so it takes precedence (BackHandlers are processed in reverse order)
            BackHandler(enabled = true) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: Back button pressed from room_list, finishing activity")
                (context as? androidx.activity.ComponentActivity)?.finish()
            }
        }
        composable("room_timeline/{roomId}") { backStackEntry ->
            val currentRoomId = backStackEntry.arguments?.getString("roomId") ?: roomId
            RoomTimelineScreen(
                roomId = currentRoomId,
                roomName = "Room", // Default name - will be updated by AppViewModel
                appViewModel = appViewModel,
                navController = navController
            )
        }
    }
}
