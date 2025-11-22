# WebSocket State Machine Analysis

## Desired State Machine

1. **Connecting**: Just pass `run_id` to identify ourselves
2. **Connected**: Depends only on ping/pong. If pong fails, reconnect with `last_received_timeline_id` and `run_id`
3. **Reconnecting**: 
   - Drop old connection
   - Check backend health
   - Make new connection
   - **Must receive `init_complete` to consider healthy**
   - If no `init_complete`:
     - Drop connection
     - Wait 2 seconds, retry
     - Double wait time for each failure (2s → 4s → 8s → 16s → 32s → 64s max)
     - Show Android notification on failure

## Current Implementation Analysis

### ✅ State 1: Connecting

**Location**: `NetworkUtils.connectToWebsocket()` (lines 233-311)

**Status**: ✅ **IMPLEMENTED CORRECTLY**

- `run_id` is passed in URL query parameter (line 279)
- If reconnecting, `last_received_event` is also included (line 282)
- Connection state set to `CONNECTING` in `WebSocketService.setWebSocket()` (line 523)

**Code**:
```kotlin
val runId = appViewModel.getCurrentRunId() // From SharedPreferences
val lastReceivedId = appViewModel.getLastReceivedId()
if (actualRunId.isNotEmpty()) {
    queryParams.add("run_id=$actualRunId")
    if (isReconnecting) {
        queryParams.add("last_received_event=$lastReceivedId")
    }
}
```

### ⚠️ State 2: Connected

**Location**: `WebSocketService.kt` ping/pong logic (lines 1225-1286)

**Status**: ⚠️ **PARTIALLY IMPLEMENTED**

**What works**:
- ✅ Ping/pong mechanism is the authority for connection health
- ✅ If 3 consecutive ping failures, connection is dropped and reconnection is triggered (line 1246)
- ✅ Reconnection includes `last_received_event` and `run_id` (via `NetworkUtils.connectToWebsocket`)

**Issues**:
- ⚠️ Uses `last_received_sync_id` (from `sync_complete`), not `last_received_timeline_id`
  - Need to verify if these are the same value
- ⚠️ Connection is marked as `CONNECTED` immediately on `onOpen` (line 525), not waiting for `init_complete`
  - This means the connection is considered healthy before `init_complete` arrives

**Code**:
```kotlin
// WebSocketService.setWebSocket() - line 525
serviceInstance.connectionState = ConnectionState.CONNECTED  // ❌ Too early!
```

### ❌ State 3: Reconnecting

**Location**: `WebSocketService.scheduleReconnection()` (lines 855-915) and `NetworkUtils.connectToWebsocket()`

**Status**: ❌ **MISSING KEY FEATURES**

**What works**:
- ✅ Drops old connection (line 520 in `setWebSocket`)
- ✅ Checks backend health before reconnecting (line 889)
- ✅ Makes new connection via `NetworkUtils.connectToWebsocket()`

**Missing**:
- ❌ **No timeout for `init_complete`** - Connection is marked healthy immediately on `onOpen`
- ❌ **No exponential backoff** if `init_complete` doesn't arrive
- ❌ **No Android notification** when `init_complete` fails
- ❌ Connection state is set to `CONNECTED` on `onOpen`, not after `init_complete`

**Current flow**:
1. `onOpen` → `setWebSocket()` → State = `CONNECTED` ❌ (should be `CONNECTING`)
2. `init_complete` arrives → `onInitComplete()` called → No state change (should mark as healthy)

**Desired flow**:
1. `onOpen` → `setWebSocket()` → State = `CONNECTING` ✅
2. Start timeout (wait for `init_complete`)
3. If `init_complete` arrives → State = `CONNECTED` ✅
4. If timeout expires → Drop connection, wait 2s, retry with exponential backoff, show notification

## Key Functions Analysis

### AppViewModel Functions

1. **`restartWebSocketConnection(reason)`** (line 894)
   - Calls `restartWebSocket(reason)`
   - ✅ Works correctly

2. **`attachToExistingWebSocketIfAvailable()`** (line 4426)
   - Attaches to existing WebSocket if available
   - ✅ Works correctly

