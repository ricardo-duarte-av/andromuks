# Navigation Refactor Plan

This document describes a phased plan to simplify the room-navigation flow, `currentRoomId` lifecycle, and chat-bubble isolation. The goal is to reduce accidental complexity without changing observable behaviour.

---

## Problem Summary

The current navigation-to-room flow has grown organically and now exhibits several symptoms of over-complexity:

- **Redundant state**: `directRoomNavigation`, `directRoomNavigationTrigger`, `directRoomNavigationTimestamp`, `isPendingNavigationFromNotification`, `openedViaDirectNotification`, `pendingRoomNavigation`, `pendingRoomToRestore` all represent facets of the same concept ("a room was requested, go there").
- **Copy-pasted logic**: The flush→clear→navigate pattern appears in 6 separate `LaunchedEffect` blocks in `RoomListScreen.kt`. An invariant that must hold in all 6 places will eventually be broken in one of them (and has been, twice).
- **`currentRoomId` overloaded**: It serves as a notification-suppression signal, a timeline-rebuild abort guard, a `timelineRefreshTrigger` condition, and a background-restore vehicle via `pendingRoomToRestore`. These responsibilities conflict and require careful ordering throughout the lifecycle.
- **Fragile ordering invariants**: `flushSyncBatchForRoom` must happen before `clearDirectRoomNavigation` to prevent `navigateToRoomListIfNeeded` from wiping the backstack mid-suspension. This is a non-obvious constraint with no compile-time enforcement.
- **Chat-bubble singleton leak**: `RoomTimelineCache.getOpenedRooms()` is a process-wide singleton. Bubble VMs must not iterate it, requiring a special `instanceRole == BUBBLE` guard that is easy to forget.

---

## Phase 1 — Extract the duplicated navigation pattern

**Risk**: Low. Pure refactor, no behavioural change.  
**Scope**: `RoomListScreen.kt` only.  
**Value**: Eliminates the 6-copy invariant risk immediately.

### What to do

Extract the repeated flush→clear→navigate sequence into a single private suspend function at the top of the `RoomListScreen` composable file:

```kotlin
private suspend fun executeRoomNavigation(
    appViewModel: AppViewModel,
    navController: NavController,
    roomId: String,
    notificationTimestamp: Long?,
) {
    // Flush BEFORE clearing so directRoomNavigation != null during suspension,
    // shielding navigateToRoomListIfNeeded from wiping the backstack.
    appViewModel.flushSyncBatchForRoom(roomId)
    appViewModel.clearDirectRoomNavigation()
    if (notificationTimestamp != null) {
        appViewModel.navigateToRoomWithCache(roomId, notificationTimestamp)
    } else {
        appViewModel.navigateToRoomWithCache(roomId)
    }
    appViewModel.openedViaDirectNotification = true
    navController.navigateToRoomTimelineForExternalEntry(roomId)
}
```

All 6 blocks in `LaunchedEffect(Unit)`, `LaunchedEffect(uiState.spacesLoaded)`, and `LaunchedEffect(navigationTrigger)` become one-line calls to this function. The ordering invariant now lives in exactly one place.

### What does NOT change

- All `LaunchedEffect` keys, guards, and polling loops remain as-is.
- `navigateToRoomWithCache`, `AuthCheck`, and all coordinator classes are untouched.

---

## Phase 2 — Replace trigger-counter + nullable-string with an event channel

**Risk**: Medium. Touches `AppViewModel`, `NavigationCoordinator`, `AuthCheck`, `MainActivity`, `RoomListScreen`.  
**Scope**: Navigation state model.  
**Value**: Eliminates the trigger-counter anti-pattern, merges 5+ state variables into one, and gives navigation a single consumer.

### The problem with the current model

`directRoomNavigation: String?` plus `directRoomNavigationTrigger: Int` is state pretending to be an event. The counter exists because a nullable string cannot represent "new request for the same room value". `pendingRoomNavigation` (shortcuts), `pendingRoomToRestore` (background restore), and `pendingBubbleNavigation` are parallel but separate channels doing the same job.

### What to do

