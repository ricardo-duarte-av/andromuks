# Battery Drain Analysis - SyncIngestor Flow

## Summary
This document identifies heavy loops and battery-intensive operations in the sync_complete processing flow, especially when the app is backgrounded with many active rooms (e.g., 588 rooms as seen in logs).

## Sync Flow Overview

1. **WebSocket receives sync_complete** → NetworkUtils.kt handles it
2. **AppViewModel.updateRoomsFromSyncJsonAsync()** is called
3. **Parallel operations:**
   - Background: `SyncIngestor.ingestSyncComplete()` - Persists to DB
   - Background: `SpaceRoomParser.parseSyncUpdate()` - Parses JSON
   - Main thread: `AppViewModel.processParsedSyncResult()` - Updates UI/RAM cache

## Heavy Operations Identified

### 1. SyncIngestor.ingestSyncComplete() - Database Operations

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt:127`

**Heavy loops:**
- **Line 234-256**: Iterates through ALL rooms in `roomsJson` (588 rooms in your case)
  - For each room, calls `processRoom()` which:
    - Processes room state/meta (line 271-322)
    - Processes timeline events array (line 328-363) - loops through ALL events
    - Processes events array (line 366-417) - loops through ALL events again
    - Processes receipts (line 437-468) - loops through receipt JSON
    - Updates room summary (line 470-534) - scans timeline backwards for last message
    - Each event triggers database upserts (line 352, 402)
    - Each event may trigger database lookups for timelineRowId (line 582)

**Battery impact:**
- Database transaction for all rooms (line 245) - locks DB during entire sync
- Multiple database queries per event for timelineRowId preservation
- JSON parsing for every event in every room
- File I/O for every database write

**Recommendation:**
- Process rooms in batches (e.g., 50 at a time) when backgrounded
- Skip timelineRowId lookups for events that already have it set
- Defer receipt processing to periodic batch updates

### 2. SpaceRoomParser.parseSyncUpdate() - JSON Parsing

**Location:** `app/src/main/java/net/vrkknn/andromuks/utils/SpaceRoomParser.kt:145`

**Heavy loops:**
- **Line 177-201**: Iterates through ALL rooms in sync JSON (588 rooms)
  - For each room, calls `parseRoomFromJson()` which:
    - **Line 261-328**: Loops through ALL events array BACKWARDS to find last message
      - Parses JSON for each event
      - Checks event type and content for message preview
    - **Line 338-360**: Parses account_data JSON for tags

**Battery impact:**
- JSON parsing on background thread (Dispatchers.Default) - CPU intensive
- String operations for every event
- Creates RoomItem objects for every room even if unchanged

**Recommendation:**
- Skip event parsing if room already exists and no new events
- Early exit when last message is found (already done, but could be optimized)
- Cache parsed room data to avoid re-parsing unchanged rooms

### 3. AppViewModel.populateMemberCacheFromSync() - Member Processing

**Location:** `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt:2536`

**Heavy loops:**
- **Line 2555-2640**: Iterates through ALL rooms in sync JSON
  - **Line 2565-2639**: For each room, loops through ALL events array
    - Checks every event type
    - Updates member cache maps
    - Processes membership changes

**Current optimization:**
- Only processes every 3rd sync (line 2542) - GOOD

**Battery impact:**
- Still processes all rooms and all events when it does run
- Map operations for every member event
- Hash generation for member state (line 3149, 3151)

**Recommendation:**
- When backgrounded, skip member cache updates entirely (already skips UI updates)
- Only process member events for rooms with actual member changes

### 4. AppViewModel.processParsedSyncResult() - Room Updates

**Location:** `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt:3144`

**Heavy loops:**
- **Line 3169-3214**: Iterates through ALL updated rooms (588 rooms potentially)
  - Updates roomMap for each room
  - Creates/updates RoomItem objects
  - Calls `RoomRepository.updateRoom()` for each (may trigger DB operations)

- **Line 3217-3230**: Iterates through ALL new rooms
  - Similar operations as above

- **Line 3241-3255**: Iterates through removed rooms
  - Removes from roomMap
  - Updates various state maps

- **Line 3322**: `updateLowPriorityRooms()` - Iterates through ALL rooms again
  - Filters all 588 rooms to find low priority ones
  - Writes to SharedPreferences

- **Line 3325**: `generateRoomStateHash()` - Iterates through all rooms
  - Generates hash string for all 588 rooms

- **Line 3349-3382**: Updates allRooms list
  - Maps over all existing rooms
  - Creates new RoomItem instances if data changed
  - Combines with new rooms

- **Line 3466**: **BACKGROUND MODE**: Still sorts ALL rooms by timestamp
  - `sortedByDescending { it.sortingTimestamp ?: 0L }` - 588 rooms sorted
  - This happens even when backgrounded!

**Battery impact:**
- Multiple iterations through all rooms
- Sorting 588 rooms on every sync (even backgrounded)
- Hash generation for room state
- SharedPreferences writes
- RoomItem object creation/changes trigger recomposition checks

**Recommendation:**
- When backgrounded, skip sorting entirely
- Skip hash generation when backgrounded
- Batch SharedPreferences updates (already throttled to every 10 syncs - GOOD)
- Only update rooms that actually changed in sync

### 5. Database Query Operations

**Heavy operations per sync:**

1. **SyncIngestor.processRoom() - Line 280**: 
   - `roomStateDao.get(roomId)` for every room (even if unchanged)
   - Could be 588 queries if pre-loading fails

2. **SyncIngestor.parseEventFromJson() - Line 582**:
   - `eventDao.getEventById(roomId, eventId)` for events without timelineRowId
   - Could be hundreds of queries per sync

3. **SyncIngestor.ingestSyncComplete() - Line 249**:
   - `roomStateDao.getAllRoomStates()` - Loads ALL room states upfront
   - Good optimization, but still expensive with 588 rooms

**Battery impact:**
- Disk I/O for every database query
- SQLite overhead
- Transaction locks

**Recommendation:**
- Cache room states in memory between syncs
- Batch event lookups instead of one-by-one
- Use bulk operations where possible

### 6. Account Data Merging

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt:148-186`

