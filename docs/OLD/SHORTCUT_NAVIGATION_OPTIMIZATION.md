# Shortcut Navigation Optimization

## Overview

This document explains the optimizations implemented to make shortcut navigation as fast as RoomListScreen navigation, and how different navigation methods work in the Andromuks app.

## Problem Statement

**Original Issue**: Opening a room via Shortcut was significantly slower than opening the same room via RoomListScreen, despite both accessing the same cached data.

## Root Cause Analysis

The slowness was caused by several architectural bottlenecks:

1. **Pending State Management**: Shortcuts used `setPendingRoomNavigation()` → `getPendingRoomNavigation()` cycle
2. **Intent Processing Overhead**: Complex Matrix URI parsing instead of direct room ID access
3. **MainActivity Lifecycle Overhead**: All navigation went through MainActivity
4. **Cache Isolation**: Different AppViewModel instances couldn't access the same cache

## Optimizations Implemented

### #1: Eliminate Pending State Management
**Before**: `setPendingRoomNavigation()` → `getPendingRoomNavigation()` cycle
**After**: `setDirectRoomNavigation()` → `getDirectRoomNavigation()` direct navigation

**Files Modified**:
- `AppViewModel.kt`: Added `setDirectRoomNavigation()`, `getDirectRoomNavigation()`, `clearDirectRoomNavigation()`
- `MainActivity.kt`: Updated to use direct navigation instead of pending state
- `RoomListScreen.kt`: Updated to use direct navigation

### #2: Optimize Intent Processing
**Before**: Complex Matrix URI parsing in `extractRoomIdFromMatrixUri()`
**After**: Pre-store `room_id` in intent extras for fast access

**Files Modified**:
- `EnhancedNotificationDisplay.kt`: Added `room_id` extra to intents
- `MainActivity.kt`: Prioritize `room_id` extra over URI parsing when `direct_navigation` flag is set

### #3: Consider Bypassing MainActivity for Shortcuts
**Created**: `ShortcutActivity.kt` for direct room navigation
**Result**: Not used due to WebSocket connection issues (see below)

### #4: Implement Cache-First Navigation
**Before**: Cache was tied to AppViewModel instances
**After**: Made `RoomTimelineCache` a singleton (like `ImageLoaderSingleton`)

**Files Modified**:
- `RoomTimelineCache.kt`: Converted from class to singleton object
- `AppViewModel.kt`: Updated to use singleton cache
- `AppViewModel.kt`: Added `navigateToRoomWithCache()` method

## How Navigation Methods Work

### 1. RoomListScreen Navigation
```
User clicks room → RoomListScreen → AppViewModel.navigateToRoomWithCache() → RoomTimelineScreen
```

**Characteristics**:
- Uses existing AppViewModel instance with WebSocket connection
- Access to singleton cache
- Full room state, profiles, and avatars available
- Fast due to cache-first navigation

### 2. Notification Navigation
```
User taps notification → MainActivity → Intent processing → RoomListScreen → RoomTimelineScreen
```

**Intent Flow**:
```kotlin
// EnhancedNotificationDisplay.kt
val intent = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    putExtra("room_id", notificationData.roomId)  // Direct room ID
    putExtra("event_id", notificationData.eventId)
    putExtra("direct_navigation", true)           // Optimization flag
    putExtra("from_notification", true)           // Source identification
    data = android.net.Uri.parse("matrix:roomid/...")  // Fallback URI
}
```

**MainActivity Processing**:
```kotlin
// MainActivity.kt
val directRoomId = intent.getStringExtra("room_id")
if (directNavigation && roomId != null) {
    // Fast path - room ID already extracted
    appViewModel.setDirectRoomNavigation(roomId)
} else {
    // Fallback to URI parsing for legacy intents
    val uriRoomId = extractRoomIdFromMatrixUri(matrixUri)
    appViewModel.setDirectRoomNavigation(uriRoomId)
}
```