**Step 1** — Define a navigation request type in `NavigationCoordinator`:

```kotlin
data class RoomNavigationRequest(
    val roomId: String,
    val timestamp: Long?,       // null = not from a notification
    val source: Source,
)  {
    enum class Source { NOTIFICATION, SHORTCUT, BUBBLE, RESTORE }
}
```

**Step 2** — Replace the scattered fields with a single channel:

```kotlin
// In NavigationCoordinator / AppViewModel:
private val _roomNavigationRequests = Channel<RoomNavigationRequest>(Channel.CONFLATED)
val roomNavigationRequests: Flow<RoomNavigationRequest> = _roomNavigationRequests.receiveAsFlow()
```

All existing setters (`setDirectRoomNavigation`, `setPendingRoomNavigation`, etc.) become `_roomNavigationRequests.trySend(...)` calls. Callers do not change — only the implementation changes.

**Step 3** — Replace the 6 `LaunchedEffect` blocks in `RoomListScreen` with a single consumer:

```kotlin
LaunchedEffect(Unit) {
    appViewModel.roomNavigationRequests.collectLatest { request ->
        // collectLatest cancels the previous coroutine if a new request arrives,
        // so there is no need for an abort guard.
        val timestamp = if (request.source == Source.NOTIFICATION) request.timestamp else null
        executeRoomNavigation(appViewModel, navController, request.roomId, timestamp)
    }
}
```

`collectLatest` provides natural cancellation: if a second notification arrives while the first is flushing, the first coroutine is cancelled and the second starts fresh. No manual abort guard needed.

**Step 4** — Simplify `AuthCheck`. The navigation callback only needs to check whether a request is in flight (via `roomNavigationRequests`) rather than inspecting multiple nullable fields. The `spacesLoaded` watcher guard becomes a simple `if (pendingRequest != null) return`.

### Fields that go away after Phase 2

| Removed | Replaced by |
|---|---|
| `directRoomNavigation: String?` | `RoomNavigationRequest.roomId` |
| `directRoomNavigationTrigger: Int` | Channel emission (each send is a distinct event) |
| `directRoomNavigationTimestamp: Long?` | `RoomNavigationRequest.timestamp` |
| `isPendingNavigationFromNotification: Boolean` | Derived: coroutine is active = navigation is pending |
| `pendingRoomNavigation: String?` | `RoomNavigationRequest(source = SHORTCUT)` |
| `pendingBubbleNavigation: String?` | `RoomNavigationRequest(source = BUBBLE)` |

`openedViaDirectNotification` and `pendingRoomToRestore` are addressed in Phase 3.

---

## Phase 3 — Narrow `currentRoomId` to one responsibility

**Risk**: High. Touches `AppViewModel`, both lifecycle and timeline coordinators, `RoomTimelineScreen`, and notification suppression logic.  
**Scope**: `currentRoomId` lifecycle model.  
**Value**: Removes the background-restore dance, simplifies `onAppBecameInvisible` / `onAppBecameVisible`, and makes `navigateToRoomWithCache` abort-guard-free.

### Current responsibilities of `currentRoomId`

1. **Notification suppression** — "do not notify if this room is currently open"
2. **Abort guard** — `navigateToRoomWithCache` returns early if `currentRoomId != roomId` after a suspension
3. **`timelineRefreshTrigger` condition** — `onAppBecameVisible` only increments if `currentRoomId.isNotEmpty()`
4. **Background restore** — `onAppBecameInvisible` saves it to `pendingRoomToRestore`; `onAppBecameVisible` restores it if no notification is pending

### What to do

**Notification suppression** — keep `currentRoomId` for this. It is the right abstraction.

**Abort guard** — remove it. With `collectLatest` (Phase 2), a new navigation request automatically cancels any in-flight coroutine for the previous request. There is no need to check `currentRoomId` inside `navigateToRoomWithCache`.

**`timelineRefreshTrigger`** — decouple from `currentRoomId`. `RoomTimelineScreen` can observe `syncBatchProcessor.isProcessingBatch` directly and trigger a refresh when it transitions `true → false` while the screen is active. No counter, no condition on `currentRoomId`.

