package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Composable
fun AuthCheckScreen(navController: NavController) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
    }
    val client = remember { OkHttpClient.Builder().build() }
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val token = sharedPreferences.getString("gomuks_auth_token", null)
        val serverBaseUrl = sharedPreferences.getString("server_base_url", null)

        if (token != null && serverBaseUrl != null) {
            Log.d("AuthCheckScreen", "Token and server URL found. Attempting auto WebSocket connect.")
            connectToWebSocket(
                baseServerUrl = serverBaseUrl,
                httpClient = client,
                scope = scope,
                sharedPreferences = sharedPreferences,
                onConnectionAttemptFinished = {
                    if (isLoading) {
                        scope.launch{ isLoading = false }
                    }
                },
                navigateToLogin = {
                    scope.launch {
                        Log.d("AuthCheckScreen", "WebSocket auto-connect failed. Going to login.")
                        navController.navigate("login") {
                            popUpTo("auth_check") { inclusive = true }
                        }
                        isLoading = false
                    }
                },
                navigateToRoomList = {
                    scope.launch {
                        Log.d("AuthCheckScreen", "WebSocket auto-connect successful. Loading room list.")
                        navController.navigate("roomlist") {
                            popUpTo("auth_check") { inclusive = true }
                        }
                        isLoading = false
                    }
                }
            )
        } else {
            Log.d("AuthCheckScreen", "No token or server URL found. Going to login.")
            isLoading = false
            navController.navigate("login") {
                popUpTo("auth_check") { inclusive = true }
            }
        }
    }

    if (isLoading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
    }
}
