# Reconnection and Spaces Clearing Analysis

## Question
When reconnecting with `last_received_id`, the backend should NOT send `clear_state=true` because we're resuming. But is there any reconnection logic that might clear spaces?

## Answer: **NO direct clearing, but edge cases exist**

---

## Reconnection Flow

### 1. **`setWebSocket()` - Called on Connect/Reconnect** (Line 7062)

**What it does:**
- Resets `requestIdCounter` to 1
- Resets `initialSyncPhase`, clears `initialSyncCompleteQueue`
- Resets sync progress counters
- Resets room state loading flags

**Does it clear spaces?** ❌ **NO** - `allSpaces` is NOT touched

**Code:**
```kotlin
fun setWebSocket(webSocket: WebSocket) {
    // ... resets sync state ...
    // NO allSpaces = emptyList() here
}
```

---

### 2. **`clearWebSocket()` - Called on Disconnect** (Line 7207)

**What it does:**
- Resets `initializationComplete` flag
- Resets `initialSyncPhase`, clears `initialSyncCompleteQueue`
- Resets sync progress counters

**Does it clear spaces?** ❌ **NO** - `allSpaces` is NOT touched

**Code:**
```kotlin
fun clearWebSocket(reason: String = "Unknown", ...) {
    // ... resets sync state ...
    // NO allSpaces = emptyList() here
}
```

---

### 3. **Reconnection Detection** (NetworkUtils.kt:295-310)

**How reconnection is detected:**
```kotlin
val hasPopulatedRooms = appViewModel?.allRooms?.isNotEmpty() ?: false
val isTrueReconnection = runId.isNotEmpty() && hasPopulatedRooms

val lastReceivedRequestId = if (isTrueReconnection) {
    // True reconnection: use last_received_request_id
    WebSocketService.getLastReceivedRequestId(context)
} else {
    // Cold start: don't use last_received_request_id
    0
}
```

**When `last_received_id` is passed:**
- ✅ `runId` exists in SharedPreferences (from previous session)
- ✅ `allRooms` is populated (app has data from previous session)

**When `last_received_id` is NOT passed (cold start):**
- ❌ `runId` is empty (first connection ever)
- ❌ `allRooms` is empty (app was killed and restarted)

---

## Potential Issues

### Issue 1: Backend Sends `clear_state=true` on Reconnection (Backend Bug)

**Scenario:**
- Reconnecting with `last_received_id` (should resume)
- Backend incorrectly sends `clear_state=true` in `sync_complete`

**Result:**
- `handleClearStateReset()` → `clearDerivedStateInMemory()` → `allSpaces = emptyList()`
- Spaces are cleared even though they shouldn't be

**Detection:**
- Check logs for `clear_state=true` during reconnection
- This would be a backend bug, not a client issue

---

### Issue 2: Cold Start Treated as Reconnection (Edge Case)

**Scenario:**
- App process was killed
- `runId` still exists in SharedPreferences (from previous session)
- `allRooms` is empty (no data loaded yet)
- Reconnection logic treats it as cold start (doesn't pass `last_received_id`)
- Backend sends full sync with `clear_state=true`

**Result:**
- Spaces are cleared (expected for cold start)
- But this is correct behavior - it's not a true reconnection

**Prevention:**
- The check `hasPopulatedRooms` prevents this - if `allRooms` is empty, it's treated as cold start

---

### Issue 3: Race Condition During Reconnection

**Scenario:**
- Reconnecting with `last_received_id`
- Multiple `sync_complete` messages arrive rapidly
- One message has `clear_state=true` (shouldn't happen, but backend bug)
- Race condition in processing

**Result:**
- Previously: Could cause spaces to be cleared out of order
- **FIXED**: `syncCompleteProcessingMutex` ensures atomic processing

**Code (Line 4732):**
```kotlin
syncCompleteProcessingMutex.withLock {
    // Atomic processing - prevents race conditions
    if (isClearState) {
        handleClearStateReset() // Inside mutex
    }
}
```

---

## Summary

### ✅ **No Direct Clearing on Reconnection**
- `setWebSocket()` does NOT clear spaces
- `clearWebSocket()` does NOT clear spaces
- Reconnection logic does NOT clear spaces

### ⚠️ **Indirect Clearing (Backend-Dependent)**
- Spaces are cleared ONLY if backend sends `clear_state=true`
- On reconnection with `last_received_id`, backend should NOT send `clear_state=true`
- If backend sends it anyway, spaces will be cleared (backend bug)

### 🔍 **How to Debug**
1. Check logs for `clear_state=true` during reconnection
2. Check if `last_received_id` is being passed correctly
3. Check if `allRooms` is populated when reconnecting
4. Check if backend is sending `clear_state=true` when it shouldn't

### 📝 **Recommendation**
Add logging to detect when `clear_state=true` is received during reconnection:

```kotlin
if (isClearState && isReconnecting) {
    android.util.Log.w("Andromuks", "⚠️ WARNING: clear_state=true received during reconnection with last_received_id - this should not happen!")
}
```

This would help identify if the backend is incorrectly sending `clear_state=true` during reconnections.