**Characteristics**:
- Uses MainActivity with established WebSocket connection
- Fast intent processing with direct room ID
- Access to singleton cache
- Full room state, profiles, and avatars available

### 3. Conversation Shortcut Navigation
```
User taps conversation shortcut → MainActivity → Intent processing → RoomListScreen → RoomTimelineScreen
```

**Intent Flow**:
```kotlin
// ConversationsApi.kt
val intent = android.content.Intent(context, MainActivity::class.java).apply {
    action = android.content.Intent.ACTION_VIEW
    data = android.net.Uri.parse(matrixUri)
    putExtra("room_id", shortcut.roomId)          // Direct room ID
    putExtra("direct_navigation", true)            // Optimization flag
    flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK  // New task
}
```

**Characteristics**:
- Uses MainActivity with established WebSocket connection
- Creates new task (clears existing app state)
- Access to singleton cache
- Full room state, profiles, and avatars available

### 4. ShortcutActivity (Created but Not Used)
```
User taps shortcut → ShortcutActivity → New AppViewModel → RoomTimelineScreen
```

**Why Not Used**:
- Creates new AppViewModel instance without WebSocket connection
- No access to room state, profiles, or avatars
- Would require establishing new WebSocket connection (not allowed - only one connection per backend)

## Cache Architecture

### Before Optimization
```
AppViewModel A → RoomTimelineCache A (instance 1)
AppViewModel B → RoomTimelineCache B (instance 2)  // Different cache!
```

### After Optimization
```
AppViewModel A → RoomTimelineCache (singleton)
AppViewModel B → RoomTimelineCache (singleton)     // Same cache!
```

**Implementation**:
```kotlin
// RoomTimelineCache.kt
object RoomTimelineCache {
    // Singleton cache shared across all AppViewModel instances
    private val roomEventsCache = mutableMapOf<String, RoomCache>()
    
    fun getCachedEventCount(roomId: String): Int {
        return roomEventsCache[roomId]?.events?.size ?: 0
    }
    
    fun getCachedEvents(roomId: String): List<TimelineEvent>? {
        return roomEventsCache[roomId]?.events?.toList()
    }
}
```

## Performance Results

### Before Optimization
- **RoomListScreen**: Fast (uses existing AppViewModel + cache)
- **Shortcuts**: Slow (new AppViewModel + no cache access)
- **Notifications**: Slow (new AppViewModel + no cache access)

### After Optimization
- **RoomListScreen**: Fast (uses existing AppViewModel + singleton cache)
- **Shortcuts**: Fast (uses MainActivity + singleton cache)
- **Notifications**: Fast (uses MainActivity + singleton cache)

## Key Learnings

1. **WebSocket Connection**: Only one connection per backend - can't create new connections
2. **AppViewModel Instances**: Can't share instances across activities due to Compose lifecycle
3. **Cache Sharing**: Must use singleton pattern for cross-instance cache access
4. **Activity Selection**: MainActivity has WebSocket connection, ShortcutActivity doesn't
5. **Intent Processing**: Direct room ID access is much faster than URI parsing

## Files Modified

### Core Files
- `AppViewModel.kt`: Added direct navigation methods and cache-first logic
- `RoomTimelineCache.kt`: Converted to singleton object
- `MainActivity.kt`: Updated intent processing for direct navigation
- `RoomListScreen.kt`: Updated to use cache-first navigation

### Navigation Files
- `EnhancedNotificationDisplay.kt`: Updated notification intents
- `ConversationsApi.kt`: Updated conversation shortcut intents
- `ShortcutActivity.kt`: Created but not used due to WebSocket limitations

### Manifest
- `AndroidManifest.xml`: Added ShortcutActivity registration

## Conclusion

The optimization successfully made shortcut navigation as fast as RoomListScreen navigation by:
1. Eliminating pending state management overhead
2. Optimizing intent processing with direct room ID access
3. Implementing singleton cache for cross-instance access
4. Using MainActivity for all navigation methods to maintain WebSocket connection

All navigation methods now provide the same fast, cache-first experience with full room functionality.
