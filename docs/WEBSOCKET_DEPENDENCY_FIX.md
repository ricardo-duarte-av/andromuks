# WebSocket Dependency Fix

## Problem Analysis

From the cold start log analysis, the app was performing WebSocket-dependent operations before the WebSocket connection was established:

### Timeline of Issues (from init.log):

1. **23:08:37.423** - WebSocket connection starts
2. **23:08:37.429** - AuthCheckScreen navigates to room_list (WebSocket may not be connected)
3. **23:08:37.559** - RoomListScreen shows (spaces already loaded from cache)
4. **23:08:38.037** - RoomListScreen queries DB for 147 rooms missing previews
5. **23:08:38.039** - RoomListScreen requests profiles for 43 message senders
6. **23:08:38.054-145** - **12 warnings**: "WebSocket is not connected, cannot send command: get_room_state"
7. **23:08:38.288** - Backend health check completed, connecting WebSocket
8. **23:08:38.555** - WebSocket opened
9. **23:08:38.900** - Init complete received

### Root Causes:

1. **RoomListScreen shows too early**: The screen appears as soon as `spacesLoaded` is true (from database cache), but WebSocket isn't connected yet.

2. **Profile requests fail**: The opportunistic profile loading in RoomListScreen runs immediately when the screen shows, but WebSocket isn't connected yet, causing all those "WebSocket not connected, skipping on-demand profile request" warnings.

3. **get_room_state commands fail**: Similar issue - commands are sent before WebSocket is ready.

4. **No WebSocket readiness check**: RoomListScreen doesn't wait for WebSocket to be connected before performing WebSocket-dependent operations.

## Solution

### Changes Made:

1. **Added WebSocket connection check to RoomListScreen**:
   - Added state tracking for WebSocket connection status
   - Added polling mechanism to check WebSocket connection every 100ms
   - Added 5-second timeout fallback to prevent infinite loading
   - Updated loading screen to show "Connecting..." when waiting for WebSocket

2. **Deferred opportunistic profile loading**:
   - Modified `LaunchedEffect` for profile loading to depend on `websocketConnected`
   - Profile requests are now deferred until WebSocket is connected
   - Added debug logging to indicate when profile loading is deferred

3. **Loading screen improvements**:
   - Added "Connecting..." message when waiting for WebSocket
   - Shows appropriate loading messages based on what's being waited for

### Code Changes:

#### RoomListScreen.kt

1. **Added WebSocket connection tracking** (lines ~289-340):
   ```kotlin
   var websocketConnected by remember { mutableStateOf(appViewModel.isWebSocketConnected()) }
   var websocketWaitStartTime by remember { mutableStateOf<Long?>(null) }
   var websocketWaitTimeout by remember { mutableStateOf(false) }
   ```

2. **Added WebSocket connection polling**:
   - Checks connection status every 100ms until connected
   - 5-second timeout to prevent infinite loading

3. **Updated loading condition**:
   - Added `(!websocketConnected && !websocketWaitTimeout)` to loading check
   - UI only shows when WebSocket is connected (or timeout expired)

4. **Deferred profile loading**:
   - Changed `LaunchedEffect` dependency to include `websocketConnected`
   - Profile requests only happen when WebSocket is connected

## Benefits

1. **Eliminates "WebSocket not connected" errors**: All WebSocket-dependent operations now wait for connection
2. **Better user experience**: Clear loading messages indicate what's happening
3. **Graceful degradation**: 5-second timeout ensures UI shows even if WebSocket fails to connect
4. **Efficient resource usage**: Profile requests are batched and only sent when WebSocket is ready

## Testing

After these changes, the cold start log should show:
- No "WebSocket not connected" warnings during initial load
- Profile requests only happen after WebSocket connects
- Clear "Connecting..." message during WebSocket connection phase
- UI appears smoothly after WebSocket is ready

## Future Improvements

1. **Queue WebSocket operations**: Instead of failing, queue operations and execute when WebSocket connects
2. **Retry mechanism**: Automatically retry failed operations when WebSocket becomes available
3. **Connection state indicator**: Show connection status in UI (optional, for debugging)

