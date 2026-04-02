# FCM Dismiss Notification Analysis

## Overview
This document analyzes the current implementation of FCM dismiss notifications and answers questions about notification management and chat bubble preservation.

## Current Implementation

### How Notifications Are Tracked

**Notification ID Generation:**
- Notifications use `roomId.hashCode()` as their notification ID
- This is consistent across the codebase:
  - `EnhancedNotificationDisplay.kt:368`: `val notifID = notificationData.roomId.hashCode()`
  - `NotificationMarkReadReceiver.kt:40`: `val notifID = roomId.hashCode()`
  - `FCMService.kt:502`: `val notificationId = generateNotificationId(roomId)` which uses `roomId?.hashCode()`

**Accessing Active Notifications:**
- The system provides `NotificationManager.activeNotifications` which returns an array of all active notifications
- We can query this to find notifications by their ID (which is derived from roomId)

### Current Dismiss Implementation

**Location:** `FCMService.kt:418-445`

The current `handleDismissNotification()` method:
- Parses the dismiss payload correctly
- Extracts room IDs from the dismiss array
- **Currently does NOT dismiss any notifications** - it just logs a message
- The reason: "to preserve bubbles"

```kotlin
// For now, let's not dismiss any notifications to preserve bubbles
// TODO: Implement proper bubble detection to selectively dismiss
Log.d(TAG, "Room $roomId - NOT dismissing notification to preserve bubble")
```

## Answers to Your Questions

### 1. Do we have a list of notifications (and the room they are assigned to) so that we may dismiss a notification simply by its !room_id?

**Answer: YES** ✅

**How it works:**
- Each notification has an ID generated from `roomId.hashCode()`
- We can access all active notifications via `NotificationManager.activeNotifications`
- We can find a specific notification by searching for one with `id == roomId.hashCode()`

**Implementation approach:**
```kotlin
val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
val notifID = roomId.hashCode()
val existingNotification = notificationManager.activeNotifications.firstOrNull { it.id == notifID }
```

**Note:** The notification ID is the absolute value of `roomId.hashCode()` in some places (see `FCMService.kt:553`), but typically just `roomId.hashCode()` is used directly.

### 2. Can we dismiss a notification, without destroying a chat bubble?

**Answer: PARTIALLY** ⚠️

**The Challenge:**
- In Android, when you dismiss a notification that has bubble metadata, the bubble typically disappears
- Bubbles are tied to notifications - if the notification is gone, the bubble usually closes
- However, bubbles can persist if:
  1. The notification is updated (not dismissed) with bubble metadata preserved
  2. The shortcut still exists (bubbles are linked to shortcuts)

**Potential Solutions:**
1. **Update instead of dismiss:** Instead of canceling the notification, update it to remove unread indicators while keeping it active
2. **Check bubble state:** Unfortunately, Android doesn't provide a direct API to check if a bubble is currently displayed
3. **Preserve bubble metadata:** When updating/dismissing, ensure bubble metadata is preserved if a bubble exists

**Current Evidence:**
- `EnhancedNotificationDisplay.kt:1143-1145` shows awareness of this issue:
  ```kotlin
  // Note: We don't copy bubble metadata because it's tied to the specific Intent
  // and recreating it would require the original PendingIntent which we don't have.
  // The bubble will work from the original notification creation.
  ```

### 3. If not, can we detect if a chat bubble for !room_id exists, and if not, dismiss the notification when the backend pushes that instruction?

**Answer: YES, with limitations** ✅

**Detection Approach:**

1. **Check if notification exists:**
   ```kotlin
   val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
   val notifID = roomId.hashCode()
   val existingNotification = notificationManager.activeNotifications.firstOrNull { it.id == notifID }
   ```

2. **Check if notification has bubble metadata:**
   ```kotlin
   val hasBubbleMetadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
       existingNotification?.notification?.bubbleMetadata != null
   } else {
       false
   }
   ```

3. **Check if shortcut exists (bubbles are linked to shortcuts):**
   ```kotlin
   val shortcutExists = ShortcutManagerCompat.getShortcuts(
       context, 
       ShortcutManagerCompat.FLAG_MATCH_DYNAMIC
   ).any { it.id == roomId }
   ```

**Limitations:**
- Android doesn't provide a direct API to check if a bubble is **currently displayed/active**
- We can only check if the notification has bubble metadata, not if the bubble is actually open
- However, if a notification has bubble metadata, it's likely that a bubble could be displayed

**Recommended Implementation Strategy:**

