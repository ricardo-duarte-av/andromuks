# Persistent Room Events Storage System

## Overview

The persistent room events storage system provides on-disk persistence for Matrix room events, room state, receipts, and metadata using Android Room Persistence Library (SQLite). This allows the app to:

- **Load app state instantly on startup** without waiting for WebSocket connection
- **Survive app restarts** with full room list and timeline data
- **Support incremental updates** using `last_received_id` and `run_id`
- **Enable offline-first functionality** with cached room data

The system stores all data in a SQLite database (`andromuks.db`) and integrates seamlessly with the in-memory `RoomTimelineCache` for fast access.

---

## Architecture

### Database Schema

The database consists of 7 main tables:

#### 1. `events` (EventEntity)
Stores individual timeline events.

**Fields:**
- `eventId` (String, Primary Key) - Matrix event ID
- `roomId` (String) - Room ID this event belongs to
- `timelineRowId` (Long) - Backend timeline row ID for ordering
- `timestamp` (Long) - Event timestamp (origin_server_ts in milliseconds)
- `type` (String) - Event type (e.g., "m.room.message", "m.room.encrypted")
- `sender` (String) - User ID who sent the event
- `decryptedType` (String?) - Decrypted event type for encrypted messages
- `relatesToEventId` (String?) - Event ID this relates to (for edits/reactions)
- `threadRootEventId` (String?) - Thread root event ID (for threaded messages)
- `isRedaction` (Boolean) - Whether this is a redaction event
- `rawJson` (String) - Complete event JSON (for future-proofing)

**Indices:**
- `(roomId, timelineRowId)` - For chronological ordering
- `(roomId, timestamp)` - For timestamp-based queries
- `(roomId)` - For room-specific queries

#### 2. `room_state` (RoomStateEntity)
Stores room metadata and state.

