# State Management Analysis - App State Issues

## Overview
This document analyzes all entry points for opening rooms and identifies state management issues when AppViewModel or WebSocket connections are missing or not fully initialized.

## Entry Points for Opening Rooms

### 1. RoomListScreen (Normal Flow)
**Path**: `RoomListScreen.kt` → `RoomListItem.onRoomClick` → `appViewModel.navigateToRoomWithCache()` → `navController.navigate("room_timeline/$roomId")`

**Dependencies**:
- AppViewModel instance (via `viewModel()`)
- WebSocket connection (for real-time updates)
- Room list data (`appViewModel.getCurrentRoomSection()`)
- Room state cache (`appViewModel.navigateToRoomWithCache()`)

**What Works When AppViewModel is Present**:
✅ Room list is populated from `appViewModel.getCurrentRoomSection()`
✅ Navigation uses `appViewModel.navigateToRoomWithCache()` which:
  - Checks `RoomTimelineCache` singleton
  - Falls back to database via `BootstrapLoader` if cache is empty
  - Prefetches room snapshot if needed
✅ Room timeline loads from cache or database
✅ WebSocket provides real-time updates

**What Fails When AppViewModel is Missing/Destroyed**:
❌ **Issue #1**: Room list shows partial data
  - `RoomListScreen` waits for `appViewModel.isProcessingPendingItems` to complete
  - If AppViewModel was destroyed, `isProcessingPendingItems` may be stale or never complete
  - Room list may show empty tabs (Favs, Direct) if `getCurrentRoomSection()` returns incomplete data
  - **Root Cause**: `RoomListScreen` depends on `appViewModel.getCurrentRoomSection()` which requires:
    - `spacesLoaded = true` (set in `onInitComplete()`)
    - `roomMap` populated from sync data
    - If AppViewModel was destroyed, these may not be restored properly

❌ **Issue #2**: Navigation may fail silently
  - `navigateToRoomWithCache()` may not have room state if AppViewModel cache is empty
  - Falls back to database, but if `appContext` is not set (via `initializeFCM()`), database access may fail
  - **Root Cause**: `BootstrapLoader` requires `appContext` to be set, which happens in `initializeFCM()`

---

### 2. Notification Tap (EnhancedNotificationDisplay)
**Path**: `EnhancedNotificationDisplay.createRoomIntent()` → `MainActivity.onCreate()` → `appViewModel.setDirectRoomNavigation()` → `RoomListScreen` → `RoomTimelineScreen`

**Intent Data**:
```kotlin
putExtra("room_id", notificationData.roomId)
putExtra("event_id", notificationData.eventId)
putExtra("direct_navigation", true)
putExtra("from_notification", true)
```

**Dependencies**:
- MainActivity's AppViewModel instance
- `appViewModel.setDirectRoomNavigation()` to store navigation intent
- `RoomListScreen` to detect and process `directRoomNavigation`
- WebSocket connection (for real-time updates)

**What Works When AppViewModel is Present**:
✅ Intent is processed in `MainActivity.onCreate()` (lines 124-158)
✅ `appViewModel.setDirectRoomNavigation(extractedRoomId)` stores the room ID
✅ `RoomListScreen` detects `directRoomNavigation` in `LaunchedEffect(Unit)` (lines 497-508)
✅ Navigation happens via `appViewModel.navigateToRoomWithCache()` + `navController.navigate()`

**What Fails When AppViewModel is Missing/Destroyed**:
❌ **Issue #3**: Only last message shows in timeline
  - **Root Cause**: When AppViewModel is destroyed, `RoomTimelineScreen` loads events from database
  - However, `RoomTimelineScreen` gets events from `appViewModel.timelineEvents` (line 389)
  - If AppViewModel cache is empty, it should fall back to database via `BootstrapLoader`
  - **Problem**: `BootstrapLoader` may only load recent events (last message) if:
    - `appContext` is not set (database access fails)
    - Database query is limited (pagination not triggered)
    - `refreshTimelineFromDatabase()` is not called when AppViewModel is recreated

