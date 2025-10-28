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
import android.os.SystemClock
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
        
        // Constants
        private val BASE_TIMEOUT_MS = 5000L
        private val MAX_TIMEOUT_MS = 15000L
        private val MIN_TIMEOUT_MS = 3000L
        private val BASE_RECONNECTION_DELAY_MS = 3000L // 3 seconds - give network time to stabilize
        private val MAX_RECONNECTION_DELAY_MS = 30000L // 30 seconds - shorter max delay
        private val MAX_RECONNECTION_ATTEMPTS = 5 // Give up after 5 attempts - less aggressive
        private val NETWORK_CHANGE_DEBOUNCE_MS = 15000L // 15 seconds between network change reconnections
        private val MIN_RECONNECTION_INTERVAL_MS = 5000L // 5 seconds minimum between any reconnections
        
        // Callback for sending WebSocket commands
        private var webSocketSendCallback: ((String, Int, Map<String, Any>) -> Boolean)? = null
        
        // Callback for triggering reconnection
        private var reconnectionCallback: ((String) -> Unit)? = null
        
        // Callback for offline mode management
        private var offlineModeCallback: ((Boolean) -> Unit)? = null
        
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
            instance?.let { serviceInstance ->
                serviceInstance.pingJob?.cancel()
                serviceInstance.pongTimeoutJob?.cancel()
                serviceInstance.pingJob = null
                serviceInstance.pongTimeoutJob = null
            }
            instance?.pingJob = serviceScope.launch {
                android.util.Log.d("WebSocketService", "Ping loop coroutine started")
                android.util.Log.d("WebSocketService", "Ping loop: isActive=$isActive, connectionState=${instance?.connectionState}")
                
                // Send immediate ping when loop starts (if conditions are met)
                val serviceInstance = instance
                if (serviceInstance != null && serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.isCurrentlyConnected) {
                    android.util.Log.d("WebSocketService", "Ping loop: sending immediate ping on start")
                    serviceInstance.sendPing()
                }
                
                // Persistent loop - don't exit when disconnected, just skip pings
                while (isActive) {
                    val serviceInstance = instance ?: break
                    android.util.Log.d("WebSocketService", "Ping loop: inside while loop, getting interval")
                    val interval = serviceInstance.getPingInterval()
                    android.util.Log.d("WebSocketService", "Ping interval: ${interval}ms (app visible: ${serviceInstance.isAppVisible})")
                    delay(interval)
                    
                    android.util.Log.d("WebSocketService", "Ping loop: after delay, checking conditions")
                    // Only send ping if connected and network is available
                    if (serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.isCurrentlyConnected) {
                        android.util.Log.d("WebSocketService", "Ping loop: conditions met, sending ping")
                        serviceInstance.sendPing()
                    } else {
                        android.util.Log.d("WebSocketService", "Ping loop: skipping ping - connected: ${serviceInstance.connectionState == ConnectionState.CONNECTED}, network: ${serviceInstance.isCurrentlyConnected}")
                    }
                }
                android.util.Log.d("WebSocketService", "Ping loop coroutine ended - isActive=$isActive")
            }
            android.util.Log.d("WebSocketService", "Ping loop started")
            logPingStatus()
        }
        
        /**
         * Log ping status for debugging
         */
        fun logPingStatus() {
            val serviceInstance = instance ?: return
            val status = when {
                !serviceInstance.pingLoopStarted -> "Not started (waiting for sync_complete)"
                serviceInstance.pingJob == null -> "Stopped"
                serviceInstance.pingJob?.isActive == true -> "Running"
                else -> "Unknown state"
            }
            android.util.Log.i("WebSocketService", "Pinger status: $status (lastReceivedSyncId: ${serviceInstance.lastReceivedSyncId}, lastKnownLag: ${serviceInstance.lastKnownLagMs}ms)")
        }
        
        /**
         * Stop ping/pong loop
         */
        fun stopPingLoop() {
            instance?.pingJob?.cancel()
            instance?.pongTimeoutJob?.cancel()
            instance?.pingJob = null
            instance?.pongTimeoutJob = null
            android.util.Log.d("WebSocketService", "Ping loop stopped")
            logPingStatus()
        }
        
        /**
         * Set app visibility for adaptive ping intervals
         */
        fun setAppVisibility(visible: Boolean) {
            instance?.isAppVisible = visible
            android.util.Log.d("WebSocketService", "App visibility changed to: $visible")
            
            // Log current ping interval when visibility changes
            val interval = instance?.let { serviceInstance ->
                if (serviceInstance.isAppVisible) 15_000L else 60_000L
            } ?: 60_000L
            android.util.Log.d("WebSocketService", "Ping interval: ${interval}ms (app visible: $visible)")
            
            // Force update the ping loop if it's running
            instance?.let { serviceInstance ->
                if (serviceInstance.pingJob?.isActive == true) {
                    android.util.Log.d("WebSocketService", "App visibility changed while ping loop running - will use new interval on next ping")
                }
            }
        }
        
        
        /**
         * Set callback for sending WebSocket commands
         */
        fun setWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
            android.util.Log.d("WebSocketService", "setWebSocketSendCallback() called")
            webSocketSendCallback = callback
        }
        
        /**
         * Set callback for triggering reconnection
         */
        fun setReconnectionCallback(callback: (String) -> Unit) {
            android.util.Log.d("WebSocketService", "setReconnectionCallback() called")
            reconnectionCallback = callback
        }
        
        /**
         * Set callback for offline mode management
         */
        fun setOfflineModeCallback(callback: (Boolean) -> Unit) {
            android.util.Log.d("WebSocketService", "setOfflineModeCallback() called")
            offlineModeCallback = callback
        }
        
        /**
         * Check if a request_id is a ping request
         */
    fun isPingRequestId(requestId: Int): Boolean {
        val serviceInstance = instance ?: return false
        return requestId == serviceInstance.lastPingRequestId
    }
    
    /**
     * Check if WebSocket is currently connected
     */
    fun isConnected(): Boolean {
        val serviceInstance = instance ?: return false
        return serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.webSocket != null
    }
    
    
    /**
     * Mark network as healthy (e.g., when FCM is received)
     */
    fun markNetworkHealthy() {
        val serviceInstance = instance ?: return
        serviceInstance.isCurrentlyConnected = true
        serviceInstance.consecutivePingTimeouts = 0
        android.util.Log.d("WebSocketService", "Network marked as healthy")
    }
    
    /**
     * Trigger WebSocket reconnection from external callers
     */
    fun triggerReconnectionFromExternal(reason: String) {
        android.util.Log.d("WebSocketService", "triggerReconnectionFromExternal called: $reason")
        // Call the reconnection callback directly
        reconnectionCallback?.invoke(reason)
    }
        
        /**
         * Handle pong response
         */
        fun handlePong(requestId: Int) {
            val serviceInstance = instance ?: return
            if (requestId == serviceInstance.lastPingRequestId) {
                serviceInstance.pongTimeoutJob?.cancel()
                serviceInstance.pongTimeoutJob = null
                
                val lagMs = System.currentTimeMillis() - serviceInstance.lastPingTimestamp
                serviceInstance.lastKnownLagMs = lagMs
                serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime() // Update heartbeat timestamp
                android.util.Log.d("WebSocketService", "Pong received, lag: ${lagMs}ms")
                
                // Update network metrics
                serviceInstance.updateNetworkMetrics(lagMs)
                
                // Update notification with lag and last sync timestamp
                updateConnectionStatus(true, lagMs, serviceInstance.lastSyncTimestamp)
                
                // Reset consecutive timeouts since we got a pong
                serviceInstance.consecutiveTimeouts = 0
                serviceInstance.consecutivePingTimeouts = 0
            }
        }
        
        
        /**
         * Calculate smart timeout based on network conditions
         */
        private fun calculateSmartTimeout(): Long {
            val serviceInstance = instance ?: return BASE_TIMEOUT_MS
            val baseTimeout = BASE_TIMEOUT_MS
            val latencyAdjustment = (serviceInstance.networkLatencyMs * 1.5).toLong().coerceAtMost(5000L)
            val stabilityAdjustment = ((1.0f - serviceInstance.connectionStability) * 5000f).toLong()
            val consecutiveTimeoutAdjustment = serviceInstance.consecutiveTimeouts * 2000L
            
            val smartTimeout = baseTimeout + latencyAdjustment + stabilityAdjustment + consecutiveTimeoutAdjustment
            return smartTimeout.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }
        
        /**
         * Trigger WebSocket reconnection
         */
        fun triggerReconnection(reason: String) {
            android.util.Log.i("WebSocketService", "Triggering WebSocket reconnection: $reason")
            // Only trigger reconnection if not already reconnecting
            val serviceInstance = instance ?: return
            if (serviceInstance.connectionState != ConnectionState.RECONNECTING && !serviceInstance.isReconnecting) {
                scheduleReconnection(reason)
            } else {
                android.util.Log.d("WebSocketService", "Already reconnecting, ignoring trigger: $reason")
            }
        }
        
        /**
         * Start network monitoring for immediate reconnection on network changes
         */
        fun startNetworkMonitoring(context: Context) {
            android.util.Log.d("WebSocketService", "startNetworkMonitoring() called")
            val serviceInstance = instance
            if (serviceInstance == null) {
                android.util.Log.e("WebSocketService", "startNetworkMonitoring() called but service instance is null!")
                return
            }
            if (serviceInstance.networkMonitor != null) {
                android.util.Log.d("WebSocketService", "Network monitor already started")
                return
            }
            
            android.util.Log.d("WebSocketService", "Starting network monitoring...")
            
            serviceInstance.networkMonitor = NetworkMonitor(
                context = context,
                onNetworkAvailable = {
                    android.util.Log.i("WebSocketService", "Network available - triggering reconnection")
                    serviceInstance.isCurrentlyConnected = true
                    
                    // Reset timeout tracking on network restoration
                    serviceInstance.consecutiveTimeouts = 0
                    serviceInstance.updateConnectionStability(true)
                    
                    // Notify AppViewModel to exit offline mode
                    offlineModeCallback?.invoke(false)
                    
                    // For network restoration, directly call the WebSocket connection instead of triggerReconnection
                    android.util.Log.d("WebSocketService", "Network restored - directly calling WebSocket connection")
                    reconnectionCallback?.invoke("Network restored - direct connection")
                },
                onNetworkLost = {
                    android.util.Log.w("WebSocketService", "Network lost - entering offline mode")
                    serviceInstance.isCurrentlyConnected = false
                    
                    // Cancel any pending ping timeouts to prevent reconnection attempts
                    serviceInstance.pongTimeoutJob?.cancel()
                    serviceInstance.pongTimeoutJob = null
                    
                    // Cancel any pending reconnections
                    cancelReconnection()
                    
                    // Notify AppViewModel to enter offline mode
                    offlineModeCallback?.invoke(true)
                    updateConnectionStatus(false)
                }
            )
            
            serviceInstance.networkMonitor?.startMonitoring()
            android.util.Log.d("WebSocketService", "Network monitoring started in service")
            
            // Test network monitoring by checking network status every 10 seconds
            serviceInstance.networkTestJob = serviceScope.launch {
                while (isActive) {
                    delay(10000) // Check every 10 seconds
                    val isNetworkAvailable = serviceInstance.networkMonitor?.isNetworkAvailable() ?: false
                    android.util.Log.d("WebSocketService", "Network test: isNetworkAvailable=$isNetworkAvailable, isCurrentlyConnected=${serviceInstance.isCurrentlyConnected}")
                }
            }
            
            // FAILSAFE: Periodic reconnection check to prevent getting stuck in "Connecting..." state
            // This runs every 30 seconds and checks if we're stuck disconnected
            serviceInstance.failsafeReconnectionJob = serviceScope.launch {
                while (isActive) {
                    delay(30000) // Check every 30 seconds
                    
                    val isNetworkAvailable = serviceInstance.networkMonitor?.isNetworkAvailable() ?: false
                    val isConnected = serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.webSocket != null
                    val isReconnecting = serviceInstance.isReconnecting || serviceInstance.connectionState == ConnectionState.RECONNECTING
                    
                    // If we have network but no connection and not already reconnecting, force a reconnection
                    if (isNetworkAvailable && !isConnected && !isReconnecting) {
                        android.util.Log.w("WebSocketService", "FAILSAFE: Stuck in disconnected state with network available - forcing reconnection")
                        reconnectionCallback?.invoke("Failsafe reconnection - stuck disconnected with network available")
                    }
                }
            }
        }
        
        /**
         * Stop network monitoring
         */
        fun stopNetworkMonitoring() {
            instance?.networkMonitor?.stopMonitoring()
            instance?.networkMonitor = null
            instance?.networkTestJob?.cancel()
            instance?.networkTestJob = null
            instance?.failsafeReconnectionJob?.cancel()
            instance?.failsafeReconnectionJob = null
            android.util.Log.d("WebSocketService", "Network monitoring stopped")
        }
        
        /**
         * Set WebSocket connection
         */
        fun setWebSocket(webSocket: WebSocket) {
            val serviceInstance = instance ?: return
            android.util.Log.i("WebSocketService", "setWebSocket() called - setting up WebSocket connection")
            android.util.Log.d("WebSocketService", "Current connection state: ${serviceInstance.connectionState}")
            
            // Check if already connecting or connected (but allow reconnection)
            if (serviceInstance.connectionState == ConnectionState.CONNECTING) {
                android.util.Log.w("WebSocketService", "WebSocket connection already in progress, ignoring new connection")
                return
            }
            
            // If already connected, close the old connection first (for reconnection)
            if (serviceInstance.connectionState == ConnectionState.CONNECTED) {
                android.util.Log.d("WebSocketService", "Replacing existing WebSocket connection (reconnection)")
                serviceInstance.webSocket?.close(1000, "Reconnecting")
            }
            
            serviceInstance.connectionState = ConnectionState.CONNECTING
            serviceInstance.webSocket = webSocket
            serviceInstance.connectionState = ConnectionState.CONNECTED
            android.util.Log.d("WebSocketService", "Connection state set to CONNECTED")
            android.util.Log.d("WebSocketService", "WebSocket reference set: ${webSocket != null}")
            serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
            
            // Ensure network connectivity is marked as available when WebSocket connects
            serviceInstance.isCurrentlyConnected = true
            
            // Set network type to WiFi since we have a working connection
            serviceInstance.currentNetworkType = NetworkType.WIFI
            
            // Don't start ping loop yet - wait for first sync_complete to get lastReceivedSyncId
            android.util.Log.d("WebSocketService", "WebSocket connected, waiting for sync_complete before starting ping loop")
            
            // Update notification with connection status
            updateConnectionStatus(true, null, serviceInstance.lastSyncTimestamp)
            
            android.util.Log.i("WebSocketService", "WebSocket connection established in service")
            logPingStatus()
        }
        
        /**
         * Clear WebSocket connection
         */
        fun clearWebSocket() {
            val serviceInstance = instance ?: return
            android.util.Log.w("WebSocketService", "clearWebSocket() called - setting connection state to DISCONNECTED")
            serviceInstance.webSocket = null
            serviceInstance.connectionState = ConnectionState.DISCONNECTED
            
            // Stop ping loop
            stopPingLoop()
            
            // Cancel any pending pong timeouts
            serviceInstance.pongTimeoutJob?.cancel()
            serviceInstance.pongTimeoutJob = null
            
            // Reset connection health tracking
            serviceInstance.lastKnownLagMs = null
            serviceInstance.lastPongTimestamp = 0L
            
            // Reset ping loop state for next connection
            serviceInstance.pingLoopStarted = false
            
            // Update notification to show disconnection
            updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
            
            android.util.Log.d("WebSocketService", "WebSocket connection cleared in service")
            logPingStatus()
        }
        
        /**
         * Check if WebSocket is connected
         */
        fun isWebSocketConnected(): Boolean {
            val serviceInstance = instance ?: return false
            return serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.webSocket != null
        }
        
        /**
         * Get WebSocket instance
         */
        fun getWebSocket(): WebSocket? {
            return instance?.webSocket
        }
        
        /**
         * Set reconnection state (run_id, last_received_event, vapid_key)
         */
        fun setReconnectionState(runId: String, lastReceivedId: Int, vapidKey: String) {
            val serviceInstance = instance ?: return
            
            serviceInstance.currentRunId = runId
            serviceInstance.lastReceivedSyncId = lastReceivedId
            serviceInstance.vapidKey = vapidKey
        }
        
        /**
         * Get current run_id for reconnection
         */
        fun getCurrentRunId(): String = instance?.currentRunId ?: ""
        
        /**
         * Get last received sync ID for reconnection
         */
        fun getLastReceivedSyncId(): Int = instance?.lastReceivedSyncId ?: 0
        
        /**
         * Get VAPID key for push notifications
         */
        fun getVapidKey(): String = instance?.vapidKey ?: ""
        
        /**
         * Update last received sync ID
         */
        fun updateLastReceivedSyncId(syncId: Int) {
            instance?.lastReceivedSyncId = syncId
        }
        
        /**
         * Update last sync timestamp when sync_complete is received
         */
        fun updateLastSyncTimestamp() {
            val serviceInstance = instance ?: return
            serviceInstance.lastSyncTimestamp = System.currentTimeMillis()
            
        android.util.Log.d("WebSocketService", "updateLastSyncTimestamp() called - connectionState: ${serviceInstance.connectionState}")
            
            // Start ping loop on first sync_complete (when we have a valid lastReceivedSyncId)
            if (!serviceInstance.pingLoopStarted) {
                serviceInstance.pingLoopStarted = true
                android.util.Log.i("WebSocketService", "First sync_complete received, starting ping loop")
                startPingLoop()
            }
            
            // Update notification with current lag and new sync timestamp
            updateConnectionStatus(true, serviceInstance.lastKnownLagMs, serviceInstance.lastSyncTimestamp)
        }
        
        /**
         * Clear reconnection state
         */
        fun clearReconnectionState() {
            val serviceInstance = instance ?: return
            serviceInstance.currentRunId = ""
            serviceInstance.lastReceivedSyncId = 0
            serviceInstance.vapidKey = ""
            android.util.Log.d("WebSocketService", "Cleared reconnection state")
        }
        
        /**
         * Get reconnection parameters for WebSocket URL construction
         */
        fun getReconnectionParameters(): Triple<String, Int, String> {
            val serviceInstance = instance ?: return Triple("", 0, "")
            android.util.Log.d("WebSocketService", "getReconnectionParameters: currentRunId='${serviceInstance.currentRunId}', lastReceivedSyncId=${serviceInstance.lastReceivedSyncId}, vapidKey='${serviceInstance.vapidKey.take(20)}...'")
            android.util.Log.d("WebSocketService", "DEBUG - currentRunId type: ${serviceInstance.currentRunId.javaClass.simpleName}, length: ${serviceInstance.currentRunId.length}")
            android.util.Log.d("WebSocketService", "DEBUG - currentRunId starts with '{': ${serviceInstance.currentRunId.startsWith("{")}")
            return Triple(serviceInstance.currentRunId, serviceInstance.lastReceivedSyncId, serviceInstance.vapidKey)
        }
        
        /**
         * Reset reconnection state (called on successful connection)
         */
        fun resetReconnectionState() {
            val serviceInstance = instance ?: return
            serviceInstance.reconnectionAttempts = 0
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            serviceInstance.isReconnecting = false
            serviceInstance.connectionState = ConnectionState.CONNECTED
            android.util.Log.d("WebSocketService", "Reset reconnection state (successful connection)")
        }
        
        /**
         * Schedule WebSocket reconnection with exponential backoff and network validation
         */
        fun scheduleReconnection(reason: String) {
            val serviceInstance = instance ?: return
            val currentTime = System.currentTimeMillis()
            
            // Don't block reconnections - allow forced reconnections even if one is in progress
            // This is critical for network restoration and FCM-triggered reconnections
            if (serviceInstance.connectionState == ConnectionState.RECONNECTING || serviceInstance.isReconnecting) {
                android.util.Log.w("WebSocketService", "Reconnection already in progress, but allowing forced reconnection: $reason")
                // Cancel the existing reconnection and start a new one
                serviceInstance.reconnectionJob?.cancel()
            }
            
            // Check minimum interval between reconnections
            if (currentTime - serviceInstance.lastReconnectionTime < MIN_RECONNECTION_INTERVAL_MS) {
                android.util.Log.d("WebSocketService", "Too soon since last reconnection, ignoring: $reason")
                return
            }
            
            // Cancel any existing reconnection job
            serviceInstance.reconnectionJob?.cancel()
            
            // NEVER GIVE UP ON RECONNECTION
            // The WebSocket connection is the heart of the app - we must always try to reconnect
            // Instead of giving up after MAX_RECONNECTION_ATTEMPTS, we reset the attempts counter
            // and continue trying with maximum delay
            if (serviceInstance.reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
                android.util.Log.w("WebSocketService", "Max reconnection attempts ($MAX_RECONNECTION_ATTEMPTS) reached, resetting and continuing")
                serviceInstance.reconnectionAttempts = 0 // Reset and keep trying
            }
            
            // Calculate delay with exponential backoff (1s → 2s → 5s → 10s, max 30s)
            val delay = kotlin.math.min(
                BASE_RECONNECTION_DELAY_MS * (1 shl serviceInstance.reconnectionAttempts), // 2^attempts
                MAX_RECONNECTION_DELAY_MS
            )
            
            serviceInstance.reconnectionAttempts++
            serviceInstance.isReconnecting = true
            serviceInstance.connectionState = ConnectionState.RECONNECTING
            serviceInstance.lastReconnectionTime = currentTime
            
            android.util.Log.w("WebSocketService", "Scheduling reconnection attempt #${serviceInstance.reconnectionAttempts} in ${delay}ms")
            android.util.Log.w("WebSocketService", "Reason: $reason")
            
            serviceInstance.reconnectionJob = serviceScope.launch {
                delay(delay)
                
                if (isActive) {
                    android.util.Log.d("WebSocketService", "Executing reconnection attempt #${serviceInstance.reconnectionAttempts}")
                    
                    // ALWAYS TRY TO RECONNECT - network validation can be unreliable
                    // The WebSocket connection is critical - we must try to reconnect regardless of validation
                    // If the network isn't actually available, the connection will fail and we'll retry
                    android.util.Log.i("WebSocketService", "Attempting reconnection regardless of network validation")
                    reconnectionCallback?.invoke("Reconnection attempt #${serviceInstance.reconnectionAttempts}: $reason")
                    } else {
                    serviceInstance.isReconnecting = false
                    serviceInstance.connectionState = ConnectionState.DISCONNECTED
                }
            }
        }
        
        /**
         * Restart WebSocket connection
         */
        fun restartWebSocket(reason: String = "Unknown reason") {
            android.util.Log.d("WebSocketService", "Restarting WebSocket connection - Reason: $reason")
            
            val serviceInstance = instance ?: return
            
            // Properly close existing WebSocket first
            val currentWebSocket = serviceInstance.webSocket
            if (currentWebSocket != null) {
                android.util.Log.d("WebSocketService", "Closing existing WebSocket before restart")
                try {
                    currentWebSocket.close(1000, "Restarting connection")
                } catch (e: Exception) {
                    android.util.Log.w("WebSocketService", "Error closing WebSocket", e)
                }
            }
            
            // Only clear WebSocket if not a network restoration
            if (!reason.contains("Network restored")) {
            clearWebSocket()
            } else {
                android.util.Log.d("WebSocketService", "Network restored - skipping clearWebSocket to preserve state")
            }
            
            // Reset reconnection state to allow new reconnection
            serviceInstance.isReconnecting = false
            
            // Add a small delay to ensure WebSocket is properly closed
            serviceScope.launch {
                delay(1000) // 1 second delay to ensure proper closure
                android.util.Log.d("WebSocketService", "Triggering reconnection after delay")
                
                // Always use the callback to avoid infinite loops
                reconnectionCallback?.invoke(reason)
            }
        }
        
        /**
         * Cancel any pending reconnection
         */
        fun cancelReconnection() {
            val serviceInstance = instance ?: return
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            serviceInstance.isReconnecting = false
            if (serviceInstance.connectionState == ConnectionState.RECONNECTING) {
                serviceInstance.connectionState = ConnectionState.DISCONNECTED
            }
            android.util.Log.d("WebSocketService", "Cancelled pending reconnection")
        }
        
        /**
         * Update network type and refresh notification
         */
        private fun updateServiceNetworkType(networkType: NetworkType) {
            val serviceInstance = instance ?: return
            serviceInstance.currentNetworkType = networkType
            android.util.Log.d("WebSocketService", "Network type updated to: $networkType")
            
            // Update notification with new network type
            updateConnectionStatus(isWebSocketConnected())
        }
        
    }
    
    // Instance variables for WebSocket state management
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var lastPingRequestId: Int = 0
    private var lastPingTimestamp: Long = 0
    private var isAppVisible = false
    
    // Network optimization variables
    private var networkLatencyMs = 1000L
    private var connectionStability = 1.0f
    private var consecutiveTimeouts = 0
    
    // Network monitoring
    private var networkMonitor: NetworkMonitor? = null
    private var networkTestJob: Job? = null
    private var failsafeReconnectionJob: Job? = null // Failsafe reconnection for stuck states
    private var isCurrentlyConnected = false
    private var currentNetworkType: NetworkType = NetworkType.NONE
    
    // WebSocket connection management
    private var webSocket: WebSocket? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private var lastPongTimestamp = 0L // Track last pong for heartbeat monitoring
    
    // Reconnection state management
    private var currentRunId: String = ""
    private var lastReceivedSyncId: Int = 0
    private var vapidKey: String = ""
    
    // Connection health tracking
    private var lastSyncTimestamp: Long = 0
    private var lastKnownLagMs: Long? = null
    private var consecutivePingTimeouts: Int = 0 // Track consecutive ping timeouts for network quality detection
    
    // Reconnection logic state
    private var reconnectionAttempts = 0
    private var reconnectionJob: Job? = null
    
    // Network change debouncing
    private var lastNetworkChangeTime = 0L
    private var isReconnecting = false // Prevent multiple simultaneous reconnections
    private var lastReconnectionTime = 0L
    
    // Ping loop state
    private var pingLoopStarted = false // Track if ping loop has been started after first sync_complete
    
    /**
     * Get appropriate ping interval based on app visibility
     */
    private fun getPingInterval(): Long {
        // Always use 15s pings for better connection resilience
        // This helps catch network issues even when app is backgrounded
        // Mobile networks can be unreliable, so frequent pings help maintain connection
        return 15_000L
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
        
        // Don't send pings if network is unavailable
        if (!isCurrentlyConnected) {
            android.util.Log.d("WebSocketService", "Skipping ping - network unavailable")
            return
        }
        
        // Don't send pings if we don't have a valid lastReceivedSyncId yet
        if (lastReceivedSyncId == 0) {
            android.util.Log.d("WebSocketService", "Skipping ping - no sync_complete received yet (lastReceivedSyncId: $lastReceivedSyncId)")
            return
        }
        
        val reqId = lastPingRequestId + 1
        lastPingRequestId = reqId
        lastPingTimestamp = System.currentTimeMillis()
        
        val data = mapOf("last_received_id" to lastReceivedSyncId)
        
        android.util.Log.d("WebSocketService", "Sending ping (requestId: $reqId, lastReceivedSyncId: $lastReceivedSyncId)")
        
        // Log ping status before sending
        android.util.Log.i("WebSocketService", "Pinger status: Sending ping (requestId: $reqId, lastReceivedSyncId: $lastReceivedSyncId)")
        
        // Send ping directly via WebSocket
        try {
            val json = org.json.JSONObject()
            json.put("command", "ping")
            json.put("data", org.json.JSONObject(data))
            json.put("request_id", reqId)
            val jsonString = json.toString()
            
            // Log the actual PING JSON being sent
            android.util.Log.i("WebSocketService", "PING JSON: $jsonString")
            
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
        pongTimeoutJob = WebSocketService.serviceScope.launch {
            // Use fixed timeout: 10-15 seconds as recommended
            val timeoutMs = 12_000L // 12 seconds - between 10-15s as recommended
            delay(timeoutMs)
            
            // Check if we're still connected and network is available before triggering reconnection
            if (connectionState != ConnectionState.CONNECTED || !isCurrentlyConnected) {
                android.util.Log.d("WebSocketService", "Pong timeout but WebSocket disconnected or network unavailable - not triggering reconnection")
                android.util.Log.d("WebSocketService", "Skipping timeout tracking - network unavailable during ping timeout")
                return@launch
            }
            
            android.util.Log.w("WebSocketService", "Pong timeout for ping $pingRequestId (timeout: ${timeoutMs}ms)")
            
            // Update timeout tracking only if we're still connected and have network
            // This prevents false timeout tracking when network drops during ping
            consecutiveTimeouts++
            consecutivePingTimeouts++
            updateConnectionStability(false)
            
            // Network quality detection based on consecutive timeouts
            when {
                consecutivePingTimeouts >= 5 -> {
                    android.util.Log.w("WebSocketService", "Poor network quality detected - 5+ consecutive timeouts")
                    // Consider this a degraded connection
                }
                consecutivePingTimeouts >= 3 -> {
                    android.util.Log.w("WebSocketService", "Network quality degrading - 3+ consecutive timeouts")
                }
            }
            
            // Only trigger reconnection if we're still connected and have network
            if (connectionState == ConnectionState.CONNECTED && isCurrentlyConnected) {
                triggerReconnection("Ping timeout (${timeoutMs}ms) - consecutive: $consecutivePingTimeouts")
            } else {
                android.util.Log.d("WebSocketService", "Skipping reconnection - WebSocket disconnected or network unavailable")
            }
        }
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
         * Validate network connectivity by testing a simple HTTP request
         */
        private suspend fun validateNetworkConnectivity(): Boolean {
            return try {
                // Simple connectivity test - just check if we can reach the network
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
        private var networkFlapCount = 0
        private var lastNetworkChangeTime = 0L
        private var adaptiveDebounceMs = WebSocketService.NETWORK_CHANGE_DEBOUNCE_MS
        private var lastActiveNetwork: Network? = null
        
        companion object {
            private const val TAG = "WebSocketService.NetworkMonitor"
            private const val MAX_FLAP_COUNT = 3
            private const val ADAPTIVE_DEBOUNCE_MULTIPLIER = 2
            private const val MAX_DEBOUNCE_MS = 60000L // 1 minute max
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
            WebSocketService.instance?.currentNetworkType = initialNetworkType
            Log.d(TAG, "Initial network state: connected=$isCurrentlyConnected, type=$initialNetworkType")
            
            // Register network callback for connectivity changes
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    Log.d(TAG, "onAvailable: isCurrentlyConnected=$isCurrentlyConnected")
                        
                    if (!isCurrentlyConnected) {
                        // Get network capabilities to verify internet reachability
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                        if (capabilities != null) {
                            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            
                            Log.d(TAG, "Network available - Internet: $hasInternet, Validated: $isValidated")
                            
                            // Only proceed if we have internet capability
                            if (hasInternet) {
                                isCurrentlyConnected = true
                                
                            val currentType = getNetworkType(capabilities)
                            val currentTime = System.currentTimeMillis()
                            
                            // Update network type and check for changes
                            if (currentType != lastNetworkType) {
                                Log.i(TAG, "Network type changed from $lastNetworkType to $currentType on network available")
                                lastNetworkType = currentType
                                    WebSocketService.instance?.lastNetworkChangeTime = currentTime
                                    WebSocketService.updateServiceNetworkType(currentType)
                        }
                        
                                if (isValidated) {
                                    Log.i(TAG, "Network validated - triggering reconnect")
                        onNetworkAvailable()
                                } else {
                                    Log.d(TAG, "Network available but not validated - will retry when validated")
                                    // Don't trigger reconnection yet, wait for validation
                                }
                            } else {
                                Log.d(TAG, "Network available but no internet capability - ignoring")
                            }
                        } else {
                            Log.d(TAG, "Network available but no capabilities - ignoring")
                        }
                    } else {
                        Log.d(TAG, "onAvailable: Already connected, ignoring")
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    // Always mark as disconnected when any network is lost
                    // This ensures reconnection will be triggered when network comes back
                        isCurrentlyConnected = false
                    Log.w(TAG, "Network lost - marking as disconnected")
                    
                    // Check if we still have internet connectivity
                    val stillHasInternet = isNetworkAvailable()
                    Log.d(TAG, "Network lost - still has internet: $stillHasInternet")
                    
                    if (!stillHasInternet) {
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
                    Log.d(TAG, "onCapabilitiesChanged: isCurrentlyConnected=$isCurrentlyConnected")
                    
                    // Detect network type changes (WiFi ↔ 4G/5G) with debouncing
                    if (hasInternet && isValidated) {
                        val currentType = getNetworkType(networkCapabilities)
                        val currentTime = System.currentTimeMillis()
                        
                        // Check if this is a different network instance
                        val isNewNetwork = lastActiveNetwork != network
                        if (isNewNetwork) {
                            Log.d(TAG, "New network instance detected: $network (was: $lastActiveNetwork)")
                            lastActiveNetwork = network
                        }
                        
                        // Check if we were disconnected and now have connectivity
                        if (!isCurrentlyConnected) {
                            Log.i(TAG, "Network connectivity restored - triggering reconnect")
                            isCurrentlyConnected = true
                            lastNetworkType = currentType
                            lastActiveNetwork = network
                            WebSocketService.instance?.lastNetworkChangeTime = currentTime
                            WebSocketService.updateServiceNetworkType(currentType)
                            
                            // Only trigger reconnection if not already reconnecting
                            if (!(WebSocketService.instance?.isReconnecting ?: false)) {
                                onNetworkAvailable()
                            } else {
                                Log.d(TAG, "Already reconnecting, ignoring network restoration")
                            }
                        } else if (currentType != lastNetworkType || isNewNetwork) {
                            // For new network instances, always reconnect (true network switch)
                            if (isNewNetwork) {
                                Log.i(TAG, "New network instance detected - treating as true network switch")
                                lastNetworkType = currentType
                                lastNetworkChangeTime = currentTime
                                WebSocketService.instance?.lastNetworkChangeTime = currentTime
                                WebSocketService.updateServiceNetworkType(currentType)
                                
                                // Reset flap count on successful change
                                networkFlapCount = 0
                                adaptiveDebounceMs = WebSocketService.NETWORK_CHANGE_DEBOUNCE_MS
                                
                                // Only trigger reconnection if not already reconnecting
                                if (!(WebSocketService.instance?.isReconnecting ?: false)) {
                                    onNetworkAvailable() // Trigger reconnection on new network
                                } else {
                                    Log.d(TAG, "Already reconnecting, ignoring new network")
                                }
                            } else {
                                // Check if enough time has passed since last network change (adaptive debouncing)
                                val timeSinceLastChange = currentTime - lastNetworkChangeTime
                                if (timeSinceLastChange > adaptiveDebounceMs) {
                                Log.i(TAG, "Network type changed from $lastNetworkType to $currentType - scheduling reconnection")
                                lastNetworkType = currentType
                                lastNetworkChangeTime = currentTime
                                    WebSocketService.instance?.lastNetworkChangeTime = currentTime
                                // Update service network type
                                    WebSocketService.updateServiceNetworkType(currentType)
                                    
                                    // Reset flap count on successful change
                                    networkFlapCount = 0
                                    adaptiveDebounceMs = WebSocketService.NETWORK_CHANGE_DEBOUNCE_MS
                                // Only trigger reconnection if not already reconnecting
                                    if (!(WebSocketService.instance?.isReconnecting ?: false)) {
                                    onNetworkAvailable() // Trigger reconnection on network type change
                                } else {
                                    Log.d(TAG, "Already reconnecting, ignoring network type change")
                                }
                            } else {
                                    // Detect network flaps and increase debounce time
                                    networkFlapCount++
                                    Log.d(TAG, "Network type changed but debouncing - ignoring rapid change (flap count: $networkFlapCount)")
                                    
                                    // Increase debounce time for flaky networks
                                    if (networkFlapCount >= MAX_FLAP_COUNT) {
                                        adaptiveDebounceMs = kotlin.math.min(
                                            adaptiveDebounceMs * ADAPTIVE_DEBOUNCE_MULTIPLIER,
                                            MAX_DEBOUNCE_MS
                                        )
                                        Log.w(TAG, "Network flapping detected - increasing debounce to ${adaptiveDebounceMs}ms")
                                    }
                                }
                            }
                        } else {
                            // Network is connected and validated, just update type if needed
                            if (currentType != lastNetworkType) {
                                Log.d(TAG, "Network type updated from $lastNetworkType to $currentType (already connected)")
                                lastNetworkType = currentType
                                WebSocketService.updateServiceNetworkType(currentType)
                            }
                        }
                    } else {
                        // Network lost internet or validation
                        Log.w(TAG, "Network lost internet connectivity or validation")
                        if (isCurrentlyConnected) {
                            isCurrentlyConnected = false
                            Log.w(TAG, "Marking network as disconnected due to capability loss")
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
        fun isNetworkAvailable(): Boolean {
            val network = connectivityManager.activeNetwork
            if (network == null) {
                Log.d(TAG, "isNetworkAvailable: No active network")
                return false
            }
            
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities == null) {
                Log.d(TAG, "isNetworkAvailable: No network capabilities")
                return false
            }
            
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            
            Log.d(TAG, "isNetworkAvailable: hasInternet=$hasInternet, isValidated=$isValidated")
            
            return hasInternet && isValidated
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
    
    /**
     * WebSocket connection state machine
     */
    enum class ConnectionState {
        DISCONNECTED,    // No connection, not attempting to connect
        CONNECTING,      // Attempting to establish connection
        CONNECTED,       // Connected and healthy
        RECONNECTING     // Connected but attempting to reconnect (graceful transition)
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
        
        // Start network monitoring immediately when service is created
        android.util.Log.d("WebSocketService", "Starting network monitoring from onCreate")
        startNetworkMonitoring(this)
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
