# WebSocket Reconnection Logic Analysis

## Issues Identified

### 1. Notification Stuck in "Connecting..." or "Reconnecting..."

**Root Causes:**

1. **State Machine Issues:**
   - Connection state can get stuck in `CONNECTING` or `RECONNECTING` if:
     - `init_complete` never arrives (timeout might not trigger properly)
     - Reconnection callback is missing and reconnection is queued but never executed
     - Network validation fails but reconnection still proceeds
     - Connection state is set to `RECONNECTING` but reconnection job fails silently

2. **Notification Update Logic:**
   - `updateConnectionStatus()` only updates notification when explicitly called
   - If connection state gets stuck, notification never updates
   - Release builds only update on state changes, but state might not change if stuck
   - No periodic health check to detect stuck states

3. **Missing Callback Handling:**
   - When reconnection callback is missing, reconnection is queued
   - `ensureHeadlessPrimary()` is called but might fail silently
   - Notification shows "Waiting for app..." but never recovers if primary ViewModel never registers

**Code Locations:**
- `WebSocketService.kt:1595-1686` - `scheduleReconnection()`
- `WebSocketService.kt:2967-3165` - `updateConnectionStatus()`
- `WebSocketService.kt:2465-2510` - `startInitCompleteTimeout()`

### 2. Rooms Out of Date After Reconnection

**Root Causes:**

1. **Initial Sync Queue Not Flushed:**
   - On reconnection, `initialSyncPhase` is reset to `false` to queue `sync_complete` messages
   - If `init_complete` doesn't arrive or arrives late, queued messages might not be processed
   - Room list shows stale data from before reconnection
   - `sync_complete` messages after reconnection might not properly update room data if initial sync queue isn't flushed

2. **Cache Clearing Timing:**
   - Timeline caches are cleared on `init_complete`, but room summaries might not be refreshed
   - Room list might show old message previews/timestamps until new `sync_complete` arrives
   - No forced refresh of room list data after reconnection

3. **State Synchronization:**
   - `initialSyncComplete` flag might remain `true` during reconnection, causing UI to show stale data
   - Room list update counter might not increment properly after reconnection

**Code Locations:**
- `AppViewModel.kt:6263-6275` - `setWebSocket()` resets `initialSyncPhase`
- `AppViewModel.kt:4718-4851` - `onInitComplete()` processes queued messages
- `AppViewModel.kt:4199-4211` - `updateRoomsFromSyncJsonAsync()` queues messages

### 3. Network Switching Issues

**Root Causes:**

1. **Aggressive Reconnection:**
   - Network monitoring triggers reconnection on every network type change
   - Weak WiFi might cause frequent reconnections
   - Network validation timeout (2s) might be too short for slow networks
   - Reconnection happens too aggressively when switching between networks

2. **Network Validation Logic:**
   - `waitForNetworkValidation()` has 2s timeout, which might be too short for slow networks
   - If validation fails, reconnection still proceeds (just logs warning)
   - No retry logic for network validation failures

3. **Network Type Change Detection:**
   - `shouldReconnectOnNetworkChange()` always reconnects on WiFi→WiFi or Mobile→Mobile changes
   - This causes unnecessary reconnections when switching between same-type networks
   - No debouncing for rapid network changes

**Code Locations:**
- `WebSocketService.kt:2034-2137` - `startNetworkMonitoring()`
- `WebSocketService.kt:2169-2209` - `waitForNetworkValidation()`
- `WebSocketService.kt:2218-2259` - `shouldReconnectOnNetworkChange()`

### 4. Reconnection Logic Brittleness

**Root Causes:**

1. **Multiple Reconnection Paths:**
   - Network monitoring triggers reconnection
   - Ping timeout triggers reconnection
   - `init_complete` timeout triggers reconnection
   - Race conditions between different reconnection triggers
   - No coordination between different reconnection paths

2. **State Management:**
   - Reconnection state can get stuck if callback is missing
   - No proper cleanup if reconnection fails
   - `isReconnecting` flag might not be reset properly in error cases

3. **Error Handling:**
   - Reconnection failures are logged but not always handled
   - No retry limit for reconnection attempts
   - No exponential backoff for persistent failures

