# Repository Pattern Implementation Plan

## Overview

This document provides a step-by-step implementation plan for migrating to the Repository pattern, implementing WebSocket callback queue, and enabling Android Chat Bubbles.

**Strategy**: Incremental migration with testing checkpoints between each phase to ensure stability.

---

## Phase 1: Create Repository (No Breaking Changes)

**Goal**: Create `RoomRepository` alongside existing code without breaking anything.

**Duration**: ~2-3 hours  
**Risk**: Low (additive changes only)

### Step 1.1: Create RoomRepository.kt

**File**: `app/src/main/java/net/vrkknn/andromuks/RoomRepository.kt`

```kotlin
package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * RoomRepository - Single source of truth for room and timeline data
 * 
 * This repository coordinates:
 * - In-memory state (StateFlow)
 * - RAM cache (RoomTimelineCache)
 * - Database persistence (AndromuksDatabase)
 * 
 * Phase 1: Created alongside existing AppViewModel state (non-breaking)
 */
object RoomRepository {
    private const val TAG = "RoomRepository"
    
    // ========== STATE (StateFlow) ==========
    private val _timelineEvents = MutableStateFlow<Map<String, List<TimelineEvent>>>(emptyMap())
    val timelineEvents: StateFlow<Map<String, List<TimelineEvent>>> = _timelineEvents.asStateFlow()
    
    private val _roomMap = MutableStateFlow<Map<String, RoomItem>>(emptyMap())
    val roomMap: StateFlow<Map<String, RoomItem>> = _roomMap.asStateFlow()
    
    private val _currentRoomId = MutableStateFlow<String>("")
    val currentRoomId: StateFlow<String> = _currentRoomId.asStateFlow()
    
    // ========== INITIALIZATION ==========
    private var context: Context? = null
    private var isInitialized = false
    
    /**
     * Initialize Repository (call from Application or MainActivity onCreate)
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "Repository already initialized")
            return
        }
        this.context = context.applicationContext
        isInitialized = true
        Log.d(TAG, "RoomRepository initialized")
    }
    
    // ========== QUERY FUNCTIONS ==========
    /**
     * Get timeline for a specific room
     */
    fun getTimelineForRoom(roomId: String): List<TimelineEvent> {
        return _timelineEvents.value[roomId] ?: emptyList()
    }
    
    /**
     * Get event count for a room
     */
    fun getEventCount(roomId: String): Int {
        return _timelineEvents.value[roomId]?.size ?: 0
    }
    
    /**
     * Get room by ID
     */
    fun getRoom(roomId: String): RoomItem? {
        return _roomMap.value[roomId]
    }
    
    /**
     * Set current room ID
     */
    fun setCurrentRoom(roomId: String) {
        _currentRoomId.value = roomId
        Log.d(TAG, "Current room set to: $roomId")
    }
    
    // ========== UPDATE FUNCTIONS (Phase 1: Called alongside AppViewModel updates) ==========
    /**
     * Update timeline for a room
     * Phase 1: Called from AppViewModel AFTER it updates its own state
     */
    fun updateTimeline(roomId: String, events: List<TimelineEvent>) {
        _timelineEvents.update { currentMap ->
            val existingEvents = currentMap[roomId] ?: emptyList()
            
            // Merge: Add new events, deduplicate by eventId
            val existingEventIds = existingEvents.map { it.eventId }.toSet()
            val uniqueNewEvents = events.filter { it.eventId !in existingEventIds }
            
            if (uniqueNewEvents.isEmpty()) {
                currentMap // No new events
            } else {
                // Merge and sort by timestamp
                val merged = (existingEvents + uniqueNewEvents)
                    .sortedBy { it.timestamp }
                currentMap + (roomId to merged)
            }
        }
        Log.d(TAG, "Updated timeline for room $roomId: ${getEventCount(roomId)} events")
    }
    
    /**
     * Append events to timeline (for sync_complete)
     */
    fun appendTimelineEvents(roomId: String, newEvents: List<TimelineEvent>) {
        _timelineEvents.update { currentMap ->
            val existingEvents = currentMap[roomId] ?: emptyList()
            val existingEventIds = existingEvents.map { it.eventId }.toSet()
            val uniqueNewEvents = newEvents.filter { it.eventId !in existingEventIds }
            
            if (uniqueNewEvents.isEmpty()) {
                currentMap
            } else {
                val merged = (existingEvents + uniqueNewEvents).sortedBy { it.timestamp }
                currentMap + (roomId to merged)
            }
        }
        Log.d(TAG, "Appended ${newEvents.size} events to room $roomId: ${getEventCount(roomId)} total")
    }
    
    /**
     * Update room map
     */
    fun updateRooms(rooms: Map<String, RoomItem>) {
        _roomMap.value = rooms
        Log.d(TAG, "Updated room map: ${rooms.size} rooms")
    }
    
    /**
     * Update single room
     */
    fun updateRoom(room: RoomItem) {
        _roomMap.update { it + (room.id to room) }
        Log.d(TAG, "Updated room: ${room.id}")
    }
    
    // ========== PHASE 1: PLACEHOLDER FUNCTIONS (Will be implemented in later phases) ==========
    /**
     * Update from sync_complete
     * Phase 1: Placeholder - will be implemented in Phase 2
     */
    suspend fun updateFromSyncComplete(
        syncJson: JSONObject,
        requestId: Int,
        runId: String,
        memberMap: Map<String, MemberProfile>
    ) = withContext(Dispatchers.IO) {
        // Phase 1: Do nothing - AppViewModel still handles this
        Log.d(TAG, "updateFromSyncComplete called (Phase 1: placeholder)")
    }
    
    /**
     * Load events from database
     * Phase 1: Placeholder - will be implemented in Phase 2
     */
    suspend fun loadEventsFromDatabase(
        roomId: String,
        limit: Int = 100,
        beforeRowId: Long = -1
    ): List<TimelineEvent> = withContext(Dispatchers.IO) {
        // Phase 1: Return empty - AppViewModel still handles this
        Log.d(TAG, "loadEventsFromDatabase called (Phase 1: placeholder)")
        emptyList()
    }
}
```

