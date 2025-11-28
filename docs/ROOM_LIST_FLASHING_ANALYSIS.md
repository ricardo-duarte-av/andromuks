# Room List Flashing/Twitching Analysis

## Problem
The room list visually flashes/twitches on every `sync_complete` message, even though there's a debouncer for sorting. Icons and rooms appear to re-render unnecessarily.

## Root Causes Identified

### 1. **New Object Creation on Every Update**
**Location:** `RoomListScreen.kt:350-360` (enrichedSection)

**Issue:** Even when `stableSection` doesn't change, `enrichedSection` creates new `RoomItem` copies via `.map { room.copy(...) }`. This creates new object instances, causing Compose to think the data changed.

```kotlin
val enrichedSection = remember(stableSection, roomsWithSummaries) {
    stableSection.copy(
        rooms = stableSection.rooms.map { room ->
            val (messagePreview, messageSender) = roomsWithSummaries[room.id] ?: (null to null)
            room.copy(  // ⚠️ Always creates new RoomItem instances
                messagePreview = messagePreview ?: room.messagePreview,
                messageSender = messageSender ?: room.messageSender
            )
        }
    )
}
```

**Impact:** Every recomposition creates new RoomItem objects, triggering LazyColumn to recompose items even if data is identical.

---

### 2. **Deep Comparison Too Sensitive**
**Location:** `RoomListScreen.kt:367-368`

**Issue:** The comparison includes `sortingTimestamp`, which may change slightly even when the room order doesn't actually change (e.g., millisecond differences).

```kotlin
val roomsChanged = stableSection.rooms.map { "${it.id}:${it.unreadCount}:${it.sortingTimestamp}" } != 
                  newSection.rooms.map { "${it.id}:${it.unreadCount}:${it.sortingTimestamp}" }
```

**Impact:** Triggers updates even when the visual order is unchanged.

---

### 3. **roomListUpdateCounter Increments Too Frequently**
**Location:** `AppViewModel.kt` - Multiple locations increment the counter

**Issue:** The counter increments on every sync_complete, even when no visual changes occur (e.g., receipt-only updates, unchanged rooms).

**Impact:** Triggers `LaunchedEffect(roomListUpdateCounter)` unnecessarily, causing recomposition checks.

---

### 4. **AvatarImage Recomposition**
**Location:** `RoomListScreen.kt:1234-1244` (RoomListItem)

