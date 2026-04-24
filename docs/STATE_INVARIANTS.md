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
