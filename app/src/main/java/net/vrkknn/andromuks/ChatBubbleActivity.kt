package net.vrkknn.andromuks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.BuildConfig

import android.app.ActivityManager
import android.content.ComponentName
import androidx.activity.OnBackPressedCallback

class ChatBubbleActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel
    private var isMinimizing = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()

        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCreate called")

        // Set up proper back button handling for bubbles
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback triggered - minimizing bubble")
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - calling moveTaskToBack")
                try {
                    moveTaskToBack(true)
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - moveTaskToBack completed")
                } catch (e: Exception) {
                    Log.e("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - moveTaskToBack failed", e)
                    // Try alternative approach - simulate home button
                    try {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - home intent completed")
                    } catch (e2: Exception) {
                        Log.e("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - home intent also failed", e2)
                    }
                }
            }
        })

        setContent {
            AndromuksTheme {
                ChatBubbleNavigation(
                    modifier = Modifier.fillMaxSize(),
                    onViewModelCreated = { viewModel ->
                        appViewModel = viewModel
                        appViewModel.markAsBubbleInstance()
                        // Load cached user profiles on app startup
                        appViewModel.loadCachedProfiles(this)
                        appViewModel.loadSettings(this)
                        appViewModel.attachToExistingWebSocketIfAvailable()
                        
                        // IMPORTANT: Mark bubble as visible for live updates WITHOUT expensive UI refresh
                        // Bubbles don't need to update shortcuts or refresh room list
                        appViewModel.setBubbleVisible(true)
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Marked bubble as visible (lightweight)")
                        
                        // OPTIMIZATION #2: Optimized intent processing for ChatBubbleActivity
                        val roomId = intent.getStringExtra("room_id")
                        val directNavigation = intent.getBooleanExtra("direct_navigation", false)
                        val bubbleMode = intent.getBooleanExtra("bubble_mode", false)
                        val matrixUri = intent.data
                        
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCreate - roomId: $roomId, directNavigation: $directNavigation, bubbleMode: $bubbleMode, matrixUri: $matrixUri")
                        
                        val extractedRoomId = if (directNavigation && roomId != null) {
                            // OPTIMIZATION #2: Fast path - room ID already extracted
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCreate - OPTIMIZATION #2 - Using pre-extracted room ID: $roomId")
                            roomId
                        } else {
                            // Fallback to URI parsing for legacy intents
                            val uriRoomId = extractRoomIdFromMatrixUri(matrixUri)
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCreate - Fallback URI parsing: $uriRoomId")
                            uriRoomId
                        }
                        
                        // CRITICAL: Track bubble at Activity level for reliable detection
                        // This ensures bubble is tracked even if Composable hasn't composed yet
                        if (extractedRoomId != null) {
                            BubbleTracker.onBubbleOpened(extractedRoomId)
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCreate - Tracked bubble opened at Activity level for room: $extractedRoomId")
                            appViewModel.setPendingBubbleNavigation(extractedRoomId)
                        }
                    },
                    onCloseBubble = {
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble called - closing bubble")
                        // CRITICAL FIX: Close the bubble by finishing the activity
                        // This properly dismisses the bubble instead of just moving it to background
                        try {
                            // First, mark bubble as closed in tracker
                            val roomId = intent.getStringExtra("room_id") ?: 
                                        extractRoomIdFromMatrixUri(intent.data)
                            if (roomId != null) {
                                BubbleTracker.onBubbleInvisible(roomId)
                                BubbleTracker.onBubbleClosed(roomId)
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble - marked bubble as closed for room: $roomId")
                            }
                            
                            // Finish the activity to close the bubble
                            finish()
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble - finish() called")
                        } catch (e: Exception) {
                            Log.e("Andromuks", "ChatBubbleActivity: onCloseBubble - finish() failed", e)
                            // Fallback: try moveTaskToBack if finish fails
                            try {
                                moveTaskToBack(true)
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble - fallback moveTaskToBack completed")
                            } catch (e2: Exception) {
                                Log.e("Andromuks", "ChatBubbleActivity: onCloseBubble - fallback also failed", e2)
                            }
                        }
                    },
                    onMinimizeBubble = {
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onMinimizeBubble called - minimizing bubble")
                        // Minimize the bubble by finishing with transition (collapses the bubble)
                        // Do NOT cancel the notification - just finish the activity so the system collapses the bubble
                        // Note: moveTaskToBack() doesn't work for always-on-top bubbles (WindowManager ignores it)
                        try {
                            this@ChatBubbleActivity.finishAfterTransition()
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onMinimizeBubble - finishAfterTransition() called")
                        } catch (e: Exception) {
                            Log.e("Andromuks", "ChatBubbleActivity: onMinimizeBubble - finishAfterTransition() failed", e)
                            // Fallback: try moveTaskToBack (though it may be ignored for always-on-top tasks)
                            try {
                                this@ChatBubbleActivity.moveTaskToBack(true)
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onMinimizeBubble - fallback moveTaskToBack(true) called")
                            } catch (e2: Exception) {
                                Log.e("Andromuks", "ChatBubbleActivity: onMinimizeBubble - fallback also failed", e2)
                            }
                        }
                    }
                )
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onStart called")
    }
    
    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onResume called")
        
        // Extract room ID from intent to track bubble visibility
        val roomId = intent.getStringExtra("room_id") ?: 
                    extractRoomIdFromMatrixUri(intent.data)
        
        if (roomId != null) {
            // CRITICAL: Ensure bubble is tracked as open (backup in case onCreate didn't catch it)
            // This handles cases where room ID wasn't available in onCreate
            // IMPORTANT: Activity-level tracking is authoritative - don't let Composable lifecycle override it
            BubbleTracker.onBubbleOpened(roomId)
            BubbleTracker.onBubbleVisible(roomId)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onResume - Tracked bubble opened and visible for room: $roomId")
        }
        
        if (::appViewModel.isInitialized) {
            // Lightweight visibility flag - don't trigger expensive UI refresh for bubbles
            appViewModel.setBubbleVisible(true)
            appViewModel.attachToExistingWebSocketIfAvailable()
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onPause called")
        
        // Extract room ID from intent to track bubble visibility
        val roomId = intent.getStringExtra("room_id") ?: 
                    extractRoomIdFromMatrixUri(intent.data)
        
        if (roomId != null) {
            BubbleTracker.onBubbleInvisible(roomId)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Tracked bubble invisible for room: $roomId")
        }
        
        if (::appViewModel.isInitialized) {
            // Lightweight invisibility flag - don't trigger shutdown for bubbles
            appViewModel.setBubbleVisible(false)
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onStop called")
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onNewIntent called - bubble reactivated")
        
        // CRITICAL FIX: Update the intent to handle new notifications for existing bubble
        // This prevents the activity from restarting when a new notification arrives
        setIntent(intent)
        
        // Extract room ID from new intent
        val roomId = intent.getStringExtra("room_id") ?: 
                    extractRoomIdFromMatrixUri(intent.data)
        
        if (roomId != null) {
            // CRITICAL: Track bubble for new room ID (in case room changed)
            BubbleTracker.onBubbleOpened(roomId)
            BubbleTracker.onBubbleVisible(roomId)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onNewIntent - Tracked bubble opened for room: $roomId")
            
            if (::appViewModel.isInitialized) {
                // Update navigation to the new room if different, or refresh if same room
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onNewIntent - updating navigation for room: $roomId")
                appViewModel.setPendingBubbleNavigation(roomId)
            }
        }
        
        // When the bubble is tapped again, bring it to foreground
        // Note: moveTaskToFront() is not available in ComponentActivity
        // The system will handle bringing the bubble to foreground
    }
    
    
    override fun onBackPressed() {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Back pressed - minimizing bubble")
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Back pressed - calling moveTaskToBack")
        
        // Minimize the bubble by moving it to background
        moveTaskToBack(true)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Back pressed - moveTaskToBack completed")
    }
    
    override fun finish() {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: finish() called - isMinimizing=$isMinimizing")
        if (isMinimizing) {
            // If we're minimizing, properly finish the activity to collapse the bubble
            // The notification will keep the bubble alive so it can be reopened
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: finish() - minimizing, calling super.finish()")
            super.finish()
        } else {
            // For other finish calls, minimize by moving to background
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: finish() - not minimizing, calling moveTaskToBack")
            moveTaskToBack(true)
        }
    }
    
    override fun finishAfterTransition() {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: finishAfterTransition() called - setting isMinimizing flag")
        // Set flag so that when finish() is called after transition, it properly finishes
        isMinimizing = true
        super.finishAfterTransition()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onDestroy called")
        
        // CRITICAL: Track bubble closure at Activity level for reliable detection
        // Extract room ID from intent to track which bubble was closed
        // IMPORTANT: Activity-level tracking is authoritative - this runs AFTER ViewModel is cleared
        // so it ensures bubble state is accurate even if Composable disposed early
        val roomId = intent.getStringExtra("room_id") ?: 
                    extractRoomIdFromMatrixUri(intent.data)
        
        if (roomId != null) {
            // Only close if not already closed (handles case where Composable already closed it)
            // But Activity-level tracking should be the final authority
            if (BubbleTracker.isBubbleOpen(roomId)) {
                BubbleTracker.onBubbleInvisible(roomId)
                BubbleTracker.onBubbleClosed(roomId)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onDestroy - Tracked bubble closed at Activity level for room: $roomId")
            } else {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: onDestroy - Bubble already closed (likely by Composable disposal) for room: $roomId")
            }
        } else {
            if (BuildConfig.DEBUG) Log.w("Andromuks", "ChatBubbleActivity: onDestroy - Could not extract room ID from intent")
        }
        
        // Note: BubbleTimelineScreen's DisposableEffect also tracks bubbles for redundancy
        // but Activity-level tracking in onDestroy() is the final authority for notification dismissal checks
    }
    
    /**
     * Extract room ID from matrix: URI
     * Examples:
     * - matrix://bubble/bDYZaOWoWwefjYdoRz:aguiarvieira.pt
     * - matrix://bubble/bDYZaOWoWwefjYdoRz:aguiarvieira.pt
     */
    private fun extractRoomIdFromMatrixUri(uri: android.net.Uri?): String? {
        if (uri == null) return null
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Extracting room ID from URI: $uri")
        
        // Handle matrix://bubble/roomId format
        if (uri.scheme == "matrix" && uri.host == "bubble") {
            val pathSegments = uri.pathSegments
            if (pathSegments.isNotEmpty()) {
                val roomIdWithoutExclamation = pathSegments[0]
                val roomId = "!$roomIdWithoutExclamation"
                if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Extracted room ID: $roomId")
                return roomId
            }
        }
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleActivity: Room ID without exclamation is empty")
        return null
    }
}

@Composable
fun ChatBubbleNavigation(
    modifier: Modifier,
    onViewModelCreated: (AppViewModel) -> Unit = {},
    onCloseBubble: () -> Unit = {},
    onMinimizeBubble: () -> Unit = {}
) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel()
    
    // Notify the parent about the ViewModel creation (only once)
    LaunchedEffect(Unit) {
        onViewModelCreated(appViewModel)
    }
    
    if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleNavigation: Starting bubble navigation")
    
    // Handle back navigation - if we're at the root, close the bubble
    LaunchedEffect(navController) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (BuildConfig.DEBUG) Log.d("Andromuks", "ChatBubbleNavigation: Navigated to ${destination.route}")
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = "chat_bubble_loading",
        modifier = modifier
    ) {
        composable("chat_bubble_loading") { 
            ChatBubbleLoadingScreen(
                navController = navController, 
                modifier = modifier, 
                appViewModel = appViewModel,
                onCloseBubble = onCloseBubble
            ) 
        }
        composable(
            route = "chat_bubble/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            val roomName = appViewModel.getRoomById(roomId)?.name ?: ""
            val context = LocalContext.current
            BubbleTimelineScreen(
                roomId = roomId,
                roomName = roomName,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel,
                onCloseBubble = onCloseBubble,
                onMinimizeBubble = onMinimizeBubble,
                onOpenInApp = {
                    try {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            putExtra("room_id", roomId)
                            putExtra("direct_navigation", true)
                            putExtra("from_notification", true)
                            data = android.net.Uri.parse(
                                "matrix:roomid/${roomId.removePrefix("!")}"
                            )
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("Andromuks", "ChatBubbleNavigation: Failed to open main app for room $roomId", e)
                    }
                }
            )
        }
    }
}