**Fields:**
- `roomId` (String, Primary Key)
- `name` (String?) - Room display name
- `topic` (String?) - Room topic
- `avatarUrl` (String?) - Room avatar MXC URL
- `canonicalAlias` (String?) - Room canonical alias (e.g., #room:server.com)
- `isDirect` (Boolean) - Whether this is a direct message room
- `isFavourite` (Boolean) - Whether room is favorited (m.favourite tag)
- `isLowPriority` (Boolean) - Whether room is low priority (m.lowpriority tag)
- `bridgeInfoJson` (String?) - Serialized BridgeInfo JSON (or "" if checked, no bridge)
- `updatedAt` (Long) - Last update timestamp

#### 3. `room_summary` (RoomSummaryEntity)
Stores summarized room information for room list display.

**Fields:**
- `roomId` (String, Primary Key)
- `lastEventId` (String?) - ID of the most recent message event
- `lastTimestamp` (Long) - Timestamp of most recent event (for sorting)
- `unreadCount` (Int) - Number of unread messages
- `highlightCount` (Int) - Number of highlighted (mention/keyword) messages
- `messageSender` (String?) - Sender of the last message
- `messagePreview` (String?) - Preview text of the last message

**Indices:**
- `lastTimestamp DESC` - For sorting rooms by most recent activity

#### 4. `receipts` (ReceiptEntity)
Stores read receipts (who read which message).

**Fields:**
- `userId` (String) - User who read the message
- `eventId` (String) - Event ID that was read
- `roomId` (String) - Room ID
- `timestamp` (Long) - When the receipt was created
- `type` (String) - Receipt type (typically "m.read")

#### 5. `sync_meta` (SyncMetaEntity)
Stores synchronization metadata.

**Fields:**
- `key` (String, Primary Key) - Metadata key
- `value` (String) - Metadata value

**Keys:**
- `"run_id"` - Current sync run ID (changes when backend restarts)
- `"last_received_id"` - Last request_id received from sync_complete
- `"since"` - Sync token from server (for incremental sync)

#### 6. `spaces` (SpaceEntity)
Stores space metadata.

**Fields:**
- `spaceId` (String, Primary Key) - Space room ID
- `name` (String?) - Space display name
- `avatarUrl` (String?) - Space avatar MXC URL

#### 7. `space_rooms` (SpaceRoomEntity)
Stores relationships between spaces and their child rooms/spaces.

**Fields:**
- `spaceId` (String) - Parent space ID
- `childId` (String) - Child room/space ID
- `canonical` (Boolean?) - Whether this is a canonical relationship
- `suggested` (Boolean?) - Whether this is a suggested relationship
- `parentEventRowid` (Long?) - Event row ID that created this relationship
- `childEventRowid` (Long?) - Child event row ID

---

## Key Components

### 1. AndromuksDatabase

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/AndromuksDatabase.kt`

**Purpose:** Room database singleton that provides access to all DAOs.

**Key Methods:**
- `getInstance(context: Context): AndromuksDatabase` - Get singleton database instance
- `vacuum()` - Compact database to reclaim space after deletions

**Database Configuration:**
- **Database Name:** `andromuks.db`
- **Version:** 3
- **Migration Strategy:** `fallbackToDestructiveMigration()` (data cleared on schema changes)

**DAOs Provided:**
- `eventDao()` - EventEntity operations
- `roomStateDao()` - RoomStateEntity operations
- `roomSummaryDao()` - RoomSummaryEntity operations
- `receiptDao()` - ReceiptEntity operations
- `syncMetaDao()` - SyncMetaEntity operations
- `spaceDao()` - SpaceEntity operations
- `spaceRoomDao()` - SpaceRoomEntity operations

---

### 2. SyncIngestor

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt`

**Purpose:** Parses and persists `sync_complete` JSON messages into the database.

#### Key Methods

##### `ingestSyncComplete(syncJson: JSONObject, requestId: Int, runId: String): Unit`
Main entry point for persisting sync data.

**Flow:**
1. Check if `run_id` has changed → clear all data if changed
2. Extract `since` token from sync data
3. Store sync metadata (`last_received_id`, `since`)
4. Process spaces (`top_level_spaces`, `space_edges`)
5. Process all rooms in a single transaction:
   - Room state (meta)
   - Timeline events
   - Events array
   - Receipts
   - Room summary

**Parameters:**
- `syncJson`: Complete sync_complete JSON object (must have `data` field)
- `requestId`: Request ID from sync_complete (stored as `last_received_id`)
- `runId`: Current run ID (validated against stored run_id)

**Transaction Safety:** All room processing happens in a single database transaction for consistency.

##### `checkAndHandleRunIdChange(newRunId: String): Boolean`
Validates and handles run_id changes.

**Behavior:**
- If stored run_id is empty → First time, store it
- If stored run_id matches new run_id → Continue normally
- If stored run_id differs → Clear all data, store new run_id

**Returns:** `true` if run_id changed (data was cleared), `false` otherwise

##### `clearAllData(): Unit`
Clears all persisted data (called when run_id changes).

**Clears:**
- All events
- All room states
- All room summaries
- All receipts
- All spaces and space-room relationships
- Sync metadata (except run_id)

##### `processRoom(roomId: String, roomObj: JSONObject): Unit`
Processes a single room from sync_complete.

**Processes:**
1. **Room State (meta):**
   - Extracts name, topic, avatar, canonical alias
   - Detects direct messages (from `meta.dm_user_id`)
   - Extracts tags (isFavourite, isLowPriority) from `account_data.m.tag`
   - **CRITICAL:** Preserves existing values if not present in sync (prevents overwriting with nulls)

2. **Timeline Events:**
   - Parses `timeline` array
   - Extracts `rowid` from timeline entries
   - Creates `EventEntity` for each event
   - Upserts to database

3. **Events Array:**
   - Parses `events` array (additional events)
   - Uses `timeline_rowid` from event JSON if available
   - Upserts to database

4. **Receipts:**
   - Parses `receipts` object
   - Creates `ReceiptEntity` for each receipt
   - Upserts to database

5. **Room Summary:**
   - Extracts unread counts from meta
   - Finds last message event (from timeline or events array)
   - Creates/updates `RoomSummaryEntity`

##### `processSpaces(data: JSONObject): Unit`
Processes space metadata from sync_complete.

**Handles:**
- `top_level_spaces`: Complete replacement of all spaces
- `space_edges`: Complete replacement of relationships for specific spaces

**Strategy:** Complete replacement (delete all, then insert new) when keys are present.

##### `parseEventFromJson(roomId: String, eventJson: JSONObject, timelineRowid: Long): EventEntity?`
Parses a single event JSON into `EventEntity`.

**Key Features:**
- Extracts `origin_server_ts` for timestamp
- Handles encrypted events (extracts `decrypted_type`)
- Extracts thread relationships (`m.in_reply_to`, `m.thread_relation`)
- Handles redactions (extracts `redacts` field)
- Stores complete raw JSON for future-proofing

##### `persistPaginatedEvents(roomId: String, events: List<TimelineEvent>): Unit`
Persists events from paginate responses.

**Usage:** Called when events are fetched via `paginate` command (not from sync_complete).

**Flow:**
1. Converts `TimelineEvent` to `EventEntity`
2. Upserts to database (handles duplicates automatically)

##### `persistBridgeInfo(roomId: String, bridgeInfo: BridgeInfo): Unit`
Persists bridge information for a room.

**Usage:** Called when bridge info is loaded from `m.bridge` state event.

**Storage:** Serializes `BridgeInfo` to JSON and stores in `RoomStateEntity.bridgeInfoJson`.

**Note:** Empty string `""` means "checked, no bridge" (prevents re-requesting).

---

### 3. BootstrapLoader

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/BootstrapLoader.kt`

**Purpose:** Loads initial app state from database on startup.

#### Key Methods

##### `loadBootstrap(): BootstrapResult`
Loads all bootstrap data from database.

**Flow:**
1. Validates `run_id` exists (returns empty result if missing)
2. Loads sync metadata (`run_id`, `last_received_id`, `since`)
3. Loads ALL room summaries (not just top 200)
4. Loads ALL room states
5. Builds `RoomItem` list from summaries + states
6. Pre-loads events for top 10 rooms into `RoomTimelineCache`
7. Returns `BootstrapResult` with all loaded data

**BootstrapResult:**
```kotlin
data class BootstrapResult(
    val rooms: List<RoomItem>,
    val runId: String,
    val lastReceivedId: Int,
    val sinceToken: String,
    val isValid: Boolean
)
```

**Important:** If `lastTimestamp` is 0, queries `EventDao.getLastEventTimestamp()` as fallback.

##### `loadRoomEvents(roomId: String, limit: Int = 100): List<TimelineEvent>`
Loads events for a specific room from database.

**Usage:** Called when opening a room and memory cache is empty.

**Returns:** List of `TimelineEvent` objects (converted from `EventEntity`)

**Order:** Descending (newest first), limited to `limit` events

##### `getStoredRunId(): String`
Returns stored run_id from database.

**Usage:** For validation before connecting WebSocket.

##### `loadBridgeInfoFromDb(): Map<String, String>`
Loads bridge info for all rooms from database.

**Returns:** Map of `roomId -> bridgeInfoJson`

**Note:** Includes rooms with empty string `""` (checked, no bridge).

##### `getBridgeCheckedRooms(): Set<String>`
Returns set of room IDs that have been checked for bridge info.

**Usage:** Prevents re-requesting `m.bridge` for already-checked rooms.

##### `loadSpacesFromDb(roomMap: Map<String, RoomItem>): List<SpaceItem>`
Loads spaces and their relationships from database.

**Flow:**
1. Loads all `SpaceEntity` objects
2. Loads all `SpaceRoomEntity` relationships
3. Groups relationships by space ID
4. Reconstructs `SpaceItem` objects with child rooms

**Returns:** List of `SpaceItem` objects with populated `rooms` list

---

### 4. Data Access Objects (DAOs)

#### EventDao

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/dao/EventDao.kt`

**Key Methods:**
- `upsertAll(events: List<EventEntity>)` - Upsert multiple events (REPLACE on conflict)
- `upsert(event: EventEntity)` - Upsert single event
- `getEventsForRoomAsc(roomId, limit)` - Get events ascending (oldest first)
- `getEventsForRoomDesc(roomId, limit)` - Get events descending (newest first)
- `deleteEventsOlderThan(cutoffTimestamp)` - Delete events older than timestamp (TTL)
- `deleteAll()` - Delete all events
- `deleteAllForRoom(roomId)` - Delete all events for a room (full refresh)
- `getEventCountForRoom(roomId)` - Count events for a room
- `getTotalSizeForRoom(roomId)` - Get total disk size (sum of rawJson lengths)
- `getLastEventTimestamp(roomId)` - Get most recent event timestamp for a room

**Ordering:** Events are ordered by `(timelineRowId, timestamp)` for proper chronological ordering.

#### RoomStateDao

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/dao/RoomStateDao.kt`

**Key Methods:**
- `upsert(state: RoomStateEntity)` - Upsert room state
- `upsertAll(states: List<RoomStateEntity>)` - Upsert multiple states
- `get(roomId: String)` - Get room state for a room
- `getAllRoomStates()` - Get all room states
- `deleteAll()` - Delete all room states

#### RoomSummaryDao

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/dao/RoomSummaryDao.kt`

**Key Methods:**
- `upsert(summary: RoomSummaryEntity)` - Upsert room summary
- `getAllRooms()` - Get all room summaries (sorted by lastTimestamp DESC)
- `getTopRooms(limit)` - Get top N rooms (by lastTimestamp DESC)
- `deleteAll()` - Delete all summaries
- `deleteOrphanedSummaries()` - Delete summaries for non-existent rooms

#### ReceiptDao

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/dao/ReceiptDao.kt`

**Key Methods:**
- `upsertAll(receipts: List<ReceiptEntity>)` - Upsert multiple receipts
- `deleteAll()` - Delete all receipts
- `deleteOrphanedReceipts()` - Delete receipts for non-existent events

#### SyncMetaDao

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/dao/SyncMetaDao.kt`

**Key Methods:**
- `upsert(meta: SyncMetaEntity)` - Upsert metadata entry
- `get(key: String)` - Get metadata value by key
- `deleteAll()` - Delete all metadata

#### SpaceDao

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/dao/SpaceDao.kt`

**Key Methods:**
- `upsertAll(spaces: List<SpaceEntity>)` - Upsert multiple spaces
- `getAllSpaces()` - Get all spaces
- `deleteAllSpaces()` - Delete all spaces (complete replacement)

#### SpaceRoomDao

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/dao/SpaceRoomDao.kt`

**Key Methods:**
- `upsertAll(spaceRooms: List<SpaceRoomEntity>)` - Upsert multiple relationships
- `getAllRoomsForAllSpaces()` - Get all space-room relationships
- `deleteRoomsForSpace(spaceId)` - Delete relationships for a specific space
- `deleteAllSpaceRooms()` - Delete all relationships
- `deleteOrphanedSpaceRooms()` - Delete relationships for non-existent spaces/rooms

---

## Data Flow

### 1. App Startup Flow

```
App Startup
    ↓
AppViewModel.loadStateFromStorage()
    ↓
BootstrapLoader.loadBootstrap()
    ↓
    ├─ Check run_id exists
    ├─ Load sync metadata (run_id, last_received_id, since)
    ├─ Load ALL room summaries
    ├─ Load ALL room states
    ├─ Build RoomItem list
    ├─ Pre-load events for top 10 rooms → RoomTimelineCache
    └─ Return BootstrapResult
    ↓
AppViewModel.populateStateFromBootstrap()
    ↓
    ├─ Set currentRunId, lastReceivedSyncId, vapidKey
    ├─ Populate roomMap from loaded rooms
    ├─ Update allRooms list
    ├─ Load bridge info from DB → bridgeInfoCache
    ├─ Load spaces from DB → allSpaces, spaceList
    ├─ Update cached room sections (tabs)
    └─ Update badge counts
    ↓
WebSocket Connection
    ├─ Pass run_id and last_received_id
    └─ Receive incremental sync_complete messages
```

### 2. Sync Complete Ingestion Flow

```
WebSocket receives sync_complete
    ↓
AppViewModel.updateRoomsFromSyncJsonAsync()
    ↓
SyncIngestor.ingestSyncComplete()
    ↓
    ├─ checkAndHandleRunIdChange()
    │   └─ If run_id changed → clearAllData()
    │
    ├─ Extract since token
    ├─ Store sync metadata (last_received_id, since)
    │
    ├─ processSpaces(data)
    │   ├─ If top_level_spaces present → deleteAllSpaces(), upsert new
    │   └─ If space_edges present → deleteRoomsForSpace(), upsert new
    │
    └─ For each room in sync_complete:
        └─ processRoom(roomId, roomObj)
            ├─ Process room state (meta)
            │   ├─ Extract name, topic, avatar, alias
            │   ├─ Detect isDirect (from dm_user_id)
            │   ├─ Extract tags (isFavourite, isLowPriority)
            │   └─ Preserve existing values if not present
            │
            ├─ Process timeline events
            │   ├─ Parse timeline array
            │   ├─ Extract rowid from timeline entries
            │   └─ Upsert EventEntity objects
            │
            ├─ Process events array
            │   ├─ Parse events array
            │   └─ Upsert EventEntity objects
            │
            ├─ Process receipts
            │   ├─ Parse receipts object
            │   └─ Upsert ReceiptEntity objects
            │
            └─ Update room summary
                ├─ Extract unread counts
                ├─ Find last message event
                └─ Upsert RoomSummaryEntity
```

### 3. Room Opening Flow

```
User opens room
    ↓
AppViewModel.requestRoomTimeline(roomId)
    ↓
    ├─ Store room open timestamp (for animation filtering)
    │
    ├─ Check RoomTimelineCache (memory)
    │   └─ If hit → processCachedEvents() → Display immediately
    │
    ├─ If cache miss → Load from database
    │   └─ BootstrapLoader.loadRoomEvents(roomId, 100)
    │       ├─ Query EventDao.getEventsForRoomDesc(roomId, 100)
    │       ├─ Convert EventEntity → TimelineEvent
    │       └─ Seed RoomTimelineCache with loaded events
    │
    └─ If still < 100 events in RAM after DB load:
        └─ Request paginate(200) from backend
            └─ On response → persistPaginatedEvents()
                └─ SyncIngestor.persistPaginatedEvents()
```

### 4. Pagination Flow

```
User scrolls up (or auto-pagination triggers)
    ↓
AppViewModel.loadOlderMessages(roomId)
    ↓
Send paginate command to backend
    ↓
Backend responds with events
    ↓
AppViewModel.handlePaginationMerge()
    ↓
    ├─ Merge events into timeline
    ├─ Update RoomTimelineCache
    └─ SyncIngestor.persistPaginatedEvents(roomId, events)
        └─ Convert TimelineEvent → EventEntity
        └─ Upsert to database
```

### 5. Full Refresh Flow

```
User clicks refresh button
    ↓
AppViewModel.fullRefreshRoomTimeline(roomId)
    ↓
    ├─ EventDao.deleteAllForRoom(roomId)  // Delete from DB
    ├─ RoomTimelineCache.clearRoomCache(roomId)  // Clear RAM
    ├─ Clear timeline state (eventChainMap, etc.)
    ├─ Reset pagination state
    └─ Request paginate(200) from backend
        └─ On response → persistPaginatedEvents()
```

---

## Important Concepts

### Run ID

**Purpose:** Identifies the current sync session. If the backend restarts, the run_id changes, indicating all local data is stale.

**Storage:** `sync_meta` table, key `"run_id"`

**Behavior:**
- **First sync:** Store run_id
- **Subsequent syncs:** Compare with stored run_id
  - **Match:** Continue normally
  - **Mismatch:** Clear all data (events, rooms, receipts, spaces), store new run_id

**Why:** Backend restart means event ordering/changes may have occurred, so local data cannot be trusted.

### Last Received ID

**Purpose:** Tracks the last `request_id` received from `sync_complete`. Used to resume incremental sync after reconnection.

**Storage:** `sync_meta` table, key `"last_received_id"`

**Usage:** Passed to WebSocket on reconnection to receive only new events since last sync.

### Since Token

**Purpose:** Server-side sync token for incremental sync. More reliable than `request_id` for resuming sync.

**Storage:** `sync_meta` table, key `"since"`

**Usage:** Passed to backend for incremental sync (if available).

### Timestamp-Based Animation Filtering

**Purpose:** Prevents animations for old messages (paginated messages, initial load).

**Storage:** `roomOpenTimestamps` map in `AppViewModel` (in-memory)

**Flow:**
1. When room opens → Store `System.currentTimeMillis()` as room open timestamp
2. When new message arrives → Check `message.timestamp > roomOpenTimestamp`
3. Only animate if message is newer than room open timestamp

**Result:**
- ✅ Initial load messages: No animation (timestamp < roomOpenTimestamp)
- ✅ Paginated messages: No animation (timestamp < roomOpenTimestamp)
- ✅ New messages: Animated (timestamp > roomOpenTimestamp)

### Event Ordering

Events are ordered by `(timelineRowId, timestamp)` for proper chronological ordering.

**Why:**
- `timelineRowId` is the backend's internal ordering (handles edge cases)
- `timestamp` is used as tiebreaker and for queries

**Queries:**
- `getEventsForRoomDesc`: `ORDER BY timelineRowId DESC, timestamp DESC` (newest first)
- `getEventsForRoomAsc`: `ORDER BY timelineRowId ASC, timestamp ASC` (oldest first)

### Raw JSON Storage

All events store their complete raw JSON in `rawJson` field.

**Benefits:**
- Future-proofing (handles schema changes)
- Can reconstruct `TimelineEvent` from stored JSON
- No data loss if event structure changes

**Conversion:**
- **Store:** `EventEntity(rawJson = eventJson.toString())`
- **Load:** `TimelineEvent.fromJson(JSONObject(entity.rawJson))`

### Tag Preservation

Room tags (`isFavourite`, `isLowPriority`) are preserved when not present in sync.

**Problem:** If `account_data` is missing in sync_complete, tags would be overwritten with `false`.

**Solution:**
1. Load existing `RoomStateEntity` before processing
2. Initialize tags from existing state
3. Only update tags if `account_data.m.tag` is explicitly present in sync
4. Preserve existing values if not present

**Result:** Tags persist across syncs that don't include `account_data`.

### Bridge Info Persistence

Bridge information is stored to prevent re-requesting `m.bridge` state events.

**Storage:** `RoomStateEntity.bridgeInfoJson` (JSON string)

**Values:**
- `null` = Not checked yet
- `""` (empty string) = Checked, no bridge found
- `"{...}"` = Bridge info JSON

**Loading:** `BootstrapLoader.loadBridgeInfoFromDb()` loads all bridge info on startup.

---

## Database Maintenance

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/DatabaseMaintenance.kt`

**Scheduled:** Daily at 2 AM via `DatabaseMaintenanceWorker` (WorkManager)

**Operations:**
1. **TTL Cleanup:** Delete events older than 1 year
2. **Orphaned Receipts:** Delete receipts for non-existent events
3. **Orphaned Summaries:** Delete summaries for non-existent rooms
4. **Orphaned Space Rooms:** Delete space-room relationships for non-existent spaces/rooms
5. **Vacuum:** Compact database to reclaim space

**Result:** Returns `MaintenanceResult` with statistics.

---

## Integration Points

### AppViewModel Integration

**Initialization:**
```kotlin
private var syncIngestor: SyncIngestor? = null
private var bootstrapLoader: BootstrapLoader? = null
```

**On Startup:**
```kotlin
loadStateFromStorage(context: Context): Boolean
    └─ bootstrapLoader = BootstrapLoader(context)
    └─ bootstrapLoader.loadBootstrap()
        └─ Populate AppViewModel state from BootstrapResult
```

**On Sync Complete:**
```kotlin
updateRoomsFromSyncJsonAsync(syncJson: JSONObject)
    └─ syncIngestor = SyncIngestor(context)
    └─ syncIngestor.ingestSyncComplete(syncJson, requestId, runId)
```

**On Room Open:**
```kotlin
requestRoomTimeline(roomId: String)
    └─ If cache miss → bootstrapLoader.loadRoomEvents(roomId, 100)
    └─ If < 100 events → Request paginate(200)
        └─ On response → syncIngestor.persistPaginatedEvents()
```

**On Pagination:**
```kotlin
handlePaginationMerge()
handleInitialTimelineBuild()
handleBackgroundPrefetch()
    └─ syncIngestor.persistPaginatedEvents(roomId, events)
```

**On Bridge Info Load:**
```kotlin
updateBridgeInfo(roomId: String, bridgeInfo: BridgeInfo)
    └─ syncIngestor.persistBridgeInfo(roomId, bridgeInfo)
```

**On Full Refresh:**
```kotlin
fullRefreshRoomTimeline(roomId: String)
    └─ EventDao.deleteAllForRoom(roomId)
    └─ Clear RAM cache
    └─ Request paginate(200)
```

### RoomTimelineCache Integration

**Pre-loading:**
- `BootstrapLoader.loadBootstrap()` pre-loads top 10 rooms into `RoomTimelineCache`
- Uses `RoomTimelineCache.seedCacheWithPaginatedEvents()`

**On Room Open:**
- If cache hit → Use cached events (no DB query)
- If cache miss → Load from DB → Seed cache → Display

**After Pagination:**
- Events are added to cache AND persisted to DB

---

## Error Handling

### Run ID Mismatch
- **Detection:** `checkAndHandleRunIdChange()` compares stored vs new run_id
- **Action:** Clear all data, store new run_id
- **Result:** Fresh start with new sync session

### Database Errors
- **Transaction Safety:** All multi-step operations use `database.withTransaction()`
- **Upsert Strategy:** `OnConflictStrategy.REPLACE` prevents duplicates
- **Fallback:** If DB load fails, fall back to SharedPreferences (legacy)

### Missing Data
- **Room State:** Preserves existing values if not in sync
- **Timestamps:** Falls back to `EventDao.getLastEventTimestamp()` if summary timestamp is 0
- **Bridge Info:** Empty string `""` indicates "checked, no bridge" (prevents re-requesting)

---

## Performance Considerations

### Indexing
- Events table has indices on `(roomId, timelineRowId)` and `(roomId, timestamp)`
- Room summary table has index on `lastTimestamp DESC` for sorting

### Transaction Batching
- All rooms in a sync_complete are processed in a single transaction
- Prevents partial updates if sync is interrupted

### Pre-loading
- Top 10 rooms are pre-loaded into `RoomTimelineCache` on startup
- Enables instant room opening for frequently accessed rooms

### Async Operations
- All database operations use `Dispatchers.IO`
- UI is never blocked by database queries

### Pagination Limits
- Initial load: 100 events from DB
- Pagination: 200 events per request
- Prevents loading too much data at once

---

## Testing

**Location:** `app/src/androidTest/java/net/vrkknn/andromuks/database/`

**Test Files:**
- `EventDaoTest.kt` - Event DAO operations
- `SyncIngestorTest.kt` - Sync ingestion logic
- `BootstrapLoaderTest.kt` - Bootstrap loading
- `PaginationPersistenceTest.kt` - Pagination persistence
- `DatabasePerformanceTest.kt` - Performance benchmarks

**Test Utilities:**
- `DatabaseTestUtils.kt` - Helper functions for creating test data

**Important:** Tests use the singleton database and call `deleteAll()` on all DAOs in `setUp()` and `tearDown()` to ensure isolation.

---

## Future Enhancements

### Potential Improvements
1. **Migration Support:** Add proper Room migrations instead of `fallbackToDestructiveMigration()`
2. **Full-Text Search:** Add FTS for searching messages
3. **Compression:** Compress `rawJson` for large events
4. **Incremental Sync Optimization:** Use `since` token for more efficient sync
5. **Room-Specific TTL:** Different retention periods per room
6. **Background Sync:** Sync while app is in background

---

## Summary

The persistent room events storage system provides robust, transaction-safe persistence for all room data. It enables:

- ✅ Instant app startup with cached data
- ✅ Offline-first functionality
- ✅ Incremental sync with run_id validation
- ✅ Complete room state persistence (including tags, bridge info, spaces)
- ✅ Automatic maintenance (TTL, orphan cleanup, compaction)
- ✅ Thread-safe operations with Room transactions
- ✅ Future-proof storage (raw JSON)

The system integrates seamlessly with the in-memory cache and provides a fallback when cache is empty, ensuring smooth user experience even after app restarts.

