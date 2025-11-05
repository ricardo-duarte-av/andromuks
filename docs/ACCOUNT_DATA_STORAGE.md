# Account Data Storage System

## Overview

Account data is user-specific configuration and settings that are stored globally (not per-room) and synchronized via Matrix sync. This includes direct message mappings (`m.direct`), recently used emojis (`io.element.recent_emoji`), push notification rules (`m.push_rules`), and various other client-specific settings.

The account data storage system provides persistent, on-disk storage for account data with intelligent merging of partial updates.

---

## Architecture

### Database Schema

Account data is stored in a single-row table:

**Table:** `account_data`

| Column | Type | Description |
|--------|------|-------------|
| `key` | String (PRIMARY KEY) | Always `"account_data"` (single row) |
| `accountDataJson` | String | Complete account_data JSON object as string |

**Entity:** `AccountDataEntity`

```kotlin
@Entity(tableName = "account_data")
data class AccountDataEntity(
    @PrimaryKey val key: String = "account_data",
    val accountDataJson: String // Full account_data JSON object as string
)
```

### Key Design Decisions

1. **Single Row Storage:** Account data is stored as a single JSON object, not normalized into separate tables. This simplifies merging and matches Matrix's structure.
2. **JSON Storage:** The entire account_data object is stored as a JSON string, preserving all fields and allowing easy merging.
3. **Partial Update Support:** Only keys present in incoming sync are updated, preserving other keys.

---

## Data Flow

### 1. Ingesting Account Data (SyncIngestor)

When a `sync_complete` message arrives with `account_data`:

```
sync_complete → SyncIngestor.ingestSyncComplete()
                ↓
        1. Load existing account_data from DB
        2. Merge incoming keys with existing
        3. Store merged result
```

**Merge Logic:**
- Load existing `account_data` JSON from database
- Parse both existing and incoming as JSON objects
- Copy all keys from existing
- Overwrite with keys from incoming (incoming keys replace existing)
- Store merged result

**Example:**
```json
// Existing in DB:
{
  "m.direct": {...},
  "io.element.recent_emoji": {"content": {"recent_emoji": [["😀", 5]]}},
  "m.push_rules": {...}
}

// Incoming sync (partial update):
{
  "io.element.recent_emoji": {"content": {"recent_emoji": [["😍", 1]]}}
}

// Merged result (stored to DB):
{
  "m.direct": {...},                    // Preserved
  "io.element.recent_emoji": {...},     // Updated (replaced)
  "m.push_rules": {...}                 // Preserved
}
```

### 2. Loading Account Data (BootstrapLoader)

On app startup:

```
App startup → BootstrapLoader.loadBootstrap()
              ↓
      1. Load account_data JSON from DB
      2. Return in BootstrapResult
      3. AppViewModel.processAccountData() processes it
```

**Loading Process:**
- `BootstrapLoader.loadBootstrap()` queries `accountDataDao.getAccountData()`
- Returns `accountDataJson` in `BootstrapResult`
- `AppViewModel.loadStateFromStorage()` calls `processAccountData()` with loaded JSON

### 3. Processing Account Data (AppViewModel)

`processAccountData()` extracts and processes specific fields:

```kotlin
private fun processAccountData(accountDataJson: JSONObject) {
    // 1. Process io.element.recent_emoji
    val recentEmojiData = accountDataJson.optJSONObject("io.element.recent_emoji")
    // ... extract and sort emojis by frequency
    
    // 2. Process m.direct
    val mDirectData = accountDataJson.optJSONObject("m.direct")
    // ... extract DM room IDs and update directMessageRoomIds cache
}
```

---

## Partial Updates

### Matrix Behavior

Matrix clients receive **partial updates** to account_data. Only keys that have changed are included in the sync message.

**Full Account Data Example:**
```json
{
  "m.direct": {
    "content": {
      "@alice:example.com": ["!room1:example.com"]
    }
  },
  "io.element.recent_emoji": {
    "content": {
      "recent_emoji": [["😀", 5], ["😍", 3]]
    }
  },
  "m.push_rules": {...},
  "fi.mau.gomuks.preferences": {...}
}
```

**Partial Update Example:**
```json
{
  "io.element.recent_emoji": {
    "content": {
      "recent_emoji": [["😀", 6], ["😍", 4]]  // Only this key updated
    }
  }
}
```

### Merge Implementation

The merge logic in `SyncIngestor.ingestSyncComplete()`:

```kotlin
// Load existing account_data from database
val existingAccountDataStr = accountDataDao.getAccountData()
val mergedAccountData = if (existingAccountDataStr != null) {
    // Merge: existing + incoming (incoming keys replace existing keys)
    val existingAccountData = JSONObject(existingAccountDataStr)
    val merged = JSONObject(existingAccountData.toString())
    
    // Overwrite with incoming keys
    val incomingKeys = incomingAccountData.keys()
    while (incomingKeys.hasNext()) {
        val key = incomingKeys.next()
        merged.put(key, incomingAccountData.get(key))
    }
    
    merged
} else {
    // No existing data, use incoming as-is
    incomingAccountData
}
```

