# Repository Pattern Explained

## What is the Repository Pattern?

The **Repository Pattern** is a design pattern that separates data access logic from business logic. It acts as a **single source of truth** for your app's data, making it accessible from anywhere in your app (multiple Activities, ViewModels, etc.).

Think of it like a **centralized data store** that:
- Holds all your app's state in one place
- Provides a clean API to read/write data
- Automatically notifies all observers when data changes
- Works independently of Activities/ViewModels

## Current Architecture (Without Repository)

### Problem: State is Scattered

Currently, your app's state lives inside `AppViewModel`:

```kotlin
// AppViewModel.kt
class AppViewModel : ViewModel() {
    // State lives HERE - tied to this ViewModel instance
    var timelineEvents by mutableStateOf<List<TimelineEvent>>(emptyList())
    var roomMap by mutableStateOf<Map<String, RoomItem>>(emptyMap())
    var currentRoomId by mutableStateOf("")
    
    // When sync_complete arrives, it updates THIS instance
    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
        // Updates timelineEvents, roomMap, etc. in THIS ViewModel
    }
}
```

**Issue**: Each Activity gets its own `AppViewModel` instance:
- MainActivity → AppViewModel Instance A
- BubbleActivity → AppViewModel Instance B

They don't share state!

## Repository Pattern Architecture

### Solution: Centralized State Store

Move state to a **singleton Repository** that lives outside ViewModels:

```kotlin
// RoomRepository.kt (NEW FILE)
object RoomRepository {
    // Private mutable state (only Repository can modify)
    private val _timelineEvents = MutableStateFlow<Map<String, List<TimelineEvent>>>(emptyMap())
    
    // Public read-only state (ViewModels can observe)
    val timelineEvents: StateFlow<Map<String, List<TimelineEvent>>> = _timelineEvents.asStateFlow()
    
    // Private mutable state for rooms
    private val _roomMap = MutableStateFlow<Map<String, RoomItem>>(emptyMap())
    val roomMap: StateFlow<Map<String, RoomItem>> = _roomMap.asStateFlow()
    
    // Private mutable state for current room
    private val _currentRoomId = MutableStateFlow<String>("")
    val currentRoomId: StateFlow<String> = _currentRoomId.asStateFlow()
    
    // Public functions to update state (single source of truth)
    fun updateTimeline(roomId: String, events: List<TimelineEvent>) {
        _timelineEvents.update { currentMap ->
            currentMap + (roomId to events)
        }
    }
    
    fun updateRooms(rooms: Map<String, RoomItem>) {
        _roomMap.value = rooms
    }
    
    fun setCurrentRoom(roomId: String) {
        _currentRoomId.value = roomId
    }
    
    // Helper function to get timeline for a specific room
    fun getTimelineForRoom(roomId: String): List<TimelineEvent> {
        return _timelineEvents.value[roomId] ?: emptyList()
    }
}
```

### How ViewModels Use the Repository

ViewModels **observe** the Repository instead of holding state:

```kotlin
// AppViewModel.kt (MODIFIED)
class AppViewModel : ViewModel() {
    // Instead of: var timelineEvents by mutableStateOf(...)
    // We observe the Repository:
    val timelineEvents: StateFlow<List<TimelineEvent>> = RoomRepository.timelineEvents
        .map { timelineMap ->
            // Filter to current room's timeline
            val currentRoom = RoomRepository.currentRoomId.value
            timelineMap[currentRoom] ?: emptyList()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )
    
    // Observe rooms
    val roomMap: StateFlow<Map<String, RoomItem>> = RoomRepository.roomMap
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())
    
    // When sync_complete arrives, update the Repository (not local state)
    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            // Parse sync_complete...
            val events = parseEvents(syncJson)
            
            // Update Repository (not local state!)
            RoomRepository.updateTimeline(roomId, events)
            
            // All ViewModels observing RoomRepository automatically get the update!
        }
    }
}
```

## How It Works: Step by Step

### Step 1: sync_complete Arrives

```
WebSocket → NetworkUtils → AppViewModel.updateRoomsFromSyncJsonAsync()
```

### Step 2: AppViewModel Updates Repository

```kotlin
// In AppViewModel
fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
    val events = parseEvents(syncJson)
    val roomId = extractRoomId(syncJson)
    
    // Update Repository (single source of truth)
    RoomRepository.updateTimeline(roomId, events)
}
```

### Step 3: Repository Notifies All Observers

```kotlin
// RoomRepository.kt
fun updateTimeline(roomId: String, events: List<TimelineEvent>) {
    _timelineEvents.update { currentMap ->
        currentMap + (roomId to events)  // Update internal state
    }
    // StateFlow automatically emits new value to all collectors!
}
```