3. **`shutdownWebSocket()`** (line 4537)
   - Clears WebSocket connection
   - ✅ Works correctly

4. **`setWebSocket(webSocket)`** (line 5009)
   - Sets WebSocket and registers callbacks
   - ⚠️ Calls `WebSocketService.setWebSocket()` which marks as CONNECTED too early

5. **`isWebSocketConnected()`** (line 5089)
   - Delegates to `WebSocketService.isWebSocketConnected()`
   - ✅ Works correctly

6. **`clearWebSocket(reason)`** (line 5093)
   - Clears WebSocket connection
   - ✅ Works correctly

7. **`retryPendingWebSocketOperations()`** (line 5102)
   - Retries pending operations when connection is restored
   - ✅ Works correctly

8. **`restartWebSocket(reason)`** (line 5794)
   - Restarts WebSocket connection
   - ⚠️ Doesn't wait for `init_complete` timeout

9. **`startWebSocketService()`** (line 12066)
   - Starts the foreground service
   - ✅ Works correctly

10. **`stopWebSocketService()`** (line 12080)
    - Stops the foreground service
    - ✅ Works correctly

## Required Changes

### 1. Fix Connection State to Wait for `init_complete`

**File**: `WebSocketService.kt`

**Change**: Don't mark as `CONNECTED` on `onOpen`. Instead:
- Mark as `CONNECTING` on `onOpen`
- Mark as `CONNECTED` only after `init_complete` is received
- Start a timeout when connection opens

**Location**: `setWebSocket()` method (line 502-553)

### 2. Add `init_complete` Timeout Mechanism

**File**: `WebSocketService.kt`

**New functionality**:
- Track when WebSocket opens
- Start timeout job waiting for `init_complete`
- If timeout expires, drop connection and retry with exponential backoff

**Location**: New method `startInitCompleteTimeout()`

### 3. Implement Exponential Backoff for `init_complete` Failures

**File**: `WebSocketService.kt`

**New functionality**:
- Track retry count for `init_complete` failures
- Wait time: 2s → 4s → 8s → 16s → 32s → 64s (max)
- Reset retry count on successful `init_complete`

**Location**: `scheduleReconnection()` or new method

### 4. Add Android Notification on `init_complete` Failure

**File**: `WebSocketService.kt`

**New functionality**:
- Show notification when `init_complete` timeout expires
- Notification should indicate WebSocket connection issue
- Clear notification when connection is restored

**Location**: New method `showInitCompleteFailureNotification()`

### 5. Handle `init_complete` in WebSocketService

**File**: `NetworkUtils.kt` and `WebSocketService.kt`

**Change**: 
- When `init_complete` is received, call `WebSocketService.onInitCompleteReceived()`
- This should mark connection as healthy and cancel timeout

**Location**: `NetworkUtils.kt` line 402-408, new method in `WebSocketService.kt`

## Questions to Clarify

1. **`last_received_timeline_id` vs `last_received_sync_id`**: 
   - Code uses `last_received_sync_id` (from `sync_complete` message)
   - User mentions `last_received_timeline_id`
   - Are these the same value? Need to verify.

2. **Timeout duration**: 
   - How long should we wait for `init_complete` before considering it failed?
   - Suggested: 10-15 seconds

3. **Notification content**:
   - What should the notification say?
   - Suggested: "WebSocket connection issue - retrying..."

## Summary

| Feature | Status | Notes |
|---------|--------|-------|
| Connecting with `run_id` | ✅ | Working correctly |
| Ping/pong as authority | ✅ | Working correctly |
| Reconnection with `last_received_event` | ✅ | Working correctly |
| Drop old connection on reconnect | ✅ | Working correctly |
| Backend health check | ✅ | Working correctly |
| Wait for `init_complete` before CONNECTED | ❌ | **MISSING** |
| Timeout for `init_complete` | ❌ | **MISSING** |
| Exponential backoff for `init_complete` failures | ❌ | **MISSING** |
| Notification on `init_complete` failure | ❌ | **MISSING** |