### Step 1.2: Initialize Repository in MainActivity

**File**: `app/src/main/java/net/vrkknn/andromuks/MainActivity.kt`

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Initialize Repository (Phase 1)
    RoomRepository.initialize(this)
    
    // ... rest of onCreate
}
```

### Step 1.3: Update AppViewModel to Also Update Repository

**File**: `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

**Location**: In `updateRoomsFromSyncJsonAsync()` method

```kotlin
// AFTER updating AppViewModel's own state, also update Repository
fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
    viewModelScope.launch(Dispatchers.IO) {
        // ... existing code that updates AppViewModel state ...
        
        // PHASE 1: Also update Repository (non-breaking)
        val currentRoomId = this@AppViewModel.currentRoomId
        if (currentRoomId.isNotEmpty()) {
            val currentTimeline = this@AppViewModel.timelineEvents
            RoomRepository.updateTimeline(currentRoomId, currentTimeline)
        }
        
        // Also update room map
        RoomRepository.updateRooms(this@AppViewModel.roomMap)
    }
}
```

**Location**: In `navigateToRoom()` or wherever `currentRoomId` is set

```kotlin
fun navigateToRoom(roomId: String) {
    // ... existing code ...
    
    // PHASE 1: Also update Repository
    RoomRepository.setCurrentRoom(roomId)
}
```

### Step 1.4: Testing Checklist

**Before proceeding to Phase 2, verify:**

- [ ] App compiles without errors
- [ ] App starts normally
- [ ] Room list displays correctly
- [ ] Opening a room shows timeline
- [ ] Sending a message works
- [ ] Receiving messages works (sync_complete)
- [ ] No crashes or regressions
- [ ] Check logs: Repository should log updates alongside AppViewModel

**Test Commands:**
```bash
# Run app and check logs
adb logcat | grep -E "(RoomRepository|AppViewModel)"
```

**Expected Behavior:**
- Repository logs should appear alongside AppViewModel logs
- Both should update when sync_complete arrives
- No functionality should break

**Rollback Plan:**
- If issues occur, simply remove Repository initialization and update calls
- AppViewModel continues working as before

---

## Phase 2: Migrate ViewModels to Observe Repository

**Goal**: ViewModels observe Repository instead of holding state.

**Duration**: ~4-5 hours  
**Risk**: Medium (changing state management)

### Step 2.1: Modify AppViewModel to Observe Repository

**File**: `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

**Change 1**: Replace `timelineEvents` state with Repository observation

```kotlin
// BEFORE (Phase 1):
var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
    private set

// AFTER (Phase 2):
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

