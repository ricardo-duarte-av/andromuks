# Repository Pattern and Database Interaction

## Current Architecture Overview

Your app has **three layers** of data storage:

1. **In-Memory (RAM)**: `RoomTimelineCache` - Fast access, lost on app restart
2. **On-Disk Database**: `AndromuksDatabase` (Room/SQLite) - Persistent, survives app restart
3. **Backend (WebSocket)**: Real-time `sync_complete` messages - Source of truth

### Current Flow

```
sync_complete arrives
    ↓
AppViewModel.updateRoomsFromSyncJsonAsync()
    ↓
    ├─→ RoomTimelineCache.addEventsFromSync()  (RAM cache)
    └─→ SyncIngestor.ingestSyncComplete()      (Database persistence)
```

## How Repository Pattern Fits In

The Repository becomes the **single source of truth** that coordinates all three layers:

```
┌─────────────────────────────────────────────────────────┐
│              RoomRepository (Singleton)                 │
│  ┌───────────────────────────────────────────────────┐  │
│  │  StateFlow<Map<String, List<TimelineEvent>>>      │  │
│  │  (In-Memory State - Fast Access)                  │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↕                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │  RoomTimelineCache (RAM Cache)                    │  │
│  │  - Fast access for instant rendering              │  │
│  │  - Lost on app restart                            │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↕                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │  AndromuksDatabase (SQLite)                       │  │
│  │  - Persistent storage                             │  │
│  │  - Survives app restart                            │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↕                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │  Backend (WebSocket sync_complete)                │  │
│  │  - Source of truth                                │  │
│  │  - Real-time updates                              │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## Complete Repository Implementation

### 1. Repository Structure

```kotlin
// RoomRepository.kt
object RoomRepository {
    // ========== IN-MEMORY STATE (StateFlow) ==========
    // This is what ViewModels observe
    private val _timelineEvents = MutableStateFlow<Map<String, List<TimelineEvent>>>(emptyMap())
    val timelineEvents: StateFlow<Map<String, List<TimelineEvent>>> = _timelineEvents.asStateFlow()
    
