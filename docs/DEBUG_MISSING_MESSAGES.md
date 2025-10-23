# Debug Missing Messages from Other Devices

## Problem
Messages sent from your account on other devices are not showing up in the timeline when you open a room.

## Root Cause Analysis

After analyzing the code, I found that **events are only processed into the live timeline when that specific room is currently open**. However, events SHOULD still appear when you open the room later because:

1. **Events ARE cached** when they arrive via sync_complete (even if room is closed)
2. **When you open a room**, it checks the cache first
3. **If cache has >= 100 events**, it uses cache (instant)
4. **If cache has < 100 events**, it requests from server via `paginate`
5. **Either way, your messages should appear!**

If messages are still missing after closing and reopening the room (or using refresh), it means they're being **filtered out somewhere**.

### Event Processing Flow

1. **sync_complete arrives** from server with events for 1+ rooms
2. **Events are cached** for ALL rooms via `cacheTimelineEventsFromSync()` 
3. **Events are processed into live timeline** ONLY IF:
   - `currentRoomId != null` (a room is currently open)
   - AND the sync_complete contains events for that specific room
   - AND `isAppVisible == true` (app not in background)

### Code Locations

**AppViewModel.kt Line 3345-3370:**
```kotlin
private fun checkAndUpdateCurrentRoomTimeline(syncJson: JSONObject) {
    // Only update timeline if room is currently open
    if (currentRoomId != null && rooms.has(currentRoomId)) {
        updateTimelineFromSync(syncJson, currentRoomId!!)
    } else {
        // Events are cached but NOT processed into timeline
    }
}
```

**AppViewModel.kt Line 1092:**
```kotlin
if (isAppVisible) {
    // Only process timeline updates when app is visible
    checkAndUpdateCurrentRoomTimeline(syncJson)
}
```

### Cache Filtering

**RoomTimelineCache.kt Line 207:**
Events are filtered when being cached:
```kotlin
if (event.timelineRowid >= 0 && 
    event.type != "m.reaction" && 
    event.type != "m.room.member") {
    events.add(event)  // Only these events are cached
}
```

**Potential Issue:** If sync_complete events have `timelineRowid = -1`, they will be filtered out and NOT cached at all!

## Debug Logging Added

I've added comprehensive logging to trace exactly what's happening:

### 1. Room Open Status (AppViewModel.kt)
```
AppViewModel: sync_complete contains events for X rooms: [room1, room2, ...]
AppViewModel: currentRoomId = !abc:server.com (ROOM OPEN)
AppViewModel: ✓ Processing sync_complete events for OPEN room: !abc:server.com
```
OR
```
AppViewModel: currentRoomId = null (NO ROOM OPEN)
AppViewModel: ✗ Skipping sync_complete - no room currently open (events will be cached only)
```

### 2. Cache Operations (AppViewModel.kt)
```
AppViewModel: Caching 5 events for room: !abc:server.com (current room: !xyz:server.com)
```

### 3. Cache Filtering (RoomTimelineCache.kt)
For each event being cached:
```
RoomTimelineCache: Cached event: $abc123 type=m.room.message sender=@you:server.com timelineRowid=12345
```
OR if filtered:
```
RoomTimelineCache: Filtered event: $abc123 type=m.room.message sender=@you:server.com reason=[timelineRowid < 0 (-1)]
RoomTimelineCache: Filtered 3 events from cache. Reasons: {timelineRowid < 0 (-1): 3}
```

### 4. Own Messages Processing (AppViewModel.kt)
When YOUR messages arrive from other devices:
```
AppViewModel: [LIVE SYNC] ★ Processing OUR OWN message from another device: $abc123 body='Hello from...' timelineRowid=12345
AppViewModel: [LIVE SYNC] Adding event $abc123 to chain (sender=@you:server.com)
```

## How to Debug

1. **Send a message from another device** while Andromuks is open on your phone
2. **Watch logcat** (filter by "Andromuks" tag)
3. **Look for these patterns:**

