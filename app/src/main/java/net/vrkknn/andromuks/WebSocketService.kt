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
import java.util.concurrent.atomic.AtomicLong
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
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "websocket_service_channel"
        private const val CHANNEL_NAME = "WebSocket Service"
        // BATTERY OPTIMIZATION: WAKE_LOCK_TAG removed - wake lock no longer used
        private const val ALARM_RESTART_DELAY_MS = 1000L // 1 second delay for AlarmManager restart
        private var instance: WebSocketService? = null
        
        // Service-scoped coroutine scope for background processing
        private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        // Constants
        private val BASE_RECONNECTION_DELAY_MS = 500L // 3 seconds - give network time to stabilize
        private val MIN_RECONNECTION_INTERVAL_MS = 1000L // 5 seconds minimum between any reconnections
        private val MIN_NOTIFICATION_UPDATE_INTERVAL_MS = 1000L // 500ms minimum between notification updates (UI smoothing)
        private const val BACKEND_HEALTH_RETRY_DELAY_MS = 1_000L
        // Connection health: Ping every 15s (first ping 15s after connect), mark bad after 60s without ANY message
        private const val PING_INTERVAL_MS = 15_000L // 15 seconds - first ping 15s after connect, then every 15s
        private const val MESSAGE_TIMEOUT_MS = 60_000L // 60 seconds without ANY message = connection stale, reconnect
        private const val PONG_CLEAR_INFLIGHT_MS = 5_000L // Clear pingInFlight after 5s so next ping can be sent
        private const val INIT_COMPLETE_TIMEOUT_MS_BASE = 15_000L // Legacy - used by unified monitoring
        private const val HARD_CONNECTING_TIMEOUT_MS = 5_000L // Legacy - unused, we go directly to Ready
        private const val RUN_ID_TIMEOUT_MS = 2_000L // Legacy - unused, we don't wait for run_id
        private const val INIT_COMPLETE_AFTER_RUN_ID_TIMEOUT_MS_BASE = 5_000L // Legacy - unused
        private const val INIT_COMPLETE_EXTENSION_PER_MESSAGE_MS = 5_000L // Legacy - unused
        private const val MAX_RECONNECTION_ATTEMPTS = 99
        private const val RECONNECTION_RESET_TIME_MS = 300_000L // Reset count after 5 minutes
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 500L // Debounce rapid network changes
        private val TOGGLE_STACK_DEPTH = 6
        private val toggleCounter = AtomicLong(0)
        
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
        
        // PHASE 4: WebSocket Callback Queues (allows multiple ViewModels to interact)
        
        // Send callbacks: For sending commands FROM service TO ViewModels (e.g., ping/pong)
        private val webSocketSendCallbacks = mutableListOf<Pair<String, (String, Int, Map<String, Any>) -> Boolean>>()
        
        // PHASE 4: Receive callbacks: For distributing messages FROM server TO ViewModels
        // When a message arrives from server, all registered ViewModels are notified
        private val webSocketReceiveCallbacks = mutableListOf<Pair<String, AppViewModel>>()
        
        private val callbacksLock = Any() // Thread safety for callback lists
        
        // STEP 2.1: ViewModel lifecycle tracking - tracks which ViewModels are alive
        // This allows the service to know which ViewModels exist and can be promoted to primary
        data class ViewModelInfo(
            val viewModelId: String,
            val isPrimary: Boolean,
            val registeredAt: Long = System.currentTimeMillis()
        )
        private val registeredViewModels = mutableMapOf<String, ViewModelInfo>()
        
        // PHASE 1.1: Primary instance tracking - ensures only one AppViewModel controls WebSocket lifecycle
        // Primary instance is the one that manages reconnection, offline mode, and activity logging
        private var primaryViewModelId: String? = null
        
        // Connection state StateFlow for reactive UI updates
        private val _connectionStateFlow = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
        val connectionStateFlow: StateFlow<WebSocketState> = _connectionStateFlow.asStateFlow()
        
        // STEP 1.1: Primary callbacks stored in service (not AppViewModel)
        // These callbacks are stored as lambda functions in the service, allowing them to persist
        // even if the primary AppViewModel is destroyed. The callbacks are invoked by the service
        // when needed (reconnection, offline mode changes, activity logging).
        // NOTE: Currently these callbacks may still capture AppViewModel references, which will be
        // addressed in Step 1.2 when we modify how callbacks are registered.
        private var primaryReconnectionCallback: ((String) -> Unit)? = null
        private var primaryOfflineModeCallback: ((Boolean) -> Unit)? = null
        private var primaryActivityLogCallback: ((String, String?) -> Unit)? = null
        private var primaryClearCacheCallback: (() -> Unit)? = null
        
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
            // Log.i("WS-Toggle", "id=$id ts=$ts event=$event details=[$details] stack=[$stack]")
            return id
        }
        
        // Activity log callback - set by AppViewModel
        // Legacy callback - kept for backward compatibility
        private var activityLogCallback: ((String, String?) -> Unit)? = null

        // Headless AppViewModel used for boot/background startup when no UI is running.
        private var headlessViewModel: AppViewModel? = null
        // Debounce headless recovery attempts to avoid churn during flappy networks.
        // CRITICAL FIX: Reduced from 10s to 1s - 10 seconds is too long for users
        private const val MIN_HEADLESS_RECOVERY_INTERVAL_MS = 1_000L
        private var lastHeadlessRecoveryAttemptMs: Long = 0L
        
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
        fun getPrimaryViewModelId(): String? = primaryViewModelId
        
        /**
         * PHASE 1.1: Check if a ViewModel ID is the primary instance
         */
        fun isPrimaryInstance(viewModelId: String): Boolean {
            return primaryViewModelId == viewModelId
        }

        /**
         * Ensure a headless AppViewModel exists to bring up the WebSocket on boot/background.
         * This is only used when no UI ViewModel has registered callbacks yet.
         * 
         * CRITICAL FIX: Reduced debounce to 1 second and ensures callbacks are registered before returning.
         */
        /**
         * REFACTORING: Headless ViewModel is now only needed for message routing, not reconnection
         * Reconnection is handled directly by the service
         */
        fun ensureHeadlessPrimary(context: Context, reason: String) {
            // Check if we already have a ViewModel for message routing
            val hasViewModel = getRegisteredViewModels().isNotEmpty() || headlessViewModel != null
            if (hasViewModel) {
                if (BuildConfig.DEBUG) Log.d("WebSocketService", "Headless ViewModel already exists - skipping creation")
                return
            }
            
            val now = System.currentTimeMillis()
            if (now - lastHeadlessRecoveryAttemptMs < MIN_HEADLESS_RECOVERY_INTERVAL_MS) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "WebSocketService",
                        "Headless recovery debounced (${now - lastHeadlessRecoveryAttemptMs}ms < ${MIN_HEADLESS_RECOVERY_INTERVAL_MS}ms): $reason"
                    )
                }
                return
            }
            lastHeadlessRecoveryAttemptMs = now

            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
            val authToken = prefs.getString("gomuks_auth_token", "") ?: ""
            if (homeserverUrl.isBlank() || authToken.isBlank()) {
                if (BuildConfig.DEBUG) Log.d("WebSocketService", "Headless start skipped - missing credentials")
                return
            }

            // CRITICAL FIX: Check if headless ViewModel exists but callbacks aren't registered yet
            // This can happen if markAsPrimaryInstance() failed or hasn't completed
            if (headlessViewModel != null) {
                val stillNoCallback = getActiveReconnectionCallback() == null
                if (stillNoCallback) {
                    android.util.Log.w("WebSocketService", "Headless ViewModel exists but callbacks not registered - re-registering callbacks")
                    // Try to re-register callbacks
                    headlessViewModel?.markAsPrimaryInstance()
                    // Wait a moment for callbacks to register
                    serviceScope.launch {
                        delay(100)
                        val callbackNow = getActiveReconnectionCallback()
                        if (callbackNow == null) {
                            android.util.Log.e("WebSocketService", "Headless ViewModel callbacks still not registered after re-registration - recreating ViewModel")
                            headlessViewModel = null // Force recreation
                        }
                    }
                }
            }

            if (headlessViewModel == null) {
                android.util.Log.i("WebSocketService", "Creating headless AppViewModel for background startup ($reason)")
                headlessViewModel = AppViewModel().apply {
                    initializeFCM(context.applicationContext, homeserverUrl, authToken, skipCacheClear = true)
                    updateHomeserverUrl(homeserverUrl)
                    updateAuthToken(authToken)
                    loadSettings(context.applicationContext)
                    markAsPrimaryInstance()
                }
                
                // CRITICAL FIX: Verify callbacks are registered after creation
                // Wait a moment for markAsPrimaryInstance() to complete
                serviceScope.launch {
                    delay(200) // Give time for callbacks to register
                    val callbackRegistered = getActiveReconnectionCallback() != null
                    if (callbackRegistered) {
                        if (BuildConfig.DEBUG) Log.d("WebSocketService", "Headless ViewModel callbacks successfully registered")
                    } else {
                        android.util.Log.e("WebSocketService", "CRITICAL: Headless ViewModel created but callbacks not registered - this will cause reconnection failures")
                    }
                }
            }

            // Trigger connection if not already connected.
            // CRITICAL FIX: Only trigger if we have a callback (otherwise it will fail)
            val callbackAvailable = getActiveReconnectionCallback() != null
            if (callbackAvailable) {
                headlessViewModel?.initializeWebSocketConnection(homeserverUrl, authToken)
            } else {
                android.util.Log.w("WebSocketService", "Headless ViewModel exists but callback not available yet - will retry after callback registration")
                // Retry after a short delay to allow callbacks to register
                serviceScope.launch {
                    delay(500)
                    val callbackNow = getActiveReconnectionCallback()
                    if (callbackNow != null) {
                        android.util.Log.i("WebSocketService", "Headless ViewModel callback now available - initializing WebSocket connection")
                        headlessViewModel?.initializeWebSocketConnection(homeserverUrl, authToken)
                    } else {
                        android.util.Log.e("WebSocketService", "Headless ViewModel callback still not available after delay - connection will fail")
                    }
                }
            }
        }
        
        /**
         * STEP 1.1: Check if primary callbacks are registered
         * Returns true if all primary callbacks (reconnection, offline mode, activity log) are registered
         * This is useful for debugging and health checks
         */
        fun hasPrimaryCallbacks(): Boolean {
            synchronized(callbacksLock) {
                return primaryReconnectionCallback != null &&
                       primaryOfflineModeCallback != null &&
                       primaryActivityLogCallback != null
            }
        }
        
        /**
         * STEP 1.1: Get primary callback status for debugging
         * Returns a map indicating which callbacks are registered
         */
        fun getPrimaryCallbackStatus(): Map<String, Boolean> {
            synchronized(callbacksLock) {
                return mapOf(
                    "primaryViewModelId" to (primaryViewModelId != null),
                    "reconnectionCallback" to (primaryReconnectionCallback != null),
                    "offlineModeCallback" to (primaryOfflineModeCallback != null),
                    "activityLogCallback" to (primaryActivityLogCallback != null)
                )
            }
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
            synchronized(callbacksLock) {
                val primaryId = primaryViewModelId
                
                // Check if primary ID is set
                if (primaryId == null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 3.1 - Primary health check: No primary ViewModel ID set")
                    return false
                }
                
                // Check if primary ViewModel is still registered (not destroyed)
                val isRegistered = registeredViewModels.containsKey(primaryId)
                if (!isRegistered) {
                    android.util.Log.w("WebSocketService", "STEP 3.1 - Primary health check: Primary ViewModel $primaryId is not registered (was destroyed)")
                    return false
                }
                
                // Check if primary callbacks are still valid
                val hasCallbacks = primaryReconnectionCallback != null &&
                                  primaryOfflineModeCallback != null &&
                                  primaryActivityLogCallback != null
                
                if (!hasCallbacks) {
                    android.util.Log.w("WebSocketService", "STEP 3.1 - Primary health check: Primary ViewModel $primaryId is registered but callbacks are missing")
                    return false
                }
                
                // All checks passed - primary is alive and healthy
                return true
            }
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
            synchronized(callbacksLock) {
                val existing = registeredViewModels[viewModelId]
                if (existing != null) {
                    // Already registered - update if primary status changed
                    if (existing.isPrimary != isPrimary) {
                        registeredViewModels[viewModelId] = existing.copy(isPrimary = isPrimary)
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 2.1 - Updated ViewModel $viewModelId primary status: ${existing.isPrimary} -> $isPrimary")
                        
                        // Update primary tracking if this became primary
                        if (isPrimary) {
                            primaryViewModelId = viewModelId
                        } else if (existing.isPrimary && primaryViewModelId == viewModelId) {
                            // Was primary, now secondary - clear primary tracking
                            primaryViewModelId = null
                        }
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 2.1 - ViewModel $viewModelId already registered with same primary status")
                    }
                    return true
                }
                
                // New registration
                registeredViewModels[viewModelId] = ViewModelInfo(
                    viewModelId = viewModelId,
                    isPrimary = isPrimary
                )
                
                // Update primary tracking if this is the primary instance
                if (isPrimary) {
                    primaryViewModelId = viewModelId
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 2.1 - Registered primary ViewModel: $viewModelId")
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 2.1 - Registered secondary ViewModel: $viewModelId")
                }
                
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 2.1 - Total registered ViewModels: ${registeredViewModels.size}")
                return true
            }
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
            synchronized(callbacksLock) {
                val removed = registeredViewModels.remove(viewModelId)
                if (removed != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 2.2 - Unregistered ViewModel: $viewModelId (wasPrimary=${removed.isPrimary})")
                    
                    // STEP 2.2: If this was the primary, attempt to promote another ViewModel
                    if (removed.isPrimary && primaryViewModelId == viewModelId) {
                        android.util.Log.i("WebSocketService", "STEP 2.2 - Primary ViewModel $viewModelId destroyed - attempting automatic promotion")
                        primaryViewModelId = null
                        attemptPrimaryPromotion("primary_destroyed")
                    }
                    
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 2.2 - Remaining registered ViewModels: ${registeredViewModels.size}")
                    return true
                } else {
                    android.util.Log.w("WebSocketService", "STEP 2.2 - Attempted to unregister unknown ViewModel: $viewModelId")
                    return false
                }
            }
        }
        
        /**
         * STEP 2.1: Get list of registered ViewModel IDs
         * Returns all ViewModels that are currently registered (alive)
         */
        fun getRegisteredViewModelIds(): List<String> {
            synchronized(callbacksLock) {
                return registeredViewModels.keys.toList()
            }
        }
        
        /**
         * STEP 2.1: Get list of registered ViewModels (for primary promotion)
         * Returns ViewModelInfo for all registered ViewModels
         */
        fun getRegisteredViewModelInfos(): List<ViewModelInfo> {
            synchronized(callbacksLock) {
                return registeredViewModels.values.toList()
            }
        }
        
        /**
         * STEP 2.1: Check if a ViewModel is registered (alive)
         */
        fun isViewModelRegistered(viewModelId: String): Boolean {
            synchronized(callbacksLock) {
                return registeredViewModels.containsKey(viewModelId)
            }
        }
        
        /**
         * STEP 2.2/3.3: Attempt to promote a secondary ViewModel to primary
         * This is called when primary is destroyed or detected as dead
         * 
         * @param reason Reason for promotion (for logging)
         */
        private fun attemptPrimaryPromotion(reason: String) {
            synchronized(callbacksLock) {
                // Find another ViewModel to promote to primary
                val remainingViewModels = registeredViewModels.values.toList()
                if (remainingViewModels.isNotEmpty()) {
                    // Prefer MainActivity ViewModel (AppViewModel_0) if available, otherwise use first available
                    val candidateToPromote = remainingViewModels.firstOrNull { 
                        it.viewModelId.startsWith("AppViewModel_0") 
                    } ?: remainingViewModels.first()
                    
                    android.util.Log.i("WebSocketService", "STEP 3.3 - Promoting ViewModel ${candidateToPromote.viewModelId} to primary (reason: $reason)")
                    
                    // Update registration to mark as primary
                    registeredViewModels[candidateToPromote.viewModelId] = candidateToPromote.copy(isPrimary = true)
                    primaryViewModelId = candidateToPromote.viewModelId
                    
                    // Notify the promoted ViewModel
                    // Find the AppViewModel instance from registered receive callbacks
                    val promotedViewModel = webSocketReceiveCallbacks.firstOrNull { 
                        it.first == candidateToPromote.viewModelId 
                    }?.second
                    
                    if (promotedViewModel != null) {
                        try {
                            android.util.Log.i("WebSocketService", "STEP 3.3 - Notifying ViewModel ${candidateToPromote.viewModelId} of promotion to primary")
                            promotedViewModel.onPromotedToPrimary()
                        } catch (e: Exception) {
                            android.util.Log.e("WebSocketService", "STEP 3.3 - Error notifying ViewModel of promotion: ${e.message}", e)
                        }
                    } else {
                        android.util.Log.w("WebSocketService", "STEP 3.3 - Promoted ViewModel ${candidateToPromote.viewModelId} not found in receive callbacks - will register callbacks when it attaches")
                    }
                } else {
                    android.util.Log.w("WebSocketService", "STEP 3.3 - No remaining ViewModels to promote (reason: $reason) - primary callbacks remain in service for next ViewModel")
                    // Note: Primary callbacks are NOT cleared here - they remain in service (Step 1.3)
                    // This allows callbacks to work even after primary is destroyed
                    // The next MainActivity launch will automatically become primary when it calls markAsPrimaryInstance()
                }
            }
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
         * Get the active clear cache callback (primary only)
         * Internal helper to ensure we use primary callback when available
         */
        private fun getActiveClearCacheCallback(): (() -> Unit)? {
            synchronized(callbacksLock) {
                return primaryClearCacheCallback
            }
        }
        
        /**
         * STEP 1.3: Safely invoke reconnection callback with error handling and logging
         * 
         * This method uses stored callbacks (not AppViewModel references), allowing callbacks
         * to work even if the primary AppViewModel is destroyed. The callback reads credentials
         * from SharedPreferences and finds registered ViewModels to handle reconnection.
         * 
         * @param reason Reason for reconnection
         * @param logIfMissing Whether to log a warning if callback is missing (default: true)
         */
        /**
         * REFACTORING: Service now handles reconnection directly
         * No longer needs ViewModel callbacks - service reads credentials from SharedPreferences
         * and uses any available ViewModel (preferably primary) or creates headless if needed
         */
        private fun invokeReconnectionCallback(reason: String, lastReceivedId: Int = 0, logIfMissing: Boolean = true) {
            val serviceInstance = instance ?: run {
                if (logIfMissing) {
                    android.util.Log.w("WebSocketService", "Service instance not available - cannot reconnect: $reason")
                }
                return
            }
            
            // Service handles reconnection directly - read credentials from SharedPreferences
            serviceScope.launch {
                try {
                    val prefs = serviceInstance.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    val homeserverUrl = prefs.getString("homeserver_url", "") ?: ""
                    val authToken = prefs.getString("gomuks_auth_token", "") ?: ""
                    
                    if (homeserverUrl.isEmpty() || authToken.isEmpty()) {
                        android.util.Log.w("WebSocketService", "Cannot reconnect - missing credentials in SharedPreferences")
                        return@launch
                    }
                    
                    android.util.Log.i("WebSocketService", "Service handling reconnection directly: $reason")
                    
                    // CRITICAL FIX: Prefer primary ViewModel when reconnecting
                    // This ensures the correct app instance handles reconnection
                    // If multiple app instances exist, the primary one should reconnect
                    val registeredViewModels = getRegisteredViewModels()
                    val viewModelToUse = if (primaryViewModelId != null) {
                        // Prefer primary ViewModel if available
                        synchronized(callbacksLock) {
                            webSocketReceiveCallbacks.firstOrNull { it.first == primaryViewModelId }?.second
                        } ?: registeredViewModels.firstOrNull() ?: headlessViewModel
                    } else {
                        // No primary - use any registered ViewModel or headless
                        registeredViewModels.firstOrNull() ?: headlessViewModel
                    }
                    
                    // Log which ViewModel is being used for reconnection
                    val viewModelId = if (viewModelToUse != null) {
                        synchronized(callbacksLock) {
                            webSocketReceiveCallbacks.firstOrNull { it.second == viewModelToUse }?.first
                        } ?: if (viewModelToUse == headlessViewModel) "headless" else "unknown"
                    } else {
                        "none"
                    }
                    android.util.Log.i("WebSocketService", "Reconnecting using ViewModel: $viewModelId (primary: $primaryViewModelId, total registered: ${registeredViewModels.size})")
                    
                    // last_received_id: prefer explicit param from reconnection job, then Reconnecting state,
                    // then prefs. Never drop to 0 after clearWebSocket() left state Disconnected or the
                    // next connect would omit last_received_event and full resync stalls reconnect.
                    val resolvedLastReceivedId = when {
                        lastReceivedId != 0 -> lastReceivedId
                        serviceInstance.connectionState.getLastReceivedRequestId() != 0 ->
                            serviceInstance.connectionState.getLastReceivedRequestId()
                        else -> getLastReceivedRequestId(serviceInstance.applicationContext)
                    }
                    if (resolvedLastReceivedId != 0 && BuildConfig.DEBUG) {
                        android.util.Log.d("WebSocketService", "invokeReconnectionCallback: using last_received_id=$resolvedLastReceivedId for connectWebSocket")
                    }
                    
                    // Reconnection: pass run_id and last_received_event in URL
                    connectWebSocket(homeserverUrl, authToken, viewModelToUse, reason, resolvedLastReceivedId, isReconnection = true)
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
            val callback = getActiveOfflineModeCallback()
            if (callback != null) {
                try {
                    callback.invoke(isOffline)
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 1.3 - Offline mode callback invoked: isOffline=$isOffline")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "STEP 1.3 - Error invoking offline mode callback: ${e.message}", e)
                }
            } else {
                if (logIfMissing) {
                    android.util.Log.w("WebSocketService", "STEP 1.3 - Offline mode callback not available - cannot notify offline mode change: isOffline=$isOffline")
                }
            }
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
            // Try primary callback first
            val callback = primaryActivityLogCallback ?: activityLogCallback
            if (callback != null) {
                try {
                    callback.invoke(event, networkType)
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "STEP 1.3 - Activity log callback invoked: event=$event")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "STEP 1.3 - Error invoking activity log callback: ${e.message}", e)
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
         * Show a short-lived foreground notification message indicating that
         * buffered sync_complete messages are being processed on resume.
         *
         * Only used in release builds; debug builds already show a Toast.
         */
        fun showBatchProcessingStatus(pendingCount: Int) {
            if (BuildConfig.DEBUG) return
            instance?.let { serviceInstance ->
                serviceInstance.showBatchProcessingStatus(pendingCount)
            }
        }

        /**
         * Clear any temporary batch-processing notification text and restore
         * the normal connection-status foreground notification in release builds.
         */
        fun clearBatchProcessingStatus() {
            if (BuildConfig.DEBUG) return
            instance?.clearBatchProcessingStatus()
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
            instance?.pingJob = serviceScope.launch {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Ping loop started - first ping in 15s")
                // First ping 15 seconds after websocket connect
                delay(PING_INTERVAL_MS)
                
                while (isActive) {
                    val serviceInstance = instance ?: break
                    
                    if (!serviceInstance.connectionState.isReady() || !serviceInstance.isCurrentlyConnected) {
                        delay(PING_INTERVAL_MS)
                        continue
                    }
                    
                    // Check 60s message timeout - if no message for 60s, connection is stale
                    val timeSinceLastMessage = System.currentTimeMillis() - serviceInstance.lastMessageReceivedTimestamp
                    if (timeSinceLastMessage >= MESSAGE_TIMEOUT_MS) {
                        android.util.Log.w("WebSocketService", "No message received for ${timeSinceLastMessage}ms (>= ${MESSAGE_TIMEOUT_MS}ms) - reconnecting")
                        logActivity("Message Timeout - Reconnecting", serviceInstance.currentNetworkType.name)
                        clearWebSocket("No message for 60 seconds - connection stale")
                        scheduleReconnection("60 second message timeout")
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
        /**
         * Set the clear cache callback for the primary ViewModel
         * Called when WebSocket connects/reconnects to clear all timeline caches
         */
        fun setPrimaryClearCacheCallback(viewModelId: String, callback: () -> Unit): Boolean {
            synchronized(callbacksLock) {
                // Check if another instance is already registered as primary
                if (primaryViewModelId != null && primaryViewModelId != viewModelId) {
                    android.util.Log.w("WebSocketService", "setPrimaryClearCacheCallback: Another instance ($primaryViewModelId) is already registered as primary. Rejecting registration from $viewModelId")
                    return false
                }
                
                // Same instance re-registering - allow it (might be reconnecting after being cleared)
                if (primaryViewModelId == viewModelId) {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setPrimaryClearCacheCallback: Primary instance $viewModelId re-registering callback")
                } else {
                    // New primary instance
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setPrimaryClearCacheCallback: Registering $viewModelId as primary instance")
                    primaryViewModelId = viewModelId
                }
                
                primaryClearCacheCallback = callback
                
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "setPrimaryClearCacheCallback: Successfully registered primary clear cache callback for $viewModelId")
                
                return true
            }
        }
        
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
            serviceInstance.isCurrentlyConnected = true
            serviceInstance.consecutivePingTimeouts = 0
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network marked as healthy")
        }
    
        /**
         * Trigger reconnection check if needed
         * This can be called from FCM or other external triggers
         */
        fun triggerBackendHealthCheck() {
            val serviceInstance = instance ?: return
            serviceScope.launch {
                try {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "triggerBackendHealthCheck called - checking connection state")
                    // Skip HTTP health check - if WebSocket is not connected, trigger reconnection
                    if (!serviceInstance.connectionState.isReady() || serviceInstance.webSocket == null) {
                        android.util.Log.w("WebSocketService", "WebSocket not connected - triggering reconnection")
                        triggerReconnectionFromExternal("External trigger - WebSocket not connected")
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
        fun triggerReconnection(reason: String) {
            android.util.Log.i("WebSocketService", "Triggering WebSocket reconnection: $reason")
            val serviceInstance = instance ?: return
            
            // Get last received request ID for reconnection
            val lastReceivedId = getLastReceivedRequestId(serviceInstance.applicationContext)
            
            // Only trigger reconnection if not already reconnecting
            if (!serviceInstance.connectionState.isReconnecting() && !serviceInstance.isReconnecting) {
                // Clear current connection
                clearWebSocket("Reconnecting: $reason")
                
                // Transition to RECONNECTING state
                updateConnectionState(WebSocketState.Reconnecting(
                    backoffDelayMs = 1000L,
                    attemptCount = 0,
                    lastReceivedRequestId = lastReceivedId
                ))
                
                // Schedule reconnection attempt
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
            
            // If already connected/initializing (but not Ready), close the old connection first (for reconnection)
            if (serviceInstance.connectionState.isConnected() || 
                serviceInstance.connectionState.isInitializing()) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Replacing existing WebSocket connection (reconnection)")
                serviceInstance.webSocket?.close(1000, "Reconnecting")
            }
            
            // Connection is marked good when WebSocket connects - we don't wait for run_id or init_complete
            updateConnectionState(WebSocketState.Ready)
            serviceInstance.webSocket = webSocket
            serviceInstance.connectionStartTime = System.currentTimeMillis()
            serviceInstance.lastMessageReceivedTimestamp = System.currentTimeMillis()
            serviceInstance.lastPongTimestamp = SystemClock.elapsedRealtime()
            
            // Clear connection flag - connection completed successfully
            synchronized(serviceInstance.reconnectionLock) {
                serviceInstance.isConnecting = false
            }
            resetReconnectionState()
            
            // Ensure network connectivity is marked as available when WebSocket connects
            serviceInstance.isCurrentlyConnected = true
            
            val actualNetworkType = serviceInstance.getNetworkTypeFromCapabilities()
            serviceInstance.currentNetworkType = actualNetworkType
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network type set to: $actualNetworkType")
            
            // Start ping loop: first ping 15s after connect, then every 15s
            if (!serviceInstance.pingLoopStarted) {
                serviceInstance.pingLoopStarted = true
                serviceInstance.hasEverReachedReadyState = true
                android.util.Log.i("WebSocketService", "WebSocket connected - starting ping loop (first ping in 15s)")
                logActivity("WebSocket Connected", actualNetworkType.name)
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
            traceToggle("clearWebSocket")
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
            updateConnectionState(WebSocketState.Disconnected)
            serviceInstance.isCurrentlyConnected = false
            
            // CRITICAL FIX: Reset reconnection state when connection fails during reconnection
            // This allows new reconnection attempts when network becomes available again
            if (serviceInstance.isReconnecting) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearWebSocket() called during reconnection - resetting isReconnecting flag")
                serviceInstance.isReconnecting = false
                // Cancel any pending reconnection job
                serviceInstance.reconnectionJob?.cancel()
                serviceInstance.reconnectionJob = null
            }
            
            // Clear connection flag - connection attempt failed or was cleared
            synchronized(serviceInstance.reconnectionLock) {
                serviceInstance.isConnecting = false
            }
            
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
            serviceInstance.waitingForInitComplete = false
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
                    serviceInstance.isReconnecting = false
                    serviceInstance.reconnectionJob?.cancel()
                    serviceInstance.reconnectionJob = null
                }
                
                scheduleReconnection("WebSocket closed (code=$code): $reason")
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
        fun getConnectionState(): WebSocketState? {
            return instance?.connectionState
        }
        
        /**
         * Check if service is currently reconnecting or connecting
         * Used by health check workers to avoid redundant reconnection attempts
         */
        fun isReconnectingOrConnecting(): Boolean {
            val serviceInstance = instance ?: return false
            val state = serviceInstance.connectionState
            return state.isReconnecting() || state.isConnecting() || serviceInstance.isReconnecting
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
            val hasCallback = getActiveReconnectionCallback() != null
            
            // Stuck if in CONNECTING/RECONNECTING state but no callback to handle recovery
            if ((state.isConnecting() || state.isReconnecting()) && !hasCallback) {
                return true
            }
            
            // Also check if waiting for init_complete for too long
            if (state.isConnecting() && serviceInstance.waitingForInitComplete) {
                val timeSinceConnect = if (serviceInstance.connectionStartTime > 0) {
                    System.currentTimeMillis() - serviceInstance.connectionStartTime
                } else {
                    0L
                }
                // Stuck if waiting >20 seconds for init_complete (timeout is 15s, so 20s means timeout failed)
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
            return getActiveReconnectionCallback() != null &&
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
                serviceInstance.connectionState.isReconnecting() &&
                System.currentTimeMillis() - serviceInstance.lastReconnectionTime > 60_000) {
                android.util.Log.w("WebSocketService", "CORRUPTION: Stuck in reconnecting state for >60s - recovering")
                serviceInstance.isReconnecting = false
                updateConnectionState(WebSocketState.Disconnected)
                corruptionDetected = true
            }
            
            // 2. Check for inconsistent connection state (WebSocket vs state mismatch)
            val hasWebSocket = serviceInstance.webSocket != null
            val isReady = serviceInstance.connectionState.isReady()
            
            if (hasWebSocket && !isReady && !serviceInstance.connectionState.isInitializing() && !serviceInstance.connectionState.isConnected()) {
                android.util.Log.w("WebSocketService", "CORRUPTION: WebSocket exists but state is not active - recovering")
                // Don't auto-recover - let normal flow handle it
                corruptionDetected = true
            } else if (!hasWebSocket && isReady) {
                android.util.Log.w("WebSocketService", "CORRUPTION: State is READY but no WebSocket - recovering")
                updateConnectionState(WebSocketState.Disconnected)
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
            
            // Reset all state
            serviceInstance.isReconnecting = false
            serviceInstance.isCurrentlyConnected = false
            updateConnectionState(WebSocketState.Disconnected)
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
        private fun updateConnectionState(newState: WebSocketState) {
            val serviceInstance = instance
            if (serviceInstance != null) {
                val oldState = serviceInstance.connectionState
                serviceInstance.connectionState = newState
                android.util.Log.i("WebSocketService", "State transition: $oldState → $newState")
            }
            _connectionStateFlow.value = newState
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connection state updated: $newState")
        }
        
        /**
         * Helper functions for state checks (for cleaner code)
         * Made public so they can be used from other files (e.g., NetworkUtils)
         */
        fun WebSocketState.isDisconnected(): Boolean = this is WebSocketState.Disconnected
        fun WebSocketState.isReconnecting(): Boolean = this is WebSocketState.Reconnecting
        fun WebSocketState.isConnecting(): Boolean = this is WebSocketState.Connecting
        fun WebSocketState.isConnected(): Boolean = this is WebSocketState.Connected
        fun WebSocketState.isInitializing(): Boolean = this is WebSocketState.Initializing
        fun WebSocketState.isReady(): Boolean = this is WebSocketState.Ready
        
        /**
         * Check if connection is active (CONNECTING, CONNECTED, INITIALIZING, or READY)
         */
        fun WebSocketState.isActive(): Boolean = 
            this is WebSocketState.Connecting || 
            this is WebSocketState.Connected || 
            this is WebSocketState.Initializing || 
            this is WebSocketState.Ready
        
        /**
         * Get last received request ID from state (if in Reconnecting state)
         */
        fun WebSocketState.getLastReceivedRequestId(): Int {
            return if (this is WebSocketState.Reconnecting) {
                this.lastReceivedRequestId
            } else {
                0
            }
        }
        
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
         * @deprecated No longer used - we never pass last_received_id on connect/reconnect
         * All timeline caches are cleared on connect/reconnect instead
         */
        @Deprecated("No longer used - caches are cleared on connect/reconnect")
        fun setReconnectionState(lastReceivedId: Int) {
            // No-op - we no longer track last_received_id
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
        fun connectWebSocket(homeserverUrl: String, token: String, appViewModel: AppViewModel? = null, reason: String = "Service-initiated connection", lastReceivedId: Int = 0, isReconnection: Boolean = false) {
            android.util.Log.i("WebSocketService", "connectWebSocket() called - reason: $reason, isReconnection: $isReconnection")
            
            // For now, delegate to NetworkUtils.connectToWebsocket() which will call setWebSocket()
            // Eventually, we'll move the connection logic here
            serviceScope.launch {
                try {
                    // CRITICAL FIX: Wait for service instance to be ready (with timeout)
                    // This prevents race condition where connectWebSocket() is called before onCreate()
                    val serviceInstance = waitForServiceInstance(5_000L) ?: run {
                        android.util.Log.e("WebSocketService", "connectWebSocket() called but service instance is null after waiting 5 seconds")
                        return@launch
                    }
                    
                    // Check if already connected
                    if (isWebSocketConnected()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "connectWebSocket() called but already connected - skipping")
                        return@launch
                    }
                    
                    // FIX #2: Only skip if there's actually a live WebSocket attempt (not just a stale state)
                    // This prevents race conditions where state is Connecting but no actual connection is in progress
                    if (serviceInstance.connectionState.isConnecting() && serviceInstance.webSocket != null) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "connectWebSocket() called but already connecting with active socket - skipping")
                        return@launch
                    }
                    
                    // CRITICAL FIX: Set flag to prevent NetworkMonitor from scheduling duplicate reconnections
                    // This flag is cleared when connection completes (Ready) or fails (Disconnected)
                    synchronized(serviceInstance.reconnectionLock) {
                        if (serviceInstance.isConnecting) {
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "connectWebSocket() called but connection already in progress - skipping")
                            return@launch
                        }
                        serviceInstance.isConnecting = true
                    }
                    
                    // If last_received_id is provided, store it in SharedPreferences for NetworkUtils to read
                    if (lastReceivedId != 0) {
                        updateLastReceivedRequestId(lastReceivedId, serviceInstance.applicationContext)
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Stored last_received_id: $lastReceivedId for reconnection")
                    }
                    
                    // Skip backend health check on initial connect - WebSocket will fail fast if backend is unreachable
                    // and we handle connection failures gracefully with retry logic
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connecting WebSocket directly (skipping health check)")
                    
                    // REFACTORING: Connect websocket - no ViewModel needed, service handles everything
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Calling NetworkUtils.connectToWebsocket()")
                    val client = okhttp3.OkHttpClient.Builder().build()
                    net.vrkknn.andromuks.utils.connectToWebsocket(homeserverUrl, client, token, serviceInstance.applicationContext, appViewModel, reason = reason, isReconnection = isReconnection)
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "NetworkUtils.connectToWebsocket() call completed")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in connectWebSocket()", e)
                    clearWebSocket("Connection error: ${e.message}")
                    // clearWebSocket() already clears isConnecting flag, so no need to do it here
                }
                // Note: isConnecting flag is cleared by:
                // 1. clearWebSocket() if connection fails
                // 2. When transitioning to Ready if connection succeeds
                // No finally block needed - the flag lifecycle is managed by state transitions
            }
        }
        
        /**
         * @deprecated No longer used - we never pass last_received_id on connect/reconnect
         */
        @Deprecated("No longer used - we no longer track last_received_id")
        fun getLastReceivedSyncId(): Int = 0
        
        /**
         * @deprecated No longer used - we never pass last_received_id on connect/reconnect
         */
        @Deprecated("No longer used - we no longer track last_received_id")
        fun updateLastReceivedSyncId(syncId: Int) {
            // No-op
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
            
            if (currentState is WebSocketState.Initializing) {
                val newProcessed = if (processedCount > 0) processedCount else currentState.processedSyncCompleteCount + 1
                val newPending = if (pendingCount > 0) pendingCount else currentState.pendingSyncCompleteCount
                
                // Transition to READY if all sync_completes are processed
                if (newPending > 0 && newProcessed >= newPending) {
                    updateConnectionState(WebSocketState.Ready)
                    android.util.Log.i("WebSocketService", "All sync_completes processed - state transition: INITIALIZING → READY")
                    
                    // Clear connection flag - connection completed successfully
                    synchronized(serviceInstance.reconnectionLock) {
                        serviceInstance.isConnecting = false
                    }
                    
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
                    updateConnectionState(WebSocketState.Initializing(
                        pendingSyncCompleteCount = newPending,
                        processedSyncCompleteCount = newProcessed
                    ))
                }
            } else if (currentState is WebSocketState.Ready) {
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
            if (currentState is WebSocketState.Initializing && currentState.pendingSyncCompleteCount == 0) {
                android.util.Log.i("WebSocketService", "Init_complete received with no pending sync_completes - transitioning to READY immediately")
                updateConnectionState(WebSocketState.Ready)
                
                // Clear connection flag - connection completed successfully
                synchronized(serviceInstance.reconnectionLock) {
                    serviceInstance.isConnecting = false
                }
                
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
         * Mark that at least one sync_complete has been persisted this session.
         * This unlocks the ability to include last_received_event on reconnections.
         */
        /**
         * @deprecated No longer used - we never pass last_received_id on connect/reconnect
         */
        @Deprecated("No longer used - we no longer track last_received_id")
        fun markInitialSyncPersisted() {
            // No-op
        }
        
        /**
         * Clear reconnection state (no-op - we no longer track last_received_id)
         */
        fun clearReconnectionState() {
            // No-op - we no longer track last_received_id
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "clearReconnectionState called (no-op - we no longer track last_received_id)")
        }
        
        /**
         * @deprecated No longer used - we never pass last_received_id on connect/reconnect
         * Returns: (runId from SharedPreferences, 0, false)
         */
        @Deprecated("No longer used - we no longer track last_received_id")
        fun getReconnectionParameters(): Triple<String, Int, Boolean> {
            val runId = getCurrentRunId() // Always read from SharedPreferences
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "getReconnectionParameters: runId='$runId' (no last_received_id)")
            return Triple(runId, 0, false)
        }
        
        /**
         * Reset reconnection state (called on successful connection)
         */
        fun resetReconnectionState() {
            val serviceInstance = instance ?: return
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            serviceInstance.isReconnecting = false
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
        fun onInitCompleteReceived() {
            val serviceInstance = instance ?: return
            onMessageReceived() // Reset 60s message timeout
            
            // Clear all timeline caches on init_complete - all caches are stale
            RoomTimelineCache.clearAll()
            
            val clearCacheCallback = getActiveClearCacheCallback()
            if (clearCacheCallback != null) {
                try {
                    clearCacheCallback.invoke()
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Notified AppViewModel to clear all timeline caches")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error invoking clear cache callback: ${e.message}", e)
                }
            }
            
            logActivity("Init Complete Received", serviceInstance.currentNetworkType.name)
            updateConnectionStatus(true, null, serviceInstance.lastSyncTimestamp)
            serviceInstance.clearInitCompleteFailureNotification()
            
            // AppViewModel receives init_complete via NetworkUtils and handles sync_complete queue
        }
        
        /**
         * Schedule WebSocket reconnection with backend health check
         * RUSH TO HEALTHY: Fast retry with backend health check, no exponential backoff
         */
        fun scheduleReconnection(reason: String) {
            val serviceInstance = instance ?: return
            
            // CRITICAL FIX: Don't schedule reconnection if network is not available
            if (serviceInstance.currentNetworkType == NetworkType.NONE) {
                android.util.Log.w("WebSocketService", "Cannot schedule reconnection - no network available (reason: $reason)")
                serviceInstance.showWebSocketToast("No network - cannot reconnect")
                return
            }
            
            // PHASE 2.1: If reconnection callback is not available, queue the request
            val activeCallback = getActiveReconnectionCallback()
            // REFACTORING: Service handles reconnection directly - no callback needed
            // We still need a ViewModel for NetworkUtils, but we'll get/create one in invokeReconnectionCallback()
            // No need to queue or check for callbacks
            
            // ATOMIC GUARD: Use synchronized lock to prevent parallel reconnection attempts
            synchronized(serviceInstance.reconnectionLock) {
                val currentTime = System.currentTimeMillis()
                
                // Get last received request ID from current state (if in RECONNECTING)
                val currentState = serviceInstance.connectionState
                val lastReceivedId = if (currentState is WebSocketState.Reconnecting) {
                    currentState.lastReceivedRequestId
                } else {
                    getLastReceivedRequestId(serviceInstance.applicationContext)
                }
                
                // CRITICAL FIX: Check if reconnection is stuck and reset if needed
                if (serviceInstance.connectionState.isReconnecting() || serviceInstance.isReconnecting) {
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
                        android.util.Log.w("WebSocketService", "Reconnection stuck for ${timeSinceReconnect}ms - resetting to allow new reconnection attempt: $reason")
                        serviceInstance.isReconnecting = false
                        serviceInstance.reconnectionJob?.cancel()
                        serviceInstance.reconnectionJob = null
                        // Fall through to start new reconnection
                    } else if (timeSinceReconnect > 10_000) {
                        // Reconnection in progress for >10s but <30s - allow if this is a network change
                        // Network changes should be able to interrupt a slow reconnection
                        if (reason.contains("Network") || reason.contains("network")) {
                            android.util.Log.w("WebSocketService", "Reconnection in progress for ${timeSinceReconnect}ms but network changed - interrupting: $reason")
                            serviceInstance.isReconnecting = false
                            serviceInstance.reconnectionJob?.cancel()
                            serviceInstance.reconnectionJob = null
                            // Fall through to start new reconnection
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting (${timeSinceReconnect}ms), dropping redundant request: $reason")
                            return
                        }
                    } else {
                        // Reconnection just started (<10s) - don't interrupt
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting (${timeSinceReconnect}ms), dropping redundant request: $reason")
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
                
                // Calculate exponential backoff (1s → 2s → 4s → 8s → 16s → 32s → 64s → 120s max)
                val backoffDelay = minOf(
                    1000L * (1 shl serviceInstance.reconnectionAttemptCount),
                    120_000L // Max 120 seconds
                )
                
                // Transition to RECONNECTING state with backoff delay and last_received_id
                updateConnectionState(WebSocketState.Reconnecting(
                    backoffDelayMs = backoffDelay,
                    attemptCount = serviceInstance.reconnectionAttemptCount + 1,
                    lastReceivedRequestId = lastReceivedId
                ))
                serviceInstance.lastReconnectionTime = currentTime
                serviceInstance.reconnectionAttemptCount++
                
                android.util.Log.w("WebSocketService", "Scheduling reconnection: $reason")
                
                // PHASE 4.1: Log last close code if available (for debugging)
                val lastCloseCode = serviceInstance.lastCloseCode
                if (lastCloseCode != null) {
                    android.util.Log.i("WebSocketService", "Last WebSocket close code: $lastCloseCode (${serviceInstance.lastCloseReason})")
                }
                
                logActivity("Connecting - $reason", serviceInstance.currentNetworkType.name)
                
                // Show toast for reconnection scheduled
                serviceInstance.showWebSocketToast("Reconnecting: $reason")
                
                serviceInstance.reconnectionJob = serviceScope.launch {
                    try {
                        // Get backoff delay from current state
                        val currentState = serviceInstance.connectionState
                        val backoffDelay = if (currentState is WebSocketState.Reconnecting) {
                            currentState.backoffDelayMs
                        } else {
                            1000L // Default 1 second
                        }
                        
                        // Wait for exponential backoff delay
                        delay(backoffDelay)
                        
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
                        
                        // PHASE 3.3: Validate network before reconnecting (prevents reconnection on captive portals)
                        // Check NET_CAPABILITY_VALIDATED
                        val networkValidated = serviceInstance.waitForNetworkValidation(2000L)
                        if (!networkValidated) {
                            // CRITICAL FIX: If network validation failed and network is NONE, cancel reconnection
                            if (serviceInstance.currentNetworkType == NetworkType.NONE) {
                                android.util.Log.w("WebSocketService", "Network validation failed and network is NONE - cancelling reconnection")
                                serviceInstance.showWebSocketToast("No network - cancelling reconnection")
                                updateConnectionState(WebSocketState.Disconnected)
                                serviceInstance.isReconnecting = false
                                return@launch
                            }
                            
                            android.util.Log.w("WebSocketService", "Network validation timeout or failed - proceeding with reconnection anyway (might be slow network)")
                            logActivity("Network Validation Timeout - Proceeding", serviceInstance.currentNetworkType.name)
                            
                            // Show toast for network validation timeout
                            serviceInstance.showWebSocketToast("Network validation timeout")
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network validated - proceeding with reconnection")
                        }
                        
                        // Skip backend HTTP health check - it's redundant since backend serves both HTTP and WebSocket
                        // If backend is unreachable, WebSocket connection will fail fast
                        
                        // Network validated - proceed with reconnection
                        // BUT: If, in the meantime, another path has already established a
                        // healthy READY connection with a live WebSocket, this job is now
                        // stale and must not touch the state machine at all.
                        val currentStateNow = serviceInstance.connectionState
                        if (currentStateNow.isReady() && serviceInstance.webSocket != null) {
                            android.util.Log.i(
                                "WebSocketService",
                                "Reconnection job: connection already READY with live WebSocket - skipping reconnection (reason=$reason)"
                            )
                            return@launch
                        }
                        
                        // Get last_received_id from current state (may have been updated in retry loop)
                        val finalState = currentStateNow
                        val lastReceivedId = if (finalState is WebSocketState.Reconnecting) {
                            finalState.lastReceivedRequestId
                        } else {
                            getLastReceivedRequestId(serviceInstance.applicationContext)
                        }
                        
                        if (isActive) {
                            // FINAL CHECK: Right before invoking callback, verify we're still not Ready
                            // This prevents race conditions where state changes between the earlier check and now
                            val stateBeforeCallback = serviceInstance.connectionState
                            if (stateBeforeCallback.isReady() && serviceInstance.webSocket != null) {
                                android.util.Log.w(
                                    "WebSocketService",
                                    "Reconnection job: connection became READY before callback - aborting reconnection (reason=$reason)"
                                )
                                return@launch
                            }
                            
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Executing reconnection: $reason (attempt ${serviceInstance.reconnectionAttemptCount}/$MAX_RECONNECTION_ATTEMPTS, last_received_id: $lastReceivedId)")
                            logActivity("Reconnection Attempt - $reason", serviceInstance.currentNetworkType.name)
                            
                            // FIX #1: Don't transition to Connecting here - let setWebSocket() do it when connection actually starts
                            // This prevents premature state transition that causes race conditions with concurrent triggers
                            
                            // PHASE 1.4: Use safe invocation helper with error handling
                            // Pass last_received_id to reconnection callback
                            invokeReconnectionCallback(reason, lastReceivedId)
                        } else {
                            // Job was cancelled (e.g., because a separate successful connection
                            // path called resetReconnectionState()). In that case, do NOT
                            // overwrite whatever the current connection state is (it might
                            // already be READY). Just exit quietly.
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "WebSocketService",
                                    "Reconnection job cancelled before execution, skipping state change (reason=$reason, currentState=$currentStateNow)"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WebSocketService", "Reconnection job failed", e)
                        // CRITICAL FIX: Always reset reconnecting flag on error
                        synchronized(serviceInstance.reconnectionLock) {
                            serviceInstance.isReconnecting = false
                        }
                        // Update notification to show error
                        updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
                    } finally {
                        // CRITICAL FIX: Always reset reconnecting flag when job completes
                        synchronized(serviceInstance.reconnectionLock) {
                            serviceInstance.isReconnecting = false
                        }
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
            if (serviceInstance.connectionState.isReconnecting()) {
                updateConnectionState(WebSocketState.Disconnected)
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
    private var runIdReceivedTime: Long = 0 // Timestamp when run_id was received
    private var messagesReceivedWhileWaitingForInitComplete: Int = 0 // Track messages received to extend timeout
    private var initCompleteTimeoutEndTime: Long = 0 // Track when init_complete timeout will expire (for dynamic extension)
    private var lastPingRequestId: Int = 0
    private var lastPingTimestamp: Long = 0
    private var pingInFlight: Boolean = false // Guard to prevent concurrent pings
    private var isAppVisible = false
    private var initCompleteRetryCount: Int = 0 // Track retry count for exponential backoff
    private var waitingForInitComplete: Boolean = false // Track if we're waiting for init_complete
    private var isReconnectingWithLastReceivedEvent: Boolean = false // Track if we're reconnecting with last_received_event (backend won't send init_complete)
    
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
    private var isCurrentlyConnected = false
    private var currentNetworkType: NetworkType = NetworkType.NONE
    
    // PHASE 3.2: Network type change detection
    private var lastNetworkType: NetworkType = NetworkType.NONE // Track previous network type
    private var lastNetworkIdentity: String? = null // Track previous network identity (SSID for WiFi, null for others)
    private var networkChangeDebounceJob: Job? = null // Debounce rapid network changes
    
    // PHASE 4.1: WebSocket close code tracking
    private var lastCloseCode: Int? = null // Track last WebSocket close code
    private var lastCloseReason: String? = null // Track last WebSocket close reason
    
    // WebSocket connection management
    private var webSocket: WebSocket? = null
    private var connectionState: WebSocketState = WebSocketState.Disconnected
    private var lastPongTimestamp = 0L // Track last pong for heartbeat monitoring
    private var lastMessageReceivedTimestamp = 0L // Reset on ANY message - 60s without = reconnect
    private var connectionStartTime: Long = 0 // Track when WebSocket connection was established (0 = not connected)
    // Reconnection state management
    // run_id is always read from SharedPreferences - not stored in service state
    // NOTE: We no longer track last_received_id - all timeline caches are cleared on connect/reconnect
    
    // Connection health tracking
    private var lastSyncTimestamp: Long = 0
    private var lastKnownLagMs: Long? = null
    private var consecutivePingTimeouts: Int = 0 // Track consecutive ping timeouts for network quality detection
    
    // Reconnection logic state (simplified - no exponential backoff)
    private var reconnectionJob: Job? = null
    
    private var isReconnecting = false // Prevent multiple simultaneous reconnections
    private var isConnecting = false // Track if connectWebSocket() is currently running (prevents NetworkMonitor from triggering duplicate reconnections)
    private var lastReconnectionTime = 0L
    private var reconnectionAttemptCount = 0 // Track reconnection attempts for retry limit
    
    // Atomic lock for preventing parallel reconnection attempts (compare-and-set)
    private val reconnectionLock = Any()
    
    // PHASE 2.1: Pending reconnection queue for when callback is not yet available
    private val pendingReconnectionReasons = mutableListOf<String>()
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
     * 1. Callback health (every 30s) - ensures reconnection callbacks are available
     * 2. State corruption (every 60s) - detects and recovers from state inconsistencies
     * 3. Primary ViewModel health (every 30s) - ensures primary ViewModel is alive, promotes secondary if needed
     * 4. Connection health (every 30s) - detects stuck CONNECTING/RECONNECTING states
     * 5. Notification staleness (every 30s) - ensures notification is updated
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
                    
                    // 2. State corruption check (~every 30s - every 30 iterations)
                    // Detects and recovers from state inconsistencies (WebSocket vs state mismatch, stuck states)
                    stateCorruptionCheckCounter++
                    if (stateCorruptionCheckCounter >= 30) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Unified monitoring: Running state corruption check")
                        WebSocketService.detectAndRecoverStateCorruption()
                        stateCorruptionCheckCounter = 0
                    }
                    
                    // 3. Primary ViewModel health check (every 30s)
                    // STEP 3.2: Ensures primary ViewModel is alive and healthy
                    // Automatically promotes secondary ViewModel if primary is detected as dead
                    val isAlive = isPrimaryAlive()
                    val primaryId = getPrimaryViewModelId()
                    
                    if (!isAlive) {
                        android.util.Log.w("WebSocketService", "STEP 3.2 - Primary health check: Primary ViewModel is NOT alive (primaryId=$primaryId)")
                        
                        // Log detailed status for debugging
                        val registeredCount = getRegisteredViewModelIds().size
                        val callbackStatus = getPrimaryCallbackStatus()
                        android.util.Log.w("WebSocketService", "STEP 3.2 - Health check details: registeredViewModels=$registeredCount, callbackStatus=$callbackStatus")
                        
                        // STEP 3.3: If primary health check fails, automatically promote next available ViewModel
                        if (primaryId != null) {
                            // Primary ID is set but ViewModel is dead - clear it and attempt promotion
                            android.util.Log.i("WebSocketService", "STEP 3.3 - Primary ViewModel $primaryId detected as dead - attempting automatic promotion")
                            synchronized(callbacksLock) {
                                primaryViewModelId = null
                            }
                            Companion.attemptPrimaryPromotion("health_check_failed")
                        } else {
                            // No primary ID set - check if we have ViewModels to promote
                            if (registeredCount > 0) {
                                android.util.Log.i("WebSocketService", "STEP 3.3 - No primary set but $registeredCount ViewModels available - attempting promotion")
                                Companion.attemptPrimaryPromotion("no_primary_set")
                            } else {
                                android.util.Log.w("WebSocketService", "STEP 3.3 - No primary and no ViewModels available - next MainActivity launch will become primary")
                                // Primary callbacks remain in service - next MainActivity will automatically become primary
                            }
                        }
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
                    
                    // Check for stuck CONNECTING state (waiting for init_complete)
                    val isStuckConnecting = when {
                        currentState.isConnecting() && waitingForInitComplete -> {
                            val timeoutActive = initCompleteTimeoutJob?.isActive == true
                            val timeSinceConnect = if (connectionStartTime > 0) currentTime - connectionStartTime else 0
                            // Stuck if timeout is active but exceeded, or timeout job is missing but we've been waiting too long
                            // Use base timeout + margin for checking
                            (timeoutActive && timeSinceConnect > INIT_COMPLETE_TIMEOUT_MS_BASE + 5000) ||
                            (!timeoutActive && timeSinceConnect > INIT_COMPLETE_TIMEOUT_MS_BASE + 10000)
                        }
                        else -> false
                    }
                    
                    // Check for stuck RECONNECTING state
                    val isStuckReconnecting = when {
                        currentState.isReconnecting() && isReconnecting -> {
                            val timeSinceReconnect = if (lastReconnectionTime > 0) currentTime - lastReconnectionTime else 0
                            timeSinceReconnect > 60_000 // Stuck for >60s
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
                                isReconnecting = false
                                reconnectionJob?.cancel()
                                reconnectionJob = null
                            }
                        }
                        
                        // Schedule new reconnection
                        scheduleReconnection("Health check recovery from stuck state: $stuckReason")
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
        
        // PHASE 1.2: Check active callback (primary or legacy)
        val activeReconnectionCallback = getActiveReconnectionCallback()
        val isConnected = connectionState.isReady()
        
        // Verify primaryReconnectionCallback if connection is not READY
        if (!isConnected && activeReconnectionCallback == null) {
            android.util.Log.w("WebSocketService", "Callback health check: reconnection callback is null and connection is not CONNECTED")
            logActivity("Callback Missing - Waiting for AppViewModel", currentNetworkType.name)
            
            // Update notification to show "Waiting for app..."
            updateConnectionStatus(
                isConnected = false,
                lagMs = lastKnownLagMs,
                lastSyncTimestamp = lastSyncTimestamp
            )
            // Headless recovery: recreate a primary ViewModel when callbacks are missing.
            ensureHeadlessPrimary(applicationContext, "Callback missing during health check")
        }
        
        // Check all primary callbacks for completeness (for diagnostics)
        val hasPrimaryReconnection = primaryReconnectionCallback != null
        val hasPrimaryOfflineMode = primaryOfflineModeCallback != null
        val hasPrimaryActivityLog = primaryActivityLogCallback != null
        
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
                // CRITICAL FIX: Debounce rapid network changes to avoid excessive reconnections
                networkChangeDebounceJob?.cancel()
                networkChangeDebounceJob = serviceScope.launch {
                    delay(NETWORK_CHANGE_DEBOUNCE_MS)
                    
                    android.util.Log.i("WebSocketService", "Network available: $networkType - checking if reconnection needed")
                    val newNetworkType = convertNetworkType(networkType)
                    val previousNetworkType = lastNetworkType
                    val hasCallback = getActiveReconnectionCallback() != null
                    
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
                    
                    // Wait for network validation first (5 seconds max)
                    // CRITICAL FIX: If we're disconnected, use fallback validation with exponential backoff
                    val networkValidated = waitForNetworkValidation(2000L)
                    if (!networkValidated) {
                        if (connectionState.isDisconnected()) {
                            // We're disconnected - try fallback validation with exponential backoff
                            android.util.Log.w("WebSocketService", "Network available but Android validation timeout - trying fallback backend health check")
                            val fallbackValidated = tryFallbackNetworkValidation()
                            if (fallbackValidated) {
                                android.util.Log.i("WebSocketService", "Fallback validation succeeded - proceeding with reconnection")
                                logActivity("Network Available - Reconnecting (fallback validated)", currentNetworkType.name)
                                // Reset backoff on success
                                fallbackBackoffDelayMs = 1000L
                                scheduleReconnection("Network available: $networkType (fallback validated)")
                            } else {
                                android.util.Log.w("WebSocketService", "Fallback validation failed - will retry with exponential backoff (next: ${fallbackBackoffDelayMs}ms)")
                                showWebSocketToast("Network validation failed - retrying...")
                                // Don't reconnect yet - will retry on next network event with increased backoff
                            }
                            return@launch
                        } else {
                            // Connected/connecting/reconnecting - validation failed/timeout
                            if (connectionState.isReconnecting()) {
                                // CRITICAL FIX: If we're in RECONNECTING state but validation failed, don't schedule reconnection yet
                                // Wait for validation to succeed first - will retry on next network event
                                android.util.Log.w("WebSocketService", "Network available but validation failed/timeout - waiting for validation (state: RECONNECTING)")
                                logActivity("Network Available - Waiting for Validation", currentNetworkType.name)
                                showWebSocketToast("Network validation timeout - waiting")
                                // Don't schedule reconnection - will retry when validation succeeds
                            } else {
                                // Connected/connecting - wait for validation
                                android.util.Log.w("WebSocketService", "Network available but validation failed/timeout - not reconnecting yet (state: $connectionState)")
                                showWebSocketToast("Network not validated - waiting")
                            }
                            return@launch
                        }
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
                        val alreadyConnecting = synchronized(this@WebSocketService.reconnectionLock) {
                            this@WebSocketService.isConnecting
                        }
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
                        scheduleReconnection("Network validated: $networkType")
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
                            scheduleReconnection("Network change: Recovering from stuck CONNECTING state")
                        } else {
                            // Already connecting and not stuck - wait for init_complete
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("WebSocketService", "Network validated but WebSocket already connecting (${timeSinceConnect}ms, timeouts active: $hasAnyActiveTimeout) - waiting for init_complete")
                            }
                        }
                    } else if (connectionState.isReconnecting()) {
                        // FIX #2: Check if there's an active reconnection job
                        // If not, we're in a dead reconnection state and need to restart
                        val hasActiveJob = synchronized(reconnectionLock) {
                            reconnectionJob?.isActive == true
                        }
                        
                        if (!hasActiveJob) {
                            // Dead reconnection state - always restart
                            android.util.Log.w("WebSocketService", "Network available while in RECONNECTING state with no active job - restarting reconnection")
                            logActivity("Network Available - Restarting Reconnection", currentNetworkType.name)
                            synchronized(reconnectionLock) {
                                isReconnecting = false
                                reconnectionJob = null
                            }
                            // CRITICAL FIX: Reset connection state to Disconnected before scheduling reconnection
                            // This ensures scheduleReconnection starts from a clean state
                            updateConnectionState(WebSocketState.Disconnected)
                            scheduleReconnection("Network returned, no active reconnection job")
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
                            isReconnecting = false
                        }
                        
                        // Get last received request ID from current state
                        val currentState = connectionState
                        val lastReceivedId = if (currentState is WebSocketState.Reconnecting) {
                            currentState.lastReceivedRequestId
                        } else {
                            getLastReceivedRequestId(applicationContext)
                        }
                        
                        // FIX #1: Don't transition to Connecting here - let setWebSocket() do it when connection actually starts
                        // Start reconnection immediately (no backoff)
                        invokeReconnectionCallback("Network available: $networkType (validated)", lastReceivedId)
                    } else if (previousNetworkType != newNetworkType && previousNetworkType != NetworkType.NONE) {
                        // Network type changed - USER REQUIREMENT: Always force reconnect
                        android.util.Log.w("WebSocketService", "Network type changed while connected - forcing reconnection ($previousNetworkType → $newNetworkType)")
                        clearWebSocket("Network type changed: $previousNetworkType → $newNetworkType")
                        scheduleReconnection("Network type changed: $previousNetworkType → $newNetworkType")
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
                
                // Network lost - mark connection as unavailable
                isCurrentlyConnected = false
                
                // Clear WebSocket since there's no network
                clearWebSocket("Network lost - no network available")
                
                // USER REQUIREMENT: If Android believes network was lost, we return to RECONNECTING
                // WiFi network name is irrelevant - if Android says network lost, go to RECONNECTING
                updateConnectionState(WebSocketState.Reconnecting(
                    backoffDelayMs = 1000L,
                    attemptCount = 0,
                    lastReceivedRequestId = lastReceivedId
                ))
                updateConnectionStatus(false, lastKnownLagMs, lastSyncTimestamp)
            },
            onNetworkTypeChanged = { previousType, newType ->
                val previousNetworkType = lastNetworkType
                val newNetworkType = convertNetworkType(newType)
                val hasCallback = getActiveReconnectionCallback() != null
                
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
                    isReconnecting = false
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
                if (!hasCallback) {
                    ensureHeadlessPrimary(applicationContext, "Network type change - callback missing")
                }
                scheduleReconnection("Network type changed: $previousNetworkType → $newNetworkType")
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
            
            // Cancel all coroutine jobs with timeout
            val jobs = listOf(
                pingJob,
                pongTimeoutJob,
                reconnectionJob,
                unifiedMonitoringJob
            )
            
            jobs.forEach { job ->
                job?.cancel()
            }
            
            // Clear WebSocket connection immediately
            webSocket?.close(1000, "Service stopped")
            webSocket = null
            
            // Reset connection state
            updateConnectionState(WebSocketState.Disconnected)
            isReconnecting = false
            isCurrentlyConnected = false
            
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
        
        // Don't send pings if network is unavailable
        if (!isCurrentlyConnected) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Skipping ping - network unavailable")
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
        pongTimeoutJob = WebSocketService.serviceScope.launch {
            delay(PONG_CLEAR_INFLIGHT_MS)
            if (!connectionState.isReady() || !isCurrentlyConnected) return@launch
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
                    if (!connectionState.isConnecting() && !connectionState.isConnected()) {
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
                    scheduleReconnection("Run ID timeout - reconnecting")
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
        if (!waitingForInitComplete) {
            return // Not waiting for init_complete, no need to extend
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
                    if (!waitingForInitComplete) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete timeout expired but already received - ignoring")
                        return@launch
                    }
                    
                    // Check if connection is still active
                    if (!connectionState.isConnecting() && !connectionState.isConnected()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete timeout expired but connection not active - ignoring")
                        return@launch
                    }
                    
                    // Timeout expired - handle it
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
            isReconnecting || reconnectionJob?.isActive == true
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
                        isReconnecting = false
                        reconnectionJob?.cancel()
                        reconnectionJob = null
                    }
                    
                    // Schedule reconnection
                    scheduleReconnection("Hard timeout recovery from stuck Connecting state")
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
     * WebSocket connection state machine
     */
    /**
     * WebSocket connection state machine
     * 
     * State transitions:
     * DISCONNECTED/RECONNECTING → CONNECTING (when network validated + backend health check OK)
     * CONNECTING → CONNECTED (when WebSocket opened + run_id received)
     * CONNECTED → INITIALIZING (when init_complete received)
     * INITIALIZING → READY (when all sync_completes processed)
     * READY → RECONNECTING (when network lost OR ping timeout)
     */
    sealed class WebSocketState {
        /**
         * DISCONNECTED: No connection, not attempting to connect
         * Entry: Initial state, connection closed
         * Exit: Network validated + backend health check OK → CONNECTING
         */
        object Disconnected : WebSocketState()
        
        /**
         * RECONNECTING: Waiting to reconnect (exponential backoff up to 120s)
         * Entry: Network lost, ping timeout, connection error
         * Exit: Network validated + backend health check OK → CONNECTING
         * 
         * @param backoffDelayMs Current backoff delay (1s → 2s → 4s → 8s → 16s → 32s → 64s → 120s max)
         * @param attemptCount Number of reconnection attempts
         * @param lastReceivedRequestId Last received request_id to pass on reconnection
         */
        data class Reconnecting(
            val backoffDelayMs: Long = 1000L,
            val attemptCount: Int = 0,
            val lastReceivedRequestId: Int = 0
        ) : WebSocketState()
        
        /**
         * CONNECTING: Actively establishing WebSocket connection
         * Entry: Network validated + backend health check OK
         * Exit: WebSocket opened + run_id received → CONNECTED
         */
        object Connecting : WebSocketState()
        
        /**
         * CONNECTED: WebSocket connected and run_id received
         * Entry: WebSocket opened + run_id message received
         * Exit: init_complete received → INITIALIZING
         */
        object Connected : WebSocketState()
        
        /**
         * INITIALIZING: Receiving init messages (sync_completes)
         * Entry: init_complete received
         * Exit: All sync_completes processed → READY
         * 
         * @param pendingSyncCompleteCount Total number of sync_complete messages to process
         * @param processedSyncCompleteCount Number of sync_complete messages processed so far
         */
        data class Initializing(
            val pendingSyncCompleteCount: Int = 0,
            val processedSyncCompleteCount: Int = 0
        ) : WebSocketState()
        
        /**
         * READY: Fully initialized and operational
         * Entry: All sync_completes processed
         * Exit: Network lost OR ping timeout → RECONNECTING
         */
        object Ready : WebSocketState()
    }
    
    // Legacy enum for backward compatibility during migration
    @Deprecated("Use WebSocketState sealed class instead", ReplaceWith("WebSocketState"))
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DEGRADED,
        RECONNECTING
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
        val hasCallback = getActiveReconnectionCallback() != null
        updateConnectionStatus(
            isConnected = connectionState.isReady() && webSocket != null,
            lagMs = lastKnownLagMs,
            lastSyncTimestamp = lastSyncTimestamp
        )
        
        // If callback is missing, log it for debugging
        if (!hasCallback) {
            android.util.Log.w("WebSocketService", "Service started but reconnection callback not set - notification will show 'Waiting for app...'")
            // Boot/background startup: create a headless AppViewModel so WebSocket can connect.
            ensureHeadlessPrimary(applicationContext, "Service start")
        }
        
        // CRITICAL FIX: Return START_STICKY to ensure service restarts if killed by system
        // This is essential for reliability, especially without battery optimization exemption
        return START_STICKY
        
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
                updateConnectionState(WebSocketState.Disconnected)
                webSocket = null // Don't assume WebSocket is still connected
                isCurrentlyConnected = false
                connectionStartTime = 0 // Reset since we're not connected
                logActivity("Service Restarted - Waiting for AppViewModel", currentNetworkType.name)
                // Don't attempt reconnection yet - wait for callback registration
            } else {
                // Service restarted and callback is available - check if WebSocket is actually connected
                val isActuallyConnected = webSocket != null && connectionState.isReady()
                
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
            
            val isConnected = connectionState.isReady() && webSocket != null
            
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
            // BATTERY OPTIMIZATION: Unified monitoring replaces 3 separate jobs
            stopUnifiedMonitoring()
            // PHASE 3.1: Stop network monitoring
            stopNetworkMonitoring()
            
            // Cancel all coroutine jobs
            pingJob?.cancel()
            pongTimeoutJob?.cancel()
            initCompleteTimeoutJob?.cancel()
            reconnectionJob?.cancel()
            
            // Clear WebSocket connection
            webSocket?.close(1000, "Service destroyed")
            webSocket = null
            
            // Reset connection state
            updateConnectionState(WebSocketState.Disconnected)
            isReconnecting = false
            isCurrentlyConnected = false
            
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
            is WebSocketState.Disconnected -> "Connecting..."
            is WebSocketState.Reconnecting -> "Reconnecting... (${currentState.attemptCount})"
            is WebSocketState.Connecting -> "Connecting..."
            is WebSocketState.Connected -> "Connected..."
            is WebSocketState.Initializing -> "Initializing... (${currentState.processedSyncCompleteCount}/${currentState.pendingSyncCompleteCount})"
            is WebSocketState.Ready -> if (BuildConfig.DEBUG) "Connected." else "Connected."
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
        traceToggle("updateConnectionStatus", "isConnected=$isConnected lag=$lagMs lastSync=$lastSyncTimestamp")
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
                getActiveReconnectionCallback() == null && !connectionState.isReady() -> {
                    "Waiting for app... • ${getNetworkTypeDisplayName(currentNetworkType)}"
                }
                !isConnected -> "Connecting... • ${getNetworkTypeDisplayName(currentNetworkType)}"
                connectionState.isReconnecting() -> {
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
            val callbackMissing = getActiveReconnectionCallback() == null && !currentState.isReady()
            
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
                currentState is WebSocketState.Disconnected && currentNetworkType == NetworkType.NONE -> "Disconnected"
                currentState is WebSocketState.Disconnected -> "Connecting..."
                currentState is WebSocketState.Reconnecting -> "Reconnecting... (${currentState.attemptCount})"
                currentState is WebSocketState.Connecting -> "Connecting..."
                currentState is WebSocketState.Connected -> "Connected..."
                currentState is WebSocketState.Initializing -> "Initializing... (${currentState.processedSyncCompleteCount}/${currentState.pendingSyncCompleteCount})"
                currentState is WebSocketState.Ready -> "Connected."
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
     * Instance-level helper for release builds to show that buffered messages
     * are being processed when the app returns to foreground.
     *
     * This reuses the existing foreground notification channel and ID.
     */
    private fun showBatchProcessingStatus(pendingCount: Int) {
        if (BuildConfig.DEBUG) return
        
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
        
        val notificationText = "Processing $pendingCount buffered messages…"
        
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
    }

    /**
     * Restore normal connection status after temporary "Processing X buffered messages…"
     * text was shown in release foreground notification.
     */
    private fun clearBatchProcessingStatus() {
        if (BuildConfig.DEBUG) return
        // Force next status render even when connection state didn't change.
        lastConnectionStateForNotification = null
        lastNotificationText = null
        updateConnectionStatus(
            isConnected = connectionState.isReady(),
            lagMs = lastKnownLagMs,
            lastSyncTimestamp = lastSyncTimestamp
        )
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
        if (connectionState.isReady() && isCurrentlyConnected) {
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