    // ========== DEPENDENCIES ==========
    private var context: Context? = null
    private val database: AndromuksDatabase? get() = context?.let { AndromuksDatabase.getInstance(it) }
    private val syncIngestor: SyncIngestor? get() = context?.let { SyncIngestor(it) }
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
    }
    
    // ========== UPDATE FROM sync_complete ==========
    /**
     * Updates Repository from sync_complete message
     * This is called by AppViewModel when sync_complete arrives
     */
    suspend fun updateFromSyncComplete(
        syncJson: JSONObject,
        requestId: Int,
        runId: String,
        memberMap: Map<String, MemberProfile>
    ) = withContext(Dispatchers.IO) {
        val data = syncJson.optJSONObject("data") ?: return@withContext
        val rooms = data.optJSONObject("rooms") ?: return@withContext
        
        // Process each room in sync_complete
        rooms.keys().forEach { roomId ->
            val roomData = rooms.optJSONObject(roomId) ?: return@forEach
            val eventsArray = roomData.optJSONArray("events") ?: return@forEach
            
            // 1. Parse events from JSON
            val events = parseEventsFromSync(eventsArray, memberMap)
            
            // 2. Update RAM cache (RoomTimelineCache)
            RoomTimelineCache.addEventsFromSync(roomId, eventsArray, memberMap)
            
            // 3. Persist to database (SyncIngestor)
            syncIngestor?.let { ingestor ->
                // Ingest this room's data to database
                ingestor.ingestSyncComplete(syncJson, requestId, runId)
            }
            
            // 4. Update Repository StateFlow (notifies all observers)
            updateTimelineInRepository(roomId, events)
        }
    }
    
    /**
     * Updates Repository's StateFlow with new events
     * This automatically notifies all ViewModels observing timelineEvents
     */
    private fun updateTimelineInRepository(roomId: String, newEvents: List<TimelineEvent>) {
        _timelineEvents.update { currentMap ->
            val existingEvents = currentMap[roomId] ?: emptyList()
            
            // Merge: Add new events, deduplicate by eventId
            val existingEventIds = existingEvents.map { it.eventId }.toSet()
            val uniqueNewEvents = newEvents.filter { it.eventId !in existingEventIds }
            
            if (uniqueNewEvents.isEmpty()) {
                currentMap // No new events
            } else {
                // Append new events and sort by timestamp
                val merged = (existingEvents + uniqueNewEvents)
                    .sortedBy { it.timestamp }
                currentMap + (roomId to merged)
            }
        }
    }
    
    // ========== LOAD FROM DATABASE ==========
    /**
     * Loads events from database when opening a room
     * This is called when:
     * - Opening a room for the first time
     * - App restarts and needs to restore state
     * - Loading more historical messages (pagination)
     */
    suspend fun loadEventsFromDatabase(
        roomId: String,
        limit: Int = 100,
        beforeRowId: Long = -1
    ): List<TimelineEvent> = withContext(Dispatchers.IO) {
        val db = database ?: return@withContext emptyList()
        val eventDao = db.eventDao()
        
        // Query database for events
        val dbEvents = if (beforeRowId > 0) {
            // Load older events (pagination)
            eventDao.getEventsBeforeRowId(roomId, beforeRowId, limit)
        } else {
            // Load latest events
            eventDao.getLatestEvents(roomId, limit)
        }
        
        // Convert database entities to TimelineEvent objects
        val timelineEvents = dbEvents.map { entity ->
            TimelineEvent.fromEntity(entity)
        }
        
        // Update Repository StateFlow with loaded events
        if (timelineEvents.isNotEmpty()) {
            updateTimelineInRepository(roomId, timelineEvents)
            
            // Also update RAM cache for fast access
            RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, timelineEvents)
        }
        
        timelineEvents
    }
    
    // ========== LOAD FROM BACKEND (Pagination) ==========
    /**
     * Loads more events from backend via paginate command
     * This is called when:
     * - User scrolls to top (load older messages)
     * - Cache is insufficient (need more events)
     */
    suspend fun loadEventsFromBackend(
        roomId: String,
        beforeRowId: Long = -1,
        limit: Int = 100,
        webSocketCallback: (String, Int, Map<String, Any>) -> Boolean
    ): List<TimelineEvent> = withContext(Dispatchers.IO) {
        // Send paginate command via WebSocket
        val requestId = generateRequestId()
        val success = webSocketCallback("paginate", requestId, mapOf(
            "room_id" to roomId,
            "max_timeline_id" to beforeRowId,
            "limit" to limit,
            "reset" to false
        ))
        
        if (!success) {
            Log.w("RoomRepository", "Failed to send paginate command")
            return@withContext emptyList()
        }
        
        // Wait for response (this would be handled by AppViewModel's response handler)
        // The response will call updateFromPaginateResponse() below
        emptyList() // Temporary - actual events come via response handler
    }
    
    /**
     * Updates Repository from paginate response
     * Called by AppViewModel when paginate response arrives
     */
    suspend fun updateFromPaginateResponse(
        roomId: String,
        events: List<TimelineEvent>
    ) = withContext(Dispatchers.IO) {
        // 1. Persist to database
        val db = database ?: return@withContext
        val eventDao = db.eventDao()
        
        // Save events to database
        val entities = events.map { event ->
            EventEntity.fromTimelineEvent(event, roomId)
        }
        eventDao.insertAll(entities)
        
        // 2. Update RAM cache
        RoomTimelineCache.mergePaginatedEvents(roomId, events)
        
        // 3. Update Repository StateFlow
        updateTimelineInRepository(roomId, events)
    }
    
    // ========== HELPER FUNCTIONS ==========
    private fun parseEventsFromSync(
        eventsArray: JSONArray,
        memberMap: Map<String, MemberProfile>
    ): List<TimelineEvent> {
        // Same parsing logic as RoomTimelineCache.parseEventsFromArray()
        val events = mutableListOf<TimelineEvent>()
        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i) ?: continue
            try {
                val event = TimelineEvent.fromJson(eventJson)
                if (shouldCacheEvent(event)) {
                    events.add(event)
                }
            } catch (e: Exception) {
                Log.w("RoomRepository", "Failed to parse event: ${e.message}")
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
}
```

## Data Flow: Complete Picture

### Scenario 1: sync_complete Arrives

```
1. WebSocket receives sync_complete
   ↓
