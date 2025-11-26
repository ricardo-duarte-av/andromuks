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
import net.vrkknn.andromuks.utils.NetworkMonitor
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
import net.vrkknn.andromuks.BuildConfig


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
        private const val INIT_COMPLETE_TIMEOUT_MS = 15_000L // 15 seconds to wait for init_complete
        private const val INIT_COMPLETE_RETRY_BASE_MS = 2_000L // 2 seconds initial retry delay
        private const val INIT_COMPLETE_RETRY_MAX_MS = 64_000L // 64 seconds max retry delay
        private val TOGGLE_STACK_DEPTH = 6
        private val toggleCounter = AtomicLong(0)
        
        // PHASE 4: WebSocket Callback Queues (allows multiple ViewModels to interact)
        
        // Send callbacks: For sending commands FROM service TO ViewModels (e.g., ping/pong)
        private val webSocketSendCallbacks = mutableListOf<Pair<String, (String, Int, Map<String, Any>) -> Boolean>>()
        
        // PHASE 4: Receive callbacks: For distributing messages FROM server TO ViewModels
        // When a message arrives from server, all registered ViewModels are notified
        private val webSocketReceiveCallbacks = mutableListOf<Pair<String, AppViewModel>>()
        
        private val callbacksLock = Any() // Thread safety for callback lists
        
        // PHASE 1.1: Primary instance tracking - ensures only one AppViewModel controls WebSocket lifecycle
        // Primary instance is the one that manages reconnection, offline mode, and activity logging
        private var primaryViewModelId: String? = null
        
        // Primary callbacks - only the primary instance can set these
        private var primaryReconnectionCallback: ((String) -> Unit)? = null
        private var primaryOfflineModeCallback: ((Boolean) -> Unit)? = null
        private var primaryActivityLogCallback: ((String, String?) -> Unit)? = null
        
        // Legacy callbacks - kept for backward compatibility during migration
        // These will be removed in a future phase after migration is complete
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
        // Legacy callback - kept for backward compatibility
        private var activityLogCallback: ((String, String?) -> Unit)? = null
        
        /**
         * PHASE 1.1: Get the primary ViewModel ID
         * Returns the ID of the AppViewModel instance that is registered as primary
         */
        fun getPrimaryViewModelId(): String? = primaryViewModelId
        
        /**
         * PHASE 1.1: Check if a ViewModel ID is the primary instance
         */
        fun isPrimaryInstance(viewModelId: String): Boolean {
            return primaryViewModelId == viewModelId
        }
        
        /**
         * PHASE 1.2: Set callback for logging activity events
         * Only the primary instance can register this callback
         * 
         * @param viewModelId Unique identifier for the AppViewModel instance
         * @param callback Callback function to invoke when activity events occur
         * @return true if registration succeeded, false if another instance is already primary
         */
        fun setActivityLogCallback(viewModelId: String, callback: (String, String?) -> Unit): Boolean {
            synchronized(callbacksLock) {
                // Check if this is the primary instance
                if (primaryViewModelId != null && primaryViewModelId != viewModelId) {
                    android.util.Log.w("WebSocketService", "setActivityLogCallback: Instance $viewModelId is not the primary instance ($primaryViewModelId). Rejecting registration.")
                    return false
                }
                
                // Must have registered reconnection callback first (enforces primary instance)
                if (primaryViewModelId != viewModelId) {
                    android.util.Log.w("WebSocketService", "setActivityLogCallback: Instance $viewModelId must register reconnection callback first. Rejecting registration.")
                    return false
                }
                
                primaryActivityLogCallback = callback
                // Also set legacy callback for backward compatibility
                activityLogCallback = callback
                
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setActivityLogCallback: Successfully registered primary activity log callback for $viewModelId")
                return true
            }
        }
        
        /**
         * Legacy method - kept for backward compatibility
         * @deprecated Use setActivityLogCallback(viewModelId, callback) instead
         */
        @Deprecated("Use setActivityLogCallback(viewModelId, callback) instead", ReplaceWith("setActivityLogCallback(\"legacy\", callback)"))
        fun setActivityLogCallback(callback: (String, String?) -> Unit) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setActivityLogCallback() called (legacy)")
            // Legacy: allow registration without viewModelId, but don't set as primary
            activityLogCallback = callback
        }
        
        /**
         * PHASE 1.2: Clear primary callbacks for a specific ViewModel instance
         * This should be called when the primary instance is being destroyed
         * 
         * @param viewModelId Unique identifier for the AppViewModel instance
         * @return true if callbacks were cleared, false if this instance was not primary
         */
        fun clearPrimaryCallbacks(viewModelId: String): Boolean {
            synchronized(callbacksLock) {
                if (primaryViewModelId != viewModelId) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearPrimaryCallbacks: Instance $viewModelId is not the primary instance ($primaryViewModelId). Nothing to clear.")
                    return false
                }
                
                android.util.Log.i("WebSocketService", "clearPrimaryCallbacks: Clearing primary callbacks for $viewModelId")
                
                primaryViewModelId = null
                primaryReconnectionCallback = null
                primaryOfflineModeCallback = null
                primaryActivityLogCallback = null
                
                // Note: We don't clear legacy callbacks here to maintain backward compatibility
                // Legacy callbacks will be cleared by the legacy cleanup code if needed
                
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearPrimaryCallbacks: Successfully cleared primary callbacks for $viewModelId")
                return true
            }
        }
        
        /**
         * PHASE 1.2: Get the active reconnection callback (primary first, then legacy)
         * Internal helper to ensure we use primary callback when available
         */
        private fun getActiveReconnectionCallback(): ((String) -> Unit)? {
            return primaryReconnectionCallback ?: reconnectionCallback
        }
        
        /**
         * PHASE 1.2: Get the active offline mode callback (primary first, then legacy)
         * Internal helper to ensure we use primary callback when available
         */
        private fun getActiveOfflineModeCallback(): ((Boolean) -> Unit)? {
            return primaryOfflineModeCallback ?: offlineModeCallback
        }
        
        /**
         * PHASE 1.4: Safely invoke reconnection callback with error handling and logging
         * 
         * @param reason Reason for reconnection
         * @param logIfMissing Whether to log a warning if callback is missing (default: true)
         */
        private fun invokeReconnectionCallback(reason: String, logIfMissing: Boolean = true) {
            val callback = getActiveReconnectionCallback()
            if (callback != null) {
                try {
                    callback.invoke(reason)
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Reconnection callback invoked: $reason")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error invoking reconnection callback: ${e.message}", e)
                }
            } else {
                if (logIfMissing) {
                    android.util.Log.w("WebSocketService", "Reconnection callback not available - cannot trigger reconnection: $reason")
                }
            }
        }
        
        /**
         * PHASE 1.4: Safely invoke offline mode callback with error handling and logging
         * 
         * @param isOffline Whether the app is in offline mode
         * @param logIfMissing Whether to log a warning if callback is missing (default: false)
         */
        private fun invokeOfflineModeCallback(isOffline: Boolean, logIfMissing: Boolean = false) {
            val callback = getActiveOfflineModeCallback()
            if (callback != null) {
                try {
                    callback.invoke(isOffline)
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Offline mode callback invoked: isOffline=$isOffline")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error invoking offline mode callback: ${e.message}", e)
                }
            } else {
                if (logIfMissing) {
                    android.util.Log.w("WebSocketService", "Offline mode callback not available - cannot notify offline mode change: isOffline=$isOffline")
                }
            }
        }
        
        /**
         * Log an activity event (app started, websocket connected, disconnected, etc.)
         * PHASE 1.4: Uses primary callback if available, falls back to legacy callback with error handling
         */
        fun logActivity(event: String, networkType: String? = null) {
            // Try primary callback first
            val callback = primaryActivityLogCallback ?: activityLogCallback
            if (callback != null) {
                try {
                    callback.invoke(event, networkType)
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error invoking activity log callback: ${e.message}", e)
                }
            }
            // Note: We don't log warnings for missing activity log callbacks as it's not critical
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop coroutine started")
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop: isActive=$isActive, connectionState=${instance?.connectionState}")
                
                // Send immediate ping when loop starts (if conditions are met)
                val serviceInstance = instance
                if (serviceInstance != null && serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.isCurrentlyConnected) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop: sending immediate ping on start")
                    serviceInstance.sendPing()
                }
                
                // Persistent loop - don't exit when disconnected, just skip pings
                while (isActive) {
                    val serviceInstance = instance ?: break
                    
                    // RUSH TO HEALTHY: If we have consecutive failures, send ping with minimal delay (prevent storm)
                    val interval = if (serviceInstance.consecutivePingTimeouts > 0) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "RUSH TO HEALTHY: Sending ping after failure (consecutive: ${serviceInstance.consecutivePingTimeouts})")
                        100L // Small delay to prevent ping storm (100ms minimum)
                    } else {
                        PING_INTERVAL_MS // Normal interval
                    }
                    
                    if (interval > 0) {
                        delay(interval)
                    }
                    
                    // Only send ping if connected, network is available, and no ping is already in flight
                    if (serviceInstance.connectionState == ConnectionState.CONNECTED && 
                        serviceInstance.isCurrentlyConnected && 
                        !serviceInstance.pingInFlight) {
                        serviceInstance.sendPing()
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop: skipping ping - connected: ${serviceInstance.connectionState == ConnectionState.CONNECTED}, network: ${serviceInstance.isCurrentlyConnected}, pingInFlight: ${serviceInstance.pingInFlight}")
                    }
                }
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop coroutine ended - isActive=$isActive")
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop started")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop stopped")
            logPingStatus()
        }
        
        /**
         * Set app visibility for adaptive ping intervals
         */
        fun setAppVisibility(visible: Boolean) {
            instance?.isAppVisible = visible
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "App visibility changed to: $visible")
            
            // Log current ping interval when visibility changes
            val interval = instance?.let { serviceInstance ->
                if (serviceInstance.isAppVisible) 15_000L else 60_000L
            } ?: 60_000L
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping interval: ${interval}ms (app visible: $visible)")
            
            // Force update the ping loop if it's running
            instance?.let { serviceInstance ->
                if (serviceInstance.pingJob?.isActive == true) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "App visibility changed while ping loop running - will use new interval on next ping")
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Registered WebSocket callback for: $callbackId (total: ${webSocketSendCallbacks.size})")
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
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Unregistered WebSocket callback for: $callbackId (remaining: ${webSocketSendCallbacks.size})")
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Registered receive callback for: $viewModelId (total: ${webSocketReceiveCallbacks.size})")
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
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Unregistered receive callback for: $viewModelId (remaining: ${webSocketReceiveCallbacks.size})")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setWebSocketSendCallback() called (deprecated - use registerWebSocketSendCallback)")
            registerWebSocketSendCallback("legacy", callback)
        }
        
        /**
         * PHASE 1.2: Set callback for triggering reconnection
         * Only one primary instance can register this callback
         * 
         * @param viewModelId Unique identifier for the AppViewModel instance
         * @param callback Callback function to invoke when reconnection is needed
         * @return true if registration succeeded, false if another instance is already primary
         */
        fun setReconnectionCallback(viewModelId: String, callback: (String) -> Unit): Boolean {
            synchronized(callbacksLock) {
                // Check if another instance is already registered as primary
                if (primaryViewModelId != null && primaryViewModelId != viewModelId) {
                    android.util.Log.w("WebSocketService", "setReconnectionCallback: Another instance ($primaryViewModelId) is already registered as primary. Rejecting registration from $viewModelId")
                    return false
                }
                
                // Same instance re-registering - allow it (might be reconnecting after being cleared)
                if (primaryViewModelId == viewModelId) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setReconnectionCallback: Primary instance $viewModelId re-registering callback")
                } else {
                    // New primary instance
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setReconnectionCallback: Registering $viewModelId as primary instance")
                    primaryViewModelId = viewModelId
                }
                
                primaryReconnectionCallback = callback
                // Also set legacy callback for backward compatibility during migration
                reconnectionCallback = callback
                
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setReconnectionCallback: Successfully registered primary reconnection callback for $viewModelId")
                
                // PHASE 2.1: Process any pending reconnection requests that were queued before callback was available
                processPendingReconnections()
                
                return true
            }
        }
        
        /**
         * PHASE 2.1: Process pending reconnection requests that were queued when callback was unavailable
         */
        private fun processPendingReconnections() {
            val serviceInstance = instance ?: return
            
            // Check if callback is now available
            val activeCallback = getActiveReconnectionCallback()
            if (activeCallback == null) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "processPendingReconnections: Callback still not available, skipping")
                return
            }
            
            // Process all queued requests
            synchronized(serviceInstance.pendingReconnectionLock) {
                if (serviceInstance.pendingReconnectionReasons.isEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "processPendingReconnections: No pending reconnection requests")
                    return
                }
                
                val queuedReasons = serviceInstance.pendingReconnectionReasons.toList()
                serviceInstance.pendingReconnectionReasons.clear()
                
                android.util.Log.i("WebSocketService", "processPendingReconnections: Processing ${queuedReasons.size} queued reconnection request(s)")
                
                // Process each queued request
                queuedReasons.forEach { reason ->
                    android.util.Log.i("WebSocketService", "processPendingReconnections: Processing queued reconnection: $reason")
                    // Use scheduleReconnection to ensure proper handling (rate limiting, state checks, etc.)
                    scheduleReconnection(reason)
                }
            }
        }
        
        /**
         * Legacy method - kept for backward compatibility
         * @deprecated Use setReconnectionCallback(viewModelId, callback) instead
         */
        @Deprecated("Use setReconnectionCallback(viewModelId, callback) instead", ReplaceWith("setReconnectionCallback(\"legacy\", callback)"))
        fun setReconnectionCallback(callback: (String) -> Unit) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setReconnectionCallback() called (legacy - no viewModelId)")
            // Legacy: allow registration without viewModelId, but don't set as primary
            reconnectionCallback = callback
            // PHASE 2.1: Process any pending reconnection requests
            processPendingReconnections()
        }
        
        /**
         * PHASE 1.2: Set callback for offline mode management
         * Only the primary instance can register this callback
         * 
         * @param viewModelId Unique identifier for the AppViewModel instance
         * @param callback Callback function to invoke when offline mode changes
         * @return true if registration succeeded, false if another instance is already primary
         */
        fun setOfflineModeCallback(viewModelId: String, callback: (Boolean) -> Unit): Boolean {
            synchronized(callbacksLock) {
                // Check if this is the primary instance
                if (primaryViewModelId != null && primaryViewModelId != viewModelId) {
                    android.util.Log.w("WebSocketService", "setOfflineModeCallback: Instance $viewModelId is not the primary instance ($primaryViewModelId). Rejecting registration.")
                    return false
                }
                
                // Must have registered reconnection callback first (enforces primary instance)
                if (primaryViewModelId != viewModelId) {
                    android.util.Log.w("WebSocketService", "setOfflineModeCallback: Instance $viewModelId must register reconnection callback first. Rejecting registration.")
                    return false
                }
                
                primaryOfflineModeCallback = callback
                // Also set legacy callback for backward compatibility
                offlineModeCallback = callback
                
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setOfflineModeCallback: Successfully registered primary offline mode callback for $viewModelId")
                return true
            }
        }
        
        /**
         * Legacy method - kept for backward compatibility
         * @deprecated Use setOfflineModeCallback(viewModelId, callback) instead
         */
        @Deprecated("Use setOfflineModeCallback(viewModelId, callback) instead", ReplaceWith("setOfflineModeCallback(\"legacy\", callback)"))
        fun setOfflineModeCallback(callback: (Boolean) -> Unit) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setOfflineModeCallback() called (legacy - no viewModelId)")
            // Legacy: allow registration without viewModelId, but don't set as primary
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network marked as healthy")
        }
    
        /**
         * Trigger backend health check and reconnection if needed
         * This can be called from FCM or other external triggers
         */
        fun triggerBackendHealthCheck() {
            val serviceInstance = instance ?: return
            serviceScope.launch {
                try {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Triggering manual backend health check")
                    val isHealthy = serviceInstance.checkBackendHealth()
                    
                    if (!isHealthy && (serviceInstance.connectionState == ConnectionState.CONNECTED || serviceInstance.connectionState == ConnectionState.DEGRADED)) {
                        android.util.Log.w("WebSocketService", "Manual backend health check failed - triggering reconnection")
                        triggerReconnectionFromExternal("Manual backend health check failed")
                    } else if (isHealthy) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Manual backend health check passed")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "triggerReconnectionFromExternal called: $reason")
            // PHASE 1.4: Use safe invocation helper with error handling
            invokeReconnectionCallback(reason)
        }
        
        /**
         * Safely trigger reconnection with validation
         * RUSH TO HEALTHY: Simplified - no exponential backoff, just validate and reconnect
         */
        fun triggerReconnectionSafely(reason: String) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "triggerReconnectionSafely called: $reason")
            
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
            // PHASE 1.2: Check active callback (primary or legacy)
            if (getActiveReconnectionCallback() == null) {
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
            
            // Accept pong if it matches the last ping request ID (most recent ping)
            // This prevents processing stale pongs from previous connections
            if (requestId == serviceInstance.lastPingRequestId) {
                serviceInstance.pongTimeoutJob?.cancel()
                serviceInstance.pongTimeoutJob = null
                serviceInstance.pingInFlight = false // Clear ping-in-flight flag
                
                val lagMs = System.currentTimeMillis() - serviceInstance.lastPingTimestamp
                serviceInstance.lastKnownLagMs = lagMs
                serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Pong received, lag: ${lagMs}ms")
                
                // RUSH TO HEALTHY: Reset consecutive failures on successful pong
                if (serviceInstance.consecutivePingTimeouts > 0) {
                    android.util.Log.i("WebSocketService", "Pong received - resetting consecutive failures (was: ${serviceInstance.consecutivePingTimeouts})")
                    logActivity("Pong Received - Recovered (was ${serviceInstance.consecutivePingTimeouts} failures)", serviceInstance.currentNetworkType.name)
                }
                serviceInstance.consecutivePingTimeouts = 0
                
                // Update notification with lag and last sync timestamp
                updateConnectionStatus(true, lagMs, serviceInstance.lastSyncTimestamp)
            } else {
                // Pong for a different request ID - might be a stale pong, but still indicates connection is alive
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Pong received for non-matching requestId: $requestId (expected: ${serviceInstance.lastPingRequestId}) - connection is alive but pong is stale")
                // Don't reset pingInFlight or update lag, but connection is clearly working
                // Reset consecutive failures since we got a pong (connection is alive)
                if (serviceInstance.consecutivePingTimeouts > 0) {
                    android.util.Log.i("WebSocketService", "Stale pong received - connection is alive, resetting consecutive failures (was: ${serviceInstance.consecutivePingTimeouts})")
                    serviceInstance.consecutivePingTimeouts = 0
                }
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting, ignoring trigger: $reason")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Current connection state: ${serviceInstance.connectionState}")
            
            // Validate state before setting WebSocket
            detectAndRecoverStateCorruption()
            
            // Check if already connecting or connected (but allow reconnection)
            if (serviceInstance.connectionState == ConnectionState.CONNECTING) {
                android.util.Log.w("WebSocketService", "WebSocket connection already in progress, ignoring new connection")
                return
            }
            
            // If already connected, close the old connection first (for reconnection)
            if (serviceInstance.connectionState == ConnectionState.CONNECTED) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Replacing existing WebSocket connection (reconnection)")
                serviceInstance.webSocket?.close(1000, "Reconnecting")
            }
            
            serviceInstance.connectionState = ConnectionState.CONNECTING
            serviceInstance.webSocket = webSocket
            // DO NOT mark as CONNECTED yet - wait for init_complete
            // Track connection start time for duration display
            serviceInstance.connectionStartTime = System.currentTimeMillis()
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connection state set to CONNECTING (waiting for init_complete)")
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "WebSocket reference set: ${webSocket != null}")
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connection start time recorded: ${serviceInstance.connectionStartTime}")
            serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
            
            // Ensure network connectivity is marked as available when WebSocket connects
            serviceInstance.isCurrentlyConnected = true
            
            // Get the actual network type (not hardcoded to WiFi)
            // Get network type from the service instance's connectivity manager
            val actualNetworkType = serviceInstance.getNetworkTypeFromCapabilities()
            serviceInstance.currentNetworkType = actualNetworkType
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network type set to: $actualNetworkType")
            
            // Start timeout for init_complete - connection is only healthy after init_complete arrives
            serviceInstance.waitingForInitComplete = true
            serviceInstance.startInitCompleteTimeout()
            
            // Log activity: WebSocket connecting (not connected yet)
            logActivity("WebSocket Connecting - Waiting for init_complete", actualNetworkType.name)
            
            // Update notification with connection status (still connecting)
            updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
            
            android.util.Log.i("WebSocketService", "WebSocket connection opened - waiting for init_complete")
            logPingStatus()
        }
        
        /**
         * Clear WebSocket connection
         */
        fun clearWebSocket(reason: String = "Unknown") {
            traceToggle("clearWebSocket")
            val serviceInstance = instance ?: return
            serviceInstance.pingInFlight = false // Reset ping-in-flight flag
            
            // Only log disconnection if we actually had a connection
            val wasConnected = serviceInstance.connectionState == ConnectionState.CONNECTED || serviceInstance.webSocket != null
            
            if (!wasConnected) {
                // Already disconnected, don't log redundant disconnection
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearWebSocket() called but already disconnected - skipping")
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
            
            // Cancel init_complete timeout if active
            serviceInstance.initCompleteTimeoutJob?.cancel()
            serviceInstance.initCompleteTimeoutJob = null
            serviceInstance.waitingForInitComplete = false
            
            // Reset connection health tracking
            serviceInstance.lastKnownLagMs = null
            serviceInstance.lastPongTimestamp = 0L
            
            // Reset ping loop state for next connection (ready-state flag stays true so failsafe can run)
            serviceInstance.pingLoopStarted = false
            
            // Log activity: WebSocket disconnected
            logActivity("WebSocket Disconnected - $reason", serviceInstance.currentNetworkType.name)
            
            // Update notification to show disconnection
            updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "WebSocket connection cleared in service")
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
            // PHASE 1.2: Check active callback (primary or legacy)
            return getActiveReconnectionCallback() != null &&
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
            // PHASE 1.2: Check active callback (primary or legacy)
            if (getActiveReconnectionCallback() == null) {
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
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Running state corruption detection...")
            
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "No state corruption detected")
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
            serviceInstance.initCompleteTimeoutJob?.cancel()
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Manual state corruption check requested")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Updated last_received_sync_id: $lastReceivedId (run_id read from SharedPreferences)")
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
            
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "updateLastSyncTimestamp() called - connectionState: ${serviceInstance.connectionState}")
            
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Initial sync persisted - last_received_event will be included on reconnections")
            }
        }
        
        /**
         * Clear reconnection state (only last_received_sync_id - run_id stays in SharedPreferences)
         */
        fun clearReconnectionState() {
            val serviceInstance = instance ?: return
            serviceInstance.lastReceivedSyncId = 0
            serviceInstance.hasPersistedSync = false
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Cleared reconnection state (last_received_sync_id reset, run_id preserved in SharedPreferences)")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "getReconnectionParameters: runId='$runId' (from SharedPreferences), lastReceivedSyncId=$lastReceivedId, isReconnecting=$isReconnecting")
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
            // DO NOT reset connectionState here - it's set when init_complete arrives
            // DO NOT clear lastReceivedSyncId - it's needed for future reconnections
            serviceInstance.initCompleteRetryCount = 0 // Reset retry count on successful connection
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Reset reconnection state (reconnection job cancelled, retry count reset)")
        }
        
        /**
         * Handle init_complete received - mark connection as healthy
         */
        fun onInitCompleteReceived() {
            val serviceInstance = instance ?: return
            
            // Cancel timeout if still waiting
            serviceInstance.initCompleteTimeoutJob?.cancel()
            serviceInstance.initCompleteTimeoutJob = null
            
            if (!serviceInstance.waitingForInitComplete) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete received but not waiting - ignoring")
                return
            }
            
            serviceInstance.waitingForInitComplete = false
            
            // Reset retry count on successful init_complete
            serviceInstance.initCompleteRetryCount = 0
            
            // Now mark as CONNECTED - connection is healthy
            serviceInstance.connectionState = ConnectionState.CONNECTED
            android.util.Log.i("WebSocketService", "Init complete received - connection marked as CONNECTED")
            logActivity("Init Complete Received - Connection Healthy", serviceInstance.currentNetworkType.name)
            
            // Update notification
            updateConnectionStatus(true, null, serviceInstance.lastSyncTimestamp)
            
            // Clear any failure notifications
            serviceInstance.clearInitCompleteFailureNotification()
        }
        
        /**
         * Schedule WebSocket reconnection with backend health check
         * RUSH TO HEALTHY: Fast retry with backend health check, no exponential backoff
         */
        fun scheduleReconnection(reason: String) {
            val serviceInstance = instance ?: return
            
            // PHASE 2.1: If reconnection callback is not available, queue the request
            val activeCallback = getActiveReconnectionCallback()
            if (activeCallback == null) {
                synchronized(serviceInstance.pendingReconnectionLock) {
                    serviceInstance.pendingReconnectionReasons.add(reason)
                    android.util.Log.w("WebSocketService", "Reconnection callback not available - queued reconnection request: $reason (queue size: ${serviceInstance.pendingReconnectionReasons.size})")
                    logActivity("Reconnection Queued - $reason", serviceInstance.currentNetworkType.name)
                }
                return
            }
            
            // ATOMIC GUARD: Use synchronized lock to prevent parallel reconnection attempts
            synchronized(serviceInstance.reconnectionLock) {
                val currentTime = System.currentTimeMillis()
                
                // Check if already reconnecting - if so, drop redundant request
                if (serviceInstance.connectionState == ConnectionState.RECONNECTING || serviceInstance.isReconnecting) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting, dropping redundant request: $reason")
                    return
                }
                
                // Set reconnecting flag atomically
                serviceInstance.isReconnecting = true
                
                // Check minimum interval between reconnections (prevent rapid-fire reconnections)
                if (currentTime - serviceInstance.lastReconnectionTime < MIN_RECONNECTION_INTERVAL_MS) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Too soon since last reconnection, ignoring: $reason")
                    serviceInstance.isReconnecting = false
                    return
                }
                
                // Cancel any existing reconnection job
                serviceInstance.reconnectionJob?.cancel()
                
                serviceInstance.connectionState = ConnectionState.RECONNECTING
                serviceInstance.lastReconnectionTime = currentTime
                
                android.util.Log.w("WebSocketService", "Scheduling reconnection: $reason")
                logActivity("Connecting - $reason", serviceInstance.currentNetworkType.name)
                
                serviceInstance.reconnectionJob = serviceScope.launch {
                    // RUSH TO HEALTHY: Check backend health first, then reconnect
                    val backendHealthy = serviceInstance.checkBackendHealth()
                    
                    if (backendHealthy) {
                        // Backend healthy - reconnect immediately with short delay
                        delay(BASE_RECONNECTION_DELAY_MS)
                    } else {
                        // Backend unhealthy - wait longer before retry
                        android.util.Log.w("WebSocketService", "Backend unhealthy - waiting ${BACKEND_HEALTH_RETRY_DELAY_MS}ms before retry")
                        logActivity("Backend Unhealthy - Delaying Reconnection", serviceInstance.currentNetworkType.name)
                        delay(BACKEND_HEALTH_RETRY_DELAY_MS)
                    }
                    
                    // Reset reconnecting flag when job completes
                    synchronized(serviceInstance.reconnectionLock) {
                        serviceInstance.isReconnecting = false
                    }
                    
                    if (isActive) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Executing reconnection: $reason")
                        logActivity("Reconnection Attempt - $reason", serviceInstance.currentNetworkType.name)
                        // PHASE 1.4: Use safe invocation helper with error handling
                        invokeReconnectionCallback(reason)
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Restarting WebSocket connection - Reason: $reason")
            
            val serviceInstance = instance ?: return
            
            // Properly close existing WebSocket first
            val currentWebSocket = serviceInstance.webSocket
            if (currentWebSocket != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Closing existing WebSocket before restart")
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network restored - skipping clearWebSocket to preserve state")
            }
            
            // Reset reconnection state to allow new reconnection
            serviceInstance.isReconnecting = false
            
            // Add a small delay to ensure WebSocket is properly closed
            serviceScope.launch {
                delay(1000) // 1 second delay to ensure proper closure
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Triggering reconnection after delay")
                
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
                
                // PHASE 1.4 FIX: Prevent infinite loop - if reason is "Service restarted - reconnecting",
                // don't call the callback again as it was already called from onStartCommand
                // The callback should handle the actual connection, not trigger another restart
                if (reason.contains("Service restarted")) {
                    android.util.Log.w("WebSocketService", "restartWebSocket called with 'Service restarted' reason - this would create a loop. AppViewModel should handle connection directly.")
                    // Don't call callback again - AppViewModel's restartWebSocket should handle the connection
                    return@launch
                }
                
                // Internal trigger - use callback
                // PHASE 1.4: Use safe invocation helper with error handling
                invokeReconnectionCallback(reason)
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Cancelled pending reconnection")
        }
        
    }
    
    // Instance variables for WebSocket state management
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var initCompleteTimeoutJob: Job? = null // Timeout waiting for init_complete
    private var lastPingRequestId: Int = 0
    private var lastPingTimestamp: Long = 0
    private var pingInFlight: Boolean = false // Guard to prevent concurrent pings
    private var isAppVisible = false
    private var initCompleteRetryCount: Int = 0 // Track retry count for exponential backoff
    private var waitingForInitComplete: Boolean = false // Track if we're waiting for init_complete
    
    // Notification state cache for idempotent updates
    private var lastNotificationText: String? = null
    private var lastNotificationUpdateTime: Long = 0
    private var lastConnectionStateForNotification: String? = null // Track state changes for release builds (format: "STATE-callbackMissing")
    
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
    
    // PHASE 2.1: Pending reconnection queue for when callback is not yet available
    private val pendingReconnectionReasons = mutableListOf<String>()
    private val pendingReconnectionLock = Any() // Thread safety for pending queue
    
    // Ping loop state
    private var pingLoopStarted = false // Track if ping loop has been started after first sync_complete
    private var hasEverReachedReadyState = false // Track if we have ever received sync_complete on this run
    
    // PHASE 2.2: Service restart detection
    private var serviceStartTime: Long = 0 // Track when service started (0 = not started yet)
    private var wasRestarted: Boolean = false // Track if service was restarted (instance was null before onCreate)
    
    // PHASE 2.3: Callback health monitoring
    private var lastCallbackCheckTime: Long = 0 // Track when callbacks were last verified
    
    // PHASE 3.1: Network monitoring
    private var networkMonitor: NetworkMonitor? = null
    
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
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Backend health check: ${response.code} - ${if (isHealthy) "HEALTHY" else "UNHEALTHY"}")
            
            // Log backend health check result
            logActivity("Backend Health Check: ${if (isHealthy) "HEALTHY" else "UNHEALTHY"} (HTTP ${response.code})", currentNetworkType.name)
            
            response.close()
            isHealthy
        } catch (e: Exception) {
            android.util.Log.w("WebSocketService", "Backend health check failed", e)
            logActivity("Backend Health Check: FAILED - ${e.message}", currentNetworkType.name)
            false
        }
    }
    
    /**
     * Start periodic state corruption monitoring
     */
    private fun startStateCorruptionMonitoring() {
        stateCorruptionJob?.cancel()
        stateCorruptionJob = serviceScope.launch {
            var stateCorruptionCheckCounter = 0
            while (isActive) {
                delay(30_000) // Check every 30 seconds (for callback health)
                
                try {
                    // PHASE 2.3: Check callback health every 30 seconds
                    validateCallbacks()
                    
                    // State corruption check every 60 seconds (every other iteration)
                    stateCorruptionCheckCounter++
                    if (stateCorruptionCheckCounter >= 2) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Running periodic state corruption check")
                        WebSocketService.detectAndRecoverStateCorruption()
                        stateCorruptionCheckCounter = 0
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in state corruption/callback monitoring", e)
                }
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "State corruption and callback health monitoring started")
    }
    
    /**
     * Stop state corruption monitoring
     */
    private fun stopStateCorruptionMonitoring() {
        stateCorruptionJob?.cancel()
        stateCorruptionJob = null
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "State corruption and callback health monitoring stopped")
    }
    
    /**
     * PHASE 2.3: Validate that all primary callbacks are set
     * Checks if callbacks are available and logs warnings if missing
     * Updates notification if callback is missing and connection is not CONNECTED
     */
    private fun validateCallbacks() {
        lastCallbackCheckTime = System.currentTimeMillis()
        
        // PHASE 1.2: Check active callback (primary or legacy)
        val activeReconnectionCallback = getActiveReconnectionCallback()
        val isConnected = connectionState == ConnectionState.CONNECTED
        
        // Verify primaryReconnectionCallback if connection is not CONNECTED
        if (!isConnected && activeReconnectionCallback == null) {
            android.util.Log.w("WebSocketService", "Callback health check: reconnection callback is null and connection is not CONNECTED")
            logActivity("Callback Missing - Waiting for AppViewModel", currentNetworkType.name)
            
            // Update notification to show "Waiting for app..."
            updateConnectionStatus(
                isConnected = false,
                lagMs = lastKnownLagMs,
                lastSyncTimestamp = lastSyncTimestamp
            )
        } else if (BuildConfig.DEBUG && !isConnected) {
            android.util.Log.d("WebSocketService", "Callback health check: reconnection callback is available (connection not CONNECTED)")
        } else if (BuildConfig.DEBUG && isConnected) {
            android.util.Log.d("WebSocketService", "Callback health check: connection is CONNECTED (callback check skipped)")
        }
        
        // Check all primary callbacks for completeness (for diagnostics)
        val hasPrimaryReconnection = primaryReconnectionCallback != null
        val hasPrimaryOfflineMode = primaryOfflineModeCallback != null
        val hasPrimaryActivityLog = primaryActivityLogCallback != null
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("WebSocketService", "Callback health check: reconnection=$hasPrimaryReconnection, offlineMode=$hasPrimaryOfflineMode, activityLog=$hasPrimaryActivityLog")
        }
        
        // Log warning if primary instance is registered but callbacks are missing
        if (primaryViewModelId != null) {
            if (!hasPrimaryReconnection) {
                android.util.Log.w("WebSocketService", "Primary instance $primaryViewModelId registered but reconnection callback is missing")
            }
        }
    }
    
    /**
     * PHASE 3.1: Start network monitoring
     * Detects network changes immediately (WiFi↔WiFi, Mobile↔Mobile, Offline↔Online, WiFi↔Mobile)
     */
    private fun startNetworkMonitoring() {
        if (networkMonitor != null) {
            if (BuildConfig.DEBUG) android.util.Log.w("WebSocketService", "NetworkMonitor already started")
            return
        }
        
        networkMonitor = NetworkMonitor(
            context = this,
            onNetworkAvailable = { networkType ->
                android.util.Log.i("WebSocketService", "Network available: $networkType - checking if reconnection needed")
                currentNetworkType = convertNetworkType(networkType)
                
                // Network became available - trigger reconnection if disconnected
                if (connectionState != ConnectionState.CONNECTED) {
                    android.util.Log.w("WebSocketService", "Network available but WebSocket not connected - triggering reconnection")
                    logActivity("Network Available - Reconnecting", currentNetworkType.name)
                    
                    // PHASE 2.1: Use scheduleReconnection which will queue if callback not available
                    scheduleReconnection("Network available: $networkType")
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network available and WebSocket already connected - no action needed")
                }
            },
            onNetworkLost = {
                android.util.Log.w("WebSocketService", "Network lost - marking connection as degraded")
                logActivity("Network Lost", currentNetworkType.name)
                
                // Network lost - mark connection as degraded, don't reconnect yet
                // Wait for network to become available again
                isCurrentlyConnected = false
                currentNetworkType = NetworkType.NONE
                
                // Update notification to show network loss
                updateConnectionStatus(false, lastKnownLagMs, lastSyncTimestamp)
            },
            onNetworkTypeChanged = { previousType, newType ->
                android.util.Log.i("WebSocketService", "Network type changed: $previousType → $newType - reconnecting WebSocket")
                logActivity("Network Type Changed: $previousType → $newType", newType.name)
                
                currentNetworkType = convertNetworkType(newType)
                
                // Network type changed (e.g., WiFi → Mobile, or different WiFi network)
                // Close existing connection and reconnect on new network
                if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.RECONNECTING) {
                    android.util.Log.w("WebSocketService", "Network type changed while connected - reconnecting on new network")
                    clearWebSocket("Network type changed: $previousType → $newType")
                    scheduleReconnection("Network type changed: $previousType → $newType")
                } else {
                    // Not connected - just trigger reconnection
                    scheduleReconnection("Network type changed: $previousType → $newType")
                }
            }
        )
        
        networkMonitor?.start()
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network monitoring started")
    }
    
    /**
     * PHASE 3.1: Stop network monitoring
     */
    private fun stopNetworkMonitoring() {
        networkMonitor?.stop()
        networkMonitor = null
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network monitoring stopped")
    }
    
    /**
     * PHASE 3.1: Convert NetworkMonitor.NetworkType to WebSocketService.NetworkType
     */
    private fun convertNetworkType(networkType: NetworkMonitor.NetworkType): NetworkType {
        return when (networkType) {
            NetworkMonitor.NetworkType.NONE -> NetworkType.NONE
            NetworkMonitor.NetworkType.WIFI -> NetworkType.WIFI
            NetworkMonitor.NetworkType.CELLULAR -> NetworkType.CELLULAR
            NetworkMonitor.NetworkType.ETHERNET -> NetworkType.ETHERNET
            NetworkMonitor.NetworkType.VPN -> NetworkType.VPN
            NetworkMonitor.NetworkType.OTHER -> NetworkType.OTHER
        }
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
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Stopping WebSocket service")
        
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
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "WebSocket service stopped successfully")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Skipping ping - network unavailable")
            return
        }
        
        // Don't send pings if we don't have a valid lastReceivedSyncId yet
        if (lastReceivedSyncId == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Skipping ping - no sync_complete received yet (lastReceivedSyncId: $lastReceivedSyncId)")
            return
        }
        
        // Guard: Don't send if ping is already in flight
        if (pingInFlight) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Skipping ping - ping already in flight")
            return
        }
        
        val reqId = lastPingRequestId + 1
        lastPingRequestId = reqId
        lastPingTimestamp = System.currentTimeMillis()
        pingInFlight = true // Mark ping as in flight
        
        val data = mapOf("last_received_id" to lastReceivedSyncId)
        
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Sending ping (requestId: $reqId, lastReceivedSyncId: $lastReceivedSyncId)")
        
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping sent successfully")
                // Start timeout for this ping
                startPongTimeout(reqId)
            } else {
                android.util.Log.w("WebSocketService", "Failed to send ping - WebSocket send returned false")
                pingInFlight = false // Reset flag on failure
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to send ping", e)
            pingInFlight = false // Reset flag on exception
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
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Pong timeout but connection already inactive - ignoring")
                return@launch
            }
            
            android.util.Log.w("WebSocketService", "Pong timeout for ping $pingRequestId (${PONG_TIMEOUT_MS}ms)")
            
            // Clear ping-in-flight flag since we timed out
            pingInFlight = false
            
            // Increment consecutive failure counter
            consecutivePingTimeouts++
            android.util.Log.w("WebSocketService", "Consecutive ping failures: $consecutivePingTimeouts")
            
            // Log ping timeout to activity log
            logActivity("Ping Timeout (${consecutivePingTimeouts}/${CONSECUTIVE_FAILURES_TO_DROP})", currentNetworkType.name)
            
            // RUSH TO HEALTHY: If 3 consecutive failures, drop WebSocket and reconnect
            if (consecutivePingTimeouts >= CONSECUTIVE_FAILURES_TO_DROP) {
                android.util.Log.e("WebSocketService", "3 consecutive ping failures - dropping WebSocket and reconnecting")
                logActivity("Dropping Connection - 3 consecutive ping failures", currentNetworkType.name)
                consecutivePingTimeouts = 0
                
                // Check backend health
                val backendHealthy = checkBackendHealth()
                
                if (backendHealthy) {
                    // Backend is healthy - reconnect immediately
                    android.util.Log.i("WebSocketService", "Backend healthy - triggering immediate reconnection")
                    logActivity("Backend Healthy - Reconnecting", currentNetworkType.name)
                    clearWebSocket("3 consecutive ping failures - backend healthy")
                    // PHASE 1.4: Use safe invocation helper with error handling
                    invokeReconnectionCallback("3 consecutive ping failures - backend healthy")
                } else {
                    // Backend unhealthy - wait for recovery
                    android.util.Log.w("WebSocketService", "Backend unhealthy - waiting for recovery")
                    logActivity("Backend Unhealthy - Waiting for Recovery", currentNetworkType.name)
                    clearWebSocket("3 consecutive ping failures - backend unhealthy")
                    
                    // Poll backend health and reconnect when healthy
                    while (isActive) {
                        delay(BACKEND_HEALTH_RETRY_DELAY_MS)
                        if (!isActive) return@launch
                        
                        val recovered = checkBackendHealth()
                        if (recovered) {
                            android.util.Log.i("WebSocketService", "Backend healthy again - triggering reconnection")
                            logActivity("Backend Recovered - Reconnecting", currentNetworkType.name)
                            // PHASE 1.4: Use safe invocation helper with error handling
                            invokeReconnectionCallback("Backend recovered after ping failures")
                            return@launch
                        }
                    }
                }
            } else {
                // RUSH TO HEALTHY: Send next ping immediately (don't wait for normal interval)
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping failure #$consecutivePingTimeouts - sending next ping immediately")
                // The ping loop will send immediately when it sees we're still connected
            }
        }
    }
    
    // RUSH TO HEALTHY: Removed network metrics - ping/pong failures are the only metric we need
    
    /**
     * Start timeout for init_complete after WebSocket opens
     * If init_complete doesn't arrive within timeout, drop connection and retry with exponential backoff
     */
    private fun startInitCompleteTimeout() {
        initCompleteTimeoutJob?.cancel()
        initCompleteTimeoutJob = serviceScope.launch {
            delay(INIT_COMPLETE_TIMEOUT_MS)
            
            // Check if we're still waiting for init_complete
            if (!waitingForInitComplete) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete timeout expired but already received - ignoring")
                return@launch
            }
            
            // Check if connection is still active
            if (connectionState != ConnectionState.CONNECTING && connectionState != ConnectionState.CONNECTED) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete timeout expired but connection not active - ignoring")
                return@launch
            }
            
            android.util.Log.w("WebSocketService", "Init complete timeout expired after ${INIT_COMPLETE_TIMEOUT_MS}ms - connection failed")
            logActivity("Init Complete Timeout - Connection Failed", currentNetworkType.name)
            
            // Show notification about connection failure
            showInitCompleteFailureNotification()
            
            // Drop the connection
            clearWebSocket("Init complete timeout - connection failed")
            
            // Calculate exponential backoff delay (2s, 4s, 8s, 16s, 32s, 64s max)
            val delayMs = minOf(
                INIT_COMPLETE_RETRY_BASE_MS * (1 shl initCompleteRetryCount),
                INIT_COMPLETE_RETRY_MAX_MS
            )
            initCompleteRetryCount++
            
            android.util.Log.w("WebSocketService", "Retrying connection after ${delayMs}ms (retry count: $initCompleteRetryCount)")
            logActivity("Retrying Connection - Wait ${delayMs}ms (Retry $initCompleteRetryCount)", currentNetworkType.name)
            
            // Wait for backoff delay
            delay(delayMs)
            
            // Retry connection
            if (isActive) {
                // PHASE 1.4: Use safe invocation helper with error handling
                invokeReconnectionCallback("Init complete timeout - retrying")
            }
        }
    }
    
    
    /**
     * Show notification when init_complete fails
     */
    private fun showInitCompleteFailureNotification() {
        val serviceInstance = instance ?: return
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WebSocket Connection Issue")
                .setContentText("Retrying connection...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to show init_complete failure notification", e)
        }
    }
    
    /**
     * Clear init_complete failure notification
     */
    private fun clearInitCompleteFailureNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID + 1)
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to clear init_complete failure notification", e)
        }
    }
        

    
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
        
        // PHASE 2.2: Track service start time
        // Note: We can't reliably detect restart in onCreate() because Android kills the process,
        // so static variables are reset. We'll detect restart in onStartCommand() by checking
        // if we have connection state that suggests a previous connection existed.
        serviceStartTime = System.currentTimeMillis()
        wasRestarted = false // Will be set in onStartCommand() if needed
        
        instance = this
        createNotificationChannel()
        
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service created - serviceStartTime: $serviceStartTime")
        
        // run_id is always read from SharedPreferences when needed - no need to restore on startup
        // Reset last_received_sync_id on service startup (will be set when sync_complete arrives)
        lastReceivedSyncId = 0
        hasPersistedSync = false
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service startup - last_received_sync_id reset (run_id will be read from SharedPreferences when needed)")
        
        // Start state corruption monitoring immediately when service is created
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Starting service state monitoring from onCreate")
        startStateCorruptionMonitoring()
        
        // PHASE 3.1: Start network monitoring for immediate reconnection on network changes
        startNetworkMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service started with intent: ${intent?.action}")
        
        // Handle stop request
        if (intent?.action == "STOP_SERVICE") {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Stop service requested via intent")
            stopService()
            return START_NOT_STICKY
        }
        
        // Start as foreground service with notification
        // This keeps the app process alive and prevents Android from killing it
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires explicit service type
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Foreground service started successfully (Android 14+)")
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Foreground service started successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to start foreground service", e)
            // Don't crash - service will run in background
        }
        
        // Update notification with current connection state after service starts
        // This ensures the notification shows the correct state even if no WebSocket is connected yet
        // Also check if reconnection callback is available
        // PHASE 1.2: Check active callback (primary or legacy)
        val hasCallback = getActiveReconnectionCallback() != null
        updateConnectionStatus(
            isConnected = connectionState == ConnectionState.CONNECTED,
            lagMs = lastKnownLagMs,
            lastSyncTimestamp = lastSyncTimestamp
        )
        
        // If callback is missing, log it for debugging
        if (!hasCallback) {
            android.util.Log.w("WebSocketService", "Service started but reconnection callback not set - notification will show 'Waiting for app...'")
        }
        
        // PHASE 2.2: Handle service restart detection
        // Detect restart by checking if we have connection state that suggests a previous connection existed
        // (connectionStartTime > 0 indicates we had a connection before service was killed)
        val hadPreviousConnection = connectionStartTime > 0
        if (hadPreviousConnection) {
            wasRestarted = true
            android.util.Log.w("WebSocketService", "Service was restarted (had previous connection) - handling restart scenario")
            
            // PHASE 1.2: Check active callback (primary or legacy)
            val activeCallback = getActiveReconnectionCallback()
            
            if (activeCallback == null) {
                // Service restarted but callback not available - don't assume connection is alive
                android.util.Log.w("WebSocketService", "Service restarted but reconnection callback not available - setting state to DISCONNECTED and waiting for AppViewModel")
                connectionState = ConnectionState.DISCONNECTED
                webSocket = null // Don't assume WebSocket is still connected
                isCurrentlyConnected = false
                connectionStartTime = 0 // Reset since we're not connected
                logActivity("Service Restarted - Waiting for AppViewModel", currentNetworkType.name)
                // Don't attempt reconnection yet - wait for callback registration
            } else {
                // Service restarted and callback is available - check if WebSocket is actually connected
                val isActuallyConnected = webSocket != null && connectionState == ConnectionState.CONNECTED
                
                if (!isActuallyConnected) {
                    android.util.Log.w("WebSocketService", "Service restarted and WebSocket is not connected - triggering reconnection")
                    logActivity("Service Restarted - Reconnecting", currentNetworkType.name)
                    
                    // Trigger reconnection via callback
                    try {
                        activeCallback("Service restarted - reconnecting")
                    } catch (e: Exception) {
                        android.util.Log.e("WebSocketService", "Reconnection callback failed on service restart", e)
                        logActivity("Reconnection Failed - ${e.message}", currentNetworkType.name)
                    }
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service restarted and WebSocket is still connected - no action needed")
                }
                
                // PHASE 2.1: Process any pending reconnection requests that were queued before callback was available
                processPendingReconnections()
            }
        } else {
            // Fresh start - no previous connection
            wasRestarted = false
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service started fresh (no previous connection)")
        }
        
        // RESILIENCE: Check connection state on service restart
        // If service was restarted by Android and WebSocket is disconnected, attempt reconnection
        // BUT: Only if AppViewModel is available (reconnectionCallback set)
        // We should NOT create ad-hoc WebSocket connections - only AppViewModel should create them
        serviceScope.launch {
            delay(2000) // Wait 2 seconds for service to fully initialize
            
            val isConnected = connectionState == ConnectionState.CONNECTED && webSocket != null
            
            // PHASE 1.2: Check active callback (primary or legacy)
            val activeCallback = getActiveReconnectionCallback()
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service restart check: connected=$isConnected, reconnectionCallback=${activeCallback != null}")
            
            // PHASE 1.4 FIX: Only trigger reconnection if we had a connection that was lost
            // On initial service start, we should wait for the app to establish the connection
            // Don't trigger reconnection on startup - this creates infinite loops
            if (!isConnected && !isReconnecting) {
                // Check if this is a fresh service start (no previous connection) or a lost connection
                // If connectionStartTime is 0, this is a fresh start - don't trigger reconnection
                val hadPreviousConnection = connectionStartTime > 0
                
                if (hadPreviousConnection) {
                    // We had a connection that was lost - trigger reconnection
                    val callback = activeCallback
                    if (callback != null) {
                        android.util.Log.w("WebSocketService", "Service restarted and connection was lost - triggering reconnection via AppViewModel")
                        logActivity("Service Restarted - Connection Lost - Reconnecting", currentNetworkType.name)
                        try {
                            callback("Service restarted - connection lost - reconnecting")
                        } catch (e: Exception) {
                            android.util.Log.e("WebSocketService", "Reconnection callback failed", e)
                            logActivity("Reconnection Failed - ${e.message}", currentNetworkType.name)
                        }
                    } else {
                        // AppViewModel not available - wait for it
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service restarted but AppViewModel not available - waiting for app to connect WebSocket properly")
                        logActivity("Service Restarted - Waiting for AppViewModel", currentNetworkType.name)
                    }
                } else {
                    // Fresh service start - don't trigger reconnection, let app handle it
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service started fresh - waiting for app to establish initial connection")
                    logActivity("Service Started - Waiting for Initial Connection", currentNetworkType.name)
                }
            } else if (isConnected) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service restarted and WebSocket already connected")
            }
        }
        
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service onDestroy() called - cleaning up resources")
        
        try {
            // Stop all monitoring and jobs
            stopStateCorruptionMonitoring()
            // PHASE 3.1: Stop network monitoring
            stopNetworkMonitoring()
            
            // Cancel all coroutine jobs
            pingJob?.cancel()
            pongTimeoutJob?.cancel()
            initCompleteTimeoutJob?.cancel()
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
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service cleanup completed")
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

        // Use current connection state for initial notification text
        val initialText = when (connectionState) {
            ConnectionState.DISCONNECTED -> "Connecting..."
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.CONNECTED -> if (BuildConfig.DEBUG) "Connected." else "Connected."
            ConnectionState.DEGRADED -> "Reconnecting..."
            ConnectionState.RECONNECTING -> "Reconnecting..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Andromuks")
            .setContentText(initialText)
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
        // Only update detailed notification in debug builds
        // In release builds, use updateConnectionStatus which only updates on state changes
        if (!BuildConfig.DEBUG) {
            return
        }
        
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
        
        if (BuildConfig.DEBUG) Log.d("WebSocketService", "Notification updated: $notificationText")
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
        
        val notificationText = if (BuildConfig.DEBUG) {
            // DEBUG BUILD: Show detailed stats with lag, sync time, etc.
            when {
                // Check if reconnection callback is missing (AppViewModel not available)
                // PHASE 1.2: Check active callback (primary or legacy)
                getActiveReconnectionCallback() == null && connectionState != ConnectionState.CONNECTED -> {
                    "Waiting for app... • ${getNetworkTypeDisplayName(currentNetworkType)}"
                }
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
        } else {
            // RELEASE BUILD: Only show simple status messages when connection state changes
            val currentState = connectionState
            
            // Check if reconnection callback is missing (AppViewModel not available)
            // PHASE 1.2: Check active callback (primary or legacy)
            val callbackMissing = getActiveReconnectionCallback() == null && currentState != ConnectionState.CONNECTED
            
            // Track both state and callback status for change detection
            val stateKey = "$currentState-$callbackMissing"
            val stateChanged = lastConnectionStateForNotification?.let { it != stateKey } ?: true
            
            // Only update if state changed
            if (!stateChanged && lastNotificationText != null) {
                if (BuildConfig.DEBUG) Log.d("WebSocketService", "Skipping notification update - state unchanged: $currentState, callbackMissing: $callbackMissing")
                return
            }
            
            lastConnectionStateForNotification = stateKey
            
            when {
                callbackMissing -> "Waiting for app..."
                currentState == ConnectionState.DISCONNECTED -> "Connecting..."
                currentState == ConnectionState.CONNECTING -> "Connecting..."
                currentState == ConnectionState.CONNECTED -> "Connected."
                currentState == ConnectionState.DEGRADED -> "Reconnecting..."
                currentState == ConnectionState.RECONNECTING -> "Reconnecting..."
                else -> "Connecting..."
            }
        }
        
        // IDEMPOTENT: Only update notification if the text actually changed
        if (lastNotificationText == notificationText) {
            if (BuildConfig.DEBUG) Log.d("WebSocketService", "Skipping notification update - text unchanged: $notificationText")
            return
        }
        
        // THROTTLE: Prevent rapid notification updates (UI smoothing) - only in debug builds
        if (BuildConfig.DEBUG) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastUpdate = currentTime - lastNotificationUpdateTime
            
            if (timeSinceLastUpdate < MIN_NOTIFICATION_UPDATE_INTERVAL_MS) {
                // Too soon - skip this update to avoid flicker
                if (BuildConfig.DEBUG) Log.d("WebSocketService", "Throttling notification update (${timeSinceLastUpdate}ms < ${MIN_NOTIFICATION_UPDATE_INTERVAL_MS}ms)")
                
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
                        
                        if (BuildConfig.DEBUG) Log.d("WebSocketService", "Delayed notification update: $notificationText")
                    }
                }
                return
            }
        }
        
        lastNotificationText = notificationText
        lastNotificationUpdateTime = System.currentTimeMillis()
        
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
        
        if (BuildConfig.DEBUG) Log.d("WebSocketService", "Connection status updated: $notificationText")
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
