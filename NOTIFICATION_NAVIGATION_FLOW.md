# Notification to Room Navigation Flow

This document traces the complete flow when opening a room from a notification.

## Timeline from Logs (19:39:16.548 - 19:39:42.199)

### Phase 1: Activity Start (19:39:16.548 - 19:39:16.618)
1. **Notification tap** → `MainActivity` starts
2. **MainActivity.onCreate()**:
   - Extracts room ID from intent: `!erfjdPEYxkLrUqdLYS:aguiarvieira.pt`
   - Sets `directRoomNavigation` via `appViewModel.setDirectRoomNavigation()` (19:39:16.608)
   - Calls `initializeFCM()` with `skipCacheClear=true` (preserves cache) ✓
   - WebSocket attaches immediately (connected=true)

### Phase 2: STALL (19:39:17.108 - 19:39:26.705) ⚠️ **9.6 SECOND DELAY**
- App is waiting for something...
- No logs during this period
- Likely waiting for UI composition/navigation graph setup

### Phase 3: RoomListScreen Appears (19:39:26.705)
- `RoomListScreen` finally composes
- Room is cached (100 events detected)
- WebSocket is connected (pollCount=0)
- **Navigation happens immediately** (19:39:26.932):
  - `navigateToRoomWithCache()` called
  - `navController.navigate("room_timeline/$roomId")` called
  - Timeline builds from cache (100 events)

### Phase 4: RoomTimelineScreen Opens (19:39:27.138)
- `RoomTimelineScreen` composable starts
- `LaunchedEffect(roomId)` triggers:
  - Calls `navigateToRoomWithCache()` again (duplicate call!)
  - Calls `awaitRoomDataReadiness()` - **THIS BLOCKS FOR 15 SECONDS**
  - Sets `readinessCheckComplete = true` after timeout
- Timeline events process in background
- Room state requests sent

### Phase 5: Readiness Timeout (19:39:42.199)
- `awaitRoomDataReadiness()` times out after 15 seconds
- Room finally displays events

---

## Code Flow Analysis

### 1. MainActivity.onCreate() (when notification tapped)
**File:** `MainActivity.kt` lines 93-179

```kotlin
// Extract room ID from intent
val extractedRoomId = extractRoomIdFromIntent(...)

// Set direct room navigation
appViewModel.setDirectRoomNavigation(
    roomId = extractedRoomId,
    notificationTimestamp = notificationTimestamp,
    targetEventId = notificationEventId
)

// Initialize FCM (with skipCacheClear=true to preserve preemptive cache)
appViewModel.initializeFCM(context, homeserverUrl, authToken, skipCacheClear = true)
```

**Key Functions Called:**
- `AppViewModel.setDirectRoomNavigation()` - Stores room ID for later navigation
- `AppViewModel.initializeFCM()` - Initializes FCM, preserves cache if `skipCacheClear=true`

### 2. Navigation Graph Setup
The app must navigate through:
- `AuthCheckScreen` → checks WebSocket connection
- `RoomListScreen` → handles direct room navigation

### 3. RoomListScreen - Direct Room Navigation Handling
**File:** `RoomListScreen.kt` lines 462-537

**First LaunchedEffect(Unit):**
```kotlin
LaunchedEffect(Unit) {
    val directRoomId = appViewModel.getDirectRoomNavigation()
    if (directRoomId != null) {
        val cachedEventCount = RoomTimelineCache.getCachedEventCount(directRoomId)
        val isRoomCached = cachedEventCount >= 10 || RoomTimelineCache.isRoomActivelyCached(directRoomId)
        
        if (isRoomCached) {
            // Wait for WebSocket connection (max 5 seconds)
            // Then navigate immediately
            appViewModel.navigateToRoomWithCache(directRoomId)
            navController.navigate("room_timeline/$directRoomId")
        }
    }
}
```

**Key Functions Called:**
- `RoomTimelineCache.getCachedEventCount()` - Checks if room is cached
- `AppViewModel.navigateToRoomWithCache()` - Builds timeline from cache
- `navController.navigate()` - Navigates to RoomTimelineScreen