❌ **Issue #4**: Navigation may be delayed or fail
  - If `MainActivity.onCreate()` runs before AppViewModel is fully initialized:
    - `appViewModel.setDirectRoomNavigation()` may be called before `spacesLoaded = true`
    - `RoomListScreen` may not detect `directRoomNavigation` if it renders before the flag is set
  - **Root Cause**: Race condition between:
    - `MainActivity.onCreate()` → `onViewModelCreated` callback (line 89-180)
    - `RoomListScreen` → `LaunchedEffect(Unit)` checking for `directRoomNavigation` (line 497)

---

### 3. Shortcut Tap (ShortcutActivity)
**Path**: `ShortcutActivity.onCreate()` → `ShortcutNavigation()` → `appViewModel.navigateToRoomWithCache()` → `navController.navigate("room_timeline/$roomId")`

**Dependencies**:
- **NEW AppViewModel instance** (via `viewModel()` in `ShortcutNavigation`, line 107)
- `appViewModel.initializeFCM()` to set `appContext` (line 119)
- `appViewModel.loadCachedProfiles()` and `loadSettings()` (lines 128-131)
- `appViewModel.attachToExistingWebSocketIfAvailable()` (line 134)
- WebSocket connection (may be shared with MainActivity's primary instance)

**What Works When AppViewModel is Present**:
✅ ShortcutActivity creates its own AppViewModel instance
✅ Initializes FCM, profiles, and settings
✅ Attempts to attach to existing WebSocket (if MainActivity's primary instance exists)
✅ Uses cache-first navigation via `navigateToRoomWithCache()`

**What Fails When AppViewModel is Missing/Destroyed**:
❌ **Issue #5**: Unpredictable behavior
  - **Root Cause**: ShortcutActivity creates a **secondary AppViewModel instance** (not marked as primary, line 124)
  - This instance:
    - Does NOT create WebSocket connections (only attaches to existing ones)
    - Has its own `roomMap`, `allRooms`, `spacesLoaded` state
    - May not have room data if WebSocket never connected or MainActivity's primary instance was destroyed
  - **Problems**:
    1. If MainActivity's primary AppViewModel was destroyed, there's no WebSocket to attach to
    2. ShortcutActivity's AppViewModel has empty `roomMap` and `allRooms`
    3. `navigateToRoomWithCache()` may work (uses singleton cache), but:
       - Room state may be missing (`getRoomById()` returns null)
       - Timeline may only show database events (last message issue)
    4. `spacesLoaded` may never become `true` if WebSocket never connects

❌ **Issue #6**: State synchronization problems
  - ShortcutActivity's AppViewModel is a separate instance from MainActivity's
  - State is NOT shared between instances:
    - `roomMap`, `allRooms`, `spacesLoaded` are instance variables
    - Only `RoomTimelineCache` is a singleton (shared)
  - **Result**: ShortcutActivity may show stale or missing data

---

## Critical State Dependencies

### 1. WebSocket Connection State
**Where it's created**: `AppViewModel.markAsPrimaryInstance()` → `connectWebSocket()`
**Where it's used**: 
- Real-time sync updates (`processSyncEventsArray()`)
- Room list population (`onInitComplete()` sets `spacesLoaded = true`)
- Timeline updates (new messages appear via sync)

**What happens when missing**:
- No real-time updates
- `spacesLoaded` may never become `true`
- Room list may be empty or incomplete
- Timeline may only show database events (stale data)

### 2. AppViewModel Initialization State
**Required initialization steps** (in order):
1. `initializeFCM(context, homeserverUrl, authToken)` - Sets `appContext` (required for database access)
2. `loadCachedProfiles(context)` - Restores user profiles from disk
3. `loadSettings(context)` - Loads app settings
4. `markAsPrimaryInstance()` - Marks as primary (creates WebSocket)
5. `attachToExistingWebSocketIfAvailable()` - Attaches to existing WebSocket (for secondary instances)
6. `checkAndProcessPendingItemsOnStartup()` - Processes pending sync items

**What happens when incomplete**:
- Missing `appContext`: Database access fails → timeline shows empty or only cached events
- Missing profiles: Display names show as user IDs
- Missing WebSocket: No real-time updates, `spacesLoaded` never becomes `true`

### 3. Room List State (`spacesLoaded`, `roomMap`, `allRooms`)
**Where it's populated**: `onInitComplete()` after WebSocket receives `init_complete` message
**Where it's used**:
- `RoomListScreen.getCurrentRoomSection()` - Filters rooms by section type
- `RoomTimelineScreen.getRoomById()` - Gets room metadata
- Navigation - Determines if room exists

**What happens when missing**:
- `RoomListScreen` shows empty tabs (Favs, Direct, etc.)
- `RoomTimelineScreen` can't get room name/avatar
- Navigation may fail if room doesn't exist in `roomMap`

### 4. Database State (BootstrapLoader)
**Where it's used**: 
- `RoomTimelineScreen` loads events when AppViewModel cache is empty
- `RoomListScreen` queries last messages for room summaries
- Fallback when WebSocket is disconnected

**What happens when missing**:
- Timeline shows empty or only last message
- Room list summaries are missing
- **Root Cause**: `BootstrapLoader` requires `appContext` to be set (via `initializeFCM()`)

---

## Root Cause Analysis

### Problem 1: RoomListScreen Shows Partial Data
**Scenario**: AppViewModel was destroyed, app restarts, RoomListScreen renders before `spacesLoaded = true`

**Flow**:
1. `MainActivity.onCreate()` creates new AppViewModel
2. `RoomListScreen` renders immediately (doesn't wait for `spacesLoaded`)
3. `appViewModel.getCurrentRoomSection()` returns incomplete data (empty `roomMap`)
4. Tabs show empty (Favs, Direct) because rooms aren't loaded yet
5. WebSocket connects later, `onInitComplete()` populates `roomMap`, but UI may not update

**Fix Needed**: `RoomListScreen` should wait for `spacesLoaded = true` before showing room list (similar to profile loading check)

### Problem 2: Notification Shows Only Last Message
**Scenario**: AppViewModel was destroyed, user taps notification, timeline loads from database but only shows last message

**Flow**:
1. `MainActivity.onCreate()` processes notification intent
2. `appViewModel.setDirectRoomNavigation()` stores room ID
3. `RoomListScreen` navigates to `RoomTimelineScreen`
4. `RoomTimelineScreen` gets events from `appViewModel.timelineEvents` (line 389)
5. If AppViewModel cache is empty, should fall back to database
6. **Problem**: `BootstrapLoader` may only load recent events (pagination not triggered)
7. **Problem**: `refreshTimelineFromDatabase()` may not be called when AppViewModel is recreated

**Fix Needed**: 
- `RoomTimelineScreen` should explicitly call `refreshTimelineFromDatabase()` when AppViewModel cache is empty
- `BootstrapLoader` should load more events (not just last message) when loading from cold start

### Problem 3: Shortcut Unpredictable Behavior
**Scenario**: MainActivity's primary AppViewModel was destroyed, user taps shortcut, ShortcutActivity creates secondary instance

**Flow**:
1. `ShortcutActivity.onCreate()` creates new AppViewModel instance (secondary)
2. `initializeFCM()` sets `appContext` ✅
3. `attachToExistingWebSocketIfAvailable()` - No WebSocket to attach to (primary instance destroyed) ❌
4. `navigateToRoomWithCache()` - Cache may be empty or stale
5. `RoomTimelineScreen` loads from database, but room state may be missing

**Fix Needed**:
- ShortcutActivity should either:
  - Wait for WebSocket connection before navigating, OR
  - Load room state from database if WebSocket is not available, OR
  - Share state with MainActivity's AppViewModel (singleton pattern)

---

## State Synchronization Issues

### Issue 1: Multiple AppViewModel Instances
- **MainActivity**: Primary instance (creates WebSocket, manages state)
- **ShortcutActivity**: Secondary instance (separate state, attaches to WebSocket)
- **Problem**: State is NOT shared between instances
  - `roomMap`, `allRooms`, `spacesLoaded` are instance variables
  - Only `RoomTimelineCache` is a singleton

**Impact**: ShortcutActivity may show stale or missing data

### Issue 2: WebSocket Connection Ownership
- **Primary instance**: Creates WebSocket connection
- **Secondary instances**: Attach to existing connection
- **Problem**: If primary instance is destroyed, secondary instances have no WebSocket

**Impact**: No real-time updates, `spacesLoaded` never becomes `true`

### Issue 3: Database Access Dependency
- **Requirement**: `appContext` must be set (via `initializeFCM()`)
- **Problem**: If `initializeFCM()` is not called or fails, database access fails

**Impact**: Timeline shows empty or only cached events

---

## Recommendations

### 1. Add State Readiness Checks
- `RoomListScreen` should wait for `spacesLoaded = true` before showing room list
- `RoomTimelineScreen` should check if AppViewModel cache is empty and explicitly load from database
- Navigation should wait for critical state (WebSocket connected, `spacesLoaded = true`)

### 2. Improve Database Fallback
- `BootstrapLoader` should load more events (not just last message) when loading from cold start
- `RoomTimelineScreen` should explicitly call `refreshTimelineFromDatabase()` when cache is empty
- Add pagination support for loading older events from database

### 3. Fix ShortcutActivity State Management
- Option A: Make AppViewModel a singleton (shared state between MainActivity and ShortcutActivity)
- Option B: ShortcutActivity should wait for WebSocket connection before navigating
- Option C: ShortcutActivity should load room state from database if WebSocket is not available

### 4. Add State Restoration
- Save critical state (room list, current room) to SharedPreferences or database
- Restore state when AppViewModel is recreated
- Ensure `spacesLoaded` is restored from saved state

### 5. Improve Error Handling
- Add fallback UI when WebSocket is not connected
- Show loading states when state is being restored
- Handle database access failures gracefully

---

## Testing Scenarios

### Scenario 1: Cold Start from Notification
1. Kill app completely
2. Receive notification
3. Tap notification
4. **Expected**: Timeline shows all messages from database
5. **Current**: Timeline shows only last message

### Scenario 2: Cold Start from Shortcut
1. Kill app completely
2. Tap shortcut
3. **Expected**: Room opens with full timeline
4. **Current**: Unpredictable (may show empty or partial data)

### Scenario 3: AppViewModel Destroyed, RoomList Renders
1. AppViewModel is destroyed (low memory)
2. App resumes, RoomListScreen renders
3. **Expected**: Room list shows all rooms after state is restored
4. **Current**: Room list shows partial data (empty tabs)

### Scenario 4: WebSocket Not Connected
1. WebSocket fails to connect
2. User navigates to room
3. **Expected**: Timeline loads from database, shows all messages
4. **Current**: Timeline may show empty or only cached events

---

## Files to Review

1. **RoomListScreen.kt** (lines 143-258): Profile and pending items loading checks
2. **RoomTimelineScreen.kt** (lines 360-600): Event loading logic
3. **AppViewModel.kt**: State initialization and restoration
4. **ShortcutActivity.kt** (lines 104-170): Secondary instance initialization
5. **MainActivity.kt** (lines 60-184): Primary instance initialization
6. **BootstrapLoader.kt**: Database loading logic
7. **RoomTimelineCache.kt**: Singleton cache implementation

