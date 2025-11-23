# processRoom() Function Dissection

## Overview

`processRoom()` is called for **each room** present in a sync_complete message. It processes a single room's data and persists it to the database. This function handles multiple aspects of room data: metadata, events, reactions, receipts, and summaries.

## Function Signature

```kotlin
private suspend fun processRoom(
    roomId: String, 
    roomObj: JSONObject, 
    existingState: RoomStateEntity? = null
)
```

**Parameters:**
- `roomId`: The Matrix room ID (e.g., `"!abc123:matrix.org"`)
- `roomObj`: The room JSON object from sync_complete containing all room data
- `existingState`: Pre-loaded existing room state from database (null if room is new)

## Step-by-Step Breakdown

### Phase 1: Room State (Metadata) Processing (Lines 271-323)

**Purpose:** Update or create room metadata in `room_state` table.

**What it does:**
1. **Extracts `meta` object** from `roomObj` (contains name, topic, avatar, etc.)
2. **Detects Direct Message status:**
   - Checks for `dm_user_id` field in meta
   - Sets `isDirect = true` if present
3. **Loads existing state** if not provided:
   - Uses `existingState` parameter (from bulk load) if available
   - Falls back to `roomStateDao.get(roomId)` query if missing
4. **Preserves favorite/low-priority tags:**
   - Starts with existing values from database
   - Only updates if `account_data.m.tag` is present in sync
   - Preserves existing tags if sync doesn't include account_data
5. **Merges metadata fields:**
   - **name, topic, avatarUrl, canonicalAlias**: Use sync value if present, otherwise keep existing
   - **isFavourite, isLowPriority**: Use sync value if account_data.m.tag present, otherwise keep existing
6. **Creates/updates RoomStateEntity** and upserts to database

**Database operations:**
- 1 query if existingState not provided: `roomStateDao.get(roomId)`
- 1 upsert: `roomStateDao.upsert(roomState)`

---

### Phase 2: Timeline Events Processing (Lines 328-364)

**Purpose:** Persist timeline events (main message history) to `events` table.

**What it does:**
1. **Extracts `timeline` array** from `roomObj`
2. **Iterates through each timeline entry:**
   - Each entry has structure: `{ rowid: 123, event: { ... } }`
   - Extracts `rowid` (timeline position) and `event` JSON
3. **For each event:**
   - Calls `collectReactionPersistenceFromEvent()` to track reactions (see below)
   - Calls `parseEventFromJson()` to convert JSON → EventEntity
     - Validates event_id and type are present
     - Extracts sender, timestamp, content
     - Resolves timelineRowId (from sync, or lookup from DB if missing)
     - Handles encrypted messages (checks decrypted content)
     - Extracts thread relationships (threadRootEventId, relatesToEventId)
     - Handles redactions, edits, reactions
     - Serializes entire event JSON to `rawJson` field
4. **Bulk upserts all events:**
   - Collects all parsed events in a list
   - Calls `eventDao.upsertAll(events)` - single database operation

**Heavy operations:**
- **JSON parsing:** Every event JSON is parsed
- **Database lookups:** If timelineRowId missing, queries `eventDao.getEventById()` (can be many)
- **TimelineRowId preservation:** Uses cache (`existingTimelineRowCache`) to avoid repeated queries

**Database operations:**
- Multiple queries (worst case): `eventDao.getEventById()` for events without timelineRowId
- 1 bulk upsert: `eventDao.upsertAll(events)`

---

### Phase 3: Events Array Processing (Lines 366-418)

**Purpose:** Process additional events (preview events, state events) not in timeline.

**What it does:**
- **Same as Phase 2**, but processes `events` array instead of `timeline` array
- Events array contains preview/state events that may not have timeline positions
- Same processing: collect reactions, parse events, bulk upsert

**Why two arrays?**
- `timeline`: Events with known positions in timeline (ordered)
- `events`: Additional events without timeline positions (previews, state changes)

**Database operations:**
- Same as Phase 2 (potentially many queries + 1 bulk upsert)

---

### Phase 4: Reaction Processing (Lines 420-435)

**Purpose:** Persist reaction data to `reactions` table.

**What `collectReactionPersistenceFromEvent()` does:**
- **Scans all events** from timeline and events arrays
- **Detects reaction events** (`m.reaction` type):
  - Extracts target event ID (what was reacted to)
  - Extracts reaction key (emoji, e.g., "👍")
  - Tracks upserts and deletes
- **Detects redactions:**
  - If reaction event is redacted, marks for deletion
  - If event redacts a reaction, marks that reaction for deletion

**After scanning, applies changes:**
- Bulk delete: `reactionDao.deleteByEventIds(reactionDeletes)`
- Bulk upsert: `reactionDao.upsertAll(reactionUpserts.values)`

**Database operations:**
- 1 bulk delete (if reactions to delete)
- 1 bulk upsert (if reactions to add/update)

---

### Phase 5: Receipt Processing (Lines 437-469)

**Purpose:** Persist read receipts to `receipts` table.

