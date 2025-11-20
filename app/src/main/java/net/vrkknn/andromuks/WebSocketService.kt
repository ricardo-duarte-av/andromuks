package net.vrkknn.andromuks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import okhttp3.WebSocket
import org.json.JSONObject
import net.vrkknn.andromuks.utils.trimWebsocketHost

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
        private val BASE_RECONNECTION_DELAY_MS = 3000L // 3 seconds - give network time to stabilize
        private val MIN_RECONNECTION_INTERVAL_MS = 5000L // 5 seconds minimum between any reconnections
        private val MIN_NOTIFICATION_UPDATE_INTERVAL_MS = 500L // 500ms minimum between notification updates (UI smoothing)
        private const val BACKEND_HEALTH_RETRY_DELAY_MS = 5_000L
        private const val PONG_TIMEOUT_MS = 1_000L // 1 second - "rush to healthy"
        private const val PING_INTERVAL_MS = 15_000L // 15 seconds normal interval
        private const val CONSECUTIVE_FAILURES_TO_DROP = 3 // Drop WebSocket after 3 consecutive ping failures
        private val TOGGLE_STACK_DEPTH = 6
        private val toggleCounter = AtomicLong(0)
        
        // PHASE 4: WebSocket Callback Queues (allows multiple ViewModels to interact)
        
        // Send callbacks: For sending commands FROM service TO ViewModels (e.g., ping/pong)
        private val webSocketSendCallbacks = mutableListOf<Pair<String, (String, Int, Map<String, Any>) -> Boolean>>()
        
        // PHASE 4: Receive callbacks: For distributing messages FROM server TO ViewModels
        // When a message arrives from server, all registered ViewModels are notified
        private val webSocketReceiveCallbacks = mutableListOf<Pair<String, AppViewModel>>()
        
        private val callbacksLock = Any() // Thread safety for callback lists
        
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
         * Check if the WebSocketService is currently running
         * Returns true if the service instance exists (service is running)
         */
        fun isServiceRunning(): Boolean {
            return instance != null
        }

        private fun shortStackTrace(): String {
            val st = Thread.currentThread().stackTrace
            // Skip first frames that are inside Thread/Logger helpers; find first non-runtime frame
            val frames = st.drop(4).take(TOGGLE_STACK_DEPTH).joinToString(" | ") {
                "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}"
            }
            return frames
        }

        fun traceToggle(event: String, details: String = ""): Long {
            val id = toggleCounter.incrementAndGet()
            val ts = System.currentTimeMillis()
            val stack = shortStackTrace()
            Log.i("WS-Toggle", "id=$id ts=$ts event=$event details=[$details] stack=[$stack]")
            return id
        }
        
        // Activity log callback - set by AppViewModel
        private var activityLogCallback: ((String, String?) -> Unit)? = null
        
        /**
         * Set callback for logging activity events
         */
        fun setActivityLogCallback(callback: (String, String?) -> Unit) {
            android.util.Log.d("WebSocketService", "setActivityLogCallback() called")
            activityLogCallback = callback
        }
        
        /**
         * Log an activity event (app started, websocket connected, disconnected, etc.)
         */
        fun logActivity(event: String, networkType: String? = null) {
            activityLogCallback?.invoke(event, networkType)
        }
        
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
                    
                    // RUSH TO HEALTHY: If we have consecutive failures, send ping immediately (don't wait)
                    val interval = if (serviceInstance.consecutivePingTimeouts > 0) {
                        android.util.Log.d("WebSocketService", "RUSH TO HEALTHY: Sending ping immediately after failure (consecutive: ${serviceInstance.consecutivePingTimeouts})")
                        0L // Send immediately
                    } else {
                        PING_INTERVAL_MS // Normal interval
                    }
                    
                    if (interval > 0) {
                        delay(interval)
                    }
                    
                    // Only send ping if connected and network is available
                    if (serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.isCurrentlyConnected) {
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
         * NOTE: This should only be called when the service is being destroyed
         * During normal operation, the ping loop should run continuously even when disconnected
         * It automatically skips pings when not connected, and resumes immediately when connection is restored
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
        /**
         * PHASE 4: Register WebSocket send callback (supports multiple callbacks)
         * 
         * @param callbackId Unique identifier for this callback (e.g., "MainActivity", "BubbleActivity")
         * @param callback Function to handle WebSocket responses
         * @return true if registered successfully, false if already registered
         */
        fun registerWebSocketSendCallback(callbackId: String, callback: (String, Int, Map<String, Any>) -> Boolean): Boolean {
            synchronized(callbacksLock) {
                // Check if already registered
                if (webSocketSendCallbacks.any { it.first == callbackId }) {
                    android.util.Log.w("WebSocketService", "Callback already registered for: $callbackId")
                    return false
                }
                
                webSocketSendCallbacks.add(Pair(callbackId, callback))
                android.util.Log.d("WebSocketService", "Registered WebSocket callback for: $callbackId (total: ${webSocketSendCallbacks.size})")
                return true
            }
        }
        
        /**
         * PHASE 4: Unregister WebSocket send callback
         * 
         * @param callbackId Unique identifier for the callback to remove
         * @return true if unregistered successfully, false if not found
         */
        fun unregisterWebSocketSendCallback(callbackId: String): Boolean {
            synchronized(callbacksLock) {
                val removed = webSocketSendCallbacks.removeIf { it.first == callbackId }
                if (removed) {
                    android.util.Log.d("WebSocketService", "Unregistered WebSocket callback for: $callbackId (remaining: ${webSocketSendCallbacks.size})")
                } else {
                    android.util.Log.w("WebSocketService", "No callback found to unregister for: $callbackId")
                }
                return removed
            }
        }
        
        /**
         * PHASE 4: Register AppViewModel to receive WebSocket messages
         * Multiple ViewModels can register to receive all server messages
         * 
         * @param viewModelId Unique identifier (e.g., "MainActivity", "BubbleActivity")
         * @param viewModel The AppViewModel instance to receive messages
         */
        fun registerReceiveCallback(viewModelId: String, viewModel: AppViewModel) {
            synchronized(callbacksLock) {
                // Check if already registered
                if (webSocketReceiveCallbacks.any { it.first == viewModelId }) {
                    android.util.Log.w("WebSocketService", "Receive callback already registered for: $viewModelId")
                    return
                }
                
                webSocketReceiveCallbacks.add(Pair(viewModelId, viewModel))
                android.util.Log.d("WebSocketService", "Registered receive callback for: $viewModelId (total: ${webSocketReceiveCallbacks.size})")
            }
        }
        
        /**
         * PHASE 4: Unregister AppViewModel from receiving messages
         * 
         * @param viewModelId Unique identifier
         */
        fun unregisterReceiveCallback(viewModelId: String) {
            synchronized(callbacksLock) {
                val removed = webSocketReceiveCallbacks.removeIf { it.first == viewModelId }
                if (removed) {
                    android.util.Log.d("WebSocketService", "Unregistered receive callback for: $viewModelId (remaining: ${webSocketReceiveCallbacks.size})")
                } else {
                    android.util.Log.w("WebSocketService", "No receive callback found for: $viewModelId")
                }
            }
        }
        
        /**
         * PHASE 4: Get all registered AppViewModels
         * Used by NetworkUtils to distribute incoming messages
         * 
         * @return List of registered AppViewModel instances
         */
        fun getRegisteredViewModels(): List<AppViewModel> {
            synchronized(callbacksLock) {
                return webSocketReceiveCallbacks.map { it.second }
            }
        }
        
        /**
         * DEPRECATED: Use registerWebSocketSendCallback instead
         * Kept for backward compatibility
         */
        @Deprecated("Use registerWebSocketSendCallback instead", ReplaceWith("registerWebSocketSendCallback(\"legacy\", callback)"))
        fun setWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
            android.util.Log.d("WebSocketService", "setWebSocketSendCallback() called (deprecated - use registerWebSocketSendCallback)")
            registerWebSocketSendCallback("legacy", callback)
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
         * Trigger backend health check and reconnection if needed
         * This can be called from FCM or other external triggers
         */
        fun triggerBackendHealthCheck() {
            val serviceInstance = instance ?: return
            serviceScope.launch {
                try {
                    android.util.Log.d("WebSocketService", "Triggering manual backend health check")
                    val isHealthy = serviceInstance.checkBackendHealth()
                    
                    if (!isHealthy && (serviceInstance.connectionState == ConnectionState.CONNECTED || serviceInstance.connectionState == ConnectionState.DEGRADED)) {
                        android.util.Log.w("WebSocketService", "Manual backend health check failed - triggering reconnection")
                        triggerReconnectionFromExternal("Manual backend health check failed")
                    } else if (isHealthy) {
                        android.util.Log.d("WebSocketService", "Manual backend health check passed")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in manual backend health check", e)
                }
                }
            }
            
            /**
         * Stop the WebSocket service properly
         * This should be called when the app is being closed
         */
        fun stopService() {
            val serviceInstance = instance ?: return
            serviceInstance.stopService()
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
         * Safely trigger reconnection with validation
         * RUSH TO HEALTHY: Simplified - no exponential backoff, just validate and reconnect
         */
        fun triggerReconnectionSafely(reason: String) {
            android.util.Log.d("WebSocketService", "triggerReconnectionSafely called: $reason")
            
            // Validate service health first
            if (!validateServiceHealth()) {
                android.util.Log.e("WebSocketService", "CRITICAL: Service unhealthy - cannot trigger reconnection")
                return
            }
            
            // Check for state corruption before reconnection
            if (!detectAndRecoverStateCorruption()) {
                android.util.Log.w("WebSocketService", "State corruption detected and recovered before reconnection")
            }
            
            // Check if reconnection callback is available
            // If AppViewModel is not available, we should NOT create ad-hoc WebSocket connections
            // Wait for AppViewModel to connect the WebSocket properly when app starts
            if (reconnectionCallback == null) {
                android.util.Log.w("WebSocketService", "Reconnection callback not set (AppViewModel not available) - cannot reconnect. Waiting for app to connect WebSocket properly.")
                return
            }
            
            // RUSH TO HEALTHY: Use scheduled reconnection (handles backend health check and fast retry)
            scheduleReconnection(reason)
        }
        
        /**
         * Handle pong response
         * RUSH TO HEALTHY: Reset failure counter on any successful pong
         */
        fun handlePong(requestId: Int) {
            val serviceInstance = instance ?: return
            if (requestId == serviceInstance.lastPingRequestId) {
                serviceInstance.pongTimeoutJob?.cancel()
                serviceInstance.pongTimeoutJob = null
                
                val lagMs = System.currentTimeMillis() - serviceInstance.lastPingTimestamp
                serviceInstance.lastKnownLagMs = lagMs
                serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
                android.util.Log.d("WebSocketService", "Pong received, lag: ${lagMs}ms")
                
                // RUSH TO HEALTHY: Reset consecutive failures on successful pong
                if (serviceInstance.consecutivePingTimeouts > 0) {
                    android.util.Log.i("WebSocketService", "Pong received - resetting consecutive failures (was: ${serviceInstance.consecutivePingTimeouts})")
                }
                serviceInstance.consecutivePingTimeouts = 0
                
                // Update notification with lag and last sync timestamp
                updateConnectionStatus(true, lagMs, serviceInstance.lastSyncTimestamp)
            }
        }
        
        
        // RUSH TO HEALTHY: Removed smart timeout calculation - fixed 1 second timeout
        
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
        
        // Network monitoring removed: backend health checks and ping/pong now drive reconnections exclusively.
        
        /**
         * Set WebSocket connection
         */
        fun setWebSocket(webSocket: WebSocket) {
            traceToggle("setWebSocket", "state=${instance?.connectionState}")
            val serviceInstance = instance ?: return
            android.util.Log.i("WebSocketService", "setWebSocket() called - setting up WebSocket connection")
            android.util.Log.d("WebSocketService", "Current connection state: ${serviceInstance.connectionState}")
            
            // Validate state before setting WebSocket
            detectAndRecoverStateCorruption()
            
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
            // Track connection start time for duration display
            serviceInstance.connectionStartTime = System.currentTimeMillis()
            android.util.Log.d("WebSocketService", "Connection state set to CONNECTED")
            android.util.Log.d("WebSocketService", "WebSocket reference set: ${webSocket != null}")
            android.util.Log.d("WebSocketService", "Connection start time recorded: ${serviceInstance.connectionStartTime}")
            serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
            
            // Ensure network connectivity is marked as available when WebSocket connects
            serviceInstance.isCurrentlyConnected = true
            
            // Get the actual network type (not hardcoded to WiFi)
            // Get network type from the service instance's connectivity manager
            val actualNetworkType = serviceInstance.getNetworkTypeFromCapabilities()
            serviceInstance.currentNetworkType = actualNetworkType
            android.util.Log.d("WebSocketService", "Network type set to: $actualNetworkType")
            
            // Don't start ping loop yet - wait for first sync_complete to get lastReceivedSyncId
            android.util.Log.d("WebSocketService", "WebSocket connected, waiting for sync_complete before starting ping loop")
            
            // Log activity: WebSocket connected
            logActivity("WebSocket Connected", actualNetworkType.name)
            
            // Update notification with connection status
            updateConnectionStatus(true, null, serviceInstance.lastSyncTimestamp)
            
            android.util.Log.i("WebSocketService", "WebSocket connection established in service")
            logPingStatus()
        }
        
        /**
         * Clear WebSocket connection
         */
        fun clearWebSocket(reason: String = "Unknown") {
            traceToggle("clearWebSocket")
            val serviceInstance = instance ?: return
            
            // Only log disconnection if we actually had a connection
            val wasConnected = serviceInstance.connectionState == ConnectionState.CONNECTED || serviceInstance.webSocket != null
            
            if (!wasConnected) {
                // Already disconnected, don't log redundant disconnection
                android.util.Log.d("WebSocketService", "clearWebSocket() called but already disconnected - skipping")
                return
            }
            
            android.util.Log.w("WebSocketService", "clearWebSocket() called - setting connection state to DISCONNECTED")
            
            // Validate state before clearing
            detectAndRecoverStateCorruption()
            
            // Close the WebSocket properly before clearing the reference
            serviceInstance.webSocket?.close(1000, "Clearing connection")
            serviceInstance.webSocket = null
            serviceInstance.connectionState = ConnectionState.DISCONNECTED
            serviceInstance.isCurrentlyConnected = false
            
            // Reset connection start time
            serviceInstance.connectionStartTime = 0
            
            // Cancel any pending pong timeouts (ping loop keeps running)
            serviceInstance.pongTimeoutJob?.cancel()
            serviceInstance.pongTimeoutJob = null
            
            // Reset connection health tracking
            serviceInstance.lastKnownLagMs = null
            serviceInstance.lastPongTimestamp = 0L
            
            // Reset ping loop state for next connection (ready-state flag stays true so failsafe can run)
            serviceInstance.pingLoopStarted = false
            
            // Log activity: WebSocket disconnected
            logActivity("WebSocket Disconnected - $reason", serviceInstance.currentNetworkType.name)
            
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
         * Check if service is healthy and can handle reconnections
         */
        fun isServiceHealthy(): Boolean {
            val serviceInstance = instance ?: return false
            return reconnectionCallback != null &&
                   serviceInstance.connectionState != ConnectionState.DISCONNECTED
        }
        
        /**
         * Validate service health and attempt recovery if needed
         */
        fun validateServiceHealth(): Boolean {
            if (isServiceHealthy()) {
                return true
            }
            
            android.util.Log.w("WebSocketService", "Service health check failed - attempting recovery")
            
            // Try to recover by reinitializing critical components
            val serviceInstance = instance ?: return false
            
            // Check if reconnection callback is missing
            if (reconnectionCallback == null) {
                android.util.Log.w("WebSocketService", "Recovering missing reconnection callback")
                // This should be set by AppViewModel, but we can't recover it here
                return false
            }
            
            return true
        }
        
        /**
         * Detect and recover from state corruption
         * SIMPLIFIED: Only essential checks - ping/pong handles connection health
         */
        fun detectAndRecoverStateCorruption(): Boolean {
            val serviceInstance = instance ?: return false
            var corruptionDetected = false
            
            android.util.Log.d("WebSocketService", "Running state corruption detection...")
            
            // 1. Check for stuck reconnecting state (>60s)
            if (serviceInstance.isReconnecting && 
                serviceInstance.connectionState == ConnectionState.RECONNECTING &&
                System.currentTimeMillis() - serviceInstance.lastReconnectionTime > 60_000) {
                android.util.Log.w("WebSocketService", "CORRUPTION: Stuck in reconnecting state for >60s - recovering")
                serviceInstance.isReconnecting = false
                serviceInstance.connectionState = ConnectionState.DISCONNECTED
                corruptionDetected = true
            }
            
            // 2. Check for inconsistent connection state (WebSocket vs state mismatch)
            val hasWebSocket = serviceInstance.webSocket != null
            val isConnected = serviceInstance.connectionState == ConnectionState.CONNECTED
            
            if (hasWebSocket && !isConnected) {
                android.util.Log.w("WebSocketService", "CORRUPTION: WebSocket exists but state is not CONNECTED - recovering")
                serviceInstance.connectionState = ConnectionState.CONNECTED
                corruptionDetected = true
            } else if (!hasWebSocket && isConnected) {
                android.util.Log.w("WebSocketService", "CORRUPTION: State is CONNECTED but no WebSocket - recovering")
                serviceInstance.connectionState = ConnectionState.DISCONNECTED
                corruptionDetected = true
            }
            
            // 3. Check for stuck pong timeout (should only be active when connected and waiting for pong)
            if (serviceInstance.pongTimeoutJob?.isActive == true && 
                serviceInstance.connectionState != ConnectionState.CONNECTED) {
                android.util.Log.w("WebSocketService", "CORRUPTION: Pong timeout running but not connected - stopping timeout")
                serviceInstance.pongTimeoutJob?.cancel()
                serviceInstance.pongTimeoutJob = null
                corruptionDetected = true
            }
            
            if (corruptionDetected) {
                android.util.Log.i("WebSocketService", "State corruption detected and recovered")
                serviceInstance.updateConnectionStatus(serviceInstance.connectionState == ConnectionState.CONNECTED)
            } else {
                android.util.Log.d("WebSocketService", "No state corruption detected")
            }
            
            return !corruptionDetected
        }
        
        /**
         * Force state recovery - nuclear option
         */
        fun forceStateRecovery() {
            val serviceInstance = instance ?: return
            android.util.Log.w("WebSocketService", "FORCE RECOVERY: Resetting all state to clean state")
            
            // Cancel all jobs
            serviceInstance.pingJob?.cancel()
            serviceInstance.pongTimeoutJob?.cancel()
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.stateCorruptionJob?.cancel()
            
            // Reset all state
            serviceInstance.isReconnecting = false
            serviceInstance.isCurrentlyConnected = false
            serviceInstance.connectionState = ConnectionState.DISCONNECTED
            serviceInstance.consecutivePingTimeouts = 0
            serviceInstance.lastReconnectionTime = 0
            
            // Clear WebSocket
            serviceInstance.webSocket?.close(1000, "Force recovery")
            serviceInstance.webSocket = null
            
            // Update notification
            serviceInstance.updateConnectionStatus(false)
            
            android.util.Log.i("WebSocketService", "Force recovery completed - all state reset")
        }
        
        /**
         * Manually trigger state corruption detection
         * This can be called from external sources (like FCM) to check for corruption
         */
        fun checkStateCorruption() {
            android.util.Log.d("WebSocketService", "Manual state corruption check requested")
            detectAndRecoverStateCorruption()
        }
        
        /**
         * Get WebSocket instance
         */
        fun getWebSocket(): WebSocket? {
            return instance?.webSocket
        }
        
        /**
         * Set reconnection state (last_received_event only)
         * run_id is always read from SharedPreferences - not tracked in service state
         * vapid_key is not used (we use FCM)
         */
        fun setReconnectionState(lastReceivedId: Int) {
            val serviceInstance = instance ?: return
            serviceInstance.lastReceivedSyncId = lastReceivedId
            android.util.Log.d("WebSocketService", "Updated last_received_sync_id: $lastReceivedId (run_id read from SharedPreferences)")
        }
        
        /**
         * Get run_id from SharedPreferences (always use stored value)
         */
        fun getCurrentRunId(): String {
            val serviceInstance = instance ?: return ""
            return try {
                val prefs = serviceInstance.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
                prefs.getString("ws_run_id", "") ?: ""
            } catch (e: Exception) {
                android.util.Log.e("WebSocketService", "Failed to read run_id from SharedPreferences", e)
                ""
            }
        }
        
        /**
         * Get last received sync ID for reconnection
         */
        fun getLastReceivedSyncId(): Int = instance?.lastReceivedSyncId ?: 0
        
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
                serviceInstance.hasEverReachedReadyState = true
                android.util.Log.i("WebSocketService", "First sync_complete received, starting ping loop")
                logActivity("WebSocket Ready", serviceInstance.currentNetworkType.name)
                startPingLoop()
            }
            
            // Update notification with current lag and new sync timestamp
            updateConnectionStatus(true, serviceInstance.lastKnownLagMs, serviceInstance.lastSyncTimestamp)
        }
        
        /**
         * Mark that at least one sync_complete has been persisted this session.
         * This unlocks the ability to include last_received_event on reconnections.
         */
        fun markInitialSyncPersisted() {
            val serviceInstance = instance ?: return
            if (!serviceInstance.hasPersistedSync) {
                serviceInstance.hasPersistedSync = true
                android.util.Log.d("WebSocketService", "Initial sync persisted - last_received_event will be included on reconnections")
            }
        }
        
        /**
         * Clear reconnection state (only last_received_sync_id - run_id stays in SharedPreferences)
         */
        fun clearReconnectionState() {
            val serviceInstance = instance ?: return
            serviceInstance.lastReceivedSyncId = 0
            serviceInstance.hasPersistedSync = false
            android.util.Log.d("WebSocketService", "Cleared reconnection state (last_received_sync_id reset, run_id preserved in SharedPreferences)")
        }
        
        /**
         * Get reconnection parameters for WebSocket URL construction
         * Returns: (runId from SharedPreferences, lastReceivedSyncId, isReconnecting)
         */
        fun getReconnectionParameters(): Triple<String, Int, Boolean> {
            val serviceInstance = instance ?: return Triple("", 0, false)
            val runId = getCurrentRunId() // Always read from SharedPreferences
            val lastReceivedId = serviceInstance.lastReceivedSyncId
            val isReconnecting = lastReceivedId > 0 // If we have lastReceivedId, we're reconnecting
            android.util.Log.d("WebSocketService", "getReconnectionParameters: runId='$runId' (from SharedPreferences), lastReceivedSyncId=$lastReceivedId, isReconnecting=$isReconnecting")
            return Triple(runId, lastReceivedId, isReconnecting)
        }
        
        /**
         * Reset reconnection state (called on successful connection)
         */
        fun resetReconnectionState() {
            val serviceInstance = instance ?: return
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            serviceInstance.isReconnecting = false
            serviceInstance.connectionState = ConnectionState.CONNECTED
            android.util.Log.d("WebSocketService", "Reset reconnection state (successful connection)")
        }
        
        /**
         * Schedule WebSocket reconnection with backend health check
         * RUSH TO HEALTHY: Fast retry with backend health check, no exponential backoff
         */
        fun scheduleReconnection(reason: String) {
            val serviceInstance = instance ?: return
            
            // ATOMIC GUARD: Use synchronized lock to prevent parallel reconnection attempts
            synchronized(serviceInstance.reconnectionLock) {
                val currentTime = System.currentTimeMillis()
                
                // Check if already reconnecting - if so, drop redundant request
                if (serviceInstance.connectionState == ConnectionState.RECONNECTING || serviceInstance.isReconnecting) {
                    android.util.Log.d("WebSocketService", "Already reconnecting, dropping redundant request: $reason")
                    return
                }
                
                // Set reconnecting flag atomically
                serviceInstance.isReconnecting = true
                
                // Check minimum interval between reconnections (prevent rapid-fire reconnections)
                if (currentTime - serviceInstance.lastReconnectionTime < MIN_RECONNECTION_INTERVAL_MS) {
                    android.util.Log.d("WebSocketService", "Too soon since last reconnection, ignoring: $reason")
                    serviceInstance.isReconnecting = false
                    return
                }
                
                // Cancel any existing reconnection job
                serviceInstance.reconnectionJob?.cancel()
                
                serviceInstance.connectionState = ConnectionState.RECONNECTING
                serviceInstance.lastReconnectionTime = currentTime
                
                android.util.Log.w("WebSocketService", "Scheduling reconnection: $reason")
                
                serviceInstance.reconnectionJob = serviceScope.launch {
                    // RUSH TO HEALTHY: Check backend health first, then reconnect
                    val backendHealthy = serviceInstance.checkBackendHealth()
                    
                    if (backendHealthy) {
                        // Backend healthy - reconnect immediately with short delay
                        delay(BASE_RECONNECTION_DELAY_MS)
                    } else {
                        // Backend unhealthy - wait longer before retry
                        android.util.Log.w("WebSocketService", "Backend unhealthy - waiting ${BACKEND_HEALTH_RETRY_DELAY_MS}ms before retry")
                        delay(BACKEND_HEALTH_RETRY_DELAY_MS)
                    }
                    
                    // Reset reconnecting flag when job completes
                    synchronized(serviceInstance.reconnectionLock) {
                        serviceInstance.isReconnecting = false
                    }
                    
                    if (isActive) {
                        android.util.Log.d("WebSocketService", "Executing reconnection: $reason")
                        reconnectionCallback?.invoke(reason)
                    } else {
                        serviceInstance.connectionState = ConnectionState.DISCONNECTED
                    }
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
            clearWebSocket(reason)
            } else {
                android.util.Log.d("WebSocketService", "Network restored - skipping clearWebSocket to preserve state")
            }
            
            // Reset reconnection state to allow new reconnection
            serviceInstance.isReconnecting = false
            
            // Add a small delay to ensure WebSocket is properly closed
            serviceScope.launch {
                delay(1000) // 1 second delay to ensure proper closure
                android.util.Log.d("WebSocketService", "Triggering reconnection after delay")
                
                // Check if this is called from FCM (external trigger) or from AppViewModel
                val isExternalTrigger = !reason.contains("Network restored") && 
                                       !reason.contains("Failsafe reconnection") &&
                                       reason.contains("FCM")
                
                if (isExternalTrigger) {
                    // External trigger (FCM) - don't use callback to avoid infinite loop
                    android.util.Log.w("WebSocketService", "External trigger detected - cannot connect WebSocket without AppViewModel")
                    android.util.Log.w("WebSocketService", "WebSocket will remain in 'Connecting...' state until app is opened manually")
                    return@launch
                }
                
                // Internal trigger - use callback
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
        
    }
    
    // Instance variables for WebSocket state management
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var lastPingRequestId: Int = 0
    private var lastPingTimestamp: Long = 0
    private var isAppVisible = false
    
    // Notification state cache for idempotent updates
    private var lastNotificationText: String? = null
    private var lastNotificationUpdateTime: Long = 0
    
    // RUSH TO HEALTHY: Removed network optimization variables - ping/pong is the authority
    
    private var stateCorruptionJob: Job? = null // State corruption monitoring
    private var isCurrentlyConnected = false
    private var currentNetworkType: NetworkType = NetworkType.NONE
    
    // WebSocket connection management
    private var webSocket: WebSocket? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private var lastPongTimestamp = 0L // Track last pong for heartbeat monitoring
    private var connectionStartTime: Long = 0 // Track when WebSocket connection was established (0 = not connected)
    private var hasPersistedSync = false
    
        // Reconnection state management
        // run_id is always read from SharedPreferences - not stored in service state
        // vapid_key is not used (we use FCM)
        private var lastReceivedSyncId: Int = 0
    
    // Connection health tracking
    private var lastSyncTimestamp: Long = 0
    private var lastKnownLagMs: Long? = null
    private var consecutivePingTimeouts: Int = 0 // Track consecutive ping timeouts for network quality detection
    
    // Reconnection logic state (simplified - no exponential backoff)
    private var reconnectionJob: Job? = null
    
    private var isReconnecting = false // Prevent multiple simultaneous reconnections
    private var lastReconnectionTime = 0L
    
    // Atomic lock for preventing parallel reconnection attempts (compare-and-set)
    private val reconnectionLock = Any()
    
    // Ping loop state
    private var pingLoopStarted = false // Track if ping loop has been started after first sync_complete
    private var hasEverReachedReadyState = false // Track if we have ever received sync_complete on this run
    
    // RUSH TO HEALTHY: Fixed ping interval - no adaptive logic needed
    // Ping/pong failures are handled by immediate retry and dropping after 3 failures
    
    /**
     * Check backend health by making a simple HTTP GET request
     * This is faster than WebSocket ping/pong and catches backend-specific issues
     */
    private suspend fun checkBackendHealth(): Boolean {
        return try {
            val sharedPrefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
            val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
            
            if (homeserverUrl.isEmpty()) {
                android.util.Log.w("WebSocketService", "Backend health check skipped - no homeserver URL")
                return false
            }
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url(homeserverUrl)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val isHealthy = response.isSuccessful && response.code == 200
            
            android.util.Log.d("WebSocketService", "Backend health check: ${response.code} - ${if (isHealthy) "HEALTHY" else "UNHEALTHY"}")
            
            response.close()
            isHealthy
        } catch (e: Exception) {
            android.util.Log.w("WebSocketService", "Backend health check failed", e)
            false
        }
    }
    
    /**
     * Start periodic state corruption monitoring
     */
    private fun startStateCorruptionMonitoring() {
        stateCorruptionJob?.cancel()
        stateCorruptionJob = serviceScope.launch {
            while (isActive) {
                delay(60_000) // Check every 60 seconds
                
                try {
                    android.util.Log.d("WebSocketService", "Running periodic state corruption check")
                    WebSocketService.detectAndRecoverStateCorruption()
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in state corruption monitoring", e)
                }
            }
        }
        android.util.Log.d("WebSocketService", "State corruption monitoring started")
    }
    
    /**
     * Stop state corruption monitoring
     */
    private fun stopStateCorruptionMonitoring() {
        stateCorruptionJob?.cancel()
        stateCorruptionJob = null
        android.util.Log.d("WebSocketService", "State corruption monitoring stopped")
    }
    
    private fun shouldIncludeLastReceivedForReconnect(): Boolean {
        return hasPersistedSync && lastReceivedSyncId != 0
    }
    
    // REMOVED: attemptFallbackReconnection() - This created ad-hoc WebSocket connections
    // without AppViewModel callbacks, making them "unhealthy" (reconnectionCallback=false).
    // WebSocket connections should ONLY be created by AppViewModel via NetworkUtils.connectToWebsocket()
    // to ensure proper callback registration and message processing.
    
    /**
     * Properly stop the WebSocket service
     * This should be called when the app is being closed or when we want to stop the service
     */
    fun stopService() {
        android.util.Log.d("WebSocketService", "Stopping WebSocket service")
        
        try {
            // Stop monitoring jobs immediately
            stopStateCorruptionMonitoring()
            
            // Cancel all coroutine jobs with timeout
            val jobs = listOf(
                pingJob,
                pongTimeoutJob,
                reconnectionJob,
                stateCorruptionJob
            )
            
            jobs.forEach { job ->
                job?.cancel()
            }
            
            // Clear WebSocket connection immediately
            webSocket?.close(1000, "Service stopped")
            webSocket = null
            
            // Reset connection state
            connectionState = ConnectionState.DISCONNECTED
            isReconnecting = false
            isCurrentlyConnected = false
            
            // Stop foreground service immediately
            stopForeground(true)
            
            // Stop self immediately
            stopSelf()
            
            android.util.Log.d("WebSocketService", "WebSocket service stopped successfully")
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Error stopping service", e)
            // Even if there's an error, try to stop the service
            try {
                stopSelf()
            } catch (e2: Exception) {
                android.util.Log.e("WebSocketService", "Error in fallback stopSelf", e2)
            }
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
     * RUSH TO HEALTHY: 1 second timeout, immediate retry on failure, drop after 3 consecutive failures
     */
    private fun startPongTimeout(pingRequestId: Int) {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = WebSocketService.serviceScope.launch {
            delay(PONG_TIMEOUT_MS) // 1 second timeout
            
            // Check if we're still connected before processing timeout
            if (connectionState != ConnectionState.CONNECTED || !isCurrentlyConnected) {
                android.util.Log.d("WebSocketService", "Pong timeout but connection already inactive - ignoring")
                return@launch
            }
            
            android.util.Log.w("WebSocketService", "Pong timeout for ping $pingRequestId (${PONG_TIMEOUT_MS}ms)")
            
            // Increment consecutive failure counter
            consecutivePingTimeouts++
            android.util.Log.w("WebSocketService", "Consecutive ping failures: $consecutivePingTimeouts")
            
            // RUSH TO HEALTHY: If 3 consecutive failures, drop WebSocket and reconnect
            if (consecutivePingTimeouts >= CONSECUTIVE_FAILURES_TO_DROP) {
                android.util.Log.e("WebSocketService", "3 consecutive ping failures - dropping WebSocket and reconnecting")
                consecutivePingTimeouts = 0
                
                // Check backend health
                val backendHealthy = checkBackendHealth()
                
                if (backendHealthy) {
                    // Backend is healthy - reconnect immediately
                    android.util.Log.i("WebSocketService", "Backend healthy - triggering immediate reconnection")
                    clearWebSocket("3 consecutive ping failures - backend healthy")
                    reconnectionCallback?.invoke("3 consecutive ping failures - backend healthy")
                } else {
                    // Backend unhealthy - wait for recovery
                    android.util.Log.w("WebSocketService", "Backend unhealthy - waiting for recovery")
                    clearWebSocket("3 consecutive ping failures - backend unhealthy")
                    
                    // Poll backend health and reconnect when healthy
                    while (isActive) {
                        delay(BACKEND_HEALTH_RETRY_DELAY_MS)
                        if (!isActive) return@launch
                        
                        val recovered = checkBackendHealth()
                        if (recovered) {
                            android.util.Log.i("WebSocketService", "Backend healthy again - triggering reconnection")
                            reconnectionCallback?.invoke("Backend recovered after ping failures")
                            return@launch
                        }
                    }
                }
            } else {
                // RUSH TO HEALTHY: Send next ping immediately (don't wait for normal interval)
                android.util.Log.d("WebSocketService", "Ping failure #$consecutivePingTimeouts - sending next ping immediately")
                // The ping loop will send immediately when it sees we're still connected
            }
        }
    }
    
    // RUSH TO HEALTHY: Removed network metrics - ping/pong failures are the only metric we need
        

    
    // Instance methods
    
    /**
     * Get current network type from capabilities
     */
    fun getNetworkTypeFromCapabilities(): NetworkType {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.NONE
        
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
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
        DEGRADED,        // Connected but experiencing issues (timeouts, poor quality)
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
        
        // run_id is always read from SharedPreferences when needed - no need to restore on startup
        // Reset last_received_sync_id on service startup (will be set when sync_complete arrives)
        lastReceivedSyncId = 0
        hasPersistedSync = false
        android.util.Log.d("WebSocketService", "Service startup - last_received_sync_id reset (run_id will be read from SharedPreferences when needed)")
        
        // Start state corruption monitoring immediately when service is created
        android.util.Log.d("WebSocketService", "Starting service state monitoring from onCreate")
        startStateCorruptionMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("WebSocketService", "Service started with intent: ${intent?.action}")
        
        // Handle stop request
        if (intent?.action == "STOP_SERVICE") {
            android.util.Log.d("WebSocketService", "Stop service requested via intent")
            stopService()
            return START_NOT_STICKY
        }
        
        // Start as foreground service with notification
        // This keeps the app process alive and prevents Android from killing it
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires explicit service type
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                android.util.Log.d("WebSocketService", "Foreground service started successfully (Android 14+)")
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
                android.util.Log.d("WebSocketService", "Foreground service started successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to start foreground service", e)
            // Don't crash - service will run in background
        }
        
        // RESILIENCE: Check connection state on service restart
        // If service was restarted by Android and WebSocket is disconnected, attempt reconnection
        // BUT: Only if AppViewModel is available (reconnectionCallback set)
        // We should NOT create ad-hoc WebSocket connections - only AppViewModel should create them
        serviceScope.launch {
            delay(2000) // Wait 2 seconds for service to fully initialize
            
            val isConnected = connectionState == ConnectionState.CONNECTED && webSocket != null
            
            android.util.Log.d("WebSocketService", "Service restart check: connected=$isConnected, reconnectionCallback=${reconnectionCallback != null}")
            
            // If we have no active connection and we're not already reconnecting, attempt reconnection
            // BUT: Only if AppViewModel is available (reconnectionCallback is set)
            // If AppViewModel is not available, wait for it to connect the WebSocket properly
            if (!isConnected && !isReconnecting) {
                // Store callback in local variable for safe smart cast
                val callback = reconnectionCallback
                if (callback != null) {
                    // AppViewModel is available - trigger reconnection through it
                    android.util.Log.w("WebSocketService", "Service restarted but WebSocket disconnected - triggering reconnection via AppViewModel")
                    try {
                        callback("Service restarted - reconnecting")
                    } catch (e: Exception) {
                        android.util.Log.e("WebSocketService", "Reconnection callback failed", e)
                    }
                } else {
                    // AppViewModel not available - DO NOT create ad-hoc WebSocket
                    // Wait for AppViewModel to connect the WebSocket properly when app starts
                    android.util.Log.d("WebSocketService", "Service restarted but AppViewModel not available - waiting for app to connect WebSocket properly")
                }
            } else if (isConnected) {
                android.util.Log.d("WebSocketService", "Service restarted and WebSocket already connected")
            }
        }
        
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        
        android.util.Log.d("WebSocketService", "Service onDestroy() called - cleaning up resources")
        
        try {
            // Stop all monitoring and jobs
            stopStateCorruptionMonitoring()
            
            // Cancel all coroutine jobs
            pingJob?.cancel()
            pongTimeoutJob?.cancel()
            reconnectionJob?.cancel()
            stateCorruptionJob?.cancel()
            
            // Clear WebSocket connection
            webSocket?.close(1000, "Service destroyed")
            webSocket = null
            
            // Reset connection state
            connectionState = ConnectionState.DISCONNECTED
            isReconnecting = false
            isCurrentlyConnected = false
            hasPersistedSync = false
            
            // Stop foreground service
            stopForeground(true)
            
            // Clear instance reference
        instance = null
            
            android.util.Log.d("WebSocketService", "Service cleanup completed")
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Error during service cleanup", e)
        }
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
        traceToggle("updateConnectionStatus", "isConnected=$isConnected lag=$lagMs lastSync=$lastSyncTimestamp")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notificationText = when {
            !isConnected -> "Connecting... • ${getNetworkTypeDisplayName(currentNetworkType)}"
            connectionState == ConnectionState.DEGRADED -> {
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
                
                // Calculate and format connection duration
                val durationText = if (connectionStartTime > 0) {
                    val durationSeconds = (System.currentTimeMillis() - connectionStartTime) / 1000
                    when {
                        durationSeconds < 60 -> "${durationSeconds}s"
                        durationSeconds < 3600 -> "${durationSeconds / 60}m"
                        else -> "${durationSeconds / 3600}h"
                    }
                } else {
                    "0s"
                }
                
                "⚠️ Degraded • ${getNetworkTypeDisplayName(currentNetworkType)} • Lag: $lagText • Last: $lastSyncText • Up: $durationText"
            }
            else -> {
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
            
            // Calculate and format connection duration
            val durationText = if (connectionStartTime > 0) {
                val durationSeconds = (System.currentTimeMillis() - connectionStartTime) / 1000
                when {
                    durationSeconds < 60 -> "${durationSeconds}s"
                    durationSeconds < 3600 -> "${durationSeconds / 60}m"
                    else -> "${durationSeconds / 3600}h"
                }
            } else {
                "0s"
            }
            
            "$healthIndicator • ${getNetworkTypeDisplayName(currentNetworkType)} • Lag: $lagText • Last: $lastSyncText • Up: $durationText"
            }
        }
        
        // IDEMPOTENT: Only update notification if the text actually changed
        if (lastNotificationText == notificationText) {
            Log.d("WebSocketService", "Skipping notification update - text unchanged: $notificationText")
            return
        }
        
        // THROTTLE: Prevent rapid notification updates (UI smoothing)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastNotificationUpdateTime
        
        if (timeSinceLastUpdate < MIN_NOTIFICATION_UPDATE_INTERVAL_MS) {
            // Too soon - skip this update to avoid flicker
            Log.d("WebSocketService", "Throttling notification update (${timeSinceLastUpdate}ms < ${MIN_NOTIFICATION_UPDATE_INTERVAL_MS}ms)")
            
            // Schedule a delayed update to ensure we eventually update
            serviceScope.launch {
                val delayNeeded = MIN_NOTIFICATION_UPDATE_INTERVAL_MS - timeSinceLastUpdate
                delay(delayNeeded)
                
                // Check again after delay (text might have changed again)
                if (lastNotificationText != notificationText) {
                    lastNotificationText = notificationText
                    lastNotificationUpdateTime = System.currentTimeMillis()
                    
                    // Create and show notification
                    val delayedNotification = NotificationCompat.Builder(this@WebSocketService, CHANNEL_ID)
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
                    notificationManager.notify(NOTIFICATION_ID, delayedNotification)
                    
                    Log.d("WebSocketService", "Delayed notification update: $notificationText")
                }
            }
            return
        }
        
        lastNotificationText = notificationText
        lastNotificationUpdateTime = currentTime
        
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
