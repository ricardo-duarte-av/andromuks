# Optimization #11: Conversation Shortcuts Update - Analysis

## User's Questions

### 1. Are these just the 4 shortcuts that show up on long-pressing the app icon, or does this also include shortcuts the user created for any room in the desktop?

**Answer: ✅ YES - Just the 4 dynamic shortcuts on long-press**

**Evidence:**
- `MAX_SHORTCUTS = 4` (ConversationsApi.kt line 51)
- Uses `ShortcutManagerCompat.pushDynamicShortcut()` - Android's dynamic shortcuts API
- These are **Android system shortcuts** shown when long-pressing the app icon
- **NOT** user-created desktop shortcuts (those would be "pinned shortcuts" which are different)

**How Users See Them:**
- Long-press app icon → Shows up to 4 conversation shortcuts
- Tap shortcut → Opens room directly
- Shortcuts automatically update based on recent/unread rooms

### 2. What happens when we update a shortcut? The icon or room name etc?

**Answer: When a shortcut is updated, the following can change:**

**Updated Properties (from `createShortcutInfoCompat()`):**
1. **Room Name** (`setShortLabel`, `setLongLabel`)
   - From `RoomItem.name`

2. **Icon/Avatar** (`setIcon`)
   - Downloads avatar from `roomAvatarUrl` if not cached
   - Creates fallback icon with initials if avatar unavailable
   - Icon is circular/adaptive bitmap

3. **Intent** (stays same room ID, but recreated)
   - Matrix URI: `matrix:roomid/...`
   - Opens `MainActivity` with `room_id` extra

**What Doesn't Change:**
- Unread count (shown as badge by Android system)
- Last message preview (shown in shortcut UI by Android system)

**Update Logic (from `shortcutsNeedUpdate()`):**
- ✅ Checks if room IDs changed (new rooms in top 4)
- ✅ Checks if room names changed
- ✅ Checks if avatar URLs changed
- ✅ Checks if avatars need downloading
- ✅ Only updates if something actually changed

### 3. What is the app workflow for these updates?

**Current Workflow:**

**Called From:**
1. **AppViewModel.processParsedSyncResult()** (lines 3454, 3535, 3575, 3968)
   - Foreground: Every sync (but debounced internally)
   - Background: Every 10 syncs (throttled)

2. **EnhancedNotificationDisplay.kt** (on notification arrival)
   - Triggers async update

**Update Flow:**
```
updateConversationShortcuts(sortedRooms)
  ↓
[Debounce 30 seconds]
  ↓
createShortcutsFromRooms(rooms)
  ├─ Filters rooms with valid timestamp
  ├─ Sorts by: unread count → timestamp
  └─ Takes top 4 rooms (MAX_SHORTCUTS)
  ↓
shortcutsNeedUpdate(newShortcuts)
  ├─ Compares IDs (have shortcuts changed?)
  ├─ Compares names/avatars (have they changed?)
  └─ Checks if avatars need downloading
  ↓
updateShortcuts(shortcuts) [Only if needed]
  ├─ Rate limiting (5 min cooldown, unless avatars need download)
  └─ For each shortcut:
      ├─ createShortcutInfoCompat(shortcut)
      │   ├─ Downloads avatar if needed
      │   └─ Creates icon (avatar or fallback)
      └─ ShortcutManagerCompat.pushDynamicShortcut()
          └─ Updates shortcut in Android system
```

**Key Points:**
- Processes **ALL rooms** (588 rooms), sorts them, takes top 4
- Has debouncing (30 seconds) and rate limiting (5 minutes)
- Only updates if shortcuts actually changed
- Runs in background thread (`Dispatchers.IO`)

## Current Performance

### What Happens Per Update

**When `updateConversationShortcuts()` is called:**

1. **Sorts ALL rooms** (588 rooms):
   - Primary: Unread count (descending)
   - Secondary: Timestamp (descending)
   - **Cost: ~2-5ms for 588 rooms**

2. **Takes top 4 rooms**

3. **Creates shortcuts**:
   - Downloads avatars if needed (~100-500ms per avatar)
   - Creates icons (~1-5ms per shortcut)
   - **Cost: ~0-2 seconds (if avatars need download)**

4. **Updates shortcuts** (only if changed):
   - `pushDynamicShortcut()` per shortcut
   - **Cost: ~1-5ms per shortcut**

**Total Cost:**
- If no avatar downloads: ~3-10ms
- If avatars need download: ~500ms-2s

### Frequency

**Foreground:**
- Called every sync (but debounced 30 seconds internally)
- **Actual updates:** Every 30 seconds + 5 minute cooldown

**Background:**
- Called every 10 syncs
- **Actual updates:** Every 10 syncs + 5 minute cooldown

## Optimization Opportunity

### User's Suggestion: "Update only for changed rooms, not all rooms"

**Current Problem:**
- Processes ALL 588 rooms every time
- Sorts all 588 rooms
- Takes top 4

**Optimized Approach:**
- Only process rooms that actually changed in sync_complete
- Check if any of the top 4 shortcuts need updating
- Only re-sort if changed rooms affect the top 4

**Benefits:**
- Skip sorting all 588 rooms if no relevant changes
- Process only 2-3 changed rooms instead of 588
- **Savings: ~2-5ms per sync (when shortcuts don't need update)**

## Implementation Strategy

### Current Flow
```
sync_complete arrives
  ↓
processParsedSyncResult()
  ↓
Sort ALL 588 rooms
  ↓
Take top 4
  ↓
Check if shortcuts need update
  ↓
Update if needed
```

### Optimized Flow
```
sync_complete arrives
  ↓
processParsedSyncResult()
  ↓
Check which rooms changed in sync
  ↓
If changed rooms could affect top 4:
  ├─ Sort only relevant rooms (changed + current top 4)
  └─ Check if shortcuts need update
  ↓
Update if needed
Else:
  └─ Skip (shortcuts unchanged)
```

**Key Insight:**
- Top 4 shortcuts only change if:
  1. A changed room becomes top 4 (high unread/new message)
  2. A current top 4 room's data changed (name/avatar)
  3. Sorting order changes (unread count or timestamp change)

**Implementation:**
- Track current top 4 shortcut room IDs
- Check if changed rooms affect top 4
- Only re-sort if needed
- Process only changed rooms + current top 4

## Battery Impact

### Current Cost (Background)
- Every 10 syncs: Sort 588 rooms (~2-5ms)
- Every update: Process shortcuts (~3-10ms)
- **Total: ~5-15ms every 10 syncs**

### Optimized Cost (Background)
- Every 10 syncs: Check changed rooms (~0.1ms)
- Only if needed: Sort relevant rooms (~0.5-1ms)
- Every update: Process shortcuts (~3-10ms)
- **Total: ~0.1-1ms (when no changes) or ~3-11ms (when changes)**

**Savings:**
- **~4-14ms saved** when shortcuts don't need updating
- **Most of the time** shortcuts don't need updating (same top 4 rooms)

## Conclusion

✅ **User's theory is correct:**
- Update only for changed rooms (not all rooms)
- This becomes much lighter
- **Don't skip entirely when backgrounded** - just optimize it

**Implementation:**
- Track current top 4 shortcut room IDs
- Only re-sort/update if changed rooms affect top 4
- Process only changed rooms + current top 4
- Much lighter than processing all 588 rooms every time

