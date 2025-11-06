# Android Bubbles Implementation Issues

## Architecture Overview

Your app uses:
- **WebSocketService**: Foreground service that maintains WebSocket connection
- **AppViewModel**: ViewModel scoped to MainActivity that processes `sync_complete` messages
- **NetworkUtils**: Handles WebSocket message reception and routes to AppViewModel
- **RoomTimelineScreen**: Displays messages from AppViewModel's `timelineEvents` state

## Critical Issues with Bubbles

### 1. **ViewModel Instance Isolation** ❌

**Problem**: 
- `AppViewModel` is created with `viewModel()` which is scoped to the Activity
- MainActivity and BubbleActivity are **separate Activity instances**
- Each Activity gets its **own AppViewModel instance**
- Bubbles cannot access the same ViewModel instance as MainActivity

**Evidence from code**:
```kotlin
// MainActivity.kt
val appViewModel: AppViewModel = viewModel()  // Instance A

// ChatBubbleActivity (if it existed)
val appViewModel: AppViewModel = viewModel()  // Instance B (different!)
```

**Impact**:
- Bubble's AppViewModel won't have the same state (rooms, messages, etc.)
- Bubble won't receive `sync_complete` updates processed by MainActivity's AppViewModel
- Timeline data is isolated between MainActivity and BubbleActivity

### 2. **WebSocket Command Access** ⚠️ (Partially Solvable)

**Current Architecture**:
```kotlin
// WebSocketService.kt
private var webSocketSendCallback: ((String, Int, Map<String, Any>) -> Boolean)? = null

fun setWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
    webSocketSendCallback = callback
}
```

**Problem**:
- The callback is set by MainActivity's AppViewModel during initialization
- If BubbleActivity creates a new AppViewModel, it needs to set its own callback
- **Only one callback can be active at a time** (the last one set wins)
- This creates a race condition where MainActivity and BubbleActivity compete for the callback

