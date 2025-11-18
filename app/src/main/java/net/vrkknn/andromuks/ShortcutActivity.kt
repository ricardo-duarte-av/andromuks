package net.vrkknn.andromuks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

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
        
        // PHASE 1: Initialize RoomRepository (single source of truth for room/timeline data)
        // This is critical for loading events from database when RAM cache is empty
        RoomRepository.initialize(this)
        
        // Extract room ID from intent
        val roomId = extractRoomIdFromIntent(intent)
        if (roomId == null) {
            android.util.Log.e("Andromuks", "ShortcutActivity: No room ID found in intent")
            finish()
            return
        }
        
        android.util.Log.d("Andromuks", "ShortcutActivity: OPTIMIZATION #3 - Direct shortcut navigation to room: $roomId")
        
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
            android.util.Log.d("Andromuks", "ShortcutActivity: OPTIMIZATION #3 - Using direct room_id: $directRoomId")
            return directRoomId
        }
        
        // Fallback to URI parsing for legacy shortcuts
        val data = intent.data
        if (data != null) {
            android.util.Log.d("Andromuks", "ShortcutActivity: OPTIMIZATION #3 - Parsing URI: $data")
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
    
    // Initialize AppViewModel like MainActivity does
    LaunchedEffect(Unit) {
        // Get homeserver URL and auth token from SharedPreferences (needed for initializeFCM)
        val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
        
        // Initialize FCM to set appContext (required for database loading via bootstrapLoader)
        // This is critical for loading events from database when RAM cache is empty
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
        
        android.util.Log.d("Andromuks", "ShortcutActivity: AppViewModel initialized with profiles, settings, and FCM")
    }
    
    // OPTIMIZATION #3: Direct navigation to room timeline
    LaunchedEffect(roomId) {
        android.util.Log.d("Andromuks", "ShortcutActivity: OPTIMIZATION #3 - Direct navigation to room: $roomId")
        
        // Use cache-first navigation for instant loading
        // This will fall back to database loading if RAM cache is empty
        appViewModel.navigateToRoomWithCache(roomId)
        
        // Navigate directly to room timeline
        navController.navigate("room_timeline/$roomId")
    }
    
    NavHost(
        navController = navController,
        startDestination = "room_timeline/$roomId"
    ) {
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
