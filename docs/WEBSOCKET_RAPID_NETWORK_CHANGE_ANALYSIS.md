# WebSocket Reconnection Analysis: Rapid Network Changes

## User Scenario

1. **Initial State**: Connected to WiFi AP1, websocket working
2. **Move to bathroom**: WiFi AP1 disconnects (out of range)
3. **Phone switches to 5G**: ~1 second duration
4. **Phone connects to WiFi AP2**: Different AP name
5. **Result**: Websocket stuck in "Connecting..." state, never recovers

## Root Cause Analysis

### The Problem Flow

```
Time 0s:  WiFi AP1 connected → WebSocket CONNECTED
Time 1s:  WiFi AP1 disconnects → onNetworkLost() called
          - Cancels networkChangeDebounceJob
          - Sets connectionState = DISCONNECTED
          - Clears WebSocket
          
Time 2s:  5G connects → onNetworkAvailable(CELLULAR) called
          - Cancels any existing networkChangeDebounceJob (none)
          - Starts NEW networkChangeDebounceJob (500ms delay)
          - After 500ms: Network validation starts (2s timeout)
          - After validation: scheduleReconnection() called
          - Reconnection job starts → connectionState = RECONNECTING
          - Reconnection job validates network, checks backend health
          - Reconnection job calls invokeReconnectionCallback()
          - WebSocket connection starts → connectionState = CONNECTING
          
Time 3s:  WiFi AP2 connects → onNetworkAvailable(WIFI) called
          - Cancels previous networkChangeDebounceJob (if still running)
          - Starts NEW networkChangeDebounceJob (500ms delay)
          - After 500ms: Network validation starts
          - After validation: Checks connectionState
          - **PROBLEM**: connectionState is already CONNECTING
          - Code at line 2836-2840: "Already connecting - don't trigger another reconnection"
          - **Returns early without checking if connection is stuck**
```

### The Critical Bug

**Location**: `WebSocketService.kt:2836-2840`

```kotlin
} else if (connectionState == ConnectionState.CONNECTING) {
    // Already connecting - don't trigger another reconnection
    if (BuildConfig.DEBUG) {
        android.util.Log.d("WebSocketService", "Network validated but WebSocket already connecting - waiting for init_complete")
    }
}
```

**Problem**: When a network change occurs while the connection is in `CONNECTING` state, the code assumes the connection is progressing normally and returns early. However, it doesn't check if:

1. The connection is actually stuck (waiting too long for `init_complete`)
2. The previous network change's reconnection attempt might have failed
3. The WebSocket might be in a broken state

### Why It Gets Stuck

1. **First network change (5G)**: Triggers reconnection, sets state to `CONNECTING`
2. **Second network change (WiFi AP2)**: Detects `CONNECTING` state and assumes connection is in progress
3. **No timeout check**: The code doesn't verify if the connection has been stuck in `CONNECTING` for too long
4. **No recovery**: Since the code returns early, no recovery mechanism is triggered
5. **Stuck forever**: The connection remains in `CONNECTING` state indefinitely

### Additional Issues

1. **Debounce job cancellation**: When WiFi AP2 connects, it cancels the debounce job from 5G connection. If the debounce job was about to trigger reconnection, that gets cancelled.

2. **Reconnection job state**: The reconnection job from the 5G connection might still be running when WiFi AP2 connects. The new network change doesn't check if the previous reconnection job is stuck.

3. **Network validation timing**: Network validation takes 2 seconds. If multiple network changes happen within 2 seconds, validation might fail or timeout, but the code might still proceed.

## Code Locations

### Primary Issue
- **File**: `app/src/main/java/net/vrkknn/andromuks/WebSocketService.kt`
- **Lines**: 2836-2840 (onNetworkAvailable callback)
- **Function**: `startNetworkMonitoring()` → `onNetworkAvailable` lambda

### Related Code
- **Lines**: 2768-2855 (onNetworkAvailable handler)
- **Lines**: 2124-2294 (scheduleReconnection function)
- **Lines**: 2640-2678 (stuck state detection in unified monitoring)
- **Lines**: 1585-1632 (detectAndRecoverStateCorruption function)

