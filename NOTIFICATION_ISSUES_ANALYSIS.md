# Notification Navigation Issues Analysis

## Questions Answered

### 1. Why must RoomListScreen load before RoomTimelineScreen?

**Answer**: This is a **navigation graph constraint**, not a technical requirement.

The navigation graph in `MainActivity.kt` is structured as:
```
auth_check → room_list → room_timeline/{roomId}
```

When opening from a notification:
1. `AuthCheckScreen` runs first (always the start destination)
2. `AuthCheckScreen` navigates to `"room_list"` after WebSocket connects
3. `RoomListScreen` has `LaunchedEffect`s that detect `directRoomNavigation` 
4. `RoomListScreen` then navigates to `"room_timeline/{roomId}"`

**The problem**: This creates an **unnecessary intermediate step** that causes:
- 9+ second delays while waiting for `spacesLoaded`
- Room list showing incomplete data (missing rooms)
- Stale UI state

**Why it's this way**: The navigation graph was designed for normal app usage (app icon → room list → select room), not for direct navigation from notifications.

**Could we skip RoomListScreen?** Yes! We could navigate directly from `AuthCheckScreen` to `room_timeline/{roomId}` when opening from notification, similar to how `ShortcutActivity` works. But the current implementation forces the room_list route.

---

### 2. Why does starting from notification break RoomListScreen (missing rooms)?

**Answer**: **Timing issue** - RoomListScreen renders BEFORE AppViewModel is fully initialized.

#### When starting from App Icon:
1. App starts → MainActivity.onCreate
2. AppViewModel initializes
3. WebSocket connects
4. Initial sync messages arrive (3+ sync messages)
5. `spacesLoaded = true` is set (after 3 sync messages OR in `onInitComplete()`)
6. Room data is populated in `roomMap`
7. `AuthCheckScreen` navigates to `room_list`
8. `RoomListScreen` renders with **complete data** ✅

#### When starting from Notification:
1. Notification tap → MainActivity.onCreate
2. AppViewModel initializes (may attach to existing WebSocket)
3. `AuthCheckScreen` navigates to `room_list` **IMMEDIATELY** (if WebSocket already connected)
4. `RoomListScreen` renders **BEFORE** sync messages arrive
5. `spacesLoaded = false` (hasn't been set yet)
6. `roomMap` is empty or stale (from previous session)
7. Room list shows **incomplete/missing rooms** ❌
8. Navigation to room_timeline happens, but RoomListScreen already rendered with bad data

**Root causes**:
- `AuthCheckScreen` navigates too early (when WebSocket connects, not when data is ready)
- `RoomListScreen` reads from `appViewModel.getCurrentRoomSection()` which depends on:
  - `spacesLoaded = true` (set after 3 sync messages OR `onInitComplete()`)
  - `roomMap` populated from sync data
  - When opening from notification, these may not be ready yet

---

### 3. What is `awaitRoomDataReadiness()`?

**Answer**: A blocking function that waits for AppViewModel to be "ready" before proceeding.

**Location**: `AppViewModel.kt` line 342

**What it checks** (waits for ALL to be true):
```kotlin
suspend fun awaitRoomDataReadiness(
    timeoutMs: Long = 15_000L,  // 15 second timeout
    pollDelayMs: Long = 100L,
    requireInitComplete: Boolean = false
): Boolean {
    while (true) {
        val pendingReady = !isProcessingPendingItems    // No pending items processing
        val spacesReady = spacesLoaded                  // Spaces have loaded
        val syncReady = initialSyncComplete            // Initial sync is complete
        val initReady = !requireInitComplete || initializationComplete  // Init complete (if required)
        
        if (pendingReady && spacesReady && syncReady && initReady) {
            break  // All ready!
        }
        delay(pollDelayMs)  // Check every 100ms
    }
    return true  // Returns false if timeout
}
```

**What each condition means**:
- `pendingReady`: No items in the processing queue (sync_complete messages being processed)
- `spacesReady`: `spacesLoaded = true` (set after 3 sync messages OR `onInitComplete()`)
- `syncReady`: `initialSyncComplete = true` (set when first sync_complete message is processed)
- `initReady`: `initializationComplete = true` (set when `init_complete` WebSocket message received)

**Where it's called**:
- `RoomTimelineScreen.kt` line 1723:
  ```kotlin
  val readinessResult = appViewModel.awaitRoomDataReadiness(requireInitComplete = requireInitComplete)
  ```
  - Blocks the RoomTimelineScreen LaunchedEffect for up to 15 seconds
  - This is why you see the stall when opening from notification

**Why it blocks for 15 seconds**:
- When opening from notification, AppViewModel may not have received sync messages yet
- `spacesLoaded` may still be `false`
- `initialSyncComplete` may still be `false`
- The function waits for these to become true, but if they don't (e.g., WebSocket not connected properly), it times out after 15 seconds

---

## Summary

1. **RoomListScreen must load first** because of navigation graph structure (not a technical requirement)
2. **Notification breaks RoomListScreen** because it renders before AppViewModel is initialized (missing rooms in list)
3. **awaitRoomDataReadiness** blocks for up to 15 seconds waiting for AppViewModel state to be ready (spacesLoaded, initialSyncComplete, etc.)

## Potential Solutions

1. **Skip RoomListScreen for notifications**: Navigate directly from AuthCheckScreen to room_timeline (like ShortcutActivity does)
2. **Wait for data before navigating**: Don't navigate to room_list until spacesLoaded is true
3. **Load rooms from database**: RoomListScreen could load from database immediately (not wait for sync)

