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
            // Set up navigation callback BEFORE connecting websocket
            appViewModel.setNavigationCallback {
                android.util.Log.d("Andromuks", "AuthCheck: Navigation callback triggered - navigating to room_list")
                appViewModel.isLoading = false
                navController.navigate("room_list")
            }
            // Now connect websocket
            connectToWebsocket(homeserverUrl, client, scope, token, appViewModel)
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