**Code Locations:**
- `WebSocketService.kt:1595-1686` - `scheduleReconnection()`
- `WebSocketService.kt:1117-1126` - `triggerReconnection()`
- `WebSocketService.kt:534-600` - `invokeReconnectionCallback()`

## Proposed Fixes

### Fix 1: Add Periodic Health Check for Stuck States

**Problem:** Connection state can get stuck without notification updates.

**Solution:** Add a periodic health check that detects stuck states and forces recovery.

```kotlin
// In WebSocketService.kt
private var healthCheckJob: Job? = null

private fun startHealthCheck() {
    healthCheckJob?.cancel()
    healthCheckJob = serviceScope.launch {
        while (isActive) {
            delay(30_000) // Check every 30 seconds
            
            val currentState = connectionState
            val isStuck = when {
                currentState == ConnectionState.CONNECTING && waitingForInitComplete -> {
                    // Check if init_complete timeout is still running
                    val timeoutActive = initCompleteTimeoutJob?.isActive == true
                    val timeSinceConnect = System.currentTimeMillis() - connectionStartTime
                    timeoutActive && timeSinceConnect > INIT_COMPLETE_TIMEOUT_MS + 5000 // 5s grace period
                }
                currentState == ConnectionState.RECONNECTING && isReconnecting -> {
                    val timeSinceReconnect = System.currentTimeMillis() - lastReconnectionTime
                    timeSinceReconnect > 60_000 // Stuck for >60s
                }
                else -> false
            }
            
            if (isStuck) {
                android.util.Log.w("WebSocketService", "Health check: Detected stuck state ($currentState) - forcing recovery")
                logActivity("Health Check - Stuck State Detected", currentNetworkType.name)
                
                // Force recovery
                clearWebSocket("Health check: Stuck state detected")
                scheduleReconnection("Health check recovery")
            }
            
            // Also check if notification is stale (not updated in last 60s)
            val timeSinceNotificationUpdate = System.currentTimeMillis() - lastNotificationUpdateTime
            if (timeSinceNotificationUpdate > 60_000 && currentState != ConnectionState.CONNECTED) {
                android.util.Log.w("WebSocketService", "Health check: Notification stale (${timeSinceNotificationUpdate}ms old) - forcing update")
                updateConnectionStatus(
                    isConnected = currentState == ConnectionState.CONNECTED,
                    lagMs = lastKnownLagMs,
                    lastSyncTimestamp = lastSyncTimestamp
                )
            }
        }
    }
}
```

### Fix 2: Improve Network Validation and Debouncing

**Problem:** Network validation timeout too short, reconnection too aggressive.

**Solution:** Increase timeout, add debouncing, and improve validation logic.

```kotlin
// In WebSocketService.kt
private const val NETWORK_VALIDATION_TIMEOUT_MS = 5_000L // Increased from 2s to 5s
private const val NETWORK_CHANGE_DEBOUNCE_MS = 2_000L // Debounce rapid network changes

private var networkChangeDebounceJob: Job? = null

private fun startNetworkMonitoring() {
    // ... existing code ...
    
    networkMonitor = NetworkMonitor(
        context = this,
        onNetworkAvailable = { networkType ->
            // Debounce rapid network changes
            networkChangeDebounceJob?.cancel()
            networkChangeDebounceJob = serviceScope.launch {
                delay(NETWORK_CHANGE_DEBOUNCE_MS)
                
                android.util.Log.i("WebSocketService", "Network available: $networkType - checking if reconnection needed")
                val newNetworkType = convertNetworkType(networkType)
                val previousNetworkType = lastNetworkType
                val hasCallback = getActiveReconnectionCallback() != null
                
                // ... rest of existing logic ...
            }
        },
        // ... rest of callbacks ...
    )
}

private suspend fun waitForNetworkValidation(timeoutMs: Long = NETWORK_VALIDATION_TIMEOUT_MS): Boolean {
    // ... existing code with increased timeout ...
}

private fun shouldReconnectOnNetworkChange(previousType: NetworkType, newType: NetworkType): Boolean {
    // Don't reconnect on same-type network changes if connection is healthy
    if (previousType == newType && connectionState == ConnectionState.CONNECTED) {
        // Check if connection is actually healthy (recent sync, low lag)
        val timeSinceSync = System.currentTimeMillis() - lastSyncTimestamp
        val isHealthy = timeSinceSync < 60_000 && (lastKnownLagMs == null || lastKnownLagMs < 1000)
        
        if (isHealthy) {
            if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "shouldReconnectOnNetworkChange: Same network type and connection healthy - not reconnecting")
            return false
        }
    }
    
    // ... rest of existing logic ...
}
```

