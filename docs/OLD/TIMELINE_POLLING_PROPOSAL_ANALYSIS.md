# Timeline Polling Proposal Analysis

## Your Proposal

**Idea:** Maintain a table with `room_id` and `latest_line_timestamp`, poll it every second when a room is open, and refresh if timestamp changes.

## Analysis

### ✅ Pros

1. **Simple and Reliable**
   - Would reliably detect new events
   - Avoids race conditions with async writes
   - Easy to implement

2. **Event-Driven Updates**
   - Only refreshes when timestamp actually changes
   - More efficient than constant polling of full event list

### ❌ Cons & Criticisms

1. **Polling Every Second is CPU/Battery Heavy**
   - **CPU:** Constant DB queries every second for each open room
   - **Battery:** Wakes up device frequently, prevents deep sleep
   - **Scalability:** With multiple rooms open (bubbles), multiplies the load
   - **Better:** Use Room's reactive Flow/LiveData instead (event-driven, no polling)

2. **Redundant Table**
   - We already have `events` table with `timestamp` column
   - We already have `getLastEventTimestamp()` DAO method (line 84)
   - **Better:** Query `MAX(timestamp)` directly from events table

3. **Maintenance Overhead**
   - Need to update the table on every event write
   - Risk of table getting out of sync with events table
   - Another point of failure

4. **Race Conditions Still Possible**
   - If we poll at T=0s and event is written at T=0.5s, we miss it until T=1s
   - Still need retry logic

## Better Alternatives

### Option 1: Use Room Flow (RECOMMENDED) ⭐

**Best solution:** Use Room's reactive Flow to observe the events table directly.

**Benefits:**
- **Event-driven:** Only triggers when DB actually changes
- **Zero polling:** No CPU/battery waste
- **Automatic:** Room handles all the complexity
- **Reliable:** No race conditions

**Implementation:**
```kotlin
// In EventDao.kt
@Query("SELECT MAX(timestamp) FROM events WHERE roomId = :roomId AND timestamp > 0")
fun observeLastEventTimestamp(roomId: String): Flow<Long?>

// In AppViewModel.kt
fun observeRoomTimelineChanges(roomId: String): Flow<Long?> {
    return eventDao.observeLastEventTimestamp(roomId)
        .distinctUntilChanged() // Only emit when timestamp actually changes
}

// In RoomTimelineScreen.kt
LaunchedEffect(roomId) {
    appViewModel.observeRoomTimelineChanges(roomId)
        .collect { latestTimestamp ->
            if (latestTimestamp != null && latestTimestamp > currentLatestTimestamp) {
                // Refresh timeline
                refreshTimelineFromDatabase(roomId)
            }
        }
}
```

**CPU Impact:** Near zero - only triggers on actual DB changes

### Option 2: Query MAX(timestamp) Directly (Simpler)

**If Flow is too complex:** Just query `MAX(timestamp)` from events table (we already have this method!)

**Implementation:**
```kotlin
// In RoomTimelineScreen.kt
LaunchedEffect(roomId) {
    var lastKnownTimestamp = 0L
    
    while (appViewModel.currentRoomId == roomId) {
        kotlinx.coroutines.delay(2000) // Check every 2 seconds (less aggressive)
        
        val latestTimestamp = withContext(Dispatchers.IO) {
            eventDao.getLastEventTimestamp(roomId) ?: 0L
        }
        
        if (latestTimestamp > lastKnownTimestamp) {
            refreshTimelineFromDatabase(roomId)
            lastKnownTimestamp = latestTimestamp
        }
    }
}
```

**CPU Impact:** Low - one simple query every 2 seconds (much better than 1 second)

### Option 3: Hybrid Approach (Current + Improvements)

**Keep current sync_complete mechanism but improve it:**

1. ✅ Already fixed: Retry mechanism in `refreshTimelineFromDatabase()`
2. ✅ Already fixed: Refresh even if room not in sync batch
3. **Add:** Fallback periodic check (every 5-10 seconds, not 1 second)

**Implementation:**
```kotlin
// In RoomTimelineScreen.kt - fallback only
LaunchedEffect(roomId) {
    while (appViewModel.currentRoomId == roomId) {
        kotlinx.coroutines.delay(5000) // Check every 5 seconds (fallback only)
        
        // Only check if we haven't seen an update recently
        val timeSinceLastUpdate = System.currentTimeMillis() - lastTimelineUpdateTime
        if (timeSinceLastUpdate > 10000) { // 10 seconds since last update
            // Fallback: check DB for new events
            val latestTimestamp = eventDao.getLastEventTimestamp(roomId)
            if (latestTimestamp != null && latestTimestamp > currentLatestTimestamp) {
                refreshTimelineFromDatabase(roomId)
            }
        }
    }
}
```

**CPU Impact:** Very low - only runs as fallback, infrequently

## Recommendation

**Use Option 1 (Room Flow)** - It's the most efficient and follows Android best practices.

**If Flow is not feasible, use Option 2** with 2-5 second intervals instead of 1 second.

**Avoid:** Your original proposal (separate table + 1 second polling) - too heavy on resources.

## Performance Comparison

| Approach | CPU Usage | Battery Impact | Latency | Complexity |
|----------|-----------|----------------|---------|------------|
| Your Proposal (1s poll) | High | High | 0-1s | Low |
| Option 1 (Flow) | Near Zero | Near Zero | Real-time | Medium |
| Option 2 (2s poll) | Low | Low | 0-2s | Low |
| Option 3 (5s fallback) | Very Low | Very Low | 0-5s | Low |

## Conclusion

Your idea is sound but can be optimized:
- ✅ **Keep the concept:** Monitor timestamp changes
- ❌ **Don't create separate table:** Use existing `MAX(timestamp)` query
- ❌ **Don't poll every second:** Use Room Flow or poll less frequently (2-5s)
- ✅ **Use event-driven:** Room Flow is the best solution


