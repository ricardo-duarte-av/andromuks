# WebSocket Service Resilience Analysis

## Current State

### ✅ What Works Well

1. **Foreground Service Independence**
   - `WebSocketService` runs as a foreground service with persistent notification
   - Service can survive AppViewModel destruction
   - Ping/pong loop continues running independently
   - Network monitoring continues independently

2. **AppViewModel Reattachment**
   - `attachToExistingWebSocketIfAvailable()` allows new AppViewModel to attach to existing WebSocket
   - Primary instance tracking ensures only one AppViewModel controls lifecycle
   - Callback registration system allows multiple ViewModels to receive messages

3. **Service Restart Detection (Phase 2.2)**
   - Detects when service is restarted by Android
   - Handles reconnection when callbacks are available
   - Waits for AppViewModel when callbacks are missing

4. **Callback Health Monitoring (Phase 2.3)**
   - Periodically checks if primary callbacks are available
   - Updates notification to show "Waiting for app..." when callbacks are missing
   - Provides diagnostics for debugging

5. **Pending Reconnection Queue (Phase 2.1)**
   - Queues reconnection requests when callbacks are not available
   - Processes queue when callbacks become available

### ⚠️ Potential Gaps

1. **Orphaned Service Scenario**
   - **Problem**: If AppViewModel is destroyed (`onCleared`) while service is still running:
     - Primary callbacks are cleared
     - WebSocket connection may still be alive
     - If WebSocket fails, service has no way to reconnect
     - Service becomes "orphaned" - connected but unable to recover
   
   - **Current Behavior**:
     - Service continues running
     - Ping/pong loop continues
     - Network monitoring continues
     - But: No reconnection capability if WebSocket fails

2. **WebSocket Connection State Mismatch**
   - **Problem**: Service may think WebSocket is connected, but:
     - AppViewModel was destroyed
     - WebSocket may have failed silently
     - No way to verify connection health without callbacks
   
   - **Current Behavior**:
     - `validateCallbacks()` checks callback availability
     - But doesn't verify if WebSocket is actually healthy

3. **Recovery When New AppViewModel Attaches**
   - **Problem**: When new AppViewModel attaches:
     - `attachToExistingWebSocketIfAvailable()` only attaches if WebSocket exists
     - Doesn't check if WebSocket is actually healthy
     - Doesn't trigger reconnection if WebSocket is dead
   
   - **Current Behavior**:
     - Attaches to existing WebSocket reference
     - But doesn't verify connection is alive

## Recommended Improvements

### 1. **Orphaned Service Detection & Recovery**

**Goal**: Detect when service is orphaned (no callbacks, but WebSocket may be dead) and attempt recovery.

**Implementation**:
- In `validateCallbacks()`: If callbacks are missing AND WebSocket exists:
  - Check if WebSocket is actually alive (ping test or connection state)
  - If dead, clear WebSocket and set state to DISCONNECTED
  - Log "Orphaned service detected - WebSocket cleared"
- When new AppViewModel attaches:
  - Check WebSocket health before attaching
  - If dead, trigger reconnection immediately

**Files to modify**:
- `WebSocketService.kt` - `validateCallbacks()`
- `AppViewModel.kt` - `attachToExistingWebSocketIfAvailable()`

### 2. **WebSocket Health Verification**

**Goal**: Verify WebSocket connection is actually alive, not just that the reference exists.

**Implementation**:
- Add `isWebSocketAlive(): Boolean` function that:
  - Checks if `webSocket != null`
  - Checks if connection state is CONNECTED
  - Optionally: Sends a test ping and waits for pong (with timeout)
- Use this in:
  - `validateCallbacks()` - to detect orphaned dead connections
  - `attachToExistingWebSocketIfAvailable()` - to verify connection before attaching
  - `onStartCommand()` - to verify connection on service restart

**Files to modify**:
- `WebSocketService.kt` - Add `isWebSocketAlive()` function
- `WebSocketService.kt` - Update `validateCallbacks()`
- `AppViewModel.kt` - Update `attachToExistingWebSocketIfAvailable()`

### 3. **Automatic Reconnection on AppViewModel Attachment**

**Goal**: When new AppViewModel attaches, automatically reconnect if WebSocket is dead.

**Implementation**:
- In `attachToExistingWebSocketIfAvailable()`:
  - Check if WebSocket exists
  - If exists, verify it's alive using `isWebSocketAlive()`
  - If dead or missing, trigger reconnection via `initializeWebSocketConnection()`
  - This ensures new AppViewModel always has a healthy connection

**Files to modify**:
- `AppViewModel.kt` - `attachToExistingWebSocketIfAvailable()`

### 4. **Service Self-Recovery**

**Goal**: Allow service to attempt self-recovery when orphaned (as last resort).

**Implementation**:
- In `validateCallbacks()`: If orphaned for >30 seconds:
  - Log warning: "Service orphaned - attempting self-recovery"
  - Clear dead WebSocket connection
  - Set state to DISCONNECTED
  - Update notification: "Waiting for app - connection lost"
  - When new AppViewModel attaches, it will trigger reconnection

**Files to modify**:
- `WebSocketService.kt` - `validateCallbacks()`

## Priority

**High Priority** (Critical for stability):
1. WebSocket Health Verification (#2)
2. Automatic Reconnection on AppViewModel Attachment (#3)

**Medium Priority** (Improves resilience):
3. Orphaned Service Detection & Recovery (#1)
4. Service Self-Recovery (#4)

## Testing Scenarios

1. **AppViewModel Destroyed, Service Running**
   - Kill AppViewModel (simulate activity destruction)
   - Verify service continues running
   - Verify callbacks are cleared
   - Verify notification shows "Waiting for app..."
   - Create new AppViewModel
   - Verify it attaches and reconnects if needed

2. **WebSocket Dead, AppViewModel Destroyed**
   - Kill WebSocket connection
   - Destroy AppViewModel
   - Verify service detects orphaned state
   - Create new AppViewModel
   - Verify it detects dead connection and reconnects

3. **Service Restart, WebSocket Dead**
   - Kill WebSocket connection
   - Restart service (simulate Android kill)
   - Verify service detects dead connection
   - Create new AppViewModel
   - Verify it triggers reconnection

## Conclusion

The current implementation is **mostly resilient** but has a critical gap: **orphaned service scenario**. When AppViewModel is destroyed, the service loses reconnection capability. The recommended improvements would make the system fully resilient to AppViewModel lifecycle changes.

