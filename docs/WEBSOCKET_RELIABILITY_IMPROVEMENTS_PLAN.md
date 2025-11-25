# WebSocket Reliability Improvements - Implementation Plan

## Overview

This document outlines the implementation plan for 5 key improvements to enhance WebSocket reliability and availability:

1. **Network Change Handling** - Proactive reconnection on network changes
2. **Error Handling & Recovery** - Comprehensive error handling with specific recovery strategies
3. **Message Reliability** - Outgoing message queue with acknowledgment tracking
4. **Service Lifecycle & Reliability** - Improved service restart and callback management
5. **Multiple AppViewModel Instances** - Prevention and handling of duplicate registrations

---

## Phase 1: Multiple AppViewModel Instances (Foundation)

**Priority: HIGH** - Must be done first to prevent state corruption

### 1.1 Add Primary Instance Tracking

**Files to modify:**
- `WebSocketService.kt`

**Changes:**
- Add `primaryViewModelId: String?` to track the primary AppViewModel instance
- Add `primaryReconnectionCallback: ((String) -> Unit)?` separate from generic `reconnectionCallback`
- Add `primaryOfflineModeCallback: ((Boolean) -> Unit)?` separate from generic callback
- Add `primaryActivityLogCallback: ((String, String?) -> Unit)?` separate from generic callback

**Rationale:** 
- Prevents multiple AppViewModels from interfering with each other
- Ensures only one instance manages WebSocket lifecycle
- Allows secondary instances to receive messages but not control connection

### 1.2 Update Callback Registration

**Files to modify:**
- `WebSocketService.kt` - `setReconnectionCallback()`, `setOfflineModeCallback()`, `setActivityLogCallback()`

**Changes:**
- Modify `setReconnectionCallback()` to:
  - Check if a primary instance is already registered
  - If yes, log warning and reject (or replace if same viewModelId)
  - Store `viewModelId` parameter to identify the primary instance
  - Store callback as `primaryReconnectionCallback`
- Apply same pattern to `setOfflineModeCallback()` and `setActivityLogCallback()`
- Add `clearPrimaryCallbacks(viewModelId: String)` to allow cleanup

**Rationale:**
- Prevents multiple callbacks from being registered
- Ensures only one AppViewModel controls reconnection logic
- Allows proper cleanup when primary instance is destroyed

### 1.3 Add Instance Validation

**Files to modify:**
- `AppViewModel.kt` - `setWebSocket()`, `onCleared()`

**Changes:**
- In `setWebSocket()`, pass `viewModelId` to `setReconnectionCallback()` calls
- In `onCleared()`, call `WebSocketService.clearPrimaryCallbacks(viewModelId)` if `instanceRole == InstanceRole.PRIMARY`
- Add validation in `setWebSocket()` to check if another primary instance is already registered

**Rationale:**
- Ensures proper cleanup when primary instance is destroyed
- Prevents orphaned callbacks from dead instances

### 1.4 Update Callback Invocations

**Files to modify:**
- `WebSocketService.kt` - All places that invoke `reconnectionCallback`, `offlineModeCallback`, `activityLogCallback`

**Changes:**
- Replace `reconnectionCallback?.invoke()` with `primaryReconnectionCallback?.invoke()`
- Replace `offlineModeCallback?.invoke()` with `primaryOfflineModeCallback?.invoke()`
- Replace `activityLogCallback?.invoke()` with `primaryActivityLogCallback?.invoke()`
- Add null checks and logging when callbacks are missing

**Rationale:**
- Ensures only primary instance receives lifecycle callbacks
- Prevents multiple instances from reacting to the same events

---

## Phase 2: Service Lifecycle & Reliability

**Priority: HIGH** - Improves service restart resilience

### 2.1 Add Pending Reconnection Queue

**Files to modify:**
- `WebSocketService.kt`

**Changes:**
- Add `pendingReconnectionReasons: MutableList<String>` to queue reconnection requests
- Add `pendingReconnectionLock: Any` for thread safety
- Modify `scheduleReconnection()` to:
  - If `reconnectionCallback == null`, add reason to `pendingReconnectionReasons` instead of returning
  - Log that reconnection is queued
