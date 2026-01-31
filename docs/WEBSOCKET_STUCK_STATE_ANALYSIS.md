# WebSocket "Connecting..." Stuck State Analysis

## Aggressive Timeout Detection (Updated)

The WebSocket connection now uses **aggressive timeout detection** to quickly identify broken connections:

- **`run_id` timeout: 500ms** - If `run_id` is not received within 500ms after WebSocket connection opens, the connection is considered broken and will be cleared and reconnected.
- **`init_complete` timeout: 500ms** - If `init_complete` is not received within 500ms after `run_id` is received, the connection is considered broken and will be cleared and reconnected.

**Rationale:**
- On a good connection (laptop at home, via WiFi), the time between `run_id` and `init_complete` is typically ~150ms
- 500ms is still very generous (3x the normal time) but catches broken connections much faster than the previous 15-second timeout
- WebSocket connections are lightweight (data transmitted is in KB), so aggressive reconnection is safe and improves user experience

**Fallback:** If `run_id` is not received, the system falls back to a 15-second timeout for `init_complete` (legacy behavior for edge cases).

---

## Question 1: Why is FCM Notification Opening Different from Permanent Notification?

### Permanent Notification (WebSocketService) Flow

**Intent Creation:**
```kotlin
// WebSocketService.kt:2872-2880
val intent = Intent(this, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
}
```

**What Happens:**
1. **MainActivity.onCreate()** is called (activity is recreated)
2. **AppViewModel is created fresh** via `viewModel()` in `AppNavigation`
3. **onViewModelCreated callback** executes:
   - `appViewModel.markAsPrimaryInstance()` ← **CRITICAL: Registers callbacks**
   - `appViewModel.initializeFCM()` ← Sets up appContext
   - `appViewModel.initializeWebSocketConnection()` ← **May be called if WebSocket missing**
4. **attachToExistingWebSocketIfAvailable()** is called
5. **If WebSocket doesn't exist**: `initializeWebSocketConnection()` creates new connection
6. **If WebSocket exists but stuck**: New primary instance can trigger reconnection

### FCM Notification Flow

**Intent Creation:**
```kotlin
// FCMService.kt:661-665
val intent = Intent(this, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    putExtra("room_id", roomId)
    putExtra("event_id", eventId)
}
```

**What Happens:**
1. **If MainActivity already exists**: `onNewIntent()` is called (activity NOT recreated)
2. **If MainActivity doesn't exist**: `onCreate()` is called (same as permanent notification)

**The Critical Difference:**

**When MainActivity already exists (app in background):**
- `onNewIntent()` is called instead of `onCreate()`
- **AppViewModel is NOT recreated** (existing instance is reused)
- **markAsPrimaryInstance() is NOT called again** (already marked)
- **initializeWebSocketConnection() is NOT called** (assumes connection exists)
- Only `attachToExistingWebSocketIfAvailable()` is called
- **If WebSocket is stuck in CONNECTING state, nothing triggers recovery**

**When MainActivity doesn't exist (app was killed):**
- `onCreate()` is called
- Same flow as permanent notification
- Should work correctly

### Why Permanent Notification Works But FCM Doesn't

**Permanent Notification:**
- Always uses `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`
- **Forces MainActivity recreation** (even if it exists)
- **onCreate() always called** → Fresh AppViewModel → `markAsPrimaryInstance()` → Callbacks registered → Can trigger reconnection

**FCM Notification (when app is in background):**
- Uses `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` (same flags)
- **BUT**: If MainActivity task already exists, Android may call `onNewIntent()` instead
- **onNewIntent() doesn't recreate AppViewModel** → No callback re-registration → Stuck state persists

### The Real Issue

**Both use the same intent flags**, but Android's behavior differs:
- **Permanent notification**: User explicitly taps → Android more likely to recreate activity
- **FCM notification**: System notification → Android may reuse existing activity → `onNewIntent()` path

**However**, the actual problem is:
- **onNewIntent() doesn't check if WebSocket is stuck**
- **onNewIntent() doesn't trigger reconnection if connection is broken**
- **onNewIntent() assumes WebSocket is healthy**

