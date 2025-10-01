package net.vrkknn.andromuks.database.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.database.repository.MatrixRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response

/**
 * Manages WebSocket reconnection with database-backed sync state
 * 
 * Handles reconnection using run_id and last_received_id for efficient delta sync
 */
class ReconnectionManager(
    private val repository: MatrixRepository,
    private val scope: CoroutineScope
) {
    
    private var onRestartWebSocket: (() -> Unit)? = null
    
    fun setRestartCallback(callback: () -> Unit) {
        onRestartWebSocket = callback
    }
    
    /**
     * Connect to WebSocket with sync parameters for reconnection
     */
    fun connectToWebsocket(
        baseUrl: String,
        client: OkHttpClient,
        token: String,
        onWebSocketReady: (WebSocket) -> Unit,
        onMessage: (String) -> Unit
    ) {
        Log.d("ReconnectionManager", "Connecting to WebSocket with sync parameters")
        
        scope.launch(Dispatchers.IO) {
            val syncParams = repository.getSyncParameters()
            val wsUrl = buildWebSocketUrl(baseUrl, syncParams)
            
            Log.d("ReconnectionManager", "WebSocket URL: $wsUrl")
            
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Cookie", "gomuks_auth=$token")
                .build()
            
            val websocketListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("ReconnectionManager", "WebSocket opened: ${response.message}")
                    onWebSocketReady(webSocket)
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("ReconnectionManager", "WebSocket message: $text")
                    onMessage(text)
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("ReconnectionManager", "WebSocket closing ($code): $reason")
                    if (code != 1000 && code != 1001) {
                        Log.w("ReconnectionManager", "WebSocket closed unexpectedly, will reconnect")
                        scheduleReconnection()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("ReconnectionManager", "WebSocket failure", t)
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
            Log.d("ReconnectionManager", "Scheduling reconnection in 5 seconds")
            kotlinx.coroutines.delay(5_000)
            onRestartWebSocket?.invoke()
        }
    }
    
    /**
     * Update sync state with new run ID
     */
    suspend fun updateRunId(runId: String) {
        scope.launch(Dispatchers.IO) {
            repository.updateSyncState(runId, 0)
            Log.d("ReconnectionManager", "Updated run ID: $runId")
        }
    }
    
    /**
     * Update sync state with last received ID
     */
    suspend fun updateLastReceivedId(lastReceivedId: Long) {
        scope.launch(Dispatchers.IO) {
            repository.updateSyncState(null, lastReceivedId)
            Log.d("ReconnectionManager", "Updated last received ID: $lastReceivedId")
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
