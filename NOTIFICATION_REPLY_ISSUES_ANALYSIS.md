# Notification Reply Issues Analysis

## Problem Summary
Replies from notifications sometimes get sent **3 times** or **not at all**. This document analyzes the root causes and proposes fixes.

## Root Causes Identified

### 1. **Deduplication Happens Before Send, But Failure Doesn't Clear It** ⚠️ CRITICAL

**Location**: `AppViewModel.sendMessageFromNotification()` (line 10233)

**Problem**:
```kotlin
// Mark this reply as sent (BEFORE actual send)
recentNotificationReplies[dedupKey] = now

// ... later ...
val result = sendWebSocketCommand("send_message", messageRequestId, commandData)

if (result != WebSocketResult.SUCCESS) {
    // FAILURE: But deduplication entry is NOT removed!
    // This blocks retries within the 5-second window
    return
}
```

**Impact**: If WebSocket fails, the message can't be retried for 5 seconds, causing "none at all" scenario.

**Fix**: Remove deduplication entry on failure, or only add it AFTER successful send.

---

### 2. **Ordered Broadcast Without Receiver Priority** ⚠️ HIGH

**Location**: `NotificationReplyReceiver.kt` (line 86)

**Problem**:
```kotlin
context.sendOrderedBroadcast(forwardIntent, null)  // No priority specified!
```

**Impact**: 
- MainActivity's receiver might not be first
- `abortBroadcast()` only works if receiver is first
- Multiple receivers might process the same reply → **3x sends**

**Fix**: Use explicit receiver priority or use `sendBroadcast()` with package restriction.

---

### 3. **Mismatched Deduplication Windows** ⚠️ MEDIUM

**Location**: 
- `NotificationReplyReceiver`: 3 seconds (line 26)
- `AppViewModel`: 5 seconds (line 178)

**Problem**: Different windows can cause race conditions where one layer thinks it's a duplicate but the other doesn't.

**Impact**: Inconsistent deduplication behavior.

**Fix**: Use the same deduplication window (5 seconds) in both places.

---

### 4. **No Retry Mechanism on WebSocket Failure** ⚠️ HIGH

**Location**: `AppViewModel.sendMessageFromNotification()` (line 10322)

**Problem**:
```kotlin
if (result != WebSocketResult.SUCCESS) {
    // Just logs error and returns - NO RETRY!
    android.util.Log.e("Andromuks", "Failed to send message from notification")
    return
}
```

**Impact**: If WebSocket is temporarily unavailable, message is lost → **"none at all"** scenario.

**Fix**: Queue failed messages for retry when WebSocket reconnects.

---

### 5. **Race Condition: Deduplication vs Actual Send** ⚠️ MEDIUM

**Location**: `AppViewModel.sendMessageFromNotification()` (lines 10233, 10319)

**Problem**: 
- Deduplication entry added at line 10233
- Actual send happens at line 10319
- If multiple threads/processes call this simultaneously, both might pass deduplication check before either sends

**Impact**: Multiple sends of the same message → **3x sends**.

**Fix**: Use synchronized block or atomic operations for the entire send process.

---

### 6. **Notification Update Interference** ⚠️ LOW

**Location**: `MainActivity.kt` (line 452) - `updateNotificationWithReply()`

**Problem**: 
- Notification update happens 200ms after send
- If notification update triggers another reply action somehow, it could cause duplicate sends

**Impact**: Rare, but possible duplicate sends.

**Fix**: Ensure notification updates don't trigger reply actions.

---

## Proposed Fixes

### Fix 1: Remove Deduplication Entry on Failure

```kotlin
// In AppViewModel.sendMessageFromNotification()
val result = sendWebSocketCommand("send_message", messageRequestId, commandData)

if (result != WebSocketResult.SUCCESS) {
    android.util.Log.e("Andromuks", "Failed to send message from notification, result: $result")
    
    // FIX: Remove deduplication entry to allow retry
    recentNotificationReplies.remove(dedupKey)
    
    messageRequests.remove(messageRequestId)
    if (pendingSendCount > 0) {
        pendingSendCount--
    }
    notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
    return
}

// Only mark as sent AFTER successful send
// (Move deduplication entry addition here)
```