**Issue:** Even though AvatarImage has caching, if the `room` object is a new instance (which happens due to issue #1), Compose may recompose the AvatarImage.

**Impact:** Avatars may flash/reload even when cached.

---

### 5. **roomsWithSummaries State Updates**
**Location:** `RoomListScreen.kt:529-531`

**Issue:** `roomsWithSummaries` is updated even when the data hasn't changed, triggering `enrichedSection` recalculation.

```kotlin
roomsWithSummaries = roomsWithSummaries.toMutableMap().apply {
    putAll(summaryMap) // ⚠️ Always creates new map, even if values are identical
}
```

**Impact:** Causes `enrichedSection` to recalculate unnecessarily.

---

## Suggested Fixes

### Fix 1: Memoize RoomItem Copies (Prevent Unnecessary Object Creation)

**Change in `RoomListScreen.kt`:**

```kotlin
// Enrich stableSection with database summaries
// FIX: Only create new RoomItem copies if data actually changed
val enrichedSection = remember(stableSection, roomsWithSummaries) {
    val enrichedRooms = stableSection.rooms.map { room ->
        val (messagePreview, messageSender) = roomsWithSummaries[room.id] ?: (null to null)
        
        // Only create new copy if preview/sender actually changed
        val currentPreview = messagePreview ?: room.messagePreview
        val currentSender = messageSender ?: room.messageSender
        
        if (currentPreview != room.messagePreview || currentSender != room.messageSender) {
            room.copy(
                messagePreview = currentPreview,
                messageSender = currentSender
            )
        } else {
            // Return same instance if unchanged - prevents unnecessary recomposition
            room
        }
    }
    
    // Only create new RoomSection if rooms list actually changed
    if (enrichedRooms === stableSection.rooms) {
        stableSection // Return same instance if unchanged
    } else {
        stableSection.copy(rooms = enrichedRooms)
    }
}
```

**Benefit:** Prevents creating new RoomItem instances when data hasn't changed, reducing recomposition.

---

### Fix 2: Improve Deep Comparison (Ignore Micro Timestamp Changes)

**Change in `RoomListScreen.kt:363-391`:**

```kotlin
// PERFORMANCE: Only update section if data actually changed
LaunchedEffect(roomListUpdateCounter) {
    val newSection = appViewModel.getCurrentRoomSection()
    
    // FIX: Compare only meaningful fields, ignore micro timestamp changes
    // Compare room order (by ID sequence), unread counts, and names
    // Don't compare exact sortingTimestamp (millisecond differences don't matter visually)
    val oldRoomSignature = stableSection.rooms.map { "${it.id}:${it.unreadCount}:${it.highlightCount}:${it.name}" }
    val newRoomSignature = newSection.rooms.map { "${it.id}:${it.unreadCount}:${it.highlightCount}:${it.name}" }
    val roomsChanged = oldRoomSignature != newRoomSignature
    
    // Also check if room order changed (by ID sequence)
    val oldRoomIds = stableSection.rooms.map { it.id }
    val newRoomIds = newSection.rooms.map { it.id }
    val orderChanged = oldRoomIds != newRoomIds
    
    val spacesChanged = stableSection.spaces.map { "${it.id}:${it.name}" } != 
                       newSection.spaces.map { "${it.id}:${it.name}" }
    
    if (newSection.type != stableSection.type) {
        // Section type changed - update with animation
        val oldIndex = RoomSectionType.values().indexOf(previousSectionType)
        val newIndex = RoomSectionType.values().indexOf(newSection.type)
        sectionAnimationDirection = when {
            newIndex > oldIndex -> 1
            newIndex < oldIndex -> -1
            else -> 0
        }
        previousSectionType = stableSection.type
        stableSection = newSection
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Section type changed from ${previousSectionType} to ${newSection.type}")
    } else if (roomsChanged || orderChanged || spacesChanged) {
        // Same section type but data changed - update without animation
        sectionAnimationDirection = 0
        stableSection = newSection
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomListScreen: Section data changed - rooms:$roomsChanged order:$orderChanged spaces:$spacesChanged")
    }
    // If nothing changed, skip update - prevents unnecessary recomposition and avatar flashing
}
```

**Benefit:** Only updates when visual changes occur, ignoring micro timestamp differences.

---

### Fix 3: Prevent Unnecessary roomsWithSummaries Updates

**Change in `RoomListScreen.kt:528-531`:**

```kotlin
// FIX: Only update roomsWithSummaries if values actually changed
val hasChanges = summaryMap.any { (roomId, (newPreview, newSender)) ->
    val (oldPreview, oldSender) = roomsWithSummaries[roomId] ?: (null to null)
    newPreview != oldPreview || newSender != oldSender
}

if (hasChanges) {
    // Merge new summaries with existing ones (preserve JSON-parsed previews for rooms not queried)
    roomsWithSummaries = roomsWithSummaries.toMutableMap().apply {
        putAll(summaryMap) // Overwrite with new DB results
    }
    
    if (BuildConfig.DEBUG) {
        android.util.Log.d("Andromuks", "RoomListScreen: Updated summaries for ${summaryMap.size} rooms")
    }
} else {
    if (BuildConfig.DEBUG) {
        android.util.Log.d("Andromuks", "RoomListScreen: Summaries unchanged, skipping update")
    }
}
```

**Benefit:** Prevents `enrichedSection` recalculation when summaries haven't changed.

---

### Fix 4: Add Stable Keys to RoomListItem Parameters

**Change in `RoomListScreen.kt:1823-1826`:**

```kotlin
RoomListItem(
    room = room,
    homeserverUrl = appViewModel.homeserverUrl, // ⚠️ This might change reference
    authToken = authToken, // ⚠️ This might change reference
    // ... rest of params
)
```

**Better approach:** Use `remember` for stable values:

```kotlin
// At top of RoomListContent
val stableHomeserverUrl = remember { appViewModel.homeserverUrl }
val stableAuthToken = remember(authToken) { authToken }

// Then use in RoomListItem
RoomListItem(
    room = room,
    homeserverUrl = stableHomeserverUrl,
    authToken = stableAuthToken,
    // ...
)
```

**Benefit:** Prevents recomposition when these values haven't actually changed.

---

### Fix 5: Use derivedStateOf for Filtered Rooms

**Change in `RoomListScreen.kt:1688-1697`:**

```kotlin
// FIX: Use derivedStateOf to prevent recalculation on every recomposition
val filteredRooms = remember {
    derivedStateOf {
        if (searchQuery.isBlank()) {
            enrichedSection.rooms
        } else {
            enrichedSection.rooms.filter { room ->
                room.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }
}.value
```

**Note:** Actually, the current `remember(rooms, searchQuery)` is already good. But we should ensure `enrichedSection.rooms` reference stability.

---

## Summary of Recommended Changes

1. **Fix 1 (Memoize RoomItem Copies)** - HIGH PRIORITY
   - Prevents unnecessary object creation
   - Reduces recomposition significantly

2. **Fix 2 (Improve Deep Comparison)** - HIGH PRIORITY
   - Only updates when visual changes occur
   - Ignores micro timestamp differences

3. **Fix 3 (Prevent Unnecessary Summary Updates)** - MEDIUM PRIORITY
   - Prevents cascading recompositions
   - Reduces state updates

4. **Fix 4 (Stable Keys)** - LOW PRIORITY
   - Minor optimization
   - Helps with AvatarImage stability

## Testing Strategy

1. Apply Fix 1 and Fix 2 first (highest impact)
2. Test with frequent sync_complete messages
3. Verify no flashing when:
   - Receipt-only updates arrive
   - Unread counts change
   - Room order doesn't change
4. Apply Fix 3 if still seeing issues
5. Apply Fix 4 as final polish

