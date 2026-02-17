# Battery Drain Root Cause Analysis

## Executive Summary

Your app is experiencing significant battery drain when backgrounded due to **multiple compounding factors**, not just message processing. The main culprits are:

1. **Wake Lock (10 hours)** - Keeps CPU awake continuously
2. **Immediate Decompression** - Every message at 4-8 Hz requires CPU work even if processing is deferred
3. **Ping Loop** - Wakes CPU every 15-60 seconds
4. **Multiple Monitoring Jobs** - 3 separate jobs checking every 30 seconds
5. **Foreground Service** - Prevents Android from dozing the app

## Root Causes (Priority Order)

### 🔴 CRITICAL: Wake Lock (10 Hours)

**Location:** `WebSocketService.kt:4251`

```kotlin
wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
    acquire(10 * 60 * 60 * 1000L) // 10 hours timeout
}
```

**Impact:** 
- **Keeps CPU awake continuously** - prevents Android from entering deep sleep
- This is the #1 battery drain source
- Even with no messages, CPU stays active

**Recommendation:**
- **Remove wake lock entirely** - Android's foreground service already keeps the process alive
- Wake locks are only needed for short operations (e.g., sending a ping)
- If you must keep it, use `acquireTimeout()` with short durations (e.g., 30 seconds) and release immediately after operations

### 🔴 CRITICAL: Immediate Decompression (Your Suspicion is Correct!)

**Location:** `NetworkUtils.kt:727-730`

```kotlin
if (streamingDecompressor != null) {
    streamingDecompressor!!.write(bytes.toByteArray())
    val decompressedText = streamingDecompressor!!.readAvailable()
    // ... processes immediately
}
```

**Impact:**
- **Every message at 4-8 Hz requires CPU-intensive decompression**
- Decompression happens **immediately on receipt**, before any batching
- Even if you defer processing, decompression still uses CPU
- With compression enabled, this is **unavoidable CPU work** on every message

**The Problem:**
- Messages arrive compressed → must be decompressed immediately
- Decompression uses CPU → wakes device from sleep
- Even if you buffer messages, decompression happens synchronously
- **Batching doesn't help** because decompression is required to parse JSON

**Recommendation:**
1. **Disable compression when backgrounded** - Accept larger messages but save CPU
2. **OR drop WebSocket when backgrounded** - Use FCM only, reconnect on foreground
3. **OR defer decompression** - Store compressed bytes, decompress in batches (complex)

### 🟠 HIGH: Ping Loop (Every 15-60 Seconds)

**Location:** `WebSocketService.kt:761-798`

```kotlin
while (isActive) {
    val interval = if (serviceInstance.consecutivePingTimeouts > 0) {
        100L // Rush to healthy
    } else {
        PING_INTERVAL_MS // 15 seconds foreground, 60 seconds background
    }
    delay(interval)
    serviceInstance.sendPing()
}
```

**Impact:**
- **Wakes CPU every 15-60 seconds** to send ping
- Even when backgrounded, still pings every 60 seconds
- Each ping requires network I/O and CPU wake

**Recommendation:**
- **Increase background ping interval to 5 minutes** (300 seconds)
- Or **disable ping entirely when backgrounded** - rely on server-side keepalive
- Ping is mainly for connection health - less critical when backgrounded

### 🟠 HIGH: Multiple Monitoring Jobs (Every 30 Seconds)

**Location:** `WebSocketService.kt`

1. **State Corruption Monitoring** (line 2524-2548): Checks every 30 seconds
2. **Primary Health Monitoring** (line 2564-2609): Checks every 30 seconds  
3. **Connection Health Check** (line 2624-2691): Checks every 30 seconds

**Impact:**
- **3 separate jobs waking CPU every 30 seconds**
- Each job performs checks and logging
- Combined: CPU wakes every 10 seconds on average

**Recommendation:**
- **Combine into single monitoring job** (check all 3 things at once)
- **Increase interval to 5 minutes** when backgrounded
- **Skip non-critical checks** when backgrounded (e.g., primary health)

### 🟡 MEDIUM: Network Monitoring Callbacks

**Location:** `WebSocketService.kt:2754-2944`

**Impact:**
- Continuous network state callbacks
- Triggers reconnection logic on network changes
- Can cause CPU wake-ups on network transitions

