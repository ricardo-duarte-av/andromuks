# Optimization #3: Persistence Fix for Skipped Rooms

## Problem

When rooms are processed in batches (only 50 when backgrounded), the remaining rooms are kept in RAM. If the OS kills the app, these skipped rooms are lost and won't be processed.

## Solution

**Persist skipped rooms to database** so they survive app kills and can be processed later.

## Implementation

### 1. Created PendingRoomEntity

**New Entity:** `PendingRoomEntity.kt`
- Stores `roomId` (primary key)
- Stores full `roomJson` (complete room data from sync_complete)
- Stores `timestamp` (when it was deferred)

### 2. Created PendingRoomDao

**New DAO:** `PendingRoomDao.kt`
- `upsertAll()` - Store skipped rooms
- `getAllPendingRooms()` - Retrieve all pending rooms
- `deletePendingRooms()` - Remove processed rooms
- `deleteAll()` - Clear all pending rooms

### 3. Updated Database Schema

**AndromuksDatabase.kt:**
- Added `PendingRoomEntity` to entities list
- Bumped version to 8
- Added `pendingRoomDao()` method

### 4. Persist Skipped Rooms

**SyncIngestor.ingestSyncComplete():**
- When backgrounded and rooms are skipped, persist them to database
- Store full room JSON so we can process them later

```kotlin
if (roomsSkipped > 0) {
    val pendingRooms = skippedRoomIds.mapNotNull { roomId ->
        val roomObj = roomsJson.optJSONObject(roomId) ?: return@mapNotNull null
        PendingRoomEntity(
            roomId = roomId,
            roomJson = roomObj.toString(),
            timestamp = System.currentTimeMillis()
        )
    }
    pendingRoomDao.upsertAll(pendingRooms)
}
```

### 5. Process Pending Rooms

**Two strategies:**

#### A. Process on Next Sync
- At start of `ingestSyncComplete()`, check for pending rooms
- Process pending rooms that are NOT in current sync (avoid duplicates)
- Delete pending rooms that ARE in current sync (use fresh data from sync)

#### B. Rush Process When App Becomes Visible
- `rushProcessPendingItems()` processes all pending rooms immediately
- Called from `AppViewModel.onAppBecameVisible()`
- Processes with `isAppVisible = true` (full processing including summaries)

### 6. Cleanup

**When rooms are left:**
- Delete pending rooms for left rooms (in `left_rooms` processing)
- Prevents processing rooms we've already left

## Data Flow

### Normal Flow (Backgrounded)
```
1. Sync arrives with 588 rooms
2. Process first 50 rooms immediately
3. Persist remaining 538 rooms to database
4. Next sync arrives
5. Check pending rooms
6. Process pending rooms not in current sync
7. Delete pending rooms that ARE in current sync (use fresh data)
```

### App Killed Scenario
```
1. Sync arrives with 588 rooms
2. Process first 50 rooms immediately
3. Persist remaining 538 rooms to database ✅
4. App is killed by OS
5. App restarts
6. Next sync arrives
7. Check pending rooms from database ✅
8. Process all 538 pending rooms
9. Delete after processing
```

### App Becomes Visible
```
1. App becomes visible
2. rushProcessPendingItems() called
3. Load all pending rooms from database
4. Process all pending rooms immediately
5. Delete after processing
```

## Benefits

✅ **Survives app kills** - Pending rooms persisted to database
✅ **No data loss** - All rooms eventually processed
✅ **Efficient** - Only processes pending rooms not in current sync
✅ **Automatic cleanup** - Pending rooms deleted after processing
✅ **Handles edge cases** - Left rooms cleaned up

## Database Impact

**Storage:**
- Each pending room stores full JSON (~1-5KB per room)
- With 538 skipped rooms: ~500KB-2.5MB
- Temporary storage (cleared after processing)
- Acceptable for modern devices

**Performance:**
- Single transaction for batch upsert
- Indexed by roomId for fast lookups
- Cleared after processing (no long-term growth)

## Testing Checklist

1. ✅ Skipped rooms are persisted to database
2. ✅ Pending rooms are processed on next sync
3. ✅ Pending rooms are rushed when app becomes visible
4. ✅ Pending rooms are deleted after processing
5. ✅ Left rooms clean up pending rooms
6. ✅ App kill scenario works (rooms survive restart)
7. ✅ No duplicate processing (pending rooms in current sync are deleted)

## Edge Cases Handled

1. **App killed while backgrounded:**
   - Pending rooms survive in database ✅
   - Processed on next sync or when app becomes visible ✅

2. **Pending room appears in next sync:**
   - Delete pending room, use fresh data from sync ✅
   - Avoids processing stale data ✅

3. **Room left while pending:**
   - Pending room deleted when room is left ✅
   - Prevents processing rooms we've already left ✅

4. **Multiple syncs with same pending rooms:**
   - Only process once (deleted after processing) ✅
   - No duplicate processing ✅

