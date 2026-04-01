package net.vrkknn.andromuks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
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

    private var currentRoomId: String? = null
    // Set by onUserLeaveHint (HOME / recents navigation) and consumed in onStop to arm the
    // "finish on next foreground" flag.  onUserLeaveHint is NOT called when we launch a
    // child activity (e.g. an image viewer) from within the app, so this correctly ignores
    // child-activity open/close cycles.
    private var userLeftTask = false
    private var hasBeenStopped = false

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
        currentRoomId = roomId

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

    // singleTop: called instead of onCreate when this activity is already at the top of the
    // task and a new shortcut/widget intent arrives.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newRoomId = extractRoomIdFromIntent(intent) ?: return
        if (newRoomId != currentRoomId) {
            // Different room — finish and let Android start a fresh instance so the
            // Compose NavController is rebuilt for the new room.
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: onNewIntent - different room ($currentRoomId → $newRoomId), restarting")
            finish()
            startActivity(Intent(this, ShortcutActivity::class.java).apply {
                putExtra("room_id", newRoomId)
            })
        }
        // Same room: activity is already showing it, nothing to do.
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Called only when the user leaves the task via HOME or recents — NOT when we launch
        // a child activity from within the app (startActivity from our own code keeps the flag
        // false).  Arm the flag so onStop can mark us as "backgrounded by user".
        userLeftTask = true
    }

    override fun onStop() {
        super.onStop()
        if (userLeftTask) {
            hasBeenStopped = true
        }
        userLeftTask = false
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: onStop called (hasBeenStopped=$hasBeenStopped)")
    }

    override fun onStart() {
        super.onStart()
        if (hasBeenStopped) {
            hasBeenStopped = false
            // The user foregrounded the app (app-icon or recents) after leaving via HOME from a
            // pinned shortcut / conversation widget.  Finish this activity so MainActivity (which
            // lives below in the same task) becomes the visible screen, preserving wherever the
            // user left it.  If MainActivity is not in the task (pure-shortcut first launch),
            // finishing here returns to the home screen; a subsequent app-icon tap starts
            // MainActivity normally.
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: onStart after user-leave background — finishing to reveal MainActivity")
            finish()
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

    // Fast path: if the WebSocket is already connected (app was running in the background)
    // skip the loading spinner entirely and go straight to the room.
    val alreadyConnected = remember { WebSocketService.isWebSocketConnected() }
    var hasNavigated by remember { mutableStateOf(false) }
    // Only show the loading screen when we actually need to wait for the connection.
    var showLoading by remember { mutableStateOf(!alreadyConnected) }

    // Initialize AppViewModel (runs regardless of fast/slow path).
    LaunchedEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""

        appViewModel.initializeFCM(context, homeserverUrl, authToken)
        appViewModel.loadCachedProfiles(context)
        appViewModel.loadSettings(context)
        appViewModel.attachToExistingWebSocketIfAvailable()

        if (!appViewModel.spacesLoaded) {
            appViewModel.loadStateFromStorage(context)
        }

        // Fast path: WebSocket was already up when the composable was first created —
        // set up room state now (init above has finished) and mark navigation done so
        // the slow-path LaunchedEffects below do nothing.
        if (alreadyConnected && !hasNavigated) {
            appViewModel.setCurrentRoomIdForTimeline(roomId)
            appViewModel.navigateToRoomWithCache(roomId)
            hasNavigated = true
            showLoading = false
        }
    }

    // Slow path: wait for WebSocket connection and spacesLoaded before navigating.
    LaunchedEffect(appViewModel.spacesLoaded) {
        if (hasNavigated) return@LaunchedEffect

        if (appViewModel.spacesLoaded) {
            var websocketConnected = appViewModel.isWebSocketConnected()
            var pollCount = 0
            while (!websocketConnected && pollCount < 100) {
                kotlinx.coroutines.delay(100)
                websocketConnected = appViewModel.isWebSocketConnected()
                pollCount++
            }

            if (websocketConnected || pollCount >= 100) {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ShortcutActivity: WebSocket connected=$websocketConnected (pollCount=$pollCount), navigating to: $roomId")
                appViewModel.setCurrentRoomIdForTimeline(roomId)
                appViewModel.navigateToRoomWithCache(roomId)
                navController.navigate("room_timeline/$roomId")
                hasNavigated = true
                showLoading = false
            }
        }
    }

    // Timeout fallback: navigate after 10 s even if WebSocket never connects.
    LaunchedEffect(Unit) {
        if (hasNavigated) return@LaunchedEffect
        kotlinx.coroutines.delay(10000)
        if (!hasNavigated) {
            android.util.Log.w("Andromuks", "ShortcutActivity: Navigation timeout (10 s) for $roomId — proceeding without WebSocket")
            appViewModel.setCurrentRoomIdForTimeline(roomId)
            appViewModel.navigateToRoomWithCache(roomId)
            navController.navigate("room_timeline/$roomId")
            hasNavigated = true
            showLoading = false
        }
    }

    // Show loading screen only while waiting for a slow/cold connection.
    if (showLoading && !hasNavigated) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ExpressiveLoadingIndicator(
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
                appViewModel.populateSpacesFromCache()
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
