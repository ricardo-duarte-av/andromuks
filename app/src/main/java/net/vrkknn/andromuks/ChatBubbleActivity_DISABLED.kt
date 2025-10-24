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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import android.app.ActivityManager
import android.content.ComponentName
import androidx.activity.OnBackPressedCallback

class ChatBubbleActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.d("Andromuks", "ChatBubbleActivity: onCreate called")

        // Set up proper back button handling for bubbles
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback triggered - minimizing bubble")
                Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - calling moveTaskToBack")
                try {
                    moveTaskToBack(true)
                    Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - moveTaskToBack completed")
                } catch (e: Exception) {
                    Log.e("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - moveTaskToBack failed", e)
                    // Try alternative approach - simulate home button
                    try {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        Log.d("Andromuks", "ChatBubbleActivity: OnBackPressedCallback - home intent completed")
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
                        // Load cached user profiles on app startup
                        appViewModel.loadCachedProfiles(this)
                        
                        // IMPORTANT: Mark bubble as visible for live updates WITHOUT expensive UI refresh
                        // Bubbles don't need to update shortcuts or refresh room list
                        appViewModel.setBubbleVisible(true)
                        Log.d("Andromuks", "ChatBubbleActivity: Marked bubble as visible (lightweight)")
                        
                        // OPTIMIZATION #2: Optimized intent processing for ChatBubbleActivity
                        val roomId = intent.getStringExtra("room_id")
                        val directNavigation = intent.getBooleanExtra("direct_navigation", false)
                        val bubbleMode = intent.getBooleanExtra("bubble_mode", false)
                        val matrixUri = intent.data
                        
                        Log.d("Andromuks", "ChatBubbleActivity: onCreate - roomId: $roomId, directNavigation: $directNavigation, bubbleMode: $bubbleMode, matrixUri: $matrixUri")
                        
                        val extractedRoomId = if (directNavigation && roomId != null) {
                            // OPTIMIZATION #2: Fast path - room ID already extracted
                            Log.d("Andromuks", "ChatBubbleActivity: onCreate - OPTIMIZATION #2 - Using pre-extracted room ID: $roomId")
                            roomId
                        } else {
                            // Fallback to URI parsing for legacy intents
                            val uriRoomId = extractRoomIdFromMatrixUri(matrixUri)
                            Log.d("Andromuks", "ChatBubbleActivity: onCreate - Fallback URI parsing: $uriRoomId")
                            uriRoomId
                        }
                        
                        if (extractedRoomId != null) {
                            appViewModel.setPendingBubbleNavigation(extractedRoomId)
                        }
                    },
                    onCloseBubble = {
                        Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble called - minimizing bubble")
                        Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble - calling moveTaskToBack")
                        try {
                            moveTaskToBack(true)
                            Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble - moveTaskToBack completed")
                        } catch (e: Exception) {
                            Log.e("Andromuks", "ChatBubbleActivity: onCloseBubble - moveTaskToBack failed", e)
                            // Try alternative approach
                            try {
                                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                activityManager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
                                Log.d("Andromuks", "ChatBubbleActivity: onCloseBubble - alternative method completed")
                            } catch (e2: Exception) {
                                Log.e("Andromuks", "ChatBubbleActivity: onCloseBubble - alternative method also failed", e2)
                            }
                        }
                    }
                )
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d("Andromuks", "ChatBubbleActivity: onStart called")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("Andromuks", "ChatBubbleActivity: onResume called")
        if (::appViewModel.isInitialized) {
            // Lightweight visibility flag - don't trigger expensive UI refresh for bubbles
            appViewModel.setBubbleVisible(true)
        }
    }
    
    override fun onPause() {
        super.onPause()
        Log.d("Andromuks", "ChatBubbleActivity: onPause called")
        if (::appViewModel.isInitialized) {
            // Lightweight invisibility flag - don't trigger shutdown for bubbles
            appViewModel.setBubbleVisible(false)
        }
    }
    
    override fun onStop() {
        super.onStop()
        Log.d("Andromuks", "ChatBubbleActivity: onStop called")
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("Andromuks", "ChatBubbleActivity: onNewIntent called - bubble reactivated")
        // When the bubble is tapped again, bring it to foreground
        // Note: moveTaskToFront() is not available in ComponentActivity
        // The system will handle bringing the bubble to foreground
    }
    
    
    override fun onBackPressed() {
        Log.d("Andromuks", "ChatBubbleActivity: Back pressed - minimizing bubble")
        Log.d("Andromuks", "ChatBubbleActivity: Back pressed - calling moveTaskToBack")
        
        // Minimize the bubble by moving it to background
        moveTaskToBack(true)
        Log.d("Andromuks", "ChatBubbleActivity: Back pressed - moveTaskToBack completed")
    }
    
    override fun finish() {
        Log.d("Andromuks", "ChatBubbleActivity: finish() called - minimizing bubble instead of destroying")
        // Don't call super.finish() to prevent the bubble from being destroyed
        // Instead, just move to background so it can be reopened
        moveTaskToBack(true)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d("Andromuks", "ChatBubbleActivity: onDestroy called")
    }
    
    /**
     * Extract room ID from matrix: URI
     * Examples:
     * - matrix://bubble/bDYZaOWoWwefjYdoRz:aguiarvieira.pt
     * - matrix://bubble/bDYZaOWoWwefjYdoRz:aguiarvieira.pt
     */
    private fun extractRoomIdFromMatrixUri(uri: android.net.Uri?): String? {
        if (uri == null) return null
        
        Log.d("Andromuks", "ChatBubbleActivity: Extracting room ID from URI: $uri")
        
        // Handle matrix://bubble/roomId format
        if (uri.scheme == "matrix" && uri.host == "bubble") {
            val pathSegments = uri.pathSegments
            if (pathSegments.isNotEmpty()) {
                val roomIdWithoutExclamation = pathSegments[0]
                val roomId = "!$roomIdWithoutExclamation"
                Log.d("Andromuks", "ChatBubbleActivity: Extracted room ID: $roomId")
                return roomId
            }
        }
        
        Log.d("Andromuks", "ChatBubbleActivity: Room ID without exclamation is empty")
        return null
    }
}

@Composable
fun ChatBubbleNavigation(
    modifier: Modifier,
    onViewModelCreated: (AppViewModel) -> Unit = {},
    onCloseBubble: () -> Unit = {}
) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel()
    
    // Notify the parent about the ViewModel creation (only once)
    LaunchedEffect(Unit) {
        onViewModelCreated(appViewModel)
    }
    
    Log.d("Andromuks", "ChatBubbleNavigation: Starting bubble navigation")
    
    // Handle back navigation - if we're at the root, close the bubble
    LaunchedEffect(navController) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            Log.d("Andromuks", "ChatBubbleNavigation: Navigated to ${destination.route}")
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
            ChatBubbleScreen(
                roomId = roomId,
                roomName = roomName,
                modifier = modifier,
                appViewModel = appViewModel,
                onCloseBubble = onCloseBubble
            )
        }
    }
}