**Key Points:**
- **Preserves existing keys** not present in incoming sync
- **Replaces entire key** if present in incoming sync (not deep merge)
- **Handles first-time** storage (no existing data)

---

## Processed Fields

### Currently Processed

#### 1. `io.element.recent_emoji`

**Purpose:** Recently used emojis with frequency counts

**Structure:**
```json
{
  "io.element.recent_emoji": {
    "type": "io.element.recent_emoji",
    "content": {
      "recent_emoji": [
        ["😀", 5],  // [emoji, frequency]
        ["😍", 3]
      ]
    }
  }
}
```

**Processing:**
- Extracts emoji-frequency pairs
- Sorts by frequency (descending)
- Updates `recentEmojiFrequencies` and `recentEmojis` in `AppViewModel`

**Usage:** Emoji picker shows recently used emojis first

#### 2. `m.direct`

**Purpose:** Direct message room mappings (user ID → room IDs)

**Structure:**
```json
{
  "m.direct": {
    "type": "m.direct",
    "content": {
      "@alice:example.com": ["!room1:example.com"],
      "@bob:example.com": ["!room2:example.com", "!room3:example.com"]
    }
  }
}
```

**Processing:**
- Extracts all room IDs from all user mappings
- Updates `directMessageRoomIds` set in `AppViewModel`
- Used to mark rooms as direct messages in UI

**Usage:** "Direct" tab in room list, DM room detection

### Other Fields (Stored but Not Processed)

The following fields are stored in the database but not currently processed by `AppViewModel`:

- `m.push_rules` - Push notification rules
- `fi.mau.gomuks.preferences` - gomuks-specific preferences
- `im.vector.setting.breadcrumbs` - Recent rooms list
- `m.cross_signing.*` - Cross-signing keys
- `m.secret_storage.*` - Secret storage keys
- `m.ignored_user_list` - Ignored users
- Various Element/Vector-specific settings

**Future Enhancement:** These fields can be processed as needed by adding handlers in `processAccountData()`.

---

## Database Operations

### DAO Methods

**AccountDataDao:**
```kotlin
@Dao
interface AccountDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(accountData: AccountDataEntity)
    
    @Query("SELECT accountDataJson FROM account_data WHERE key = 'account_data' LIMIT 1")
    suspend fun getAccountData(): String?
    
    @Query("DELETE FROM account_data")
    suspend fun deleteAll()
}
```

### Transaction Safety

Account data updates are **not** wrapped in a transaction with other sync data. This is intentional:

- Account data updates are independent
- Failure to merge account_data shouldn't block room/event processing
- Partial failures are handled gracefully (fallback to incoming-only)

### Run ID Handling

When `run_id` changes (indicating a fresh sync):

- `SyncIngestor.clearAllData()` calls `accountDataDao.deleteAll()`
- All account_data is cleared
- New account_data will be stored from the next sync

**Rationale:** If run_id changes, the server state has changed significantly, so local account_data may be stale.

---

## Integration Points

### 1. SyncIngestor

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt`

**Method:** `ingestSyncComplete()`

**Responsibilities:**
- Extract `account_data` from sync_complete
- Merge with existing account_data
- Persist to database

**Called from:** `AppViewModel.updateRoomsFromSyncJsonAsync()`

### 2. BootstrapLoader

**Location:** `app/src/main/java/net/vrkknn/andromuks/database/BootstrapLoader.kt`

**Methods:**
- `loadBootstrap()` - Loads account_data as part of bootstrap
- `loadAccountData()` - Standalone method to load account_data

**Responsibilities:**
- Load account_data JSON from database
- Return in `BootstrapResult` for app startup

**Called from:** `AppViewModel.loadStateFromStorage()`

### 3. AppViewModel

**Location:** `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

**Methods:**
- `processAccountData(accountDataJson: JSONObject)` - Processes account_data fields
- `loadStateFromStorage()` - Loads and processes account_data on startup
- `updateRoomsFromSyncJsonAsync()` - Processes account_data from sync messages

**Responsibilities:**
- Extract and process specific account_data fields
- Update in-memory caches (`recentEmojis`, `directMessageRoomIds`)
- Trigger UI updates

---

## Error Handling

### Merge Failures

If merging account_data fails:

