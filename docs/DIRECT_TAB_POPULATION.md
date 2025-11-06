# Direct Tab Population System

## Overview

The Direct tab displays all rooms that are identified as Direct Messages (DMs) between the user and other Matrix users. The system uses multiple detection methods with `m.direct` account data from the persistent database as the authoritative source.

---

## Architecture

### Data Sources

The Direct tab uses three detection methods (in priority order):

1. **Primary: `dm_user_id` in meta** - Most reliable for gomuks JSON format
2. **Secondary: `m.direct` account data** - Authoritative source from Matrix account_data
3. **Fallback: Room name pattern** - Detects Matrix user ID format in room name

### Storage

- **Account Data**: `m.direct` is stored persistently in the `account_data` table
- **Room State**: DM status is cached in `room_state.isDirect` for quick access
- **In-Memory Cache**: `directMessageRoomIds` set in `AppViewModel` for O(1) lookups

---

## Detection Methods

### Method 1: `dm_user_id` in Meta (Primary)

**Location:** `SpaceRoomParser.detectDirectMessage()`

**How it works:**
- Checks if the `meta` object from sync JSON has a `dm_user_id` field
- If present and non-empty, the room is identified as a DM
- This is the most reliable method for gomuks-specific JSON format

**Example:**
```json
{
  "meta": {
    "dm_user_id": "@alice:example.com"
  }
}
```

**Result:** Room is marked as DM

### Method 2: `m.direct` Account Data (Secondary - Authoritative)

**Location:** `AppViewModel.processAccountData()`, `SpaceRoomParser.detectDirectMessage()`

**How it works:**
- Extracts room IDs from `account_data.m.direct` structure
- Structure: `{ "@user:server.com": ["!room1:server.com", "!room2:server.com"] }`
- All room IDs listed under any user are marked as DMs
- Populates `directMessageRoomIds` set in `AppViewModel`
- Used as fallback when `dm_user_id` is not available

**Example:**
```json
{
  "account_data": {
    "m.direct": {
      "type": "m.direct",
      "content": {
        "@alice:example.com": ["!room1:example.com"],
        "@bob:example.com": ["!room2:example.com", "!room3:example.com"]
      }
    }
  }
}
```

**Result:** Rooms `!room1`, `!room2`, and `!room3` are marked as DMs

**Processing:**
- During bootstrap: Loaded from database and processed
- During sync: Updated when account_data changes in sync_complete
- All rooms in `roomMap` are updated with correct DM status

### Method 3: Room Name Pattern (Fallback)

**Location:** `SpaceRoomParser.detectDirectMessage()`

**How it works:**
- Checks if room name matches exact Matrix user ID format: `^@[^:]+:[^:]+$`
- Only matches if the name is EXACTLY a Matrix user ID (not just contains @)
- Used as last resort when other methods fail

**Example:**
- Room name: `@alice:example.com` → DM detected
- Room name: `Alice` → Not a DM
- Room name: `@alice` → Not a DM (missing server part)

---

## Data Flow

### On App Startup (Bootstrap)

```
1. BootstrapLoader.loadBootstrap()
   ↓
2. Load rooms from database (room_summary + room_state)
   ↓
3. Load account_data from database
   ↓
4. AppViewModel.loadStateFromStorage()
   ↓
5. Rooms added to roomMap (with isDirectMessage from room_state)
   ↓
6. processAccountData(accountDataJson) called
   ↓
7. Extract m.direct → directMessageRoomIds set
   ↓
8. updateRoomsDirectMessageStatus(dmRoomIds) called
   ↓
9. All rooms in roomMap updated with correct DM status
   ↓
10. allRooms updated and cache invalidated
    ↓
11. Direct tab shows correct rooms
```

### During Sync Updates

```
1. sync_complete arrives with account_data
   ↓
2. SyncIngestor.ingestSyncComplete() merges account_data
   ↓
3. AppViewModel.updateRoomsFromSyncJsonAsync()
   ↓
4. processAccountData(accountData) called
   ↓
5. Extract m.direct → directMessageRoomIds updated
   ↓
6. updateRoomsDirectMessageStatus(dmRoomIds) called
   ↓
7. Existing rooms in roomMap updated with new DM status
   ↓
8. allRooms updated and cache invalidated
   ↓
9. Direct tab reflects changes
```

### Room Parsing During Sync

```
1. SpaceRoomParser.parseRoomFromJson()
   ↓
2. detectDirectMessage() called
   ↓
3. Checks in order:
   - dm_user_id in meta? → DM
   - Room ID in directMessageRoomIds? → DM
   - Room name is Matrix user ID? → DM
   ↓
4. RoomItem created with isDirectMessage flag
   ↓
5. Added to roomMap
```

---

## Caching & Performance

