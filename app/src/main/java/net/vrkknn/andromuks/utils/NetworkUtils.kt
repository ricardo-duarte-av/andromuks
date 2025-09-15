package net.vrkknn.andromuks.utils

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.IOException
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

fun performHttpLogin(
    url: String,
    username: String,
    password: String,
    client: OkHttpClient,
    scope: CoroutineScope,
    sharedPreferences: SharedPreferences,
    onSuccess: () -> Unit,
    onFailure: () -> Unit
) {
    val authUrl = buildAuthHttpUrl(url)
    val credentials = okhttp3.Credentials.basic(username, password)
    Log.d("LoginScreen", "Attempting HTTP(S) login to: $authUrl with user: $username")

    val request = buildRequest(authUrl, credentials)

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("LoginScreen", "HTTP(S) Login onFailure", e)
            scope.launch {
                onFailure()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            val responseBodyString = response.body.string()
            if (response.isSuccessful) {
                try {
                    val jsonResponse = JSONObject(responseBodyString)
                    val receivedToken = jsonResponse.optString("token", "")
                    if (receivedToken != null) {
                        sharedPreferences.edit {
                            putString("gomuks_auth_token", receivedToken)
                            putString("homeserver_url", url)
                        }
                        Log.d(
                            "LoginScreen",
                            "Token and server base URL saved to SharedPreferences."
                        )
                        scope.launch {
                            onSuccess()
                        }
                    } else {
                        Log.w("LoginScreen", "Login successful, but no token in response")
                        scope.launch {
                            onFailure()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LoginScreen", "JSON Parsing Error", e)
                    scope.launch {
                        onFailure()
                    }
                }
            } else {
                Log.e("LoginScreen", "HTTP Login failed: ${response.code}")
                scope.launch {
                    onFailure()
                }
            }
        }
    })
}

fun buildRequest(url: String, credentials: String): Request {
    val requestBody = "".toRequestBody(null)
    val request = Request.Builder()
        .url(url)
        .header("Authorization", credentials)
        .post(requestBody)
        .build()
    Log.d("LoginScreen", "Request: $request with Authorization header")

    return request
}

fun connectToWebsocket(
    url: String,
    client: OkHttpClient,
    scope: CoroutineScope,
    token: String
) {
    Log.d("NetworkUtils", "connectToWebsocket: Initializing...")

    val webSocketUrl = trimWebsocketHost(url)

    val request = Request.Builder()
        .url(webSocketUrl)
        .addHeader("Cookie", "gomuks_auth=$token")
        .build()

    val websocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("NetworkUtils", "onOpen: ws opened on ${response.message}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("NetworkUtils", "WebSocket TextMessage: $text")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d("NetworkUtils", "WebSocket ByteMessage: ${bytes.hex()}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("NetworkUtils", "WebSocket Closing ($code): $reason")
            if (code != 1000 && code != 1001) { // 1000 = normal, 1001 = going away
                scope.launch {
                    Log.w("NetworkUtils", "WebSocket closed unexpectedly while potentially active.")
                }
            }
        }
    }

    client.newWebSocket(request, websocketListener)
}

fun trimWebsocketHost(url: String): String {
    var wsHost = url.lowercase().trim()
    if (wsHost.startsWith("https://")) {
        wsHost = wsHost.substringAfter("https://")
    } else if (wsHost.startsWith("http://")) {
        wsHost = wsHost.substringAfter("http://")
    }
    wsHost = wsHost.split("/").firstOrNull() ?: ""
    return "wss://$wsHost/_gomuks/websocket"
}
