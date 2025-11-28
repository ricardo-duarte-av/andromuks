# WebSocket Architecture Analysis

## Current Architecture

### Facts

1. **Primary AppViewModel**:
   - Created by `MainActivity` (calls `markAsPrimaryInstance()`)
   - Responsible for creating WebSocket connection
   - Registers reconnection callbacks with `WebSocketService`
   - Can be destroyed by Android (even with foreground service running)

2. **Foreground Service**:
   - `WebSocketService` maintains the WebSocket connection
   - Shows persistent notification to prevent Android from killing the app
   - **BUT**: Android can still destroy the primary AppViewModel even if service is running
   - Service holds reference to WebSocket, but callbacks are registered to primary AppViewModel

3. **Secondary AppViewModels**:
   - Created by: `ShortcutActivity`, `ChatBubbleActivity`, notifications (via `MainActivity`)
   - Call `attachToExistingWebSocketIfAvailable()` to attach to existing WebSocket
   - **Do NOT** check if primary is healthy
   - **Do NOT** jumpstart primary if it's destroyed
   - **Problem**: If primary is destroyed, secondary instances can't create new connection

## Current Code Analysis

### Secondary AppViewModels Behavior

#### ShortcutActivity
```kotlin
appViewModel.attachToExistingWebSocketIfAvailable()
// If no WebSocket exists, it just logs and continues
// No check for primary health
// No jumpstart capability
```

#### ChatBubbleActivity
```kotlin
appViewModel.attachToExistingWebSocketIfAvailable()
// Same behavior - just attaches if available
// No primary health check
```

#### MainActivity (from notification)
```kotlin
viewModel.attachToExistingWebSocketIfAvailable()
// Creates NEW primary instance
// But if old primary was destroyed, old callbacks might be stale
```

### `attachToExistingWebSocketIfAvailable()` Implementation

```kotlin
fun attachToExistingWebSocketIfAvailable() {
    val existingWebSocket = WebSocketService.getWebSocket()
    if (existingWebSocket != null) {
        // Attach to existing WebSocket
        webSocket = existingWebSocket
        WebSocketService.registerReceiveCallback(viewModelId, this)
    } else {
        // No WebSocket exists - just logs and continues
        // NO attempt to check primary health
        // NO attempt to jumpstart connection
    }
}
```

### Primary Instance Tracking

```kotlin
// WebSocketService tracks primary via primaryViewModelId
private var primaryViewModelId: String? = null

fun getPrimaryViewModelId(): String? = primaryViewModelId
fun isPrimaryInstance(viewModelId: String): Boolean {
    return primaryViewModelId == viewModelId
}
```

**Problem**: If primary AppViewModel is destroyed:
- `primaryViewModelId` still points to destroyed instance
- Reconnection callbacks are lost (they were in the destroyed AppViewModel)
- Secondary instances can't create new connection (only primary can)
- WebSocket might still be connected, but no callbacks to handle messages

## Answers to Your Questions

### Question 1: Would an Application-scoped Singleton help?

**YES, but with caveats:**

#### Benefits:
1. **Persistent State**: Singleton survives AppViewModel destruction
2. **Centralized Management**: Single point of control for WebSocket
3. **Callback Persistence**: Callbacks wouldn't be lost when primary is destroyed
4. **Easier Recovery**: Secondary instances could check singleton state

#### Challenges:
1. **ViewModel Lifecycle**: AppViewModels are tied to Activity lifecycle
   - Singleton would need to coordinate with ViewModels
   - State synchronization becomes complex
2. **Memory Leaks**: Singleton holding references to destroyed ViewModels
3. **State Management**: Who owns the state? Singleton or ViewModels?
4. **Testing**: Harder to test with singleton

#### Better Alternative: **Hybrid Approach**
- Keep WebSocket in `WebSocketService` (already singleton-like via service)
- Move callback registration to service (not AppViewModel)
- Service maintains list of active ViewModels
- When primary is destroyed, service can promote a secondary to primary

### Question 2: Do secondary AppViewModels check primary health and jumpstart?

**NO - Current Implementation:**

#### What They Do:
1. ✅ Call `attachToExistingWebSocketIfAvailable()`
2. ✅ Register to receive messages if WebSocket exists
3. ❌ **Do NOT** check if primary is healthy
4. ❌ **Do NOT** check if primary callbacks are registered
5. ❌ **Do NOT** attempt to jumpstart primary
6. ❌ **Do NOT** promote themselves to primary if primary is missing

