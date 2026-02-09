# Spaces Clearing Analysis

This document lists all code paths that can clear `allSpaces` and the circumstances under which they occur.

## 1. `performFullRefresh()` - Line 1342 in AppViewModel.kt

**Function**: `performFullRefresh()`

**What it does**: 
- Sets `allSpaces = emptyList()`
- Also clears `roomMap`, `allRooms`, `spaceList`, `knownSpaceIds`, `storedSpaceEdges`
- Sets `spacesLoaded = false`

**Circumstances**:
- **Currently NOT CALLED** - The function exists but no callers were found in the codebase
- Intended for: User pull-to-refresh gesture or automatic stale state detection (cached data > 10 minutes old)
- Would trigger: Full WebSocket reconnection with `clear_state=true`

**Status**: ⚠️ **DEFINED BUT NOT USED** - This is a potential code path that could clear spaces if called in the future

---

## 2. `clearDerivedStateInMemory()` - Line 4552 in AppViewModel.kt

**Function**: `clearDerivedStateInMemory()` (private)

**What it does**:
- Sets `allSpaces = emptyList()`
- Also clears `roomMap`, `allRooms`, `spaceList`, `knownSpaceIds`, `storedSpaceEdges`
- Sets `spacesLoaded = false`
- Clears read receipts, reactions, caches, etc.

**Circumstances**:
- Called by `handleClearStateReset()` when `clear_state=true` is received in a `sync_complete` message

**Call Chain**:
1. `updateRoomsFromSyncJsonAsync()` receives `sync_complete` with `clear_state=true`
2. Calls `handleClearStateReset()` (inside mutex for messages after `init_complete`)
3. `handleClearStateReset()` calls `clearDerivedStateInMemory()`
4. `clearDerivedStateInMemory()` sets `allSpaces = emptyList()`

**When it happens**:
- **After `init_complete`**: When a `sync_complete` message with `clear_state=true` arrives, it's processed inside `syncCompleteProcessingMutex.withLock` (atomic)
- **Before `init_complete`**: When processing queued messages in `processInitialSyncComplete()`, `handleClearStateReset()` is called before parsing

**Status**: ✅ **ACTIVE** - This is the main path for clearing spaces when server requests a state reset

---

## 3. `SpaceRoomParser.parseSyncUpdate()` - Line 167 in SpaceRoomParser.kt

**Function**: `SpaceRoomParser.parseSyncUpdate()` with `isClearState=true`

**What it does**:
- Calls `appViewModel?.updateAllSpaces(emptyList())` when `isClearState=true`

**Circumstances**:
- Called when parsing a `sync_complete` message with `clear_state=true`
- This happens in TWO places:
  1. **`processInitialSyncComplete()`** - For queued messages (before `init_complete`)
  2. **`updateRoomsFromSyncJsonAsync()`** - For real-time messages (after `init_complete`)

**When it happens**:
- When `isClearState=true` is detected in the sync data
- **NOTE**: This is called AFTER `handleClearStateReset()` in the processing flow, so it's redundant but safe

**Status**: ✅ **ACTIVE** - This is a secondary clearing path (redundant with `handleClearStateReset()`)

---

## Summary of Clearing Scenarios

### Scenario 1: Server sends `clear_state=true` in sync_complete (AFTER init_complete)
1. `updateRoomsFromSyncJsonAsync()` receives message
2. Inside `syncCompleteProcessingMutex.withLock`:
   - `handleClearStateReset()` → `clearDerivedStateInMemory()` → `allSpaces = emptyList()`
   - `SpaceRoomParser.parseSyncUpdate()` with `isClearState=true` → `updateAllSpaces(emptyList())`
3. **Result**: Spaces cleared twice (redundant but safe)

### Scenario 2: Server sends `clear_state=true` in sync_complete (BEFORE init_complete)
1. Message is queued in `initialSyncCompleteQueue`
2. After `init_complete`, messages are processed sequentially:
   - `processInitialSyncComplete()` calls `handleClearStateReset()` → `clearDerivedStateInMemory()` → `allSpaces = emptyList()`
   - `SpaceRoomParser.parseSyncUpdate()` with `isClearState=true` → `updateAllSpaces(emptyList())`
3. **Result**: Spaces cleared twice (redundant but safe)

### Scenario 3: User triggers full refresh (NOT CURRENTLY IMPLEMENTED)
1. `performFullRefresh()` would be called
2. Sets `allSpaces = emptyList()` directly
3. **Result**: Spaces cleared (but this path is not currently used)

---

## Potential Issues

1. **Redundant Clearing**: Both `handleClearStateReset()` and `SpaceRoomParser` clear spaces when `clear_state=true`. This is safe but redundant.

2. **Race Condition (FIXED)**: Previously, `handleClearStateReset()` was called outside the mutex, which could cause race conditions. This has been fixed by moving it inside the mutex.

3. **Empty Spaces After Clear**: If `clear_state=true` clears spaces but the next `sync_complete` doesn't have `top_level_spaces`, spaces stay empty. This is expected behavior - spaces should be repopulated by subsequent messages.

4. **Space Edges Without Top-Level Spaces**: Previously, if a `sync_complete` had `space_edges` but no `top_level_spaces`, the edges were ignored. This has been fixed - edges are now stored even when `top_level_spaces` is null.

---

## Recommendations

1. **Remove Redundant Clearing**: Consider removing the `updateAllSpaces(emptyList())` call from `SpaceRoomParser` since `handleClearStateReset()` already clears spaces.

2. **Add Logging**: The logging added will help track when spaces are cleared in release builds (though currently only in DEBUG builds).

3. **Monitor**: Watch for cases where spaces are cleared but not repopulated - this would indicate a backend issue or message ordering problem.

