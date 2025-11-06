# Repository Pattern vs WebSocket Callback Issue

## The Two Separate Problems

You're absolutely right! The Repository pattern solves **one problem** but **not the other**. Let me break this down:

### Problem 1: State Synchronization ✅ (Solved by Repository)

**Issue**: Each Activity has its own AppViewModel instance with separate state
- MainActivity's AppViewModel → `timelineEvents` (Instance A)
- BubbleActivity's AppViewModel → `timelineEvents` (Instance B)
- They don't share data!

**Solution**: Repository Pattern
- Single source of truth (Repository)
- All ViewModels observe the same Repository
- ✅ **SOLVED**

### Problem 2: WebSocket Callback Ownership ❌ (NOT Solved by Repository)

**Issue**: WebSocketService has only **one callback slot**

```kotlin
// WebSocketService.kt (CURRENT)
private var webSocketSendCallback: ((String, Int, Map<String, Any>) -> Boolean)? = null

fun setWebSocketSendCallback(callback: ...) {
    webSocketSendCallback = callback  // ← OVERWRITES previous callback!
}
```

**What happens**:
1. MainActivity's AppViewModel sets callback → `webSocketSendCallback = callbackA`
2. BubbleActivity's AppViewModel sets callback → `webSocketSendCallback = callbackB` ❌
3. MainActivity's callback is **lost**!
4. MainActivity can no longer send messages!

**Repository Pattern does NOT solve this** because:
- Repository handles **data/state** (what you receive)
- WebSocket callback handles **commands** (what you send)
- They're separate concerns!

## The Complete Solution: Two-Part Fix

You need **BOTH** solutions:

### Part 1: Repository Pattern (State Synchronization) ✅

```kotlin
// RoomRepository.kt
object RoomRepository {
    val timelineEvents: StateFlow<Map<String, List<TimelineEvent>>>
    
    fun updateFromSyncComplete(...) {
        // Updates Repository
        // All ViewModels automatically see update
    }
}
```

**Solves**: 
- ✅ sync_complete updates visible to all Activities
- ✅ Timeline state synchronized
- ✅ Database persistence coordinated

### Part 2: WebSocket Callback Queue (Command Sending) ✅

```kotlin
// WebSocketService.kt (MODIFIED)
companion object {
    // CHANGE: List instead of single callback
    private val webSocketCallbacks = mutableListOf<((String, Int, Map<String, Any>) -> Boolean)>()
    
    /**
     * Add a callback (instead of replacing)
     */
    fun addWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
        synchronized(webSocketCallbacks) {
            if (!webSocketCallbacks.contains(callback)) {
                webSocketCallbacks.add(callback)
                android.util.Log.d("WebSocketService", "Added WebSocket callback (total: ${webSocketCallbacks.size})")
            }
        }
    }
    
    /**
     * Remove a callback (when Activity/ViewModel is destroyed)
     */
    fun removeWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
        synchronized(webSocketCallbacks) {
            webSocketCallbacks.remove(callback)
            android.util.Log.d("WebSocketService", "Removed WebSocket callback (total: ${webSocketCallbacks.size})")
        }
    }
    
    /**
     * Send command via WebSocket (tries all callbacks)
     */
    private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): Boolean {
        synchronized(webSocketCallbacks) {
            // Try each callback until one succeeds
            for (callback in webSocketCallbacks) {
                try {
                    if (callback(command, requestId, data)) {
                        android.util.Log.d("WebSocketService", "WebSocket command sent successfully via callback")
                        return true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in WebSocket callback", e)
                    // Continue to next callback
                }
            }
        }
        
        android.util.Log.w("WebSocketService", "No callback succeeded in sending WebSocket command")
        return false
    }
}
```

**Solves**:
- ✅ Multiple Activities can send commands
- ✅ No callback override issues
- ✅ Both MainActivity and BubbleActivity can send messages

## How They Work Together

### Scenario: Bubble Sends a Message

