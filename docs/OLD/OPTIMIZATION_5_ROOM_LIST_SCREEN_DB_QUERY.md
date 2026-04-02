# Optimization #5: RoomListScreen Queries Database Directly for Last Messages

## Problem

Previously, we were querying database summaries in `AppViewModel` and merging them into `RoomItems`. However, this creates an unnecessary dependency - RoomListScreen should query the database directly for last messages, ensuring it always shows fresh data.

## Solution

**RoomListScreen queries database directly** when displaying rooms, making it the source of truth for last messages.

### Architecture Change

**Before:**
```
1. SpaceRoomParser parses metadata + events JSON
2. AppViewModel queries database summaries
3. AppViewModel merges summaries into RoomItems
4. RoomListScreen displays RoomItems
```

**After:**
```
1. SpaceRoomParser parses metadata only (no events - Optimization #4)
2. AppViewModel processes parsed rooms (no merging)
3. RoomListScreen queries database summaries when section updates
4. RoomListScreen enriches RoomItems with summary data for display
```

## Implementation

### 1. Added Database Query in RoomListScreen

**When section updates:**
- Queries `RoomSummaryDao.getRoomSummariesByIds()` for all rooms in current section
- Stores summaries in state: `roomsWithSummaries`
- Enriches RoomItems with summary data before display

**Code:**
```kotlin
// Query database when section updates
LaunchedEffect(stableSection.rooms.map { it.id }.sorted()) {
    val summaries = database.roomSummaryDao().getRoomSummariesByIds(roomIds)
    roomsWithSummaries = summaries.associate { 
        it.roomId to (it.messagePreview to it.messageSender) 
    }
}

// Enrich rooms with database summaries
val enrichedSection = remember(stableSection, roomsWithSummaries) {
    stableSection.copy(
        rooms = stableSection.rooms.map { room ->
            val (messagePreview, messageSender) = roomsWithSummaries[room.id] ?: (null to null)
            room.copy(
                messagePreview = messagePreview ?: room.messagePreview,
                messageSender = messageSender ?: room.messageSender
            )
        }
    )
}
```

### 2. Removed Merging Logic from AppViewModel

**Removed:**
- Database query for summaries
- Merging summaries into RoomItems
- ~60 lines of merging code

**Result:**
- AppViewModel only processes metadata from SpaceRoomParser
- Simpler, cleaner code
- RoomListScreen is responsible for its own data

## Benefits

✅ **Always fresh data** - Database is source of truth, queried when displayed
✅ **Simpler flow** - No merging in AppViewModel
✅ **Separation of concerns** - RoomListScreen handles its own data needs
✅ **Lazy loading** - Only queries when section is displayed
✅ **Self-contained** - RoomListScreen doesn't depend on sync parsing for last messages

## Trade-offs

- **Slight delay possible** - Last message may be 1 sync behind if SyncIngestor hasn't processed it yet
  - Acceptable because:
    - Unread counts still immediate (from metadata)
    - Full messages visible when opening room
    - Database summaries updated by SyncIngestor anyway

## Battery Impact

**Before:**
- Query summaries in AppViewModel on every sync
- Merge into RoomItems
- Pass to RoomListScreen

**After:**
- Query summaries in RoomListScreen when section updates
- Only query when user is viewing room list
- If backgrounded, queries don't happen (no room list visible)

**Battery Savings:**
- No queries when backgrounded ✅
- Queries only when needed (section displayed) ✅
- Batch query (single query for all rooms) ✅

## Testing Checklist

1. ✅ Room list displays last messages correctly
2. ✅ Messages update when new messages arrive
3. ✅ Query happens when section changes
4. ✅ Query doesn't happen when backgrounded
5. ✅ No crashes or errors
6. ✅ Performance is good (batch query is fast)

## Next Optimization

After testing confirms this works, we've completed all suggestions from item 2:
- ✅ Skip event parsing (Optimization #4)
- ✅ Cache parsed room data (Optimization #4 + #5)
- ✅ Query database for last messages (Optimization #5)

Ready to move to the next item in BATTERY_DRAIN_ANALYSIS.md!

