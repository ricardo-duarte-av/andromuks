# Ancient View Bug Analysis

## Problem

**Scenario:**
1. App closed
2. Messages arrive in a room (1 or more) → persisted to DB
3. User opens the room
4. **Messages are missing (ancient view shown)**
5. Receive 1 message with room opened
6. **All messages suddenly appear**

## Root Cause

### The Bug: Incorrect Sorting in `BootstrapLoader.loadRoomEvents()`

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/BootstrapLoader.kt:241-251`

**The Problem:**
```kotlin
.sortedWith { a, b ->
    when {
        a.timelineRowid > 0 && b.timelineRowid > 0 -> a.timelineRowid.compareTo(b.timelineRowid)  // ❌ ASCENDING!
        a.timelineRowid > 0 -> -1
        b.timelineRowid > 0 -> 1
        else -> {
            val tsCompare = a.timestamp.compareTo(b.timestamp)  // ❌ ASCENDING!
            if (tsCompare != 0) tsCompare else a.eventId.compareTo(b.eventId)
        }
    }
}
```

**What Happens:**
1. Database query `getEventsForRoomDesc()` correctly gets events ordered by `timestamp DESC, timelineRowId DESC` (newest first)
2. Query returns newest 200 events (if there are 500 total, it gets events 301-500)
3. **BUT THEN** `BootstrapLoader.loadRoomEvents()` re-sorts them by `timelineRowid ASC` (ascending = oldest first)
4. This **reverses the order** - newest events end up at the END of the list
5. When `processCachedEvents()` processes them, it only shows the first events (which are now the OLDEST)
6. Result: **Ancient view** - shows old messages, newest are hidden

**Why New Message Fixes It:**
- When a new message arrives while room is open, it triggers `refreshTimelineFromDatabase()`
- This loads events again, but this time the Flow observation or refresh mechanism properly loads ALL events
- Or the new message triggers a full refresh that bypasses the broken sort

## The Fix

Change the sorting to **DESCENDING** order to preserve the "newest first" order from the database query:

```kotlin
.sortedWith { a, b ->
    when {
        a.timelineRowid > 0 && b.timelineRowid > 0 -> b.timelineRowid.compareTo(a.timelineRowid)  // ✅ DESCENDING
        a.timelineRowid > 0 -> 1  // Events with timelineRowid come first (newer)
        b.timelineRowid > 0 -> -1
        else -> {
            val tsCompare = b.timestamp.compareTo(a.timestamp)  // ✅ DESCENDING
            if (tsCompare != 0) tsCompare else b.eventId.compareTo(a.eventId)  // ✅ DESCENDING
        }
    }
}
```

**Wait, actually...** The timeline should be in **chronological order** (oldest to newest), not newest to oldest. So the sort should be **ASCENDING** for timeline display.

But the issue is: the database query gets the **newest 200 events**, then we sort them ascending. This means:
- If there are 500 events total
- Query gets events 301-500 (newest 200)
- Sort puts them in order: 301, 302, ..., 500
- This is correct for timeline display!

**So the sort is actually correct...**

## Real Root Cause

After analyzing the code flow, I found **TWO issues**:

### Issue 1: Limited Event Loading (FIXED ✅)

**When opening a room:**
1. `requestRoomTimeline()` loads only **200 events** from database (line 6416)
2. Query `getEventsForRoomDesc()` orders by `timestamp DESC, timelineRowId DESC` - gets **newest 200**
3. `BootstrapLoader.loadRoomEvents()` sorts them by `timelineRowid ASC` - puts in chronological order
4. **Problem:** If there are gaps in timelineRowid or events don't have proper timestamps, the query might not get the actual newest events
5. **Problem:** Limit of 200 might miss events if there are more than 200 total

**Why New Message Fixes It:**
- When a new message arrives while room is open, `refreshTimelineFromDatabase()` is called
- This loads **MAX_TIMELINE_EVENTS_PER_ROOM (1000)** events (line 6279)
- This loads ALL recent events, not just 200
- All messages suddenly appear!

**Fix Applied:** Changed limit from 200 to `MAX_TIMELINE_EVENTS_PER_ROOM` (1000) when loading from DB on room open.

### Issue 2: Potential Query/Sorting Problem

**The sorting logic:**
- Database query: `ORDER BY timestamp DESC, timelineRowId DESC` - gets newest first
- BootstrapLoader sort: `timelineRowid ASC` - puts in chronological order (oldest first)

**Potential Issue:**
- If events arrive while app is closed and don't have `timelineRowid` set (or have `timelineRowid = -1`), they fall into the `else` branch
- The `else` branch sorts by `timestamp ASC` (ascending = oldest first)
- But if timestamps are wrong or missing, events might be sorted incorrectly
- OR: Events without timelineRowid might be sorted to the beginning, showing old events first

**The Real Problem:**
The query gets events ordered by `timestamp DESC`, but if many events have the same timestamp (or timestamps are missing), the secondary sort by `timelineRowId DESC` determines order. Then BootstrapLoader sorts by `timelineRowid ASC`, which should work... **UNLESS** events don't have timelineRowid set correctly.

**When events arrive while app is closed:**
- They're persisted by `SyncIngestor.ingestSyncComplete()`
- `timelineRowid` is assigned based on the event's `timeline_rowid` field from sync_complete
- If this field is missing or -1, the event might not sort correctly

### Complete Fix

1. ✅ **Increased limit** from 200 to 1000 (already done)
2. **Verify events have proper timelineRowid** when persisted while app is closed
3. **Add fallback** to ensure we always load the newest events, even if sorting is wrong