```
1. User types message in Bubble
   ↓
2. BubbleActivity's AppViewModel.sendMessage()
   ↓
3. AppViewModel.sendWebSocketCommand()
   ↓
4. WebSocketService.sendWebSocketCommand()
   ├─→ Tries callback from MainActivity's AppViewModel
   ├─→ Tries callback from BubbleActivity's AppViewModel ✅ (succeeds)
   └─→ Message sent!
   ↓
5. Backend processes message
   ↓
6. sync_complete arrives with new message
   ↓
7. NetworkUtils → AppViewModel.updateRoomsFromSyncJsonAsync()
   ↓
8. RoomRepository.updateFromSyncComplete()
   ├─→ Updates Repository StateFlow
   ├─→ Saves to database
   └─→ Updates RAM cache
   ↓
9. All ViewModels observing Repository get update!
   ├─→ MainActivity's AppViewModel → UI updates ✅
   └─→ BubbleActivity's AppViewModel → UI updates ✅
```

## Modified AppViewModel for Callback Queue

```kotlin
// AppViewModel.kt (MODIFIED)
class AppViewModel : ViewModel() {
    // Store callback reference for cleanup
    private val webSocketCallback = { command: String, requestId: Int, data: Map<String, Any> ->
        sendWebSocketCommand(command, requestId, data) == WebSocketResult.SUCCESS
    }
    
    init {
        // Register callback when ViewModel is created
        WebSocketService.addWebSocketSendCallback(webSocketCallback)
    }
    
    override fun onCleared() {
        super.onCleared()
        // Unregister callback when ViewModel is destroyed
        WebSocketService.removeWebSocketSendCallback(webSocketCallback)
    }
    
    // Rest of AppViewModel...
    // Observe Repository for state
    val timelineEvents: StateFlow<List<TimelineEvent>> = combine(
        RoomRepository.timelineEvents,
        RoomRepository.currentRoomId
    ) { timelineMap, currentRoomId ->
        timelineMap[currentRoomId] ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
```

## Complete Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    WebSocketService                     │
│  ┌───────────────────────────────────────────────────┐  │
│  │  webSocketCallbacks: List<Callback>               │  │
│  │  - MainActivity's AppViewModel callback          │  │
│  │  - BubbleActivity's AppViewModel callback        │  │
│  │  (Both can send commands!)                        │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                        ↕
┌─────────────────────────────────────────────────────────┐
│                    RoomRepository                       │
│  ┌───────────────────────────────────────────────────┐  │
│  │  timelineEvents: StateFlow                       │  │
│  │  (Single source of truth)                         │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                        ↕
        ┌───────────────────────┐
        │                       │
        ↓                       ↓
┌───────────────┐      ┌───────────────┐
│ MainActivity  │      │ BubbleActivity│
│ AppViewModel  │      │ AppViewModel  │
│               │      │               │
│ - Observes    │      │ - Observes    │
│   Repository  │      │   Repository  │
│               │      │               │
│ - Registers   │      │ - Registers   │
│   callback    │      │   callback    │
└───────────────┘      └───────────────┘
```

## Summary

### Repository Pattern Solves ✅
- ✅ State synchronization between Activities
- ✅ sync_complete updates visible to all
- ✅ Timeline data shared
- ✅ Database persistence coordinated

### Repository Pattern Does NOT Solve ❌
- ❌ WebSocket callback ownership
- ❌ Multiple Activities sending commands
- ❌ Callback override issues

### Complete Solution Requires Both ✅

1. **Repository Pattern** → Solves state synchronization
2. **WebSocket Callback Queue** → Solves command sending

**Together**, they enable:
- ✅ Multiple Activities can send commands (Callback Queue)
- ✅ All Activities see the same data (Repository)
- ✅ Real-time updates work everywhere (Both)

## Implementation Order

### Step 1: Implement Callback Queue (Quick Fix)
- Modify `WebSocketService.setWebSocketSendCallback()` → `addWebSocketSendCallback()`
- Update AppViewModel to register/unregister callbacks
- **Result**: Both Activities can send commands ✅

### Step 2: Implement Repository Pattern (Long-term)
- Create `RoomRepository`
- Migrate state from AppViewModel to Repository
- Update AppViewModel to observe Repository
- **Result**: All Activities see same data ✅

### Step 3: Test Together
- Open MainActivity and BubbleActivity simultaneously
- Send message from Bubble → Should appear in both
- Receive sync_complete → Should update both
- **Result**: Everything works! ✅

## Key Takeaway

**Repository Pattern** and **WebSocket Callback Queue** solve **different problems**:

- **Repository** = "How do we share **data** between Activities?"
- **Callback Queue** = "How do we share **command sending** between Activities?"

You need **both** for a complete Bubble implementation!

