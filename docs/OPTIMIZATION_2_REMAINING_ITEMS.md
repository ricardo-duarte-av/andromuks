# Optimization #2 - Remaining Items from Item 1

## What We've Completed ✅

1. ✅ **Optimization #1**: Selective room state loading (only load states for rooms in sync)
2. ✅ **Optimization #2**: Skip/query room summary instead of scanning JSON

## What's Still Missing from Item 1

### 1. Process Rooms in Batches When Backgrounded ⚠️

**Current State:**
- Processes ALL rooms in sync, even when backgrounded
- Line 255-257: `for (roomId in roomsToProcess)` processes all rooms

**Recommendation:**
- When backgrounded, process rooms in batches (e.g., 50 at a time)
- Process remaining rooms in subsequent syncs
- Or skip processing unchanged rooms entirely when backgrounded

**Battery Impact:**
- With 588 rooms, processing all of them even when backgrounded wastes battery
- User can't see the UI anyway, so we can defer processing

**Implementation:**
```kotlin
if (isAppVisible) {
    // Process all rooms immediately
    for (roomId in roomsToProcess) {
        processRoom(...)
    }
} else {
    // Background: Process in batches
    val batchSize = 50
    val roomsToProcessNow = roomsToProcess.take(batchSize)
    for (roomId in roomsToProcessNow) {
        processRoom(...)
    }
    // TODO: Track remaining rooms for next sync
}
```

### 2. Defer Receipt Processing to Periodic Batch Updates ⚠️

**Current State:**
- Line 444-472: Processes receipts for EVERY room, EVERY sync
- Even when backgrounded, receipts are processed immediately

**Recommendation:**
- When backgrounded, skip receipt processing entirely
- Process receipts periodically (e.g., every 5-10 syncs) or when app becomes visible
- Receipts are not critical for notifications, can be deferred

**Battery Impact:**
- Receipt processing involves JSON parsing and database writes
- With 588 rooms, this is significant overhead
- Receipts don't affect notifications, so deferring is safe

**Implementation:**
```kotlin
// In processRoom()
if (isAppVisible || shouldProcessReceiptsThisSync()) {
    // Process receipts (line 444-472)
} else {
    // Skip receipt processing when backgrounded
}
```

### 3. Optimize timelineRowId Lookups (Partially Done) ⚠️

**Current State:**
- Line 617: Only queries if `resolvedTimelineRowId <= 0` ✅
- Line 618-625: Checks cache first, then queries database
- This is already optimized, but could be better

**Potential Further Optimization:**
- Pre-populate cache for all events in sync before processing
- Batch query all missing timelineRowIds at once instead of one-by-one
- Skip lookup entirely if event already has timelineRowId from JSON

**Current Code:**
```kotlin
if (resolvedTimelineRowId <= 0) {
    val cachedValue = existingTimelineRowCache[eventId]
    val preservedRowId = if (cachedValue != null) {
        cachedValue  // ✅ Uses cache
    } else {
        val existing = eventDao.getEventById(roomId, eventId)  // ⚠️ One-by-one query
        existingTimelineRowCache[eventId] = existing?.timelineRowId
        existing?.timelineRowId
    }
}
```

**Better Approach:**
- Batch query all events missing timelineRowId at once
- Populate cache in bulk
- Reduces database queries from N queries to 1 query

## Priority Order

### High Priority (Biggest Battery Impact)

1. **Defer receipt processing when backgrounded** - Easy win, receipts not critical
2. **Process rooms in batches when backgrounded** - Reduces processing load significantly

### Medium Priority

3. **Batch timelineRowId lookups** - Already optimized, but could be better

## Next Steps

1. Implement receipt processing deferral (easiest, biggest impact)
2. Implement batch room processing when backgrounded
3. Consider batch timelineRowId queries (if still needed after other optimizations)

