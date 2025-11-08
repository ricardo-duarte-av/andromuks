package net.vrkknn.andromuks.utils

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.WebSocketService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import net.vrkknn.andromuks.TimelineEvent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

/**
 * Streaming DEFLATE decompressor that maintains state across multiple frames
 * Similar to JavaScript's DecompressionStream("deflate-raw")
 */
class StreamingDeflateDecompressor {
    private val inflater = Inflater(true) // Raw DEFLATE
    private val outputBuffer = ByteArrayOutputStream()
    private val inputBuffer = ByteArrayOutputStream()
    private var isFinished = false
    
    fun write(data: ByteArray) {
        if (isFinished) return
        
        inputBuffer.write(data)
        inflater.setInput(inputBuffer.toByteArray())
        
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished() && !inflater.needsInput()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    outputBuffer.write(buffer, 0, count)
                }
            }
            
            // Remove processed data from input buffer
            val remaining = inflater.remaining
            if (remaining > 0) {
                val currentInput = inputBuffer.toByteArray()
                inputBuffer.reset()
                inputBuffer.write(currentInput, currentInput.size - remaining, remaining)
            } else {
                inputBuffer.reset()
            }
            
        } catch (e: Exception) {
            Log.e("Andromuks", "StreamingDeflateDecompressor: Decompression failed", e)
            throw e
        }
    }
    
    fun readAvailable(): String? {
        val data = outputBuffer.toByteArray()
        if (data.isEmpty()) return null
        
        outputBuffer.reset()
        return String(data, Charsets.UTF_8)
    }
    
    fun close() {
        inflater.end()
        isFinished = true
    }
}

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
                        // Log a dump of SharedPreferences (mask sensitive values)
                        try {
                            val allPrefs = sharedPreferences.all
                            Log.d("LoginScreen", "SharedPreferences dump start →")
                            for ((key, value) in allPrefs) {
                                val masked = if (key.contains("token", ignoreCase = true) || key.contains("password", ignoreCase = true)) "<redacted>" else value?.toString()
                                Log.d("LoginScreen", "pref[$key] = $masked")
                            }
                            Log.d("LoginScreen", "← SharedPreferences dump end")
                        } catch (e: Exception) {
                            Log.w("LoginScreen", "Failed to dump SharedPreferences", e)
                        }
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
    token: String,
    appViewModel: AppViewModel,
    reason: String = "Initial connection"
) {
    Log.d("NetworkUtils", "connectToWebsocket: Initializing... Reason: $reason")
    
    var streamingDecompressor: StreamingDeflateDecompressor? = null

    val webSocketUrl = trimWebsocketHost(url)
    
    // Build WebSocket URL with reconnection parameters if available
    // Get reconnection parameters from AppViewModel (service may not be started yet)
    val (runId, lastReceivedId, vapidKey) = Triple(appViewModel.getCurrentRunId(), appViewModel.getLastReceivedId(), "")
    
    // Debug logging to identify JSON encoding issue
    Log.d("NetworkUtils", "DEBUG: runId='$runId', lastReceivedId=$lastReceivedId, vapidKey='${vapidKey.take(20)}...'")
    Log.d("NetworkUtils", "DEBUG: runId type: ${runId.javaClass.simpleName}, length: ${runId.length}")
    
    // TEMPORARY FIX: If runId is JSON-encoded, extract the actual run_id
    val actualRunId = if (runId.startsWith("{")) {
        try {
            val jsonObject = org.json.JSONObject(runId)
            val extractedRunId = jsonObject.optString("run_id", "")
            Log.w("NetworkUtils", "TEMPORARY FIX: Extracted run_id from JSON: '$extractedRunId'")
            extractedRunId
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Failed to extract run_id from JSON: $runId", e)
            runId
        }
    } else {
        runId
    }
    
    // Check if compression is enabled
    val compressionEnabled = appViewModel.enableCompression
    val compressionParam = if (compressionEnabled) "&compress=1" else ""
    
    // Initialize streaming decompressor if compression is enabled
    if (compressionEnabled) {
        streamingDecompressor = StreamingDeflateDecompressor()
        Log.d("NetworkUtils", "Streaming DEFLATE decompressor initialized")
    }
    
    val finalWebSocketUrl = if (actualRunId.isNotEmpty() && lastReceivedId != 0) {
        // Reconnecting with run_id and last_received_event
        val url = "$webSocketUrl?run_id=$actualRunId&last_received_event=$lastReceivedId$compressionParam"
        Log.d("NetworkUtils", "Reconnecting with run_id: $actualRunId, last_received_event: $lastReceivedId, compression: $compressionEnabled")
        Log.d("NetworkUtils", "DEBUG: Final URL: $url")
        url
    } else if (actualRunId.isNotEmpty() && lastReceivedId == 0) {
        // Force refresh: reconnecting with run_id but NO last_received_event (full payload)
        val url = "$webSocketUrl?run_id=$actualRunId$compressionParam"
        Log.d("NetworkUtils", "FORCE REFRESH: Reconnecting with run_id: $actualRunId but last_received_event=0 (full payload), compression: $compressionEnabled")
        Log.d("NetworkUtils", "DEBUG: Final URL: $url")
        url
    } else {
        // First connection
        val url = if (compressionEnabled) "$webSocketUrl?compress=1" else webSocketUrl
        Log.d("NetworkUtils", "First connection to websocket, compression: $compressionEnabled")
        Log.d("NetworkUtils", "DEBUG: Final URL: $url")
        url
    }

    val request = Request.Builder()
        .url(finalWebSocketUrl)
        .addHeader("Cookie", "gomuks_auth=$token")
        .addHeader("User-Agent", "Andromuks/1.0 (Android)")
        .build()
    
    // Set up websocket restart callback
    appViewModel.onRestartWebSocket = { reason ->
        Log.d("NetworkUtils", "Websocket restart requested, reconnecting... Reason: $reason")
        connectToWebsocket(url, client, token, appViewModel, reason)
    }

    val websocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("Andromuks", "NetworkUtils: onOpen: ws opened on "+response.message)
            
            // Debug: Log all response headers to see if backend request ID is available
            Log.d("Andromuks", "NetworkUtils: WebSocket response headers:")
            response.headers.names().forEach { name ->
                val value = response.header(name)
                Log.d("Andromuks", "NetworkUtils: Header '$name': '$value'")
            }
            
            // Set WebSocket in AppViewModel (which will also set it in service)
            Log.d("Andromuks", "NetworkUtils: Calling appViewModel.setWebSocket()")
            appViewModel.setWebSocket(webSocket)
            Log.d("Andromuks", "NetworkUtils: connectToWebsocket using AppViewModel instance: $appViewModel")
            
            // Reset reconnection tracking on successful connection
            appViewModel.resetReconnectionState()
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("Andromuks", "NetworkUtils: WebSocket connection failed", t)
            Log.e("Andromuks", "NetworkUtils: Failure reason: ${t.message}, response: ${response?.code}")
            
            // Clear WebSocket connection in both AppViewModel and service
            val failureReason = "Connection failure: ${t.message}"
            appViewModel.clearWebSocket(failureReason)
            WebSocketService.clearWebSocket(failureReason)
            
            // Trigger reconnection with exponential backoff
            appViewModel.scheduleReconnection(reason = failureReason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            //Log.d("Andromuks", "NetworkUtils: WebSocket TextMessage: $text")
            val jsonObject = try { JSONObject(text) } catch (e: Exception) { null }
            if (jsonObject != null) {
                // Debug: Check if this message contains backend request ID
                val backendRequestId = jsonObject.optString("backend_request_id", null)
                if (backendRequestId != null) {
                    Log.d("Andromuks", "NetworkUtils: Backend request ID found in message: $backendRequestId")
                }
                // Track last received request_id for ping purposes
                val receivedReqId = jsonObject.optInt("request_id", 0)
                if (receivedReqId != 0) {
                    appViewModel.noteIncomingRequestId(receivedReqId)
                }
                val command = jsonObject.optString("command")
                when (command) {
                    "pong" -> {
                        val requestId = jsonObject.optInt("request_id")
                        Log.i("Andromuks", "PONG JSON: $text")
                        
                        // Handle pong response directly in service
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            WebSocketService.handlePong(requestId)
                        }
                    }
                    "run_id" -> {
                        val data = jsonObject.optJSONObject("data")
                        val runId = data?.optString("run_id", "")
                        val vapidKey = data?.optString("vapid_key", "")
                        Log.d("Andromuks", "NetworkUtils: Received run_id: $runId, vapid_key: ${vapidKey?.take(20)}...")
                        Log.d("Andromuks", "NetworkUtils: DEBUG - Full data object: ${data?.toString()}")
                        Log.d("Andromuks", "NetworkUtils: DEBUG - runId type: ${runId?.javaClass?.simpleName}, length: ${runId?.length}")
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                viewModel.handleRunId(runId ?: "", vapidKey ?: "")
                            }
                        }
                    }
                    "sync_complete" -> {
                        Log.d("Andromuks", "NetworkUtils: Processing sync_complete message with async parsing")
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            val registeredViewModels = WebSocketService.getRegisteredViewModels()
                            for (index in registeredViewModels.indices) {
                                val viewModel = registeredViewModels[index]
                                // JSONObject is not thread-safe, so provide an isolated copy per ViewModel
                                val clonedJson = if (index == registeredViewModels.lastIndex) {
                                    // Last ViewModel can reuse original to avoid extra allocation
                                    jsonObject
                                } else {
                                    JSONObject(jsonObject.toString())
                                }
                                viewModel.updateRoomsFromSyncJsonAsync(clonedJson)
                            }
                        }
                    }
                    "init_complete" -> {
                        Log.d("Andromuks", "NetworkUtils: Received init_complete - initialization finished")
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.Main) {
                            Log.d("Andromuks", "NetworkUtils: Calling onInitComplete on main thread")
                            for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                viewModel.onInitComplete()
                            }
                        }
                    }
                    "client_state" -> {
                        val data = jsonObject.optJSONObject("data")
                        val userId = data?.optString("user_id")
                        val deviceId = data?.optString("device_id")
                        val hs = data?.optString("homeserver_url")
                        Log.d("Andromuks", "NetworkUtils: client_state user=$userId device=$deviceId homeserver=$hs")
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                viewModel.handleClientState(userId, deviceId, hs)
                            }
                        }
                    }
                    "image_auth_token" -> {
                        val token = jsonObject.optString("data", "")
                        Log.d("Andromuks", "NetworkUtils: image_auth_token received: ${token.isNotBlank()}")
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                viewModel.updateImageAuthToken(token)
                            }
                        }
                    }
                    "response" -> {
                        val requestId = jsonObject.optInt("request_id")
                        val data = jsonObject.opt("data")
                        Log.d("Andromuks", "NetworkUtils: Routing response, requestId=$requestId, dataType=${data?.javaClass?.simpleName}")
                        
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                viewModel.handleResponse(requestId, data ?: Any())
                            }
                        }
                    }
                    "send_complete" -> {
                        val data = jsonObject.optJSONObject("data")
                        val event = data?.optJSONObject("event")
                        Log.d("Andromuks", "NetworkUtils: Processing send_complete, hasEvent=${event != null}")
                        if (event != null) {
                            val eventType = event.optString("type", "unknown")
                            val eventId = event.optString("event_id", "unknown")
                            Log.d("Andromuks", "NetworkUtils: send_complete event - type: $eventType, eventId: $eventId")
                        }
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            if (event != null) {
                                for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                    // Use the dedicated processSendCompleteEvent function
                                    viewModel.processSendCompleteEvent(event)
                                }
                            }
                        }
                    }
                    "error" -> {
                        val requestId = jsonObject.optInt("request_id")
                        val errorMessage = jsonObject.optString("data", "Unknown error")
                        Log.d("Andromuks", "NetworkUtils: Received error for requestId=$requestId: $errorMessage")
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                viewModel.handleError(requestId, errorMessage)
                            }
                        }
                    }
                    "typing" -> {
                        val data = jsonObject.optJSONObject("data")
                        val roomId = data?.optString("room_id")
                        val userIds = mutableListOf<String>()
                        val userIdsArray = data?.optJSONArray("user_ids")
                        if (userIdsArray != null) {
                            for (i in 0 until userIdsArray.length()) {
                                userIdsArray.optString(i)?.let { userIds.add(it) }
                            }
                        }
                        Log.d("Andromuks", "NetworkUtils: Received typing event for room=$roomId, users=$userIds")
                        // PHASE 4: Distribute to all registered ViewModels
                        WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                            for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                viewModel.updateTypingUsers(roomId ?: "", userIds)
                            }
                        }
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d("Andromuks", "NetworkUtils: WebSocket ByteMessage received (${bytes.size} bytes)")
            
            try {
                if (streamingDecompressor != null) {
                    // Use streaming decompressor for stateful DEFLATE decompression
                    streamingDecompressor!!.write(bytes.toByteArray())
                    val decompressedText = streamingDecompressor!!.readAvailable()
                    
                    if (decompressedText != null) {
                        Log.d("Andromuks", "NetworkUtils: Decompressed message: $decompressedText")
                        
                        // Handle multiple JSON objects that may be concatenated in one frame
                        val jsonObjects = parseMultipleJsonObjects(decompressedText)
                        
                        for (jsonObject in jsonObjects) {
                            // Debug: Check if this message contains backend request ID
                            val backendRequestId = jsonObject.optString("backend_request_id", null)
                            if (backendRequestId != null) {
                                Log.d("Andromuks", "NetworkUtils: Backend request ID found in compressed message: $backendRequestId")
                            }
                            
                            // Track last received request_id for ping purposes
                            val receivedReqId = jsonObject.optInt("request_id", 0)
                            if (receivedReqId != 0) {
                                appViewModel.noteIncomingRequestId(receivedReqId)
                            }
                            val command = jsonObject.optString("command")
                            when (command) {
                                "pong" -> {
                                    val requestId = jsonObject.optInt("request_id")
                                    Log.i("Andromuks", "PONG JSON (compressed): $decompressedText")
                                    
                                    // Handle pong response directly in service
                                    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                                        WebSocketService.handlePong(requestId)
                                    }
                                }
                                "run_id" -> {
                                    val data = jsonObject.optJSONObject("data")
                                    val runId = data?.optString("run_id", "")
                                    val vapidKey = data?.optString("vapid_key", "")
                                    Log.d("Andromuks", "NetworkUtils: Received compressed run_id: $runId, vapid_key: ${vapidKey?.take(20)}...")
                                    Log.d("Andromuks", "NetworkUtils: DEBUG - Full compressed data object: ${data?.toString()}")
                                    Log.d("Andromuks", "NetworkUtils: DEBUG - compressed runId type: ${runId?.javaClass?.simpleName}, length: ${runId?.length}")
                                    // PHASE 4: Distribute to all registered ViewModels
                                    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                                        for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                            viewModel.handleRunId(runId ?: "", vapidKey ?: "")
                                        }
                                    }
                                }
                                "sync_complete" -> {
                                    Log.d("Andromuks", "NetworkUtils: Processing compressed sync_complete message with async parsing")
                                    // PHASE 4: Distribute to all registered ViewModels
                                    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                                        for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                            viewModel.updateRoomsFromSyncJsonAsync(jsonObject)
                                        }
                                    }
                                }
                                "init_complete" -> {
                                    Log.d("Andromuks", "NetworkUtils: Received compressed init_complete - initialization finished")
                                    // PHASE 4: Distribute to all registered ViewModels
                                    WebSocketService.getServiceScope().launch(Dispatchers.Main) {
                                        Log.d("Andromuks", "NetworkUtils: Calling onInitComplete on main thread")
                                        for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                            viewModel.onInitComplete()
                                        }
                                    }
                                }
                                "client_state" -> {
                                    val data = jsonObject.optJSONObject("data")
                                    val userId = data?.optString("user_id")
                                    val deviceId = data?.optString("device_id")
                                    val hs = data?.optString("homeserver_url")
                                    Log.d("Andromuks", "NetworkUtils: client_state user=$userId device=$deviceId homeserver=$hs")
                                    // PHASE 4: Distribute to all registered ViewModels
                                    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                                        for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                            viewModel.handleClientState(userId, deviceId, hs)
                                        }
                                    }
                                }
                                "image_auth_token" -> {
                                    val token = jsonObject.optString("data", "")
                                    Log.d("Andromuks", "NetworkUtils: Received image auth token")
                                    // PHASE 4: Distribute to all registered ViewModels
                                    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                                        for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                            viewModel.updateImageAuthToken(token)
                                        }
                                    }
                                }
                                 "typing" -> {
                                     val data = jsonObject.optJSONObject("data")
                                     val roomId = data?.optString("room_id")
                                     val userIds = data?.optJSONArray("user_ids")?.let { array ->
                                         (0 until array.length()).mapNotNull { array.optString(it).takeIf { it.isNotEmpty() } }
                                     } ?: emptyList()
                                     Log.d("Andromuks", "NetworkUtils: Received compressed typing event for room=$roomId, users=$userIds")
                                     // PHASE 4: Distribute to all registered ViewModels
                                     WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                                         for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                             viewModel.updateTypingUsers(roomId ?: "", userIds)
                                         }
                                     }
                                 }
                                "response" -> {
                                    val requestId = jsonObject.optInt("request_id")
                                    val data = jsonObject.opt("data")
                                    Log.d("Andromuks", "NetworkUtils: Routing compressed response, requestId=$requestId, dataType=${data?.javaClass?.simpleName}")
                                    
                                    // PHASE 4: Distribute to all registered ViewModels
                                    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
                                        for (viewModel in WebSocketService.getRegisteredViewModels()) {
                                            viewModel.handleResponse(requestId, data ?: Any())
                                        }
                                    }
                                }
                             }
                         }
                    }
                }
            } catch (e: Exception) {
                Log.e("Andromuks", "NetworkUtils: Failed to decompress WebSocket message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("Andromuks", "NetworkUtils: WebSocket Closing ($code): $reason")
            
            // Determine the close reason
            val closeReason = when (code) {
                1000 -> "Normal closure ($code)"
                1001 -> "Server going away ($code)"
                1006 -> "Abnormal closure ($code)"
                else -> "Close code $code: $reason"
            }
            
            // Clear WebSocket connection in both AppViewModel and service
            appViewModel.clearWebSocket(closeReason)
            WebSocketService.clearWebSocket(closeReason)
            
            // Trigger reconnection for abnormal closures
            when (code) {
                1000 -> {
                    // Normal closure - don't reconnect
                    Log.d("Andromuks", "NetworkUtils: Normal WebSocket closure")
                }
                1001 -> {
                    // Going away - reconnect after delay
                    Log.w("Andromuks", "NetworkUtils: Server going away, scheduling reconnection")
                    appViewModel.scheduleReconnection(reason = "Server going away (1001)")
                }
                1006 -> {
                    // Abnormal closure (no close frame) - immediate reconnect attempt
                    Log.e("Andromuks", "NetworkUtils: Abnormal WebSocket closure (1006)")
                    appViewModel.scheduleReconnection(reason = "Abnormal closure (1006)")
                }
                else -> {
                    // Other errors - reconnect with backoff
                    Log.w("Andromuks", "NetworkUtils: WebSocket closed with code $code: $reason")
                    appViewModel.scheduleReconnection(reason = closeReason)
                }
            }
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("Andromuks", "NetworkUtils: WebSocket Closed ($code): $reason")
            streamingDecompressor?.close()
            appViewModel.clearWebSocket("WebSocket closed ($code)")
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


/**
 * Parse multiple JSON objects from a single string
 * The server may concatenate multiple JSON objects in one frame
 */
fun parseMultipleJsonObjects(jsonText: String): List<JSONObject> {
    val jsonObjects = mutableListOf<JSONObject>()
    var currentPos = 0
    var braceCount = 0
    var startPos = 0
    var inString = false
    var escapeNext = false
    
    for (i in jsonText.indices) {
        val char = jsonText[i]
        
        if (escapeNext) {
            escapeNext = false
            continue
        }
        
        if (char == '\\') {
            escapeNext = true
            continue
        }
        
        if (char == '"' && !escapeNext) {
            inString = !inString
            continue
        }
        
        if (!inString) {
            when (char) {
                '{' -> {
                    if (braceCount == 0) {
                        startPos = i
                    }
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        // Found complete JSON object
                        val jsonStr = jsonText.substring(startPos, i + 1)
                        try {
                            val jsonObject = JSONObject(jsonStr)
                            jsonObjects.add(jsonObject)
                            Log.d("Andromuks", "NetworkUtils: Parsed JSON object: ${jsonObject.optString("command", "unknown")}")
                        } catch (e: Exception) {
                            Log.w("Andromuks", "NetworkUtils: Failed to parse JSON object: $jsonStr", e)
                        }
                    }
                }
            }
        }
    }
    
    return jsonObjects
}
