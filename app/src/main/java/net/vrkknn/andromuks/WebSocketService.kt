package net.vrkknn.andromuks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import net.vrkknn.andromuks.utils.NetworkMonitor
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.WebSocket
import org.json.JSONObject
import net.vrkknn.andromuks.utils.trimWebsocketHost
import net.vrkknn.andromuks.utils.getUserAgent
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

    /**
     * Coroutines tied to this service instance only. Cancelled in [onDestroy] so jobs cannot
     * outlive the service or run against a recycled instance after Android restarts the service.
     */
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "websocket_service_channel"
        private const val CHANNEL_NAME = "WebSocket Service"
        // BATTERY OPTIMIZATION: WAKE_LOCK_TAG removed - wake lock no longer used
        private const val ALARM_RESTART_DELAY_MS = 1000L // 1 second delay for AlarmManager restart
        private var instance: WebSocketService? = null
        
        // Constants
        private val BASE_RECONNECTION_DELAY_MS = 500L // 3 seconds - give network time to stabilize
        private val MIN_RECONNECTION_INTERVAL_MS = 1000L // 5 seconds minimum between any reconnections
        private val MIN_NOTIFICATION_UPDATE_INTERVAL_MS = 1000L // 500ms minimum between notification updates (UI smoothing)
        private const val BACKEND_HEALTH_RETRY_DELAY_MS = 1_000L
        // Connection health: Ping every 15s (first ping 15s after connect), mark bad after 60s without ANY message
        private const val PING_INTERVAL_MS = 15_000L // 15 seconds - first ping 15s after connect, then every 15s
        private const val MESSAGE_TIMEOUT_MS = 60_000L // 60 seconds without ANY message = connection stale, reconnect
        private const val PONG_CLEAR_INFLIGHT_MS = 5_000L // Clear pingInFlight after 5s so next ping can be sent
        private const val INIT_COMPLETE_TIMEOUT_MS_BASE = 15_000L // init_complete wait before run_id (unified monitoring / timeouts)
        private const val HARD_CONNECTING_TIMEOUT_MS = 5_000L // stuck in Connecting — force recovery
        private const val RUN_ID_TIMEOUT_MS = 2_000L // run_id must arrive shortly after dial
        private const val INIT_COMPLETE_AFTER_RUN_ID_TIMEOUT_MS_BASE = 5_000L // init_complete base after run_id
        private const val INIT_COMPLETE_EXTENSION_PER_MESSAGE_MS = 5_000L // extend init window per backend message
        private const val MAX_RECONNECTION_ATTEMPTS = 99
        private const val RECONNECTION_RESET_TIME_MS = 300_000L // Reset count after 5 minutes
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 500L // Debounce rapid network changes
        /** First step of connection flow: wait for NET_CAPABILITY_VALIDATED before any DNS/connect. */
        internal const val NETWORK_VALIDATION_TIMEOUT_MS = 10_000L
        
        const val ACTION_HEARTBEAT_ALARM = "net.vrkknn.andromuks.HEARTBEAT_ALARM"
        private const val HEARTBEAT_MARGIN_MS = 5000L // 5 seconds margin for coroutine to win
        private const val HEARTBEAT_WAKE_LOCK_MS = 10000L // 10 seconds wake lock for alarm processing
        
        // Track if Android has denied starting this service as a foreground service
        // for the current process lifetime (e.g., Android 14 FGS quota exhausted).
        // When true, callers should avoid startForegroundService() and use startService() instead.
        @Volatile
        private var foregroundStartNotAllowedForThisProcess: Boolean = false

        /**
         * Helper for callers (e.g., AppViewModel) to decide whether to use startForegroundService()
         * or fall back to startService().
         *
         * Returns false after we have seen a ForegroundServiceStartNotAllowedException
         * for this process lifetime.
         */
        fun shouldUseForegroundService(): Boolean {
            return !foregroundStartNotAllowedForThisProcess
        }
        
        // ViewModel attachment, primary id, and promotion live in [SyncRepository].
        
        // Connection state: single source of truth in [SyncRepository]
        val connectionStateFlow: StateFlow<ConnectionState> = SyncRepository.connectionState
        
        /**
         * CPU Weight levels for dynamic performance management
         */
        enum class CPUWeight {
            RUSH,       // Foreground: High priority, high parallelism
            EFFICIENT,  // Background (Screen OFF): Normal priority, normal parallelism (race to sleep)
            POLITE      // Background (Screen ON): Low priority, low parallelism (don't lag other apps)
        }
        
        /**
         * Get the current CPU weight based on app visibility and screen state.
         */
        fun getCPUWeight(): CPUWeight {
            val service = instance ?: return CPUWeight.EFFICIENT
            if (service.isAppVisible) return CPUWeight.RUSH
            return if (service.isScreenOn) CPUWeight.POLITE else CPUWeight.EFFICIENT
        }
        
        /**
         * Get the recommended Linux thread priority (niceness) based on current app/device state.
         * Values:
         * - THREAD_PRIORITY_MORE_FAVORABLE (-2): RUSH (Foreground)
         * - THREAD_PRIORITY_DEFAULT (0): EFFICIENT (Background, Screen OFF) - Race to sleep
         * - THREAD_PRIORITY_BACKGROUND (10): POLITE (Background, Screen ON) - Don't lag UI
         */
        fun getRecommendedNiceness(): Int {
            return when (getCPUWeight()) {
                CPUWeight.RUSH -> android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE // -2
                CPUWeight.EFFICIENT -> android.os.Process.THREAD_PRIORITY_DEFAULT // 0
                CPUWeight.POLITE -> android.os.Process.THREAD_PRIORITY_BACKGROUND // 10
            }
        }
        
        /**
         * Get the recommended parallelism for CPU-bound tasks (like JSON parsing).
         * - RUSH: coreCount * 2 (max 8)
         * - EFFICIENT: coreCount (max 4)
         * - POLITE: 1 (minimum)
         */
        fun getRecommendedParallelism(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return when (getCPUWeight()) {
                CPUWeight.RUSH -> minOf(cores * 2, 8)
                CPUWeight.EFFICIENT -> minOf(cores, 4)
                CPUWeight.POLITE -> 1
            }
        }
        
        /**
         * Scope for work that must be tied to the running [WebSocketService] instance.
         * Falls back to [AndromuksApplication.applicationScope] briefly if the service is not running.
         */
        fun getServiceScope(): CoroutineScope =
            instance?.serviceScope ?: AndromuksApplication.applicationScope
        
        /**
         * Check if the WebSocketService is currently running
         * Returns true if the service instance exists (service is running)
         */
        fun isServiceRunning(): Boolean {
            return instance != null
        }

        // Shared OkHttpClient for health checks - evict connection pool on network changes
        private val healthCheckClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        /**
         * Evict all connections from the health check client's connection pool.
         * Call this when network changes to avoid stale DNS/connection issues.
         */
        fun evictHealthCheckConnections() {
            healthCheckClient.connectionPool.evictAll()
            if (BuildConfig.DEBUG) {
                android.util.Log.d("WebSocketService", "Evicted all connections from health check client pool")
            }
        }
        
        /**
         * PHASE 1.1: Get the primary ViewModel ID
         * Returns the ID of the AppViewModel instance that is registered as primary
         */
        fun getPrimaryViewModelId(): String? = SyncRepository.getPrimaryViewModelId()
        
        /**
         * PHASE 1.1: Check if a ViewModel ID is the primary instance
         */
        fun isPrimaryInstance(viewModelId: String): Boolean {
            return SyncRepository.getPrimaryViewModelId() == viewModelId
        }

        /**
         * True when a primary id is set and at least one ViewModel is attached in [SyncRepository].
         */
        fun hasPrimaryCallbacks(): Boolean {
            return SyncRepository.getPrimaryViewModelId() != null && SyncRepository.getAttachedViewModels().isNotEmpty()
        }
        
        /**
         * Debug map for connection / attachment state.
         */
        fun getPrimaryCallbackStatus(): Map<String, Boolean> {
            return mapOf(
                "primaryViewModelId" to (SyncRepository.getPrimaryViewModelId() != null),
                "reconnectionCallback" to SyncRepository.getAttachedViewModels().isNotEmpty(),
                "offlineModeCallback" to true,
                "activityLogCallback" to true
            )
        }
        
        /**
         * STEP 3.1: Check if primary ViewModel is still alive and healthy
         * Returns true if:
         * - Primary ViewModel ID is set
         * - Primary ViewModel is still registered (not destroyed)
         * - Primary callbacks are still valid
         * Returns false if primary is missing, destroyed, or callbacks are invalid
         */
        fun isPrimaryAlive(): Boolean {
            val primaryId = SyncRepository.getPrimaryViewModelId()
            if (primaryId == null) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 3.1 - Primary health check: No primary ViewModel ID set")
                return false
            }
            if (!SyncRepository.isPrimaryEntryAlive()) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("WebSocketService", "STEP 3.1 - Primary health check: Primary ViewModel $primaryId missing or not primary in SyncRepository")
                }
                return false
            }
            return true
        }
        
        /**
         * STEP 2.1: Register a ViewModel with the service
         * This tracks which ViewModels are alive and can be used for primary promotion
         * 
         * @param viewModelId Unique identifier for the ViewModel
         * @param isPrimary Whether this ViewModel is the primary instance
         * @return true if registration succeeded or updated, false if registration failed
         */
        fun registerViewModel(viewModelId: String, isPrimary: Boolean): Boolean {
            val ok = SyncRepository.registerViewModel(viewModelId, isPrimary)
            if (ok && BuildConfig.DEBUG) {
                android.util.Log.d("WebSocketService", "STEP 2.1 - registerViewModel → SyncRepository: $viewModelId primary=$isPrimary")
            }
            return ok
        }
        
        /**
         * STEP 2.2: Unregister a ViewModel from the service
         * This is called when a ViewModel is destroyed (e.g., activity destroyed)
         * If the destroyed ViewModel was primary, automatically promotes another ViewModel to primary
         * 
         * @param viewModelId Unique identifier for the ViewModel
         * @return true if unregistered, false if not found
         */
        fun unregisterViewModel(viewModelId: String): Boolean {
            val ok = SyncRepository.detachViewModel(viewModelId)
            if (!ok) {
                android.util.Log.w("WebSocketService", "STEP 2.2 - unregisterViewModel: no attached ViewModel for $viewModelId")
            }
            return ok
        }
        
        /**
         * STEP 2.1: Get list of registered ViewModel IDs
         * Returns all ViewModels that are currently registered (alive)
         */
        fun getRegisteredViewModelIds(): List<String> = SyncRepository.getRegisteredViewModelIds()
        
        /**
         * STEP 2.1: Get list of registered ViewModels (for primary promotion)
         */
        fun getRegisteredViewModelInfos(): List<ViewModelRegistryInfo> =
            SyncRepository.getRegisteredViewModelInfos()
        
        /**
         * STEP 2.1: Check if a ViewModel is registered (alive)
         */
        fun isViewModelRegistered(viewModelId: String): Boolean = SyncRepository.isViewModelRegistered(viewModelId)
        
        /**
         * Legacy no-op: activity logging is handled via [SyncRepository.emitActivityLog] / [SyncEvent.ActivityLog].
         */
        @Suppress("UNUSED_PARAMETER")
        fun setActivityLogCallback(viewModelId: String, callback: (String, String?) -> Unit): Boolean {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setActivityLogCallback: no-op (use SyncRepository)")
            return true
        }
        
        @Deprecated("Use SyncRepository")
        fun setActivityLogCallback(callback: (String, String?) -> Unit) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setActivityLogCallback legacy: no-op")
        }
        
        /**
         * Clears primary id when the primary [AppViewModel] is torn down.
         */
        fun clearPrimaryCallbacks(viewModelId: String): Boolean {
            val pid = SyncRepository.getPrimaryViewModelId()
            if (pid != viewModelId) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearPrimaryCallbacks: Instance $viewModelId is not the primary instance ($pid). Nothing to clear.")
                return false
            }
            android.util.Log.i("WebSocketService", "clearPrimaryCallbacks: Clearing primary id for $viewModelId")
            SyncRepository.clearPrimaryFor(viewModelId)
            return true
        }
        
        /** True when logged-in credentials exist (replacement for legacy reconnection callback presence). */
        private fun hasReconnectionSignal(): Boolean {
            val ctx = instance?.applicationContext ?: return false
            return SyncRepository.hasCredentials(ctx)
        }

        private val reconnectTraceRegex = Regex("\\[(rc-[^\\]]+)\\]")

        private fun extractReconnectTraceId(text: String): String? {
            return reconnectTraceRegex.find(text)?.groupValues?.getOrNull(1)
        }

        private fun withReconnectTrace(traceId: String?, message: String): String {
            return if (traceId.isNullOrBlank()) message else "[$traceId] $message"
        }
        
        /**
         * Dispatches a reconnect dial: reads credentials from SharedPreferences, picks an attached
         * [AppViewModel] when present (primary preferred), and calls [connectWebSocket] (may dial with null VM).
         */
        private fun invokeReconnectionCallback(trigger: ReconnectTrigger, lastReceivedId: Int = 0, forceColdConnect: Boolean = false, logIfMissing: Boolean = true) {
            val serviceInstance = instance ?: run {
                if (logIfMissing) {
                    android.util.Log.w("WebSocketService", "Service instance not available - cannot reconnect: ${trigger.toLogString()}")
                }
                return
            }
            val reasonText = withReconnectTrace(serviceInstance.currentReconnectTraceId, trigger.toLogString())
            val traceId = extractReconnectTraceId(reasonText) ?: serviceInstance.currentReconnectTraceId
            if (!traceId.isNullOrBlank()) {
                serviceInstance.currentReconnectTraceId = traceId
            }
            
            // Service handles reconnection directly - read credentials from SharedPreferences
            serviceInstance.serviceScope.launch {
                try {
                    val prefs = serviceInstance.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
                    val authToken = prefs.getString("gomuks_auth_token", "") ?: ""
                    
                    if (homeserverUrl.isEmpty() || authToken.isEmpty()) {
                        android.util.Log.w("WebSocketService", "Cannot reconnect - missing credentials in SharedPreferences")
                        logActivity(withReconnectTrace(traceId, "Reconnection aborted: missing credentials"), serviceInstance.currentNetworkType.name)
                        return@launch
                    }
                    
                    android.util.Log.i("WebSocketService", "Service handling reconnection directly: ${trigger.toLogString()}")
                    
                    val attached = SyncRepository.getAttachedViewModels()
                    val primaryId = SyncRepository.getPrimaryViewModelId()
                    val viewModelToUse = if (primaryId != null) {
                        SyncRepository.getViewModel(primaryId) ?: attached.firstOrNull()
                    } else {
                        attached.firstOrNull()
                    }
                    
                    val viewModelIdStr = when {
                        viewModelToUse == null -> "none"
                        primaryId != null && SyncRepository.getViewModel(primaryId) === viewModelToUse -> primaryId
                        else -> "attached"
                    }
                    if (viewModelToUse == null) {
                        android.util.Log.i("WebSocketService", "Reconnection with no attached ViewModel — dialing with null AppViewModel (credentials OK)")
                    }
                    android.util.Log.i("WebSocketService", "Reconnecting using ViewModel: $viewModelIdStr (primary: $primaryId, attached: ${attached.size})")
                    logActivity(withReconnectTrace(traceId, "Reconnection dispatch: using ViewModel $viewModelIdStr"), serviceInstance.currentNetworkType.name)
                    
                    val resolvedLastReceivedId: Int
                    val isReconnection: Boolean
                    if (forceColdConnect) {
                        resolvedLastReceivedId = 0
                        isReconnection = false
                        android.util.Log.i("WebSocketService", "invokeReconnectionCallback: forceColdConnect - no run_id/last_received_event")
                    } else {
                        resolvedLastReceivedId = when {
                            lastReceivedId != 0 -> lastReceivedId
                            serviceInstance.connectionState.getLastReceivedRequestId() != 0 ->
                                serviceInstance.connectionState.getLastReceivedRequestId()
                            else -> getLastReceivedRequestId(serviceInstance.applicationContext)
                        }
                        isReconnection = true
                        if (resolvedLastReceivedId != 0 && BuildConfig.DEBUG) {
                            android.util.Log.d("WebSocketService", "invokeReconnectionCallback: using last_received_id=$resolvedLastReceivedId for connectWebSocket")
                        }
                    }
                    logActivity(withReconnectTrace(traceId, "Reconnection dispatch: connectWebSocket"), serviceInstance.currentNetworkType.name)
                    connectWebSocket(homeserverUrl, authToken, viewModelToUse, trigger, resolvedLastReceivedId, isReconnection = isReconnection)
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error during service-initiated reconnection", e)
                }
            }
        }
        
        /**
         * STEP 1.3: Safely invoke offline mode callback with error handling and logging
         * 
         * This method uses stored callbacks (not AppViewModel references), allowing callbacks
         * to work even if the primary AppViewModel is destroyed. The callback broadcasts to all
         * registered ViewModels to update their offline state.
         * 
         * @param isOffline Whether the app is in offline mode
         * @param logIfMissing Whether to log a warning if callback is missing (default: false)
         */
        private fun invokeOfflineModeCallback(isOffline: Boolean, logIfMissing: Boolean = false) {
            SyncRepository.emitOfflineModeChanged(isOffline)
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Offline mode emitted via SyncRepository: isOffline=$isOffline")
        }
        
        /**
         * STEP 1.3: Log an activity event (app started, websocket connected, disconnected, etc.)
         * 
         * This method uses stored callbacks (not AppViewModel references), allowing callbacks
         * to work even if the primary AppViewModel is destroyed. The callback broadcasts to all
         * registered ViewModels to log the activity.
         * 
         * Uses primary callback if available, falls back to legacy callback with error handling
         */
        fun logActivity(event: String, networkType: String? = null) {
            SyncRepository.emitActivityLog(event, networkType)
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
         * Start ping loop for connection health monitoring.
         * First ping 15s after connect, then every 15s.
         * Connection marked bad after 60s without ANY message (ping or otherwise).
         */
        fun startPingLoop() {
            instance?.let { serviceInstance ->
                serviceInstance.pingJob?.cancel()
                serviceInstance.pongTimeoutJob?.cancel()
                serviceInstance.pingJob = null
                serviceInstance.pongTimeoutJob = null
            }
            val svc = instance ?: return
            svc.pingJob = svc.serviceScope.launch {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop started - first ping in 15s")
                // First ping 15 seconds after websocket connect
                delay(PING_INTERVAL_MS)
                
                while (isActive) {
                    val serviceInstance = instance ?: break
                    
                    if (!serviceInstance.connectionState.isReady()) {
                        delay(PING_INTERVAL_MS)
                        continue
                    }
                    
                    // Check 60s message timeout - if no message for 60s, connection is stale
                    val timeSinceLastMessage = System.currentTimeMillis() - serviceInstance.lastMessageReceivedTimestamp
                    if (timeSinceLastMessage >= MESSAGE_TIMEOUT_MS) {
                        android.util.Log.w("WebSocketService", "No message received for ${timeSinceLastMessage}ms (>= ${MESSAGE_TIMEOUT_MS}ms) - reconnecting")
                        logActivity("Message Timeout - Reconnecting", serviceInstance.currentNetworkType.name)
                        clearWebSocket("No message for 60 seconds - connection stale")
                        scheduleReconnection(ReconnectTrigger.MessageTimeout)
                        delay(PING_INTERVAL_MS)
                        continue
                    }
                    
                    if (!serviceInstance.pingInFlight) {
                        serviceInstance.sendPing()
                    }
                    
                    delay(PING_INTERVAL_MS)
                }
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop ended - isActive=$isActive")
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
            android.util.Log.i("WebSocketService", "Pinger status: $status (lastKnownLag: ${serviceInstance.lastKnownLagMs}ms)")
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
         * Set app visibility (kept for compatibility, ping interval is fixed at 15s)
         */
        fun setAppVisibility(visible: Boolean) {
            instance?.isAppVisible = visible
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "App visibility changed to: $visible")
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
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "registerWebSocketSendCallback: no-op ($callbackId)")
            return true
        }
        
        fun unregisterWebSocketSendCallback(callbackId: String): Boolean {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "unregisterWebSocketSendCallback: no-op ($callbackId)")
            return true
        }
        
        fun registerReceiveCallback(viewModelId: String, viewModel: AppViewModel) {
            SyncRepository.attachViewModel(viewModelId, viewModel)
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "registerReceiveCallback → SyncRepository.attach ($viewModelId)")
        }
        
        fun unregisterReceiveCallback(viewModelId: String): Boolean {
            val ok = SyncRepository.detachViewModel(viewModelId)
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "unregisterReceiveCallback → SyncRepository.detach ($viewModelId) ok=$ok")
            return ok
        }
        
        fun getRegisteredViewModels(): List<AppViewModel> = SyncRepository.getAttachedViewModels()
        
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
         * Registers primary role for cache-clear semantics (no-op callback; [SyncRepository] handles events).
         */
        @Suppress("UNUSED_PARAMETER")
        fun setPrimaryClearCacheCallback(viewModelId: String, callback: () -> Unit): Boolean {
            val pid = SyncRepository.getPrimaryViewModelId()
            if (pid != null && pid != viewModelId) {
                android.util.Log.w("WebSocketService", "setPrimaryClearCacheCallback: Another instance ($pid) is already primary. Rejecting $viewModelId")
                return false
            }
            if (pid == null) {
                SyncRepository.registerViewModel(viewModelId, isPrimary = true)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setPrimaryClearCacheCallback: no-op (SyncRepository handles cache events)")
            return true
        }
        
        @Suppress("UNUSED_PARAMETER")
        fun setReconnectionCallback(viewModelId: String, callback: (String) -> Unit): Boolean {
            val pid = SyncRepository.getPrimaryViewModelId()
            if (pid != null && pid != viewModelId) {
                android.util.Log.w("WebSocketService", "setReconnectionCallback: Another instance ($pid) is already primary. Rejecting $viewModelId")
                return false
            }
            if (pid == null) {
                SyncRepository.registerViewModel(viewModelId, isPrimary = true)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setReconnectionCallback: primary id set ($viewModelId); reconnection uses SyncRepository/credentials")
            processPendingReconnections()
            return true
        }
        
        /**
         * PHASE 2.1: Process pending reconnection requests that were queued when callback was unavailable
         */
        private fun processPendingReconnections() {
            val serviceInstance = instance ?: return
            
            if (!hasReconnectionSignal()) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "processPendingReconnections: No credentials / reconnection signal, skipping")
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
                queuedReasons.forEach { queuedTrigger ->
                    android.util.Log.i("WebSocketService", "processPendingReconnections: Processing queued reconnection: $queuedTrigger")
                    // Use scheduleReconnection to ensure proper handling (rate limiting, state checks, etc.)
                    scheduleReconnection(queuedTrigger)
                }
            }
        }
        
        /**
         * Legacy method - kept for backward compatibility
         * @deprecated Use setReconnectionCallback(viewModelId, callback) instead
         */
        @Deprecated("Use SyncRepository")
        @Suppress("UNUSED_PARAMETER")
        fun setReconnectionCallback(callback: (String) -> Unit) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setReconnectionCallback legacy: no-op")
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
        @Suppress("UNUSED_PARAMETER")
        fun setOfflineModeCallback(viewModelId: String, callback: (Boolean) -> Unit): Boolean {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setOfflineModeCallback: no-op (offline via SyncRepository)")
            return true
        }
        
        @Deprecated("Use SyncRepository")
        @Suppress("UNUSED_PARAMETER")
        fun setOfflineModeCallback(callback: (Boolean) -> Unit) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setOfflineModeCallback legacy: no-op")
        }
        
        /**
         * Check if a request_id is a ping request
         */
        fun isPingRequestId(requestId: Int): Boolean {
            val serviceInstance = instance ?: return false
            return requestId == serviceInstance.lastPingRequestId
        }
        
        /**
         * Check if WebSocket is currently connected and ready
         */
        fun isConnected(): Boolean {
            val serviceInstance = instance ?: return false
            return serviceInstance.connectionState.isReady() && serviceInstance.webSocket != null
        }
        
        
        /**
         * Mark network as healthy (e.g., when FCM is received)
         */
        fun markNetworkHealthy() {
            val serviceInstance = instance ?: return
            serviceInstance.consecutivePingTimeouts = 0
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network marked as healthy")
        }
    
        /**
         * Trigger reconnection check if needed
         * This can be called from FCM or other external triggers
         */
        fun triggerBackendHealthCheck() {
            val serviceInstance = instance ?: return
            serviceInstance.serviceScope.launch {
                try {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "triggerBackendHealthCheck called - checking connection state")
                    // Skip HTTP health check - if WebSocket is not connected, trigger reconnection
                    if (!serviceInstance.connectionState.isReady() || serviceInstance.webSocket == null) {
                        android.util.Log.w("WebSocketService", "WebSocket not connected - triggering reconnection")
                        triggerReconnectionFromExternal(ReconnectTrigger.ExternalTriggerNotConnected)
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "WebSocket already connected and ready")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in triggerBackendHealthCheck", e)
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
        fun triggerReconnectionFromExternal(trigger: ReconnectTrigger = ReconnectTrigger.ExternalTriggerNotConnected) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "triggerReconnectionFromExternal called: ${trigger.toLogString()}")
            // PHASE 1.4: Use safe invocation helper with error handling
            invokeReconnectionCallback(trigger)
        }
        
        /**
         * Safely trigger reconnection with validation
         * RUSH TO HEALTHY: Simplified - no exponential backoff, just validate and reconnect
         */
        fun triggerReconnectionSafely(trigger: ReconnectTrigger) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "triggerReconnectionSafely called: ${trigger.toLogString()}")
            
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
            if (!hasReconnectionSignal()) {
                android.util.Log.w("WebSocketService", "Reconnection callback not set (AppViewModel not available) - cannot reconnect. Waiting for app to connect WebSocket properly.")
                logActivity("Reconnection deferred: activity callback not set", getCurrentNetworkType().name)
                return
            }
            
            // RUSH TO HEALTHY: Use scheduled reconnection (handles backend health check and fast retry)
            scheduleReconnection(trigger)
        }
        
        /**
         * Notify that a message was received from the server.
         * Resets the 60-second message timeout - connection is marked bad if no message for 60s.
         * Call from NetworkUtils on every message (pong, run_id, sync_complete, etc.)
         */
        fun onMessageReceived() {
            val serviceInstance = instance ?: return
            serviceInstance.lastMessageReceivedTimestamp = System.currentTimeMillis()
        }
        
        /**
         * Handle pong response
         * RUSH TO HEALTHY: Reset failure counter on any successful pong
         */
        fun handlePong(requestId: Int) {
            val serviceInstance = instance ?: return
            onMessageReceived() // Reset 60s message timeout
            
            // Accept pong if it matches the last ping request ID (most recent ping)
            // This prevents processing stale pongs from previous connections
            if (requestId == serviceInstance.lastPingRequestId) {
                serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
                serviceInstance.consecutivePingTimeouts = 0 // Reset consecutive timeouts on success
                
                // Reschedule heartbeat alarm fallback - we just got a pong, so connection is alive
                serviceInstance.scheduleHeartbeatAlarm()
                
                if (serviceInstance.pingInFlight) {
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
                }
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
         * Transitions to RECONNECTING state with last_received_id
         */
        fun triggerReconnection(trigger: ReconnectTrigger) {
            android.util.Log.i("WebSocketService", "Triggering WebSocket reconnection: ${trigger.toLogString()}")
            val serviceInstance = instance ?: return
            
            // Get last received request ID for reconnection
            val lastReceivedId = getLastReceivedRequestId(serviceInstance.applicationContext)
            
            // Only trigger reconnection if not already reconnecting
            if (!serviceInstance.connectionState.isReconnectingPhase()) {
                // Clear current connection
                clearWebSocket("Reconnecting: ${trigger.toLogString()}")
                
                val rid = getCurrentRunId()
                if (lastReceivedId != 0) {
                    updateConnectionState(ConnectionState.QuickReconnecting(rid, lastReceivedId, 1))
                } else {
                    updateConnectionState(ConnectionState.FullReconnecting)
                }
                
                // Schedule reconnection attempt
                scheduleReconnection(trigger)
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting, ignoring trigger: ${trigger.toLogString()}")
            }
        }
        
        // Network monitoring removed: backend health checks and ping/pong now drive reconnections exclusively.
        
        /**
         * Set WebSocket connection
         */
        fun setWebSocket(webSocket: WebSocket) {
            val serviceInstance = instance ?: return
            val traceId = serviceInstance.currentReconnectTraceId
            android.util.Log.i("WebSocketService", "setWebSocket() called - setting up WebSocket connection")
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Current connection state: ${serviceInstance.connectionState}")
            
            // Validate state before setting WebSocket
            detectAndRecoverStateCorruption()
            
            // FIX #2: Only skip if there's actually a live WebSocket (not just a stale state)
            // This prevents race conditions where state is Connecting but no actual connection is in progress
            if (serviceInstance.connectionState.isConnecting() && serviceInstance.webSocket != null) {
                android.util.Log.w("WebSocketService", "WebSocket connection already in progress with active socket, ignoring new connection")
                return
            }
            
            // CRITICAL FIX: If we're already Ready with a live socket, reject replacement attempts
            // This prevents reconnection jobs from killing healthy connections
            if (serviceInstance.connectionState.isReady() && serviceInstance.webSocket != null) {
                android.util.Log.w("WebSocketService", "WebSocket already Ready with live socket - rejecting replacement attempt (likely stale reconnection job)")
                // Close the new socket immediately - we don't want it
                webSocket.close(1000, "Connection already established")
                return
            }
            
            // If already dialing or syncing (but not Ready), close the old connection first (for reconnection)
            if (serviceInstance.connectionState.isDialOrSyncing()) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Replacing existing WebSocket connection (reconnection)")
                serviceInstance.webSocket?.close(1000, "Reconnecting")
            }
            
            // Set webSocket BEFORE transitioning to Ready to prevent a race with
            // detectAndRecoverStateCorruption(): if the monitoring coroutine runs between the
            // state update and the webSocket assignment it would see Ready+null, wrongly reset
            // state to Disconnected, and leave the notification stuck at "Connecting..." even
            // though the socket is alive and delivering messages.
            serviceInstance.webSocket = webSocket
            serviceInstance.connectionStartTime = System.currentTimeMillis()
            serviceInstance.lastMessageReceivedTimestamp = System.currentTimeMillis()
            serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
            serviceInstance.hadSuccessfulConnectionThisProcess = true
            // Connection is marked good when WebSocket connects - we don't wait for run_id or init_complete
            updateConnectionState(ConnectionState.Ready)
            
            resetReconnectionState()
            
            val actualNetworkType = serviceInstance.getNetworkTypeFromCapabilities()
            serviceInstance.currentNetworkType = actualNetworkType
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network type set to: $actualNetworkType")
            
            // Start ping loop: first ping 15s after connect, then every 15s
            if (!serviceInstance.pingLoopStarted) {
                serviceInstance.pingLoopStarted = true
                serviceInstance.hasEverReachedReadyState = true
                android.util.Log.i("WebSocketService", "WebSocket connected - starting ping loop (first ping in 15s)")
                logActivity(withReconnectTrace(traceId, "WebSocket Connected"), actualNetworkType.name)
                startPingLoop()
            }
            
            updateConnectionStatus(true, null, serviceInstance.lastSyncTimestamp)
            android.util.Log.i("WebSocketService", "WebSocket connection opened - connection good")
            logPingStatus()
            serviceInstance.showWebSocketToast("Connection opened")
            
            // Add startup progress message to all registered ViewModels
            for (viewModel in getRegisteredViewModels()) {
                viewModel.addStartupProgressMessage("Connection established")
            }
        }
        
        /**
         * Clear WebSocket connection
         */
        /**
         * PHASE 4.1: Clear WebSocket with close code and reason
         */
        fun clearWebSocket(reason: String = "Unknown", closeCode: Int? = null, closeReason: String? = null) {
            val serviceInstance = instance ?: return
            
            // PHASE 4.1: Store close code and reason if provided
            if (closeCode != null) {
                serviceInstance.lastCloseCode = closeCode
                serviceInstance.lastCloseReason = closeReason ?: reason
                android.util.Log.i("WebSocketService", "WebSocket closed with code $closeCode: ${closeReason ?: reason}")
            }
            
            serviceInstance.pingInFlight = false // Reset ping-in-flight flag
            
            // Check if we're in a state that needs clearing (any active state)
            // DISCONNECTED state means we're already cleared, so we can skip
            val needsClearing = !serviceInstance.connectionState.isDisconnected() || serviceInstance.webSocket != null
            
            if (!needsClearing) {
                // Already disconnected and no WebSocket, don't log redundant disconnection
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearWebSocket() called but already disconnected - skipping")
                return
            }
            
            android.util.Log.w("WebSocketService", "clearWebSocket() called - setting connection state to DISCONNECTED (was: ${serviceInstance.connectionState})")
            
            // Show toast for connection cleared
            serviceInstance.showWebSocketToast("Connection killed: $reason")
            
            // Validate state before clearing
            detectAndRecoverStateCorruption()
            
            // Close the WebSocket properly. Null out BEFORE close() so any synchronous onClosing
            // callback sees serviceInstance.webSocket !== closingSocket and does not double-clear
            // or cancel the reconnection job / schedule duplicate reconnections (race on network switch).
            val wsToClose = serviceInstance.webSocket
            serviceInstance.webSocket = null
            wsToClose?.close(1000, "Clearing connection")
            serviceInstance.connectionLostAt = System.currentTimeMillis()
            updateConnectionState(ConnectionState.Disconnected)
            
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            
            // Reset connection start time
            serviceInstance.connectionStartTime = 0
            
            // Cancel any pending pong timeouts (ping loop keeps running)
            serviceInstance.pongTimeoutJob?.cancel()
            serviceInstance.pongTimeoutJob = null
            
            // Cancel init_complete timeout if active
            serviceInstance.initCompleteTimeoutJob?.cancel()
            serviceInstance.initCompleteTimeoutJob = null
            // Cancel run_id timeout if active
            serviceInstance.runIdTimeoutJob?.cancel()
            serviceInstance.runIdTimeoutJob = null
            // CRITICAL FIX: Cancel hard timeout when clearing WebSocket
            serviceInstance.hardConnectingTimeoutJob?.cancel()
            serviceInstance.hardConnectingTimeoutJob = null
            serviceInstance.runIdReceived = false
            serviceInstance.runIdReceivedTime = 0
            serviceInstance.messagesReceivedWhileWaitingForInitComplete = 0
            serviceInstance.initCompleteTimeoutEndTime = 0
            
            // Reset connection health tracking
            serviceInstance.lastKnownLagMs = null
            serviceInstance.lastPongTimestamp = 0L
            serviceInstance.lastMessageReceivedTimestamp = 0L
            
            // Reset ping loop state for next connection (ready-state flag stays true so failsafe can run)
            serviceInstance.pingLoopStarted = false
            
            // Log activity: WebSocket disconnected
            logActivity("WebSocket Disconnected - $reason", serviceInstance.currentNetworkType.name)
            
            // Update notification to show disconnection
            updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "WebSocket connection cleared in service")
            logPingStatus()

            // Notify all registered ViewModels to reset their per-connection sync state.
            // Must run on Main because the flags are read by Compose (e.g. initialSyncPhase,
            // initializationComplete). We use the service scope so this survives even if a
            // ViewModel is being destroyed simultaneously.
            serviceInstance.serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                for (vm in getRegisteredViewModels()) {
                    vm.onWebSocketCleared(reason)
                }
            }
        }
        
        /**
         * Handle WebSocket closing events safely, ignoring stale sockets.
         *
         * When we replace an existing WebSocket with a new one in setWebSocket(), the old socket
         * will still emit onClosing/onClosed callbacks. Previously, those callbacks always invoked
         * clearWebSocket(), which would incorrectly tear down the NEW active connection.
         *
         * This helper ensures we only clear the connection if the closing socket is the currently
         * active one stored in the service. Closes from stale/old sockets are logged and ignored.
         * 
         * CRITICAL FIX: If we're in Connecting state when WebSocket closes, treat it as a failed
         * connection attempt and schedule reconnection, regardless of close code. Code 1000 during
         * Connecting means the connection attempt failed, not that we had a healthy connection.
         */
        fun handleWebSocketClosing(webSocket: WebSocket, code: Int, reason: String) {
            val serviceInstance = instance ?: return
            
            // Only act if this WebSocket is the one currently tracked by the service.
            // If it's a stale socket from a previous connection, ignore its close event.
            if (serviceInstance.webSocket !== webSocket) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "WebSocketService",
                        "handleWebSocketClosing: Ignoring close for stale WebSocket instance (code=$code, reason=$reason)"
                    )
                }
                return
            }
            
            val wasReady = serviceInstance.connectionState.isReady()
            val wasConnecting = serviceInstance.connectionState.isConnecting()
            
            clearWebSocket("WebSocket closing ($code)", code, reason)
            
            // Schedule reconnection when connection was closed by server
            // - If Ready: had healthy connection, server closed us (1000, 1001, 1002, 1003, etc.)
            // - If Connecting: failed connection attempt
            if (wasReady || wasConnecting) {
                val logMsg = if (wasReady) {
                    "WebSocket closed by server (code=$code) - scheduling reconnection"
                } else {
                    "WebSocket closed during connection attempt (code=$code) - scheduling reconnection"
                }
                android.util.Log.w("WebSocketService", logMsg)
                logActivity("Connection Closed - Reconnecting", serviceInstance.currentNetworkType.name)
                
                synchronized(serviceInstance.reconnectionLock) {
                    serviceInstance.reconnectionJob?.cancel()
                    serviceInstance.reconnectionJob = null
                }
                
                scheduleReconnection(ReconnectTrigger.WebSocketClosed(code, reason))
            }
        }
        
        /**
         * PHASE 4.1: Get last WebSocket close code
         */
        fun getLastCloseCode(): Int? {
            return instance?.lastCloseCode
        }
        
        /**
         * PHASE 4.1: Get last WebSocket close reason
         */
        fun getLastCloseReason(): String? {
            return instance?.lastCloseReason
        }
        
        /**
         * Returns true if [ws] is the currently active WebSocket tracked by the service.
         * Use this in OkHttp listener callbacks (onClosing / onClosed) to ignore events from
         * stale sockets that were replaced by a new connection (e.g. after a network-type switch).
         */
        fun isActiveWebSocket(ws: WebSocket): Boolean {
            val serviceInstance = instance ?: return false
            return serviceInstance.webSocket === ws
        }

        /**
         * Check if WebSocket is connected and ready
         * 
         * CRITICAL: This checks both the connection state AND the WebSocket reference.
         * If instance is null, the service isn't running, so we return false.
         * If connectionState is READY but webSocket is null, the connection was lost.
         */
        fun isWebSocketConnected(): Boolean {
            val serviceInstance = instance ?: return false
            // Both conditions must be true: state is READY AND WebSocket reference exists
            val isConnected = serviceInstance.connectionState.isReady() && serviceInstance.webSocket != null
            if (BuildConfig.DEBUG && !isConnected && serviceInstance.connectionState.isReady()) {
                // Log when state says READY but WebSocket is null (state corruption)
                android.util.Log.w("WebSocketService", "isWebSocketConnected(): State is READY but webSocket is null - state corruption detected")
            }
            return isConnected
        }
        
        /**
         * Get current connection state (for debugging and recovery)
         */
        fun getConnectionState(): ConnectionState? {
            return instance?.connectionState
        }
        
        /**
         * Check if service is currently reconnecting or connecting
         * Used by health check workers to avoid redundant reconnection attempts
         */
        fun isReconnectingOrConnecting(): Boolean {
            val serviceInstance = instance ?: return false
            val state = serviceInstance.connectionState
            val reconnectJobActive = serviceInstance.reconnectionJob?.isActive == true
            return state.isConnecting() || state.isReconnectingPhase() || reconnectJobActive
        }
        
        /**
         * Get current network type (for checking if network is available)
         */
        fun getCurrentNetworkType(): NetworkType {
            return instance?.currentNetworkType ?: NetworkType.NONE
        }
        
        /**
         * Check if connection is stuck in CONNECTING or RECONNECTING state
         */
        fun isConnectionStuck(): Boolean {
            val serviceInstance = instance ?: return false
            val state = serviceInstance.connectionState
            val hasCallback = hasReconnectionSignal()
            
            // Stuck if in CONNECTING/RECONNECTING state but no callback to handle recovery
            if ((state.isConnecting() || state.isReconnectingPhase()) && !hasCallback) {
                return true
            }
            
            if (state.isDialOrSyncing()) {
                val timeSinceConnect = if (serviceInstance.connectionStartTime > 0) {
                    System.currentTimeMillis() - serviceInstance.connectionStartTime
                } else {
                    0L
                }
                if (timeSinceConnect > 20_000) {
                    return true
                }
            }
            
            return false
        }
        
        /**
         * Check if service is healthy and can handle reconnections
         */
        fun isServiceHealthy(): Boolean {
            val serviceInstance = instance ?: return false
            // PHASE 1.2: Check active callback (primary or legacy)
            return hasReconnectionSignal() &&
                   !serviceInstance.connectionState.isDisconnected()
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
            if (!hasReconnectionSignal()) {
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
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Running state consistency check (single source: ConnectionState)...")
            
            // Reconnecting/backoff is fully described by ConnectionState; no parallel boolean sweep needed.
            
            // 1. WebSocket vs ConnectionState mismatch
            val hasWebSocket = serviceInstance.webSocket != null
            val isReady = serviceInstance.connectionState.isReady()
            
            if (hasWebSocket && !isReady && !serviceInstance.connectionState.isDialOrSyncing()) {
                android.util.Log.w("WebSocketService", "CORRUPTION: WebSocket exists but state is not active - recovering")
                // Don't auto-recover - let normal flow handle it
                corruptionDetected = true
            } else if (!hasWebSocket && isReady) {
                android.util.Log.w("WebSocketService", "CORRUPTION: State is READY but no WebSocket - recovering")
                updateConnectionState(ConnectionState.Disconnected)
                corruptionDetected = true
            }
            
            // 3. Check for stuck pong timeout (should only be active when ready and waiting for pong)
            if (serviceInstance.pongTimeoutJob?.isActive == true && 
                !serviceInstance.connectionState.isReady()) {
                android.util.Log.w("WebSocketService", "CORRUPTION: Pong timeout running but not ready - stopping timeout")
                serviceInstance.pongTimeoutJob?.cancel()
                serviceInstance.pongTimeoutJob = null
                corruptionDetected = true
            }
            
            if (corruptionDetected) {
                android.util.Log.i("WebSocketService", "State corruption detected and recovered")
                serviceInstance.updateConnectionStatus(serviceInstance.connectionState.isReady())
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
            serviceInstance.unifiedMonitoringJob?.cancel()
            
            updateConnectionState(ConnectionState.Disconnected)
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
         * Update connection state (both instance and StateFlow)
         */
        private fun updateConnectionState(newState: ConnectionState) {
            val serviceInstance = instance
            if (serviceInstance != null) {
                val oldState = serviceInstance.connectionState
                serviceInstance.connectionState = newState
                android.util.Log.i("WebSocketService", "State transition: $oldState → $newState")
            }
            SyncRepository.updateConnectionState(newState)
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connection state updated: $newState")
            // Discard any sync_complete messages buffered during the now-terminated connection so
            // they are not replayed against a new session.  This must run on every transition to
            // Disconnected (clearWebSocket(), state-corruption recovery, health-check timeout, …).
            if (newState is ConnectionState.Disconnected) {
                SyncRepository.clearSyncBuffer()
            }
        }
        
        // ConnectionState helpers: see net.vrkknn.andromuks.ConnectionState.kt (single source of truth)
        
        /**
         * Send a WebSocket command
         * @param command The command name (e.g., "get_room_state", "mark_read")
         * @param requestId The request ID for matching responses (0 for no response expected)
         * @param data The command data as a map
         * @return true if the command was sent successfully, false otherwise
         */
        fun sendCommand(command: String, requestId: Int, data: Map<String, Any>): Boolean {
            val serviceInstance = instance ?: run {
                android.util.Log.w("WebSocketService", "sendCommand() called but service instance is null")
                return false
            }
            
            val ws = serviceInstance.webSocket
            if (ws == null) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "sendCommand() called but WebSocket is null: $command")
                return false
            }
            
            // Check connection state - only allow commands when READY
            if (!serviceInstance.connectionState.isReady()) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "sendCommand() called but not ready (state: ${serviceInstance.connectionState}): $command")
                return false
            }
            
            return try {
                val json = JSONObject()
                json.put("command", command)
                json.put("request_id", requestId)
                json.put("data", JSONObject(data))
                val jsonString = json.toString()
                
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "sendCommand: command='$command', requestId=$requestId")
                
                ws.send(jsonString)
            } catch (e: Exception) {
                android.util.Log.e("WebSocketService", "Failed to send WebSocket command: $command", e)
                false
            }
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
         * Last WebSocket URL actually used for a (re)connection.
         * Persisted when we connect in NetworkUtils; used by Settings to show the real URL.
         */
        fun getLastConnectionUrl(context: Context): String {
            return try {
                context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    .getString("last_websocket_connection_url", "") ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        
        /**
         * Wait for service instance to be ready (with timeout)
         * This prevents race conditions where methods are called before onCreate()
         */
        private suspend fun waitForServiceInstance(timeoutMs: Long = 5_000L): WebSocketService? {
            if (instance != null) {
                return instance
            }
            
            // Poll for instance with short delays
            val startTime = System.currentTimeMillis()
            val pollDelay = 50L // 50ms between checks
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (instance != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service instance ready after ${System.currentTimeMillis() - startTime}ms")
                    return instance
                }
                delay(pollDelay)
            }
            
            android.util.Log.w("WebSocketService", "Service instance not ready after ${timeoutMs}ms timeout")
            return null
        }
        
        /**
         * REFACTORING: Initiate WebSocket connection from service
         * This method will eventually own the WebSocket connection lifecycle
         * For now, it delegates to AppViewModel but the service will own the WebSocket reference
         */
        fun connectWebSocket(
            homeserverUrl: String,
            token: String,
            appViewModel: AppViewModel? = null,
            trigger: ReconnectTrigger = ReconnectTrigger.Unclassified("Service-initiated connection"),
            lastReceivedId: Int = 0,
            isReconnection: Boolean = false
        ) {
            // Must not require [instance] here: [startForegroundService] returns before [onCreate] sets [instance].
            // Launch on [getServiceScope] (service or application fallback), then wait for the instance inside.
            getServiceScope().launch {
                try {
                    // CRITICAL FIX: Wait for service instance to be ready (with timeout)
                    // This prevents race condition where connectWebSocket() is called before onCreate()
                    val serviceInstance = waitForServiceInstance(5_000L) ?: run {
                        android.util.Log.e("WebSocketService", "connectWebSocket() called but service instance is null after waiting 5 seconds")
                        return@launch
                    }
                    val reason = withReconnectTrace(serviceInstance.currentReconnectTraceId, trigger.toLogString())
                    val traceId = extractReconnectTraceId(reason) ?: serviceInstance.currentReconnectTraceId
                    if (!traceId.isNullOrBlank()) {
                        serviceInstance.currentReconnectTraceId = traceId
                    }
                    android.util.Log.i("WebSocketService", "connectWebSocket() called - reason: $reason, isReconnection: $isReconnection")
                    
                    // Check if already connected
                    if (isWebSocketConnected()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "connectWebSocket() called but already connected - skipping")
                        logActivity(withReconnectTrace(traceId, "connectWebSocket skipped: already connected"), serviceInstance.currentNetworkType.name)
                        return@launch
                    }
                    
                    if (serviceInstance.connectionState is ConnectionState.Connecting) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "connectWebSocket() called but dial already in progress - skipping")
                        logActivity(withReconnectTrace(traceId, "connectWebSocket skipped: already connecting"), serviceInstance.currentNetworkType.name)
                        return@launch
                    }
                    
                    synchronized(serviceInstance.reconnectionLock) {
                        val attempt = serviceInstance.reconnectionAttemptCount.coerceAtLeast(0) + 1
                        updateConnectionState(ConnectionState.Connecting(attempt))
                    }
                    serviceInstance.startHardConnectingTimeout()
                    
                    // STATE A: First step - wait for NET_CAPABILITY_VALIDATED before any DNS/connect.
                    // On cold start, currentNetworkType may still be NONE (NetworkMonitor hasn't fired yet).
                    // We must wait for validation if network is NONE, even for reconnection attempts.
                    val validated = if (isReconnection && serviceInstance.currentNetworkType != NetworkType.NONE) {
                        true
                    } else {
                        serviceInstance.waitForNetworkValidation(NETWORK_VALIDATION_TIMEOUT_MS)
                    }
                    var useColdConnect = false
                    if (!validated) {
                        val disconnectedMs = if (serviceInstance.connectionLostAt > 0) System.currentTimeMillis() - serviceInstance.connectionLostAt else 0L
                        // If we've been disconnected for more than 60 minutes, fall back to a cold connect
                        // (no run_id/last_received_event) instead of attempting a resume.
                        val offlineThresholdMs = 60L * 60_000L // 60 minutes
                        if (serviceInstance.connectionLostAt > 0 && disconnectedMs > offlineThresholdMs) {
                            android.util.Log.i("WebSocketService", "connectWebSocket: Validation timeout but disconnected >60 min (${disconnectedMs}ms) - connecting cold (no run_id/last_received_event)")
                            logActivity(withReconnectTrace(traceId, "Validation Timeout - Connecting Cold (>60 min offline)"), serviceInstance.currentNetworkType.name)
                            serviceInstance.showWebSocketToast("Connecting without resume (offline >60 min)")
                            useColdConnect = true
                        } else {
                            android.util.Log.w("WebSocketService", "connectWebSocket: Network not validated within ${NETWORK_VALIDATION_TIMEOUT_MS}ms - aborting (will retry on next trigger)")
                            serviceInstance.showWebSocketToast("Network not validated")
                            logActivity(withReconnectTrace(traceId, "connectWebSocket aborted: network not validated"), serviceInstance.currentNetworkType.name)
                            updateConnectionState(ConnectionState.Disconnected)
                            return@launch
                        }
                    }
                    val effectiveIsReconnection = isReconnection && !useColdConnect
                    val effectiveLastReceivedId = if (useColdConnect) 0 else lastReceivedId
                    
                    // If last_received_id is provided, store it in SharedPreferences for NetworkUtils to read
                    if (effectiveLastReceivedId != 0) {
                        updateLastReceivedRequestId(effectiveLastReceivedId, serviceInstance.applicationContext)
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Stored last_received_id: $effectiveLastReceivedId for reconnection")
                    }
                    
                    // Skip backend health check on initial connect - WebSocket will fail fast if backend is unreachable
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connecting WebSocket (isReconnection=$effectiveIsReconnection)")
                    logActivity(withReconnectTrace(traceId, "WebSocket dial started (isReconnection=$effectiveIsReconnection)"), serviceInstance.currentNetworkType.name)
                    
                    val client = okhttp3.OkHttpClient.Builder().build()
                    net.vrkknn.andromuks.utils.connectToWebsocket(homeserverUrl, client, token, serviceInstance.applicationContext, appViewModel, reason = reason, isReconnection = effectiveIsReconnection)
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "NetworkUtils.connectToWebsocket() call completed")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in connectWebSocket()", e)
                    clearWebSocket("Connection error: ${e.message}")
                    // clearWebSocket() updates [ConnectionState] (e.g. Disconnected); no duplicate reset here
                }
                // Connecting state is cleared by clearWebSocket() on failure or by transition to Ready/Initializing on success
            }
        }
        
        /**
         * Update INITIALIZING state progress when sync_complete is processed
         * Transitions to READY when all sync_completes are processed
         * 
         * @param pendingCount Total number of sync_complete messages to process (0 = unknown/not tracking)
         * @param processedCount Number of sync_complete messages processed so far
         */
        fun updateInitializingProgress(pendingCount: Int = 0, processedCount: Int = 0) {
            val serviceInstance = instance ?: return
            val currentState = serviceInstance.connectionState
            
            if (currentState is ConnectionState.Initializing) {
                val newProcessed = if (processedCount > 0) processedCount else currentState.receivedSyncCount + 1
                val newPending = if (pendingCount > 0) pendingCount else currentState.pendingSyncCount
                
                // Transition to READY if all sync_completes are processed
                if (newPending > 0 && newProcessed >= newPending) {
                    updateConnectionState(ConnectionState.Ready)
                    android.util.Log.i("WebSocketService", "All sync_completes processed - state transition: INITIALIZING → READY")
                    
                    // Cancel any pending reconnection jobs now that we're Ready
                    resetReconnectionState()
                    
                    // Start ping loop when ready
                    if (!serviceInstance.pingLoopStarted) {
                        serviceInstance.pingLoopStarted = true
                        serviceInstance.hasEverReachedReadyState = true
                        android.util.Log.i("WebSocketService", "WebSocket ready, starting ping loop")
                        logActivity("WebSocket Ready", serviceInstance.currentNetworkType.name)
                        startPingLoop()
                    }
                } else {
                    // Update progress
                    updateConnectionState(ConnectionState.Initializing(
                        runId = currentState.runId.ifEmpty { getCurrentRunId() },
                        pendingSyncCount = newPending,
                        receivedSyncCount = newProcessed
                    ))
                }
            } else if (currentState is ConnectionState.Ready) {
                // Already ready - no update needed
            } else {
                // Not in INITIALIZING state - might be a race condition, log it
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "updateInitializingProgress called but state is $currentState (expected INITIALIZING)")
            }
        }
        
        /**
         * Update last sync timestamp when sync_complete is received
         */
        fun updateLastSyncTimestamp() {
            val serviceInstance = instance ?: return
            serviceInstance.lastSyncTimestamp = System.currentTimeMillis()
            
            // Update notification with current lag and new sync timestamp
            updateConnectionStatus(serviceInstance.connectionState.isReady(), serviceInstance.lastKnownLagMs, serviceInstance.lastSyncTimestamp)
        }
        
        /**
         * Extend init_complete timeout when a message is received from backend
         * Each message extends the timeout by 5 seconds, indicating the connection is alive but slow
         * Called from NetworkUtils when messages are received
         */
        fun extendInitCompleteTimeoutOnMessage() {
            val serviceInstance = instance ?: return
            serviceInstance.extendInitCompleteTimeoutOnMessage()
        }
        
        /**
         * Update last received request_id from sync_complete (stored in RAM for faster reconnections)
         * This is called after sync_complete is processed successfully
         * 
         * @param requestId The request_id from the sync_complete message
         */
        fun updateLastReceivedRequestId(requestId: Int, context: Context) {
            val serviceInstance = instance
            // CRITICAL: request_id can be negative (and usually is), so check != 0 instead of > 0
            if (requestId != 0) {
                // Update RAM value for fast access
                serviceInstance?.lastReceivedRequestId = requestId
                
                // CRITICAL: Also persist to SharedPreferences so it survives service restarts
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("last_received_request_id", requestId).apply()
                
                //if (BuildConfig.DEBUG) {
                //    android.util.Log.d("WebSocketService", "Updated last_received_request_id to $requestId (stored in RAM and SharedPreferences for faster reconnections)")
                //}
            }
        }
        
        /**
         * Get last received request_id (for reconnection URL parameter)
         * Returns 0 if no sync_complete has been received yet
         * Note: request_id can be negative (and usually is)
         * 
         * First checks RAM (fast), then falls back to SharedPreferences (survives restarts)
         */
        fun getLastReceivedRequestId(context: Context): Int {
            // First check RAM (fast access)
            val ramValue = instance?.lastReceivedRequestId
            if (ramValue != null && ramValue != 0) {
                return ramValue
            }
            
            // Fall back to SharedPreferences (survives service restarts)
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val persistedValue = prefs.getInt("last_received_request_id", 0)
            
            // If we have a persisted value but RAM is empty, restore it to RAM
            if (persistedValue != 0 && instance != null) {
                instance!!.lastReceivedRequestId = persistedValue
            }
            
            return persistedValue
        }
        
        /**
         * Clear last_received_request_id (called on cold starts)
         * This ensures we don't use stale values on initial connections
         */
        fun clearLastReceivedRequestId(context: Context) {
            // Clear from RAM
            instance?.lastReceivedRequestId = 0
            
            // Clear from SharedPreferences
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit().remove("last_received_request_id").apply()
            
            if (BuildConfig.DEBUG) {
                android.util.Log.d("WebSocketService", "Cleared last_received_request_id (cold start detected)")
            }
        }
        
        /**
         * Set flag indicating we're reconnecting with last_received_event
         * When true, backend won't send init_complete - first sync_complete acts as init_complete
         */
        fun setReconnectingWithLastReceivedEvent(value: Boolean) {
            instance?.isReconnectingWithLastReceivedEvent = value
        }
        
        /**
         * Check if we're reconnecting with last_received_event
         */
        fun isReconnectingWithLastReceivedEvent(): Boolean {
            return instance?.isReconnectingWithLastReceivedEvent ?: false
        }

        /**
         * Start ping loop immediately after init_complete so small/no-traffic accounts don’t wait for the first sync_complete.
         */
        fun startPingLoopOnInitComplete() {
            val serviceInstance = instance ?: return
            // CRITICAL FIX: Only start ping loop if connection is in INITIALIZING or READY state
            // This prevents ping loop from starting if init_complete arrives after timeout
            if (!serviceInstance.connectionState.isInitializing() && !serviceInstance.connectionState.isReady()) {
                android.util.Log.w("WebSocketService", "Init_complete received but connection state is ${serviceInstance.connectionState} - not starting ping loop")
                return
            }
            
            // CRITICAL FIX: If pending count is 0, we're already ready — transition to READY now
            // This prevents the state from being stuck in Initializing when no sync_completes are pending
            // The ping guard requires isReady(), so we must transition to Ready for pings to be sent
            val currentState = serviceInstance.connectionState
            if (currentState is ConnectionState.Initializing && currentState.pendingSyncCount == 0) {
                android.util.Log.i("WebSocketService", "Init_complete received with no pending sync_completes - transitioning to READY immediately")
                updateConnectionState(ConnectionState.Ready)
                
                // Cancel any pending reconnection jobs now that we're Ready
                resetReconnectionState()
            }
            
            if (!serviceInstance.pingLoopStarted) {
                serviceInstance.pingLoopStarted = true
                serviceInstance.hasEverReachedReadyState = true
                android.util.Log.i("WebSocketService", "Init_complete received, starting ping loop")
                logActivity("WebSocket Ready", serviceInstance.currentNetworkType.name)
                startPingLoop()
            }
        }
        
        /**
         * Clear reconnection state (no-op - we no longer track last_received_id)
         */
        fun clearReconnectionState() {
            // No-op - we no longer track last_received_id
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearReconnectionState called (no-op - we no longer track last_received_id)")
        }
        
        /**
         * Reset reconnection state (called on successful connection)
         */
        fun resetReconnectionState() {
            val serviceInstance = instance ?: return
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            serviceInstance.reconnectionAttemptCount = 0 // Reset attempt count on successful connection
            // DO NOT reset connectionState here - it's set when init_complete arrives
            serviceInstance.initCompleteRetryCount = 0 // Reset retry count on successful connection
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Reset reconnection state (reconnection job cancelled, retry count reset)")
        }
        
        /**
         * Notify that run_id was received.
         * Connection health no longer depends on run_id - we mark good on websocket connect.
         * Kept for AppViewModel which still needs run_id for reconnection params.
         */
        fun onRunIdReceived() {
            instance?.runIdReceived = true
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Run ID received")
        }
        
        /**
         * Handle init_complete received.
         * Connection health no longer depends on init_complete - we mark good on websocket connect.
         * Still clears caches and notifies AppViewModel for application-level sync processing.
         */
        fun onInitCompleteReceived(source: String = "init_complete") {
            val serviceInstance = instance ?: return
            onMessageReceived() // Reset 60s message timeout
            val traceId = serviceInstance.currentReconnectTraceId
            
            // Do NOT clear caches here. Cache clearing is driven only by sync_complete with clear_state: true
            // (cold connect sends that; resume with last_received_event does not).

            val now = System.currentTimeMillis()
            val isDuplicateWithinWindow = serviceInstance.lastInitCompleteReceivedAt > 0L &&
                (now - serviceInstance.lastInitCompleteReceivedAt) < 2000L
            serviceInstance.lastInitCompleteReceivedAt = now

            if (isDuplicateWithinWindow) {
                logActivity(withReconnectTrace(traceId, "Init Complete Duplicate ($source)"), serviceInstance.currentNetworkType.name)
            } else {
                logActivity(withReconnectTrace(traceId, "Init Complete Received ($source)"), serviceInstance.currentNetworkType.name)
            }
            updateConnectionStatus(true, null, serviceInstance.lastSyncTimestamp)
            serviceInstance.clearInitCompleteFailureNotification()
            
            // AppViewModel receives init_complete via NetworkUtils and handles sync_complete queue
        }
        
        /**
         * Schedule WebSocket reconnection with backend health check
         * RUSH TO HEALTHY: Fast retry with backend health check, no exponential backoff
         */
        fun scheduleReconnection(trigger: ReconnectTrigger) {
            val serviceInstance = instance ?: return
            val reasonLabel = trigger.toLogString()
            val scheduleTime = System.currentTimeMillis()
            val reconnectTraceId = "rc-${scheduleTime.toString(36)}-${(serviceInstance.reconnectionAttemptCount + 1)}"
            serviceInstance.currentReconnectTraceId = reconnectTraceId
            
            // No network: state holds last event id so we know resume vs cold when link returns
            if (serviceInstance.currentNetworkType == NetworkType.NONE) {
                val lastEv = getLastReceivedRequestId(serviceInstance.applicationContext)
                updateConnectionState(ConnectionState.WaitingForNetwork(lastEv))
                synchronized(serviceInstance.reconnectionLock) {
                    serviceInstance.pendingReconnectionReasons.add(trigger)
                }
                android.util.Log.w("WebSocketService", "No network — state=WaitingForNetwork(lastEventId=$lastEv), queued trigger=$trigger")
                serviceInstance.showWebSocketToast("No network - waiting")
                logActivity("[$reconnectTraceId] Waiting for network ($reasonLabel)", serviceInstance.currentNetworkType.name)
                return
            }
            
            // ATOMIC GUARD: Use synchronized lock to prevent parallel reconnection attempts
            synchronized(serviceInstance.reconnectionLock) {
                val currentTime = System.currentTimeMillis()
                
                val currentState = serviceInstance.connectionState
                val lastReceivedId = when (currentState) {
                    is ConnectionState.QuickReconnecting -> currentState.lastEventId
                    is ConnectionState.WaitingForNetwork -> currentState.lastEventId
                    else -> getLastReceivedRequestId(serviceInstance.applicationContext)
                }
                
                // CRITICAL FIX: Check if reconnection is stuck and reset if needed
                if (serviceInstance.connectionState.isReconnectingPhase()) {
                    val timeSinceReconnect = if (serviceInstance.lastReconnectionTime > 0) {
                        currentTime - serviceInstance.lastReconnectionTime
                    } else {
                        0L
                    }
                    
                    // CRITICAL FIX: If reconnection is stuck for >30s, allow another attempt
                    // This handles cases where one app instance's reconnection gets stuck
                    // and another instance needs to take over
                    if (timeSinceReconnect > 30_000) {
                        // Reconnection stuck for >30s - reset and allow new reconnection
                        android.util.Log.w("WebSocketService", "Reconnection stuck for ${timeSinceReconnect}ms - resetting to allow new reconnection attempt: $reasonLabel")
                        serviceInstance.reconnectionJob?.cancel()
                        serviceInstance.reconnectionJob = null
                        // Fall through to start new reconnection
                    } else if (timeSinceReconnect > 10_000) {
                        // Reconnection in progress for >10s but <30s - allow if this is a network change
                        // Network changes should be able to interrupt a slow reconnection
                        if (trigger.interruptsSlowReconnection()) {
                            android.util.Log.w("WebSocketService", "Reconnection in progress for ${timeSinceReconnect}ms but network changed - interrupting: $reasonLabel")
                            serviceInstance.reconnectionJob?.cancel()
                            serviceInstance.reconnectionJob = null
                            // Fall through to start new reconnection
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting (${timeSinceReconnect}ms), dropping redundant request: $reasonLabel")
                            logActivity("[$reconnectTraceId] Reconnection skipped: already reconnecting (${timeSinceReconnect}ms)", serviceInstance.currentNetworkType.name)
                            return
                        }
                    } else {
                        // Reconnection just started (<10s) - don't interrupt
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting (${timeSinceReconnect}ms), dropping redundant request: $reasonLabel")
                        logActivity("[$reconnectTraceId] Reconnection skipped: reconnect in progress (${timeSinceReconnect}ms)", serviceInstance.currentNetworkType.name)
                        return
                    }
                }
                
                // Reset attempt count if last reconnection was long ago (successful connection)
                if (currentTime - serviceInstance.lastReconnectionTime > RECONNECTION_RESET_TIME_MS) {
                    serviceInstance.reconnectionAttemptCount = 0
                }
                
                // Check retry limit
                if (serviceInstance.reconnectionAttemptCount >= MAX_RECONNECTION_ATTEMPTS) {
                    android.util.Log.e("WebSocketService", "Reconnection attempt limit reached (${serviceInstance.reconnectionAttemptCount}) - stopping retries")
                    logActivity("Reconnection Limit Reached - Stopping", serviceInstance.currentNetworkType.name)
                    updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
                    return
                }
                
                // Check minimum interval between reconnections (prevent rapid-fire reconnections)
                if (currentTime - serviceInstance.lastReconnectionTime < MIN_RECONNECTION_INTERVAL_MS) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Too soon since last reconnection, ignoring: $reasonLabel")
                    logActivity("[$reconnectTraceId] Reconnection skipped: min interval guard ($reasonLabel)", serviceInstance.currentNetworkType.name)
                    return
                }
                
                // Cancel any existing reconnection job
                serviceInstance.reconnectionJob?.cancel()
                
                // Calculate exponential backoff (1s → 2s → 4s → 8s → 16s → 32s → 64s → 120s max)
                val backoffDelayMs = minOf(
                    1000L * (1 shl serviceInstance.reconnectionAttemptCount),
                    120_000L // Max 120 seconds
                )
                
                val rid = getCurrentRunId()
                val nextAttempt = serviceInstance.reconnectionAttemptCount + 1
                if (lastReceivedId != 0) {
                    updateConnectionState(ConnectionState.QuickReconnecting(rid, lastReceivedId, nextAttempt))
                } else {
                    updateConnectionState(ConnectionState.FullReconnecting)
                }
                serviceInstance.lastReconnectionTime = currentTime
                serviceInstance.reconnectionAttemptCount++
                
                android.util.Log.w("WebSocketService", "Scheduling reconnection: $reasonLabel ($trigger)")
                
                // PHASE 4.1: Log last close code if available (for debugging)
                val lastCloseCode = serviceInstance.lastCloseCode
                if (lastCloseCode != null) {
                    android.util.Log.i("WebSocketService", "Last WebSocket close code: $lastCloseCode (${serviceInstance.lastCloseReason})")
                }
                
                logActivity("[$reconnectTraceId] Connecting - $reasonLabel", serviceInstance.currentNetworkType.name)
                
                // Show toast for reconnection scheduled
                serviceInstance.showWebSocketToast("Reconnecting: $reasonLabel")
                
                serviceInstance.reconnectionJob = serviceInstance.serviceScope.launch {
                    val currentJob = coroutineContext[Job]
                    var invokedReconnectionCallback = false
                    try {
                        // Wait for exponential backoff (computed when this job was scheduled)
                        delay(backoffDelayMs)
                        
                        // FIX #1: If network is NONE, wait for network to return instead of exiting
                        // This allows the job to self-adapt when network is lost and regained
                        if (serviceInstance.currentNetworkType == NetworkType.NONE) {
                            android.util.Log.w("WebSocketService", "Reconnection job: Network is NONE - waiting for network to return")
                            serviceInstance.showWebSocketToast("No network - waiting")
                            
                            // Wait for network to return (poll every 1 second)
                            while (isActive && serviceInstance.currentNetworkType == NetworkType.NONE) {
                                delay(1000L)
                            }
                            
                            // Check if job was cancelled
                            if (!isActive) {
                                return@launch
                            }
                            
                            // Network returned - continue with reconnection
                            android.util.Log.i("WebSocketService", "Reconnection job: Network returned - continuing reconnection")
                        }
                        
                        // STATE A: First step - wait for NET_CAPABILITY_VALIDATED. Do not connect until validated.
                        logActivity("[$reconnectTraceId] Reconnection waiting for validated network", serviceInstance.currentNetworkType.name)
                        val networkValidated = serviceInstance.waitForNetworkValidation(NETWORK_VALIDATION_TIMEOUT_MS)
                        if (!networkValidated) {
                            if (serviceInstance.currentNetworkType == NetworkType.NONE) {
                                android.util.Log.w("WebSocketService", "Reconnection job: Network NONE - cancelling reconnection")
                                serviceInstance.showWebSocketToast("No network")
                                updateConnectionState(ConnectionState.Disconnected)
                                return@launch
                            }
                            val disconnectedMs = if (serviceInstance.connectionLostAt > 0) System.currentTimeMillis() - serviceInstance.connectionLostAt else 0L
                            if (serviceInstance.connectionLostAt > 0 && disconnectedMs > 60_000L) {
                                android.util.Log.i("WebSocketService", "Reconnection job: Validation timeout but disconnected >1 min (${disconnectedMs}ms) - connecting cold")
                                logActivity("[$reconnectTraceId] Validation Timeout - Connecting Cold (>1 min offline)", serviceInstance.currentNetworkType.name)
                                serviceInstance.showWebSocketToast("Connecting without resume (offline >1 min)")
                                invokedReconnectionCallback = true
                                invokeReconnectionCallback(trigger, lastReceivedId = 0, forceColdConnect = true)
                                return@launch
                            }
                            android.util.Log.w("WebSocketService", "Reconnection job: Network not validated within ${NETWORK_VALIDATION_TIMEOUT_MS}ms - aborting (will retry on next trigger)")
                            logActivity("[$reconnectTraceId] Network Not Validated - Will Retry", serviceInstance.currentNetworkType.name)
                            serviceInstance.showWebSocketToast("Network not validated - retrying")
                            // Prevent leaving the service in a stale reconnect state.
                            updateConnectionState(ConnectionState.Disconnected)
                            updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
                            // Autonomous retry: slow/captive networks may validate later with no new trigger.
                            serviceInstance.serviceScope.launch {
                                delay(5000L)
                                val stillDisconnected = !isWebSocketConnected()
                                if (stillDisconnected && !isReconnectingOrConnecting()) {
                                    logActivity("[$reconnectTraceId] Reconnection Retry: post-validation-timeout backoff", serviceInstance.currentNetworkType.name)
                                    scheduleReconnection(ReconnectTrigger.ValidationTimeoutRetry)
                                }
                            }
                            return@launch
                        }
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Reconnection job: Network validated - proceeding")
                        
                        // Skip backend HTTP health check - redundant; WebSocket will fail fast if unreachable
                        // If backend is unreachable, WebSocket connection will fail fast
                        
                        // Network validated - proceed with reconnection
                        // BUT: If, in the meantime, another path has already established a
                        // healthy READY connection with a live WebSocket, this job is now
                        // stale and must not touch the state machine at all.
                        val currentStateNow = serviceInstance.connectionState
                        if (currentStateNow.isReady() && serviceInstance.webSocket != null) {
                            android.util.Log.i(
                                "WebSocketService",
                                "Reconnection job: connection already READY with live WebSocket - skipping reconnection (trigger=$trigger)"
                            )
                            return@launch
                        }
                        
                        // Get last_received_id from current state (may have been updated in retry loop)
                        val finalState = currentStateNow
                        val lastReceivedId = when (finalState) {
                            is ConnectionState.QuickReconnecting -> finalState.lastEventId
                            is ConnectionState.WaitingForNetwork -> finalState.lastEventId
                            else -> getLastReceivedRequestId(serviceInstance.applicationContext)
                        }
                        
                        if (isActive) {
                            // FINAL CHECK: Right before invoking callback, verify we're still not Ready
                            // This prevents race conditions where state changes between the earlier check and now
                            val stateBeforeCallback = serviceInstance.connectionState
                            if (stateBeforeCallback.isReady() && serviceInstance.webSocket != null) {
                                android.util.Log.w(
                                    "WebSocketService",
                                    "Reconnection job: connection became READY before callback - aborting reconnection (trigger=$trigger)"
                                )
                                return@launch
                            }
                            
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Executing reconnection: $reasonLabel (attempt ${serviceInstance.reconnectionAttemptCount}/$MAX_RECONNECTION_ATTEMPTS, last_received_id: $lastReceivedId)")
                            logActivity("[$reconnectTraceId] Reconnection Attempt - $reasonLabel", serviceInstance.currentNetworkType.name)
                            
                            // FIX #1: Don't transition to Connecting here - let setWebSocket() do it when connection actually starts
                            // This prevents premature state transition that causes race conditions with concurrent triggers
                            
                            // PHASE 1.4: Use safe invocation helper with error handling
                            // Pass last_received_id to reconnection callback
                            invokedReconnectionCallback = true
                            invokeReconnectionCallback(trigger, lastReceivedId)
                        } else {
                            // Job was cancelled (e.g., because a separate successful connection
                            // path called resetReconnectionState()). In that case, do NOT
                            // overwrite whatever the current connection state is (it might
                            // already be READY). Just exit quietly.
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "WebSocketService",
                                    "Reconnection job cancelled before execution, skipping state change (trigger=$trigger, currentState=$currentStateNow)"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WebSocketService", "Reconnection job failed", e)
                        // Update notification to show error
                        updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
                    } finally {
                        synchronized(serviceInstance.reconnectionLock) {
                            // If this finished job is still the active reconnectionJob, clear it.
                            if (serviceInstance.reconnectionJob === currentJob) {
                                serviceInstance.reconnectionJob = null
                            }
                            
                            // If we never actually started a real connection attempt,
                            // transition back to DISCONNECTED so health checks can recover.
                            if (serviceInstance.connectionState.isReconnectingPhase() &&
                                serviceInstance.webSocket == null &&
                                !invokedReconnectionCallback
                            ) {
                                logActivity(
                                    "[$reconnectTraceId] Reconnection Recovery: forced DISCONNECTED (callback not invoked)",
                                    serviceInstance.currentNetworkType.name
                                )
                                updateConnectionState(ConnectionState.Disconnected)
                                updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
                            }
                        }
                    }
                }
            }
        }
        
        /**
         * Restart WebSocket connection
         */
        fun restartWebSocket(trigger: ReconnectTrigger = ReconnectTrigger.Unclassified("Unknown reason")) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Restarting WebSocket connection - trigger: $trigger")
            
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
            
            // Only clear WebSocket if not a plain "network back" path that must preserve local resume state
            if (trigger !is ReconnectTrigger.NetworkAvailable) {
                clearWebSocket(trigger.toLogString())
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network restored - skipping clearWebSocket to preserve state")
            }
            
            // Add a small delay to ensure WebSocket is properly closed
            serviceInstance.serviceScope.launch {
                delay(1000) // 1 second delay to ensure proper closure
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Triggering reconnection after delay")
                
                // FCM wake: no ViewModel — do not recurse through the reconnect callback
                if (trigger is ReconnectTrigger.FcmPush) {
                    android.util.Log.w("WebSocketService", "FCM push trigger — cannot connect WebSocket without AppViewModel")
                    android.util.Log.w("WebSocketService", "WebSocket will remain in 'Connecting...' state until app is opened manually")
                    return@launch
                }
                
                // Service was already restarted upstream; avoid callback loop
                if (trigger is ReconnectTrigger.ServiceRestarted) {
                    android.util.Log.w("WebSocketService", "restartWebSocket(ServiceRestarted) — would loop; AppViewModel should connect directly")
                    return@launch
                }
                
                invokeReconnectionCallback(trigger)
            }
        }
        
        /**
         * Cancel any pending reconnection
         */
        fun cancelReconnection() {
            val serviceInstance = instance ?: return
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            if (serviceInstance.connectionState.isReconnectingPhase()) {
                updateConnectionState(ConnectionState.Disconnected)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Cancelled pending reconnection")
        }
        
    }
    
    // Toast debouncing to prevent too many toasts
    private var lastToastTime: Long = 0
    private var lastToastMessage: String? = null
    private val TOAST_DEBOUNCE_MS = 1000L // Minimum 1 second between toasts
    
    /**
     * Show a Toast notification for WebSocket actions (for debugging stuck connections)
     * Debounced to prevent too many toasts (Android has limits)
     * Only shows in debug builds to avoid UX disruption in production
     */
    private fun showWebSocketToast(message: String) {
        // Only show toasts in debug builds
        if (!BuildConfig.DEBUG) {
            return
        }
        
        try {
            val now = System.currentTimeMillis()
            // Debounce: Only show if message changed or enough time passed
            if (message == lastToastMessage && now - lastToastTime < TOAST_DEBOUNCE_MS) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Toast debounced: $message")
                return
            }
            
            lastToastTime = now
            lastToastMessage = message
            
            android.widget.Toast.makeText(
                applicationContext,
                "WS: $message",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            // If toast fails (e.g., no UI context), just log it
            android.util.Log.d("WebSocketService", "Toast: $message")
        }
    }
    
    // Instance variables for WebSocket state management
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var initCompleteTimeoutJob: Job? = null // Timeout waiting for init_complete
    private var runIdTimeoutJob: Job? = null // Timeout waiting for run_id
    private var hardConnectingTimeoutJob: Job? = null // Hard timeout for total Connecting state duration
    private var runIdReceived: Boolean = false // Track if run_id was received
    private var lastInitCompleteReceivedAt: Long = 0L // Deduplicate near-simultaneous init_complete activity logs
    private var currentReconnectTraceId: String? = null // Correlates one reconnection flow across scheduler/callback/connect/open/init logs
    private var runIdReceivedTime: Long = 0 // Timestamp when run_id was received
    private var messagesReceivedWhileWaitingForInitComplete: Int = 0 // Track messages received to extend timeout
    private var initCompleteTimeoutEndTime: Long = 0 // Track when init_complete timeout will expire (for dynamic extension)
    private var lastPingRequestId: Int = 0
    private var lastPingTimestamp: Long = 0
    private var pingInFlight: Boolean = false // Guard to prevent concurrent pings
    private var isAppVisible = false
    private var isScreenOn = true
    
    // Receiver for screen state changes
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Screen turned ON")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Screen turned OFF")
                }
            }
        }
    }
    private var initCompleteRetryCount: Int = 0 // Track retry count for exponential backoff
    private var isReconnectingWithLastReceivedEvent: Boolean = false // Protocol hint from URL builder (resume); orthogonal to [ConnectionState]
    
    // Notification state cache for idempotent updates
    private var lastNotificationText: String? = null
    private var lastNotificationUpdateTime: Long = 0
    private var lastConnectionStateForNotification: String? = null // Track state changes for release builds (format: "STATE-callbackMissing")
    
    // RUSH TO HEALTHY: Removed network optimization variables - ping/pong is the authority
    
    // BATTERY OPTIMIZATION: Combined monitoring jobs into single unified job
    // Previously had 3 separate jobs checking every 30 seconds (state corruption, primary health, connection health)
    // This caused CPU to wake every ~10 seconds on average (3 jobs * 30s interval = 10s average)
    // Combined into single job that checks all 3 things at once, reducing wake-ups by 66%
    private var unifiedMonitoringJob: Job? = null // Unified monitoring for state corruption, primary health, and connection health
    private var currentNetworkType: NetworkType = NetworkType.NONE
    
    // PHASE 3.2: Network type change detection
    private var lastNetworkType: NetworkType = NetworkType.NONE // Track previous network type
    private var lastNetworkIdentity: String? = null // Track previous network identity (SSID for WiFi, null for others)
    private var networkChangeDebounceJob: Job? = null // Debounce rapid network changes
    
    // PHASE 4.1: WebSocket close code tracking
    private var lastCloseCode: Int? = null // Track last WebSocket close code
    private var lastCloseReason: String? = null // Track last WebSocket close reason
    
    // WebSocket connection management
    // @Volatile ensures cross-thread visibility: OkHttp callbacks write these on their own threads;
    // the health check coroutine reads them on the service dispatcher. Without @Volatile the JVM is
    // free to serve stale cached values, causing the health check to see Connecting long after
    // setWebSocket() has already transitioned to Ready.
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connectionState: ConnectionState = ConnectionState.Disconnected
    private var lastPongTimestamp = 0L // Track last pong for heartbeat monitoring
    private var lastMessageReceivedTimestamp = 0L // Reset on ANY message - 60s without = reconnect
    @Volatile private var connectionStartTime: Long = 0 // Track when WebSocket connection was established (0 = not connected)
    /** When we last lost the connection; used to decide cold connect after validation timeout if disconnected > 1 min */
    @Volatile private var connectionLostAt: Long = 0
    /**
     * True once we have had at least one successful WebSocket connection this process lifetime.
     * Used by the health-check to skip auto-reconnect on cold starts (FCM / START_STICKY restart)
     * when no ViewModel is attached yet — in that case we want the UI to drive the first connect
     * (via AppViewModel.initializeWebSocketConnection) so that sync_complete messages are
     * processed by a live ViewModel rather than buffered and replayed through the broken
     * no-VM attach path.
     */
    @Volatile private var hadSuccessfulConnectionThisProcess = false
    // Reconnection state management
    // run_id is always read from SharedPreferences - not stored in service state
    // NOTE: We no longer track last_received_id - all timeline caches are cleared on connect/reconnect
    
    // Connection health tracking
    private var lastSyncTimestamp: Long = 0
    private var lastKnownLagMs: Long? = null
    private var consecutivePingTimeouts: Int = 0 // Track consecutive ping timeouts for network quality detection
    
    // Reconnection logic state (simplified - no exponential backoff)
    private var reconnectionJob: Job? = null
    
    private var lastReconnectionTime = 0L
    private var reconnectionAttemptCount = 0 // Track reconnection attempts for retry limit
    
    // Atomic lock for preventing parallel reconnection attempts (compare-and-set)
    private val reconnectionLock = Any()
    
    // PHASE 2.1: Pending reconnection queue for when callback is not yet available
    private val pendingReconnectionReasons = mutableListOf<ReconnectTrigger>()
    private val pendingReconnectionLock = Any() // Thread safety for pending queue
    
    // Ping loop state
    private var pingLoopStarted = false // Track if ping loop has been started after first sync_complete
    private var hasEverReachedReadyState = false // Track if we have ever received sync_complete on this run
    
    // Last received request_id from sync_complete (stored in RAM for faster reconnections)
    private var lastReceivedRequestId: Int = 0
    
    // Fallback network validation state (exponential backoff)
    private var fallbackBackoffDelayMs = 1000L // Start with 1 second
    private var lastNetworkTypeForBackoff: NetworkType? = null // Track network type to reset backoff on change
    
    // PHASE 2.2: Service restart detection
    private var serviceStartTime: Long = 0 // Track when service started (0 = not started yet)
    private var wasRestarted: Boolean = false // Track if service was restarted (instance was null before onCreate)
    
    // PHASE 2.3: Callback health monitoring
    private var lastCallbackCheckTime: Long = 0 // Track when callbacks were last verified
    
    // PHASE 3.1: Network monitoring
    private var networkMonitor: NetworkMonitor? = null
    
    // BATTERY OPTIMIZATION: Wake locks removed - foreground service already keeps process alive
    // Wake locks were causing significant battery drain by keeping CPU awake continuously
    // The foreground service notification is sufficient to prevent Android from killing the process
    // Only heartbeatWakeLock remains for short-duration operations (heartbeat alarm processing)
    private var heartbeatWakeLock: PowerManager.WakeLock? = null
    
    // RUSH TO HEALTHY: Fixed ping interval - no adaptive logic needed
    // Ping/pong failures are handled by immediate retry and dropping after 3 failures
    
    // checkBackendHealth removed - HTTP health check is redundant since backend serves both HTTP and WebSocket
    // If backend is unreachable, WebSocket connection will fail fast
    
    /**
     * BATTERY OPTIMIZATION: Unified monitoring job
     * 
     * Previously had 3 separate monitoring jobs:
     * 1. State corruption monitoring (every 30s)
     * 2. Primary ViewModel health monitoring (every 30s)
     * 3. Connection health check (every 30s)
     * 
     * This caused CPU to wake every ~10 seconds on average (3 jobs * 30s interval = 10s average wake-up)
     * 
     * Combined into single unified job that checks all 3 things at once:
     * - Reduces wake-ups by 66% (from 3 jobs to 1 job)
     * - All checks happen in same wake-up cycle
     * - Same monitoring coverage, better battery efficiency
     * 
     * What it monitors:
     * 1. Callback health (validateCallbacks each tick; internal cadence varies)
     * 2. State corruption (~every 30s) - detects and recovers from state inconsistencies
     * 3. Primary ViewModel health (~every 30s, same tick as state corruption) - safety net; SyncRepository owns attach/prune
     * 4. Connection health (every 1s) - detects stuck CONNECTING/RECONNECTING states
     * 5. Notification staleness (each tick) - ensures notification is updated when needed
     */
    private fun startUnifiedMonitoring() {
        unifiedMonitoringJob?.cancel()
        unifiedMonitoringJob = serviceScope.launch {
            var stateCorruptionCheckCounter = 0
            while (isActive) {
                // RUN FREQUENCY:
                // Use a 1-second tick so we can enforce strict upper bounds on transient states
                // like CONNECTING without waiting 30s for the next health check.
                delay(1_000) // Check every 1 second
                
                try {
                    // 1. Callback health check (every 30s)
                    // PHASE 2.3: Validate that all primary callbacks are set
                    // Ensures reconnection callbacks are available for reconnection attempts
                    validateCallbacks()
                    
                    // 2 + 3. State corruption + primary ViewModel health (~every 30s — same counter as documented)
                    // Primary promotion is a safety net; SyncRepository flows own attachment/pruning. This must not
                    // run every 1s (it did before): it spams logs and fights registerReceiveCallback/attach ordering.
                    stateCorruptionCheckCounter++
                    if (stateCorruptionCheckCounter >= 30) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Unified monitoring: state corruption + primary health checks")
                        WebSocketService.detectAndRecoverStateCorruption()
                        
                        val isAlive = isPrimaryAlive()
                        val primaryId = getPrimaryViewModelId()
                        if (!isAlive) {
                            android.util.Log.w("WebSocketService", "STEP 3.2 - Primary health check: Primary ViewModel is NOT alive (primaryId=$primaryId)")
                            val registeredCount = getRegisteredViewModelIds().size
                            val callbackStatus = getPrimaryCallbackStatus()
                            android.util.Log.w("WebSocketService", "STEP 3.2 - Health check details: registeredViewModels=$registeredCount, callbackStatus=$callbackStatus")
                            if (primaryId != null) {
                                android.util.Log.i("WebSocketService", "STEP 3.3 - Primary ViewModel $primaryId detected as dead - attempting automatic promotion")
                                SyncRepository.clearStalePrimaryAndPromote(primaryId, "health_check_failed")
                            } else {
                                if (registeredCount > 0) {
                                    android.util.Log.i("WebSocketService", "STEP 3.3 - No primary set but $registeredCount ViewModels available - attempting promotion")
                                    SyncRepository.promoteNextPrimary("no_primary_set")
                                } else {
                                    android.util.Log.w("WebSocketService", "STEP 3.3 - No primary and no ViewModels available - next MainActivity launch will become primary")
                                }
                            }
                        }
                        stateCorruptionCheckCounter = 0
                    }
                    
                    // 4. Connection health check (every 1s)
                    // Detects if connection is stuck in CONNECTING or RECONNECTING state and forces recovery
                    val currentState = connectionState
                    val currentTime = System.currentTimeMillis()
                    
                    // CRITICAL FIX #3: State validation - check if Connecting for >threshold with no active timeout jobs
                    // This detects cases where timeout jobs failed to schedule or were cancelled
                    // Use adaptive threshold based on network type (longer for slow networks)
                    // CRITICAL FIX #3: State validation - check if Connecting for >3s with no active timeout jobs
                    // This detects cases where timeout jobs failed to schedule or were cancelled
                    val isStuckConnectingNoTimeouts = if (currentState.isConnecting()) {
                        val timeSinceConnect = if (connectionStartTime > 0) currentTime - connectionStartTime else 0
                        val hasActiveRunIdTimeout = runIdTimeoutJob?.isActive == true
                        val hasActiveInitCompleteTimeout = initCompleteTimeoutJob?.isActive == true
                        val hasActiveHardTimeout = hardConnectingTimeoutJob?.isActive == true
                        val hasAnyActiveTimeout = hasActiveRunIdTimeout || hasActiveInitCompleteTimeout || hasActiveHardTimeout
                        
                        // If we've been Connecting for >3s and NO timeout jobs are active, something is wrong
                        timeSinceConnect > 3_000 && !hasAnyActiveTimeout
                    } else {
                        false
                    }
                    
                    // FIX #3: Add a strict timeout check for stuck CONNECTING state.
                    // In a healthy backend, time from WebSocket open → run_id is in milliseconds.
                    // We never want to present "Connecting..." for more than a few seconds.
                    val isStuckConnectingSimple = if (currentState.isConnecting()) {
                        val timeSinceConnect = if (connectionStartTime > 0) currentTime - connectionStartTime else 0
                        // HARD LIMIT: If we've been in CONNECTING for >3s since onOpen, something is wrong.
                        // Either run_id/init_complete never arrived or our timeouts failed.
                        timeSinceConnect > 3_000 // Stuck for >3 seconds
                    } else {
                        false
                    }
                    
                    val isStuckConnecting = when {
                        currentState.isDialOrSyncing() -> {
                            val timeoutActive = initCompleteTimeoutJob?.isActive == true
                            val timeSinceConnect = if (connectionStartTime > 0) currentTime - connectionStartTime else 0
                            (timeoutActive && timeSinceConnect > INIT_COMPLETE_TIMEOUT_MS_BASE + 5000) ||
                                (!timeoutActive && timeSinceConnect > INIT_COMPLETE_TIMEOUT_MS_BASE + 10000)
                        }
                        else -> false
                    }
                    
                    val isStuckReconnecting = when {
                        currentState.isReconnectingPhase() -> {
                            val timeSinceReconnect = if (lastReconnectionTime > 0) currentTime - lastReconnectionTime else 0
                            timeSinceReconnect > 60_000
                        }
                        else -> false
                    }
                    
                    if (isStuckConnecting || isStuckConnectingSimple || isStuckConnectingNoTimeouts || isStuckReconnecting) {
                        val stuckReason = when {
                            isStuckConnectingNoTimeouts -> "Stuck in CONNECTING for >3s with no active timeout jobs (timeout jobs failed)"
                            isStuckConnectingSimple -> "Stuck in CONNECTING for >3s (no active connection)"
                            isStuckConnecting -> "Stuck in CONNECTING waiting for init_complete"
                            isStuckReconnecting -> "Stuck in RECONNECTING"
                            else -> "Unknown stuck state"
                        }
                        android.util.Log.w("WebSocketService", "Unified monitoring: Detected stuck state ($currentState) - $stuckReason - forcing recovery")
                        logActivity("Health Check - Stuck State Detected ($currentState)", currentNetworkType.name)
                        
                        // Force recovery
                        clearWebSocket("Health check: Stuck state detected ($currentState) - $stuckReason")
                        
                        // Reset reconnection attempt count if stuck
                        if (isStuckReconnecting || isStuckConnectingSimple || isStuckConnecting) {
                            synchronized(reconnectionLock) {
                                reconnectionJob?.cancel()
                                reconnectionJob = null
                            }
                        }
                        
                        // Schedule new reconnection
                        scheduleReconnection(ReconnectTrigger.HealthCheckRecovery(stuckReason))
                    }

                    // 4b. Stuck-DISCONNECTED recovery: if we are Disconnected with no active
                    // reconnection job, network available, and credentials present, something went
                    // wrong (e.g. a stale "forced DISCONNECTED" from a reconnection finally-block,
                    // or a START_STICKY restart where we never had a connection this process lifetime).
                    // Wait >5s after the last loss to avoid tight retry loops, but also handle the
                    // cold-start case (connectionLostAt == 0) after a short startup grace period.
                    val noReconnectJob = synchronized(reconnectionLock) { reconnectionJob?.isActive != true }
                    val sinceLastLoss = if (connectionLostAt > 0) currentTime - connectionLostAt else Long.MAX_VALUE
                    val sinceStart = if (serviceStartTime > 0) currentTime - serviceStartTime else Long.MAX_VALUE
                    val disconnectedLongEnough = sinceLastLoss > 5_000 || sinceStart > 5_000
                    // On a cold process start (FCM wake / START_STICKY restart) with no ViewModel yet,
                    // skip auto-reconnect. The UI will drive the first connection via
                    // AppViewModel.initializeWebSocketConnection() so that sync_complete messages are
                    // processed by a live ViewModel rather than buffered for the broken no-VM attach path.
                    // Once we have had one successful connection this process, resume normal behaviour.
                    val hasVmOrPriorConnection = hadSuccessfulConnectionThisProcess ||
                        SyncRepository.getAttachedViewModels().isNotEmpty()
                    if (currentState.isDisconnected() &&
                        noReconnectJob &&
                        currentNetworkType != NetworkType.NONE &&
                        hasReconnectionSignal() &&
                        disconnectedLongEnough &&
                        hasVmOrPriorConnection
                    ) {
                        android.util.Log.w("WebSocketService", "Health check: Stuck in DISCONNECTED with no active reconnection — scheduling recovery")
                        logActivity("Health Check - Stuck DISCONNECTED (no reconnection job)", currentNetworkType.name)
                        scheduleReconnection(ReconnectTrigger.HealthCheckRecovery("Stuck in DISCONNECTED with no active reconnection job"))
                    } else if (currentState.isDisconnected() && !hasVmOrPriorConnection && disconnectedLongEnough) {
                        // Rate-limit to once per 30 ticks (~30s) to avoid logcat spam.
                        if (BuildConfig.DEBUG && stateCorruptionCheckCounter == 0) {
                            android.util.Log.d("WebSocketService", "Health check: Skipping auto-reconnect (cold start, no VM attached yet — waiting for UI to drive first connection)")
                        }
                    }

                    // 5. Notification staleness check (every 30s)
                    // Ensures notification is updated for non-READY states
                    val timeSinceNotificationUpdate = currentTime - lastNotificationUpdateTime
                    if (timeSinceNotificationUpdate > 60_000 && !currentState.isReady()) {
                        android.util.Log.w("WebSocketService", "Unified monitoring: Notification stale (${timeSinceNotificationUpdate}ms old) - forcing update")
                        updateConnectionStatus(
                            isConnected = currentState.isReady(),
                            lagMs = lastKnownLagMs,
                            lastSyncTimestamp = lastSyncTimestamp
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in unified monitoring", e)
                }
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Unified monitoring started (checks every 30 seconds)")
    }
    
    /**
     * Stop unified monitoring
     */
    private fun stopUnifiedMonitoring() {
        unifiedMonitoringJob?.cancel()
        unifiedMonitoringJob = null
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Unified monitoring stopped")
    }
    
    /**
     * PHASE 2.3: Validate that all primary callbacks are set
     * Checks if callbacks are available and logs warnings if missing
     * Updates notification if callback is missing and connection is not CONNECTED
     */
    private fun validateCallbacks() {
        lastCallbackCheckTime = System.currentTimeMillis()
        
        val hasSignal = try { hasReconnectionSignal() } catch (_: Exception) { false }
        val isConnected = connectionState.isReady()
        
        if (!isConnected && !hasSignal) {
            android.util.Log.w("WebSocketService", "Callback health check: no credentials / reconnection signal and connection is not READY")
            logActivity("Callback Missing - Waiting for AppViewModel", currentNetworkType.name)
            
            // Update notification to show "Waiting for app..."
            updateConnectionStatus(
                isConnected = false,
                lagMs = lastKnownLagMs,
                lastSyncTimestamp = lastSyncTimestamp
            )
        }
        
        val pid = SyncRepository.getPrimaryViewModelId()
        if (pid != null && SyncRepository.getAttachedViewModels().isEmpty()) {
            android.util.Log.w("WebSocketService", "Primary id $pid set but no ViewModels attached in SyncRepository")
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
                // CRITICAL FIX: Debounce rapid network changes to avoid excessive reconnections
                networkChangeDebounceJob?.cancel()
                networkChangeDebounceJob = serviceScope.launch {
                    delay(NETWORK_CHANGE_DEBOUNCE_MS)
                    
                    android.util.Log.i("WebSocketService", "Network available: $networkType - checking if reconnection needed")
                    val newNetworkType = convertNetworkType(networkType)
                    val previousNetworkType = lastNetworkType
                    val hasCallback = hasReconnectionSignal()
                    
                    // Show toast for network available
                    showWebSocketToast("Network available: $networkType")
                    
                    // CRITICAL FIX: Evict connection pool when network becomes available
                    // This ensures we don't use stale DNS/connections from previous network
                    WebSocketService.evictHealthCheckConnections()
                    
                    // PHASE 3.2: Update network type tracking
                    // Note: Network identity (SSID) is updated in onNetworkIdentityChanged callback
                    lastNetworkType = newNetworkType
                    currentNetworkType = newNetworkType
                    
                    // CRITICAL FIX: Wait for network validation BEFORE attempting reconnection
                    // This prevents DNS resolution failures when network isn't fully ready
                    android.util.Log.i("WebSocketService", "Network available: $networkType - waiting for validation before reconnecting")
                    
                    // STATE A: Wait for NET_CAPABILITY_VALIDATED before any reconnection
                    val networkValidated = waitForNetworkValidation(WebSocketService.NETWORK_VALIDATION_TIMEOUT_MS)
                    if (!networkValidated) {
                        // Do not connect without NET_CAPABILITY_VALIDATED (avoids DNS failures / stuck Connecting)
                        android.util.Log.w("WebSocketService", "Network available but not validated within ${WebSocketService.NETWORK_VALIDATION_TIMEOUT_MS}ms - not reconnecting (will retry on next event)")
                        logActivity("Network Available - Waiting for Validation", currentNetworkType.name)
                        showWebSocketToast("Network not validated - waiting")
                        return@launch
                    }
                    
                    // Android validation succeeded - reset fallback backoff
                    fallbackBackoffDelayMs = 1000L
                    lastNetworkTypeForBackoff = null
                    
                    android.util.Log.i("WebSocketService", "Network validated - checking if reconnection needed")
                    
                    // USER REQUIREMENT: Implement network change logic
                    // WiFi AP Alpha -> WiFi AP Alpha: Do nothing (except if pings fail, then retrigger reconnection)
                    // WiFi AP Alpha -> WiFi AP Beta: Force reconnect (handled by onNetworkIdentityChanged)
                    // WiFi -> 5G: Force reconnect (handled by onNetworkTypeChanged)
                    // 5G -> WiFi: Force reconnect (handled by onNetworkTypeChanged)
                    // Any network type -> Another network type: Force reconnect (handled by onNetworkTypeChanged)
                    //
                    // onNetworkAvailable is called for all network changes, but:
                    // - onNetworkTypeChanged handles type changes (WiFi <-> 5G, etc.)
                    // - onNetworkIdentityChanged handles identity changes (WiFi AP Alpha -> WiFi AP Beta)
                    // - onNetworkAvailable should only reconnect if:
                    //   1. We're disconnected (offline → online)
                    //   2. Same network type and identity (natural recovery, but we're already connected)
                    //
                    // For same network (same type and identity), do nothing - let ping/pong handle failures
                    if (connectionState.isDisconnected()) {
                        // CRITICAL FIX: Check if connectWebSocket() is already running before scheduling reconnection
                        // This prevents NetworkMonitor from triggering duplicate reconnections during startup
                        val alreadyConnecting = connectionState is ConnectionState.Connecting
                        if (alreadyConnecting) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("WebSocketService", "Network validated but connection already in progress - skipping duplicate reconnection")
                            }
                            return@launch
                        }
                        
                        // Not connected - reconnect (network is now validated)
                        android.util.Log.w("WebSocketService", "Network validated but WebSocket disconnected - triggering reconnection")
                        logActivity("Network Validated - Reconnecting", currentNetworkType.name)
                        
                        // REFACTORING: Service handles reconnection directly
                        scheduleReconnection(ReconnectTrigger.NetworkValidated(newNetworkType))
                    } else if (connectionState.isConnecting()) {
                        // CRITICAL FIX: Use same logic as unified monitoring to detect stuck state
                        // Check if timeout jobs are missing or if we've been stuck too long
                        val timeSinceConnect = if (connectionStartTime > 0) {
                            System.currentTimeMillis() - connectionStartTime
                        } else {
                            0L
                        }
                        
                        val hasActiveRunIdTimeout = runIdTimeoutJob?.isActive == true
                        val hasActiveInitCompleteTimeout = initCompleteTimeoutJob?.isActive == true
                        val hasActiveHardTimeout = hardConnectingTimeoutJob?.isActive == true
                        val hasAnyActiveTimeout = hasActiveRunIdTimeout || hasActiveInitCompleteTimeout || hasActiveHardTimeout
                        
                        // If stuck for >3s with no timeout jobs, or >5s total, force recovery
                        val isStuckNoTimeouts = timeSinceConnect > 3_000 && !hasAnyActiveTimeout
                        val isStuckTooLong = timeSinceConnect > 5_000
                        
                        if (isStuckNoTimeouts || isStuckTooLong) {
                            val reason = when {
                                isStuckNoTimeouts -> "Network change: Connection stuck in CONNECTING for ${timeSinceConnect}ms with no active timeout jobs"
                                isStuckTooLong -> "Network change: Connection stuck in CONNECTING for ${timeSinceConnect}ms (>5s)"
                                else -> "Network change: Connection stuck in CONNECTING"
                            }
                            android.util.Log.w("WebSocketService", reason)
                            clearWebSocket(reason)
                            scheduleReconnection(ReconnectTrigger.StuckConnectingRecovery)
                        } else {
                            // Already connecting and not stuck - wait for init_complete
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("WebSocketService", "Network validated but WebSocket already connecting (${timeSinceConnect}ms, timeouts active: $hasAnyActiveTimeout) - waiting for init_complete")
                            }
                        }
                    } else if (connectionState.isReconnectingPhase()) {
                        // FIX #2: Check if there's an active reconnection job
                        // If not, we're in a dead reconnection state and need to restart
                        val hasActiveJob = synchronized(reconnectionLock) {
                            reconnectionJob?.isActive == true
                        }
                        
                        if (!hasActiveJob) {
                            // Dead reconnection state - we already validated above, reconnect immediately (no backoff, no second validation)
                            android.util.Log.w("WebSocketService", "Network available while in RECONNECTING state with no active job - reconnecting immediately")
                            logActivity("Network Available - Restarting Reconnection", currentNetworkType.name)
                            synchronized(reconnectionLock) {
                                reconnectionJob = null
                            }
                            val currentState = connectionState
                            val lastReceivedId = when (currentState) {
                                is ConnectionState.QuickReconnecting -> currentState.lastEventId
                                is ConnectionState.WaitingForNetwork -> currentState.lastEventId
                                else -> getLastReceivedRequestId(applicationContext)
                            }
                            updateConnectionState(ConnectionState.Disconnected)
                            invokeReconnectionCallback(ReconnectTrigger.NetworkValidated(newNetworkType), lastReceivedId)
                            return@launch
                        }
                        
                        // CRITICAL FIX: If we're in RECONNECTING state with active job, check backend health BEFORE scheduling reconnection
                        // This prevents scheduling a reconnection that will fail immediately
                        // Network is already validated (we're past the validation check above)
                        android.util.Log.w("WebSocketService", "Network validated while in RECONNECTING state - checking backend health before reconnecting")
                        logActivity("Network Validated - Reconnecting", currentNetworkType.name)
                        
                        // Skip backend HTTP health check - it's redundant
                        android.util.Log.i("WebSocketService", "Network validated - reconnecting immediately")
                        
                        // Cancel existing reconnection job since network is validated
                        synchronized(reconnectionLock) {
                            reconnectionJob?.cancel()
                            reconnectionJob = null
                        }
                        
                        val currentState = connectionState
                        val lastReceivedId = when (currentState) {
                            is ConnectionState.QuickReconnecting -> currentState.lastEventId
                            is ConnectionState.WaitingForNetwork -> currentState.lastEventId
                            else -> getLastReceivedRequestId(applicationContext)
                        }
                        
                        // FIX #1: Don't transition to Connecting here - let setWebSocket() do it when connection actually starts
                        // Start reconnection immediately (no backoff)
                        invokeReconnectionCallback(ReconnectTrigger.NetworkValidated(newNetworkType), lastReceivedId)
                    } else if (previousNetworkType != newNetworkType && previousNetworkType != NetworkType.NONE) {
                        // Network type changed - USER REQUIREMENT: Always force reconnect
                        android.util.Log.w("WebSocketService", "Network type changed while connected - forcing reconnection ($previousNetworkType → $newNetworkType)")
                        clearWebSocket("Network type changed: $previousNetworkType → $newNetworkType")
                        scheduleReconnection(ReconnectTrigger.NetworkTypeChanged(previousNetworkType, newNetworkType))
                    } else {
                        // Same network type and we're connected - USER REQUIREMENT: Do nothing (let ping/pong handle failures)
                        // This handles: WiFi AP Alpha -> WiFi AP Alpha (same network)
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("WebSocketService", "Network available - same network type and identity - no action needed (ping/pong will handle failures)")
                        }
                    }
                }
            },
            onNetworkLost = {
                android.util.Log.w("WebSocketService", "Network lost - transitioning to RECONNECTING")
                logActivity("Network Lost", currentNetworkType.name)
                
                // Show toast for network lost
                showWebSocketToast("Network lost")
                
                // CRITICAL FIX: Evict connection pool when network is lost
                // This ensures we don't use stale DNS/connections when network returns
                WebSocketService.evictHealthCheckConnections()
                
                // Get last received request ID for reconnection
                val lastReceivedId = getLastReceivedRequestId(applicationContext)
                
                // FIX #1: Don't cancel the reconnection job on network loss - let it self-adapt
                // The reconnection job already checks currentNetworkType == NONE and will pause
                // This prevents the race condition where onNetworkAvailable assumes job is still running
                
                // FIX #3: Reset lastReconnectionTime when network is lost
                // This allows immediate reconnection when network returns (prevents rate-limit blocking)
                synchronized(reconnectionLock) {
                    lastReconnectionTime = 0L
                }
                
                // Cancel network change debounce job
                networkChangeDebounceJob?.cancel()
                networkChangeDebounceJob = null
                
                // PHASE 3.2: Update network type tracking
                lastNetworkType = currentNetworkType // Keep previous type for when network returns
                currentNetworkType = NetworkType.NONE
                
                // Clear WebSocket since there's no network
                clearWebSocket("Network lost - no network available")
                
                // When link returns, [lastReceivedId] tells us resume vs cold reconnect
                updateConnectionState(ConnectionState.WaitingForNetwork(lastReceivedId))
                updateConnectionStatus(false, lastKnownLagMs, lastSyncTimestamp)
            },
            onNetworkTypeChanged = { previousType, newType ->
                val previousNetworkType = lastNetworkType
                val newNetworkType = convertNetworkType(newType)
                val hasCallback = hasReconnectionSignal()
                
                android.util.Log.i("WebSocketService", "Network type changed: $previousType → $newType (previous tracked: $previousNetworkType)")
                logActivity("Network Type Changed: $previousType → $newType", newType.name)
                
                // Show toast for network type change
                showWebSocketToast("Network: $previousType → $newType")
                
                // CRITICAL FIX: Evict connection pool when network type changes
                // This ensures we don't use stale DNS/connections on the new network
                WebSocketService.evictHealthCheckConnections()
                
                // CRITICAL: Reset fallback backoff when network type changes
                if (lastNetworkTypeForBackoff != newNetworkType) {
                    fallbackBackoffDelayMs = 1000L // Reset to 1 second
                    lastNetworkTypeForBackoff = newNetworkType
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("WebSocketService", "Network type changed - reset fallback backoff to 1s")
                    }
                }
                
                // USER REQUIREMENT: Network type changed - always force reconnect
                // WiFi -> 5G: Force reconnect
                // 5G -> WiFi: Force reconnect
                // Any network type -> Another network type: Force reconnect
                
                // Update network type tracking
                // Note: Network identity (SSID) is updated in onNetworkIdentityChanged callback
                lastNetworkType = newNetworkType
                currentNetworkType = newNetworkType
                
                // Always force reconnect on network type change
                android.util.Log.w("WebSocketService", "Network type changed - forcing reconnection ($previousNetworkType → $newNetworkType)")
                
                // CRITICAL FIX: Cancel any pending reconnection attempts FIRST
                // This prevents race conditions where a reconnection starts after we clear the WebSocket
                synchronized(reconnectionLock) {
                    reconnectionJob?.cancel()
                    reconnectionJob = null
                }
                
                // CRITICAL FIX: If we're in Connecting state, clear it properly before scheduling reconnection
                // This prevents getting stuck in Connecting state when network type changes
                if (connectionState.isConnecting()) {
                    android.util.Log.w("WebSocketService", "Network type changed while in CONNECTING state - clearing connection before reconnecting")
                    clearWebSocket("Network type changed: $previousNetworkType → $newNetworkType (was Connecting)")
                } else if (!connectionState.isDisconnected()) {
                    // Clear WebSocket if not already disconnected
                    clearWebSocket("Network type changed: $previousNetworkType → $newNetworkType")
                }
                scheduleReconnection(ReconnectTrigger.NetworkTypeChanged(previousNetworkType, newNetworkType))
            },
            onNetworkIdentityChanged = { previousType, previousIdentity, newType, newIdentity ->
                // USER REQUIREMENT: WiFi network name is irrelevant
                // If Android says network was lost (onNetworkLost), we return to RECONNECTING
                // Network identity changes are handled by onNetworkLost and onNetworkTypeChanged
                // This callback is kept for logging but doesn't trigger reconnection
                android.util.Log.i("WebSocketService", "Network identity changed: $previousType ($previousIdentity) → $newType ($newIdentity) - ignoring (WiFi name irrelevant)")
                logActivity("Network Identity Changed: $previousType ($previousIdentity) → $newType ($newIdentity)", newType.name)
                
                // Update network identity tracking (for logging only)
                lastNetworkIdentity = newIdentity
                
                // No reconnection triggered - handled by onNetworkLost/onNetworkTypeChanged
            }
        )
        
        networkMonitor?.start()
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network monitoring started")

        // Seed currentNetworkType from the NetworkMonitor's initial state.
        // NetworkMonitor calls updateCurrentNetworkState() before registering the callback, so
        // Android's onAvailable() will fire with previousType = WIFI (not NONE) and skip calling
        // onNetworkAvailable. Without this seed, currentNetworkType stays NONE forever after a
        // START_STICKY restart on an already-connected network, blocking all recovery paths.
        val initialType = convertNetworkType(networkMonitor!!.getCurrentNetworkType())
        if (initialType != NetworkType.NONE && currentNetworkType == NetworkType.NONE) {
            android.util.Log.i("WebSocketService", "Seeding initial network type from NetworkMonitor: $initialType")
            currentNetworkType = initialType
            lastNetworkType = initialType
        }
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
    
    /**
     * PHASE 3.3: Wait for network validation to complete
     * Checks if network has NET_CAPABILITY_VALIDATED (internet access)
     * 
     * @param timeoutMs Maximum time to wait for validation (default 5000ms - increased for slow networks)
     * @return true if network is validated, false if timeout or not validated
     */
    private suspend fun waitForNetworkValidation(timeoutMs: Long = 5000L): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        
        var activeNetwork = connectivityManager.activeNetwork ?: return false
        
        // Check if network is already validated
        var capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network validation: Already validated")
            return true
        }
        
        // Network not validated yet - wait for validation
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network validation: Waiting up to ${timeoutMs}ms for validation")
        
        val startTime = System.currentTimeMillis()
        val checkInterval = 200L // Check every 200ms
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(checkInterval)
            
            // FIX #4: Don't abort if activeNetwork changes - re-acquire the new network reference and continue
            // A different network becoming active is valid (e.g., WiFi switching APs)
            val currentNetwork = connectivityManager.activeNetwork
            if (currentNetwork != null && currentNetwork != activeNetwork) {
                // Network switched - re-check capabilities on new network instead of aborting
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("WebSocketService", "Network validation: Network switched during validation - re-checking on new network")
                }
                activeNetwork = currentNetwork
                capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                // Continue to validation check below
            } else {
                // Same network - check capabilities
                capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            }
            
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                val elapsed = System.currentTimeMillis() - startTime
                android.util.Log.i("WebSocketService", "Network validation: Validated after ${elapsed}ms")
                
                // Show toast for network validation success
                showWebSocketToast("Network validated (${elapsed}ms)")
                
                return true
            }
            
            // Check if network is still available (not null)
            if (connectivityManager.activeNetwork == null) {
                android.util.Log.w("WebSocketService", "Network validation: Network lost during validation")
                return false
            }
        }
        
        // Timeout - network validation didn't complete
        android.util.Log.w("WebSocketService", "Network validation: Timeout after ${timeoutMs}ms - network may be slow or captive portal")
        
        // Show toast for network validation timeout
        showWebSocketToast("Network validation timeout (${timeoutMs}ms)")
        
        return false
    }
    
    /**
     * Fallback network validation using backend health check with exponential backoff
     * Used when Android's network validation times out but we're disconnected
     * 
     * @return true if backend is reachable (HTTP 200), false otherwise
     */
    private suspend fun tryFallbackNetworkValidation(): Boolean {
        val serviceInstance = instance ?: return false
        
        // Get homeserver URL from SharedPreferences
        val prefs = serviceInstance.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
        val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
        
        if (homeserverUrl.isBlank()) {
            android.util.Log.w("WebSocketService", "Fallback validation: No homeserver URL available")
            return false
        }
        
        try {
            android.util.Log.i("WebSocketService", "Fallback validation: Checking backend health with ${fallbackBackoffDelayMs}ms delay")
            
            // Wait for backoff delay before attempting
            delay(fallbackBackoffDelayMs)
            
            // Perform backend health check
            val isHealthy = try {
                val healthClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val request = okhttp3.Request.Builder()
                    .url(homeserverUrl)
                    .get()
                    .header("User-Agent", getUserAgent())
                    .build()
                
                healthClient.newCall(request).execute().use { response ->
                    val healthy = response.isSuccessful && response.code == 200
                    android.util.Log.i("WebSocketService", "Fallback validation: Backend health check: HTTP ${response.code} (healthy=$healthy)")
                    healthy
                }
            } catch (e: Exception) {
                android.util.Log.w("WebSocketService", "Fallback validation: Backend health check failed: ${e.message}", e)
                false
            }
            
            if (isHealthy) {
                // Success - reset backoff
                fallbackBackoffDelayMs = 1000L
                android.util.Log.i("WebSocketService", "Fallback validation: Backend reachable - proceeding with reconnection")
                return true
            } else {
                // Failure - increase backoff exponentially (1, 2, 4, 8, 16 seconds, max 16)
                fallbackBackoffDelayMs = (fallbackBackoffDelayMs * 2).coerceAtMost(16000L)
                android.util.Log.w("WebSocketService", "Fallback validation: Backend not reachable - next backoff: ${fallbackBackoffDelayMs}ms")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Fallback validation: Error during validation", e)
            // Increase backoff on error too
            fallbackBackoffDelayMs = (fallbackBackoffDelayMs * 2).coerceAtMost(16000L)
            return false
        }
    }
    
    /**
     * PHASE 3.2: Determine if we should reconnect when network type changes
     * 
     * @param previousType Previous network type
     * @param newType New network type
     * @return true if we should reconnect, false if we should keep existing connection
     */
    private fun shouldReconnectOnNetworkChange(previousType: NetworkType, newType: NetworkType): Boolean {
        // Network was lost and now available - always reconnect
        if (previousType == NetworkType.NONE && newType != NetworkType.NONE) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Offline → Online - reconnecting")
            return true
        }
        
        // Network became unavailable - don't reconnect (wait for network to return)
        if (previousType != NetworkType.NONE && newType == NetworkType.NONE) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Online → Offline - not reconnecting")
            return false
        }
        
        // Mobile → WiFi (better network) - reconnect to get better connection
        if (previousType == NetworkType.CELLULAR && newType == NetworkType.WIFI) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Mobile → WiFi - reconnecting for better network")
            return true
        }
        
        // WiFi → Mobile (worse network, but still works) - don't reconnect unnecessarily
        if (previousType == NetworkType.WIFI && newType == NetworkType.CELLULAR) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: WiFi → Mobile - not reconnecting (network still functional)")
            return false
        }
        
        // CRITICAL FIX: Don't reconnect on same-type network changes if connection is healthy
        // This prevents unnecessary reconnections when switching between WiFi networks or mobile networks
        if (previousType == newType && connectionState.isReady()) {
            // Check if connection is actually healthy (recent sync, low lag)
            val timeSinceSync = System.currentTimeMillis() - lastSyncTimestamp
            val currentLagMs = lastKnownLagMs // Capture in local variable to avoid smart cast issue
            val isHealthy = timeSinceSync < 60_000 && (currentLagMs == null || currentLagMs < 1000)
            
            if (isHealthy) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Same network type ($previousType) and connection healthy - not reconnecting")
                return false
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Same network type but connection unhealthy (sync: ${timeSinceSync}ms, lag: $lastKnownLagMs) - reconnecting")
                return true
            }
        }
        
        // WiFi → WiFi or Mobile → Mobile (different network) - reconnect to ensure proper connection
        if ((previousType == NetworkType.WIFI && newType == NetworkType.WIFI) ||
            (previousType == NetworkType.CELLULAR && newType == NetworkType.CELLULAR)) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Same type but different network ($previousType → $newType) - reconnecting")
            return true
        }
        
        // Other transitions (Ethernet, VPN, etc.) - reconnect to be safe
        if (previousType != newType) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Other transition ($previousType → $newType) - reconnecting")
            return true
        }
        
        // No change - don't reconnect
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: No network type change - not reconnecting")
        return false
    }
    
    private fun shouldIncludeLastReceivedForReconnect(): Boolean {
        // Always return false - we never pass last_received_id on connect/reconnect
        return false
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
            // BATTERY OPTIMIZATION: Unified monitoring replaces 3 separate jobs
            stopUnifiedMonitoring()
            
            // Cancels all coroutines launched on [serviceScope] (ping, reconnect, debounce, etc.)
            serviceScope.cancel()
            
            // Clear WebSocket connection immediately
            webSocket?.close(1000, "Service stopped")
            webSocket = null
            
            // Reset connection state
            updateConnectionState(ConnectionState.Disconnected)
            
            // Stop foreground service immediately
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
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
        
        if (!connectionState.isReady() || currentNetworkType == NetworkType.NONE) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Skipping ping - not ready or offline")
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
        
        // Include last_received_id so backend can evict already-processed sync_complete payloads.
        // This value is updated *after* a sync_complete is processed successfully (including during batch flush).
        val lastReceivedId = WebSocketService.getLastReceivedRequestId(applicationContext)
        val data = mapOf<String, Any>("last_received_id" to lastReceivedId)
        
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Sending ping (requestId: $reqId)")
        
        // Log ping status before sending
        android.util.Log.i("WebSocketService", "Pinger status: Sending ping (requestId: $reqId)")
        
        // Send ping directly via WebSocket
        try {
            val json = org.json.JSONObject()
            json.put("command", "ping")
            json.put("data", org.json.JSONObject(data))
            json.put("request_id", reqId)
            val jsonString = json.toString()
            
            // Log the actual PING JSON being sent
            android.util.Log.i("WebSocketService", "PING JSON: $jsonString")
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Executing webSocket.send(ping)")
            val sent = currentWebSocket.send(jsonString)
            if (sent) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping sent successfully")
                
                // Reschedule heartbeat alarm fallback
                scheduleHeartbeatAlarm()
                // Start timeout for this ping
                startPongTimeout(reqId)
            } else {
                android.util.Log.w("WebSocketService", "Failed to send ping via WebSocket")
                pingInFlight = false // Reset since it failed to send
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Exception sending ping", e)
            pingInFlight = false // Reset on error
        }
    }
    
    /**
     * Start pong timeout - clears pingInFlight after delay so next ping can be sent.
     * Reconnection is handled by 60s message timeout, not pong timeout.
     */
    private fun startPongTimeout(pingRequestId: Int) {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = serviceScope.launch {
            delay(PONG_CLEAR_INFLIGHT_MS)
            if (!connectionState.isReady()) return@launch
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Pong clear timeout - clearing pingInFlight for next ping")
            pingInFlight = false
        }
    }
    
    // RUSH TO HEALTHY: Removed network metrics - ping/pong failures are the only metric we need
    
    /**
     * Start timeout for init_complete after WebSocket opens
     * If init_complete doesn't arrive within timeout, drop connection and retry with exponential backoff
     */
    /**
     * Start timeout for run_id - if not received within 2 seconds, connection is broken
     * CRITICAL FIX: Improved reliability - ensures job is always scheduled with fallback
     */
    private fun startRunIdTimeout() {
        runIdTimeoutJob?.cancel()
        runIdTimeoutJob = null
        
        // CRITICAL FIX: Ensure job is always scheduled - retry if serviceScope is not active
        runIdTimeoutJob = try {
            serviceScope.launch {
                try {
                    delay(RUN_ID_TIMEOUT_MS)
                    
                    // Check if run_id was received
                    if (runIdReceived) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Run ID timeout expired but already received - ignoring")
                        return@launch
                    }
                    
                    // Check if connection is still active
                    if (!connectionState.isDialOrSyncing()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Run ID timeout expired but connection not active - ignoring")
                        return@launch
                    }
                    
                    android.util.Log.w("WebSocketService", "Run ID timeout expired after ${RUN_ID_TIMEOUT_MS}ms - connection broken (run_id not received)")
                    logActivity("Run ID Timeout - Connection Broken", currentNetworkType.name)
                    
                    // Show toast for timeout
                    showWebSocketToast("run_id timeout - reconnecting")
                    
                    // Drop the connection - it's broken
                    clearWebSocket("Run ID timeout - connection broken (run_id not received)")
                    
                    // Schedule reconnection
                    scheduleReconnection(ReconnectTrigger.RunIdTimeout)
                } catch (e: CancellationException) {
                    // Job was cancelled - this is expected when run_id arrives
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Run ID timeout job cancelled (run_id received)")
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in run_id timeout job: ${e.message}", e)
                    // FALLBACK: If timeout job fails, force recovery via unified monitoring
                    // Unified monitoring will detect stuck state and recover
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to schedule run_id timeout job: ${e.message}", e)
            // FALLBACK: Schedule a delayed check via unified monitoring
            // Unified monitoring will detect if we're stuck without timeout jobs
            null
        }
        
        // CRITICAL FIX: Verify job was scheduled successfully
        if (runIdTimeoutJob == null || !runIdTimeoutJob!!.isActive) {
            android.util.Log.w("WebSocketService", "Run ID timeout job failed to schedule - unified monitoring will detect stuck state")
        }
    }
    
    
    /**
     * Start timeout for init_complete
     * CRITICAL FIX: Improved reliability - ensures job is always scheduled with fallback
     * CRITICAL FIX: Dynamic timeout extension - each message from backend extends timeout by 5s
     */
    private fun startInitCompleteTimeout() {
        initCompleteTimeoutJob?.cancel()
        initCompleteTimeoutJob = null
        
        // Reset message counter
        messagesReceivedWhileWaitingForInitComplete = 0
        
        // Use base timeout (5s) if run_id was received, otherwise use fallback timeout
        val baseTimeoutMs = if (runIdReceived) {
            INIT_COMPLETE_AFTER_RUN_ID_TIMEOUT_MS_BASE
        } else {
            INIT_COMPLETE_TIMEOUT_MS_BASE
        }
        
        // Calculate initial end time
        val currentTime = System.currentTimeMillis()
        initCompleteTimeoutEndTime = currentTime + baseTimeoutMs
        
        if (BuildConfig.DEBUG) {
            android.util.Log.d("WebSocketService", "Starting init_complete timeout: ${baseTimeoutMs}ms base (will extend by ${INIT_COMPLETE_EXTENSION_PER_MESSAGE_MS}ms per message)")
        }
        
        // Start the timeout job
        restartInitCompleteTimeoutWithNewEndTime(initCompleteTimeoutEndTime)
    }
    
    /**
     * Extend init_complete timeout when a message is received from backend
     * Each message extends the timeout by 5 seconds, indicating the connection is alive but slow
     */
    private fun extendInitCompleteTimeoutOnMessage() {
        if (!connectionState.isDialOrSyncing()) {
            return // Not in dial/sync phase, no need to extend
        }
        
        messagesReceivedWhileWaitingForInitComplete++
        val currentTime = System.currentTimeMillis()
        
        // Calculate new timeout end time
        val newEndTime = if (initCompleteTimeoutEndTime > currentTime) {
            // Extend existing timeout
            initCompleteTimeoutEndTime + INIT_COMPLETE_EXTENSION_PER_MESSAGE_MS
        } else {
            // Timeout already expired or not set, extend from now
            currentTime + INIT_COMPLETE_AFTER_RUN_ID_TIMEOUT_MS_BASE + INIT_COMPLETE_EXTENSION_PER_MESSAGE_MS
        }
        
        initCompleteTimeoutEndTime = newEndTime
        
        if (BuildConfig.DEBUG) {
            val remainingMs = newEndTime - currentTime
            android.util.Log.d("WebSocketService", "Extended init_complete timeout on message #$messagesReceivedWhileWaitingForInitComplete - new timeout: ${remainingMs}ms from now")
        }
        
        // Restart the timeout job with the new end time
        restartInitCompleteTimeoutWithNewEndTime(newEndTime)
    }
    
    /**
     * Restart init_complete timeout job with a new end time
     */
    private fun restartInitCompleteTimeoutWithNewEndTime(endTime: Long) {
        initCompleteTimeoutJob?.cancel()
        initCompleteTimeoutJob = null
        
        val currentTime = System.currentTimeMillis()
        val remainingMs = (endTime - currentTime).coerceAtLeast(0)
        
        if (remainingMs <= 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Not restarting init_complete timeout - already expired")
            return
        }
        
        initCompleteTimeoutJob = try {
            serviceScope.launch {
                try {
                    delay(remainingMs)
                    
                    // Check if we're still waiting for init_complete
                    if (!connectionState.isDialOrSyncing()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete timeout expired but not in dial/sync phase - ignoring")
                        return@launch
                    }
                    
                    handleInitCompleteTimeout()
                } catch (e: CancellationException) {
                    // Job was cancelled - this is expected when init_complete arrives or timeout is extended
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete timeout job cancelled")
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in init_complete timeout job: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to restart init_complete timeout job: ${e.message}", e)
            null
        }
    }
    
    /**
     * Handle init_complete timeout expiration
     */
    private suspend fun handleInitCompleteTimeout() {
        val reason = if (runIdReceived) {
            "Init complete timeout expired after ${messagesReceivedWhileWaitingForInitComplete} messages received - connection slow or broken"
        } else {
            "Init complete timeout expired - connection failed"
        }
        
        android.util.Log.w("WebSocketService", reason)
        logActivity("Init Complete Timeout - Connection Failed", currentNetworkType.name)
        
        // Drop the connection
        clearWebSocket("Init complete timeout - connection failed")
        
        // CRITICAL FIX: Check if we're already in a reconnection flow (from NetworkMonitor)
        val alreadyReconnecting = synchronized(reconnectionLock) {
            connectionState.isReconnectingPhase() || reconnectionJob?.isActive == true
        }
        
        if (alreadyReconnecting) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("WebSocketService", "Init complete timeout but already reconnecting - skipping duplicate notification and retry")
            }
            initCompleteRetryCount = 0
            return
        }
        
        // Show notification about connection failure
        showInitCompleteFailureNotification()
    }
    
    /**
     * CRITICAL FIX #1: Hard timeout for total Connecting state duration
     * This is a safety net that fires after timeout regardless of other timeout jobs
     * Prevents getting stuck if timeout jobs fail or get cancelled
     */
    private fun startHardConnectingTimeout() {
        hardConnectingTimeoutJob?.cancel()
        hardConnectingTimeoutJob = null
        
        hardConnectingTimeoutJob = try {
            serviceScope.launch {
                try {
                    delay(HARD_CONNECTING_TIMEOUT_MS)
                    
                    // Check if we're still in Connecting state
                    if (!connectionState.isConnecting()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Hard connecting timeout expired but no longer in Connecting state - ignoring")
                        return@launch
                    }
                    
                    val timeSinceConnect = if (connectionStartTime > 0) System.currentTimeMillis() - connectionStartTime else 0
                    android.util.Log.w("WebSocketService", "HARD TIMEOUT: Stuck in Connecting state for ${timeSinceConnect}ms (>${HARD_CONNECTING_TIMEOUT_MS}ms) - forcing recovery")
                    logActivity("Hard Timeout - Stuck in Connecting", currentNetworkType.name)
                    
                    // Force recovery
                    clearWebSocket("Hard timeout: Stuck in Connecting for >${HARD_CONNECTING_TIMEOUT_MS}ms")
                    
                    // Reset reconnection state
                    synchronized(reconnectionLock) {
                        reconnectionJob?.cancel()
                        reconnectionJob = null
                    }
                    
                    // Schedule reconnection
                    scheduleReconnection(ReconnectTrigger.HardConnectingTimeout)
                } catch (e: CancellationException) {
                    // Job was cancelled - this is expected when state transitions
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Hard connecting timeout job cancelled (state transitioned)")
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in hard connecting timeout job: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to schedule hard connecting timeout job: ${e.message}", e)
            null
        }
        
        // Verify job was scheduled successfully
        if (hardConnectingTimeoutJob == null || !hardConnectingTimeoutJob!!.isActive) {
            android.util.Log.w("WebSocketService", "Hard connecting timeout job failed to schedule")
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
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("from_service_notification", true)
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
     * Logical connection lifecycle is [ConnectionState] (see [net.vrkknn.andromuks.ConnectionState]).
     */

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
        
        // CRITICAL: Restore last_received_request_id from SharedPreferences to RAM on service start
        // This ensures the value survives service restarts
        val prefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
        val persistedRequestId = prefs.getInt("last_received_request_id", 0)
        if (persistedRequestId != 0) {
            lastReceivedRequestId = persistedRequestId
            if (BuildConfig.DEBUG) {
                android.util.Log.d("WebSocketService", "Restored last_received_request_id from SharedPreferences: $persistedRequestId")
            }
        }
        
        // Initialize connection state StateFlow
        updateConnectionState(connectionState)
        
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service created - serviceStartTime: $serviceStartTime")
        
        // run_id is always read from SharedPreferences when needed - no need to restore on startup
        // NOTE: We no longer track last_received_id - all timeline caches are cleared on connect/reconnect
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service startup - run_id will be read from SharedPreferences when needed")
        
        // BATTERY OPTIMIZATION: Start unified monitoring (combines state corruption, primary health, connection health)
        // Previously had 3 separate jobs - now combined into single job to reduce wake-ups by 66%
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Starting unified monitoring from onCreate")
        startUnifiedMonitoring()
        
        // PHASE 3.1: Start network monitoring for immediate reconnection on network changes
        startNetworkMonitoring()
        
        // BATTERY OPTIMIZATION: Wake lock removed - foreground service is sufficient
        // The foreground service notification prevents Android from killing the process
        // Wake locks were causing 50-70% of battery drain by keeping CPU awake continuously
        
        // PERFORMANCE: Register screen state receiver for dynamic priority management
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
        
        // Initialize isScreenOn state using PowerManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = pm.isInteractive
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service started with intent: ${intent?.action}")
        
        // Handle stop request
        if (intent?.action == "STOP_SERVICE") {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Stop service requested via intent")
            stopService()
            return START_NOT_STICKY
        }
        
        // Handle heartbeat alarm
        if (intent?.action == ACTION_HEARTBEAT_ALARM) {
            handleHeartbeatAlarm()
            return START_STICKY
        }
        
        // CRITICAL FIX: Check battery optimization status and warn if enabled
        // Battery optimization can cause the service to be killed
        checkBatteryOptimizationStatus()
        
        // Start as foreground service with notification
        // This keeps the app process alive and prevents Android from killing it
        if (!foregroundStartNotAllowedForThisProcess) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ requires explicit service type
                    // Use REMOTE_MESSAGING instead of DATA_SYNC to better match long-lived messaging use case
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                    )
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "WebSocketService",
                            "Foreground service started successfully (Android 14+, type=REMOTE_MESSAGING)"
                        )
                    }
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Foreground service started successfully")
                }
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Android 14+ foreground service quota or policy restriction
                foregroundStartNotAllowedForThisProcess = true
                android.util.Log.w(
                    "WebSocketService",
                    "Foreground service start not allowed (quota/policy). Running as background service only for this process.",
                    e
                )
                // NOTE: We intentionally do NOT stop the service here; it will continue as a background service.
                // This avoids crashes when the FGS quota is exhausted.
            } catch (e: Exception) {
                android.util.Log.e("WebSocketService", "Failed to start foreground service", e)
                // Don't crash - service will run in background
            }
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "WebSocketService",
                "Skipping startForeground(): previously denied for this process; running as background service only."
            )
        }
        
        // CRITICAL FIX: Update notification with current connection state after service starts
        // This ensures the notification shows the correct state even if no WebSocket is connected yet
        // Also check if reconnection callback is available
        // PHASE 1.2: Check active callback (primary or legacy)
        val hasCallback = hasReconnectionSignal()
        updateConnectionStatus(
            isConnected = connectionState.isReady() && webSocket != null,
            lagMs = lastKnownLagMs,
            lastSyncTimestamp = lastSyncTimestamp
        )
        
        // If credentials are missing, log it for debugging (reconnection uses SharedPreferences via [invokeReconnectionCallback])
        if (!hasCallback) {
            android.util.Log.w("WebSocketService", "Service started without login credentials - notification will show 'Waiting for app...'")
        }
        
        // CRITICAL FIX: Return START_STICKY to ensure service restarts if killed by system
        // This is essential for reliability, especially without battery optimization exemption
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // PERFORMANCE: Unregister screen state receiver
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            android.util.Log.w("WebSocketService", "Failed to unregister screenStateReceiver", e)
        }
        
        super.onDestroy()
        
        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service onDestroy() called - cleaning up resources")
        
        try {
            // Stop all monitoring and jobs
            // BATTERY OPTIMIZATION: Unified monitoring replaces 3 separate jobs
            stopUnifiedMonitoring()
            // PHASE 3.1: Stop network monitoring
            stopNetworkMonitoring()
            
            // Cancels ping loop, reconnection job, unified monitoring, network debounce, timeouts, etc.
            serviceScope.cancel()
            
            // Clear WebSocket connection
            webSocket?.close(1000, "Service destroyed")
            webSocket = null
            
            // Reset connection state
            updateConnectionState(ConnectionState.Disconnected)
            
            // Stop foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
            // BATTERY OPTIMIZATION: Wake lock removed - no cleanup needed
            
            // Cancel heartbeat alarm
            cancelHeartbeatAlarm()
            
            // Clear instance reference
            instance = null
            
            // Trigger auto-restart via WorkManager (unless this was an intentional stop)
            // Check if this was an intentional stop by checking if stopService() was called
            // We can't easily detect this, so we'll always schedule a restart and let
            // ServiceStartWorker check if credentials exist (if user logged out, it won't start)
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Scheduling auto-restart via WorkManager")
            scheduleAutoRestart("Service destroyed")
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Service cleanup completed")
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Error during service cleanup", e)
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.w("WebSocketService", "onTaskRemoved() called - app removed from recent apps, scheduling AlarmManager restart")
        
        // Schedule AlarmManager restart (1 second delay) to restart service
        // This ensures service restarts even if app is swiped away from recents
        scheduleAlarmManagerRestart("App removed from recent apps")
    }

    /**
     * Get the notification title showing the backend server hostname.
     * Falls back to "Andromuks" if no server URL is configured.
     */
    private fun getNotificationTitle(): String {
        return try {
            val prefs = getSharedPreferences("AndromuksAppPrefs", MODE_PRIVATE)
            val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
            if (homeserverUrl.isNotBlank()) {
                // Extract hostname from URL (e.g., "https://webmuks.server.com" -> "webmuks.server.com")
                val url = java.net.URL(if (homeserverUrl.startsWith("http")) homeserverUrl else "https://$homeserverUrl")
                url.host
            } else {
                "Andromuks"
            }
        } catch (e: Exception) {
            "Andromuks"
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
        // CRITICAL FIX: Use SINGLE_TOP instead of CLEAR_TASK to handle process death recovery
        // SINGLE_TOP brings existing activity to front without clearing task stack
        // This prevents issues when app process is killed but service survives
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Add flag to indicate this is from service notification (for process death recovery)
            putExtra("from_service_notification", true)
        }
        
        // CRITICAL FIX: Use FLAG_IMMUTABLE for Android 12+ and ensure PendingIntent is refreshed
        // Request code 0 ensures we always get the same PendingIntent (Android caches by request code)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlags
        )

        // Use current connection state for initial notification text
        val currentState = connectionState
        val initialText = when (currentState) {
            is ConnectionState.Disconnected -> "Connecting..."
            is ConnectionState.Connecting -> "Connecting..."
            is ConnectionState.Initializing -> "Initializing... (${currentState.receivedSyncCount}/${currentState.pendingSyncCount})"
            is ConnectionState.Ready -> if (BuildConfig.DEBUG) "Connected." else "Connected."
            is ConnectionState.QuickReconnecting -> "Reconnecting... (${currentState.attemptNumber})"
            is ConnectionState.FullReconnecting -> "Reconnecting..."
            is ConnectionState.WaitingForNetwork -> "Waiting for network..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getNotificationTitle())
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
        
        // CRITICAL FIX: Use SINGLE_TOP for process death recovery (same as createNotification)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_service_notification", true)
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlags
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
            .setContentTitle(getNotificationTitle())
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
        // CRITICAL FIX: Use SINGLE_TOP for process death recovery (same as createNotification)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_service_notification", true)
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlags
        )
        
        val notificationText = if (BuildConfig.DEBUG) {
            // DEBUG BUILD: Show detailed stats with lag, sync time, etc.
            when {
                // Check if reconnection callback is missing (AppViewModel not available)
                // PHASE 1.2: Check active callback (primary or legacy)
                !hasReconnectionSignal() && !connectionState.isReady() -> {
                    "Waiting for app... • ${getNetworkTypeDisplayName(currentNetworkType)}"
                }
                !isConnected -> "Connecting... • ${getNetworkTypeDisplayName(currentNetworkType)}"
                connectionState.isReconnectingPhase() -> {
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
            val callbackMissing = !hasReconnectionSignal() && !currentState.isReady()
            
            // Track both state and callback status for change detection
            val stateKey = "$currentState-$callbackMissing"
            val stateChanged = lastConnectionStateForNotification?.let { it != stateKey } ?: true
            
            // Only update if state changed
            if (!stateChanged && lastNotificationText != null) {
                //if (BuildConfig.DEBUG) Log.d("WebSocketService", "Skipping notification update - state unchanged: $currentState, callbackMissing: $callbackMissing")
                return
            }
            
            lastConnectionStateForNotification = stateKey
            
            when {
                callbackMissing -> "Waiting for app..."
                // CRITICAL FIX: When network is lost (NONE), show "Disconnected" instead of "Connecting..."
                currentState is ConnectionState.Disconnected && currentNetworkType == NetworkType.NONE -> "Disconnected"
                currentState is ConnectionState.Disconnected -> "Connecting..."
                currentState is ConnectionState.Connecting -> "Connecting..."
                currentState is ConnectionState.Initializing -> "Initializing... (${currentState.receivedSyncCount}/${currentState.pendingSyncCount})"
                currentState is ConnectionState.Ready -> "Connected."
                currentState is ConnectionState.QuickReconnecting -> "Reconnecting... (${currentState.attemptNumber})"
                currentState is ConnectionState.FullReconnecting -> "Reconnecting..."
                currentState is ConnectionState.WaitingForNetwork -> "Waiting for network..."
                else -> "Connecting..."
            }
        }
        
        // IDEMPOTENT: Only update notification if the text actually changed
        if (lastNotificationText == notificationText) {
            return
        }
        
        // BATTERY OPTIMIZATION: Throttle notification updates in both debug and release builds
        // Prevents excessive notification updates that wake the device
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastNotificationUpdateTime
        
        if (timeSinceLastUpdate < MIN_NOTIFICATION_UPDATE_INTERVAL_MS) {
                // Too soon - skip this update to avoid flicker
                //if (BuildConfig.DEBUG) Log.d("WebSocketService", "Throttling notification update (${timeSinceLastUpdate}ms < ${MIN_NOTIFICATION_UPDATE_INTERVAL_MS}ms)")
                
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
                            .setContentTitle(getNotificationTitle())
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
        
        lastNotificationText = notificationText
        lastNotificationUpdateTime = System.currentTimeMillis()
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getNotificationTitle())
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
    
    /**
     * BATTERY OPTIMIZATION: Wake lock removed
     * 
     * Previously, we used a PARTIAL_WAKE_LOCK to keep the CPU awake continuously.
     * This was causing 50-70% of battery drain when the app was backgrounded.
     * 
     * The foreground service notification is sufficient to prevent Android from
     * killing the process. Wake locks are only needed for short-duration operations
     * (like sending a ping), not for keeping the service alive.
     * 
     * If wake locks are needed in the future, use acquireTimeout() with short
     * durations (e.g., 30 seconds) and release immediately after operations.
     */
    
    /**
     * Check battery optimization status and log warning if enabled
     * Battery optimization can cause the foreground service to be killed
     */
    private fun checkBatteryOptimizationStatus() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
            
            if (!isIgnoringBatteryOptimizations) {
                android.util.Log.w("WebSocketService", "Battery optimization is ENABLED for this app - service may be killed by system")
                android.util.Log.w("WebSocketService", "To prevent service from being killed, disable battery optimization in Settings > Apps > Andromuks > Battery")
                android.util.Log.w("WebSocketService", "Or use the in-app permissions screen to request battery optimization exemption")
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("WebSocketService", "Battery optimization is disabled - service should remain stable")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to check battery optimization status: ${e.message}", e)
        }
    }
    
    /**
     * Schedule AlarmManager restart (used when app is removed from recent apps)
     * Uses setExact on Android N+ and scheduleExact on Android TIRAMISU+
     */
    private fun scheduleAlarmManagerRestart(reason: String) {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, AutoRestartReceiver::class.java).apply {
                action = AutoRestartReceiver.ACTION_RESTART_SERVICE
                putExtra(AutoRestartReceiver.EXTRA_REASON, reason)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = System.currentTimeMillis() + ALARM_RESTART_DELAY_MS
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): Use scheduleExactAllowWhileIdle for exact alarms
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Scheduled AlarmManager restart (setExactAndAllowWhileIdle): $reason")
                } else {
                    // Fallback to inexact alarm if exact alarms not allowed
                    alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    android.util.Log.w("WebSocketService", "SCHEDULE_EXACT_ALARM permission not granted - using inexact alarm")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6+ (API 23+): Use setExact
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Scheduled AlarmManager restart (setExact): $reason")
            } else {
                // Android < 6: Use set (inexact, but better than nothing)
                alarmManager.set(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Scheduled AlarmManager restart (set): $reason")
            }
            
            logActivity("AlarmManager Restart Scheduled: $reason", currentNetworkType.name)
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to schedule AlarmManager restart: ${e.message}", e)
        }
    }
    
    /**
     * Schedule auto-restart via WorkManager (used when service is destroyed)
     * This ensures service restarts at higher priority than BroadcastReceiver
     */
    private fun scheduleAutoRestart(reason: String) {
        try {
            AutoRestartReceiver.sendRestartIntent(this, reason)
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Scheduled auto-restart via WorkManager: $reason")
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to schedule auto-restart: ${e.message}", e)
        }
    }

    /**
     * Handle heartbeat alarm triggered by AlarmManager
     */
    private fun handleHeartbeatAlarm() {
        if (BuildConfig.DEBUG) android.util.Log.i("WebSocketService", "Heartbeat alarm triggered")
        
        // Acquire short wake lock to ensure we can send the ping
        acquireHeartbeatWakeLock()
        
        // Send ping if connected
        if (connectionState.isReady() && webSocket != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Sending heartbeat ping via AlarmManager")
            sendPing()
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Heartbeat alarm: service not connected, skipping ping")
            // Still reschedule so we keep attempting when back online
            scheduleHeartbeatAlarm()
        }
        
        // Wake lock will be released automatically by the timeout, 
        // but we'll release it after a small delay in case sendPing is async
        serviceScope.launch {
            delay(2000)
            releaseHeartbeatWakeLock()
        }
    }
    
    /**
     * Schedule the next heartbeat alarm
     * This acts as a fallback for the coroutine ping loop
     */
    private fun scheduleHeartbeatAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, WebSocketService::class.java).apply {
                action = ACTION_HEARTBEAT_ALARM
            }
            
            val pendingIntent = PendingIntent.getService(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Calculate next alarm time: current interval + margin
            val interval = PING_INTERVAL_MS
            val triggerTime = System.currentTimeMillis() + interval + HEARTBEAT_MARGIN_MS
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): Use scheduleExactAllowWhileIdle for exact alarms
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    // Fallback to inexact alarm if exact alarms not allowed
                    alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    if (BuildConfig.DEBUG) android.util.Log.w("WebSocketService", "SCHEDULE_EXACT_ALARM permission not granted - using inexact heartbeat")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6+ (API 23+): Use setExactAndAllowWhileIdle
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                // Android < 6: Use setExact for best effort
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Scheduled heartbeat alarm in ${interval + HEARTBEAT_MARGIN_MS}ms")
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to schedule heartbeat alarm", e)
        }
    }
    
    /**
     * Cancel the heartbeat alarm
     */
    private fun cancelHeartbeatAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, WebSocketService::class.java).apply {
                action = ACTION_HEARTBEAT_ALARM
            }
            val pendingIntent = PendingIntent.getService(
                this,
                0,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Cancelled heartbeat alarm")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to cancel heartbeat alarm", e)
        }
    }
    
    private fun acquireHeartbeatWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            heartbeatWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Andromuks:Heartbeat").apply {
                acquire(HEARTBEAT_WAKE_LOCK_MS)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Heartbeat wake lock acquired")
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to acquire heartbeat wake lock", e)
        }
    }
    
    private fun releaseHeartbeatWakeLock() {
        try {
            heartbeatWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Heartbeat wake lock released")
                }
            }
            heartbeatWakeLock = null
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "Failed to release heartbeat wake lock", e)
        }
    }
}
