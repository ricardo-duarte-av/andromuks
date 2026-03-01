# WebSocket State Machine Redesign

## New State Design

### Sealed Class (Recommended)

**Why Sealed Class over Enum?**
- ✅ Type-safe pattern matching
- ✅ Can carry data (last_received_id, error messages, backoff delays)
- ✅ Better for complex state transitions
- ✅ StateFlow can hold sealed class instances

### State Definitions

```kotlin
sealed class WebSocketState {
    /**
     * DISCONNECTED: No connection, not attempting to connect
     * - Entry: Initial state, connection closed, network lost
     * - Exit: Network validated + backend health check OK → CONNECTING
     */
    object Disconnected : WebSocketState()
    
    /**
     * RECONNECTING: Waiting to reconnect (exponential backoff)
     * - Entry: Network lost, ping timeout, connection error
     * - Exit: Network validated + backend health check OK → CONNECTING
     * - Data: Tracks backoff delay (up to 120s)
     */
    data class Reconnecting(
        val backoffDelayMs: Long = 1000L, // Current backoff delay
        val attemptCount: Int = 0,         // Number of attempts
        val lastReceivedRequestId: Int = 0 // For reconnection
    ) : WebSocketState()
    
    /**
     * CONNECTING: Actively establishing WebSocket connection
     * - Entry: Network validated + backend health check OK
     * - Exit: WebSocket opened + run_id received → CONNECTED
     */
    object Connecting : WebSocketState()
    
    /**
     * CONNECTED: WebSocket connected and run_id received
     * - Entry: WebSocket opened + run_id message received
     * - Exit: init_complete received → INITIALIZING
     */
    object Connected : WebSocketState()
    
    /**
     * INITIALIZING: Receiving init messages (sync_completes)
     * - Entry: init_complete received
     * - Exit: All sync_completes processed → READY
     * - Data: Tracks sync progress
     */
    data class Initializing(
        val pendingSyncCompleteCount: Int = 0,
        val processedSyncCompleteCount: Int = 0
    ) : WebSocketState()
    
    /**
     * READY: Fully initialized and operational
     * - Entry: All sync_completes processed
     * - Exit: Network lost, ping timeout → RECONNECTING
     */
    object Ready : WebSocketState()
}
```

## State Transitions

```
DISCONNECTED
    ↓ (Network validated + HTTP GET OK)
CONNECTING
    ↓ (WebSocket opened + run_id received)
CONNECTED
    ↓ (init_complete received)
INITIALIZING (processing sync_completes)
    ↓ (All sync_completes processed)
READY
    ↓ (Network lost OR ping timeout)
RECONNECTING (with last_received_id)
    ↓ (Network validated + HTTP GET OK)
CONNECTING (with last_received_id)
    ↓ (WebSocket opened + run_id received)
CONNECTED
    ↓ (init_complete received)
INITIALIZING
    ↓ (All sync_completes processed)
READY
```

## Implementation Details

### 1. DISCONNECTED/RECONNECTING → CONNECTING

**Conditions:**
- Network has `NET_CAPABILITY_VALIDATED`
- Simple HTTP GET to backend URL returns 200 OK
- If not, exponential backoff (1s → 2s → 4s → 8s → 16s → 32s → 64s → 120s max)

**Code:**
```kotlin
private suspend fun attemptConnectionIfReady(): Boolean {
    val networkValidated = waitForNetworkValidation(2000L)
    if (!networkValidated) {
        return false
    }
    
    val backendHealthy = checkBackendHealth() // Simple HTTP GET
    if (!backendHealthy) {
        return false
    }
    
    return true
}
```

### 2. CONNECTING → CONNECTED

**Conditions:**
- WebSocket `onOpen()` called
- `run_id` message received from backend

**Code:**
```kotlin
fun onWebSocketOpen(webSocket: WebSocket) {
    updateState(WebSocketState.Connecting)
    // Wait for run_id...
}

fun onRunIdReceived(runId: String) {
    updateState(WebSocketState.Connected)
}
```

### 3. CONNECTED → INITIALIZING

**Conditions:**
- `init_complete` message received

**Code:**
```kotlin
fun onInitComplete() {
    updateState(WebSocketState.Initializing(
        pendingSyncCompleteCount = queuedSyncCompletes.size,
        processedSyncCompleteCount = 0
    ))
}
```

