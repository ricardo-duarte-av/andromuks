package net.vrkknn.andromuks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * WebSocketService - Foreground service that maintains app process
 * 
 * This service shows a persistent notification to prevent Android from killing
 * the app process. The actual WebSocket connection is managed by NetworkUtils
 * and AppViewModel, not by this service.
 * 
 * The notification displays real-time connection health:
 * - Lag: Ping/pong round-trip time
 * - Last message: Time since last sync_complete
 */
class WebSocketService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "websocket_service_channel"
        private const val CHANNEL_NAME = "WebSocket Service"
        private var instance: WebSocketService? = null
        
        // Service-scoped coroutine scope for background processing
        private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        // Ping/Pong state management
        private var pingJob: Job? = null
        private var pongTimeoutJob: Job? = null
        private var lastPingRequestId: Int = 0
        private var lastPingTimestamp: Long = 0
        private var isAppVisible = false
        
        // Network optimization variables
        private var networkLatencyMs = 1000L
        private var connectionStability = 1.0f
        private var consecutiveTimeouts = 0
        private val BASE_TIMEOUT_MS = 5000L
        private val MAX_TIMEOUT_MS = 15000L
        private val MIN_TIMEOUT_MS = 3000L
        
        // Callback for sending WebSocket commands
        private var webSocketSendCallback: ((String, Int, Map<String, Any>) -> Boolean)? = null
        
        // Callback for triggering reconnection
        private var reconnectionCallback: ((String) -> Unit)? = null
        
        // Callback for offline mode management
        private var offlineModeCallback: ((Boolean) -> Unit)? = null
        
        // Network monitoring
        private var networkMonitor: NetworkMonitor? = null
        private var isCurrentlyConnected = false
        private var currentNetworkType: NetworkType = NetworkType.NONE
        
        // WebSocket connection management
        private var webSocket: WebSocket? = null
        private var isWebSocketConnected = false
        
        // Reconnection state management
        private var currentRunId: String = ""
        private var lastReceivedSyncId: Int = 0
        private var vapidKey: String = ""
        
        // Connection health tracking
        private var lastSyncTimestamp: Long = 0
        private var lastKnownLagMs: Long? = null
        
        // Reconnection logic state
        private var reconnectionAttempts = 0
        private var reconnectionJob: Job? = null
        private val BASE_RECONNECTION_DELAY_MS = 3000L // 3 seconds - give network time to stabilize
        private val MAX_RECONNECTION_DELAY_MS = 30000L // 30 seconds - shorter max delay
        private val MAX_RECONNECTION_ATTEMPTS = 5 // Give up after 5 attempts - less aggressive
        
        // Network change debouncing
        private var lastNetworkChangeTime = 0L
        private val NETWORK_CHANGE_DEBOUNCE_MS = 5000L // 5 seconds between network change reconnections
        
        /**
         * Get the service-scoped coroutine scope for background processing
         * This scope continues running even when the app is backgrounded
         */
        fun getServiceScope(): CoroutineScope = serviceScope
        
        /**
         * Update notification from anywhere in the app
         */
        fun updateNotification(lag: Long, lastSyncTime: Long) {
            instance?.updateNotificationText(lag, lastSyncTime)
        }
        
        /**
         * Update notification with connection status
         */
        fun updateConnectionStatus(isConnected: Boolean, lagMs: Long? = null, lastSyncTimestamp: Long? = null) {
            instance?.updateConnectionStatus(isConnected, lagMs, lastSyncTimestamp)
        }
        
        /**
         * Start ping/pong loop for connection health monitoring
         */
        fun startPingLoop() {
            stopPingLoop()
            pingJob = serviceScope.launch {
                while (isActive) {
                    val interval = getPingInterval()
                    android.util.Log.d("WebSocketService", "Ping interval: ${interval}ms (app visible: $isAppVisible)")
                    delay(interval)
                    
                    // Send ping command
                    sendPing()
                }
            }
            android.util.Log.d("WebSocketService", "Ping loop started")
        }
        
        /**
         * Stop ping/pong loop
         */
        fun stopPingLoop() {
            pingJob?.cancel()
            pongTimeoutJob?.cancel()
            pingJob = null
            pongTimeoutJob = null
            android.util.Log.d("WebSocketService", "Ping loop stopped")
        }
        
        /**
         * Set app visibility for adaptive ping intervals
         */
        fun setAppVisibility(visible: Boolean) {
            isAppVisible = visible
            android.util.Log.d("WebSocketService", "App visibility changed to: $visible")
        }
        
        
        /**
         * Set callback for sending WebSocket commands
         */
        fun setWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
            webSocketSendCallback = callback
        }
        
        /**
         * Set callback for triggering reconnection
         */
        fun setReconnectionCallback(callback: (String) -> Unit) {
            reconnectionCallback = callback
        }
        
        /**
         * Set callback for offline mode management
         */
        fun setOfflineModeCallback(callback: (Boolean) -> Unit) {
            offlineModeCallback = callback
        }
        
        /**
         * Handle pong response
         */
        fun handlePong(requestId: Int) {
            if (requestId == lastPingRequestId) {
                pongTimeoutJob?.cancel()
                pongTimeoutJob = null
                
                val lagMs = System.currentTimeMillis() - lastPingTimestamp
                lastKnownLagMs = lagMs
                android.util.Log.d("WebSocketService", "Pong received, lag: ${lagMs}ms")
                
                // Update network metrics
                updateNetworkMetrics(lagMs)
                
                // Update notification with lag and last sync timestamp
                updateConnectionStatus(true, lagMs, lastSyncTimestamp)
            }
        }
        
        /**
         * Get appropriate ping interval based on app visibility
         */
        private fun getPingInterval(): Long {
            return if (isAppVisible) {
                15_000L  // 15 seconds - responsive when user is actively using app
            } else {
                60_000L  // 60 seconds - battery efficient when in background
            }
        }
        
        /**
         * Send ping command
         */
        private fun sendPing() {
            val currentWebSocket = webSocket
            if (currentWebSocket == null) {
                android.util.Log.w("WebSocketService", "Cannot send ping - WebSocket not available")
                return
            }
            
            val reqId = lastPingRequestId + 1
            lastPingRequestId = reqId
            lastPingTimestamp = System.currentTimeMillis()
            
            val data = mapOf("last_received_id" to lastReceivedSyncId)
            
            android.util.Log.d("WebSocketService", "Sending ping (requestId: $reqId, lastReceivedSyncId: $lastReceivedSyncId)")
            
            // Send ping directly via WebSocket
            try {
                val json = org.json.JSONObject()
                json.put("command", "ping")
                json.put("request_id", reqId)
                json.put("data", org.json.JSONObject(data))
                val jsonString = json.toString()
                
                val success = currentWebSocket.send(jsonString)
                if (success) {
                    android.util.Log.d("WebSocketService", "Ping sent successfully")
                    // Start timeout for this ping
                    startPongTimeout(reqId)
                } else {
                    android.util.Log.w("WebSocketService", "Failed to send ping - WebSocket send returned false")
                }
            } catch (e: Exception) {
                android.util.Log.e("WebSocketService", "Failed to send ping", e)
            }
        }
        
        /**
         * Start pong timeout for a ping
         */
        private fun startPongTimeout(pingRequestId: Int) {
            pongTimeoutJob?.cancel()
            pongTimeoutJob = serviceScope.launch {
                val smartTimeout = calculateSmartTimeout()
                delay(smartTimeout)
                android.util.Log.w("WebSocketService", "Pong timeout for ping $pingRequestId (timeout: ${smartTimeout}ms)")
                
                // Update timeout tracking
                consecutiveTimeouts++
                updateConnectionStability(false)
                
                // Trigger reconnection
                triggerReconnection("Ping timeout (${smartTimeout}ms)")
            }
        }
        
        /**
         * Calculate smart timeout based on network conditions
         */
        private fun calculateSmartTimeout(): Long {
            val baseTimeout = BASE_TIMEOUT_MS
            val latencyAdjustment = (networkLatencyMs * 1.5).toLong().coerceAtMost(5000L)
            val stabilityAdjustment = ((1.0f - connectionStability) * 5000f).toLong()
            val consecutiveTimeoutAdjustment = consecutiveTimeouts * 2000L
            
            val smartTimeout = baseTimeout + latencyAdjustment + stabilityAdjustment + consecutiveTimeoutAdjustment
            return smartTimeout.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }
        
        /**
         * Update network metrics based on ping/pong data
         */
        private fun updateNetworkMetrics(lagMs: Long) {
            networkLatencyMs = ((networkLatencyMs * 0.7) + (lagMs * 0.3)).toLong()
            
            val lagDeviation = kotlin.math.abs(lagMs - networkLatencyMs)
            val stabilityFactor = when {
                lagDeviation < 200 -> 0.1f
                lagDeviation < 500 -> 0.05f
                lagDeviation < 1000 -> 0.02f
                else -> 0.01f
            }
            
            connectionStability = (connectionStability * 0.9f + stabilityFactor).coerceIn(0.0f, 1.0f)
        }
        
        /**
         * Update connection stability
         */
        private fun updateConnectionStability(stable: Boolean) {
            if (stable) {
                consecutiveTimeouts = 0
                connectionStability = (connectionStability * 0.9f + 0.1f).coerceIn(0.0f, 1.0f)
            } else {
                connectionStability = (connectionStability * 0.8f).coerceAtLeast(0.1f)
            }
        }
        
        /**
         * Trigger WebSocket reconnection
         */
        private fun triggerReconnection(reason: String) {
            android.util.Log.i("WebSocketService", "Triggering WebSocket reconnection: $reason")
            reconnectionCallback?.invoke(reason)
        }
        
        /**
         * Start network monitoring for immediate reconnection on network changes
         */
        fun startNetworkMonitoring(context: Context) {
            if (networkMonitor != null) {
                android.util.Log.d("WebSocketService", "Network monitor already started")
                return
            }
            
            networkMonitor = NetworkMonitor(
                context = context,
                onNetworkAvailable = {
                    android.util.Log.i("WebSocketService", "Network available - triggering reconnection")
                    // Notify AppViewModel to exit offline mode
                    offlineModeCallback?.invoke(false)
                    triggerReconnection("Network restored")
                },
                onNetworkLost = {
                    android.util.Log.w("WebSocketService", "Network lost - entering offline mode")
                    // Notify AppViewModel to enter offline mode
                    offlineModeCallback?.invoke(true)
                    updateConnectionStatus(false)
                }
            )
            
            networkMonitor?.startMonitoring()
            android.util.Log.d("WebSocketService", "Network monitoring started in service")
        }
        
        /**
         * Stop network monitoring
         */
        fun stopNetworkMonitoring() {
            networkMonitor?.stopMonitoring()
            networkMonitor = null
            android.util.Log.d("WebSocketService", "Network monitoring stopped")
        }
        
        /**
         * Set WebSocket connection
         */
        fun setWebSocket(webSocket: WebSocket) {
            this.webSocket = webSocket
            isWebSocketConnected = true
            
            // Start ping loop
            startPingLoop()
            
            // Update notification with connection status
            updateConnectionStatus(true, null, lastSyncTimestamp)
            
            android.util.Log.i("WebSocketService", "WebSocket connection established in service")
        }
        
        /**
         * Clear WebSocket connection
         */
        fun clearWebSocket() {
            this.webSocket = null
            isWebSocketConnected = false
            
            // Stop ping loop
            stopPingLoop()
            
            // Reset connection health tracking
            lastKnownLagMs = null
            
            // Update notification to show disconnection
            updateConnectionStatus(false)
            
            android.util.Log.d("WebSocketService", "WebSocket connection cleared in service")
        }
        
        /**
         * Check if WebSocket is connected
         */
        fun isWebSocketConnected(): Boolean {
            return isWebSocketConnected && webSocket != null
        }
        
        /**
         * Get WebSocket instance
         */
        fun getWebSocket(): WebSocket? {
            return webSocket
        }
        
        /**
         * Set reconnection state (run_id, last_received_event, vapid_key)
         */
        fun setReconnectionState(runId: String, lastReceivedId: Int, vapidKey: String) {
            currentRunId = runId
            lastReceivedSyncId = lastReceivedId
            this.vapidKey = vapidKey
            android.util.Log.d("WebSocketService", "Updated reconnection state - run_id: $runId, last_received_id: $lastReceivedId")
        }
        
        /**
         * Get current run_id for reconnection
         */
        fun getCurrentRunId(): String = currentRunId
        
        /**
         * Get last received sync ID for reconnection
         */
        fun getLastReceivedSyncId(): Int = lastReceivedSyncId
        
        /**
         * Get VAPID key for push notifications
         */
        fun getVapidKey(): String = vapidKey
        
        /**
         * Update last received sync ID
         */
        fun updateLastReceivedSyncId(syncId: Int) {
            lastReceivedSyncId = syncId
            android.util.Log.d("WebSocketService", "Updated lastReceivedSyncId to: $syncId")
        }
        
        /**
         * Update last sync timestamp when sync_complete is received
         */
        fun updateLastSyncTimestamp() {
            lastSyncTimestamp = System.currentTimeMillis()
            android.util.Log.d("WebSocketService", "Updated lastSyncTimestamp to: $lastSyncTimestamp")
            
            // Update notification with current lag and new sync timestamp
            updateConnectionStatus(true, lastKnownLagMs, lastSyncTimestamp)
        }
        
        /**
         * Clear reconnection state
         */
        fun clearReconnectionState() {
            currentRunId = ""
            lastReceivedSyncId = 0
            vapidKey = ""
            android.util.Log.d("WebSocketService", "Cleared reconnection state")
        }
        
        /**
         * Get reconnection parameters for WebSocket URL construction
         */
        fun getReconnectionParameters(): Triple<String, Int, String> {
            return Triple(currentRunId, lastReceivedSyncId, vapidKey)
        }
        
        /**
         * Reset reconnection state (called on successful connection)
         */
        fun resetReconnectionState() {
            reconnectionAttempts = 0
            reconnectionJob?.cancel()
            reconnectionJob = null
            android.util.Log.d("WebSocketService", "Reset reconnection state (successful connection)")
        }
        
        /**
         * Schedule WebSocket reconnection with exponential backoff and network validation
         */
        fun scheduleReconnection(reason: String) {
            // Cancel any existing reconnection job
            reconnectionJob?.cancel()
            
            // Check if we've exceeded max attempts
            if (reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
                android.util.Log.e("WebSocketService", "Max reconnection attempts ($MAX_RECONNECTION_ATTEMPTS) reached, giving up")
                android.util.Log.e("WebSocketService", "User must manually restart app or wait for network change")
                return
            }
            
            // Calculate delay with exponential backoff
            val delay = kotlin.math.min(
                BASE_RECONNECTION_DELAY_MS * (1 shl reconnectionAttempts), // 2^attempts
                MAX_RECONNECTION_DELAY_MS
            )
            
            reconnectionAttempts++
            
            android.util.Log.w("WebSocketService", "Scheduling reconnection attempt #$reconnectionAttempts in ${delay}ms")
            android.util.Log.w("WebSocketService", "Reason: $reason")
            
            reconnectionJob = serviceScope.launch {
                delay(delay)
                
                if (isActive) {
                    android.util.Log.d("WebSocketService", "Executing reconnection attempt #$reconnectionAttempts")
                    
                    // Validate network connectivity before attempting reconnection
                    val isNetworkValid = validateNetworkConnectivity()
                    if (isNetworkValid) {
                        android.util.Log.i("WebSocketService", "Network validated, proceeding with reconnection")
                        // Trigger reconnection via callback
                        reconnectionCallback?.invoke("Reconnection attempt #$reconnectionAttempts: $reason")
                    } else {
                        android.util.Log.w("WebSocketService", "Network validation failed, skipping reconnection attempt")
                        // Schedule another attempt with longer delay
                        if (reconnectionAttempts < MAX_RECONNECTION_ATTEMPTS) {
                            scheduleReconnection("Network validation failed, retrying")
                        }
                    }
                }
            }
        }
        
        /**
         * Restart WebSocket connection
         */
        fun restartWebSocket(reason: String = "Unknown reason") {
            android.util.Log.d("WebSocketService", "Restarting WebSocket connection - Reason: $reason")
            
            // Clear current connection
            clearWebSocket()
            
            // Trigger reconnection via callback
            reconnectionCallback?.invoke(reason)
        }
        
        /**
         * Cancel any pending reconnection
         */
        fun cancelReconnection() {
            reconnectionJob?.cancel()
            reconnectionJob = null
            android.util.Log.d("WebSocketService", "Cancelled pending reconnection")
        }
        
        /**
         * Update network type and refresh notification
         */
        private fun updateServiceNetworkType(networkType: NetworkType) {
            currentNetworkType = networkType
            android.util.Log.d("WebSocketService", "Network type updated to: $networkType")
            
            // Update notification with new network type
            updateConnectionStatus(isWebSocketConnected)
        }
        
        /**
         * Validate network connectivity by testing a simple HTTP request
         */
        private suspend fun validateNetworkConnectivity(): Boolean {
            return try {
                // Simple connectivity test - just check if we can reach the network
                val connectivityManager = instance?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = connectivityManager?.activeNetwork
                val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                
                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                
                android.util.Log.d("WebSocketService", "Network validation: hasInternet=$hasInternet, isValidated=$isValidated")
                hasInternet && isValidated
            } catch (e: Exception) {
                android.util.Log.w("WebSocketService", "Network validation failed", e)
                false
            }
        }
    }
    
    /**
     * Network monitoring class for detecting network changes
     */
    private class NetworkMonitor(
        private val context: Context,
        private val onNetworkAvailable: () -> Unit,
        private val onNetworkLost: () -> Unit
    ) {
        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private var networkCallback: ConnectivityManager.NetworkCallback? = null
        private var isCurrentlyConnected = false
        private var lastNetworkType: NetworkType = NetworkType.NONE
        
        companion object {
            private const val TAG = "WebSocketService.NetworkMonitor"
        }
        
        /**
         * Start monitoring network changes
         */
        fun startMonitoring() {
            if (networkCallback != null) {
                Log.d(TAG, "Already monitoring network changes")
                return
            }
            
            // Check initial connectivity state
            isCurrentlyConnected = isNetworkAvailable()
            val initialNetworkType = getInitialNetworkType()
            currentNetworkType = initialNetworkType
            Log.d(TAG, "Initial network state: connected=$isCurrentlyConnected, type=$initialNetworkType")
            
            // Register network callback for connectivity changes
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    if (!isCurrentlyConnected) {
                        isCurrentlyConnected = true
                        Log.i(TAG, "Network connection restored - triggering reconnect")
                        onNetworkAvailable()
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    // Check if we still have other networks available
                    if (!isNetworkAvailable()) {
                        isCurrentlyConnected = false
                        Log.w(TAG, "All networks lost - connection unavailable")
                        onNetworkLost()
                    } else {
                        Log.d(TAG, "Network lost but other networks still available")
                    }
                }
                
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val transportType = getTransportType(networkCapabilities)
                    
                    Log.d(TAG, "Network capabilities changed - Internet: $hasInternet, Validated: $isValidated, Transport: $transportType")
                    
                    // Detect network type changes (WiFi ↔ 4G) with debouncing
                    if (hasInternet && isValidated) {
                        val currentType = getNetworkType(networkCapabilities)
                        val currentTime = System.currentTimeMillis()
                        
                        if (currentType != lastNetworkType) {
                            // Check if enough time has passed since last network change
                            if (currentTime - lastNetworkChangeTime > NETWORK_CHANGE_DEBOUNCE_MS) {
                                Log.i(TAG, "Network type changed from $lastNetworkType to $currentType - scheduling reconnection")
                                lastNetworkType = currentType
                                lastNetworkChangeTime = currentTime
                                // Update service network type
                                updateServiceNetworkType(currentType)
                                onNetworkAvailable() // Trigger reconnection on network type change
                            } else {
                                Log.d(TAG, "Network type changed but debouncing - ignoring rapid change")
                            }
                        } else if (!isCurrentlyConnected) {
                            isCurrentlyConnected = true
                            // Update service network type
                            updateServiceNetworkType(currentType)
                            Log.i(TAG, "Network validated - triggering reconnect")
                            onNetworkAvailable()
                        }
                    }
                }
            }
            
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            
            try {
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
                Log.d(TAG, "Network monitoring started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register network callback", e)
                networkCallback = null
            }
        }
        
        /**
         * Stop monitoring network changes
         */
        fun stopMonitoring() {
            networkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                    Log.d(TAG, "Network monitoring stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister network callback", e)
                }
                networkCallback = null
            }
        }
        
        /**
         * Check if network is currently available
         */
        private fun isNetworkAvailable(): Boolean {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        
        /**
         * Get current network type from capabilities
         */
        private fun getNetworkType(capabilities: NetworkCapabilities): NetworkType {
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                else -> NetworkType.OTHER
            }
        }
        
        /**
         * Get transport type string for logging
         */
        private fun getTransportType(capabilities: NetworkCapabilities): String {
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }
        }
        
        /**
         * Get initial network type
         */
        private fun getInitialNetworkType(): NetworkType {
            val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
            
            return getNetworkType(capabilities)
        }
    }
    
    /**
     * Network type enumeration
     */
    enum class NetworkType {
        NONE,
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        OTHER
    }

    private val binder = WebSocketBinder()

    inner class WebSocketBinder : Binder() {
        fun getService(): WebSocketService = this@WebSocketService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.d("WebSocketService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("WebSocketService", "Service started")
        
        // Start as foreground service with notification
        // This keeps the app process alive and prevents Android from killing it
        startForeground(NOTIFICATION_ID, createNotification())
        
        Log.d("WebSocketService", "Foreground service started successfully")
        
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("WebSocketService", "Service destroyed")
        // Note: We don't cancel the serviceScope here as it's a companion object
        // and should continue running across service restarts
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN  // ← Changed to IMPORTANCE_MIN for minimal visibility
            ).apply {
                description = "Maintains WebSocket connection for real-time updates"
                setShowBadge(false)
                // Make notification as unobtrusive as possible
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
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
            .setPriority(NotificationCompat.PRIORITY_MIN)  // ← Minimal priority
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // ← Hide from lock screen
            .build()
    }
    
    /**
     * Update notification with connection health info
     * 
     * @param lagMs Ping/pong round-trip time in milliseconds
     * @param lastSyncTimestamp Timestamp of last sync_complete message
     */
    fun updateNotificationText(lagMs: Long, lastSyncTimestamp: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Format lag
        val lagText = when {
            lagMs < 100 -> "${lagMs}ms"
            lagMs < 1000 -> "${lagMs}ms"
            else -> "${lagMs / 1000}s"
        }
        
        // Format time since last sync
        val timeSinceSync = System.currentTimeMillis() - lastSyncTimestamp
        val lastSyncText = when {
            timeSinceSync < 1000 -> "now"
            timeSinceSync < 60_000 -> "${timeSinceSync / 1000}s ago"
            timeSinceSync < 3600_000 -> "${timeSinceSync / 60_000}m ago"
            else -> "${timeSinceSync / 3600_000}h ago"
        }
        
        val notificationText = "Lag: $lagText • Last: $lastSyncText"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Andromuks")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)  // ← Minimal priority
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // ← Hide from lock screen
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        Log.d("WebSocketService", "Notification updated: $notificationText")
    }
    
    /**
     * Update notification with connection status and optional stats
     * 
     * @param isConnected Whether WebSocket is connected
     * @param lagMs Optional ping/pong round-trip time in milliseconds
     * @param lastSyncTimestamp Optional timestamp of last sync_complete message
     */
    fun updateConnectionStatus(isConnected: Boolean, lagMs: Long? = null, lastSyncTimestamp: Long? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notificationText = if (!isConnected) {
            "Connecting... • ${getNetworkTypeDisplayName(currentNetworkType)}"
        } else {
            // Format lag
            val lagText = if (lagMs != null && lagMs > 0) {
                when {
                    lagMs < 100 -> "${lagMs}ms"
                    lagMs < 1000 -> "${lagMs}ms"
                    else -> "${lagMs / 1000}s"
                }
            } else {
                "no ping yet"
            }
            
            // Format time since last sync
            val lastSyncText = if (lastSyncTimestamp != null && lastSyncTimestamp > 0) {
                val timeSinceSync = System.currentTimeMillis() - lastSyncTimestamp
                when {
                    timeSinceSync < 1000 -> "now"
                    timeSinceSync < 60_000 -> "${timeSinceSync / 1000}s ago"
                    timeSinceSync < 3600_000 -> "${timeSinceSync / 60_000}m ago"
                    else -> "${timeSinceSync / 3600_000}h ago"
                }
            } else {
                "no sync yet"
            }
            
            // Get connection health indicator
            val healthIndicator = getConnectionHealthIndicator(lagMs, lastSyncTimestamp)
            
            "$healthIndicator • ${getNetworkTypeDisplayName(currentNetworkType)} • Lag: $lagText • Last: $lastSyncText"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Andromuks")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        Log.d("WebSocketService", "Connection status updated: $notificationText")
    }
    
    /**
     * Get display name for network type
     */
    private fun getNetworkTypeDisplayName(networkType: NetworkType): String {
        return when (networkType) {
            NetworkType.WIFI -> "WiFi"
            NetworkType.CELLULAR -> "4G/5G"
            NetworkType.ETHERNET -> "Ethernet"
            NetworkType.VPN -> "VPN"
            NetworkType.OTHER -> "Other"
            NetworkType.NONE -> "No Network"
        }
    }
    
    /**
     * Get connection health indicator based on lag and last sync time
     */
    private fun getConnectionHealthIndicator(lagMs: Long?, lastSyncTimestamp: Long?): String {
        if (lagMs == null || lastSyncTimestamp == null || lastSyncTimestamp <= 0) {
            return "🟡" // Yellow - unknown status
        }
        
        val timeSinceSync = System.currentTimeMillis() - lastSyncTimestamp
        
        return when {
            // Excellent: low lag and recent sync
            lagMs < 200 && timeSinceSync < 30_000 -> "🟢" // Green
            // Good: reasonable lag and recent sync
            lagMs < 500 && timeSinceSync < 60_000 -> "🟡" // Yellow
            // Poor: high lag or old sync
            lagMs >= 1000 || timeSinceSync >= 120_000 -> "🔴" // Red
            // Default to yellow
            else -> "🟡" // Yellow
        }
    }
}
