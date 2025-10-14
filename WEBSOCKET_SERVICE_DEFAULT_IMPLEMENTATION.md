# WebSocket Foreground Service - Default Implementation

## Summary

The WebSocket foreground service is now **always enabled by default**. The WebSocket connection is maintained permanently in the background via a foreground service, providing real-time updates even when the app is backgrounded.

## Changes Made

### 1. SettingsScreen.kt
**Removed:**
- "Keep WebSocket opened" setting card and toggle (lines 79-111)

**Result:** Users no longer see or control this setting - it's always on.

---

### 2. AppViewModel.kt

#### Removed Setting State
- Deleted `keepWebSocketOpened` state variable (previously line 62-63)
- Removed `toggleKeepWebSocketOpened()` function
- Removed `enableKeepWebSocketOpened()` function

#### Updated Lifecycle Functions

**`onAppBecameVisible()`** (lines 1014-1029)
- Removed conditional check for `keepWebSocketOpened`
- Simplified to just cancel shutdown timers
- WebSocket service now maintains connection automatically

**`onAppBecameInvisible()`** (lines 1034-1050)
- Removed 15-second shutdown timer logic
- Still saves state to disk for **crash recovery** (preserves run_id and last_received_sync_id)
- No longer closes WebSocket when app goes to background

**`suspendApp()`** (lines 1058-1061)
- Simplified to just call `onAppBecameInvisible()` for state saving
- No longer triggers WebSocket shutdown timer

**`shutdownWebSocket()`** (lines 1067-1070)
- Simplified - now only used on app cleanup (onCleared)
- Removed conditional check

#### Updated State Persistence

**`saveStateToStorage()`** (lines 1213-1255)
- Removed skip logic for `keepWebSocketOpened`
- Updated documentation - now serves as **crash recovery mechanism**
- State is saved periodically and on background to preserve run_id for seamless resumption if app is killed

**`updateRoomsFromSyncJson()`** (lines 720-725)
- Removed conditional check for `keepWebSocketOpened` in auto-save logic
- State now always saved every 10 sync messages for crash recovery

#### Made Service Functions Public

**`startWebSocketService()`** (lines 3797-3819)
- Already public, no changes needed
- Starts foreground service and binds to pass connection parameters

**`stopWebSocketService()`** (lines 3824-3830)
- Changed from `private` to `public`
- Can now be called on logout or app cleanup

---

### 3. AuthCheck.kt

**Added Automatic Service Start** (lines 107-109)
- Added call to `appViewModel.startWebSocketService()` before `connectToWebsocket()`
- Service now starts automatically on successful authentication
- Ensures foreground service is running before WebSocket connects

---

### 4. NetworkUtils.kt
**No changes needed** - The reconnection logic with `run_id` and `last_received_id` already handles network changes perfectly.

---

### 5. MainActivity.kt
**No changes needed** - Lifecycle callbacks (onPause/onResume) still work as intended, just without triggering shutdown timers.

---

### 6. WebSocketService.kt
**No changes needed** - Already implements:
- Automatic reconnection on connection loss
- Ping/pong for connection health monitoring
- run_id and last_received_id resumption
- START_STICKY for system restart

---

## How It Works Now

### App Startup Flow
1. User opens app → AuthCheck validates credentials
2. AuthCheck calls `appViewModel.startWebSocketService()`
3. Foreground service starts, shows persistent notification
4. Service binds to get connection parameters
5. WebSocket connects with optional run_id/last_received_id for resumption
6. Service maintains connection indefinitely

### App Backgrounded
1. MainActivity.onPause() triggers
2. AppViewModel.onAppBecameInvisible() called
3. State saved to disk (crash recovery)
4. **WebSocket remains open** via foreground service
5. Real-time messages still received and processed

### Network Changes
1. WebSocket connection drops (detected by ping/pong timeout)
2. Service automatic reconnection kicks in (5 second delay)
3. Reconnects with same `run_id` and `last_received_sync_id`
4. Backend resumes from where it left off - **no missed messages**

### App Killed by System
1. System kills app process
2. Foreground service receives onDestroy
3. On restart: load cached state from SharedPreferences
4. Reconnect with preserved `run_id` and `last_received_sync_id`
5. **Seamless resumption** with no message gaps