---

## Question 2: What Hypothetical State Can Block WebSocket in "Connecting..."?

### State 1: Waiting for init_complete (Most Common)

**State:**
- `connectionState = CONNECTING`
- `waitingForInitComplete = true`
- `initCompleteTimeoutJob` is running (500ms timeout after run_id, or 15 second fallback)

**What Blocks It:**
- Backend never sends `run_id` message (detected within 500ms)
- Backend never sends `init_complete` message (detected within 500ms after run_id)
- Network issues prevent messages from arriving
- WebSocket connection established but backend doesn't respond

**Recovery:**
- **NEW**: `run_id` timeout fires after 500ms if `run_id` not received → Connection broken → Clear and reconnect
- **NEW**: `init_complete` timeout fires after 500ms if `init_complete` not received after `run_id` → Connection broken → Clear and reconnect
- **FALLBACK**: If `run_id` wasn't received, falls back to 15-second timeout for `init_complete`
- Should trigger reconnection with exponential backoff
- **BUT**: If reconnection callback is missing, timeout just clears WebSocket without reconnecting

**Code Location:**
- `WebSocketService.kt:2712-2757` - `startInitCompleteTimeout()` and `startRunIdTimeout()`
- `WebSocketService.kt:59-62` - Timeout constants (500ms for both run_id and init_complete)

### State 2: Reconnection Callback Missing

**State:**
- `connectionState = CONNECTING` or `RECONNECTING`
- `getActiveReconnectionCallback() == null`
- `isReconnecting = true` (stuck)

**What Blocks It:**
- AppViewModel was destroyed
- Headless ViewModel creation failed or debounced
- Callbacks not registered yet
- Primary ViewModel ID set but ViewModel is dead

**Recovery:**
- Health check should detect this (every 30 seconds)
- `ensureHeadlessPrimary()` should create new ViewModel
- **BUT**: 1 second debounce might delay recovery
- **BUT**: If credentials are missing, headless ViewModel won't be created

**Code Location:**
- `WebSocketService.kt:1595-1614` - `scheduleReconnection()` callback check
- `WebSocketService.kt:168-204` - `ensureHeadlessPrimary()`

### State 3: Reconnection Job Stuck

**State:**
- `connectionState = RECONNECTING`
- `isReconnecting = true`
- `reconnectionJob` is running but blocked

**What Blocks It:**
- Network validation waiting forever (5 second timeout, but might hang)
- Backend health check hanging (no timeout)
- Reconnection job cancelled but flag not reset
- Exception in reconnection job but finally block didn't execute

**Recovery:**
- Health check should detect stuck state (>60 seconds)
- Forces `clearWebSocket()` and new reconnection
- **BUT**: If health check itself is blocked, no recovery

**Code Location:**
- `WebSocketService.kt:2033-2120` - Connection health check
- `WebSocketService.kt:1681-1715` - Reconnection job

### State 4: Network Validation Timeout But Proceeding Anyway

**State:**
- `connectionState = CONNECTING`
- Network validation failed (timeout after 5 seconds)
- But reconnection proceeds anyway (logs warning)
- WebSocket connection attempt fails silently
- State never transitions to CONNECTED or DISCONNECTED

**What Blocks It:**
- Weak WiFi that Android reports as available but can't actually connect
- Captive portal that passes validation but blocks WebSocket
- Network that validates but then immediately fails

**Recovery:**
- Should fail during WebSocket connection attempt
- Should trigger `onFailure()` callback
- **BUT**: If `onFailure()` doesn't properly update state, can get stuck

**Code Location:**
- `WebSocketService.kt:1683-1691` - Network validation in reconnection job
- `NetworkUtils.kt:369-450` - WebSocket connection failure handling

### State 5: Multiple App Instances Conflict

**State:**
- Two Andromuks instances on same device (different backends)
- Both try to create WebSocket connections
- Service singleton conflict
- One instance's callbacks override the other's

