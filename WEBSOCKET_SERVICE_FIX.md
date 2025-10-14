# WebSocket Service Architecture Fix

## Problem

The app was crashing after granting battery optimization permission with the error:
```
java.lang.IllegalArgumentException: Expected URL scheme 'http' or 'https' but no scheme was found
```

### Root Cause

The WebSocketService was designed to create and manage its own WebSocket connection, which created **two separate issues**:

1. **Duplicate Connection**: The service tried to create a second WebSocket connection while NetworkUtils already manages one
2. **Missing URL**: The service was started before `homeserverUrl` was available, causing it to try connecting with an empty URL

## Solution

**Simplified the WebSocketService to be just a foreground notification service:**

### Architecture Change

**Before:**
- NetworkUtils creates WebSocket connection ✅
- WebSocketService ALSO tries to create WebSocket connection ❌
- Result: Duplicate connections, complexity, crashes

**After:**
- NetworkUtils creates and manages WebSocket connection ✅
- WebSocketService only shows foreground notification ✅
- Result: Single connection, simple, reliable

### What WebSocketService Now Does

The service is now **ultra-simple** with a single purpose:

```kotlin
/**
 * WebSocketService - Foreground service that maintains app process
 * 
 * This service shows a persistent notification to prevent Android from killing
 * the app process. The actual WebSocket connection is managed by NetworkUtils
 * and AppViewModel, not by this service.
 */
```

**Features:**
1. Shows persistent foreground notification
2. Prevents Android from killing the app process
3. Keeps app eligible for background work
4. **Does NOT manage WebSocket connection**

### Files Modified

#### 1. WebSocketService.kt
**Removed:**
- All WebSocket connection management code
- `connectWebSocket()`, `disconnectWebSocket()`
- `processWebSocketMessage()`, `startPingLoop()`, `sendPing()`, `sendPong()`
- `setConnectionParameters()` and related state variables
- All OkHttp and WebSocket-related code

**Kept:**
- Foreground service notification
- Service lifecycle (onCreate, onStartCommand, onDestroy)
- Notification channel creation
- Simple binder for potential future use

**Result:** ~250 lines of code removed, service is now ~100 lines

#### 2. AppViewModel.kt - startWebSocketService()
**Before:**
```kotlin
fun startWebSocketService() {
    // Complex binding and parameter passing
    bindService(...) {
        service.setConnectionParameters(
            realMatrixHomeserverUrl,  // ❌ Often empty!
            authToken,
            this@AppViewModel
        )
    }
}
```

**After:**
```kotlin
fun startWebSocketService() {
    // Just start the service - simple!
    val intent = Intent(context, WebSocketService::class.java)
    context.startForegroundService(intent)
}
```

**Result:** No parameter passing, no binding complexity, no race conditions

#### 3. AppViewModel.kt - onInitComplete()
**Added service start here:**
```kotlin
fun onInitComplete() {
    spacesLoaded = true
    populateSpaceEdges()
    
    // Start service after everything is initialized
    startWebSocketService()  // ← Added here
    
    // ... rest of initialization
}
```

**Why here?** This is called after:
- WebSocket is connected
- Initial sync is complete
- All state is loaded
- No more race conditions!

#### 4. AuthCheck.kt
**Removed premature service start:**
```kotlin
// BEFORE - Started too early!
startWebSocketService()  // ❌ homeserverUrl not ready
connectToWebsocket(...)

// AFTER - Service starts later in onInitComplete
connectToWebsocket(...)  // ✅ Just connect, service starts later
```

## Benefits

### ✅ No More Crashes
- Service doesn't try to connect with empty URL
- No IllegalArgumentException
- Service starts at the right time

### ✅ Single WebSocket Connection
- NetworkUtils manages the connection (as it always did)
- Service just keeps process alive
- No duplicate connections
- No message routing confusion

### ✅ Simpler Code
- 250+ lines removed from service
- No complex message forwarding
- No duplicate ping/pong logic
- Easier to understand and maintain

### ✅ Proper Timing
- Service starts AFTER initialization complete
- All parameters available when needed
- No race conditions

### ✅ Same Functionality
- WebSocket still maintained in background
- Foreground notification still shows
- Android won't kill the process
- Users see "Maintaining connection..." notification

## How It Works Now

### Startup Flow

1. **User opens app**
2. **AuthCheck** validates credentials
3. **Permissions screen** (if needed)
4. **AuthCheck** continues after permissions
5. **NetworkUtils.connectToWebsocket()** establishes connection
6. **WebSocket connects** → `onOpen()` called
7. **Messages flow** → sync_complete, client_state, init_complete
8. **onInitComplete()** called
   - State is fully loaded
   - `startWebSocketService()` called ← **Service starts here**
9. **Foreground notification** appears
10. **App proceeds** to room list

### Background Operation

1. **App backgrounded** → MainActivity.onPause()
2. **AppViewModel.onAppBecameInvisible()** → Saves state
3. **WebSocket stays connected** (managed by NetworkUtils)
4. **Foreground service running** → Process won't be killed
5. **Messages received** → Processed by AppViewModel
6. **Notifications shown** → User sees new messages

### Service Role

```
┌─────────────────────────────────────────┐
│         WebSocketService                │
│  ┌───────────────────────────────────┐  │
│  │   Foreground Notification         │  │
│  │   "Maintaining connection..."     │  │
│  └───────────────────────────────────┘  │
│                                         │
│  Purpose: Keep process alive            │
│  Does NOT: Manage WebSocket            │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│    NetworkUtils + AppViewModel          │
│  ┌───────────────────────────────────┐  │
│  │   WebSocket Connection            │  │
│  │   • Connect/Reconnect             │  │
│  │   • Send/Receive                  │  │
│  │   • Ping/Pong                     │  │
│  │   • Message Processing            │  │
│  └───────────────────────────────────┘  │
│                                         │
│  Purpose: Manage WebSocket              │
└─────────────────────────────────────────┘
```

## Testing Performed

- [x] Fresh install → No crash
- [x] Grant permissions → Service starts successfully
- [x] Foreground notification appears
- [x] WebSocket connects and stays connected
- [x] App backgrounded → Connection maintained
- [x] Messages received in background
- [x] No duplicate connections
- [x] No IllegalArgumentException

## Future Considerations

### Optional: Service Can Monitor Connection

If we want the service to do more than just show a notification, we could:

1. **Add connection status monitoring**:
   ```kotlin
   fun updateConnectionStatus(isConnected: Boolean) {
       updateNotification(
           if (isConnected) "Connected" else "Reconnecting..."
       )
   }
   ```

2. **Call from AppViewModel**:
   ```kotlin
   // In NetworkUtils.onOpen
   appViewModel.notifyServiceConnectionStatus(true)
   
   // In NetworkUtils.onClosed
   appViewModel.notifyServiceConnectionStatus(false)
   ```

### Optional: Service Statistics

Could track and display in notification:
- Messages received count
- Last message timestamp
- Connection uptime
- Data usage

But for now, **simple is better** - just keep the process alive!

## Rollback Plan

If issues arise:
1. Git revert the commits
2. Service goes back to managing connection
3. Fix the URL availability issue differently

But current architecture is cleaner and more maintainable.

---

**Last Updated:** [Current Date]  
**Related Documents:**
- WEBSOCKET_SERVICE_DEFAULT_IMPLEMENTATION.md
- PERMISSIONS_IMPLEMENTATION.md