### Lazy Loading

The Direct tab uses lazy loading to defer expensive filtering:

1. **First Access:** When user opens Direct tab for the first time
   - `getCurrentRoomSection()` marks `DIRECT_CHATS` as loaded
   - Triggers `updateCachedRoomSections()`

2. **Filtering:** `updateCachedRoomSections()` filters rooms:
   ```kotlin
   if (loadedSections.contains(RoomSectionType.DIRECT_CHATS)) {
       cachedDirectChatRooms = roomsToUse.filter { it.isDirectMessage }
   }
   ```

3. **Subsequent Accesses:** Direct tab uses `cachedDirectChatRooms` (no filtering needed)

### Cache Invalidation

- **Hash-based invalidation:** `lastAllRoomsHash` tracks when rooms change
- **Automatic refresh:** When `allRooms` changes, cache is invalidated
- **Account data updates:** When `m.direct` changes, rooms are updated and cache invalidated

### Performance Optimizations

1. **O(1) Lookup:** `directMessageRoomIds` is a Set for fast membership checks
2. **In-place Updates:** Rooms updated directly in `roomMap` without full recreation
3. **Selective Updates:** Only rooms that actually changed are updated
4. **Cached Filtering:** Filtered list cached to avoid recomputation

---

## Integration Points

### 1. BootstrapLoader

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/BootstrapLoader.kt`

**Responsibilities:**
- Loads `account_data` from database
- Returns `accountDataJson` in `BootstrapResult`
- Loads rooms from database (with DM status from `room_state`)

**Called from:** `AppViewModel.loadStateFromStorage()`

### 2. SyncIngestor

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt`

**Responsibilities:**
- Merges incoming `account_data` with existing database data
- Persists `account_data` to database
- Handles partial updates (only changed keys)

**Called from:** `AppViewModel.updateRoomsFromSyncJsonAsync()`

### 3. SpaceRoomParser

**Location:** `app/src/main/java/net/vrkknn/andromuks/utils/SpaceRoomParser.kt`

**Responsibilities:**
- Detects DM status during room parsing
- Uses `detectDirectMessage()` with three detection methods
- Sets `isDirectMessage` flag on `RoomItem`

**Called from:** `AppViewModel.updateRoomsFromSyncJsonAsync()`

### 4. AppViewModel