### Fix 2: Use Explicit Receiver Priority

```kotlin
// In NotificationReplyReceiver.kt
val forwardIntent = Intent("net.vrkknn.andromuks.ACTION_REPLY").apply {
    setPackage(context.packageName)
    putExtra("room_id", roomId)
    putExtra("event_id", intent.getStringExtra("event_id"))
    putExtra("from_reply_receiver", true)
    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
}

// FIX: Use explicit priority
context.sendOrderedBroadcast(
    forwardIntent, 
    null,  // permission
    null,  // resultReceiver
    null,  // scheduler
    android.os.Process.SEND_RESULT_NOOP,  // initialCode
    null,  // initialData
    null   // initialExtras
)
```

**OR** use `sendBroadcast()` with package restriction (simpler):

```kotlin
context.sendBroadcast(forwardIntent)  // Simpler, but less control
```

### Fix 3: Synchronize Deduplication Check and Send

```kotlin
// In AppViewModel.sendMessageFromNotification()
synchronized(recentNotificationReplies) {
    val dedupKey = "$roomId|$text"
    val now = System.currentTimeMillis()
    val lastSentTime = recentNotificationReplies[dedupKey]
    
    if (lastSentTime != null && (now - lastSentTime) < Companion.NOTIFICATION_REPLY_DEDUP_WINDOW_MS) {
        // Duplicate detected
        onComplete?.invoke()
        return
    }
    
    // Mark as processing (not sent yet)
    recentNotificationReplies[dedupKey] = now
    
    // Send message
    val result = sendWebSocketCommand("send_message", messageRequestId, commandData)
    
    if (result != WebSocketResult.SUCCESS) {
        // Remove entry on failure to allow retry
        recentNotificationReplies.remove(dedupKey)
        // ... handle error ...
        return
    }
    
    // Success - entry remains for deduplication
}
```

### Fix 4: Add Retry Queue for Failed Messages

```kotlin
// In AppViewModel.sendMessageFromNotification()
if (result != WebSocketResult.SUCCESS) {
    android.util.Log.e("Andromuks", "Failed to send message from notification, result: $result")
    
    // FIX: Queue for retry when WebSocket reconnects
    if (result == WebSocketResult.NOT_CONNECTED) {
        pendingNotificationActions.add(
            PendingNotificationAction(
                type = "send_message",
                roomId = roomId,
                text = text,
                onComplete = onComplete
            )
        )
        android.util.Log.i("Andromuks", "Queued notification reply for retry when WebSocket reconnects")
    }
    
    // Remove deduplication entry to allow retry
    recentNotificationReplies.remove(dedupKey)
    
    messageRequests.remove(messageRequestId)
    if (pendingSendCount > 0) {
        pendingSendCount--
    }
    notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
    return
}
```

### Fix 5: Unify Deduplication Windows

```kotlin
// In NotificationReplyReceiver.kt
companion object {
    private const val DEDUP_WINDOW_MS = 5000L // Match AppViewModel's 5 seconds
}

// In AppViewModel.kt (already 5 seconds, no change needed)
private const val NOTIFICATION_REPLY_DEDUP_WINDOW_MS = 5000L
```

---

## Implementation Priority

1. **Fix 1** (Remove deduplication on failure) - **CRITICAL** - Fixes "none at all" issue
2. **Fix 2** (Receiver priority) - **HIGH** - Fixes "3x sends" issue
3. **Fix 3** (Synchronize deduplication) - **HIGH** - Prevents race conditions
4. **Fix 4** (Retry queue) - **MEDIUM** - Improves reliability
5. **Fix 5** (Unify windows) - **LOW** - Consistency improvement

---

## Testing Recommendations

1. **Test "none at all" scenario**:
   - Disable Wi-Fi/data before replying
   - Enable after 2 seconds
   - Verify message is sent (should be queued and retried)

2. **Test "3x sends" scenario**:
   - Send rapid replies from notification
   - Verify only one message is sent
   - Check logs for deduplication messages

3. **Test race condition**:
   - Send reply from notification while app is processing another reply
   - Verify no duplicate sends

4. **Test WebSocket reconnection**:
   - Disconnect WebSocket
   - Send reply from notification
   - Reconnect WebSocket
   - Verify queued message is sent