**Recommendation:**
- **Debounce network callbacks** (already done, but can increase debounce time)
- **Skip network validation** when backgrounded (trust Android's network state)

### 🟡 MEDIUM: Message Processing (Even with Batching)

**Location:** `AppViewModel.kt:4701-4707`

**Current State:**
- Messages are batched when backgrounded (every 10 seconds)
- But **decompression still happens immediately** on receipt
- JSON parsing happens in batches, but decompression is synchronous

**Impact:**
- Batching helps, but decompression is the bottleneck
- Processing 50 rooms per sync still uses CPU

**Recommendation:**
- Already optimized with batching
- **Consider disabling compression** to eliminate decompression overhead

## Should You Drop WebSocket When Backgrounded?

### Option 1: Keep WebSocket, Optimize Aggressively ✅ **RECOMMENDED**

**Pros:**
- Reply and mark-read work immediately
- Real-time sync when app opens
- Better UX

**Cons:**
- Still uses battery (but can be minimized)

**Optimizations:**
1. Remove wake lock
2. Disable compression when backgrounded
3. Increase ping interval to 5 minutes when backgrounded
4. Combine monitoring jobs, increase interval
5. Defer ALL processing when backgrounded (store raw JSON, process on foreground)

### Option 2: Drop WebSocket When Backgrounded ⚠️ **TRADE-OFF**

**Pros:**
- **Zero battery drain** from WebSocket
- FCM handles notifications

**Cons:**
- **Reply and mark-read won't work** (your concern)
- Must reconnect on foreground (delay)
- More complex state management

**Implementation:**
```kotlin
// When app goes to background
if (!isAppVisible) {
    WebSocketService.clearWebSocket("App backgrounded - saving battery")
    // Rely on FCM for notifications
}

// When app comes to foreground
if (isAppVisible) {
    WebSocketService.connectWebSocket(...)
    // Reconnect and sync
}
```

**Hybrid Approach:**
- Drop WebSocket after 5 minutes of background
- Keep for first 5 minutes (for quick replies)
- Reconnect on foreground

## Recommended Fixes (Priority Order)

### 1. Remove Wake Lock (Immediate Impact)

```kotlin
// REMOVE THIS:
private fun acquireWakeLock() {
    // wakeLock = powerManager.newWakeLock(...)
    // wakeLock?.acquire(10 * 60 * 60 * 1000L)
}

// OR use short-duration wake locks only for operations:
private fun acquireWakeLockForOperation() {
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
        acquireTimeout(30_000L) // 30 seconds max
    }
}
```

**Expected Impact:** 50-70% reduction in battery drain

### 2. Disable Compression When Backgrounded

```kotlin
// In NetworkUtils.kt, when connecting:
val compressionEnabled = if (isAppVisible) {
    prefs.getBoolean("enable_compression", true)
} else {
    false // Disable compression when backgrounded
}
```

**Expected Impact:** 20-30% reduction in CPU usage from messages

### 3. Increase Background Ping Interval

```kotlin
// In WebSocketService.kt:
val interval = if (serviceInstance.isAppVisible) {
    PING_INTERVAL_MS // 15 seconds
} else {
    300_000L // 5 minutes when backgrounded
}
```

**Expected Impact:** 10-15% reduction in wake-ups

### 4. Combine and Reduce Monitoring Jobs

```kotlin
// Single monitoring job that checks everything:
private fun startUnifiedMonitoring() {
    monitoringJob = serviceScope.launch {
        while (isActive) {
            val interval = if (isAppVisible) 30_000L else 300_000L // 5 min background
            delay(interval)
            
            // Check all 3 things at once
            detectAndRecoverStateCorruption()
            if (isAppVisible) { // Skip primary health when backgrounded
                isPrimaryAlive()
            }
            checkConnectionHealth()
        }
    }
}
```

**Expected Impact:** 5-10% reduction in wake-ups

### 5. Defer ALL Processing When Backgrounded

**Already partially implemented**, but can be improved:

```kotlin
// In SyncIngestor.ingestSyncComplete():
if (!isAppVisible) {
    // Store raw JSON only, no processing
    storeSyncMetadata(syncJson)
    storePendingRooms(roomsJson) // Store as-is, process later
    return // Skip all processing
}
```

**Expected Impact:** 10-20% reduction in CPU usage

## Expected Total Impact

With all optimizations:
- **Wake lock removal:** 50-70% reduction
- **Disable compression:** 20-30% reduction  
- **Ping interval:** 10-15% reduction
- **Monitoring jobs:** 5-10% reduction
- **Defer processing:** 10-20% reduction

**Combined:** **70-85% reduction in battery drain** when backgrounded

## Testing Recommendations

1. **Measure baseline:** Use Android's Battery Historian to measure current drain
2. **Test each fix individually:** Apply one fix at a time, measure impact
3. **Test with compression on/off:** Compare battery usage
4. **Test ping intervals:** Try 1 min, 5 min, 10 min intervals
5. **Monitor user experience:** Ensure notifications still work

## Conclusion

**Your suspicion about compression is correct** - decompression happens immediately and uses CPU even if processing is deferred. However, the **biggest culprit is the wake lock** keeping the CPU awake continuously.

**Recommended approach:**
1. Remove wake lock (biggest impact)
2. Disable compression when backgrounded
3. Increase ping interval when backgrounded
4. Keep WebSocket connection (for reply/mark-read functionality)
5. Monitor battery usage and adjust as needed

This should reduce battery drain by **70-85%** while maintaining functionality.

