# Optimization #9: Database Persistence - Deferred Batch Processing Proposal

## Current State

### What We Already Do ✅

1. **Process only changed rooms** (sync_complete rooms, typically 2-3, not all 588)
2. **Batch processing** when backgrounded (process 50 rooms, defer rest)
3. **Defer skipped rooms** to `PendingRoomEntity` (persisted to DB)
4. **Defer receipts** when backgrounded (in RAM, processed on foreground)
5. **Rush mechanism** exists (`rushProcessPendingRooms()`)

### What Still Happens When Backgrounded ⚠️

**Every sync_complete when backgrounded:**
- ✅ Sync metadata transaction (must persist - for sync tracking)
- ✅ Store skipped rooms to `PendingRoomEntity` (if > 50 rooms)
- ⚠️ **Process first 50 rooms immediately** (full DB transaction with events, state, summaries)
- ⚠️ Store receipts to RAM queue (not persisted, lost if app killed)

**Issue:** Even with batching, we still process 50 rooms immediately when backgrounded, which is heavy.

## Proposed Optimization

### Strategy: **Defer ALL Room Processing When Backgrounded**

**When backgrounded:**
1. ✅ **Always persist:** Sync metadata (for sync tracking)
2. ✅ **Always persist:** All room JSON to `PendingRoomEntity` (data safety)
3. ❌ **Skip:** Room processing transaction (defer to foreground)
4. ❌ **Skip:** Receipt processing (already deferred to RAM, but should persist)

**When foregrounded:**
1. ✅ **Rush:** Process all `PendingRoomEntity` immediately
2. ✅ **Rush:** Process all deferred receipts

### Benefits

1. **Data Safety** ✅
   - All room JSON persisted to `PendingRoomEntity` immediately
   - Even if app is killed, data is safe in DB
   - No data loss

2. **Battery Efficiency** ✅
   - No expensive DB transactions when backgrounded
   - Only lightweight JSON storage transaction
   - All heavy processing deferred to foreground

3. **Simplicity** ✅
   - Consistent approach: defer everything when backgrounded
   - No complex batching logic (process all or defer all)

4. **Consistency** ✅
   - All deferred data rushed when foregrounded
   - User sees up-to-date state when app opens

## Implementation Plan

### Phase 1: Defer All Room Processing When Backgrounded

**Change in `SyncIngestor.ingestSyncComplete()`:**

```kotlin
// Current (line 345-397):
val roomsToProcessNow = if (isAppVisible) {
    roomsToProcess  // Process all
} else {
    roomsToProcess.take(BACKGROUND_BATCH_SIZE)  // Process first 50
}

// Proposed:
val roomsToProcessNow = if (isAppVisible) {
    roomsToProcess  // Process all when foregrounded
} else {
    emptyList()  // Defer ALL when backgrounded
}

// Always persist ALL rooms to PendingRoomEntity when backgrounded
if (!isAppVisible && roomsToProcess.isNotEmpty()) {
    val pendingRooms = roomsToProcess.mapNotNull { roomId ->
        val roomObj = roomsJson.optJSONObject(roomId) ?: return@mapNotNull null
        PendingRoomEntity(
            roomId = roomId,
            roomJson = roomObj.toString(),
            timestamp = System.currentTimeMillis()
        )
    }
    
    database.withTransaction {
        pendingRoomDao.upsertAll(pendingRooms)
    }
}
```

### Phase 2: Persist Deferred Receipts

**Current:** Receipts stored in RAM queue (lost if app killed)

**Proposed:** Add `PendingReceiptEntity` to persist deferred receipts

```kotlin
@Entity(tableName = "pending_receipts")
data class PendingReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: String,
    val receiptJson: String,  // Store full receipt JSON
    val timestamp: Long = System.currentTimeMillis()
)
```

**Changes:**
- Store receipts to `PendingReceiptEntity` when backgrounded
- Process all `PendingReceiptEntity` when foregrounded (in `rushProcessPendingReceipts()`)
- Clear after processing

### Phase 3: Enhance Rush Mechanism

**Current:** `rushProcessPendingRooms()` processes pending rooms

**Enhancement:**
- Process all `PendingRoomEntity` 
- Process all `PendingReceiptEntity`
- Do this on app visibility change

## Data Flow

### Background Mode

```
sync_complete arrives
  ↓
1. Persist sync metadata (must persist)
  ↓
2. Persist ALL room JSON to PendingRoomEntity (data safety)
  ↓
3. Persist ALL receipt JSON to PendingReceiptEntity (if any)
  ↓
Done - No heavy DB processing
```

### Foreground Mode / App Becomes Visible

```
App becomes visible
  ↓
1. Rush process all PendingRoomEntity
   - Load from DB
   - Process all rooms in batch transaction
   - Delete processed entries
  ↓
2. Rush process all PendingReceiptEntity
   - Load from DB
   - Process all receipts in batch transaction
   - Delete processed entries
  ↓
3. Continue normal processing for future syncs
```

## Safety Guarantees

✅ **Data Safety:**
- All room JSON persisted immediately
- All receipt JSON persisted immediately
- No data loss if app is killed

✅ **Consistency:**
- All deferred data processed when foregrounded
- User sees up-to-date state

✅ **Efficiency:**
- No heavy DB processing when backgrounded
- All heavy work deferred to foreground

## Performance Impact

### Before (Current)
- Background: Process 50 rooms per sync (~10-50ms per sync)
- Background: Store skipped rooms to DB (~1-5ms per sync)
- **Total: ~11-55ms per sync when backgrounded**

### After (Proposed)
- Background: Store all room JSON to DB (~1-5ms per sync)
- Background: Store receipt JSON to DB (if any, ~0.1-1ms)
- **Total: ~1-6ms per sync when backgrounded**

### Savings
- **~90% reduction** in background processing time
- Heavy work moved to foreground (when user is actually using app)

## Open Questions

1. **Should we limit pending room count?**
   - If app is backgrounded for hours, could accumulate many pending rooms
   - Proposal: Process oldest N rooms periodically (e.g., every 10 syncs, process 50 oldest)

2. **Should we process pending rooms on next sync when foregrounded?**
   - Current: Process at app visibility change
   - Alternative: Process during next sync_complete when foregrounded (simpler)

3. **Should we batch process pending rooms?**
   - Process all at once (could be large transaction)
   - Or batch process (e.g., 50 at a time)

## Recommendation

✅ **Implement Phase 1** (defer all room processing when backgrounded)
- Simple change
- Immediate battery savings
- Data safety maintained

⚠️ **Consider Phase 2** (persist receipts)
- More complex (new entity)
- But provides data safety for receipts

✅ **Use existing rush mechanism** (Phase 3 enhancement)
- Already exists, just needs to process from DB

