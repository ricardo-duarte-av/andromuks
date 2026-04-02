# Phase 6: Room Summary - Why Is It Heavy?

## The Problem

You're right that finding the last message *should* be simple. However, there are several inefficiencies that make this operation heavier than necessary:

## Issue 1: Scanning Through Many Non-Message Events

The `timeline` array in sync_complete can contain **many types of events**, not just messages:

```json
"timeline": [
  { "event": { "type": "m.room.member", ... } },      // Member join
  { "event": { "type": "m.typing", ... } },            // Typing indicator
  { "event": { "type": "m.room.name", ... } },        // Room name change
  { "event": { "type": "m.room.member", ... } },      // Member leave
  { "event": { "type": "m.receipt", ... } },          // Read receipt
  { "event": { "type": "m.room.message", ... } }      // Actual message (last)
]
```

**The code scans backwards through ALL events** until finding a message:
- Parses JSON for each timeline entry (line 485: `timelineEntry.optJSONObject()`)
- Parses JSON for each event (line 486: `eventJson.optJSONObject()`)
- Checks event type string (line 488)
- Skips non-message events
- **If last event is a message**: Finds it quickly ✅
- **If last 10 events are non-messages**: Parses 10 events before finding message ❌

**Example worst case:**
- Timeline has 50 events: 49 typing/member events, 1 message at position 0
- Code parses 49 events before finding the message
- This happens for **every room** in sync

## Issue 2: Redundant Processing

**This work is done TWICE:**

1. **SpaceRoomParser.parseRoomFromJson()** (lines 254-329) already scans events to find last message
2. **SyncIngestor.processRoom()** Phase 6 (lines 471-535) does it again

Both functions do the same work:
- Scan events array backwards
- Find last `m.room.message` or `m.room.encrypted`
- Extract sender, body, timestamp

**Waste:** We're parsing the same JSON twice for the same purpose.

## Issue 3: Processing Unchanged Rooms

The summary is updated for **every room** in sync_complete, even if:
- The room hasn't changed
- The last message is the same as before
- The unread count hasn't changed

**From your logs:**
```
Total rooms now: 588 (updated: 1, new: 0, removed: 0) - sync message #107
```

If sync contains 1 room, only 1 room is processed. But if sync contains 10 rooms:
- Phase 6 runs 10 times
- Each time scanning timeline/events arrays
- Even if 9 of those rooms haven't changed

## Issue 4: Fallback Scanning

If no message found in `timeline`, code falls back to scanning `events` array:
- Scans timeline array (potentially many events) → no message found
- **Then** scans events array (potentially many events again)
- Double the work in worst case

## Issue 5: JSON Parsing Overhead

For each event scanned:
```kotlin
val timelineEntry = timeline.optJSONObject(i)  // Parse JSON
val eventJson = timelineEntry.optJSONObject("event")  // Parse nested JSON
val eventType = eventJson.optString("type")  // Extract string
val content = eventJson.optJSONObject("content")  // Parse content JSON
messagePreview = content.optString("body")  // Extract body
```

**Cost per event:**
- 2-3 JSON object parsings
- 3-4 string extractions
- Type checking

**With 588 rooms and frequent syncs:**
- Even if sync has 1 room with 10 timeline events
- That's 10 JSON parses just to find the last message
- Multiply by sync frequency (every few seconds) = lots of CPU

## Issue 6: Happens on Every Sync

This runs **on every sync_complete** message:
- Even when app is backgrounded (battery drain!)
- Even when room hasn't changed
- Even when user isn't looking at room list

**From your logs:**
```
sync message #107, #108, #109, #110... #170
```

That's 63 sync messages in ~2 minutes. If each sync has 1 room:
- Phase 6 runs 63 times
- 63 room summary updates
- Most likely for the same room (active room sending frequent updates)

## Why This Matters for Battery

### CPU Usage
- **JSON parsing** is CPU-intensive
- Scanning multiple events compounds the cost
- Happens repeatedly even when unnecessary

### Redundancy
- Work done in SpaceRoomParser is duplicated in SyncIngestor
- Both scan events to find last message
- Could be done once and reused

### Frequency
- Runs on every sync (every few seconds)
- Even when room hasn't changed
- Even when backgrounded (no UI to update!)

## Optimization Opportunities

### Quick Wins

1. **Skip Phase 6 when backgrounded:**
   - Room summary is mainly for UI (room list display)
   - When backgrounded, user isn't seeing room list
   - Can defer until app becomes visible

2. **Track last message during Phase 2/3:**
   - While processing timeline events, track the last message event
   - No need to scan again in Phase 6
   - Just store it as we process

3. **Only update if changed:**
   - Compare new summary with existing summary
   - Skip database write if unchanged
   - Reduces DB operations

4. **Use existing summary when no new messages:**
   - If timeline/events arrays are empty or have no messages
   - Keep existing summary (don't overwrite)
   - Skip scanning entirely

### Better Solution

5. **Reuse SpaceRoomParser result:**
   - SpaceRoomParser already finds last message
   - Pass that info to SyncIngestor
   - Don't duplicate the work

## Performance Impact

**Current cost per sync (with 1 room, 10 timeline events):**
- Phase 6: ~10 JSON parses + 1 database upsert
- **~5-10ms per room**

**If optimized:**
- Skip when backgrounded: **0ms** (100% reduction)
- Track during Phase 2/3: **~0.5ms** (95% reduction)
- **Battery savings: Significant when frequent syncs**

## Recommendation

**Priority 1:** Skip Phase 6 when app is backgrounded
- Biggest battery impact
- Minimal risk (summary updates when app opens)

**Priority 2:** Track last message during event processing
- Eliminates redundant scanning
- Reduces CPU usage

**Priority 3:** Only update if summary changed
- Reduces database writes
- Prevents unnecessary I/O

