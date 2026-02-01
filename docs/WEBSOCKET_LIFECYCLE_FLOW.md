# WebSocket Lifecycle Flow

## 1. App Started (Cold Start)

### a. Service Initialization
- `WebSocketService.onCreate()` called
- Service starts as foreground service with notification
- Notification shows: **"Connecting..."** (state = DISCONNECTED)
- Network monitoring starts (`startNetworkMonitoring()`)
- Health checks start (primary ViewModel, connection health)

### b. AppViewModel Initialization
- `AppViewModel` created (via `viewModel()` in MainActivity)
- `initializeWebSocketConnection()` called
- Checks if WebSocket already exists → No (first start)
- Calls `NetworkUtils.connectToWebsocket()`

### c. WebSocket Connection Attempt
- `NetworkUtils.connectToWebsocket()` creates OkHttp WebSocket client
- Builds WebSocket URL with `run_id` (from SharedPreferences) and compression flag
- Creates WebSocket request with auth token cookie
- Calls `client.newWebSocket(request, websocketListener)`

### d. WebSocket Opens
- `websocketListener.onOpen()` called
- Calls `appViewModel.setWebSocket(webSocket)`
- `AppViewModel.setWebSocket()`:
  - Stores WebSocket reference
  - Calls `WebSocketService.setWebSocket(webSocket)`

### e. Service Sets WebSocket
- `WebSocketService.setWebSocket()`:
  - **State: DISCONNECTED → CONNECTING**
  - Stores WebSocket reference
  - Sets `waitingForInitComplete = true`
  - Starts `init_complete` timeout (15 seconds)
  - Notification updated: **"Connecting..."** (state = CONNECTING)
  - Logs: "WebSocket connection opened - waiting for init_complete"