## Proposed Fix

### Option 1: Check for Stuck CONNECTING State (Recommended)

Modify the `onNetworkAvailable` handler to check if the connection is stuck before returning early:

```kotlin
} else if (connectionState == ConnectionState.CONNECTING) {
    // Check if connection is stuck (waiting too long)
    val timeSinceConnect = if (serviceInstance.connectionStartTime > 0) {
        System.currentTimeMillis() - serviceInstance.connectionStartTime
    } else {
        0L
    }
    
    // If stuck for >20 seconds, force recovery
    if (timeSinceConnect > 20_000) {
        android.util.Log.w("WebSocketService", "Network validated but WebSocket stuck in CONNECTING for ${timeSinceConnect}ms - forcing recovery")
        clearWebSocket("Network change: Connection stuck in CONNECTING")
        scheduleReconnection("Network change: Recovering from stuck CONNECTING state")
    } else {
        // Already connecting and not stuck - wait for init_complete
        if (BuildConfig.DEBUG) {
            android.util.Log.d("WebSocketService", "Network validated but WebSocket already connecting (${timeSinceConnect}ms) - waiting for init_complete")
        }
    }
}
```

### Option 2: Always Reconnect on Network Type Change

If the network type actually changed (e.g., CELLULAR → WIFI), always force a reconnection:

```kotlin
} else if (connectionState == ConnectionState.CONNECTING) {
    // If network type changed, force reconnection even if already connecting
    if (previousNetworkType != newNetworkType && previousNetworkType != NetworkType.NONE) {
        android.util.Log.w("WebSocketService", "Network type changed while connecting - forcing new connection ($previousNetworkType → $newNetworkType)")
        clearWebSocket("Network type changed: $previousNetworkType → $newNetworkType")
        scheduleReconnection("Network type changed: $previousNetworkType → $newNetworkType")
    } else {
        // Same network type, already connecting - wait for init_complete
        if (BuildConfig.DEBUG) {
            android.util.Log.d("WebSocketService", "Network validated but WebSocket already connecting - waiting for init_complete")
        }
    }
}
```

### Option 3: Cancel Stuck Reconnection Jobs

When a new network change occurs, cancel any stuck reconnection jobs:

```kotlin
} else if (connectionState == ConnectionState.CONNECTING) {
    // Check if reconnection job is stuck
    val timeSinceReconnect = if (serviceInstance.lastReconnectionTime > 0) {
        System.currentTimeMillis() - serviceInstance.lastReconnectionTime
    } else {
        0L
    }
    
    // If reconnection job has been running for >10 seconds, cancel it and start fresh
    if (timeSinceReconnect > 10_000) {
        android.util.Log.w("WebSocketService", "Network validated but reconnection job stuck for ${timeSinceReconnect}ms - cancelling and restarting")
        synchronized(serviceInstance.reconnectionLock) {
            serviceInstance.reconnectionJob?.cancel()
            serviceInstance.reconnectionJob = null
            serviceInstance.isReconnecting = false
        }
        clearWebSocket("Network change: Reconnection job stuck")
        scheduleReconnection("Network change: Restarting stuck reconnection")
    } else {
        // Already connecting and not stuck - wait for init_complete
        if (BuildConfig.DEBUG) {
            android.util.Log.d("WebSocketService", "Network validated but WebSocket already connecting - waiting for init_complete")
        }
    }
}
```

## Recommended Solution

**Combine Option 1 and Option 2**: Check for stuck state AND always reconnect on network type change. This provides the most robust recovery.

## Testing

To test the fix, simulate the scenario:
1. Connect to WiFi AP1
2. Move out of range (WiFi disconnects)
3. Wait for 5G to connect (briefly)
4. Connect to WiFi AP2
5. Verify websocket recovers within 20-30 seconds

## Related Issues

- The unified monitoring (every 30s) should detect stuck CONNECTING states, but it only checks every 30 seconds
- The debounce delay (500ms) might be too short for rapid network changes
- Network validation timeout (2s) might be too long for rapid changes

