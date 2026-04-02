# FCM Notification Action Implementation

## Overview

This implementation adds proper websocket connection management for FCM notification actions (Reply and Mark Read). The solution ensures that notification actions work correctly regardless of the app's visibility state and websocket connection status.

## Problem Statement

Previously, when using the Reply or Mark Read actions from FCM notifications:
- Messages would be sent but the action would never complete (eternal spinner)
- The websocket connection state wasn't properly checked
- No mechanism existed to reconnect the websocket for background notification actions
- No response tracking to know when the action completed

## Solution Architecture

### A. When Socket is NOT Connected/Ready

1. **Queue the action** - The action is queued as a pending notification action
2. **Reconnect websocket** - If app is not visible, trigger websocket reconnection with saved state (run_id, last_received_id)
3. **Wait for init_complete** - The websocket initialization completes and loads room data
4. **Execute pending actions** - All queued actions are executed once websocket is ready
5. **Track responses** - Each action has a request_id tracked for response matching
6. **Auto-shutdown** - After 15 seconds, websocket is shut down if app is still not visible

### B. When Socket IS Connected/Ready (or persistent connection enabled)

1. **Execute immediately** - Send the command directly via websocket
2. **Track response** - Use request_id to match the response
3. **Invoke completion callback** - Mark the action as complete when response arrives
4. **No shutdown** - If app is visible or persistent connection is enabled, keep websocket open

## Implementation Details

### 1. AppViewModel Changes

#### New State Tracking
```kotlin
// Pending notification actions queue
private data class PendingNotificationAction(
    val type: String, // "send_message" or "mark_read"
    val roomId: String,
    val text: String? = null,
    val eventId: String? = null,
    val requestId: Int? = null,
    val onComplete: (() -> Unit)? = null
)

private val pendingNotificationActions = mutableListOf<PendingNotificationAction>()
private var notificationActionInProgress = false
private var notificationActionShutdownTimer: Job? = null
private val notificationActionCompletionCallbacks = mutableMapOf<Int, () -> Unit>()
```

#### New Methods

**`sendMessageFromNotification(roomId: String, text: String, onComplete: (() -> Unit)?)`**
- Checks websocket state
- Queues action if websocket not ready
- Triggers reconnection if app not visible
- Sends message when ready
- Schedules auto-shutdown

**`markRoomAsReadFromNotification(roomId: String, eventId: String, onComplete: (() -> Unit)?)`**
- Same pattern as sendMessageFromNotification
- Marks room as read when websocket ready

**`executePendingNotificationActions()`**
- Called from `onInitComplete()`
- Executes all queued notification actions
- Clears the queue after execution

**`scheduleNotificationActionShutdown()`**
- Schedules websocket shutdown after 15 seconds
- Only shuts down if app still not visible
- Cancels if app becomes visible

#### Updated Methods

**`onInitComplete()`**
- Now calls `executePendingNotificationActions()` after initialization
- Ensures queued actions execute once websocket is ready

**`onAppBecameVisible()`**
- Cancels notification action shutdown timer
- Clears notification action in progress flag

**`handleMessageResponse(requestId: Int, data: Any)`**
- Invokes completion callbacks for notification actions
- Removes callback from map after invocation

**`handleMarkReadResponse(requestId: Int, success: Boolean)`**
- Invokes completion callbacks for notification actions
- Removes callback from map after invocation

### 2. MainActivity Changes

#### Updated Broadcast Receivers

Both `registerNotificationBroadcastReceiver()` and `registerNotificationActionReceiver()` now use the new notification-specific methods:

**Before:**
```kotlin
appViewModel.sendMessage(roomId, replyText)
appViewModel.markRoomAsRead(roomId, eventId)
```

**After:**
```kotlin
appViewModel.sendMessageFromNotification(roomId, replyText) {
    Log.d("Andromuks", "Reply message sent successfully")
}

appViewModel.markRoomAsReadFromNotification(roomId, eventId) {
    Log.d("Andromuks", "Mark read completed successfully")
    // Dismiss notification here
}
```

## Flow Diagrams

### Scenario 1: App Not Visible, WebSocket Not Connected