```kotlin
private fun handleDismissNotification(jsonObject: JSONObject) {
    try {
        val dismissArray = jsonObject.getJSONArray("dismiss")
        Log.d(TAG, "Found ${dismissArray.length()} dismiss requests")
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        for (i in 0 until dismissArray.length()) {
            val dismissItem = dismissArray.getJSONObject(i)
            val roomId = dismissItem.optString("room_id", "")
            
            if (roomId.isEmpty()) {
                Log.w(TAG, "Empty room_id in dismiss request, skipping")
                continue
            }
            
            Log.d(TAG, "Processing dismiss request for room: $roomId")
            
            val notifID = roomId.hashCode()
            val existingNotification = notificationManager.activeNotifications.firstOrNull { it.id == notifID }
            
            if (existingNotification == null) {
                Log.d(TAG, "No notification found for room: $roomId - nothing to dismiss")
                continue
            }
            
            // Check if notification has bubble metadata
            val hasBubbleMetadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                existingNotification.notification.bubbleMetadata != null
            } else {
                false
            }
            
            // Check if shortcut exists (bubbles are linked to shortcuts)
            val shortcutExists = ShortcutManagerCompat.getShortcuts(
                this,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC
            ).any { it.id == roomId }
            
            if (hasBubbleMetadata || shortcutExists) {
                // Notification has bubble metadata or shortcut exists - don't dismiss to preserve bubble
                Log.d(TAG, "Room $roomId - NOT dismissing notification (has bubble metadata or shortcut exists)")
                Log.d(TAG, "hasBubbleMetadata: $hasBubbleMetadata, shortcutExists: $shortcutExists")
            } else {
                // Safe to dismiss - no bubble metadata and no shortcut
                Log.d(TAG, "Room $roomId - Dismissing notification (no bubble metadata, no shortcut)")
                val notificationManagerCompat = NotificationManagerCompat.from(this)
                notificationManagerCompat.cancel(notifID)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error handling dismiss notification", e)
    }
}
```

## Summary

1. ✅ **Yes**, we can find and dismiss notifications by room_id using `roomId.hashCode()` as the notification ID
2. ⚠️ **Partially** - dismissing a notification with bubble metadata will likely close the bubble, but we can check for bubble metadata before dismissing
3. ✅ **Yes** - we can detect if a notification has bubble metadata and/or if a shortcut exists, and only dismiss if neither exists

## Implementation Status

✅ **IMPLEMENTED** - The solution has been implemented using a `BubbleTracker` singleton.

### Implementation Details

1. **BubbleTracker Singleton** (`BubbleTracker.kt`)
   - Thread-safe tracking of open bubbles by room ID
   - Methods: `onBubbleOpened()`, `onBubbleClosed()`, `isBubbleOpen()`
   - Tracks bubbles in a synchronized `MutableSet<String>`

2. **Bubble Lifecycle Tracking** (`BubbleTimelineScreen.kt`)
   - Uses `DisposableEffect` to track when bubble screen is shown/hidden
   - Automatically tracks bubble open when composable is composed
   - Automatically tracks bubble closed when composable is disposed

3. **FCM Dismiss Logic** (`FCMService.kt`)
   - Checks `BubbleTracker.isBubbleOpen()` before dismissing
   - Only relies on BubbleTracker - no fallback checks needed
   - Only dismisses notifications when it's safe (no active bubble)

### How It Works

1. When a bubble is opened:
   - `BubbleTimelineScreen`'s `DisposableEffect` calls `BubbleTracker.onBubbleOpened(roomId)`
   - Room ID is added to the tracked set

2. When a dismiss request arrives:
   - `FCMService.handleDismissNotification()` checks `BubbleTracker.isBubbleOpen(roomId)`
   - If bubble is open, notification is NOT dismissed
   - If bubble is not open, notification is dismissed

3. When a bubble is closed:
   - `BubbleTimelineScreen`'s `DisposableEffect`'s `onDispose` calls `BubbleTracker.onBubbleClosed(roomId)`
   - Room ID is removed from the tracked set

### Benefits

- ✅ Accurate tracking: Knows exactly which bubbles are open
- ✅ Thread-safe: Uses synchronized access for concurrent safety
- ✅ Automatic: No manual tracking needed, uses Compose lifecycle
- ✅ Single source of truth: Only relies on BubbleTracker, no fallback checks
- ✅ Simple API: Easy to use and understand

### Testing Recommendations

1. Test that bubbles remain open when dismiss notification arrives
2. Test that notifications are dismissed when no bubble is open
3. Test that multiple bubbles can be tracked simultaneously
4. Test edge cases (app restart, activity recreation, etc.)

