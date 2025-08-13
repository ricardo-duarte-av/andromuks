package net.vrkknn.andromuks

import android.content.SharedPreferences
import android.util.Log
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

fun buildAuthHttpUrl(rawUrl: String): String {
    var authUrl = rawUrl.lowercase().trim()
    if (!authUrl.startsWith("http://") && !authUrl.startsWith("https://")) {
        authUrl = "https://$authUrl"
    } else if (authUrl.startsWith("http://")) {
        authUrl = authUrl.replaceFirst("http://", "https://")
    }
    if (authUrl.endsWith("/")) {
        authUrl = authUrl.substring(0, authUrl.length - 1)
    }
    return "$authUrl/_gomuks/auth?output=json"
}

fun connectToWebSocket(
    baseServerUrl: String,
    httpClient: OkHttpClient,
    scope: CoroutineScope,
    sharedPreferences: SharedPreferences,
    onConnectionAttemptFinished: () -> Unit,
    navigateToLogin: () -> Unit,
    navigateToRoomList: () -> Unit
) {
    Log.d("NetworkUtils", "connectToWebSocket: Initializing...")

    val storedToken = sharedPreferences.getString("gomuks_auth_token", null)

    if (storedToken == null) {
        Log.e("NetworkUtils", "connectToWebSocket: Auth token not found in SharedPreferences.")
        scope.launch {
            navigateToLogin()
            onConnectionAttemptFinished()
        }
        return
    }

    var wsHost = baseServerUrl.lowercase().trim()
    if (wsHost.startsWith("https://")) {
        wsHost = wsHost.substringAfter("https://")
    } else if (wsHost.startsWith("http://")) {
        wsHost = wsHost.substringAfter("http://")
    }
    wsHost = wsHost.split("/").firstOrNull() ?: ""

    if (wsHost.isBlank()) {
        Log.e("NetworkUtils", "connectToWebSocket: Invalid host from baseServerUrl: $baseServerUrl")
        scope.launch {
            sharedPreferences.edit().remove("gomuks_auth_token").remove("server_base_url").apply()
            navigateToLogin()
            onConnectionAttemptFinished()
        }
        return
    }

    val webSocketUrl = "wss://$wsHost/_gomuks/websocket"
    Log.d("NetworkUtils", "Attempting WebSocket connection to: $webSocketUrl")

    val request = Request.Builder()
        .url(webSocketUrl)
        .addHeader("Cookie", "gomuks_auth=$storedToken")
        .build()

    var pingerJob: Job? = null

    val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("NetworkUtils", "WebSocket Opened: Connected! Server: ${response.message}")

            // start the pinger because gomuks websocket needs a ping eveery 15 seconds
            // TODO: fix pinger
            pingerJob = scope.launch {
                try {
                    while (isActive) {
                        val pingCommand = JSONObject().apply {
                            put("command", "ping")
                            put("request_id", System.currentTimeMillis())
                        }.toString()
                        Log.d("NetworkUtils", "Sending websocket ping: $pingCommand")
                        webSocket.send(pingCommand)
                        delay(15000L)
                    }
                } catch (e: Exception) {
                    Log.e("NetworkUtils", "WebSocket ping loop failed: ${e.message}", e)
                } finally {
                    Log.d("NetworkUtils", "Pinger loop finished.")
                }
            }

            scope.launch {
                navigateToRoomList()
                onConnectionAttemptFinished()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("NetworkUtils", "WebSocket Message: $text")

            scope.launch {
                WebSocketEvents.newRawMessage(text)
            }

            try {
                val jsonResponse = JSONObject(text)
                if (jsonResponse.optString("command") == "pong") {
                    Log.d("NetworkUtils", "Received pong for request_id: ${jsonResponse.optLong("request_id")}")
                }
            } catch (e: Exception) {}
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d("NetworkUtils", "WebSocket ByteMessage: ${bytes.hex()}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("NetworkUtils", "WebSocket Closing ($code): $reason")
            pingerJob?.cancel()
            if (code != 1000 && code != 1001) { // 1000 = normal, 1001 = going away
                scope.launch {
                    Log.w("NetworkUtils", "WebSocket closed unexpectedly while potentially active.")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            pingerJob?.cancel()
            val responseBody = response?.body?.string()
            Log.e("NetworkUtils", "WebSocket Failed: ${t.message}. Response: ${response?.message ?: "N/A"}${if (responseBody != null) " Body: $responseBody" else ""}", t)
            scope.launch {
                sharedPreferences.edit().remove("gomuks_auth_token").remove("server_base_url").apply()
                navigateToLogin()
                onConnectionAttemptFinished()
            }
        }
    }
    httpClient.newWebSocket(request, webSocketListener)
}