### 4. AppViewModel.navigateToRoomWithCache()
**File:** `AppViewModel.kt` lines 9330-9450

**Purpose:** Builds timeline from cache when room is already cached

**Key Operations:**
1. Updates SharedPreferences with current room ID
2. Adds room to `RoomTimelineCache.openedRooms` (exempt from cache clearing)
3. Gets cached events from `RoomTimelineCache`
4. Clears internal state (`eventChainMap`, `editEventsMap`, etc.)
5. Populates `eventChainMap` from cached events
6. Processes edit relationships
7. Builds timeline via `buildTimelineFromChain()` → updates `timelineEvents` state
8. Loads reactions from database
9. Requests room state (if needed)
10. Marks room as read (if room is open)

**State Updates:**
- `timelineEvents` - Updated with cached events
- `currentRoomId` - Set via `updateCurrentRoomIdInPrefs()`
- `isTimelineLoading` - Set to `false`

### 5. RoomTimelineScreen Opens
**File:** `RoomTimelineScreen.kt` lines 1714-1756

**LaunchedEffect(roomId):**
```kotlin
LaunchedEffect(roomId) {
    readinessCheckComplete = false
    appViewModel.navigateToRoomWithCache(roomId) // ⚠️ DUPLICATE CALL!
    val readinessResult = appViewModel.awaitRoomDataReadiness(...) // ⚠️ BLOCKS 15 SECONDS
    readinessCheckComplete = true
    appViewModel.setCurrentRoomIdForTimeline(roomId)
    appViewModel.requestRoomState(roomId)
}
```

**Key Functions Called:**
- `AppViewModel.navigateToRoomWithCache()` - **CALLED AGAIN!** (duplicate)
- `AppViewModel.awaitRoomDataReadiness()` - **BLOCKS FOR 15 SECONDS** ⚠️
- `AppViewModel.setCurrentRoomIdForTimeline()` - Sets current room ID
- `AppViewModel.requestRoomState()` - Requests room state

---

## Issues Identified

### Issue 1: 9.6 Second Stall Before RoomListScreen Renders
**Location:** Between app initialization and RoomListScreen composition
**Cause:** Unknown - likely navigation graph setup or AuthCheckScreen delays
**Impact:** User sees blank screen for ~10 seconds

### Issue 2: Duplicate navigateToRoomWithCache() Calls
**Location:** 
- First call: `RoomListScreen.kt` line 492 (before navigation)
- Second call: `RoomTimelineScreen.kt` line 1721 (after navigation)

**Problem:** `navigateToRoomWithCache()` is called twice:
1. In RoomListScreen before navigating
2. In RoomTimelineScreen's LaunchedEffect

**Impact:** 
- Redundant processing
- Potential race conditions
- Timeline rebuilt twice

### Issue 3: awaitRoomDataReadiness() Blocks for 15 Seconds
**Location:** `RoomTimelineScreen.kt` line 1723
**Problem:** This function waits up to 15 seconds before allowing the room to display
**Impact:** Room appears to load but doesn't display until timeout

### Issue 4: Room Switching Issue (user reported)
**Symptom:** "if i leave the app open, suddenly the timeline of another room shows"
**Possible Causes:**
- State management race condition
- Multiple navigateToRoomWithCache() calls causing state confusion
- Cache clearing/reloading issues
- Sync messages updating wrong room's timeline

---

## Recommendations

1. **Remove duplicate `navigateToRoomWithCache()` call** in RoomTimelineScreen
   - Navigation should be handled by RoomListScreen only
   - RoomTimelineScreen should only set currentRoomId and request state

2. **Investigate 9.6 second stall**
   - Add logging to identify what's blocking
   - Check AuthCheckScreen logic
   - Check navigation graph setup

3. **Fix awaitRoomDataReadiness() blocking**
   - This should not block room display when cache is already loaded
   - Should only wait when room needs to be loaded from network

4. **Add state guards** to prevent room switching bugs
   - Ensure currentRoomId is checked before updating timeline
   - Prevent sync messages from updating wrong room's timeline

