package net.vrkknn.andromuks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "websocket_service_channel"
        private const val CHANNEL_NAME = "WebSocket Service"
        private const val PING_INTERVAL_MS = 30000L // 30 seconds
        private const val RECONNECT_DELAY_MS = 5000L // 5 seconds
    }

    private val binder = WebSocketBinder()
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    
    // WebSocket connection parameters
    private var websocketUrl: String? = null
    private var authToken: String? = null
    private var appViewModel: AppViewModel? = null
    
    // Connection state
    private var isConnected = false
    private var shouldReconnect = true

    inner class WebSocketBinder : Binder() {
        fun getService(): WebSocketService = this@WebSocketService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d("WebSocketService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("WebSocketService", "Service started")
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Initialize WebSocket connection
        initializeWebSocket()
        
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d("WebSocketService", "Service destroyed")
        
        shouldReconnect = false
        disconnectWebSocket()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains WebSocket connection for real-time updates"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Andromuks")
            .setContentText("Maintaining connection...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun initializeWebSocket() {
        // Get connection parameters from AppViewModel
        // For now, we'll need to get these from the app's current state
        // This will be enhanced when we integrate with the main app
        Log.d("WebSocketService", "Initializing WebSocket connection")
        
        // Create OkHttpClient
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun setConnectionParameters(url: String, token: String, viewModel: AppViewModel) {
        websocketUrl = url
        authToken = token
        appViewModel = viewModel
        
        Log.d("WebSocketService", "Connection parameters set, connecting...")
        connectWebSocket()
    }

    private fun connectWebSocket() {
        val url = websocketUrl ?: return
        val token = authToken ?: return
        
        Log.d("WebSocketService", "Connecting to WebSocket: $url")
        
        // Build WebSocket URL with reconnection parameters if available
        val runId = appViewModel?.getCurrentRunId() ?: ""
        val lastReceivedId = appViewModel?.getLastReceivedId() ?: 0
        
        val finalUrl = if (runId.isNotEmpty() && lastReceivedId != 0) {
            // Reconnecting with run_id and last_received_id
            Log.d("WebSocketService", "Reconnecting with run_id: $runId, last_received_id: $lastReceivedId")
            "$url?run_id=$runId&last_received_id=$lastReceivedId"
        } else {
            // First connection
            Log.d("WebSocketService", "First connection to websocket")
            url
        }
        
        val request = Request.Builder()
            .url(finalUrl)
            .addHeader("Cookie", "gomuks_auth=$token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocketService", "WebSocket connected")
                isConnected = true
                this@WebSocketService.webSocket = webSocket
                
                // Notify AppViewModel
                appViewModel?.setWebSocket(webSocket)
                
                // Start ping loop
                startPingLoop()
                
                // Update notification
                updateNotification("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocketService", "WebSocket message received: $text")
                
                // Process the message and forward to AppViewModel
                processWebSocketMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocketService", "WebSocket closing: $code - $reason")
                isConnected = false
                stopPingLoop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocketService", "WebSocket closed: $code - $reason")
                isConnected = false
                stopPingLoop()
                
                // Attempt to reconnect if service should stay connected
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketService", "WebSocket failed", t)
                isConnected = false
                stopPingLoop()
                
                // Attempt to reconnect if service should stay connected
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        }

        okHttpClient?.newWebSocket(request, listener)
    }

    private fun processWebSocketMessage(text: String) {
        try {
            val jsonObject = JSONObject(text)
            val command = jsonObject.optString("command")
            
            // Track last received request_id for sync_complete messages
            val receivedReqId = jsonObject.optInt("request_id", 0)
            if (receivedReqId != 0) {
                appViewModel?.noteIncomingRequestId(receivedReqId)
            }
            
            when (command) {
                "run_id" -> {
                    Log.d("WebSocketService", "Processing run_id")
                    val data = jsonObject.optJSONObject("data")
                    val runId = data?.optString("run_id", "")
                    val vapidKey = data?.optString("vapid_key", "")
                    Log.d("WebSocketService", "Received run_id: $runId, vapid_key: ${vapidKey?.take(20)}...")
                    appViewModel?.let { viewModel ->
                        serviceScope.launch(Dispatchers.Main) {
                            viewModel.handleRunId(runId ?: "", vapidKey ?: "")
                        }
                    }
                }
                "sync_complete" -> {
                    Log.d("WebSocketService", "Processing sync_complete")
                    // Forward sync data to AppViewModel
                    appViewModel?.let { viewModel ->
                        serviceScope.launch(Dispatchers.Main) {
                            viewModel.updateRoomsFromSyncJson(jsonObject)
                        }
                    }
                }
                "ping" -> {
                    // Respond to ping with pong
                    val requestId = jsonObject.optInt("request_id", 0)
                    if (requestId != 0) {
                        sendPong(requestId)
                    }
                }
                else -> {
                    // Forward other commands to AppViewModel
                    appViewModel?.let { viewModel ->
                        serviceScope.launch(Dispatchers.Main) {
                            // Process other WebSocket messages as needed
                            Log.d("WebSocketService", "Forwarding command: $command")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocketService", "Error processing WebSocket message", e)
        }
    }

    private fun startPingLoop() {
        stopPingLoop() // Stop any existing ping loop
        
        pingJob = serviceScope.launch {
            while (isActive && isConnected) {
                delay(PING_INTERVAL_MS)
                if (isConnected) {
                    sendPing()
                }
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun sendPing() {
        val ws = webSocket ?: return
        
        try {
            val requestId = appViewModel?.getNextRequestId() ?: 0
            val lastReceivedId = appViewModel?.getLastReceivedId() ?: 0
            
            val pingData = JSONObject().apply {
                put("command", "ping")
                put("request_id", requestId)
                put("data", JSONObject().apply {
                    put("last_received_id", lastReceivedId)
                })
            }
            
            ws.send(pingData.toString())
            Log.d("WebSocketService", "Ping sent with request_id: $requestId, last_received_id: $lastReceivedId")
        } catch (e: Exception) {
            Log.e("WebSocketService", "Error sending ping", e)
        }
    }

    private fun sendPong(requestId: Int) {
        val ws = webSocket ?: return
        
        try {
            val pongData = JSONObject().apply {
                put("command", "pong")
                put("request_id", requestId)
            }
            
            ws.send(pongData.toString())
            Log.d("WebSocketService", "Pong sent for request_id: $requestId")
        } catch (e: Exception) {
            Log.e("WebSocketService", "Error sending pong", e)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldReconnect && !isConnected) {
                Log.d("WebSocketService", "Attempting to reconnect...")
                connectWebSocket()
            }
        }
    }

    private fun disconnectWebSocket() {
        shouldReconnect = false
        stopPingLoop()
        reconnectJob?.cancel()
        
        webSocket?.close(1000, "Service stopping")
        webSocket = null
        isConnected = false
        
        Log.d("WebSocketService", "WebSocket disconnected")
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Andromuks")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun isWebSocketConnected(): Boolean = isConnected
}