val timelineEvents: StateFlow<List<TimelineEvent>> = combine(
    RoomRepository.timelineEvents,
    RoomRepository.currentRoomId
) { timelineMap, currentRoomId ->
    timelineMap[currentRoomId] ?: emptyList()
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.Lazily,
    initialValue = emptyList()
)
```

**Change 2**: Replace `roomMap` state with Repository observation

```kotlin
// BEFORE:
var roomMap by mutableStateOf<Map<String, RoomItem>>(emptyMap())
    private set

// AFTER:
val roomMap: StateFlow<Map<String, RoomItem>> = RoomRepository.roomMap
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())
```

**Change 3**: Replace `currentRoomId` state with Repository observation

```kotlin
// BEFORE:
var currentRoomId by mutableStateOf("")
    private set

// AFTER:
val currentRoomId: StateFlow<String> = RoomRepository.currentRoomId
    .stateIn(viewModelScope, SharingStarted.Lazily, "")
```

**Change 4**: Update methods that set `currentRoomId`

```kotlin
// BEFORE:
fun navigateToRoom(roomId: String) {
    currentRoomId = roomId
    // ...
}

// AFTER:
fun navigateToRoom(roomId: String) {
    RoomRepository.setCurrentRoom(roomId)  // Update Repository
    // Repository automatically updates currentRoomId StateFlow
    // AppViewModel's currentRoomId StateFlow automatically updates
}
```

**Change 5**: Update `updateRoomsFromSyncJsonAsync()` to update Repository directly

```kotlin
// BEFORE (Phase 1):
fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
    viewModelScope.launch(Dispatchers.IO) {
        // Update AppViewModel state
        timelineEvents = parseEvents(...)
        // Also update Repository
        RoomRepository.updateTimeline(...)
    }
}

// AFTER (Phase 2):
fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
    viewModelScope.launch(Dispatchers.IO) {
        // Parse events
        val events = parseEvents(...)
        val roomId = extractRoomId(...)
        
        // Update Repository directly (AppViewModel observes it)
        RoomRepository.appendTimelineEvents(roomId, events)
        
        // AppViewModel's timelineEvents StateFlow automatically updates!
    }
}
```

**Change 6**: Update Composable screens to use StateFlow

**File**: `app/src/main/java/net/vrkknn/andromuks/RoomTimelineScreen.kt`

```kotlin
// BEFORE:
val timelineEvents = appViewModel.timelineEvents

// AFTER:
import androidx.compose.runtime.collectAsState

val timelineEvents by appViewModel.timelineEvents.collectAsState()
```

### Step 2.2: Update Repository.updateFromSyncComplete()

**File**: `app/src/main/java/net/vrkknn/andromuks/RoomRepository.kt`

```kotlin
suspend fun updateFromSyncComplete(
    syncJson: JSONObject,
    requestId: Int,
    runId: String,
    memberMap: Map<String, MemberProfile>
) = withContext(Dispatchers.IO) {
    val data = syncJson.optJSONObject("data") ?: return@withContext
    val rooms = data.optJSONObject("rooms") ?: return@withContext
    
    // Process each room
    rooms.keys().forEach { roomId ->
        val roomData = rooms.optJSONObject(roomId) ?: return@forEach
        val eventsArray = roomData.optJSONArray("events") ?: return@forEach
        
        // 1. Parse events
        val events = parseEventsFromSync(eventsArray, memberMap)
        
        // 2. Update RAM cache
        RoomTimelineCache.addEventsFromSync(roomId, eventsArray, memberMap)
        
        // 3. Persist to database
        val syncIngestor = context?.let { SyncIngestor(it) }
        syncIngestor?.let {
            // Ingest this room's data (SyncIngestor handles full sync)
            it.ingestSyncComplete(syncJson, requestId, runId)
        }
        
        // 4. Update Repository StateFlow
        appendTimelineEvents(roomId, events)
    }
}

