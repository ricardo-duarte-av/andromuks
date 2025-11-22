# Bridge Info Storage and Loading Analysis

## Answers to Questions

### 1. After we first populate the list and we get the 95 rooms across 3 networks, is this info stored in the DB? Where?

**YES**, bridge info is stored in the database:
- **Table**: `room_state`
- **Column**: `bridgeInfoJson` (TEXT type, stores JSON string)
- **Storage flow**:
  1. When bridge info is received, `updateBridgeInfo()` is called (AppViewModel.kt:393)
  2. This calls `persistBridgeInfo()` in SyncIngestor.kt (line 1081)
  3. `persistBridgeInfo()` stores the bridge JSON in `RoomStateEntity.bridgeInfoJson`
  4. The JSON contains: bridgebot, creator, channel (avatar_url, displayname, id), protocol (avatar_url, displayname, external_url, id)

### 2. When the app restarts, is this info loaded from the DB? When?

**YES**, bridge info is loaded from the database:
- **When**: During app initialization in `loadStateFromStorage()` (AppViewModel.kt:5213)
- **Specific location**: Line 5266 - `val bridgeInfoMap = bootstrapLoader!!.loadBridgeInfoFromDb()`
- **Process**:
  1. `BootstrapLoader.loadBridgeInfoFromDb()` queries all `RoomStateEntity` records
  2. Filters for records where `bridgeInfoJson` is not null and not empty
  3. Returns a map of `roomId -> bridgeInfoJson`
  4. Each JSON is parsed and loaded into `bridgeInfoCache` (in-memory)
  5. `bridgeCacheCheckedRooms` is populated with all checked rooms
  6. Bridge pseudo-spaces are created from the loaded data

### 3. If a state response comes without the m.bridge event, is the bridged info removed from DB?

**NO, it should NOT be removed**, but there's a potential issue:

**Current protection** (AppViewModel.kt:463):
```kotlin
if (roomState != null && roomState.bridgeInfoJson == null) {
    // Only sets empty string if bridgeInfoJson is NULL
    bridgeInfoJson = ""
}
// CRITICAL: If bridgeInfoJson is not null and not empty, it means bridge info exists - DO NOT OVERWRITE
```

**The logic is correct**: If `bridgeInfoJson` already has a value (non-null, non-empty), the condition is false, so bridge info is preserved.

**However, there are potential issues**:

1. **Race condition in `markRoomAsBridgeChecked()`**: 
   - If `roomState == null`, it creates a new state with `bridgeInfoJson = ""`
   - But if bridge info was stored via `persistBridgeInfo()` which creates the state, this shouldn't happen
   - **However**, if sync processing happens BEFORE bridge info is persisted, it might create a state without bridge info

2. **Sync processing might overwrite**:
   - `processRoom()` in SyncIngestor.kt preserves bridge info (line 300: `var bridgeInfoJson: String? = existingState?.bridgeInfoJson`)
   - But if `existingState` is null when sync processes the room, it will create a new state with `bridgeInfoJson = null`
   - This could happen if sync data arrives before bridge info is loaded from DB on restart

3. **Timeline event processing**:
   - `updateRoomStateFromTimelineEvents()` now preserves bridge info (fixed in recent changes)
   - But if it creates a new state when `existingState == null`, it re-checks the DB to preserve bridge info

## Potential Root Cause

The most likely issue is a **race condition during app startup**:
1. App restarts
2. `loadStateFromStorage()` starts loading bridge info from DB (async)
3. Sync data arrives and processes rooms
4. If sync processing happens before bridge info is fully loaded, `existingState` might be null
5. Sync creates new room states without bridge info
6. Bridge info gets lost

## Recommendations

1. **Ensure bridge info is loaded BEFORE sync processing starts**
2. **Add logging to track when bridge info is lost**
3. **Make `markRoomAsBridgeChecked()` check if bridge info exists before overwriting**

