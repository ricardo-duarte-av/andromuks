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
                        
                        // Check if we were launched from a conversation shortcut
                        val roomId = intent.getStringExtra("room_id")
                        if (roomId != null) {
                            appViewModel.setPendingRoomNavigation(roomId)
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
        notificationBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "net.vrkknn.andromuks.SEND_MESSAGE" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val messageText = intent.getStringExtra("message_text")
                        if (roomId != null && messageText != null) {
                            Log.d("Andromuks", "MainActivity: Received send message broadcast for room $roomId: $messageText")
                            appViewModel.sendMessage(roomId, messageText)
                        }
                    }
                    "net.vrkknn.andromuks.MARK_READ" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        if (roomId != null) {
                            Log.d("Andromuks", "MainActivity: Received mark read broadcast for room $roomId, event: $eventId")
                            appViewModel.markRoomAsRead(roomId, eventId ?: "")
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("net.vrkknn.andromuks.SEND_MESSAGE")
            addAction("net.vrkknn.andromuks.MARK_READ")
        }
        registerReceiver(notificationBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }
    
    private fun registerNotificationActionReceiver() {
        notificationActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "net.vrkknn.andromuks.ACTION_REPLY" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        val replyText = getReplyText(intent)
                        
                        if (roomId != null && replyText != null) {
                            Log.d("Andromuks", "MainActivity: Handling reply action for room: $roomId, text: $replyText")
                            appViewModel.sendMessage(roomId, replyText)
                        }
                    }
                    "net.vrkknn.andromuks.ACTION_MARK_READ" -> {
                        val roomId = intent.getStringExtra("room_id")
                        val eventId = intent.getStringExtra("event_id")
                        
                        if (roomId != null) {
                            Log.d("Andromuks", "MainActivity: Handling mark read action for room: $roomId, event: $eventId")
                            appViewModel.markRoomAsRead(roomId, eventId ?: "")
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
        return androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence("key_reply_text")
            ?.toString()
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Handle room navigation from notification clicks
        intent?.getStringExtra("room_id")?.let { roomId ->
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

    override fun onResume() {
        super.onResume()
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameVisible()
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (::appViewModel.isInitialized) {
            appViewModel.onAppBecameInvisible()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
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
}

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
        composable("room_list") { RoomListScreen(navController = navController, modifier = modifier, appViewModel = appViewModel) }
        composable(
            route = "room_timeline/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
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
    }
}