# Timeline Stale Data Analysis

## Problem
Sometimes `RoomTimelineScreen` and `BubbleTimelineScreen` render an old view of the room even though:
- FCM notifications are received (indicating sync_complete arrived)
- WebSocket sync_complete was received
- Closing and reopening the room fixes it

## Root Cause Analysis

### 1. Database Writes are ASYNC ⚠️

**Answer:** YES, all DB writes are async using `withContext(Dispatchers.IO)`.

**Risk:** There IS a risk of a select missing a commit.

**Evidence:**
- `SyncIngestor.ingestSyncComplete()` uses `withContext(Dispatchers.IO)` (line 164)
- All database operations are async
- `refreshTimelineFromDatabase()` is called with only a 50ms delay (line 3152)
- If DB write takes >50ms, refresh might not see new events

**Race Condition:**
```
sync_complete arrives
  ↓
SyncIngestor.ingestSyncComplete() starts (async, Dispatchers.IO)
  ↓
processSyncEventsArray() processes events → updates timelineEvents
  ↓
refreshTimelineFromDatabase() called after 50ms delay
  ↓
DB write might not be complete yet → refresh sees old data
```

### 2. Room LiveData Usage ❌

**Answer:** NO, we are NOT using Room LiveData.

**Current State:**
- Using Jetpack Compose `mutableStateOf` for `timelineEvents`
- No reactive database queries
- Manual refresh required

### 3. Jetpack Compose State ✅

**Answer:** YES, we ARE using Jetpack Compose state.

**Current Implementation:**
```kotlin
var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
var timelineUpdateCounter by mutableStateOf(0L)
```

**Problem:** State only updates when explicitly set, not automatically from DB changes.

### 4. Stale Cache ⚠️

**Answer:** YES, there are multiple caches that can be stale:

1. **RoomTimelineCache** - In-memory cache (can be stale)
2. **eventChainMap** - In-memory event chain (can miss new events)
3. **Database** - Can have race conditions with async writes

### 5. How New Events Are Detected When Room Is Open

**Current Flow:**
1. `sync_complete` arrives via WebSocket
2. `handleSyncComplete()` called (line 3089)
3. `checkAndUpdateCurrentRoomTimeline()` called (line 9644)
   - **ONLY if `currentRoomId != null` AND room is in sync batch**
4. `updateTimelineFromSync()` called (line 9693)
5. `processSyncEventsArray()` processes events (line 9758)
6. Events added to `eventChainMap`
7. `buildTimelineFromChain()` rebuilds timeline (line 9971)
8. `refreshTimelineFromDatabase()` called with 50ms delay (line 3152)

**Problems:**
- If `currentRoomId` doesn't match, events are cached but NOT processed
- If DB write is slow, refresh might miss events
- No reactive mechanism - relies on manual refresh

## Solutions

### Fix 1: Ensure DB Write Completes Before Refresh

Wait for DB write to complete before calling refresh.

### Fix 2: Add Reactive Timeline Updates

Watch for DB changes and update timeline automatically.

### Fix 3: Improve Sync Event Processing

Ensure events are always processed even if timing is off.

### Fix 4: Add Fallback Refresh Mechanism

Periodically check for new events when room is open.