### Step 4: All ViewModels Receive Update

```
MainActivity's AppViewModel → Observes RoomRepository → Gets update ✅
BubbleActivity's AppViewModel → Observes RoomRepository → Gets update ✅
```

Both Activities see the same data because they're observing the **same Repository**!

## Complete Example: Your App with Repository Pattern

### 1. Create RoomRepository

```kotlin
// RoomRepository.kt
object RoomRepository {
    // Timeline events: roomId -> List<TimelineEvent>
    private val _timelineEvents = MutableStateFlow<Map<String, List<TimelineEvent>>>(emptyMap())
    val timelineEvents: StateFlow<Map<String, List<TimelineEvent>>> = _timelineEvents.asStateFlow()
    
    // Room list
    private val _roomMap = MutableStateFlow<Map<String, RoomItem>>(emptyMap())
    val roomMap: StateFlow<Map<String, RoomItem>> = _roomMap.asStateFlow()
    
    // Current room ID
    private val _currentRoomId = MutableStateFlow<String>("")
    val currentRoomId: StateFlow<String> = _currentRoomId.asStateFlow()
    
    // Member profiles: roomId:userId -> MemberProfile
    private val _memberCache = MutableStateFlow<Map<String, MemberProfile>>(emptyMap())
    val memberCache: StateFlow<Map<String, MemberProfile>> = _memberCache.asStateFlow()
    
    // Update functions
    fun updateTimeline(roomId: String, events: List<TimelineEvent>) {
        _timelineEvents.update { it + (roomId to events) }
    }
    
    fun appendTimelineEvents(roomId: String, newEvents: List<TimelineEvent>) {
        _timelineEvents.update { currentMap ->
            val existingEvents = currentMap[roomId] ?: emptyList()
            currentMap + (roomId to (existingEvents + newEvents))
        }
    }
    
    fun updateRooms(rooms: Map<String, RoomItem>) {
        _roomMap.value = rooms
    }
    
    fun setCurrentRoom(roomId: String) {
        _currentRoomId.value = roomId
    }
    
    fun updateMemberProfile(roomId: String, userId: String, profile: MemberProfile) {
        val key = "$roomId:$userId"
        _memberCache.update { it + (key to profile) }
    }
    
    // Query functions
    fun getTimelineForRoom(roomId: String): List<TimelineEvent> {
        return _timelineEvents.value[roomId] ?: emptyList()
    }
    
    fun getRoom(roomId: String): RoomItem? {
        return _roomMap.value[roomId]
    }
    
    fun getMemberProfile(roomId: String, userId: String): MemberProfile? {
        val key = "$roomId:$userId"
        return _memberCache.value[key]
    }
}
```

### 2. Modify AppViewModel to Use Repository

```kotlin
// AppViewModel.kt
class AppViewModel : ViewModel() {
    // OBSERVE Repository instead of holding state
    val timelineEvents: StateFlow<List<TimelineEvent>> = combine(
        RoomRepository.timelineEvents,
        RoomRepository.currentRoomId
    ) { timelineMap, currentRoomId ->
        timelineMap[currentRoomId] ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val roomMap: StateFlow<Map<String, RoomItem>> = RoomRepository.roomMap
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())
    
    val currentRoomId: StateFlow<String> = RoomRepository.currentRoomId
        .stateIn(viewModelScope, SharingStarted.Lazily, "")
    
    // When sync_complete arrives, update Repository
    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            val data = syncJson.optJSONObject("data") ?: return@launch
            val rooms = data.optJSONObject("rooms") ?: return@launch
            
            // Process each room
            rooms.keys().forEach { roomId ->
                val roomData = rooms.optJSONObject(roomId) ?: return@forEach
                val events = roomData.optJSONArray("events") ?: return@forEach
                
                // Parse events
                val timelineEvents = parseTimelineEvents(events, roomId)
                
                // Update Repository (not local state!)
                RoomRepository.appendTimelineEvents(roomId, timelineEvents)
            }
            
            // All ViewModels observing RoomRepository automatically get updates!
        }
    }
    
    // When opening a room, update Repository
    fun navigateToRoom(roomId: String) {
        RoomRepository.setCurrentRoom(roomId)
        // Timeline automatically updates because we're observing currentRoomId
    }
    
    // When sending a message, update Repository
    fun sendMessage(roomId: String, text: String) {
        viewModelScope.launch {
            // Send via WebSocket...
            val result = sendWebSocketCommand("send_message", ...)
            
            if (result == WebSocketResult.SUCCESS) {
                // Message will arrive via sync_complete and update Repository
                // But we can optimistically add it to Repository immediately
                val optimisticEvent = createOptimisticMessage(roomId, text)
                RoomRepository.appendTimelineEvents(roomId, listOf(optimisticEvent))
            }
        }
    }
}
```