- Add `processPendingReconnections()` function that:
  - Checks if `reconnectionCallback != null`
  - Processes all queued reasons
  - Clears the queue
- Call `processPendingReconnections()` in `setReconnectionCallback()` after callback is set

**Rationale:**
- Allows reconnection requests to be queued when service restarts before AppViewModel registers
- Ensures no reconnection requests are lost during service restart
- Provides resilience when service and app lifecycle are out of sync

### 2.2 Improve Service Restart Detection

**Files to modify:**
- `WebSocketService.kt` - `onStartCommand()`

**Changes:**
- Add `serviceStartTime: Long` to track when service started
- In `onStartCommand()`, check if service was just restarted (instance was null before)
- If restarted and `reconnectionCallback == null`:
  - Set connection state to `DISCONNECTED` (don't assume connection is alive)
  - Log that service is waiting for AppViewModel
  - Don't attempt reconnection yet (wait for callback registration)
- If restarted and `reconnectionCallback != null`:
  - Check if WebSocket is actually connected (not just state)
  - If not connected, trigger reconnection via callback
  - Process any pending reconnection requests

**Rationale:**
- Handles service restart scenarios more gracefully
- Prevents false assumptions about connection state after restart
- Ensures reconnection happens when AppViewModel is available

### 2.3 Add Callback Health Monitoring

**Files to modify:**
- `WebSocketService.kt`

**Changes:**
- Add `lastCallbackCheckTime: Long` to track when callbacks were last verified
- Add periodic check (every 30 seconds) in state corruption job:
  - Verify `primaryReconnectionCallback != null` if connection is not `CONNECTED`
  - If callback is null and connection is not `CONNECTED`, log warning
  - Update notification to show "Waiting for app..." if callback is missing
- Add `validateCallbacks()` function that checks all primary callbacks are set

**Rationale:**
- Detects when callbacks are lost (e.g., AppViewModel destroyed)
- Provides visibility into service health
- Helps diagnose "Waiting for app..." scenarios

---

## Phase 3: Network Change Handling

**Priority: MEDIUM** - Improves reconnection speed on network changes

### 3.1 Create NetworkMonitor Integration

**Files to create/modify:**
- `utils/NetworkMonitor.kt` (create if doesn't exist)
- `WebSocketService.kt` - Add network monitoring

**Changes:**
- Create `NetworkMonitor` class if it doesn't exist:
  - Use `ConnectivityManager.NetworkCallback` to listen for network changes
  - Track `onAvailable()`, `onLost()`, `onCapabilitiesChanged()`
  - Filter for `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`
- In `WebSocketService.onCreate()`:
  - Create `NetworkMonitor` instance
  - Register callbacks:
    - `onNetworkAvailable`: Trigger reconnection if disconnected
    - `onNetworkLost`: Mark connection as degraded, don't reconnect yet
    - `onCapabilitiesChanged`: Check if network gained internet access, trigger reconnection if needed
- Store `NetworkMonitor` instance in service

**Rationale:**
- Detects network changes immediately instead of waiting for ping timeout
- Reduces reconnection delay from 20-65 seconds to 1-3 seconds
- Handles WiFi ↔ mobile data transitions gracefully

### 3.2 Add Network Type Change Detection

**Files to modify:**
- `WebSocketService.kt`

**Changes:**
- Add `lastNetworkType: NetworkType` to track previous network type
- In `NetworkMonitor.onCapabilitiesChanged()`:
  - Detect network type change (WiFi ↔ mobile ↔ none)
  - If network type changed and connection is `CONNECTED`:
    - Log network type change
    - Optionally trigger proactive reconnection (configurable)
  - Update `currentNetworkType` and `lastNetworkType`
- Add `shouldReconnectOnNetworkChange(): Boolean` that:
  - Returns `true` if network type changed from mobile to WiFi (better network)
  - Returns `false` if network type changed from WiFi to mobile (worse network, but still works)
  - Returns `true` if network was lost and now available

**Rationale:**
- Proactively reconnects when network improves
- Avoids unnecessary reconnections when network degrades but still works
- Provides better user experience on network transitions

### 3.3 Add Network Validation Before Reconnection

**Files to modify:**
- `WebSocketService.kt` - `scheduleReconnection()`

**Changes:**
- Before triggering reconnection from network change:
  - Check `NET_CAPABILITY_VALIDATED` to ensure network has internet access
  - Wait up to 2 seconds for network validation to complete
  - If validation doesn't complete, still attempt reconnection (might be slow network)
- Add `waitForNetworkValidation(timeoutMs: Long): Boolean` function
- Log network validation status

**Rationale:**
- Prevents reconnection attempts on captive portals or networks without internet
- Reduces failed reconnection attempts
- Improves battery efficiency

---

## Phase 4: Error Handling & Recovery

**Priority: MEDIUM** - Improves resilience to various failure scenarios

### 4.1 Add WebSocket Close Code Handling

**Files to modify:**
- `utils/NetworkUtils.kt` - `onClosing()` and `onClosed()`
- `WebSocketService.kt` - Add close code handling

**Changes:**
- In `NetworkUtils.onClosing()`:
  - Extract close code and reason
  - Log close code and reason
  - Pass to `AppViewModel.handleWebSocketClose(code, reason)`
- In `AppViewModel.kt`:
  - Add `handleWebSocketClose(code: Int, reason: String)` function
  - Handle specific close codes:
    - `1000` (Normal): Don't reconnect immediately, wait for ping timeout
    - `1001` (Going Away): Server restarting, reconnect with short delay
    - `1006` (Abnormal): Connection lost, reconnect immediately
    - `1012` (Service Restart): Backend restarting, reconnect with delay
    - `4000-4999` (Application codes): Log and reconnect
- In `WebSocketService.kt`:
  - Add `lastCloseCode: Int?` and `lastCloseReason: String?` to track last close
  - Store close code/reason when connection closes
  - Use close code to determine reconnection strategy

**Rationale:**
- Provides context for why connection closed
- Enables appropriate reconnection strategies based on close reason
- Improves debugging and monitoring

### 4.2 Add DNS Resolution Error Handling

**Files to modify:**
- `utils/NetworkUtils.kt` - `onFailure()`
- `AppViewModel.kt` - `scheduleReconnection()`

**Changes:**
- In `NetworkUtils.onFailure()`:
  - Detect DNS resolution failures (`UnknownHostException`)
  - Detect network unreachable (`SocketException`, `ConnectException`)
  - Pass error type to `AppViewModel.handleConnectionFailure(errorType, error)`
- In `AppViewModel.kt`:
  - Add `handleConnectionFailure(errorType: String, error: Throwable)` function
  - For DNS failures:
    - Use exponential backoff with longer delays (DNS issues are often persistent)
    - Cache last successful DNS resolution
    - Log DNS resolution attempts
  - For network unreachable:
    - Wait for network availability before retrying
    - Don't retry immediately (waste battery)

**Rationale:**
- Handles DNS failures gracefully with appropriate backoff
- Prevents battery drain from rapid retries on network issues
- Provides better error context for debugging

### 4.3 Add TLS/SSL Error Handling

**Files to modify:**
- `utils/NetworkUtils.kt` - `onFailure()`

**Changes:**
- Detect TLS/SSL errors:
  - `javax.net.ssl.SSLException`
  - `java.security.cert.CertificateException`
  - Certificate validation failures
- In `onFailure()`:
  - Check if error is TLS-related
  - Log TLS error details (don't log full certificate)
  - For certificate errors:
    - Don't reconnect automatically (security issue)
    - Trigger user notification or error state
  - For other TLS errors:
    - Reconnect with exponential backoff
    - Log error for debugging

**Rationale:**
- Prevents automatic reconnection on security issues
- Provides user feedback for certificate problems
- Handles transient TLS errors appropriately

### 4.4 Add Rate Limiting Detection

**Files to modify:**
- `utils/NetworkUtils.kt` - `onMessage()` for error responses
- `AppViewModel.kt` - Add rate limit handling

**Changes:**
- In `NetworkUtils.onMessage()`:
  - Detect error responses with rate limit indicators
  - Check for `"rate_limited"` or HTTP 429-like responses
  - Extract `retry_after` if present
- In `AppViewModel.kt`:
  - Add `handleRateLimit(retryAfterSeconds: Int?)` function
  - If `retryAfterSeconds` provided:
    - Wait for that duration before next operation
    - Pause ping/pong if needed
  - If not provided:
    - Use exponential backoff
  - Log rate limit events

**Rationale:**
- Respects backend rate limits
- Prevents further rate limiting
- Provides appropriate backoff based on server guidance

---

## Phase 5: Message Reliability

**Priority: MEDIUM** - Ensures messages are not lost

### 5.1 Enhance Outgoing Message Queue

**Files to modify:**
- `AppViewModel.kt` - `PendingWebSocketOperation` data class and queue management

**Changes:**
- Enhance `PendingWebSocketOperation` data class:
  - Add `messageId: String` (unique identifier for the message)
  - Add `timestamp: Long` (when message was queued)
  - Add `acknowledged: Boolean` (whether response was received)
  - Add `acknowledgmentTimeout: Long` (when to consider message failed)
- Modify queue to be persistent:
  - Save queue to SharedPreferences on each add
  - Load queue from SharedPreferences on app start
  - Limit queue size (e.g., max 100 messages)
  - Remove old messages (>24 hours) on load
- Add `savePendingOperationsToStorage()` and `loadPendingOperationsFromStorage()` functions

**Rationale:**
- Messages survive app crashes and restarts
- Prevents message loss during disconnections
- Provides message durability

### 5.2 Add Message Acknowledgment Tracking

**Files to modify:**
- `AppViewModel.kt` - `sendMessageInternal()`, `sendReplyInternal()`, `markRoomAsReadInternal()`
- `AppViewModel.kt` - Add acknowledgment handler

**Changes:**
- In `sendMessageInternal()`:
  - Generate unique `messageId` (UUID or timestamp-based)
  - Store message in queue with `acknowledged = false`
  - Set `acknowledgmentTimeout = System.currentTimeMillis() + 30000` (30 seconds)
  - Include `messageId` in WebSocket command data
  - Return `WebSocketResult.SUCCESS` only if queued successfully
- In `sendReplyInternal()` and `markRoomAsReadInternal()`:
  - Apply same pattern
- Add `handleMessageAcknowledgment(messageId: String, requestId: Int)` function:
  - Find message in queue by `messageId`
  - Mark as `acknowledged = true`
  - Remove from queue
  - Update UI if needed
- Add acknowledgment timeout check:
  - Periodically (every 10 seconds) check for unacknowledged messages
  - If `acknowledgmentTimeout` passed:
    - Retry message if `retryCount < maxRetryAttempts`
    - Increment `retryCount`
    - Update `acknowledgmentTimeout`
  - If `retryCount >= maxRetryAttempts`:
    - Mark message as failed
    - Notify user (optional)
    - Remove from queue

**Rationale:**
- Ensures messages are actually delivered
- Detects and retries failed messages
- Provides message delivery guarantees

### 5.3 Update Backend Response Handling

**Files to modify:**
- `utils/NetworkUtils.kt` - `onMessage()` for command responses
- `AppViewModel.kt` - Response handlers

**Changes:**
- In `NetworkUtils.onMessage()`:
  - When processing command responses (e.g., `send_message`, `mark_read`):
    - Extract `messageId` from response if present
    - Extract `request_id` from response
    - Call `AppViewModel.handleMessageAcknowledgment(messageId, requestId)`
- In `AppViewModel.kt`:
  - Update `handleMessageAcknowledgment()` to handle both `messageId` and `requestId` matching
  - If `messageId` not found, try matching by `requestId` (backward compatibility)
  - Log acknowledgment events

**Rationale:**
- Matches responses to queued messages
- Handles both new `messageId` and legacy `requestId` matching
- Provides backward compatibility

### 5.4 Add Queue Management on Reconnection

**Files to modify:**
- `AppViewModel.kt` - `retryPendingWebSocketOperations()`

**Changes:**
- Modify `retryPendingWebSocketOperations()`:
  - Only retry messages that are not `acknowledged`
  - Respect `acknowledgmentTimeout` (don't retry if timeout hasn't passed)
  - Retry in order of `timestamp` (oldest first)
  - Limit retry batch size (e.g., 10 at a time) to avoid overwhelming connection
  - Add delay between retries (e.g., 100ms) to avoid rate limiting
- Add `cleanupAcknowledgedMessages()` function:
  - Remove acknowledged messages older than 1 hour
  - Run periodically (every 5 minutes)

**Rationale:**
- Prevents duplicate message sends
- Respects message ordering
- Prevents queue from growing indefinitely

---

## Implementation Order & Dependencies

### Recommended Order:

1. **Phase 1: Multiple AppViewModel Instances** (Week 1)
   - Foundation for all other improvements
   - Prevents state corruption
   - No dependencies

2. **Phase 2: Service Lifecycle & Reliability** (Week 1-2)
   - Depends on Phase 1 (uses primary instance tracking)
   - Improves service restart resilience
   - Enables better error recovery

3. **Phase 3: Network Change Handling** (Week 2)
   - Can be done in parallel with Phase 4
   - Improves reconnection speed
   - No critical dependencies

4. **Phase 4: Error Handling & Recovery** (Week 2-3)
   - Can be done in parallel with Phase 3
   - Improves resilience
   - No critical dependencies

5. **Phase 5: Message Reliability** (Week 3-4)
   - Depends on Phase 2 (uses improved service lifecycle)
   - Most complex feature
   - Requires thorough testing

### Testing Strategy:

**For each phase:**
1. Unit tests for new functions
2. Integration tests for service lifecycle
3. Manual testing for network changes
4. Stress testing for message queue

**Specific test scenarios:**
- Multiple AppViewModel instances: Create/destroy multiple instances, verify only one controls connection
- Service restart: Kill service, verify reconnection queue works
- Network changes: Switch WiFi/mobile, verify immediate reconnection
- Error handling: Simulate various errors, verify appropriate recovery
- Message queue: Send messages, kill app, verify messages are retried

---

## Files Summary

### Files to Create:
- `utils/NetworkMonitor.kt` (if doesn't exist)

### Files to Modify:
- `WebSocketService.kt` (major changes)
- `AppViewModel.kt` (major changes)
- `utils/NetworkUtils.kt` (moderate changes)

### Estimated Lines of Code:
- Phase 1: ~200 lines
- Phase 2: ~150 lines
- Phase 3: ~300 lines
- Phase 4: ~250 lines
- Phase 5: ~400 lines
- **Total: ~1300 lines**

---

## Risk Assessment

### Low Risk:
- Phase 1 (Multiple AppViewModel instances) - Isolated changes, easy to test
- Phase 3 (Network change handling) - Additive feature, doesn't affect existing logic

### Medium Risk:
- Phase 2 (Service lifecycle) - Affects service restart behavior, needs thorough testing
- Phase 4 (Error handling) - Adds new error paths, needs comprehensive error testing

### High Risk:
- Phase 5 (Message reliability) - Complex state management, persistent storage, needs extensive testing

### Mitigation:
- Implement phases incrementally
- Add comprehensive logging for debugging
- Test each phase before moving to next
- Add feature flags to enable/disable new features
- Monitor error rates after deployment

---

## Success Metrics

### Phase 1:
- ✅ Only one AppViewModel can register as primary
- ✅ Secondary instances don't interfere with connection
- ✅ Callbacks are properly cleaned up

### Phase 2:
- ✅ Reconnection requests are queued when service restarts
- ✅ Service waits for AppViewModel before reconnecting
- ✅ No reconnection requests are lost

### Phase 3:
- ✅ Reconnection happens within 3 seconds of network change
- ✅ Network type changes are detected
- ✅ No unnecessary reconnections on network changes

### Phase 4:
- ✅ Close codes are handled appropriately
- ✅ DNS errors use appropriate backoff
- ✅ TLS errors don't cause infinite reconnection
- ✅ Rate limits are respected

### Phase 5:
- ✅ Messages are queued during disconnection
- ✅ Messages are retried on reconnection
- ✅ Message acknowledgments are tracked
- ✅ No messages are lost during app crashes

---

## Notes

- All changes should maintain backward compatibility where possible
- Add comprehensive logging for debugging
- Consider adding metrics/analytics for monitoring
- Document new behavior in code comments
- Update user-facing documentation if needed

