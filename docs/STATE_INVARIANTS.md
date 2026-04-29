# AppViewModel State Clearing Invariants

## `currentRoomState` Clearing

`AppViewModel.currentRoomState` is a global `mutableStateOf` read directly by `RoomTimelineScreen` to populate the room header. It must be cleared to `null` whenever `currentRoomId` changes to a *different* room.

**Where:** `updateCurrentRoomIdInPrefs` — the single choke-point for all `currentRoomId` changes.

**Why:** Without this, direct room-to-room navigation (A → B without going back through the list) leaves `currentRoomState` holding room A's data while room B's screen is composing, causing the wrong room name/avatar to appear in the header.

**Why not in `RoomTimelineScreen`'s `DisposableEffect`:** The `DisposableEffect` guard (`if currentRoomId == roomId`) intentionally skips `clearCurrentRoomId()` in this case (because `currentRoomId` is already room B). The clearing must therefore happen at the `updateCurrentRoomIdInPrefs` site.

## `timelineEvents` Clearing

`AppViewModel.timelineEvents` is also a global `mutableStateOf` and suffers the same stale-data problem as `currentRoomState`. It is cleared to `emptyList()` in `updateCurrentRoomIdInPrefs` alongside `currentRoomState` whenever the room changes.

**Why:** Without this, the `isWarmTimelineReturn` guard in `RoomTimelineScreen.LaunchedEffect(roomId)` evaluates `currentRoomId==roomId && timelineEvents.isNotEmpty()` as **true** for the wrong reason: `currentRoomId` has been updated synchronously to room B, but `timelineEvents` still holds room A's events because the `navigateToRoomWithCache` coroutine hasn't run yet. The screen then skips `awaitRoomDataReadiness` and renders room A's timeline under room B's header.

**Exception:** Clearing is skipped for `BUBBLE` instances, which restore their own timeline state.

**Paired guard:** In `RoomTimelineScreen.LaunchedEffect(roomId)`, `isAlreadyLoaded` uses `timelineEvents.isNotEmpty() || isTimelineLoading` so a second `navigateToRoomWithCache` call is not fired when events are empty but a load is already in flight.

## Stale `timelineEvents` Write from `processCachedEvents` Background Coroutine

`processCachedEvents` (called from `navigateToRoomWithCache`) launches a `Dispatchers.Default` coroutine that runs `processVersionedMessages` → `processEditRelationships` → `buildTimelineFromChain`. This chain is genuinely async: `buildTimelineFromChain` itself posts to `Dispatchers.Default` and ultimately lands back on `Dispatchers.Main` to write `timelineEvents`. This entire pipeline can take 200–500 ms for rooms with many events.

**The bug:** If the user opens room A via a conversation widget/shortcut (no `room_list` in the back stack), the pipeline starts for room A. The user then presses HOME, relaunches the app (which navigates back to `room_list`), and immediately opens room B. Room B renders its correct timeline first, but ~500 ms later the stale `withContext(Dispatchers.Main)` dispatch from room A's pipeline fires. Because `executeTimelineRebuild` had no room guard, it unconditionally overwrote `timelineEvents` with room A's events — causing room B's screen to re-render with the wrong room's messages and broken sender profiles (from room A's stale `updateMemberProfilesFromTimelineEvents` call).

**How to replicate:** Open room Alpha via conversation widget → press HOME → open app normally → land on RoomListScreen → open room Beta. Room Beta renders correctly for ~0.5 s, then Alpha's timeline replaces it.

**The fix (two guards, both required):**

1. **`buildTimelineFromChain` / `executeTimelineRebuild`** (`AppViewModel.kt`) — accept an optional `expectedRoomId: String?`. In `executeTimelineRebuild`'s `withContext(Dispatchers.Main)` block, if `expectedRoomId != null && currentRoomId != expectedRoomId`, discard the result entirely (set `isTimelineLoading = false`, complete the deferred, `return@withContext`).

2. **`processCachedEvents`** (`TimelineCacheCoordinator.kt`) — passes `expectedRoomId = roomId` to `buildTimelineFromChain`, so the guard above fires when the room has changed. Also adds an identical guard at the top of its own `withContext(Dispatchers.Main)` post-processing block (profiles, reactions, room state) so those writes are also suppressed for stale rooms.

Callers of `buildTimelineFromChain` from sync-event processing already have outer `if (roomId == currentRoomId)` guards and pass no `expectedRoomId` (null), so they are unaffected.