#### What Should Happen:

**Option A: Health Check + Jumpstart**
```kotlin
fun attachToExistingWebSocketIfAvailable() {
    val existingWebSocket = WebSocketService.getWebSocket()
    val primaryId = WebSocketService.getPrimaryViewModelId()
    val hasPrimaryCallbacks = WebSocketService.hasPrimaryCallbacks()
    
    if (existingWebSocket != null) {
        // WebSocket exists - attach to it
        webSocket = existingWebSocket
        WebSocketService.registerReceiveCallback(viewModelId, this)
        
        // Check if primary is healthy
        if (primaryId == null || !hasPrimaryCallbacks) {
            // Primary is missing or unhealthy - promote this instance
            if (isPrimaryInstance()) {
                // This is MainActivity - should be primary
                markAsPrimaryInstance()
            } else {
                // Secondary instance - log warning
                android.util.Log.w("Andromuks", "Primary instance missing but WebSocket exists")
            }
        }
    } else {
        // No WebSocket exists
        if (isPrimaryInstance()) {
            // This should be primary - create connection
            initializeWebSocketConnection(homeserverUrl, authToken)
        } else {
            // Secondary instance - wait for primary or timeout
            android.util.Log.w("Andromuks", "No WebSocket and not primary - waiting...")
        }
    }
}
```

**Option B: Service-Managed Primary**
- Service tracks which ViewModels are alive
- When primary is destroyed, service promotes next available ViewModel
- Secondary instances just attach and service handles promotion

## Recommendations

### Short-term Fix (Minimal Changes):

1. **Add Primary Health Check in Secondary Instances**:
   ```kotlin
   // In ShortcutActivity, ChatBubbleActivity, etc.
   LaunchedEffect(Unit) {
       val primaryId = WebSocketService.getPrimaryViewModelId()
       val hasCallbacks = WebSocketService.hasPrimaryCallbacks()
       
       if (primaryId == null || !hasCallbacks) {
           // Primary is missing - wait a bit, then check if MainActivity exists
           delay(1000)
           // If still no primary, could show warning or fallback to database
       }
   }
   ```

2. **Add Jumpstart Capability for MainActivity**:
   ```kotlin
   // In MainActivity.onCreate
   if (WebSocketService.getPrimaryViewModelId() == null) {
       // No primary exists - this MainActivity should become primary
       appViewModel.markAsPrimaryInstance()
       // Check if WebSocket exists but no callbacks
       if (WebSocketService.getWebSocket() != null && !WebSocketService.hasPrimaryCallbacks()) {
           // WebSocket exists but primary callbacks missing - re-register
           appViewModel.registerPrimaryCallbacks()
       }
   }
   ```

### Long-term Solution (Better Architecture):

1. **Move Callback Management to Service**:
   - Service maintains list of active ViewModels
   - Service automatically promotes secondary to primary when primary is destroyed
   - ViewModels just register/unregister, service handles promotion

2. **Application-Scoped State Manager**:
   - Create `WebSocketStateManager` singleton
   - Manages WebSocket connection state
   - Coordinates between service and ViewModels
   - Handles primary promotion automatically

3. **Health Monitoring**:
   - Service periodically checks if primary ViewModel is alive
   - If not, promotes next available ViewModel
   - Secondary instances can query service for primary status

## Current Issues

1. **Primary Destruction**: When primary AppViewModel is destroyed:
   - Reconnection callbacks are lost
   - Secondary instances can't create new connections
   - WebSocket might be connected but no message handlers

2. **No Recovery Mechanism**: 
   - Secondary instances don't detect primary is missing
   - No automatic promotion of secondary to primary
   - App gets stuck in broken state

3. **State Inconsistency**:
   - `primaryViewModelId` might point to destroyed instance
   - Service thinks primary exists, but callbacks are gone
   - Secondary instances can't fix this

## Conclusion

**Answer 1**: Application-scoped singleton would help, but better to improve service-based architecture.

**Answer 2**: No, secondary AppViewModels do NOT check primary health or jumpstart. This is a gap in the current implementation.

**Recommended Next Steps**:
1. Add primary health check in secondary instances
2. Add jumpstart capability in MainActivity
3. Consider moving callback management to service for better resilience