2. NetworkUtils routes to AppViewModel
   ↓
3. AppViewModel.updateRoomsFromSyncJsonAsync()
   ↓
4. RoomRepository.updateFromSyncComplete()
   ├─→ Parse events from JSON
   ├─→ RoomTimelineCache.addEventsFromSync()     [RAM Cache]
   ├─→ SyncIngestor.ingestSyncComplete()         [Database]
   └─→ Repository._timelineEvents.update()       [StateFlow]
       ↓
5. All ViewModels observing Repository automatically get update!
   ├─→ MainActivity's AppViewModel → UI updates ✅
   └─→ BubbleActivity's AppViewModel → UI updates ✅
```

**Answer to your question**: Yes, `sync_complete` still updates the database exactly as it does now! The Repository coordinates the update to all three layers (RAM cache, Database, and StateFlow).

### Scenario 2: Opening a Room (Load from Database)

```
1. User opens room
   ↓
2. AppViewModel.navigateToRoom(roomId)
   ↓
3. RoomRepository.loadEventsFromDatabase(roomId)
   ├─→ Query AndromuksDatabase for events
   ├─→ Convert database entities to TimelineEvent
   ├─→ RoomTimelineCache.seedCacheWithPaginatedEvents()  [RAM Cache]
   └─→ Repository._timelineEvents.update()               [StateFlow]
       ↓
4. ViewModel observes Repository → UI shows events immediately!
```

**Answer to your question**: Yes, events loaded from database are added to Repository! The Repository becomes the single source of truth, whether data comes from:
- Database (on app start)
- Backend (pagination)
- sync_complete (real-time updates)

### Scenario 3: Loading More Events (Pagination)

```
1. User scrolls to top (load older messages)
   ↓
2. AppViewModel.loadOlderMessages(roomId)
   ↓
3. RoomRepository.loadEventsFromBackend(roomId, beforeRowId)
   ├─→ Send paginate command via WebSocket
   └─→ Wait for response...
       ↓
4. Paginate response arrives
   ↓
5. AppViewModel.handlePaginateResponse()
   ↓
6. RoomRepository.updateFromPaginateResponse(roomId, events)
   ├─→ Save to database (EventDao.insertAll())
   ├─→ RoomTimelineCache.mergePaginatedEvents()  [RAM Cache]
   └─→ Repository._timelineEvents.update()      [StateFlow]
       ↓