### 4. INITIALIZING → READY

**Conditions:**
- All queued `sync_complete` messages processed

**Code:**
```kotlin
fun onSyncCompleteProcessed() {
    val current = connectionState.value
    if (current is WebSocketState.Initializing) {
        val newProcessed = current.processedSyncCompleteCount + 1
        if (newProcessed >= current.pendingSyncCompleteCount) {
            updateState(WebSocketState.Ready)
        } else {
            updateState(current.copy(processedSyncCompleteCount = newProcessed))
        }
    }
}
```

### 5. READY → RECONNECTING

**Conditions:**
- Network `onLost()` callback fired (Android says network lost)
- Ping timeout (3 consecutive failures)
- **WiFi network name is irrelevant** - if Android says network lost, go to RECONNECTING

**Code:**
```kotlin
fun onNetworkLost() {
    val lastReceivedId = getLastReceivedRequestId()
    clearWebSocket("Network lost")
    updateState(WebSocketState.Reconnecting(
        backoffDelayMs = 1000L,
        attemptCount = 0,
        lastReceivedRequestId = lastReceivedId
    ))
}

fun onPingTimeout() {
    if (consecutivePingTimeouts >= 3) {
        val lastReceivedId = getLastReceivedRequestId()
        clearWebSocket("Ping timeout")
        updateState(WebSocketState.Reconnecting(
            backoffDelayMs = 1000L,
            attemptCount = 0,
            lastReceivedRequestId = lastReceivedId
        ))
    }
}
```

### 6. RECONNECTING → CONNECTING

**Conditions:**
- Network validated + backend health check OK
- Exponential backoff delay elapsed

**Code:**
```kotlin
private suspend fun handleReconnectingState(state: WebSocketState.Reconnecting) {
    delay(state.backoffDelayMs)
    
    if (attemptConnectionIfReady()) {
        val lastReceivedId = state.lastReceivedRequestId
        connectWebSocket(lastReceivedId) // Pass last_received_id
        updateState(WebSocketState.Connecting)
    } else {
        // Increase backoff (exponential, max 120s)
        val newBackoff = (state.backoffDelayMs * 2).coerceAtMost(120_000L)
        updateState(state.copy(
            backoffDelayMs = newBackoff,
            attemptCount = state.attemptCount + 1
        ))
    }
}
```

## StateFlow Implementation

```kotlin
class WebSocketService : Service() {
    // Private mutable state
    private val _connectionState = MutableStateFlow<WebSocketState>(
        WebSocketState.Disconnected
    )
    
    // Public read-only state
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()
    
    // Helper to update state
    private fun updateState(newState: WebSocketState) {
        val oldState = _connectionState.value
        _connectionState.value = newState
        
        android.util.Log.i("WebSocketService", 
            "State transition: $oldState → $newState")
        
        // Update notification based on state
        updateNotificationForState(newState)
    }
    
    // Current state (for compatibility with existing code)
    var connectionStateValue: WebSocketState
        get() = _connectionState.value
        private set(value) { _connectionState.value = value }
}
```

## Notification Text Mapping

```kotlin
fun getNotificationText(state: WebSocketState): String {
    return when (state) {
        is WebSocketState.Disconnected -> "Connecting..."
        is WebSocketState.Reconnecting -> "Reconnecting... (${state.attemptCount})"
        is WebSocketState.Connecting -> "Connecting..."
        is WebSocketState.Connected -> "Connected..."
        is WebSocketState.Initializing -> 
            "Initializing... (${state.processedSyncCompleteCount}/${state.pendingSyncCompleteCount})"
        is WebSocketState.Ready -> "Connected."
    }
}
```

## Benefits of This Design

1. **Clear State Progression**: Each state has a clear purpose and transition conditions
2. **Type Safety**: Sealed class prevents invalid states
3. **Data Carrying**: States can carry relevant data (backoff delays, sync progress)
4. **Reactive**: StateFlow allows UI to observe state changes
5. **Testable**: Easy to test state transitions
6. **Debuggable**: Clear state names and data make debugging easier

## Migration Strategy

1. **Phase 1**: Add sealed class alongside existing enum (no breaking changes)
2. **Phase 2**: Update state transitions to use sealed class
3. **Phase 3**: Update all state checks to use sealed class
4. **Phase 4**: Remove old enum