### Fix 3: Force Room List Refresh After Reconnection

**Problem:** Room list shows stale data after reconnection.

**Solution:** Force refresh room list data after `init_complete` and ensure queued messages are processed.

```kotlin
// In AppViewModel.kt
fun onInitComplete() {
    // ... existing code ...
    
    // CRITICAL FIX: Force refresh room list after reconnection
    // This ensures room data is up-to-date even if initial sync queue was empty
    viewModelScope.launch {
        delay(500) // Small delay to let queued messages process
        
        // Force room list update
        forceRoomListSort()
        
        // Trigger UI update
        roomListUpdateCounter++
        
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Forced room list refresh after init_complete")
    }
    
    // ... rest of existing code ...
}
```

### Fix 4: Improve Reconnection State Management

**Problem:** Reconnection state can get stuck, no proper cleanup.

**Solution:** Add better state management and cleanup logic.

```kotlin
// In WebSocketService.kt
fun scheduleReconnection(reason: String) {
    val serviceInstance = instance ?: return
    
    // ... existing callback check ...
    
    synchronized(serviceInstance.reconnectionLock) {
        val currentTime = System.currentTimeMillis()
        
        // Check if already reconnecting - if so, drop redundant request
        if (serviceInstance.connectionState == ConnectionState.RECONNECTING || serviceInstance.isReconnecting) {
            // CRITICAL FIX: Check if reconnection is actually stuck
            val timeSinceReconnect = currentTime - serviceInstance.lastReconnectionTime
            if (timeSinceReconnect > 60_000) {
                android.util.Log.w("WebSocketService", "Reconnection stuck for ${timeSinceReconnect}ms - resetting and retrying")
                serviceInstance.isReconnecting = false
                serviceInstance.reconnectionJob?.cancel()
                serviceInstance.reconnectionJob = null
                // Fall through to start new reconnection
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("WebSocketService", "Already reconnecting, dropping redundant request: $reason")
                return
            }
        }
        
        // ... rest of existing logic ...
        
        serviceInstance.reconnectionJob = serviceScope.launch {
            try {
                // ... existing reconnection logic ...
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
```

### Fix 5: Add Reconnection Retry Limit

**Problem:** No limit on reconnection attempts, can retry indefinitely.

**Solution:** Add retry limit with exponential backoff.

```kotlin
// In WebSocketService.kt
private var reconnectionAttemptCount = 0
private const val MAX_RECONNECTION_ATTEMPTS = 10
private const val RECONNECTION_RESET_TIME_MS = 300_000L // Reset count after 5 minutes

fun scheduleReconnection(reason: String) {
    // ... existing code ...
    
    synchronized(serviceInstance.reconnectionLock) {
        val currentTime = System.currentTimeMillis()
        
        // Reset attempt count if last reconnection was long ago
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
        
        serviceInstance.reconnectionAttemptCount++
        
        // ... rest of existing logic ...
    }
}

fun resetReconnectionState() {
    val serviceInstance = instance ?: return
    serviceInstance.reconnectionJob?.cancel()
    serviceInstance.reconnectionJob = null
    serviceInstance.isReconnecting = false
    serviceInstance.reconnectionAttemptCount = 0 // Reset attempt count on success
    // ... rest of existing code ...
}
```

## Testing Recommendations

1. **Network Switching Tests:**
   - Switch WiFi → WiFi (different networks)
   - Switch WiFi → 5G
   - Switch 5G → WiFi
   - Test with weak WiFi signal
   - Test with captive portal WiFi

2. **Reconnection Tests:**
   - Force close app during connection
   - Disable network during connection
   - Test rapid network changes
   - Test stuck state recovery

3. **Notification Tests:**
   - Verify notification updates correctly
   - Test stuck state detection
   - Test callback missing scenario

4. **Room List Tests:**
   - Verify room list updates after reconnection
   - Test with stale data scenario
   - Test initial sync queue processing