### f. Waiting for init_complete
- WebSocket is open but **NOT marked as CONNECTED yet**
- Backend sends initial `sync_complete` messages (queued in AppViewModel)
- AppViewModel queues these messages (doesn't process yet)
- Notification still shows: **"Connecting..."**

### g. init_complete Received
- Backend sends `init_complete` message
- `AppViewModel.onInitComplete()` called:
  - Processes all queued `sync_complete` messages
  - Sets `initialSyncComplete = true`
  - Calls `WebSocketService.onInitCompleteReceived()`

### h. Service Marks as Connected
- `WebSocketService.onInitCompleteReceived()`:
  - Cancels `init_complete` timeout
  - Sets `waitingForInitComplete = false`
  - **State: CONNECTING → CONNECTED**
  - Clears all timeline caches
  - Notification updated: **"Connected."** (state = CONNECTED)
  - Starts ping loop (if not already started)

### i. Ping Loop Starts
- Sends ping every 15 seconds
- Monitors pong responses
- Updates connection health (lag, last sync timestamp)

---

## 2. Network Changed: 5G → WiFi

### a. NetworkMonitor Detects Change
- Android's `ConnectivityManager` fires `onCapabilitiesChanged()` or `onAvailable()`
- `NetworkMonitor` detects network type change: CELLULAR → WIFI
- Calls `onNetworkTypeChanged(CELLULAR, WIFI)` callback

### b. WebSocketService Receives Network Change
- `startNetworkMonitoring()` callback triggered
- **Debounced by 2 seconds** (to avoid rapid changes)
- After debounce, `onNetworkTypeChanged` callback executes:
  - Updates `lastNetworkType = WIFI`, `currentNetworkType = WIFI`
  - Calls `shouldReconnectOnNetworkChange(CELLULAR, WIFI)`

### c. Decision: Should Reconnect?
- `shouldReconnectOnNetworkChange()` checks:
  - Previous: CELLULAR, New: WIFI (both online)
  - Connection state: CONNECTED
  - Connection health check:
    - `timeSinceSync < 120_000` (2 minutes) AND
    - `lagMs < 2000` (2 seconds) OR `lagMs == null`
  - **If healthy: Returns `false` (don't reconnect)**
  - **If unhealthy: Returns `true` (reconnect)**

### d. If Reconnection Needed (Connection Unhealthy)
- Calls `clearWebSocket("Network type changed: CELLULAR → WIFI")`
- `clearWebSocket()`:
  - Closes WebSocket: `webSocket.close(1000, "Clearing connection")`
  - Sets `webSocket = null`
  - **State: CONNECTED → DISCONNECTED**
  - Cancels ping loop, pong timeout, init_complete timeout
  - Resets connection health tracking
  - Notification updated: **"Connecting..."** (state = DISCONNECTED)
  - If `isReconnecting == true`, resets it and cancels reconnection job

### e. Schedule Reconnection
- Calls `scheduleReconnection("Network type changed: CELLULAR → WIFI")`
- `scheduleReconnection()`:
  - Checks if callback available (AppViewModel registered)
  - If no callback: Queues reconnection request, returns early
  - If callback available:
    - Checks if already reconnecting → If yes, drops request
    - Checks retry limit (max 10 attempts)
    - Sets `isReconnecting = true`
    - **State: DISCONNECTED → RECONNECTING**
    - Notification updated: **"Reconnecting..."** (state = RECONNECTING)
    - Launches reconnection job in background

### f. Reconnection Job Executes
- Reconnection job runs in `serviceScope`:
  1. **Network Validation** (5 second timeout):
     - Waits for `NET_CAPABILITY_VALIDATED` flag
     - Checks every 200ms
     - If timeout: Logs warning but continues anyway
  2. **Backend Health Check**:
     - Calls `checkBackendHealth()` (HTTP request to backend)
     - If healthy: Wait 3 seconds (`BASE_RECONNECTION_DELAY_MS`)
     - If unhealthy: Wait 5 seconds (`BACKEND_HEALTH_RETRY_DELAY_MS`)
  3. **Invoke Reconnection Callback**:
     - Calls `invokeReconnectionCallback(reason)`
     - This calls `AppViewModel.restartWebSocket(reason)`

### g. AppViewModel Restarts WebSocket
- `AppViewModel.restartWebSocket()`:
  - Checks cooldown (5 seconds minimum between restarts)
  - Sets `isRestarting = true`
  - Calls `WebSocketService.clearWebSocket(reason)` (if needed)
  - Calls `NetworkUtils.connectToWebsocket()` again
  - Goes back to step **1.c** (WebSocket Connection Attempt)

### h. New WebSocket Opens
- Same flow as initial connection:
  - `onOpen()` → `setWebSocket()` → **State: RECONNECTING → CONNECTING**
  - Notification: **"Connecting..."**
  - Wait for `init_complete`
  - **If init_complete arrives within 15 seconds:**
    - **State: CONNECTING → CONNECTED**
    - Notification: **"Connected."**
  - **If init_complete timeout (15 seconds):**
    - Shows failure notification
    - Clears WebSocket
    - Exponential backoff retry (2s, 4s, 8s, 16s, 32s, 64s max)
    - Retries reconnection

---

## 3. Potential Issues (Why Notification Gets Stuck)

### Issue 1: Reconnection Callback Missing
- **Scenario**: AppViewModel destroyed but service still running
- **Flow**:
  - Network changes → `scheduleReconnection()` called
  - `getActiveReconnectionCallback()` returns `null`
  - Reconnection queued in `pendingReconnectionReasons`
  - Calls `ensureHeadlessPrimary()` (tries to create new AppViewModel)
  - **If this fails**: Reconnection never executes
  - **State stuck in**: RECONNECTING
  - **Notification stuck**: **"Reconnecting..."**

### Issue 2: init_complete Timeout
- **Scenario**: Backend slow or network issues
- **Flow**:
  - WebSocket opens → State: CONNECTING
  - `init_complete` timeout starts (15 seconds)
  - Backend doesn't send `init_complete` in time
  - Timeout fires → Clears WebSocket → Retries
  - **If retries keep failing**: Stuck in CONNECTING → RECONNECTING loop
  - **Notification stuck**: **"Connecting..."** or **"Reconnecting..."**

### Issue 3: Network Validation Timeout
- **Scenario**: Weak WiFi or captive portal
- **Flow**:
  - Network changes → Reconnection scheduled
  - Network validation waits 5 seconds for `NET_CAPABILITY_VALIDATED`
  - If timeout: Logs warning but continues anyway
  - **If network never validates**: WebSocket connection fails
  - **State stuck in**: RECONNECTING
  - **Notification stuck**: **"Reconnecting..."**

### Issue 4: Reconnection Job Cancelled
- **Scenario**: Multiple network changes in quick succession
- **Flow**:
  - Network changes → Reconnection job starts
  - Network changes again → New reconnection scheduled
  - Old reconnection job cancelled
  - **If cancellation happens at wrong time**: State might not reset properly
  - **State stuck in**: RECONNECTING
  - **Notification stuck**: **"Reconnecting..."**

### Issue 5: Health Check Detects Stuck State
- **Scenario**: Connection stuck for >60 seconds
- **Flow**:
  - Health check runs every 30 seconds
  - Detects stuck CONNECTING (waiting >20 seconds for init_complete)
  - Detects stuck RECONNECTING (reconnecting >60 seconds)
  - Forces recovery: `clearWebSocket()` → `scheduleReconnection()`
  - **This should fix stuck states**, but might cause notification flicker

---

## 4. State Transitions Summary

```
DISCONNECTED
    ↓ (connectToWebsocket)
CONNECTING (waiting for init_complete)
    ↓ (init_complete received)
CONNECTED
    ↓ (network change + unhealthy OR ping timeout)
RECONNECTING
    ↓ (clearWebSocket)
DISCONNECTED
    ↓ (scheduleReconnection → callback)
CONNECTING
    ↓ (init_complete received)
CONNECTED
```

**Notification States:**
- `DISCONNECTED` → **"Connecting..."**
- `CONNECTING` → **"Connecting..."**
- `CONNECTED` → **"Connected."**
- `RECONNECTING` → **"Reconnecting..."**
- `DEGRADED` → **"Reconnecting..."**

---

## 5. Key Timing Points

1. **Network change debounce**: 2 seconds
2. **Network validation timeout**: 5 seconds
3. **Backend health check**: ~1-2 seconds
4. **Reconnection delay (healthy backend)**: 3 seconds
5. **Reconnection delay (unhealthy backend)**: 5 seconds
6. **init_complete timeout**: 15 seconds
7. **init_complete retry backoff**: 2s → 4s → 8s → 16s → 32s → 64s
8. **Health check interval**: 30 seconds
9. **Stuck state detection**: >60 seconds for RECONNECTING, >20 seconds for CONNECTING
10. **Reconnection cooldown**: 5 seconds minimum between restarts
11. **Max reconnection attempts**: 10 (resets after 5 minutes of successful connection)

---

## 6. Why "Connecting..." Gets Stuck

**Most Common Cause**: Reconnection callback missing or AppViewModel destroyed

**Flow**:
1. Network changes
2. `scheduleReconnection()` called
3. `getActiveReconnectionCallback()` returns `null`
4. Reconnection queued but never executed
5. State remains `RECONNECTING` or `CONNECTING`
6. Notification shows **"Reconnecting..."** or **"Connecting..."** forever

**Recovery Mechanisms**:
- Health check (every 30s) should detect stuck state and force recovery
- But if callback is permanently missing, recovery will also fail
- Only fix: User opens app → AppViewModel created → Callback registered → Reconnection executes


