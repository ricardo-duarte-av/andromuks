package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel lifecycle: primary/bubble/secondary role, foreground visibility, [onCleared] teardown.
 * Mutable state lives on [AppViewModel]; this class holds orchestration only.
 */
internal class ViewModelLifecycleCoordinator(private val vm: AppViewModel) {

    fun markAsPrimaryInstance() {
        with(vm) {
            instanceRole = AppViewModel.InstanceRole.PRIMARY
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Instance role set to PRIMARY for $viewModelId",
                )

            // STEP 2.1: Register/update this ViewModel with service (as primary)
            // This updates the registration if it was already registered as secondary
            WebSocketService.registerViewModel(viewModelId, isPrimary = true)

            // PHASE 1.4 FIX: Register primary callbacks immediately when marked as primary
            // This ensures callbacks are available before WebSocket connection is established
            // The service can then properly detect that AppViewModel is available
            this@ViewModelLifecycleCoordinator.registerPrimaryCallbacks()
        }
    }

    fun promoteToPrimaryIfNeeded(reason: String) {
        with(vm) {
            if (instanceRole == AppViewModel.InstanceRole.PRIMARY) {
                return
            }
            val currentPrimary = WebSocketService.getPrimaryViewModelId()
            if (currentPrimary == null) {
                android.util.Log.i(
                    "Andromuks",
                    "AppViewModel: No primary instance detected - promoting $viewModelId ($reason)",
                )
                this@ViewModelLifecycleCoordinator.markAsPrimaryInstance()
                startWebSocketService()
            } else if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Primary instance already registered ($currentPrimary) - skipping promotion request from $viewModelId ($reason)",
                )
            }
        }
    }

    fun registerPrimaryCallbacks() {
        with(vm) {
            if (instanceRole != AppViewModel.InstanceRole.PRIMARY) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipping primary registration - not PRIMARY",
                    )
                return
            }
            WebSocketService.setPrimaryClearCacheCallback(viewModelId) {
                SyncRepository.requestClearTimelineCaches()
            }
            val ok = WebSocketService.setReconnectionCallback(viewModelId) {}
            if (!ok) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: setReconnectionCallback failed for $viewModelId",
                )
            }
            WebSocketService.setOfflineModeCallback(viewModelId) {}
            WebSocketService.setActivityLogCallback(viewModelId) { _, _ -> }
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Primary registered (SyncRepository flows); viewModelId=$viewModelId",
                )
        }
    }

    fun markAsBubbleInstance() {
        with(vm) {
            instanceRole = AppViewModel.InstanceRole.BUBBLE
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Instance role set to BUBBLE for $viewModelId",
                )

            // STEP 2.1: Register this ViewModel with service (as secondary)
            WebSocketService.registerViewModel(viewModelId, isPrimary = false)
        }
    }

    fun onPromotedToPrimary() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: STEP 2.3 - ViewModel $viewModelId promoted to primary",
                )

            // Update instance role
            instanceRole = AppViewModel.InstanceRole.PRIMARY

            // STEP 2.3: Update registration to reflect primary status
            WebSocketService.registerViewModel(viewModelId, isPrimary = true)

            // CRITICAL: Register to receive WebSocket messages
            WebSocketService.registerReceiveCallback(viewModelId, vm)

            // STEP 2.3: Register primary callbacks with service
            // The callbacks are already stored in service (from previous primary), but we need to
            // ensure this ViewModel can handle them. Since callbacks don't capture AppViewModel
            // (Step 1.2), they should work, but we re-register to ensure this ViewModel is set as
            // primary
            this@ViewModelLifecycleCoordinator.registerPrimaryCallbacks()

            // STEP 2.3: Take over WebSocket management
            // 1. Ensure WebSocket service is running (if not already)
            if (!WebSocketService.isServiceRunning()) {
                android.util.Log.i(
                    "Andromuks",
                    "AppViewModel: STEP 2.3 - WebSocket service not running, starting it",
                )
                startWebSocketService()
            }

            // 2. Attach to existing WebSocket if available and not already attached
            val existingWebSocket = WebSocketService.getWebSocket()
            if (existingWebSocket != null) {
                // REFACTORING: Service owns WebSocket - just register callbacks, no local storage
                // needed
                android.util.Log.i(
                    "Andromuks",
                    "AppViewModel: STEP 2.3 - Attaching to existing WebSocket",
                )
                WebSocketService.registerReceiveCallback(viewModelId, vm)
            } else {
                // 3. If no WebSocket exists and we have credentials, attempt to reconnect
                // This handles the case where the primary was destroyed while disconnected
                appContext?.let { context ->
                    val prefs =
                        context.getSharedPreferences(
                            "AndromuksPrefs",
                            android.content.Context.MODE_PRIVATE,
                        )
                    val storedHomeserverUrl = prefs.getString("homeserverUrl", null)
                    val storedAuthToken = prefs.getString("authToken", null)

                    if (storedHomeserverUrl != null && storedAuthToken != null) {
                        android.util.Log.i(
                            "Andromuks",
                            "AppViewModel: STEP 2.3 - No WebSocket found, attempting to reconnect as new primary",
                        )
                        // Use viewModelScope to ensure connection survives activity recreation
                        viewModelScope.launch {
                            initializeWebSocketConnection(storedHomeserverUrl, storedAuthToken)
                        }
                    } else {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: STEP 2.3 - No WebSocket and no credentials found - cannot reconnect",
                        )
                    }
                }
            }

            android.util.Log.i(
                "Andromuks",
                "AppViewModel: STEP 2.3 - ViewModel $viewModelId successfully promoted to primary, callbacks registered, and WebSocket management taken over",
            )
        }
    }

    fun isPrimaryInstance(): Boolean =
        with(vm) { instanceRole == AppViewModel.InstanceRole.PRIMARY }

    fun onAppBecameVisible() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: App became visible")
            isAppVisible = true
            updateAppVisibilityInPrefs(true)

            // BATTERY OPTIMIZATION: Notify batch processor to flush pending messages and process
            // immediately
            // CRITICAL: Wait for batches to flush BEFORE refreshing UI, so UI shows up-to-date data
            val (_, batchFlushJob) = syncBatchProcessor.onAppVisibilityChanged(true)

            // Notify service of app visibility change
            WebSocketService.setAppVisibility(true)

            // Only restore the previously-open room if there is no explicit navigation request
            // pending (FCM notification / shortcut). directRoomNavigation takes priority — it
            // already cleared pendingRoomToRestore in setDirectRoomNavigation, but guard here
            // as well in case of any ordering edge case (e.g. broadcast arrives before onResume).
            if (currentRoomId.isEmpty() && directRoomNavigation == null) {
                val roomToRestore = pendingRoomToRestore
                if (!roomToRestore.isNullOrEmpty()) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Restoring current room to $roomToRestore after visibility change",
                        )
                    updateCurrentRoomIdInPrefs(roomToRestore)
                }
            }
            pendingRoomToRestore = null

            // Cancel any pending shutdown
            appInvisibleJob?.cancel()
            appInvisibleJob = null

            // BATTERY OPTIMIZATION: Rush process pending rooms and receipts that were deferred when
            // backgrounded
            // CRITICAL: Set processing flag to prevent RoomListScreen from showing stale data
            processPendingItemsIfNeeded()

            // CRITICAL: Wait for batch flush to complete, THEN refresh UI
            // This ensures UI shows up-to-date data instead of stale data that gets updated again
            if (batchFlushJob != null) {
                viewModelScope.launch {
                    try {
                        batchFlushJob.join() // Wait for batch flush to complete
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Batch flush completed, refreshing UI",
                            )
                        // Refresh UI with up-to-date state after batches are processed
                        refreshUIState()
                        // Re-trigger timeline refresh now that batched events are in cache.
                        // The earlier timelineRefreshTrigger++ (below) fires before the flush
                        // completes, so the open room timeline must be rebuilt a second time
                        // after the batch lands to pick up any events that were buffered.
                        if (currentRoomId.isNotEmpty()) {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Batch flush done, re-triggering timeline refresh for room $currentRoomId",
                                )
                            timelineRefreshTrigger++
                        }
                    } catch (e: Exception) {
                        // Still refresh UI even if batch flush failed
                        refreshUIState()
                    }
                }
            } else {
                // No batches to flush, refresh UI immediately
                refreshUIState()
            }

            // CRITICAL FIX: Ensure current user profile is loaded when app becomes visible
            // This fixes issues when app starts from notification/shortcut and profile wasn't
            // loaded yet
            ensureCurrentUserProfileLoaded()

            // If a room is currently open, trigger an immediate timeline refresh from whatever
            // is already in cache. A second refresh fires after the batch flush (above) to pick
            // up any events that were still buffered at this point.
            if (currentRoomId.isNotEmpty()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Room is open ($currentRoomId), triggering timeline refresh",
                    )
                timelineRefreshTrigger++
            }

            // WebSocket service maintains connection
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: App visible, will refresh UI after batch flush completes",
                )
        }
    }

    fun onAppBecameInvisible() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: App became invisible")
            isAppVisible = false
            updateAppVisibilityInPrefs(false)

            // BATTERY OPTIMIZATION: Notify batch processor to start batching messages
            syncBatchProcessor.onAppVisibilityChanged(false)

            // Notify service of app visibility change
            WebSocketService.setAppVisibility(false)
            if (currentRoomId.isNotEmpty()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Clearing current room ($currentRoomId) while app invisible to allow notifications",
                    )
                clearCurrentRoomId(shouldRestoreOnVisible = true)
            }

            // Cancel any existing shutdown job (no shutdown needed - service maintains connection)
            appInvisibleJob?.cancel()
            appInvisibleJob = null

            // WebSocket service maintains connection in background
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: App invisible, WebSocket service continues maintaining connection",
                )
        }
    }

    fun suspendApp() {
        if (BuildConfig.DEBUG)
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: App manually suspended, WebSocket service continues",
            )
        onAppBecameInvisible()
    }

    /**
     * Called from [AppViewModel.onCleared] after [androidx.lifecycle.ViewModel.onCleared] super
     * call.
     */
    fun onCleared() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: onCleared - cleaning up resources for $viewModelId",
                )

            // Detach from SyncRepository (single registry: lifecycle + receive; primary promotion
            // on detach)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Unregistering $viewModelId from WebSocket / SyncRepository",
                )
            WebSocketService.unregisterReceiveCallback(viewModelId)

            // PHASE 1.3: Clear primary callbacks if this is the primary instance
            if (instanceRole == AppViewModel.InstanceRole.PRIMARY) {
                // PHASE 1.3: Validate that this instance is actually the primary before clearing
                val currentPrimaryId = WebSocketService.getPrimaryViewModelId()
                val isActuallyPrimary = WebSocketService.isPrimaryInstance(viewModelId)

                if (isActuallyPrimary) {
                    // This instance is confirmed as primary - safe to clear
                    val cleared = WebSocketService.clearPrimaryCallbacks(viewModelId)
                    if (cleared) {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Cleared primary callbacks for $viewModelId",
                            )
                    } else {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Failed to clear primary callbacks for $viewModelId (unexpected failure)",
                        )
                    }
                } else {
                    // This instance thinks it's primary but WebSocketService says otherwise
                    if (currentPrimaryId != null) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Instance $viewModelId marked as PRIMARY but WebSocketService reports $currentPrimaryId as primary. State mismatch detected.",
                        )
                    } else {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Instance $viewModelId marked as PRIMARY but no primary instance registered in WebSocketService. State mismatch detected.",
                        )
                    }
                    // Attempt to clear anyway (defensive cleanup)
                    val cleared = WebSocketService.clearPrimaryCallbacks(viewModelId)
                    if (!cleared && BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Clear attempt failed as expected (instance was not primary)",
                        )
                    }
                }
            } else {
                // Not a primary instance - verify we're not accidentally registered as primary
                if (WebSocketService.isPrimaryInstance(viewModelId)) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Instance $viewModelId is not marked as PRIMARY but is registered as primary in WebSocketService. Clearing to prevent state corruption.",
                    )
                    WebSocketService.clearPrimaryCallbacks(viewModelId)
                }
            }

            // Cancel any pending jobs
            appInvisibleJob?.cancel()
            appInvisibleJob = null

            if (instanceRole == AppViewModel.InstanceRole.PRIMARY) {
                // CRITICAL FIX: Don't clear WebSocket when Activity is swiped away
                // The foreground service should maintain the connection independently
                // Only clear WebSocket if we're actually shutting down (e.g., on logout)
                // Check if service is still running - if so, don't clear the connection
                val serviceStillRunning = WebSocketService.isServiceRunning()
                if (serviceStillRunning) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Service still running - NOT clearing WebSocket (foreground service maintains connection)",
                        )
                    // Just cancel reconnection, but keep the connection alive
                    WebSocketService.cancelReconnection()
                } else {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Service not running - clearing WebSocket (app shutdown)",
                        )
                    WebSocketService.cancelReconnection()
                    clearWebSocket("ViewModel cleared - service not running")
                }
            } else {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipping global WebSocket teardown for role=$instanceRole",
                    )
            }
        }
    }
}