private fun parseEventsFromSync(
    eventsArray: JSONArray,
    memberMap: Map<String, MemberProfile>
): List<TimelineEvent> {
    // Same logic as RoomTimelineCache.parseEventsFromArray()
    val events = mutableListOf<TimelineEvent>()
    for (i in 0 until eventsArray.length()) {
        val eventJson = eventsArray.optJSONObject(i) ?: continue
        try {
            val event = TimelineEvent.fromJson(eventJson)
            if (shouldCacheEvent(event)) {
                events.add(event)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse event: ${e.message}")
        }
    }
    return events
}

private fun shouldCacheEvent(event: TimelineEvent): Boolean {
    return when {
        event.type == "m.reaction" -> false
        event.type == "m.room.member" && event.timelineRowid < 0 -> false
        event.type == "m.room.message" || 
        event.type == "m.room.encrypted" || 
        event.type == "m.sticker" -> true
        event.timelineRowid >= 0 -> true
        else -> false
    }
}
```

### Step 2.3: Update NetworkUtils to Call Repository

**File**: `app/src/main/java/net/vrkknn/andromuks/utils/NetworkUtils.kt`

```kotlin
// In sync_complete handler:
"sync_complete" -> {
    Log.d("Andromuks", "NetworkUtils: Processing sync_complete message")
    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
        // PHASE 2: Update Repository (which notifies all ViewModels)
        val memberMap = appViewModel.getMemberMapForSync(jsonObject)
        val runId = WebSocketService.getCurrentRunId()
        val requestId = extractRequestId(jsonObject)
        
        RoomRepository.updateFromSyncComplete(
            syncJson = jsonObject,
            requestId = requestId,
            runId = runId,
            memberMap = memberMap
        )
        
        // ALSO call AppViewModel for compatibility (will be removed in Phase 3)
        appViewModel.updateRoomsFromSyncJsonAsync(jsonObject)
    }
}
```

### Step 2.4: Testing Checklist

**Before proceeding to Phase 3, verify:**

- [ ] App compiles without errors
- [ ] Room list displays correctly
- [ ] Opening a room shows timeline
- [ ] Timeline updates when sync_complete arrives
- [ ] Sending a message works
- [ ] Messages appear in timeline
- [ ] No duplicate events in timeline
- [ ] Room list updates correctly
- [ ] No crashes or regressions
- [ ] Check logs: Repository should be primary updater

**Test Scenarios:**
1. Open app → Room list should appear
2. Open a room → Timeline should load
3. Send a message → Should appear immediately
4. Receive sync_complete → Timeline should update
5. Switch rooms → Should show correct timeline
6. Background/foreground → State should persist

**Rollback Plan:**
- Revert AppViewModel changes (restore mutableStateOf)
- Keep Repository but don't use it
- AppViewModel continues working independently

---

## Phase 3: Remove Old State Management

**Goal**: Remove all local state from ViewModels, Repository is the only source.

**Duration**: ~3-4 hours  
**Risk**: Medium (removing code)

### Step 3.1: Remove Local State from AppViewModel

**File**: `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

**Remove**:
- All `var timelineEvents by mutableStateOf(...)`
- All `var roomMap by mutableStateOf(...)`
- All `var currentRoomId by mutableStateOf(...)`
- All direct assignments to these variables

**Keep**:
- StateFlow observations of Repository
- Business logic methods
- WebSocket command sending

### Step 3.2: Remove Compatibility Calls

**File**: `app/src/main/java/net/vrkknn/andromuks/utils/NetworkUtils.kt`

```kotlin
// BEFORE (Phase 2):
"sync_complete" -> {
    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
        RoomRepository.updateFromSyncComplete(...)
        appViewModel.updateRoomsFromSyncJsonAsync(jsonObject)  // ← REMOVE THIS
    }
}

// AFTER (Phase 3):
"sync_complete" -> {
    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
        RoomRepository.updateFromSyncComplete(...)
        // AppViewModel.updateRoomsFromSyncJsonAsync() removed - Repository handles it
    }
}
```

### Step 3.3: Update All State Access

**Search and replace** throughout AppViewModel:

```kotlin
// BEFORE:
if (timelineEvents.isNotEmpty()) { ... }
val count = timelineEvents.size

// AFTER:
val currentTimeline = timelineEvents.value
if (currentTimeline.isNotEmpty()) { ... }
val count = currentTimeline.size
```

### Step 3.4: Testing Checklist

**Before proceeding to Phase 4, verify:**

- [ ] App compiles without errors
- [ ] All functionality works as before
- [ ] No references to old state variables
- [ ] Repository is the only state source
- [ ] No crashes or regressions
- [ ] Performance is acceptable

**Test Scenarios:**
- Full app workflow (open, send, receive, navigate)
- Multiple rooms
- Background/foreground transitions
- App restart (state persistence)