**Background restore** — fold into the navigation channel as `Source.RESTORE`. When `onAppBecameVisible` determines there is no notification pending and a previous room should be restored, it posts `RoomNavigationRequest(roomId, null, Source.RESTORE)` to the channel. The same `executeRoomNavigation` path handles it. `pendingRoomToRestore` goes away.

**`openedViaDirectNotification`** — this flag exists to prevent `navigateToRoomListIfNeeded` from force-navigating back to `room_list` after a notification-driven navigation. With Phase 2 in place, `navigateToRoomListIfNeeded` can instead check `roomNavigationRequests.isNotEmpty()` (or a simpler `isNavigatingToRoom: Boolean` derived flag). The boolean itself can then be removed.

### Result

`currentRoomId` is written only by `RoomTimelineScreen`'s `DisposableEffect` (on entry and on leave). `onAppBecameInvisible` no longer touches it. The background-restore path uses the same channel as everything else.

---

## Phase 4 — Fix chat-bubble isolation without the singleton guard

**Risk**: Low–Medium. Scoped to `RoomTimelineCache` and the `BUBBLE` VM path.  
**Scope**: `RoomTimelineCache.getOpenedRooms()` usage.  
**Value**: Removes the `instanceRole == BUBBLE` special-case from the `RoomListSingletonReplicated` handler.

### The problem

`RoomTimelineCache.getOpenedRooms()` is a process-wide singleton set. When `RoomListSingletonReplicated` fires, main-app VMs iterate it and call `restoreFromLruCache` for every opened room. A bubble VM must not do this — it would overwrite its own timeline with whatever the main app last opened.

The current guard is a comment-enforced check: `if (instanceRole == BUBBLE) skip`. This is easy to forget when adding new `RoomListSingletonReplicated` handling.

### What to do

Give each `AppViewModel` instance its own `openedRooms: MutableSet<String>` field instead of delegating to the cache singleton for the "which rooms should I restore" question:

```kotlin
// In AppViewModel:
private val instanceOpenedRooms = mutableSetOf<String>()

fun addOpenedRoom(roomId: String) {
    instanceOpenedRooms.add(roomId)
    RoomTimelineCache.addOpenedRoom(roomId)   // still update global cache for eviction policy
}
```

The `RoomListSingletonReplicated` handler iterates `instanceOpenedRooms` instead of `RoomTimelineCache.getOpenedRooms()`. A bubble VM naturally only has its own room in `instanceOpenedRooms`. The `instanceRole == BUBBLE` guard and the associated comment in CLAUDE.md can be removed.

`RoomTimelineCache.getOpenedRooms()` can remain for other uses (cache eviction, preemptive pagination) — only the restore loop changes.

---

## Sequencing and dependencies

```
Phase 1  ←  safe to do now, unblocks everything
    ↓
Phase 2  ←  requires Phase 1 (call sites already simplified)
    ↓
Phase 3  ←  requires Phase 2 (abort guard removal depends on collectLatest)
    ↓
Phase 4  ←  independent of 1–3, can be done any time
```

Phases 1 and 4 are independent and can be done in either order. Phase 3 should not be attempted before Phase 2 is stable in production.

---

## Test scenarios to validate after each phase

Each phase should be validated against these three entry points:

| Scenario | Phase 1 | Phase 2 | Phase 3 |
|---|---|---|---|
| Cold start, notification tap, no cached events | ✓ | ✓ | ✓ |
| Cold start, notification tap, room is cached | ✓ | ✓ | ✓ |
| Warm start (app in background), notification tap, many batched events | ✓ | ✓ | ✓ |
| Warm start, notification for same room already open | ✓ | ✓ | ✓ |
| Warm start, notification for different room than previously open | ✓ | ✓ | ✓ |
| Two notifications arrive in quick succession | — | ✓ | ✓ |
| App shortcut navigation | — | ✓ | ✓ |
| Chat bubble open while main app is on room_list | — | — | ✓ |
| Background → foreground with no notification (restore) | — | — | ✓ |