### Scenario A: Room is Open ✓
```
AppViewModel: sync_complete contains events for 1 rooms: [!abc:server.com]
AppViewModel: currentRoomId = !abc:server.com (ROOM OPEN)
AppViewModel: ✓ Processing sync_complete events for OPEN room: !abc:server.com
AppViewModel: [LIVE SYNC] ★ Processing OUR OWN message from another device: $xyz789 body='test' timelineRowid=123
AppViewModel: [LIVE SYNC] Adding event $xyz789 to chain (sender=@you:server.com)
```
**Expected Result:** Message should appear in timeline immediately

### Scenario B: Room is Closed ✗
```
AppViewModel: sync_complete contains events for 1 rooms: [!abc:server.com]
AppViewModel: currentRoomId = null (NO ROOM OPEN)
AppViewModel: ✗ Skipping sync_complete - no room currently open (events will be cached only)
AppViewModel: Caching 1 events for room: !abc:server.com (current room: null)
RoomTimelineCache: Cached event: $xyz789 type=m.room.message sender=@you:server.com timelineRowid=123
```
**Expected Result:** Message is cached. When you open the room, it should load from cache.

### Scenario C: Different Room is Open ✗
```
AppViewModel: sync_complete contains events for 1 rooms: [!abc:server.com]
AppViewModel: currentRoomId = !different:server.com (ROOM OPEN)
AppViewModel: ✗ Skipping sync_complete - current room !different:server.com not in this sync batch
AppViewModel: Caching 1 events for room: !abc:server.com (current room: !different:server.com)
RoomTimelineCache: Cached event: $xyz789 type=m.room.message sender=@you:server.com timelineRowid=123
```
**Expected Result:** Message is cached. When you open the room, it should load from cache.

### Scenario D: Event Filtered Out ✗ (BUG!)
```
AppViewModel: Caching 1 events for room: !abc:server.com (current room: null)
RoomTimelineCache: Filtered event: $xyz789 type=m.room.message sender=@you:server.com reason=[timelineRowid < 0 (-1)]
RoomTimelineCache: Filtered 1 events from cache. Reasons: {timelineRowid < 0 (-1): 1}
```
**This is the bug!** Event has `timelineRowid = -1` and is being filtered out completely.

### Scenario E: Opening a Room - Cache Hit
```
requestRoomTimeline called for room: !abc:server
✓ USING CACHE for instant room opening: 105 events (including 5 of your own messages)
★ Cache contains 5 messages from YOU
```
**Expected Result:** Your 5 messages should render immediately

### Scenario F: Opening a Room - Cache Miss (Paginate Fallback)
```
requestRoomTimeline called for room: !abc:server
✗ NO CACHE (or < 100 events) - falling back to PAGINATE request from server
Sent paginate request_id=42 for room=!abc:server
---
[Later, paginate response arrives]
processEventsArray called with 95 events from server
[PAGINATE] ★ Found OUR message in paginate response: $123 body='test' timelineRowid=456
[PAGINATE] ★ Found OUR message in paginate response: $124 body='hello' timelineRowid=457
Processed events - timeline=95, members=12, ownMessages=2
★★★ PAGINATE RESPONSE CONTAINS 2 OF YOUR OWN MESSAGES ★★★
```
**Expected Result:** Your messages should render after paginate completes

## Next Steps

1. **Test with this logging** to see which scenario is happening
2. **Look for "Filtered event"** messages - these indicate events being dropped
3. **Check timelineRowid values** - if they're -1, that's the problem
4. **If events are being filtered**, we need to fix the cache filter logic

## Potential Fixes

### Fix 1: Change Cache Filter (if timelineRowid = -1 is the issue)
```kotlin
// Change from:
if (event.timelineRowid >= 0 && ...)

// To:
if (event.type != "m.reaction" && event.type != "m.room.member") {
    // Accept all message events regardless of timelineRowid
}
```

### Fix 2: Process Events for All Rooms (more comprehensive)
Instead of only processing when room is open, process events for all rooms in sync_complete.
This would require refactoring `checkAndUpdateCurrentRoomTimeline()`.

### Fix 3: Force Cache Refresh on Room Open
When opening a room, always refresh from cache to ensure latest events are shown.