1. **Log error** with full exception details
2. **Fallback:** Store incoming account_data as-is (replaces existing)
3. **Continue processing** (doesn't block other sync data)

**Rationale:** Better to have partial account_data than none at all.

### JSON Parsing Errors

If account_data JSON cannot be parsed:

- `processAccountData()` catches exceptions and logs them
- Existing caches remain unchanged
- App continues to function (graceful degradation)

### Database Errors

If database operations fail:

- Exceptions are caught and logged
- Account_data processing is skipped
- Other bootstrap data continues to load

---

## Example Scenarios

### Scenario 1: First Sync (No Existing Data)

**Input:**
```json
{
  "account_data": {
    "m.direct": {...},
    "io.element.recent_emoji": {...}
  }
}
```

**Process:**
1. No existing account_data in DB
2. Store incoming as-is
3. Process `m.direct` and `io.element.recent_emoji`

**Result:** Account_data stored and processed

### Scenario 2: Partial Update (Existing Data Present)

**Existing in DB:**
```json
{
  "m.direct": {...},
  "io.element.recent_emoji": {"content": {"recent_emoji": [["😀", 5]]}},
  "m.push_rules": {...}
}
```

**Incoming Sync:**
```json
{
  "account_data": {
    "io.element.recent_emoji": {"content": {"recent_emoji": [["😀", 6], ["😍", 1]]}}
  }
}
```

**Process:**
1. Load existing account_data
2. Merge: preserve `m.direct` and `m.push_rules`, replace `io.element.recent_emoji`
3. Store merged result
4. Process updated `io.element.recent_emoji`

**Result:** `io.element.recent_emoji` updated, other fields preserved

### Scenario 3: Run ID Change

**Process:**
1. `SyncIngestor.checkAndHandleRunIdChange()` detects run_id change
2. `clearAllData()` calls `accountDataDao.deleteAll()`
3. Account_data cleared from database
4. Next sync will store fresh account_data

**Result:** Fresh account_data from server

### Scenario 4: App Startup (Cold Start)

**Process:**
1. `AppViewModel.loadStateFromStorage()` called
2. `BootstrapLoader.loadBootstrap()` loads account_data from DB
3. `processAccountData()` extracts `m.direct` and `io.element.recent_emoji`
4. `directMessageRoomIds` and `recentEmojis` populated
5. UI shows correct DM rooms and recent emojis

**Result:** Account_data loaded and processed before WebSocket connection

---

## Performance Considerations

### Storage Size

Account data can be large (especially `m.push_rules` with many rules). The JSON string is stored efficiently in SQLite as TEXT.

**Typical Sizes:**
- Small: ~1-5 KB (just `m.direct` and `io.element.recent_emoji`)
- Medium: ~10-50 KB (with push rules)
- Large: ~100+ KB (with extensive push rules and settings)

### Merge Performance

Merging account_data is fast:
- Load existing: O(1) database query
- Parse JSON: O(n) where n = JSON size
- Merge keys: O(k) where k = number of incoming keys
- Store: O(1) database upsert

**Typical Performance:** < 10ms for merge operation

### Startup Performance

Loading account_data on startup:
- Single database query: O(1)
- JSON parsing: O(n) where n = JSON size
- Processing specific fields: O(k) where k = field count

**Typical Performance:** < 50ms for full load and process

---

## Testing

### Unit Tests

Account data persistence should be tested:

1. **First-time storage:** Store account_data when DB is empty
2. **Partial updates:** Merge incoming keys with existing
3. **Full updates:** Replace all keys when all keys present
4. **Run ID change:** Clear account_data when run_id changes
5. **Error handling:** Graceful fallback on merge failures

### Manual Testing

1. **Cold Start:** Verify account_data loads on app startup
2. **Partial Sync:** Send partial account_data update, verify merge
3. **Full Sync:** Send full account_data, verify replacement
4. **Run ID Change:** Change run_id, verify account_data cleared

---

## Future Enhancements

### Potential Improvements

1. **Deep Merging:** For nested objects, merge at field level instead of replacing entire object
2. **Field-Specific Processors:** Extract processing logic for each field type
3. **Validation:** Validate account_data structure before storing
4. **Compression:** Compress large account_data JSON before storing
5. **Versioning:** Track account_data version to detect changes
6. **Backup/Restore:** Export/import account_data for backup

### Additional Fields to Process

- `m.push_rules` - For notification settings UI
- `im.vector.setting.breadcrumbs` - For recent rooms list
- `fi.mau.gomuks.preferences` - For gomuks-specific settings
- `m.ignored_user_list` - For ignored users UI

---

## Related Files

### Database
- `app/src/main/java/net/vrkknn/andromuks/database/entities/AccountDataEntity.kt`
- `app/src/main/java/net/vrkknn/andromuks/database/dao/AccountDataDao.kt`
- `app/src/main/java/net/vrkknn/andromuks/database/AndromuksDatabase.kt` (version 4)

### Processing
- `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt` (ingestion)
- `app/src/main/java/net/vrkknn/andromuks/database/BootstrapLoader.kt` (loading)
- `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt` (processing)

### Documentation
- `docs/PERSISTENT_ROOM_EVENTS_STORAGE.md` - Related persistent storage system
- `docs/MEDIA_AND_PROFILE_CACHING.md` - Other caching systems

---

## Summary

The account data storage system provides:

✅ **Persistent Storage** - Account data survives app restarts  
✅ **Partial Updates** - Only updated keys are replaced, others preserved  
✅ **Fast Merging** - Efficient JSON merge operation  
✅ **Error Handling** - Graceful fallback on failures  
✅ **Run ID Protection** - Cleared on run_id change  
✅ **Extensible** - Easy to add processing for new fields  

**Key Design Principle:** Store complete account_data JSON, merge partial updates intelligently, process only needed fields.

---

**Last Updated:** 2024  
**Related Documents:**
- `PERSISTENT_ROOM_EVENTS_STORAGE.md` - Room events persistence
- `MEDIA_AND_PROFILE_CACHING.md` - Media and profile caching