**Partial Solution**:
- Bubbles **CAN** access WebSocket through `WebSocketService.setWebSocketSendCallback()`
- But only if they set the callback (which would override MainActivity's callback)
- This breaks MainActivity's ability to send messages

**Better Solution**:
- Use a **shared singleton** or **broadcast mechanism** instead of a single callback
- Or use a **message queue** that both Activities can write to

### 3. **sync_complete Message Updates** ❌

**Current Flow**:
```
WebSocket → NetworkUtils → AppViewModel.updateRoomsFromSyncJsonAsync() → timelineEvents
```

**Problem**:
- `sync_complete` messages are processed in **MainActivity's AppViewModel**
- BubbleActivity's AppViewModel is a **separate instance** and won't receive these updates
- Even if WebSocketService receives the message, it routes to the callback set by MainActivity

**Evidence from code**:
```kotlin
// NetworkUtils.kt
"sync_complete" -> {
    WebSocketService.getServiceScope().launch(Dispatchers.IO) {
        appViewModel.updateRoomsFromSyncJsonAsync(jsonObject)  // Uses MainActivity's AppViewModel
    }
}
```

**Impact**:
- Bubble timeline won't update when new messages arrive via `sync_complete`
- Bubble would need to manually poll or use a different mechanism

### 4. **Timeline State Synchronization** ❌

**Current Architecture**:
```kotlin
// AppViewModel.kt
var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
```

**Problem**:
- `timelineEvents` is a **mutable state in AppViewModel**
- Each AppViewModel instance has its own `timelineEvents`
- Bubble's timeline won't reflect MainActivity's timeline state

**Impact**:
- Opening a room in Bubble shows empty timeline (unless it loads from cache/DB)
- New messages won't appear in Bubble's timeline
- User sees different data in MainActivity vs Bubble

### 5. **Database Persistence** ✅ (Works, but with limitations)

**Good News**:
- Your app persists `sync_complete` messages to disk DB
- Bubble can read from DB to show initial timeline

**Limitations**:
- DB updates happen in MainActivity's AppViewModel
- Bubble needs to **poll the DB** or use a **ContentObserver** to detect changes
- Real-time updates won't work without additional synchronization

### 6. **Cache Access** ⚠️ (Partially Works)

**Current Architecture**:
```kotlin
// RoomTimelineCache (singleton object)
object RoomTimelineCache {
    fun getCachedEvents(roomId: String): List<TimelineEvent>?
}
```

**Good News**:
- `RoomTimelineCache` is a **singleton object**, so Bubble can access it
- Bubble can read cached events for instant loading

**Limitations**:
- Cache is updated by MainActivity's AppViewModel
- Bubble won't know when cache is updated (no real-time sync)
- Bubble needs to manually refresh or poll

## Solutions

### Option 1: Shared ViewModel (Recommended) ✅

**Approach**: Use a **singleton ViewModel** or **Application-scoped ViewModel**

```kotlin
// Create a custom ViewModelStoreOwner at Application level
class AndromuksApplication : Application() {
    val appViewModelStore = ViewModelStore()
}

// In MainActivity and BubbleActivity
val appViewModel: AppViewModel = ViewModelProvider(
    (application as AndromuksApplication).appViewModelStore
)[AppViewModel::class.java]
```

**Pros**:
- Single AppViewModel instance shared between Activities
- Bubble automatically receives `sync_complete` updates
- Timeline state is synchronized
- WebSocket callback works for both

**Cons**:
- ViewModel lifecycle tied to Application (survives Activity destruction)
- Need to manually clear ViewModel when app is truly closed

### Option 2: Event Bus / Broadcast System ✅

**Approach**: Use a **broadcast mechanism** to notify all AppViewModel instances

```kotlin
// In NetworkUtils when sync_complete arrives
LocalBroadcastManager.getInstance(context).sendBroadcast(
    Intent("sync_complete").putExtra("data", syncJson.toString())
)

// In each AppViewModel
private val syncReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "sync_complete") {
            val syncJson = JSONObject(intent.getStringExtra("data"))
            updateRoomsFromSyncJsonAsync(syncJson)
        }
    }
}
```

**Pros**:
- Works with multiple AppViewModel instances
- Decoupled architecture
- Easy to add more listeners

**Cons**:
- More complex to implement
- Need to handle registration/unregistration
- Potential memory leaks if not careful

### Option 3: Repository Pattern ✅

**Approach**: Move state management to a **Repository singleton**

```kotlin
object RoomRepository {
    private val _timelineEvents = MutableStateFlow<Map<String, List<TimelineEvent>>>(emptyMap())
    val timelineEvents: StateFlow<Map<String, List<TimelineEvent>>> = _timelineEvents.asStateFlow()
    
    fun updateTimeline(roomId: String, events: List<TimelineEvent>) {
        _timelineEvents.update { it + (roomId to events) }
    }
}

// In AppViewModel
val timelineEvents = RoomRepository.timelineEvents
    .map { it[currentRoomId] ?: emptyList() }
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
```

**Pros**:
- Single source of truth
- All Activities observe the same state
- Clean separation of concerns

**Cons**:
- Requires significant refactoring
- Need to migrate existing state to Repository

### Option 4: WebSocket Callback Queue ✅

**Approach**: Use a **message queue** instead of single callback

```kotlin
// WebSocketService.kt
private val webSocketCallbacks = mutableListOf<((String, Int, Map<String, Any>) -> Boolean)>()

fun addWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
    webSocketCallbacks.add(callback)
}

fun removeWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
    webSocketCallbacks.remove(callback)
}

private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): Boolean {
    // Try all callbacks until one succeeds
    for (callback in webSocketCallbacks) {
        if (callback(command, requestId, data)) {
            return true
        }
    }
    return false
}
```

**Pros**:
- Multiple Activities can send commands
- No callback override issues
- Minimal changes to existing code

**Cons**:
- Need to handle callback registration/unregistration
- Potential for duplicate sends if not careful

## Recommended Implementation Strategy

1. **Short-term (Quick Fix)**: Use **Option 1 (Shared ViewModel)**
   - Minimal code changes
   - Solves most issues immediately
   - Bubble gets real-time updates automatically

2. **Long-term (Best Practice)**: Migrate to **Option 3 (Repository Pattern)**
   - Better architecture
   - Easier to test
   - More scalable

## Additional Considerations

### Bubble Activity Lifecycle
- Bubbles can be minimized/maximized independently
- Need to handle `onPause()` / `onResume()` for each Activity
- WebSocketService should continue running regardless of Activity state

### Memory Management
- Shared ViewModel or Repository needs careful lifecycle management
- Clear state when app is truly closed (not just backgrounded)

### Testing
- Test with MainActivity and BubbleActivity open simultaneously
- Test with MainActivity destroyed but BubbleActivity still open
- Test WebSocket reconnection scenarios

## Summary

**Can a Bubble access the WebSocket?**
- ✅ **Yes**, but only if you fix the callback mechanism (Option 4)
- Currently, only one callback can be active, causing conflicts

**Can a Bubble receive sync_complete updates?**
- ❌ **No**, not with current architecture
- Each Activity has its own AppViewModel instance
- sync_complete is processed in MainActivity's AppViewModel only
- Solution: Use shared ViewModel (Option 1) or event bus (Option 2)

**Can a Bubble timeline be updated like RoomTimelineScreen?**
- ❌ **No**, not automatically
- Bubble's AppViewModel is separate from MainActivity's
- Timeline state is not shared
- Solution: Use shared ViewModel or Repository pattern

**Recommended Fix**: Implement **Option 1 (Shared ViewModel)** for immediate solution, then migrate to **Option 3 (Repository Pattern)** for long-term architecture.

