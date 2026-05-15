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

**Extended fix:** The outer `if (roomId == currentRoomId)` guard only protects the *launch site* of `buildTimelineFromChain`, not the deferred write. If the user navigates to a different room in the 200–500 ms between the guard check and the `withContext(Dispatchers.Main)` write, stale events overwrite the new room's empty timeline. All call sites now pass `expectedRoomId = roomId` so the guard fires at write-time:

| Call site | File | `expectedRoomId` passed |
|---|---|---|
| `processCachedEvents` | `TimelineCacheCoordinator.kt` | `roomId` ✓ |
| `appendEventsToCachedRoom` | `TimelineCacheCoordinator.kt` | `roomId` ✓ |
| `mergePaginationEvents` | `TimelineCacheCoordinator.kt` | forwarded from caller ✓ |
| `handlePaginationMerge` (full-reload branch) | `TimelineCacheCoordinator.kt` | `roomId` ✓ |
| `handlePaginationMerge` (fallback branch) | `TimelineCacheCoordinator.kt` | `roomId` ✓ |
| `handleInitialTimelineBuild` | `TimelineCacheCoordinator.kt` | `roomId` ✓ |
| stale-cache rebuild (SyncRoomsCoordinator callback) | `AppViewModel.kt` | `roomId` ✓ |
| `dismissPendingEcho` | `AppViewModel.kt` | captured `currentRoomId` ✓ |
| `processSendCompleteEvent` (error/edit/new) | `AppViewModel.kt` | `event.roomId` ✓ |
| pending echo insertion (`handleMessageResponse`) | `AppViewModel.kt` | `roomId` ✓ |
| `processSyncEventsArray` | `AppViewModel.kt` | `roomId` ✓ |
| batch-completion rebuild | `AppViewModel.kt` | captured `currentRoomId` ✓ |

## `openedViaDirectNotification` Never-Reset

`AppViewModel.openedViaDirectNotification` is set to `true` whenever a room is opened via FCM notification or shortcut (in `AuthCheck`, `RoomListScreen.executeRoomNavigation`, and `RoomTimelineScreen.LaunchedEffect(navTrigger)`). It is **never reset to `false`**.

**Effect:** `isRootDestination` in `RoomTimelineScreen` is `isBackStackEmpty || appViewModel.openedViaDirectNotification`. Once the flag is `true`, every subsequent room in the same Activity session is treated as a root destination — BackHandler calls `finish()` instead of `popBackStack()`, even for rooms opened normally via room_list.

**Why this matters:** The flag's intent is to make System Back exit the app (not go to room_list) for rooms opened directly from a notification where room_list was never on the back stack. Once the user navigates back to room_list and then into a room normally, the flag should no longer apply.

**Where it is reset:** `RoomListScreen` normal room tap handler — `appViewModel.openedViaDirectNotification = false` is set immediately before `navController.navigate("room_timeline/...")`. At this point room_list is already in the back stack, so the flag is no longer needed. It must be reset before the `navigate` call so that `RoomTimelineScreen`'s `remember { navController.previousBackStackEntry == null }` / `isRootDestination` computation sees the correct value at initial composition.

**Why not in `clearCurrentRoomId()` or `onBackClick`:** Those fire on *leaving* a room; the flag must be cleared on *entering* from room_list so the composing screen picks it up correctly.

**Why not in `navigateToRoomListIfNeeded`:** That function guards on the flag to decide whether to force-navigate to room_list on WebSocket reconnect/refresh. Clearing it there would be self-defeating.

**`executeRoomNavigation` (notification/shortcut from room_list) still sets `openedViaDirectNotification = true`** — that path navigates to room_timeline using `navigateToRoomTimelineForExternalEntry`, which clears the back stack above room_list, so room_list is NOT below room_timeline and Back should still exit.

## Sticky Room Flags: `isFavourite`, `isLowPriority`, `isDirectMessage`

These three `RoomItem` fields are "sticky" — once set to `true`, they must never silently regress to `false` just because a later sync message didn't include the account data that set them.

**Why they are sticky:**  
`isFavourite` and `isLowPriority` come from the room's per-room `account_data.m.tag.content.tags` field in the initial sync (or when the user changes the tag). Subsequent sync messages for the same room (e.g. new messages, receipt updates) do **not** re-send `account_data` — the field is simply absent. `isDirectMessage` has a similar pattern (`m.direct` arrives once at startup). A naive replace would reset these flags to `false` on every update.

**The invariant:**  
Whenever a `RoomItem` is merged/replaced in any accumulator or map, the three flags must be OR-merged:

```kotlin
isFavourite   = candidate.isFavourite   || existing.isFavourite
isLowPriority = candidate.isLowPriority || existing.isLowPriority
isDirectMessage = candidate.isDirectMessage || existing.isDirectMessage
```

**Where this is enforced (all three must merge):**

| Site | File | Description |
|---|---|---|
| `processParsedSyncResult` → `updatedRooms` loop | `SyncRoomsCoordinator.kt` | Live single-sync path |
| `processParsedSyncResult` → `newRooms` loop | `SyncRoomsCoordinator.kt` | Post-clear-state / newly-joined rooms — merges from existing `roomMap` entry if present |
| Batch accumulator → `updatedRooms` / `newRooms` loops | `SyncBatchProcessor.kt` | Multi-sync batch path; bugs here cause Favs to vanish after a burst of syncs |
| Initial-sync batch accumulator | `AppViewModel.kt` `attachToExistingWebSocket` | Secondary VM attachment batch |

**Common failure mode:**  
A burst of sync messages is batched. Sync A sets `isFavourite=true` (has `account_data.m.tag`). Sync B has a newer `sortingTimestamp` and replaces sync A's entry without OR-merging → `isFavourite` silently becomes `false` → room disappears from the Favourites tab until the next reconnect (clear_state) that re-delivers `account_data`.
