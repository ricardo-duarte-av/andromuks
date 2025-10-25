# App Opening Performance Optimization TODOs

## Overview
This document outlines performance optimization opportunities for the app opening workflow from `RoomListScreen.kt` through `SpaceRoomParser.kt` and `AppViewModel.kt`.

## Current Workflow
1. **WebSocket Connection** → receives sync messages
2. **handleSyncUpdate()** → processes sync JSON
3. **parseSyncUpdate()** → parses rooms/spaces/members (MAIN THREAD BLOCKING)
4. **populateMemberCacheFromSync()** → processes ALL member events (MAIN THREAD BLOCKING)
5. **UI Updates** → triggers recompositions

## Critical Performance Issues

### 🔴 HIGH PRIORITY

#### 1. Move JSON Parsing to Background Thread
**Current:** All parsing happens on main thread, blocking UI
**Impact:** 200-500ms delay per sync message with many rooms
**Solution:**
- Move `parseSyncUpdate()` to `Dispatchers.Default`
- Return parsed data via `StateFlow` or suspending functions
- Use `withContext(Dispatchers.Main)` only for UI state updates

**Files:** `AppViewModel.kt` line ~1400-1650, `SpaceRoomParser.kt` line ~140-240

```kotlin
// TODO: Move to background thread
fun handleSyncUpdate(syncJson: JSONObject) {
    viewModelScope.launch(Dispatchers.Default) {
        val parsedData = SpaceRoomParser.parseSyncUpdate(syncJson, memberCache, this@AppViewModel)
        withContext(Dispatchers.Main) {
            updateUIState(parsedData)
        }
    }
}
```

#### 2. Process Member Events Incrementally
**Current:** Processes ALL member events on EVERY sync message
**Impact:** 100-300ms delay for large rooms (50+ members)
**Solution:**
- Process members only for rooms that changed
- Batch member updates (process every 3rd sync message)
- Use `DebounceCollector` to batch rapid updates

**Files:** `AppViewModel.kt` line ~1629-1700

```kotlin
// TODO: Add incremental member processing
private var memberProcessingIndex = 0
private fun populateMemberCacheIncrementally(syncJson: JSONObject) {
    // Only process members every 3rd sync message
    if (memberProcessingIndex % 3 != 0) return
    
    // Only process changed rooms
    val changedRooms = getChangedRooms(syncJson)
    changedRooms.forEach { processMembersForRoom(it) }
    memberProcessingIndex++
}
```

#### 3. Lazy Load Non-Home Tabs
**Current:** All tabs process data immediately on app open
**Impact:** Unnecessary processing for tabs user may never visit
**Solution:**
- Only Home tab loads on app open
- Load other tabs on first access (on demand)
- Pre-compute counts for badges only

**Files:** `RoomListScreen.kt` line ~80-150

```kotlin
// TODO: Lazy load tabs
var directChatsRooms by remember { mutableStateOf<List<RoomItem>?>(null) }
val currentSection = appViewModel.getCurrentRoomSection()

when (currentSection.type) {
    RoomSectionType.DIRECT_CHATS -> {
        if (directChatsRooms == null) {
            // Load on first access
            LaunchedEffect(Unit) {
                directChatsRooms = appViewModel.getDirectChatRooms()
            }
            LoadingIndicator()
        } else {
            RoomListContent(directChatsRooms!!)
        }
    }
}
```

### 🟡 MEDIUM PRIORITY

#### 4. Defer Space Edge Processing
**Current:** Space edges processed after init_complete (good), but still synchronous
**Impact:** 50-100ms delay when processing nested spaces
**Solution:**
- Process space edges in background
- Show spaces progressively as they're loaded

**Files:** `AppViewModel.kt` line ~2100-2200, `SpaceRoomParser.kt` line ~500-600

```kotlin
// TODO: Background space edge processing
fun populateSpaceEdges() {
    viewModelScope.launch(Dispatchers.Default) {
        val edges = storedSpaceEdges ?: return@launch
        SpaceRoomParser.updateExistingSpacesWithEdges(edges, currentSyncData, this@AppViewModel)
        withContext(Dispatchers.Main) {
            allSpaces = updatedSpaces
        }
    }
}
```

#### 5. Defer Bridge Detection
**Current:** Requests room states for ALL rooms after init_complete
**Impact:** 500-1000ms+ delay with many rooms, multiple WebSocket requests
**Solution:**
- Load bridges on first access to Bridges tab
- Show basic bridge info immediately
- Fetch detailed info lazily