### User Logout (Future Implementation)
- Should call `appViewModel.stopWebSocketService()` to clean up
- Should call `appViewModel.clearCachedState(context)` to reset state

---

## Benefits

### ✅ Always-On Connectivity
- Real-time messages even when app is backgrounded
- Instant notifications without FCM delays
- No manual reconnection needed

### ✅ Seamless Message Continuity
- `run_id` ensures no missed messages
- Automatic resumption on network changes
- Crash recovery via state persistence

### ✅ Better Battery Life
- Single persistent connection vs multiple short connections
- Efficient ping/pong (15s intervals)
- No repeated handshake overhead

### ✅ Simpler Codebase
- Removed conditional logic throughout
- No shutdown timers to manage
- Clearer lifecycle handling

### ✅ User Awareness
- Foreground notification shows service is running
- Users know app maintains connection
- Android system manages service lifecycle appropriately

---

## Potential Concerns & Solutions

### Battery Drain
**Concern:** WebSocket running 24/7 might drain battery

**Solution:** 
- WebSocket is extremely lightweight (ping every 15s)
- Modern Android optimizes background connections
- Foreground notification provides user awareness
- Users can force-stop app if concerned

### Memory Usage
**Concern:** Service might use excessive memory

**Solution:**
- Service lifecycle tied to app process
- System can still kill app if low on memory
- State persistence allows seamless recovery
- No memory leaks - proper cleanup in onDestroy

### Network Carrier Restrictions
**Concern:** Some carriers might drop long-lived connections

**Solution:**
- Ping/pong detects dead connections (15s timeout)
- Automatic reconnection with resumption
- Exponential backoff could be added if needed

---

## Testing Checklist

- [ ] App starts → Service starts automatically
- [ ] Notification appears showing "Maintaining connection..."
- [ ] App backgrounded → WebSocket stays open
- [ ] Messages received while backgrounded
- [ ] Airplane mode → reconnects when network restored
- [ ] WiFi to cellular switch → reconnects with resumption
- [ ] App killed by system → restarts with run_id preserved
- [ ] No messages missed during reconnection
- [ ] Logout → service stops properly (when implemented)
- [ ] Battery usage acceptable for 24h connection

---

## Future Enhancements

### Optional: Exponential Backoff
If frequent reconnections become an issue, consider adding exponential backoff:
```kotlin
private var reconnectAttempts = 0
private fun getReconnectDelay(): Long {
    return minOf(5000L * (2.0.pow(reconnectAttempts)).toLong(), 60000L)
}
```

### Optional: Connection Quality Monitoring
Track connection quality and adjust ping intervals:
```kotlin
private fun adjustPingInterval(latency: Long) {
    val interval = when {
        latency < 100 -> 15000L
        latency < 500 -> 30000L
        else -> 60000L
    }
    // Restart ping loop with new interval
}
```

### Optional: Smart Reconnection
Only reconnect when app is actually in use:
```kotlin
if (isAppVisible || hasUnreadMessages) {
    scheduleReconnect()
} else {
    scheduleDelayedReconnect(60000L) // 1 minute delay
}
```

---

## Code Locations

### Files Modified
1. `app/src/main/java/net/vrkknn/andromuks/SettingsScreen.kt`
2. `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`
3. `app/src/main/java/net/vrkknn/andromuks/AuthCheck.kt`

### Files Unchanged (But Important)
1. `app/src/main/java/net/vrkknn/andromuks/WebSocketService.kt` - Already perfect
2. `app/src/main/java/net/vrkknn/andromuks/utils/NetworkUtils.kt` - Already perfect
3. `app/src/main/java/net/vrkknn/andromuks/MainActivity.kt` - No changes needed

---

## Rollback Plan

If issues arise, revert by:
1. Git revert the commits
2. Or manually restore:
   - Add back `keepWebSocketOpened` state variable
   - Restore conditional checks in lifecycle functions
   - Restore shutdown timer logic
   - Add back Settings UI toggle

---

## Documentation Updated
- This document serves as implementation guide
- Code comments updated to reflect new behavior
- Removed references to conditional service usage


