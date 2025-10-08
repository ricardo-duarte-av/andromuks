package net.vrkknn.andromuks

import android.content.Context
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
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.connectToWebsocket
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
            Log.d("AuthCheckScreen", "Token and server URL found. Attempting auto WebSocket connect.")
            
            // Try to load cached state first for instant UI
            val hasCachedState = appViewModel.loadStateFromStorage(context)
            if (hasCachedState) {
                Log.d("AuthCheckScreen", "Loaded cached state - showing UI immediately")
                // Cached state loaded, UI will show rooms immediately
                // WebSocket will reconnect with run_id and last_received_id to get only missing events
            } else {
                Log.d("AuthCheckScreen", "No cached state available - will load full initial payload")
            }
            
            // Initialize FCM with homeserver URL and auth token
            appViewModel.initializeFCM(context, homeserverUrl, token)
            // Set homeserver URL and auth token in ViewModel for avatar loading
            appViewModel.updateHomeserverUrl(homeserverUrl)
            appViewModel.updateAuthToken(token)
            // Set up navigation callback BEFORE connecting websocket
            appViewModel.setNavigationCallback {
                android.util.Log.d("Andromuks", "AuthCheck: Navigation callback triggered - navigating to room_list")
                android.util.Log.d("Andromuks", "AuthCheck: Navigation callback - pendingRoomId: ${appViewModel.getPendingRoomNavigation()}")
                appViewModel.isLoading = false
                // Register FCM notifications after successful auth
                appViewModel.registerFCMNotifications()
                
                // Check if we need to navigate to a specific room (from shortcut or bubble)
                val pendingRoomId = appViewModel.getPendingRoomNavigation()
                val pendingBubbleId = appViewModel.getPendingBubbleNavigation()
                
                if (pendingBubbleId != null) {
                    android.util.Log.d("Andromuks", "AuthCheck: Navigating to pending bubble: $pendingBubbleId")
                    appViewModel.clearPendingBubbleNavigation()
                    
                    // Check if the room exists in our room list
                    val roomExists = appViewModel.getRoomById(pendingBubbleId) != null
                    android.util.Log.d("Andromuks", "AuthCheck: Bubble room exists check - roomExists: $roomExists, roomId: $pendingBubbleId")
                    if (roomExists) {
                        android.util.Log.d("Andromuks", "AuthCheck: Room exists, navigating to chat bubble")
                        navController.navigate("chat_bubble/$pendingBubbleId")
                    } else {
                        android.util.Log.w("Andromuks", "AuthCheck: Bubble room $pendingBubbleId not found in room list, staying in bubble mode")
                        // In bubble mode, don't navigate away - just show the bubble with empty state
                        navController.navigate("chat_bubble/$pendingBubbleId")
                    }
                } else if (pendingRoomId != null) {
                    android.util.Log.d("Andromuks", "AuthCheck: Navigating to pending room: $pendingRoomId")
                    appViewModel.clearPendingRoomNavigation()
                    
                    // Check if the room exists in our room list
                    val roomExists = appViewModel.getRoomById(pendingRoomId) != null
                    android.util.Log.d("Andromuks", "AuthCheck: Room exists check - roomExists: $roomExists, roomId: $pendingRoomId")
                    if (roomExists) {
                        android.util.Log.d("Andromuks", "AuthCheck: Room exists, navigating to room timeline")
                        navController.navigate("room_timeline/$pendingRoomId")
                    } else {
                        android.util.Log.w("Andromuks", "AuthCheck: Room $pendingRoomId not found in room list, showing toast and going to room list")
                        // Show toast and navigate to room list
                        android.widget.Toast.makeText(context, "Room $pendingRoomId not found. Please try again later.", android.widget.Toast.LENGTH_LONG).show()
                        navController.navigate("room_list")
                    }
                } else {
                    navController.navigate("room_list")
                }
            }
            Log.d("Andromuks", "AuthCheckScreen: appViewModel instance: $appViewModel")
            // Now connect websocket
            connectToWebsocket(homeserverUrl, client, token, appViewModel)
            // Do not navigate yet; wait for spacesLoaded
        } else {
            Log.d("AuthCheckScreen", "No token or server URL found. Going to login.")
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