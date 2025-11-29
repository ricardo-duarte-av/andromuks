# Initial Sync Complete Fix

## Problem

The app was showing the room list before all initial room data was loaded, causing:
- Missing or incomplete room avatars
- Rooms not in proper order
- Missing unread badges
- Missing last message time, sender, and message preview

This happened because:
1. WebSocket connects and starts receiving `sync_complete` messages
2. RoomListScreen shows as soon as `spacesLoaded` is true (from cache)
3. But initial `sync_complete` messages (received before `init_complete`) are still being processed
4. UI appears with incomplete data

## Solution

Wait for all initial `sync_complete` messages (received before `init_complete`) to be processed before showing the room list.

### Flow:

1. **WebSocket connects** → Set `initialSyncPhase = false` (start tracking)
2. **sync_complete messages arrive** → Queue them (don't process yet)
3. **init_complete arrives** → Set `initialSyncPhase = true` (stop queueing)
4. **Process all queued messages** → Process each queued `sync_complete` in order
5. **Mark complete** → Set `initialSyncComplete = true` when all are processed
6. **Show UI** → RoomListScreen waits for `initialSyncComplete` before showing

## Implementation

### AppViewModel.kt

1. **Added state tracking**:
   ```kotlin
   private var initialSyncPhase = false // false = queueing, true = processing
   private val initialSyncCompleteQueue = mutableListOf<JSONObject>()
   private var initialSyncProcessingComplete = false
   var initialSyncComplete by mutableStateOf(false) // Public state for UI
   ```

2. **setWebSocket()** - Initialize tracking:
   - Set `initialSyncPhase = false` when WebSocket connects
   - Clear queue and reset flags

3. **updateRoomsFromSyncJsonAsync()** - Queue messages:
   - If `initialSyncPhase == false`, queue the message instead of processing
   - If `initialSyncPhase == true`, process normally (real-time updates)

4. **onInitComplete()** - Process queue:
   - Set `initialSyncPhase = true` (stop queueing)
   - Process all queued messages in order
   - Set `initialSyncComplete = true` when done

5. **processInitialSyncComplete()** - New function:
   - Processes a single queued `sync_complete` message
   - Same logic as `updateRoomsFromSyncJsonAsync` but without queue check

6. **attachToExistingWebSocketIfAvailable()** - Handle reconnection:
   - If already initialized, mark initial sync as complete immediately

### RoomListScreen.kt

1. **Wait for initial sync completion**:
   - Observe `appViewModel.initialSyncComplete`
   - Show loading screen until `initialSyncComplete == true`
   - 15-second timeout fallback to prevent infinite loading

2. **Updated profile loading**:
   - Wait for `initialSyncComplete` instead of just WebSocket connection
   - Ensures all room data is loaded before requesting profiles

## Benefits

1. **Complete room data**: All rooms have avatars, proper order, unread badges, last message info
2. **Better UX**: UI appears fully populated, not partially loaded
3. **No race conditions**: All initial data is processed before UI shows
4. **Graceful degradation**: 15-second timeout ensures UI shows even if something goes wrong

## Edge Cases Handled

1. **Reconnection**: If attaching to already-initialized WebSocket, mark sync as complete
2. **Timeout**: 15-second fallback prevents infinite loading
3. **No initial messages**: If no `sync_complete` messages arrive before `init_complete`, mark as complete immediately

## Testing

After these changes, the cold start should show:
- "Loading rooms..." message while initial sync processes
- Room list appears with all data complete:
  - All room avatars loaded
  - Rooms in correct order
  - Unread badges accurate
  - Last message time, sender, and preview all present
- No partial/stale data shown to user