### 3. Use in Composable Screens

```kotlin
// RoomTimelineScreen.kt
@Composable
fun RoomTimelineScreen(
    roomId: String,
    appViewModel: AppViewModel = viewModel()
) {
    // Observe timeline from ViewModel (which observes Repository)
    val timelineEvents by appViewModel.timelineEvents.collectAsState()
    
    // When Repository updates, this automatically recomposes!
    LazyColumn {
        items(timelineEvents) { event ->
            MessageBubble(event)
        }
    }
}
```

### 4. Use in Bubble Screen

```kotlin
// ChatBubbleScreen.kt
@Composable
fun ChatBubbleScreen(
    roomId: String,
    appViewModel: AppViewModel = viewModel()  // Different instance, but observes same Repository!
) {
    // Observe timeline from ViewModel (which observes Repository)
    val timelineEvents by appViewModel.timelineEvents.collectAsState()
    
    // When MainActivity's AppViewModel updates Repository,
    // Bubble's AppViewModel automatically gets the update!
    LazyColumn {
        items(timelineEvents) { event ->
            MessageBubble(event)
        }
    }
}
```

## Key Benefits

### 1. Single Source of Truth ✅

All Activities/ViewModels observe the **same Repository**:
- MainActivity's AppViewModel → Observes RoomRepository
- BubbleActivity's AppViewModel → Observes RoomRepository
- They see the **same data** automatically!

### 2. Automatic Synchronization ✅

When `sync_complete` arrives:
1. MainActivity's AppViewModel processes it
2. Updates RoomRepository
3. **Both** MainActivity and BubbleActivity automatically get the update
4. No manual synchronization needed!

### 3. Lifecycle Independent ✅

Repository is a **singleton object** (not tied to Activity lifecycle):
- Survives Activity destruction
- Works when app is backgrounded
- Accessible from anywhere

### 4. Clean Separation of Concerns ✅

- **Repository**: Holds state, provides API
- **ViewModel**: Observes Repository, handles business logic
- **UI**: Observes ViewModel, displays data

## Migration Strategy

### Phase 1: Create Repository (No Breaking Changes)

1. Create `RoomRepository.kt` with StateFlows
2. Keep existing `AppViewModel` state (don't remove yet)
3. Update Repository **in addition to** updating ViewModel state

```kotlin
// During migration - update both
fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
    // Update existing ViewModel state (for compatibility)
    timelineEvents = parseEvents(syncJson)
    
    // ALSO update Repository (new pattern)
    RoomRepository.updateTimeline(roomId, parseEvents(syncJson))
}
```

### Phase 2: Migrate ViewModels to Observe Repository

1. Change ViewModels to observe Repository instead of holding state
2. Remove local state variables
3. Test thoroughly

### Phase 3: Remove Old State Management

1. Remove all local state from ViewModels
2. All state now lives in Repository
3. Clean up any duplicate code

## StateFlow vs MutableStateFlow

### MutableStateFlow (Private)
```kotlin
private val _timelineEvents = MutableStateFlow<...>(...)
```
- **Private**: Only Repository can modify
- **Mutable**: Can be updated

### StateFlow (Public)
```kotlin
val timelineEvents: StateFlow<...> = _timelineEvents.asStateFlow()
```
- **Public**: ViewModels can observe
- **Read-only**: Cannot be modified directly
- **Immutable**: Prevents accidental modifications

This ensures **only Repository can modify state**, maintaining data integrity!

## Comparison: Before vs After

### Before (Current Architecture)

```
MainActivity → AppViewModel A → timelineEvents (Instance A)
BubbleActivity → AppViewModel B → timelineEvents (Instance B)

sync_complete → Updates AppViewModel A only
Result: BubbleActivity doesn't see updates ❌
```

### After (Repository Pattern)

```
MainActivity → AppViewModel A → Observes → RoomRepository
BubbleActivity → AppViewModel B → Observes → RoomRepository

sync_complete → Updates RoomRepository
Result: Both Activities see updates automatically ✅
```

## Real-World Example: WhatsApp/Telegram

Think of how messaging apps work:
- You open a chat in the main app
- You also open the same chat in a bubble/notification
- Both show the same messages
- When a new message arrives, both update simultaneously

This is exactly what Repository Pattern enables!

## Summary

**Repository Pattern** = Centralized state store that:
- ✅ Holds all app state in one place
- ✅ Automatically notifies all observers
- ✅ Works with multiple Activities/ViewModels
- ✅ Provides single source of truth
- ✅ Enables real-time synchronization

**Key Concept**: ViewModels **observe** Repository instead of **holding** state. When Repository updates, all observers automatically receive the new data!