**Location:** `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

**Key Methods:**

#### `processAccountData(accountDataJson: JSONObject)`
- Extracts `m.direct` from account_data
- Populates `directMessageRoomIds` set
- Calls `updateRoomsDirectMessageStatus()`

#### `updateRoomsDirectMessageStatus(dmRoomIds: Set<String>)`
- Updates all rooms in `roomMap` with correct DM status
- Updates `allRooms` list
- Invalidates section cache

#### `getCurrentRoomSection(): RoomSection`
- Returns `cachedDirectChatRooms` for DIRECT_CHATS section
- Triggers lazy loading if needed

#### `updateCachedRoomSections()`
- Filters rooms for DIRECT_CHATS section
- Only runs if section has been loaded (lazy loading)
- Caches filtered result

---

## Room State Updates

### When m.direct Account Data Changes

When `processAccountData()` processes `m.direct`:

1. **Extract room IDs** from `m.direct.content` structure
2. **Update `directMessageRoomIds`** set
3. **Call `updateRoomsDirectMessageStatus()`** with new room IDs
4. **Update all rooms** in `roomMap`:
   - If room ID in `dmRoomIds` → `isDirectMessage = true`
   - If room ID not in `dmRoomIds` → `isDirectMessage = false`
5. **Update `allRooms`** sorted list
6. **Invalidate cache** to refresh filtered sections

### Example Update

**Before:**
- `m.direct`: `{"@alice:example.com": ["!room1:example.com"]}`
- Room `!room1` has `isDirectMessage = true`
- Room `!room2` has `isDirectMessage = false`

**After sync with new m.direct:**
- `m.direct`: `{"@alice:example.com": ["!room1:example.com"], "@bob:example.com": ["!room2:example.com"]}`
- Room `!room1` stays `isDirectMessage = true`
- Room `!room2` updated to `isDirectMessage = true`
- Direct tab now shows both rooms

---

## Direct Tab Display

### RoomListScreen.kt

**Location:** `app/src/main/java/net/vrkknn/andromuks/RoomListScreen.kt`

**Flow:**
1. User taps "Direct" tab
2. `changeSelectedSection(RoomSectionType.DIRECT_CHATS)` called
3. `getCurrentRoomSection()` returns DIRECT_CHATS section
4. Section contains `cachedDirectChatRooms` (filtered list)
5. `RoomListContent` displays the rooms

**Key Code:**
```kotlin
RoomSectionType.DIRECT_CHATS -> {
    val unreadDmCount = cachedDirectChatRooms.count { 
        (it.unreadCount != null && it.unreadCount > 0) || 
        (it.highlightCount != null && it.highlightCount > 0) 
    }
    RoomSection(
        type = RoomSectionType.DIRECT_CHATS,
        rooms = cachedDirectChatRooms,
        unreadCount = unreadDmCount
    )
}
```

---

## Error Handling

### Missing Account Data

If `account_data` is missing or `m.direct` is not present:
- Fallback to other detection methods (`dm_user_id`, room name pattern)
- Existing `directMessageRoomIds` remains unchanged
- Rooms keep their current DM status

### Database Errors

If account_data cannot be loaded from database:
- Bootstrap continues with other data
- Rooms loaded with DM status from `room_state`
- Will be updated when next sync arrives with account_data

### Sync Errors

If account_data processing fails during sync:
- Error logged but processing continues
- Existing `directMessageRoomIds` remains unchanged
- Rooms keep their current DM status until next successful sync

---

## Testing

### Manual Testing

1. **Bootstrap Test:**
   - Clear app data
   - Restart app
   - Verify Direct tab shows rooms from `m.direct` account_data

2. **Sync Update Test:**
   - Open Direct tab
   - Trigger sync with updated `m.direct`
   - Verify Direct tab updates with new rooms

3. **Multiple Detection Methods:**
   - Test room with `dm_user_id` in meta
   - Test room with room ID in `m.direct`
   - Test room with Matrix user ID as name
   - Verify all appear in Direct tab

### Edge Cases

1. **Room in m.direct but not in room list:**
   - Room won't appear (not in `allRooms`)
   - Will appear when room is added to account

2. **Room removed from m.direct:**
   - `updateRoomsDirectMessageStatus()` sets `isDirectMessage = false`
   - Room removed from Direct tab

3. **Partial account_data update:**
   - Only `m.direct` key updated
   - Other account_data keys preserved
   - DM room IDs updated correctly

---

## Performance Considerations

### Memory

- `directMessageRoomIds` is a Set<String> - O(1) lookup, minimal memory
- `cachedDirectChatRooms` is a filtered list - only computed when needed
- Account_data stored as JSON string in database - efficient storage

### CPU

- **Bootstrap:** Single pass through all rooms to update DM status
- **Sync:** Only updates rooms that changed DM status
- **Filtering:** O(n) operation, but cached after first access

### Database

- Account_data stored as single JSON string - one query to load
- Room state stored per-room - efficient for lookups
- Merging account_data is fast (JSON object merge)

---

## Future Enhancements

### Potential Improvements

1. **Deep Merge:** For nested account_data structures, merge at field level instead of replacing entire object
2. **Validation:** Validate `m.direct` structure before processing
3. **Batch Updates:** Batch multiple room updates into single cache invalidation
4. **Observable State:** Use observable state for DM status changes to trigger UI updates automatically
5. **Offline Support:** Cache DM detection results for offline scenarios

### Additional Features

- **DM Indicators:** Visual indicators in room list for DM rooms
- **DM Filtering:** Filter by DM status in search
- **DM Statistics:** Count of DMs, unread DMs, etc.
- **DM Grouping:** Group DMs by user in Direct tab

---

## Related Files

### Core Implementation
- `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt` - Main logic and state management
- `app/src/main/java/net/vrkknn/andromuks/utils/SpaceRoomParser.kt` - DM detection
- `app/src/main/java/net/vrkknn/andromuks/RoomListScreen.kt` - Direct tab UI

### Database
- `app/src/main/java/net/vrkknn/andromuks/database/BootstrapLoader.kt` - Bootstrap loading
- `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt` - Sync ingestion
- `app/src/main/java/net/vrkknn/andromuks/database/entities/AccountDataEntity.kt` - Database entity

### Documentation
- `docs/ACCOUNT_DATA_STORAGE.md` - Account data storage system
- `docs/ROOM_RESTORE_FIX.md` - Room restoration on app resume

---

## Summary

The Direct tab population system:

✅ **Uses authoritative source** - `m.direct` account data from database  
✅ **Multiple detection methods** - Fallback when account_data unavailable  
✅ **Persistent storage** - Account data survives app restarts  
✅ **Automatic updates** - Rooms updated when account_data changes  
✅ **Performance optimized** - Lazy loading, caching, O(1) lookups  
✅ **Error resilient** - Graceful fallback on errors  

**Key Design Principle:** Use `m.direct` account data as authoritative source, but support multiple detection methods for reliability and compatibility.

---

**Last Updated:** 2024  
**Related Documents:**
- `ACCOUNT_DATA_STORAGE.md` - Account data storage details
- `ROOM_RESTORE_FIX.md` - Room restoration system

