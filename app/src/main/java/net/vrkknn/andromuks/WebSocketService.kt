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
        private const val PONG_TIMEOUT_MS = 1_000L // 1 second - "rush to healthy"
        // BATTERY OPTIMIZATION: Ping interval kept at 15s foreground, 30s background
        // Backend closes WebSocket if no message received in 60 seconds.
        // 30s gives a safe 50% margin against scheduling jitter, GC pauses, and network latency.
        private const val PING_INTERVAL_MS = 15_000L // 15 seconds normal interval (foreground)
        private const val PING_INTERVAL_BACKGROUND_MS = 30_000L // 30 seconds when backgrounded (safe margin vs 60s backend timeout)
        private const val CONSECUTIVE_FAILURES_TO_DROP = 3 // Drop WebSocket after 3 consecutive ping failures
        private const val INIT_COMPLETE_TIMEOUT_MS = 15_000L // 15 seconds to wait for init_complete (fallback)
        private const val RUN_ID_TIMEOUT_MS = 500L // 500ms to wait for run_id after connection
        private const val INIT_COMPLETE_AFTER_RUN_ID_TIMEOUT_MS = 2_000L // 2 seconds to wait for init_complete after run_id (increased from 1s to handle slower networks)
        private const val INIT_COMPLETE_RETRY_BASE_MS = 2_000L // 2 seconds initial retry delay
        private const val INIT_COMPLETE_RETRY_MAX_MS = 8_000L // 64 seconds max retry delay
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
        private val _connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
        val connectionStateFlow: StateFlow<ConnectionState> = _connectionStateFlow.asStateFlow()
        
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
        private fun invokeReconnectionCallback(reason: String, logIfMissing: Boolean = true) {
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
                    
                    // Connect - ViewModel is optional (null is fine, service handles everything)
                    connectWebSocket(homeserverUrl, authToken, viewModelToUse, reason)
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
                        // BATTERY OPTIMIZATION: Use adaptive interval based on app visibility
                        // Foreground: 15s, Background: 30s (safe margin vs 60s backend timeout)
                        if (serviceInstance.isAppVisible) PING_INTERVAL_MS else PING_INTERVAL_BACKGROUND_MS
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
         * Set app visibility for adaptive ping intervals
         */
        fun setAppVisibility(visible: Boolean) {
            instance?.isAppVisible = visible
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "App visibility changed to: $visible")
            
            // BATTERY OPTIMIZATION: Adaptive ping interval based on app visibility
            // Foreground: 15 seconds (normal interval)
            // Background: 30 seconds (safe margin vs 60s backend timeout)
            val interval = instance?.let { serviceInstance ->
                if (serviceInstance.isAppVisible) PING_INTERVAL_MS else PING_INTERVAL_BACKGROUND_MS
            } ?: PING_INTERVAL_BACKGROUND_MS
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
            
            updateConnectionState(ConnectionState.CONNECTING)
            serviceInstance.webSocket = webSocket
            // DO NOT mark as CONNECTED yet - wait for init_complete
            // Track connection start time for duration display
            serviceInstance.connectionStartTime = System.currentTimeMillis()
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connection state set to CONNECTING (waiting for init_complete)")
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "WebSocket reference set")
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
            serviceInstance.runIdReceived = false
            serviceInstance.runIdReceivedTime = 0
            // CRITICAL: Track if we're reconnecting with last_received_event (backend won't send init_complete)
            // This is set when we connect with last_received_event in the URL (in NetworkUtils)
            // Don't reset it here - it's set by NetworkUtils when building the URL
            // Start timeout for run_id (2 seconds) - if not received, connection is broken
            serviceInstance.startRunIdTimeout()
            // Don't start init_complete timeout yet - wait for run_id first
            
            // Log activity: WebSocket connecting (not connected yet)
            logActivity("WebSocket Connecting - Waiting for init_complete", actualNetworkType.name)
            
            // Update notification with connection status (still connecting)
            updateConnectionStatus(false, null, serviceInstance.lastSyncTimestamp)
            
            android.util.Log.i("WebSocketService", "WebSocket connection opened - waiting for init_complete")
            logPingStatus()
            
            // Show toast for connection opened
            serviceInstance.showWebSocketToast("Connection opened - waiting for run_id")
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
            
            // Check if we're in a state that needs clearing (CONNECTED, RECONNECTING, CONNECTING)
            // DISCONNECTED state means we're already cleared, so we can skip
            val needsClearing = serviceInstance.connectionState != ConnectionState.DISCONNECTED || serviceInstance.webSocket != null
            
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
            
            // Close the WebSocket properly before clearing the reference
            serviceInstance.webSocket?.close(1000, "Clearing connection")
            serviceInstance.webSocket = null
            updateConnectionState(ConnectionState.DISCONNECTED)
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
            serviceInstance.waitingForInitComplete = false
            serviceInstance.runIdReceived = false
            serviceInstance.runIdReceivedTime = 0
            
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
         * Check if WebSocket is connected
         * 
         * CRITICAL: This checks both the connection state AND the WebSocket reference.
         * If instance is null, the service isn't running, so we return false.
         * If connectionState is CONNECTED but webSocket is null, the connection was lost.
         */
        fun isWebSocketConnected(): Boolean {
            val serviceInstance = instance ?: return false
            // Both conditions must be true: state is CONNECTED AND WebSocket reference exists
            val isConnected = serviceInstance.connectionState == ConnectionState.CONNECTED && serviceInstance.webSocket != null
            if (BuildConfig.DEBUG && !isConnected && serviceInstance.connectionState == ConnectionState.CONNECTED) {
                // Log when state says CONNECTED but WebSocket is null (state corruption)
                android.util.Log.w("WebSocketService", "isWebSocketConnected(): State is CONNECTED but webSocket is null - state corruption detected")
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
            return state == ConnectionState.RECONNECTING || 
                   state == ConnectionState.CONNECTING || 
                   serviceInstance.isReconnecting
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
            if ((state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) && !hasCallback) {
                return true
            }
            
            // Also check if waiting for init_complete for too long
            if (state == ConnectionState.CONNECTING && serviceInstance.waitingForInitComplete) {
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
                updateConnectionState(ConnectionState.DISCONNECTED)
                corruptionDetected = true
            }
            
            // 2. Check for inconsistent connection state (WebSocket vs state mismatch)
            val hasWebSocket = serviceInstance.webSocket != null
            val isConnected = serviceInstance.connectionState == ConnectionState.CONNECTED
            
            if (hasWebSocket && !isConnected) {
                android.util.Log.w("WebSocketService", "CORRUPTION: WebSocket exists but state is not CONNECTED - recovering")
                updateConnectionState(ConnectionState.CONNECTED)
                corruptionDetected = true
            } else if (!hasWebSocket && isConnected) {
                android.util.Log.w("WebSocketService", "CORRUPTION: State is CONNECTED but no WebSocket - recovering")
                updateConnectionState(ConnectionState.DISCONNECTED)
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
            serviceInstance.unifiedMonitoringJob?.cancel()
            
            // Reset all state
            serviceInstance.isReconnecting = false
            serviceInstance.isCurrentlyConnected = false
            updateConnectionState(ConnectionState.DISCONNECTED)
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
                serviceInstance.connectionState = newState
            }
            _connectionStateFlow.value = newState
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Connection state updated: $newState")
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
            
            // Check connection state
            if (serviceInstance.connectionState != ConnectionState.CONNECTED) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "sendCommand() called but not connected (state: ${serviceInstance.connectionState}): $command")
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
        fun connectWebSocket(homeserverUrl: String, token: String, appViewModel: AppViewModel? = null, reason: String = "Service-initiated connection") {
            android.util.Log.i("WebSocketService", "connectWebSocket() called - initiating connection (reason: $reason)")
            
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
                    
                    // Check if already connecting
                    if (serviceInstance.connectionState == ConnectionState.CONNECTING) {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "connectWebSocket() called but already connecting - skipping")
                        return@launch
                    }
                    
                    // Verify backend health with timeout
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Verifying backend health before opening WebSocket")
                    try {
                        withTimeout(10_000L) { // 10 second timeout
                            net.vrkknn.andromuks.utils.waitForBackendHealth(homeserverUrl, loggerTag = "WebSocketService")
                        }
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Backend health check completed, connecting WebSocket")
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("WebSocketService", "Backend health check timed out after 10 seconds - proceeding with WebSocket connection anyway")
                    } catch (e: Exception) {
                        android.util.Log.e("WebSocketService", "Backend health check failed with exception - proceeding with WebSocket connection anyway", e)
                    }
                    
                    // REFACTORING: Connect websocket - no ViewModel needed, service handles everything
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Calling NetworkUtils.connectToWebsocket()")
                    val client = okhttp3.OkHttpClient.Builder().build()
                    // Pass context and optional ViewModel (for message routing only)
                    net.vrkknn.andromuks.utils.connectToWebsocket(homeserverUrl, client, token, serviceInstance.applicationContext, appViewModel, reason = reason)
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "NetworkUtils.connectToWebsocket() call completed")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in connectWebSocket()", e)
                    clearWebSocket("Connection error: ${e.message}")
                }
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
         * Update last sync timestamp when sync_complete is received
         */
        fun updateLastSyncTimestamp() {
            val serviceInstance = instance ?: return
            serviceInstance.lastSyncTimestamp = System.currentTimeMillis()
            
            //if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "updateLastSyncTimestamp() called - connectionState: ${serviceInstance.connectionState}")
            
            // Start ping loop on first sync_complete
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
            // CRITICAL FIX: Only start ping loop if connection is actually CONNECTED
            // This prevents ping loop from starting if init_complete arrives after timeout
            if (serviceInstance.connectionState != ConnectionState.CONNECTED) {
                android.util.Log.w("WebSocketService", "Init_complete received but connection state is ${serviceInstance.connectionState} - not starting ping loop")
                return
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
         * Notify that run_id was received - start 1-second timeout for init_complete
         */
        fun onRunIdReceived() {
            val serviceInstance = instance ?: return
            
            if (serviceInstance.runIdReceived) {
                // Already received - ignore duplicate
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Run ID already received - ignoring duplicate")
                return
            }
            
            serviceInstance.runIdReceived = true
            serviceInstance.runIdReceivedTime = System.currentTimeMillis()
            
            // Cancel run_id timeout (we got it)
            serviceInstance.runIdTimeoutJob?.cancel()
            serviceInstance.runIdTimeoutJob = null
            
            android.util.Log.i("WebSocketService", "Run ID received - starting 1-second timeout for init_complete")
            
            // Start 1-second timeout for init_complete
            serviceInstance.startInitCompleteTimeout()
        }
        
        /**
         * Handle init_complete received - mark connection as healthy
         */
        fun onInitCompleteReceived() {
            val serviceInstance = instance ?: return
            
            // Cancel timeout if still waiting
            serviceInstance.initCompleteTimeoutJob?.cancel()
            serviceInstance.initCompleteTimeoutJob = null
            // Cancel run_id timeout if still active (should already be cancelled, but be safe)
            serviceInstance.runIdTimeoutJob?.cancel()
            serviceInstance.runIdTimeoutJob = null
            
            // CRITICAL FIX: Handle race condition where init_complete arrives just after timeout
            // If we're not waiting but connection is still CONNECTING, accept it anyway
            if (!serviceInstance.waitingForInitComplete) {
                if (serviceInstance.connectionState == ConnectionState.CONNECTING) {
                    // Race condition: timeout fired but init_complete arrived shortly after
                    // Accept it anyway since connection is still active
                    android.util.Log.w("WebSocketService", "Init complete received after timeout but connection still CONNECTING - accepting (race condition)")
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Init complete received but not waiting - ignoring (state: ${serviceInstance.connectionState})")
                    return
                }
            }
            
            serviceInstance.waitingForInitComplete = false
            
            // CRITICAL FIX: Verify connection state before proceeding
            // If connection was cleared due to timeout, don't proceed with init_complete processing
            if (serviceInstance.connectionState != ConnectionState.CONNECTING && serviceInstance.connectionState != ConnectionState.CONNECTED) {
                android.util.Log.w("WebSocketService", "Init complete received but connection state is ${serviceInstance.connectionState} - ignoring")
                return
            }
            
            // Reset retry count on successful init_complete
            serviceInstance.initCompleteRetryCount = 0
            
            // CRITICAL: Clear all timeline caches on connect/reconnect - all caches are stale
            // This ensures we don't use stale data after reconnection
            // Also notifies AppViewModel to clear its internal caches
            RoomTimelineCache.clearAll()
            
            // Notify AppViewModel to clear its internal caches via callback
            val clearCacheCallback = getActiveClearCacheCallback()
            if (clearCacheCallback != null) {
                try {
                    clearCacheCallback.invoke()
                    if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Notified AppViewModel to clear all timeline caches")
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error invoking clear cache callback: ${e.message}", e)
                }
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Clear cache callback not available - RoomTimelineCache already cleared")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Cleared all timeline caches on init_complete (all rooms marked as needing pagination)")
            
            // Now mark as CONNECTED - connection is healthy
            updateConnectionState(ConnectionState.CONNECTED)
            android.util.Log.i("WebSocketService", "Init complete received - connection marked as CONNECTED")
            logActivity("Init Complete Received - Connection Healthy", serviceInstance.currentNetworkType.name)
            
            // Show toast for successful connection
            serviceInstance.showWebSocketToast("Connected!")
            
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
                
                // CRITICAL FIX: Check if reconnection is stuck and reset if needed
                if (serviceInstance.connectionState == ConnectionState.RECONNECTING || serviceInstance.isReconnecting) {
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
                
                updateConnectionState(ConnectionState.RECONNECTING)
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
                        // CRITICAL FIX: Check if network is still available before attempting reconnection
                        // Network might have been lost between scheduling and execution
                        if (serviceInstance.currentNetworkType == NetworkType.NONE) {
                            android.util.Log.w("WebSocketService", "Reconnection job started but network is NONE - cancelling reconnection")
                            serviceInstance.showWebSocketToast("No network - cancelling reconnection")
                            serviceInstance.isReconnecting = false
                            return@launch
                        }
                        
                        // PHASE 3.3: Validate network before reconnecting (prevents reconnection on captive portals)
                        // Increased timeout from 2s to 5s for slow networks
                        val networkValidated = serviceInstance.waitForNetworkValidation(2000L)
                        if (!networkValidated) {
                            // CRITICAL FIX: If network validation failed and network is NONE, cancel reconnection
                            if (serviceInstance.currentNetworkType == NetworkType.NONE) {
                                android.util.Log.w("WebSocketService", "Network validation failed and network is NONE - cancelling reconnection")
                                serviceInstance.showWebSocketToast("No network - cancelling reconnection")
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
                        
                        if (isActive) {
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Executing reconnection: $reason (attempt ${serviceInstance.reconnectionAttemptCount}/$MAX_RECONNECTION_ATTEMPTS)")
                            logActivity("Reconnection Attempt - $reason", serviceInstance.currentNetworkType.name)
                            // PHASE 1.4: Use safe invocation helper with error handling
                            invokeReconnectionCallback(reason)
                        } else {
                            serviceInstance.connectionState = ConnectionState.DISCONNECTED
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
            if (serviceInstance.connectionState == ConnectionState.RECONNECTING) {
                serviceInstance.connectionState = ConnectionState.DISCONNECTED
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
    private var runIdReceived: Boolean = false // Track if run_id was received
    private var runIdReceivedTime: Long = 0 // Timestamp when run_id was received
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
    private var networkChangeDebounceJob: Job? = null // Debounce rapid network changes
    
    // PHASE 4.1: WebSocket close code tracking
    private var lastCloseCode: Int? = null // Track last WebSocket close code
    private var lastCloseReason: String? = null // Track last WebSocket close reason
    
    // WebSocket connection management
    private var webSocket: WebSocket? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private var lastPongTimestamp = 0L // Track last pong for heartbeat monitoring
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
                .header("User-Agent", getUserAgent())
                .build()
            
            val response = client.newCall(request).execute()
            val isHealthy = response.isSuccessful && response.code == 200
            
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Backend health check: ${response.code} - ${if (isHealthy) "HEALTHY" else "UNHEALTHY"}")
            
            // Log backend health check result
            logActivity("Backend Health Check: ${if (isHealthy) "HEALTHY" else "UNHEALTHY"} (HTTP ${response.code})", currentNetworkType.name)
            
            // Show toast for backend health check
            showWebSocketToast("Backend: ${if (isHealthy) "HEALTHY" else "UNHEALTHY"} (${response.code})")
            
            response.close()
            isHealthy
        } catch (e: Exception) {
            android.util.Log.w("WebSocketService", "Backend health check failed", e)
            logActivity("Backend Health Check: FAILED - ${e.message}", currentNetworkType.name)
            
            // Show toast for backend health check failure
            showWebSocketToast("Backend: FAILED - ${e.message?.take(20)}")
            
            false
        }
    }
    
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
                delay(30_000) // Check every 30 seconds
                
                try {
                    // 1. Callback health check (every 30s)
                    // PHASE 2.3: Validate that all primary callbacks are set
                    // Ensures reconnection callbacks are available for reconnection attempts
                    validateCallbacks()
                    
                    // 2. State corruption check (every 60s - every other iteration)
                    // Detects and recovers from state inconsistencies (WebSocket vs state mismatch, stuck states)
                    stateCorruptionCheckCounter++
                    if (stateCorruptionCheckCounter >= 2) {
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
                    
                    // 4. Connection health check (every 30s)
                    // Detects if connection is stuck in CONNECTING or RECONNECTING state and forces recovery
                    val currentState = connectionState
                    val currentTime = System.currentTimeMillis()
                    
                    // Check for stuck CONNECTING state (waiting for init_complete)
                    val isStuckConnecting = when {
                        currentState == ConnectionState.CONNECTING && waitingForInitComplete -> {
                            val timeoutActive = initCompleteTimeoutJob?.isActive == true
                            val timeSinceConnect = if (connectionStartTime > 0) currentTime - connectionStartTime else 0
                            // Stuck if timeout is active but exceeded, or timeout job is missing but we've been waiting too long
                            (timeoutActive && timeSinceConnect > INIT_COMPLETE_TIMEOUT_MS + 5000) ||
                            (!timeoutActive && timeSinceConnect > INIT_COMPLETE_TIMEOUT_MS + 10000)
                        }
                        else -> false
                    }
                    
                    // Check for stuck RECONNECTING state
                    val isStuckReconnecting = when {
                        currentState == ConnectionState.RECONNECTING && isReconnecting -> {
                            val timeSinceReconnect = if (lastReconnectionTime > 0) currentTime - lastReconnectionTime else 0
                            timeSinceReconnect > 60_000 // Stuck for >60s
                        }
                        else -> false
                    }
                    
                    if (isStuckConnecting || isStuckReconnecting) {
                        android.util.Log.w("WebSocketService", "Unified monitoring: Detected stuck state ($currentState) - forcing recovery")
                        logActivity("Health Check - Stuck State Detected ($currentState)", currentNetworkType.name)
                        
                        // Force recovery
                        clearWebSocket("Health check: Stuck state detected ($currentState)")
                        
                        // Reset reconnection attempt count if stuck
                        if (isStuckReconnecting) {
                            synchronized(reconnectionLock) {
                                isReconnecting = false
                                reconnectionJob?.cancel()
                                reconnectionJob = null
                            }
                        }
                        
                        // Schedule new reconnection
                        scheduleReconnection("Health check recovery from stuck state")
                    }
                    
                    // 5. Notification staleness check (every 30s)
                    // Ensures notification is updated for non-CONNECTED states
                    val timeSinceNotificationUpdate = currentTime - lastNotificationUpdateTime
                    if (timeSinceNotificationUpdate > 60_000 && currentState != ConnectionState.CONNECTED) {
                        android.util.Log.w("WebSocketService", "Unified monitoring: Notification stale (${timeSinceNotificationUpdate}ms old) - forcing update")
                        updateConnectionStatus(
                            isConnected = currentState == ConnectionState.CONNECTED,
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
            // Headless recovery: recreate a primary ViewModel when callbacks are missing.
            ensureHeadlessPrimary(applicationContext, "Callback missing during health check")
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
                    
                    // PHASE 3.2: Update network type tracking
                    lastNetworkType = newNetworkType
                    currentNetworkType = newNetworkType
                    
                    // CRITICAL FIX: Wait for network validation BEFORE attempting reconnection
                    // This prevents DNS resolution failures when network isn't fully ready
                    android.util.Log.i("WebSocketService", "Network available: $networkType - waiting for validation before reconnecting")
                    
                    // Wait for network validation first (5 seconds max)
                    // CRITICAL FIX: If we're disconnected, use fallback validation with exponential backoff
                    val networkValidated = waitForNetworkValidation(2000L)
                    if (!networkValidated) {
                        if (connectionState == ConnectionState.DISCONNECTED) {
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
                            // Connected/connecting - wait for validation
                            android.util.Log.w("WebSocketService", "Network available but validation failed/timeout - not reconnecting yet (state: $connectionState)")
                            showWebSocketToast("Network not validated - waiting")
                            return@launch
                        }
                    }
                    
                    // Android validation succeeded - reset fallback backoff
                    fallbackBackoffDelayMs = 1000L
                    lastNetworkTypeForBackoff = null
                    
                    android.util.Log.i("WebSocketService", "Network validated - checking if reconnection needed")
                    
                    // SIMPLIFIED: Only reconnect if:
                    // 1. We're not connected (offline → online)
                    // 2. Network type changed AND connection is unhealthy
                    // Otherwise, trust Android's network state and keep existing connection
                    // CRITICAL FIX: Don't reconnect if we're already CONNECTING - wait for init_complete first
                    if (connectionState == ConnectionState.DISCONNECTED) {
                        // Not connected - reconnect (network is now validated)
                        android.util.Log.w("WebSocketService", "Network validated but WebSocket disconnected - triggering reconnection")
                        logActivity("Network Validated - Reconnecting", currentNetworkType.name)
                        
                        // REFACTORING: Service handles reconnection directly
                        scheduleReconnection("Network validated: $networkType")
                    } else if (connectionState == ConnectionState.CONNECTING) {
                        // Already connecting - don't trigger another reconnection
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("WebSocketService", "Network validated but WebSocket already connecting - waiting for init_complete")
                        }
                    } else if (previousNetworkType != newNetworkType && previousNetworkType != NetworkType.NONE) {
                        // Network type changed while connected - only reconnect if connection is unhealthy
                        val shouldReconnect = shouldReconnectOnNetworkChange(previousNetworkType, newNetworkType)
                        if (shouldReconnect) {
                            android.util.Log.i("WebSocketService", "Network type changed while connected and connection unhealthy - reconnecting ($previousNetworkType → $newNetworkType)")
                            clearWebSocket("Network type changed: $previousNetworkType → $newNetworkType")
                            // REFACTORING: Service handles reconnection directly
                            scheduleReconnection("Network type changed: $previousNetworkType → $newNetworkType")
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network type changed ($previousNetworkType → $newNetworkType) but connection is healthy - trusting Android, keeping connection")
                        }
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Network available and WebSocket already connected - no action needed")
                    }
                }
            },
            onNetworkLost = {
                android.util.Log.w("WebSocketService", "Network lost - marking connection as degraded")
                logActivity("Network Lost", currentNetworkType.name)
                
                // Show toast for network lost
                showWebSocketToast("Network lost")
                
                // CRITICAL FIX: Cancel any pending reconnection attempts when network is lost
                // Android reports no network available, so don't try to connect
                synchronized(reconnectionLock) {
                    reconnectionJob?.cancel()
                    reconnectionJob = null
                    isReconnecting = false
                }
                
                // Cancel network change debounce job
                networkChangeDebounceJob?.cancel()
                networkChangeDebounceJob = null
                
                // PHASE 3.2: Update network type tracking
                lastNetworkType = currentNetworkType // Keep previous type for when network returns
                currentNetworkType = NetworkType.NONE
                
                // Network lost - mark connection as degraded, don't reconnect yet
                // Wait for network to become available again
                isCurrentlyConnected = false
                
                // Clear WebSocket since there's no network
                clearWebSocket("Network lost - no network available")
                
                // Update notification to show network loss (explicitly set to DISCONNECTED)
                // CRITICAL FIX: Ensure state is DISCONNECTED when network is lost
                connectionState = ConnectionState.DISCONNECTED
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
                
                // CRITICAL: Reset fallback backoff when network type changes
                if (lastNetworkTypeForBackoff != newNetworkType) {
                    fallbackBackoffDelayMs = 1000L // Reset to 1 second
                    lastNetworkTypeForBackoff = newNetworkType
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("WebSocketService", "Network type changed - reset fallback backoff to 1s")
                    }
                }
                
                // SIMPLIFIED: Trust Android's network state - only reconnect if connection is actually broken
                val shouldReconnect = shouldReconnectOnNetworkChange(previousNetworkType, newNetworkType)
                
                // Update network type tracking
                lastNetworkType = newNetworkType
                currentNetworkType = newNetworkType
                
                if (shouldReconnect) {
                    // Network type changed and connection is unhealthy - reconnect
                    if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.RECONNECTING) {
                        android.util.Log.w("WebSocketService", "Network type changed while connected and connection unhealthy - reconnecting on new network ($previousNetworkType → $newNetworkType)")
                        clearWebSocket("Network type changed: $previousNetworkType → $newNetworkType")
                        if (!hasCallback) {
                            ensureHeadlessPrimary(applicationContext, "Network type change - callback missing")
                        }
                        scheduleReconnection("Network type changed: $previousNetworkType → $newNetworkType")
                    } else if (connectionState == ConnectionState.DISCONNECTED) {
                        // Not connected - trigger reconnection (network validation will happen in scheduleReconnection)
                        android.util.Log.i("WebSocketService", "Network type changed and disconnected - triggering reconnection ($previousNetworkType → $newNetworkType)")
                        // REFACTORING: Service handles reconnection directly
                        // Note: onNetworkAvailable will also try to reconnect, but scheduleReconnection has guards against duplicates
                        scheduleReconnection("Network type changed: $previousNetworkType → $newNetworkType")
                    } else {
                        // Other state (CONNECTING, etc.) - don't trigger another reconnection
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("WebSocketService", "Network type changed but state is $connectionState - not triggering reconnection")
                        }
                    }
                } else {
                    // Network type changed but connection is healthy - trust Android, keep existing connection
                    android.util.Log.i("WebSocketService", "Network type changed ($previousNetworkType → $newNetworkType) but connection is healthy - trusting Android, keeping connection")
                    // Just update the network type, keep existing connection
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
        
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        
        // Check if network is already validated
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
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
            
            val currentCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (currentCapabilities != null && currentCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                val elapsed = System.currentTimeMillis() - startTime
                android.util.Log.i("WebSocketService", "Network validation: Validated after ${elapsed}ms")
                
                // Show toast for network validation success
                showWebSocketToast("Network validated (${elapsed}ms)")
                
                return true
            }
            
            // Check if network is still active
            val currentNetwork = connectivityManager.activeNetwork
            if (currentNetwork != activeNetwork) {
                android.util.Log.w("WebSocketService", "Network validation: Network changed during validation")
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
        if (previousType == newType && connectionState == ConnectionState.CONNECTED) {
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
            connectionState = ConnectionState.DISCONNECTED
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
        
        // NOTE: We no longer send last_received_id in ping - we never pass it on connect/reconnect
        val data = emptyMap<String, Any>()
        
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
    /**
     * Start timeout for run_id - if not received within 2 seconds, connection is broken
     */
    private fun startRunIdTimeout() {
        runIdTimeoutJob?.cancel()
        runIdTimeoutJob = serviceScope.launch {
            delay(RUN_ID_TIMEOUT_MS)
            
            // Check if run_id was received
            if (runIdReceived) {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Run ID timeout expired but already received - ignoring")
                return@launch
            }
            
            // Check if connection is still active
            if (connectionState != ConnectionState.CONNECTING && connectionState != ConnectionState.CONNECTED) {
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
        }
    }
    
    
    private fun startInitCompleteTimeout() {
        initCompleteTimeoutJob?.cancel()
        
        // Use 1-second timeout if run_id was received, otherwise use fallback 15-second timeout
        val timeoutMs = if (runIdReceived) {
            INIT_COMPLETE_AFTER_RUN_ID_TIMEOUT_MS
        } else {
            INIT_COMPLETE_TIMEOUT_MS
        }
        
        initCompleteTimeoutJob = serviceScope.launch {
            delay(timeoutMs)
            
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
            
            val reason = if (runIdReceived) {
                "Init complete timeout expired after ${timeoutMs}ms (run_id received ${System.currentTimeMillis() - runIdReceivedTime}ms ago) - connection broken"
            } else {
                "Init complete timeout expired after ${timeoutMs}ms - connection failed"
            }
            
            android.util.Log.w("WebSocketService", reason)
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
            isConnected = connectionState == ConnectionState.CONNECTED && webSocket != null,
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
            connectionState = ConnectionState.DISCONNECTED
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
                //if (BuildConfig.DEBUG) Log.d("WebSocketService", "Skipping notification update - state unchanged: $currentState, callbackMissing: $callbackMissing")
                return
            }
            
            lastConnectionStateForNotification = stateKey
            
            when {
                callbackMissing -> "Waiting for app..."
                // CRITICAL FIX: When network is lost (NONE), show "Disconnected" instead of "Connecting..."
                currentState == ConnectionState.DISCONNECTED && currentNetworkType == NetworkType.NONE -> "Disconnected"
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
        if (connectionState == ConnectionState.CONNECTED && isCurrentlyConnected) {
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
            // BATTERY OPTIMIZATION: Adaptive ping interval based on app visibility
            // Foreground: 15s, Background: 30s (safe margin vs 60s backend timeout)
            val interval = if (isAppVisible) PING_INTERVAL_MS else PING_INTERVAL_BACKGROUND_MS
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