**Heavy operation:**
- **Line 152**: Loads ALL account_data from database
- **Line 155**: Parses entire account_data JSON object
- **Line 158**: Creates copy of entire JSON
- **Line 161-166**: Merges keys one by one
- **Line 177**: Writes entire merged JSON back to DB

**Battery impact:**
- Large JSON parsing/serialization
- Database read/write
- Happens on every sync_complete

**Recommendation:**
- Only merge changed keys, not entire object
- Cache account_data in memory
- Defer writes when backgrounded

### 7. Timeline Event Caching

**Location:** `AppViewModel.cacheTimelineEventsFromSync()` (referenced at line 3315)

**Heavy operation:**
- Caches timeline events from sync JSON
- Processes events for all rooms with timeline updates

**Battery impact:**
- RAM cache updates
- Event parsing

**Recommendation:**
- Only cache for currently open room when backgrounded
- Skip caching for low priority rooms when backgrounded

### 8. Conversation Shortcuts Update

**Location:** `AppViewModel.processParsedSyncResult()` - Line 3392-3396 (foreground), 3472-3474 (background)

**Heavy operation:**
- **Background**: Updates every 10 syncs (GOOD)
- Still sorts all 588 rooms before updating shortcuts

**Recommendation:**
- Skip shortcut updates entirely when backgrounded
- Update only for changed rooms, not all rooms

### 9. Room Repository Updates

**Location:** Multiple locations in `processParsedSyncResult()`

**Heavy operation:**
- `RoomRepository.updateRoom()` called for every updated room
- May trigger database operations

**Recommendation:**
- Batch repository updates
- Skip repository updates when backgrounded

## Critical Issues for Background Mode

### Issue 1: Sorting Still Happens in Background
**Location:** Line 3466 in `processParsedSyncResult()`

```kotlin
// App is in background - minimal processing for battery saving
val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
```

**Impact:** Sorting 588 rooms on EVERY sync, even when backgrounded!

**Fix:** Skip sorting when backgrounded, keep rooms in existing order.

### Issue 2: Database Persistence Happens on Every Sync
**Location:** `SyncIngestor.ingestSyncComplete()` called for every sync_complete

**Impact:** Full database transaction processing all rooms/events even when backgrounded.

**Fix:** 
- When backgrounded, only persist critical data (sync metadata)
- Defer room/event persistence to batch operations
- Process only changed rooms instead of all rooms

### Issue 3: JSON Parsing Happens for All Rooms
**Location:** `SpaceRoomParser.parseSyncUpdate()` processes all rooms

**Impact:** Parses JSON for all 588 rooms even if most haven't changed.

**Fix:**
- Track which rooms changed in sync
- Only parse changed rooms when backgrounded

### Issue 4: Member Cache Processing
**Location:** `populateMemberCacheFromSync()` - processes every 3rd sync

**Impact:** When it runs, processes all rooms and all events.

**Fix:**
- Skip entirely when backgrounded
- Only process rooms with member events

## Recommendations Priority

### High Priority (Immediate Battery Impact)

1. **Skip sorting in background mode** - Line 3466
2. **Defer database persistence when backgrounded** - Only persist sync metadata
3. **Skip JSON parsing for unchanged rooms** - Track room changes

### Medium Priority

4. **Batch database operations** - Process rooms in chunks
5. **Skip member cache updates when backgrounded**
6. **Skip repository updates when backgrounded**
7. **Cache room states in memory** - Avoid repeated DB queries

### Low Priority (Already Optimized)

8. Conversation shortcuts throttling (already every 10 syncs)
9. Member processing throttling (already every 3 syncs)
10. State saving throttling (already every 10 syncs)

## Summary of Battery Drains

With 588 rooms and frequent sync_complete messages:

1. **Every sync (backgrounded):**
   - JSON parsing for all 588 rooms
   - Database transaction for all rooms
   - Sorting 588 rooms
   - Hash generation for all rooms
   - Multiple iterations through all rooms

2. **Every 3rd sync:**
   - Member cache processing for all rooms and events

3. **Every 10th sync:**
   - Conversation shortcuts update
   - State saving

**Estimated CPU usage per sync:** High (multiple full iterations through 588 rooms)
**Estimated disk I/O per sync:** High (database operations for all rooms)
**Estimated battery impact:** Significant when syncs arrive frequently

## Next Steps

1. Add background mode detection to skip heavy operations
2. Implement batching for database operations
3. Track room changes to avoid processing unchanged rooms
4. Defer non-critical operations when backgrounded