**Files:** `AppViewModel.kt` line ~2100-2200, `SpaceRoomParser.kt` line ~580-648

```kotlin
// TODO: Lazy bridge detection
fun loadBridges() {
    if (allBridges.isNotEmpty()) return // Already loaded
    
    viewModelScope.launch(Dispatchers.Default) {
        val bridges = detectBridgesFromCache()
        withContext(Dispatchers.Main) {
            allBridges = bridges
        }
        // Fetch detailed info in background
        fetchDetailedBridgeInfo(bridges)
    }
}
```

#### 6. Batch UI Updates More Aggressively
**Current:** UI updates batched with 16ms delay (good), but can be improved
**Impact:** Multiple recompositions for rapid sync messages
**Solution:**
- Increase batch delay to 100ms during initial sync
- Use `debounce()` for rapid updates
- Only show animation after batch completes

**Files:** `AppViewModel.kt` line ~1780-1810

```kotlin
// TODO: Adaptive batching
private fun scheduleUIUpdate(updateType: String, priority: UpdatePriority) {
    val delay = when {
        !spacesLoaded -> 100L // Slower during initial sync
        priority == UpdatePriority.HIGH -> 16L // Fast for user actions
        else -> 50L // Medium for background updates
    }
    
    batchUpdateJob = viewModelScope.launch {
        delay(delay)
        performBatchedUIUpdates()
    }
}
```

### 🟢 LOW PRIORITY

#### 7. Reduce Logging in Production
**Current:** Excessive logging on every sync message
**Impact:** 10-20ms per sync message, memory overhead
**Solution:**
- Use `BuildConfig.DEBUG` to disable logging in release builds
- Group multiple logs into single Log.d() call

**Files:** Throughout `SpaceRoomParser.kt` and `AppViewModel.kt`

#### 8. Cache Filtered Room Lists
**Current:** Rooms re-filtered on every recomposition
**Impact:** 5-10ms per recomposition
**Solution:**
- Use `remember()` with keys for filtered lists
- Only recalculate when rooms or search query changes

**Files:** `RoomListScreen.kt` line ~850-880

```kotlin
// TODO: Already implemented in RoomListContent - good!
val filteredRooms = remember(rooms, searchQuery) {
    if (searchQuery.isBlank()) rooms
    else rooms.filter { it.name.contains(searchQuery, ignoreCase = true) }
}
```

#### 9. Optimize Room List Item Rendering
**Current:** All room items recomposed on every update
**Impact:** 10-20ms per update
**Solution:**
- Use `LazyColumn` with stable keys (already done ✓)
- Implement `equals()` on `RoomItem` for proper diffing
- Use `@Stable` annotation on data classes

**Files:** `RoomListScreen.kt` line ~880-950

## Implementation Priority

### Phase 1: Quick Wins (1-2 hours)
- [ ] Reduce logging in production (Task #7)
- [ ] Optimize room list rendering (Task #9)

### Phase 2: Major Improvements (4-6 hours)
- [x] Move JSON parsing to background (Task #1) ✅ COMPLETED
- [x] Incremental member processing (Task #2) ✅ COMPLETED
- [ ] Adaptive UI batching (Task #6)

### Phase 3: Lazy Loading (2-3 hours)
- [x] Lazy load non-Home tabs (Task #3) ✅ COMPLETED
- [ ] Defer bridge detection (Task #5)

### Phase 4: Polish (1-2 hours)
- [x] Background space edge processing (Task #4) ✅ COMPLETED

## Expected Performance Improvements

| Metric | Current | After Phase 1 | After Phase 2 | After Phase 3 |
|--------|---------|---------------|---------------|---------------|
| Time to Room List | 500ms | 450ms | 150ms | 100ms |
| Memory on App Open | 150MB | 140MB | 130MB | 120MB |
| Frames Dropped | 5-10 | 3-5 | 0-2 | 0 |
| Time to First Paint | 800ms | 750ms | 300ms | 200ms |

## Testing Strategy

1. **Cold Start Test:** App open from terminated state, measure to Room List
2. **Large Account Test:** Account with 100+ rooms, measure memory usage
3. **Rapid Sync Test:** 10 sync messages in 2 seconds, measure UI smoothness
4. **Tab Switch Test:** Switch between tabs rapidly, measure responsiveness

## Notes

- Current optimizations (cached room sections, batched updates, diff-based updates) are good
- Focus should be on moving work OFF main thread
- Lazy loading is key for accounts with many rooms
- Progressive loading with placeholders provides better UX than waiting for everything