7. All ViewModels automatically see new events!
```

**Answer to your question**: Yes, events from pagination are added to Repository! They're:
1. Saved to database
2. Added to RAM cache
3. Merged into Repository's StateFlow
4. Automatically visible to all observing ViewModels

## Key Points

### 1. Repository Coordinates All Layers

The Repository doesn't replace the database or cache - it **coordinates** them:

```kotlin
fun updateFromSyncComplete(...) {
    // 1. Update RAM cache (fast access)
    RoomTimelineCache.addEventsFromSync(...)
    
    // 2. Persist to database (survives restart)
    SyncIngestor.ingestSyncComplete(...)
    
    // 3. Update Repository StateFlow (notifies ViewModels)
    _timelineEvents.update(...)
}
```

### 2. Database Still Persists Everything

The database layer remains unchanged:
- `SyncIngestor.ingestSyncComplete()` still saves to database
- `EventDao` still stores events
- Database queries still work the same way

**The Repository just coordinates the flow**, it doesn't replace database persistence!

### 3. Loading Strategy (Priority Order)

When opening a room, Repository tries multiple sources:

```kotlin
suspend fun loadRoomTimeline(roomId: String): List<TimelineEvent> {
    // 1. Try RAM cache first (fastest)
    val cached = RoomTimelineCache.getCachedEvents(roomId)
    if (cached != null && cached.size >= 100) {
        updateTimelineInRepository(roomId, cached)
        return cached
    }
    
    // 2. Try database (persistent, but slower)
    val dbEvents = loadEventsFromDatabase(roomId)
    if (dbEvents.isNotEmpty()) {
        return dbEvents
    }
    
    // 3. Fall back to backend (slowest, but always works)
    return loadEventsFromBackend(roomId)
}
```

### 4. All Updates Go Through Repository

Whether data comes from:
- ✅ **sync_complete** → `RoomRepository.updateFromSyncComplete()`
- ✅ **Database load** → `RoomRepository.loadEventsFromDatabase()`
- ✅ **Pagination** → `RoomRepository.updateFromPaginateResponse()`
- ✅ **send_complete** → `RoomRepository.updateFromSendComplete()`

**All updates flow through Repository**, ensuring:
- Single source of truth
- Automatic synchronization
- All ViewModels see the same data

## Modified AppViewModel

```kotlin
// AppViewModel.kt (MODIFIED)
class AppViewModel : ViewModel() {
    // Observe Repository instead of holding state
    val timelineEvents: StateFlow<List<TimelineEvent>> = combine(
        RoomRepository.timelineEvents,
        RoomRepository.currentRoomId
    ) { timelineMap, currentRoomId ->
        timelineMap[currentRoomId] ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    // When sync_complete arrives, update Repository
    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            val memberMap = getMemberMapForSync(syncJson)
            val runId = WebSocketService.getCurrentRunId()
            val requestId = extractRequestId(syncJson)
            
            // Update Repository (which handles DB persistence)
            RoomRepository.updateFromSyncComplete(
                syncJson = syncJson,
                requestId = requestId,
                runId = runId,
                memberMap = memberMap
            )
            
            // Repository automatically:
            // 1. Updates RAM cache
            // 2. Saves to database
            // 3. Updates StateFlow (which this ViewModel observes)
        }
    }
    
    // When opening a room, load from Repository
    fun navigateToRoom(roomId: String) {
        viewModelScope.launch {
            // Set current room in Repository
            RoomRepository.setCurrentRoom(roomId)
            
            // Load events (Repository tries cache → DB → backend)
            val events = RoomRepository.loadRoomTimeline(roomId)
            
            // Events automatically appear in timelineEvents StateFlow!
        }
    }
    
    // When pagination response arrives, update Repository
    fun handlePaginateResponse(requestId: Int, events: List<TimelineEvent>) {
        val roomId = paginateRequests.remove(requestId) ?: return
        
        viewModelScope.launch(Dispatchers.IO) {
            // Update Repository (which handles DB persistence)
            RoomRepository.updateFromPaginateResponse(roomId, events)
            
            // Repository automatically:
            // 1. Saves to database
            // 2. Updates RAM cache
            // 3. Updates StateFlow (which this ViewModel observes)
        }
    }
}
```

## Summary

### Your Questions Answered

**Q: How does Repository interact with on-disk DB?**
- ✅ Repository **coordinates** database operations
- ✅ Database persistence still works exactly as before
- ✅ `SyncIngestor.ingestSyncComplete()` still saves to database
- ✅ Repository calls database functions when needed

**Q: When loading more events from DB/Backend, will they be added to Repository?**
- ✅ **Yes!** All data sources update Repository:
  - Database loads → `RoomRepository.loadEventsFromDatabase()`
  - Backend pagination → `RoomRepository.updateFromPaginateResponse()`
  - sync_complete → `RoomRepository.updateFromSyncComplete()`

**Q: Will sync_complete still update the on-disk DB as it does now?**
- ✅ **Yes!** The flow is:
  1. sync_complete arrives
  2. Repository calls `SyncIngestor.ingestSyncComplete()`
  3. Database is updated (same as before)
  4. Repository StateFlow is updated
  5. All ViewModels get the update

### Architecture Benefits

1. **Single Source of Truth**: Repository holds state, coordinates all layers
2. **Automatic Synchronization**: All ViewModels see updates automatically
3. **Database Persistence**: Still works exactly as before
4. **Multi-Layer Caching**: RAM cache + Database + Repository StateFlow
5. **Bubble Support**: Multiple Activities observe the same Repository

The Repository pattern **enhances** your existing architecture without replacing it!

