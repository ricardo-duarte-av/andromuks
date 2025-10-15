package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

class MainActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel
    private lateinit var notificationBroadcastReceiver: BroadcastReceiver
    private lateinit var notificationActionReceiver: BroadcastReceiver
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            AndromuksTheme {
                AppNavigation(
                    modifier = Modifier.fillMaxSize(),
                    onViewModelCreated = { viewModel ->
                        appViewModel = viewModel
                        // Load cached user profiles on app startup
                        // This restores previously saved user profile data from disk
                        appViewModel.loadCachedProfiles(this)
                        
                        // Check if we were launched from a conversation shortcut or matrix: URI
                        val roomId = intent.getStringExtra("room_id")
                        val matrixUri = intent.data
                        Log.d("Andromuks", "MainActivity: onCreate - roomId extra: $roomId, matrixUri: $matrixUri")
                        val extractedRoomId = roomId ?: extractRoomIdFromMatrixUri(matrixUri)
                        Log.d("Andromuks", "MainActivity: onCreate - extractedRoomId: $extractedRoomId")
                        if (extractedRoomId != null) {
                            appViewModel.setPendingRoomNavigation(extractedRoomId)
                        }
                        
                        // Register broadcast receiver for notification actions
                        registerNotificationBroadcastReceiver()
                        registerNotificationActionReceiver()
                    }
                )
            }
        }
    }
    
    private fun registerNotificationBroadcastReceiver() {
        Log.d("Andromuks", "MainActivity: Registering notification broadcast receiver")
        notificationBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("Andromuks", "MainActivity: Broadcast receiver got intent: ${intent?.action}")
                when (intent?.action) {
                    "net.vrkknn.andromuks.SEND_MESSAGE" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val messageText = intent.getStringExtra("message_text")
                        Log.d("Andromuks", "MainActivity: SEND_MESSAGE broadcast - roomId: $roomId, messageText: $messageText")
                        if (roomId != null && messageText != null) {
                            Log.d("Andromuks", "MainActivity: Received send message broadcast for room $roomId: $messageText")
                            appViewModel.sendMessageFromNotification(roomId, messageText) {
                                Log.d("Andromuks", "MainActivity: Broadcast send message completed")
                                // Update the notification with the sent message
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationWithReply(roomId, messageText)
                                        Log.d("Andromuks", "MainActivity: Updated notification with reply for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification with reply", e)
                                }
                            }
                        } else {
                            Log.w("Andromuks", "MainActivity: SEND_MESSAGE broadcast missing data - roomId: $roomId, messageText: $messageText")
                        }
                    }
                    "net.vrkknn.andromuks.MARK_READ" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        Log.d("Andromuks", "MainActivity: MARK_READ broadcast - roomId: $roomId, eventId: $eventId")
                        if (roomId != null) {
                            Log.d("Andromuks", "MainActivity: Received mark read broadcast for room $roomId, event: $eventId")
                            appViewModel.markRoomAsReadFromNotification(roomId, eventId ?: "") {
                                Log.d("Andromuks", "MainActivity: Broadcast mark read completed")
                                // Update the notification to show it's been read
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationAsRead(roomId)
                                        Log.d("Andromuks", "MainActivity: Updated notification as read for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification as read", e)
                                }
                            }
                        } else {
                            Log.w("Andromuks", "MainActivity: MARK_READ broadcast missing roomId")
                        }
                    }
                    else -> {
                        Log.w("Andromuks", "MainActivity: Unknown broadcast action: ${intent?.action}")
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("net.vrkknn.andromuks.SEND_MESSAGE")
            addAction("net.vrkknn.andromuks.MARK_READ")
        }
        registerReceiver(notificationBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d("Andromuks", "MainActivity: Notification broadcast receiver registered successfully")
    }
    
    private fun registerNotificationActionReceiver() {
        notificationActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "net.vrkknn.andromuks.ACTION_REPLY" -> {
                        Log.d("Andromuks", "MainActivity: ACTION_REPLY received in broadcast receiver")
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        val replyText = getReplyText(intent)
                        
                        Log.d("Andromuks", "MainActivity: Reply data extracted - roomId: $roomId, eventId: $eventId, replyText: '$replyText'")
                        
                        if (roomId != null && replyText != null) {
                            Log.d("Andromuks", "MainActivity: Calling appViewModel.sendMessageFromNotification for room: $roomId")
                            appViewModel.sendMessageFromNotification(roomId, replyText) {
                                Log.d("Andromuks", "MainActivity: Reply message sent successfully")
                                // Update the notification with the sent message
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationWithReply(roomId, replyText)
                                        Log.d("Andromuks", "MainActivity: Updated notification with reply for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification with reply", e)
                                }
                            }
                            Log.d("Andromuks", "MainActivity: sendMessageFromNotification call completed")
                        } else {
                            Log.w("Andromuks", "MainActivity: Missing required data - roomId: $roomId, replyText: $replyText")
                        }
                    }
                    "net.vrkknn.andromuks.ACTION_MARK_READ" -> {
                        Log.d("Andromuks", "MainActivity: ACTION_MARK_READ received in broadcast receiver")
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        
                        Log.d("Andromuks", "MainActivity: Mark read data extracted - roomId: $roomId, eventId: '$eventId'")
                        
                        if (roomId != null && eventId != null && eventId.isNotEmpty()) {
                            Log.d("Andromuks", "MainActivity: Calling appViewModel.markRoomAsReadFromNotification for room: $roomId, event: $eventId")
                            appViewModel.markRoomAsReadFromNotification(roomId, eventId) {
                                Log.d("Andromuks", "MainActivity: Mark read completed successfully")
                                // Update the notification to show it's been read
                                try {
                                    val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
                                    val authToken = sharedPrefs.getString("gomuks_auth_token", "") ?: ""
                                    val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
                                    
                                    if (homeserverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                                        val enhancedNotificationDisplay = EnhancedNotificationDisplay(this@MainActivity, homeserverUrl, authToken)
                                        enhancedNotificationDisplay.updateNotificationAsRead(roomId)
                                        Log.d("Andromuks", "MainActivity: Updated notification as read for room: $roomId")
                                    } else {
                                        Log.w("Andromuks", "MainActivity: Cannot update notification - missing homeserver or auth token")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "MainActivity: Error updating notification as read", e)
                                }
                            }
                        } else {
                            Log.w("Andromuks", "MainActivity: Missing required data for mark read - roomId: $roomId, eventId: '$eventId'")
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("net.vrkknn.andromuks.ACTION_REPLY")
            addAction("net.vrkknn.andromuks.ACTION_MARK_READ")
        }
        registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }
    
    private fun getReplyText(intent: Intent): String? {
        Log.d("Andromuks", "MainActivity: getReplyText called")
        val remoteInputResults = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
        Log.d("Andromuks", "MainActivity: RemoteInput results: $remoteInputResults")
        
        val replyText = remoteInputResults
            ?.getCharSequence("key_reply_text")
            ?.toString()
            
        Log.d("Andromuks", "MainActivity: Extracted reply text: '$replyText'")
        return replyText
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Handle room navigation from notification clicks or matrix: URIs
        val roomId = intent.getStringExtra("room_id")
        val matrixUri = intent.data
        Log.d("Andromuks", "MainActivity: onNewIntent - roomId extra: $roomId, matrixUri: $matrixUri")
        val extractedRoomId = roomId ?: extractRoomIdFromMatrixUri(matrixUri)
        Log.d("Andromuks", "MainActivity: onNewIntent - extractedRoomId: $extractedRoomId")
        extractedRoomId?.let { roomId ->
            if (::appViewModel.isInitialized) {
                Log.d("Andromuks", "MainActivity: onNewIntent - Navigating to room: $roomId")
                appViewModel.setPendingRoomNavigation(roomId)
                // Force navigation if app is already running
                if (appViewModel.spacesLoaded) {
                    // App is already initialized, trigger navigation directly
                    // This will be handled by the UI layer
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Andromuks", "MainActivity: onDestroy called")
        try {
            if (::notificationBroadcastReceiver.isInitialized) {
                unregisterReceiver(notificationBroadcastReceiver)
            }
            if (::notificationActionReceiver.isInitialized) {
                unregisterReceiver(notificationActionReceiver)
            }
        } catch (e: Exception) {
            Log.w("Andromuks", "MainActivity: Error unregistering broadcast receivers", e)
        }
    }
    
    override fun onStop() {
        super.onStop()
        Log.d("Andromuks", "MainActivity: onStop called")
    }
    
    override fun onPause() {
        super.onPause()
        Log.d("Andromuks", "MainActivity: onPause called")
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameInvisible()
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("Andromuks", "MainActivity: onResume called")
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameVisible()
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d("Andromuks", "MainActivity: onStart called")
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
    }
    
    /**
     * Extract room ID or alias from matrix: URI
     * Examples:
     * - matrix:roomid/!bDYZaOWoWwefjYdoRz:aguiarvieira.pt?via=aguiarvieira.pt
     * - matrix:r/#test9test:aguiarvieira.pt
     */
    private fun extractRoomIdFromMatrixUri(uri: android.net.Uri?): String? {
        Log.d("Andromuks", "MainActivity: extractRoomIdFromMatrixUri called with uri: $uri")
        if (uri == null) {
            Log.d("Andromuks", "MainActivity: URI is null")
            return null
        }
        
        // Use the extractRoomLink utility function
        val roomLink = net.vrkknn.andromuks.utils.extractRoomLink(uri.toString())
        if (roomLink != null) {
            Log.d("Andromuks", "MainActivity: Extracted room link: ${roomLink.roomIdOrAlias}")
            return roomLink.roomIdOrAlias
        }
        
        Log.d("Andromuks", "MainActivity: Could not extract room link from URI")
        return null
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNavigation(
    modifier: Modifier,
    onViewModelCreated: (AppViewModel) -> Unit = {}
) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel()
    
    // Notify the parent about the ViewModel creation
    onViewModelCreated(appViewModel)
    
    NavHost(
        navController = navController,
        startDestination = "auth_check",
        modifier = modifier
    ) {
        composable("login") { LoginScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable("auth_check") { AuthCheckScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable("permissions") {
            PermissionsScreen(
                onPermissionsGranted = {
                    // Navigate to auth_check after permissions are granted
                    // This will trigger WebSocket connection
                    navController.navigate("auth_check") {
                        popUpTo("permissions") { inclusive = true }
                    }
                },
                modifier = modifier
            )
        }
        composable(
            route = "room_list",
            exitTransition = {
                if (targetState.destination.route == "room_timeline/{roomId}") {
                    slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            },
            popEnterTransition = {
                if (initialState.destination.route == "room_timeline/{roomId}") {
                    slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            }
        ) { RoomListScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable(
            route = "room_timeline/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
            enterTransition = {
                if (initialState.destination.route == "room_list") {
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            },
            popExitTransition = {
                if (targetState.destination.route == "room_list") {
                    slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            }
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            val roomName = appViewModel.getRoomById(roomId)?.name ?: ""
            RoomTimelineScreen(
                roomId = roomId,
                roomName = roomName,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
        composable(
            route = "invite_detail/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            InviteDetailScreen(
                roomId = roomId,
                navController = navController,
                modifier = modifier,
                appViewModel = appViewModel
            )
        }
        composable("settings") {
            SettingsScreen(
                appViewModel = appViewModel,
                navController = navController
            )
        }
        composable(
            route = "room_info/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
            enterTransition = {
                if (initialState.destination.route == "room_timeline/{roomId}") {
                    slideInVertically(
                        initialOffsetY = { -it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            },
            popExitTransition = {
                if (targetState.destination.route == "room_timeline/{roomId}") {
                    slideOutVertically(
                        targetOffsetY = { -it },
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                } else {
                    null
                }
            }
        ) { backStackEntry: NavBackStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            net.vrkknn.andromuks.utils.RoomInfoScreen(
                roomId = roomId,
                navController = navController,
                appViewModel = appViewModel,
                modifier = modifier
            )
        }
        composable(
            route = "user_info/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry: NavBackStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: ""
            net.vrkknn.andromuks.utils.UserInfoScreen(
                userId = userId,
                navController = navController,
                appViewModel = appViewModel,
                modifier = modifier
            )
        }
    }
}