**What it does:**
1. **Extracts `receipts` object** from `roomObj`
   - Structure: `{ "event_id": [{ "user_id": "@alice:example.com", "data": { "ts": 123456, "type": "m.read" } }] }`
2. **Iterates through receipt data:**
   - For each event ID that has receipts
   - For each user's receipt on that event
   - Extracts: userId, timestamp, receipt type
3. **Creates ReceiptEntity objects** and bulk upserts

**Database operations:**
- 1 bulk upsert: `receiptDao.upsertAll(receipts)`

---

### Phase 6: Room Summary Processing (Lines 471-535)

**Purpose:** Update `room_summary` table with latest room info for room list display.

**What it does:**
1. **Extracts unread counts** from meta:
   - `unread_messages`: Total unread count
   - `unread_highlights`: Highlighted/unread count
2. **Finds last message for preview:**
   - Scans `timeline` array backwards (newest first)
   - Looks for `m.room.message` or `m.room.encrypted` events
   - Extracts: eventId, timestamp, sender, message body (preview)
   - Falls back to `events` array if no message in timeline
3. **Creates RoomSummaryEntity** with:
   - `lastEventId`: ID of last message
   - `lastTimestamp`: Timestamp of last message
   - `messageSender`: Sender of last message (for display)
   - `messagePreview`: Body text of last message (for room list)
   - `unreadCount`: Unread message count
   - `highlightCount`: Highlighted unread count
4. **Upserts summary** to database

**Database operations:**
- 1 upsert: `roomSummaryDao.upsert(summary)`

---

## Complete Database Operations Summary

For **each room** processed, `processRoom()` performs:

1. **1 query** (if existingState not provided): `roomStateDao.get(roomId)`
2. **1 upsert**: `roomStateDao.upsert(roomState)` - Room metadata
3. **N queries** (worst case): `eventDao.getEventById()` for events without timelineRowId
4. **1 bulk upsert**: `eventDao.upsertAll(timelineEvents)` - Timeline events
5. **1 bulk upsert**: `eventDao.upsertAll(eventsArray)` - Additional events
6. **1 bulk delete**: `reactionDao.deleteByEventIds()` - Delete reactions (if any)
7. **1 bulk upsert**: `reactionDao.upsertAll()` - Upsert reactions (if any)
8. **1 bulk upsert**: `receiptDao.upsertAll()` - Receipts (if any)
9. **1 upsert**: `roomSummaryDao.upsert()` - Room summary

**Total: 2-3+N queries + 5-7 upserts per room**

Where N = number of events without timelineRowId (typically 0-10, but could be more)

## Performance Characteristics

### Heavy Operations (CPU/Memory)

1. **JSON parsing:** Every event JSON is parsed (timeline + events arrays)
2. **TimelineRowId lookups:** Database queries for events missing timelineRowId
3. **Summary extraction:** Scanning timeline backwards to find last message
4. **Reaction scanning:** Iterating through all events to find reactions

### Scalability Issues

**With 588 rooms and frequent syncs:**
- If each sync has 1-2 rooms: **Manageable** (~10-20 operations)
- If sync has many rooms: **Expensive** (e.g., 50 rooms × 9 operations = 450+ DB operations)

**Current optimizations:**
- ✅ Bulk upserts (not one-by-one)
- ✅ TimelineRowId cache to avoid repeated queries
- ✅ Existing state pre-loaded (avoid N queries for room state)

**Potential optimizations:**
- ⚠️ Skip processing if room has no changes (hard to detect)
- ⚠️ Defer summary updates when backgrounded
- ⚠️ Batch all operations into fewer transactions

## Data Flow

```
sync_complete JSON
    └─> rooms { roomId: roomObj }
            └─> processRoom(roomId, roomObj)
                    ├─> RoomStateEntity → room_state table
                    ├─> EventEntity[] → events table (timeline + events)
                    ├─> ReactionEntity[] → reactions table
                    ├─> ReceiptEntity[] → receipts table
                    └─> RoomSummaryEntity → room_summary table
```

## Critical Path for Consistency

All operations happen **within a single transaction** (called from `ingestSyncComplete()` line 245):
- If any step fails, entire room processing rolls back
- Ensures database consistency
- However, this means transaction locks DB longer

## When Room State Changes

**What triggers processing:**
- Any room in sync_complete message (even if unchanged)
- Rooms with new events
- Rooms with state changes (name, topic, etc.)
- Rooms with receipt updates
- Rooms with unread count changes

**What doesn't trigger (if not in sync):**
- Room not in sync message = not processed
- No database queries for unchanged rooms

## Questions for Optimization

1. **Can we skip processing unchanged rooms?**
   - Hard: Need to detect if room actually changed (compare hashes?)
   - Easy: When backgrounded, maybe skip summary updates?

2. **Can we defer expensive operations?**
   - Summary extraction (scanning timeline) - defer when backgrounded?
   - Reaction processing - batch across multiple syncs?

3. **Can we reduce database queries?**
   - TimelineRowId lookups are expensive - better caching?
   - Can we pre-load all event IDs in one query?