**Rollback Plan:**
- Restore removed state variables
- Restore compatibility calls
- Phase 2 state can be restored

---

## Phase 4: WebSocket Callback Queue

**Goal**: Enable multiple Activities to send WebSocket commands.

**Duration**: ~2-3 hours  
**Risk**: Low (additive changes)

### Step 4.1: Modify WebSocketService

**File**: `app/src/main/java/net/vrkknn/andromuks/WebSocketService.kt`

**Change 1**: Replace single callback with list

```kotlin
// BEFORE:
private var webSocketSendCallback: ((String, Int, Map<String, Any>) -> Boolean)? = null

// AFTER:
private val webSocketCallbacks = mutableListOf<((String, Int, Map<String, Any>) -> Boolean)>()
private val callbackLock = Any()  // For thread safety
```

**Change 2**: Replace setWebSocketSendCallback with add/remove

```kotlin
// BEFORE:
fun setWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
    webSocketSendCallback = callback
}

// AFTER:
fun addWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
    synchronized(callbackLock) {
        if (!webSocketCallbacks.contains(callback)) {
            webSocketCallbacks.add(callback)
            android.util.Log.d("WebSocketService", "Added WebSocket callback (total: ${webSocketCallbacks.size})")
        }
    }
}

fun removeWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
    synchronized(callbackLock) {
        webSocketCallbacks.remove(callback)
        android.util.Log.d("WebSocketService", "Removed WebSocket callback (total: ${webSocketCallbacks.size})")
    }
}
```

**Change 3**: Update sendWebSocketCommand to try all callbacks

**Note**: This might not exist in WebSocketService - it might be in AppViewModel. Check where commands are actually sent.

**If in AppViewModel**, no changes needed - each AppViewModel uses its own `sendWebSocketCommand()`.

**If in WebSocketService**, update to:

```kotlin
private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): Boolean {
    val ws = getWebSocket()
    if (ws == null) {
        return false
    }
    
    synchronized(callbackLock) {
        // Try each callback until one succeeds
        for (callback in webSocketCallbacks) {
            try {
                if (callback(command, requestId, data)) {
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.e("WebSocketService", "Error in callback", e)
            }
        }
    }
    
    return false
}
```

### Step 4.2: Update AppViewModel to Register/Unregister

**File**: `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

```kotlin
class AppViewModel : ViewModel() {
    // Store callback reference for cleanup
    private val webSocketCallback = { command: String, requestId: Int, data: Map<String, Any> ->
        sendWebSocketCommand(command, requestId, data) == WebSocketResult.SUCCESS
    }
    
    fun setWebSocket(webSocket: WebSocket) {
        this.webSocket = webSocket
        
        // PHASE 4: Add callback instead of setting
        WebSocketService.addWebSocketSendCallback(webSocketCallback)
        
        // ... rest of setWebSocket
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // PHASE 4: Remove callback when ViewModel is destroyed
        WebSocketService.removeWebSocketSendCallback(webSocketCallback)
        
        // ... rest of cleanup
    }
}
```

### Step 4.3: Testing Checklist

**Before proceeding to Phase 5, verify:**

- [ ] App compiles without errors
- [ ] MainActivity can send messages
- [ ] WebSocket commands work normally
- [ ] No callback conflicts
- [ ] Check logs: Multiple callbacks can be registered
- [ ] No crashes or regressions

**Test Scenarios:**
1. Send message from MainActivity → Should work
2. Check logs for callback registration
3. Verify no callback override issues

**Rollback Plan:**
- Revert to single callback
- Restore `setWebSocketSendCallback()`

---

## Phase 5: Implement Android Chat Bubbles

**Goal**: Enable BubbleActivity to work with Repository and WebSocket.

**Duration**: ~6-8 hours  
**Risk**: High (new feature)

### Step 5.1: Create BubbleActivity

**File**: `app/src/main/java/net/vrkknn/andromuks/BubbleActivity.kt`

```kotlin
package net.vrkknn.andromuks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Repository (if not already initialized)
        RoomRepository.initialize(this)
        
        val roomId = intent.getStringExtra("room_id") ?: return
        
        setContent {
            AndromuksTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val appViewModel: AppViewModel = viewModel()
                    
                    // Initialize WebSocket if needed
                    appViewModel.initializeWebSocketIfNeeded(this@BubbleActivity)
                    
                    ChatBubbleScreen(
                        roomId = roomId,
                        appViewModel = appViewModel
                    )
                }
            }
        }
    }
}
```

### Step 5.2: Update ChatBubbleScreen

**File**: `app/src/main/java/net/vrkknn/andromuks/ChatBubbleScreen_DISABLED.kt`

**Rename to**: `ChatBubbleScreen.kt` (remove _DISABLED)

**Update**:
- Use `appViewModel.timelineEvents.collectAsState()` (already observes Repository!)
- Use `appViewModel.sendMessage()` (already uses callback queue!)

**No changes needed** - it should work automatically because:
- AppViewModel observes Repository
- Repository is shared between Activities
- Callback queue allows both Activities to send commands

### Step 5.3: Register Bubble in AndroidManifest.xml

**File**: `app/src/main/AndroidManifest.xml`

```xml
<activity
    android:name=".BubbleActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.Translucent.NoTitleBar"
    android:launchMode="singleTask"
    android:taskAffinity=""
    android:allowEmbedded="true"
    android:documentLaunchMode="always"
    android:resizeableActivity="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
    </intent-filter>