```
User taps "Reply" on notification
    ↓
MainActivity.notificationActionReceiver receives ACTION_REPLY
    ↓
Calls appViewModel.sendMessageFromNotification(roomId, text, onComplete)
    ↓
Checks: webSocket == null || !spacesLoaded → TRUE
    ↓
Queues action in pendingNotificationActions
    ↓
Checks: !isAppVisible → TRUE
    ↓
Sets notificationActionInProgress = true
    ↓
Calls restartWebSocketConnection()
    ↓
NetworkUtils.connectToWebsocket() reconnects with saved state
    ↓
WebSocket sends init with run_id and last_received_id
    ↓
Server responds with partial sync until init_complete
    ↓
AppViewModel.onInitComplete() is called
    ↓
Calls executePendingNotificationActions()
    ↓
Executes sendMessageFromNotification() again
    ↓
This time: webSocket != null && spacesLoaded → TRUE
    ↓
Generates request_id, stores completion callback
    ↓
Sends WebSocket command: send_message
    ↓
Schedules shutdown in 15 seconds
    ↓
Server responds with request_id
    ↓
handleMessageResponse(requestId, data) is called
    ↓
Invokes completion callback
    ↓
After 15 seconds: shutdownWebSocket() if still !isAppVisible
```

### Scenario 2: App Visible, WebSocket Connected

```
User taps "Reply" on notification
    ↓
MainActivity.notificationActionReceiver receives ACTION_REPLY
    ↓
Calls appViewModel.sendMessageFromNotification(roomId, text, onComplete)
    ↓
Checks: webSocket != null && spacesLoaded → TRUE
    ↓
Generates request_id, stores completion callback
    ↓
Sends WebSocket command: send_message
    ↓
No shutdown scheduled (app is visible)
    ↓
Server responds with request_id
    ↓
handleMessageResponse(requestId, data) is called
    ↓
Invokes completion callback
```

### Scenario 3: Persistent Connection Enabled

```
User taps "Reply" on notification (app in background)
    ↓
MainActivity.notificationActionReceiver receives ACTION_REPLY
    ↓
Calls appViewModel.sendMessageFromNotification(roomId, text, onComplete)
    ↓
Checks: webSocket != null && spacesLoaded → TRUE
    ↓
(WebSocket maintained by foreground service)
    ↓
Generates request_id, stores completion callback
    ↓
Sends WebSocket command: send_message
    ↓
Shutdown scheduled but won't execute (service keeps connection)
    ↓
Server responds with request_id
    ↓
handleMessageResponse(requestId, data) is called
    ↓
Invokes completion callback
```

## Key Features

1. **Automatic Reconnection** - Websocket automatically reconnects when needed for notification actions
2. **State Preservation** - Uses saved run_id and last_received_id for efficient reconnection
3. **Action Queuing** - Actions are queued if websocket not ready
4. **Response Tracking** - Each action has a request_id for response matching
5. **Completion Callbacks** - Actions can be notified when complete (dismiss notification, etc.)
6. **Auto-Shutdown** - Websocket shuts down after 15 seconds if app not visible
7. **Smart Cancellation** - Shutdown cancelled if app becomes visible
8. **Persistent Connection Support** - Works with keepWebSocketOpened setting

## Request ID Logic

The implementation uses the existing request_id mechanism:
- Each send_message or mark_read command gets a unique request_id from `getNextRequestId()`
- The request_id is stored in the appropriate request map (messageRequests or markReadRequests)
- For notification actions, a completion callback is also stored in `notificationActionCompletionCallbacks`
- When the server responds, the response is matched by request_id
- The completion callback is invoked and removed from the map

## Testing Scenarios

To test this implementation:

1. **Background Reply** - Put app in background, receive notification, tap Reply
   - Expected: Message sends, spinner completes, notification updates

2. **Background Mark Read** - Put app in background, receive notification, tap Mark Read
   - Expected: Room marked as read, notification dismissed

3. **Foreground Reply** - App in foreground, receive notification, tap Reply
   - Expected: Message sends immediately, spinner completes

4. **Persistent Connection** - Enable "Keep WebSocket Opened", put app in background, use notification actions
   - Expected: Actions execute immediately using existing connection

5. **Multiple Actions** - Queue multiple notification actions before websocket ready
   - Expected: All actions execute once websocket ready

6. **App Becomes Visible** - Start notification action, open app before completion
   - Expected: Action completes, no shutdown occurs

## Benefits

- ✅ No more eternal spinner on notification replies
- ✅ Actions work from any app state
- ✅ Efficient reconnection using saved state
- ✅ Minimal battery impact (15-second connection for actions)
- ✅ Respects persistent connection setting
- ✅ Proper response tracking
- ✅ Clean separation of notification vs regular actions

## Files Modified

- `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt` - Core logic and state management
- `app/src/main/java/net/vrkknn/andromuks/MainActivity.kt` - Broadcast receiver updates with notification update logic
- `app/src/main/java/net/vrkknn/andromuks/EnhancedNotificationDisplay.kt` - New `updateNotificationWithReply()` method for MessagingStyle updates

