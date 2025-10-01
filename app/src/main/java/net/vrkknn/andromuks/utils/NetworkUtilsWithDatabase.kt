package net.vrkknn.andromuks.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.database.repository.MatrixRepository
import net.vrkknn.andromuks.database.websocket.WebSocketManager
import net.vrkknn.andromuks.database.websocket.ReconnectionManager
import net.vrkknn.andromuks.TimelineEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Enhanced NetworkUtils with database integration
 * 
 * Provides WebSocket connection management with persistent storage
 * and efficient reconnection using run_id and last_received_id.
 */
class NetworkUtilsWithDatabase(
    private val repository: MatrixRepository,
    private val scope: CoroutineScope
) {
    
    private val webSocketManager = WebSocketManager(repository, scope)
    private val reconnectionManager = ReconnectionManager(repository, scope)
    
    /**
     * Connect to WebSocket with database-backed reconnection
     */
    fun connectToWebsocket(
        url: String,
        client: OkHttpClient,
        token: String,
        onWebSocketReady: (WebSocket) -> Unit,
        onMessage: (String) -> Unit
    ) {
        Log.d("NetworkUtilsWithDatabase", "Connecting to WebSocket with database integration")
        
        scope.launch(Dispatchers.IO) {
            val syncParams = repository.getSyncParameters()
            val wsUrl = buildWebSocketUrl(url, syncParams)
            
            Log.d("NetworkUtilsWithDatabase", "WebSocket URL: $wsUrl")
            
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Cookie", "gomuks_auth=$token")
                .build()
            
            val websocketListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("NetworkUtilsWithDatabase", "WebSocket opened: ${response.message}")
                    onWebSocketReady(webSocket)
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("NetworkUtilsWithDatabase", "WebSocket message: $text")
                    onMessage(text)
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("NetworkUtilsWithDatabase", "WebSocket closing ($code): $reason")
                    if (code != 1000 && code != 1001) {
                        Log.w("NetworkUtilsWithDatabase", "WebSocket closed unexpectedly, will reconnect")
                        scheduleReconnection()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("NetworkUtilsWithDatabase", "WebSocket failure", t)
                    scheduleReconnection()
                }
            }
            
            client.newWebSocket(request, websocketListener)
        }
    }
    
    /**
     * Build WebSocket URL with sync parameters
     */
    private fun buildWebSocketUrl(baseUrl: String, syncParams: Pair<String?, Long>?): String {
        val trimmedUrl = trimWebsocketHost(baseUrl)
        
        return if (syncParams != null && syncParams.first != null) {
            val (runId, lastReceivedId) = syncParams
            "$trimmedUrl?run_id=$runId&last_received_id=$lastReceivedId"
        } else {
            trimmedUrl
        }
    }
    
    /**
     * Trim WebSocket host from URL
     */
    private fun trimWebsocketHost(url: String): String {
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
     * Schedule reconnection after connection loss
     */
    private fun scheduleReconnection() {
        scope.launch(Dispatchers.IO) {
            Log.d("NetworkUtilsWithDatabase", "Scheduling reconnection in 5 seconds")
            kotlinx.coroutines.delay(5_000)
            // This will be handled by the main AppViewModel
        }
    }
    
    /**
     * Handle WebSocket message with database integration
     */
    suspend fun handleWebSocketMessage(text: String) {
        try {
            val jsonObject = JSONObject(text)
            val command = jsonObject.optString("command")
            val requestId = jsonObject.optInt("request_id", 0)
            
            // Update sync state with request ID
            if (requestId != 0) {
                repository.updateSyncState(null, requestId.toLong())
            }
            
            when (command) {
                "sync_complete" -> {
                    webSocketManager.handleSyncComplete(jsonObject)
                }
                "init_complete" -> {
                    webSocketManager.handleInitComplete()
                }
                "client_state" -> {
                    val data = jsonObject.optJSONObject("data")
                    val userId = data?.optString("user_id")
                    val deviceId = data?.optString("device_id")
                    val homeserverUrl = data?.optString("homeserver_url")
                    webSocketManager.handleClientState(userId, deviceId, homeserverUrl)
                }
                "image_auth_token" -> {
                    val token = jsonObject.optString("data", "")
                    webSocketManager.handleImageAuthToken(token)
                }
                "send_complete" -> {
                    webSocketManager.handleSendComplete(jsonObject)
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
                    webSocketManager.handleTypingIndicator(roomId ?: "", userIds)
                }
                "error" -> {
                    val errorMessage = jsonObject.optString("data", "Unknown error")
                    webSocketManager.handleError(requestId, errorMessage)
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkUtilsWithDatabase", "Error handling WebSocket message", e)
        }
    }
    
    /**
     * Update sync state with new run ID
     */
    suspend fun updateRunId(runId: String) {
        scope.launch(Dispatchers.IO) {
            repository.updateSyncState(runId, 0)
            Log.d("NetworkUtilsWithDatabase", "Updated run ID: $runId")
        }
    }
    
    /**
     * Update sync state with last received ID
     */
    suspend fun updateLastReceivedId(lastReceivedId: Long) {
        scope.launch(Dispatchers.IO) {
            repository.updateSyncState(null, lastReceivedId)
            Log.d("NetworkUtilsWithDatabase", "Updated last received ID: $lastReceivedId")
        }
    }
    
    /**
     * Handle ping response and update sync state
     */
    suspend fun handlePingResponse(requestId: Int) {
        if (requestId != 0) {
            updateLastReceivedId(requestId.toLong())
        }
    }
}