</activity>
```

### Step 5.4: Update Notification to Launch Bubble

**File**: `app/src/main/java/net/vrkknn/andromuks/FCMNotificationManager.kt` (or wherever notifications are created)

```kotlin
// Create bubble intent
val bubbleIntent = Intent(context, BubbleActivity::class.java).apply {
    putExtra("room_id", roomId)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
}

// Create bubble metadata
val bubbleMetadata = Notification.BubbleMetadata.Builder()
    .setIntent(bubbleIntent)
    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
    .build()

// Add to notification
notificationBuilder.setBubbleMetadata(bubbleMetadata)
```

### Step 5.5: Testing Checklist

**Final verification:**

- [ ] App compiles without errors
- [ ] Bubble can be opened from notification
- [ ] Bubble shows timeline (from Repository)
- [ ] Bubble can send messages (via callback queue)
- [ ] MainActivity and Bubble show same data
- [ ] sync_complete updates both Activities
- [ ] No crashes or regressions

**Test Scenarios:**
1. Open bubble from notification
2. Send message from bubble → Should appear in both
3. Send message from MainActivity → Should appear in both
4. Receive sync_complete → Both should update
5. Close bubble → MainActivity should still work
6. Open multiple bubbles → Each should work independently

---

## Testing Strategy

### Between Each Phase

1. **Compile Test**: Ensure code compiles
2. **Smoke Test**: Basic app functionality
3. **Regression Test**: Compare with previous phase
4. **Log Analysis**: Check for errors/warnings
5. **User Testing**: Real-world usage scenarios

### Test Commands

```bash
# Monitor logs
adb logcat | grep -E "(RoomRepository|AppViewModel|WebSocketService)"

# Check for crashes
adb logcat | grep -E "(FATAL|AndroidRuntime)"

# Monitor memory
adb shell dumpsys meminfo net.vrkknn.andromuks
```

### Rollback Strategy

Each phase can be rolled back independently:
- Phase 1: Remove Repository initialization
- Phase 2: Restore AppViewModel state
- Phase 3: Restore compatibility calls
- Phase 4: Revert to single callback
- Phase 5: Disable BubbleActivity

---

## Success Criteria

### Phase 1 Complete
- ✅ Repository exists and is initialized
- ✅ Repository updates alongside AppViewModel
- ✅ No functionality broken

### Phase 2 Complete
- ✅ ViewModels observe Repository
- ✅ State updates work correctly
- ✅ No duplicate state management

### Phase 3 Complete
- ✅ Repository is only state source
- ✅ Old state code removed
- ✅ Clean codebase

### Phase 4 Complete
- ✅ Multiple callbacks supported
- ✅ No callback conflicts
- ✅ Commands work from any Activity

### Phase 5 Complete
- ✅ Bubbles work correctly
- ✅ State synchronized between Activities
- ✅ Commands work from Bubbles

---

## Timeline Estimate

- **Phase 1**: 2-3 hours
- **Phase 2**: 4-5 hours
- **Phase 3**: 3-4 hours
- **Phase 4**: 2-3 hours
- **Phase 5**: 6-8 hours

**Total**: ~17-23 hours of development + testing time

---

## Notes

- Test thoroughly between each phase
- Keep git commits per phase for easy rollback
- Document any issues encountered
- Update this plan if needed based on findings