**What Blocks It:**
- Instance A creates WebSocket → State: CONNECTING
- Instance B's callbacks override Instance A's callbacks
- Instance A's reconnection callback is now null
- Instance A gets stuck, Instance B works

**Recovery:**
- Only the instance with active callbacks can recover
- Other instance stays stuck until user opens it manually

**Code Location:**
- `WebSocketService.kt:827-900` - `setReconnectionCallback()` primary instance check
- `WebSocketService.kt:146` - `headlessViewModel` is singleton (shared between instances!)

### State 6: init_complete Timeout Retry Loop

**State:**
- `connectionState = CONNECTING`
- `init_complete` timeout fires
- Retries with exponential backoff
- But retry keeps failing
- State oscillates between CONNECTING and DISCONNECTED

**What Blocks It:**
- Backend consistently fails to send `init_complete`
- Network issues prevent `init_complete` from arriving
- Retry limit (10 attempts) reached → Stops retrying
- State stuck in last state (CONNECTING or DISCONNECTED)

**Recovery:**
- Retry limit prevents infinite loop
- But leaves state in CONNECTING if last attempt was in progress
- User must manually open app to trigger new connection

**Code Location:**
- `WebSocketService.kt:2465-2510` - `startInitCompleteTimeout()`
- `WebSocketService.kt:1647-1652` - Retry limit check

---

## Why One Instance Worked and the Other Didn't

**Possible Reasons:**

1. **Different Backend Response Times:**
   - Instance A's backend responds quickly → `init_complete` arrives → CONNECTED
   - Instance B's backend is slow → `init_complete` timeout → Stuck

2. **Different Network Conditions:**
   - Instance A's backend is reachable on new WiFi
   - Instance B's backend has firewall/routing issues on new WiFi

3. **Service Singleton Conflict:**
   - Both instances share the same `WebSocketService` instance
   - Last instance to register callbacks "wins"
   - Other instance loses callbacks → Stuck

4. **Timing Race:**
   - Instance A: Network change → Reconnection scheduled → Callback available → Reconnects
   - Instance B: Network change → Reconnection scheduled → Callback missing (debounced) → Stuck

5. **Headless ViewModel Creation:**
   - Instance A: Headless ViewModel created successfully → Callbacks registered → Works
   - Instance B: Headless ViewModel creation failed (credentials, debounce, etc.) → Stuck

---

## Solutions

### Fix 1: Make onNewIntent() Check WebSocket Health

**Problem**: `onNewIntent()` doesn't check if WebSocket is stuck

**Solution**: Add WebSocket health check in `onNewIntent()`

```kotlin
override fun onNewIntent(intent: Intent) {
    // ... existing code ...
    
    // CRITICAL FIX: Check if WebSocket is stuck and trigger recovery
    if (::appViewModel.isInitialized) {
        val connectionState = WebSocketService.getConnectionState()
        val isStuck = connectionState == ConnectionState.CONNECTING || 
                     connectionState == ConnectionState.RECONNECTING
        val hasCallback = WebSocketService.getActiveReconnectionCallback() != null
        
        if (isStuck && !hasCallback) {
            android.util.Log.w("Andromuks", "MainActivity: WebSocket stuck and callback missing - triggering recovery")
            appViewModel.markAsPrimaryInstance() // Re-register callbacks
            appViewModel.initializeWebSocketConnection(homeserverUrl, authToken) // Force reconnection
        }
    }
}
```

### Fix 2: Reduce Headless ViewModel Debounce Further

**Problem**: 1 second is still too long for user experience

**Solution**: Reduce to 500ms or remove debounce for critical cases

### Fix 3: Ensure onNewIntent() Always Checks Primary Status

**Problem**: `onNewIntent()` assumes AppViewModel is primary and callbacks are registered

**Solution**: Always verify and re-register if needed

### Fix 4: Fix Service Singleton for Multiple Instances

**Problem**: Two app instances share same service → Callback conflicts

**Solution**: Use app-specific service instances or better callback management

