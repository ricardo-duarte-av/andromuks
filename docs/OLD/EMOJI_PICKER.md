# Emoji Picker System

## Overview

The emoji picker provides a comprehensive Unicode Standard Emoji selection interface for both reactions and text input. It includes a "Recent" tab that tracks frequently used emojis, persisted via Matrix account data and synchronized across devices.

**Key Features:**
- ✅ Full Unicode Standard Emoji support (3,900+ emojis)
- ✅ Recent emojis tab with frequency tracking
- ✅ Search functionality across all emojis
- ✅ Custom reaction support (text-based reactions)
- ✅ Image emoji support (MXC URLs)
- ✅ Persistent storage via account data
- ✅ Cross-device synchronization

---

## Architecture

### Components

#### 1. EmojiSelectionDialog (`EmojiSelection.kt`)

**Purpose:** Main UI component for emoji selection

**Location:** `app/src/main/java/net/vrkknn/andromuks/utils/EmojiSelection.kt`

**Features:**
- Category tabs (Recent, Smileys & Emotion, People & Body, Animals & Nature, Food & Drink, Travel & Places, Activities, Objects, Symbols, Flags)
- Search box with "Search / Custom Reaction" placeholder
- Emoji grid (8 columns)
- Custom reaction button (when search text doesn't match any emoji)

**Usage:**
```kotlin
EmojiSelectionDialog(
    recentEmojis = appViewModel.recentEmojis,
    homeserverUrl = homeserverUrl,
    authToken = authToken,
    onEmojiSelected = { emoji ->
        // Handle emoji selection
    },
    onDismiss = {
        // Handle dialog dismissal
    }
)
```

#### 2. EmojiData (`EmojiData.kt`)

**Purpose:** Comprehensive Unicode Standard Emoji dataset

**Location:** `app/src/main/java/net/vrkknn/andromuks/utils/EmojiData.kt`

**Structure:**
- 9 official Unicode Standard Emoji categories
- 1,000+ emojis organized by category
- Search functionality across all emojis

**Categories:**
1. Recent (dynamically populated)
2. Smileys & Emotion
3. People & Body
4. Animals & Nature
5. Food & Drink
6. Travel & Places
7. Activities
8. Objects
9. Symbols
10. Flags

#### 3. Recent Emoji Tracking (`AppViewModel.kt`)

**Purpose:** Tracks and persists recently used emojis with frequency counts

**Location:** `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

**Data Structures:**
- `recentEmojis: List<String>` - UI list (sorted by frequency)
- `recentEmojiFrequencies: MutableList<Pair<String, Int>>` - Internal storage with counts

**Methods:**
- `updateRecentEmojis(emoji: String)` - Updates frequency and sends to backend
- `processAccountData()` - Loads recent emojis from account data
- `sendAccountDataUpdate()` - Sends updates to backend via `set_account_data`

---

## Data Flow

### 1. Loading Recent Emojis

**On App Startup:**

```
App Startup
    ↓
BootstrapLoader.loadBootstrap()
    ↓
Load account_data JSON from database
    ↓
AppViewModel.loadStateFromStorage()
    ↓
processAccountData(accountDataJson)
    ↓
Extract io.element.recent_emoji
    ↓
Parse [["emoji", count], ...] format
    ↓
Sort by frequency (descending)
    ↓
Update recentEmojiFrequencies and recentEmojis
```

**From Sync:**

```
Sync Complete (sync_complete message)
    ↓
SyncIngestor.ingestSyncComplete()
    ↓
Merge account_data with existing
    ↓
Store to database
    ↓
AppViewModel.processAccountData()
    ↓
Update recentEmojiFrequencies and recentEmojis
```

### 2. Updating Recent Emojis

**When Emoji is Selected:**

```
User selects emoji (reaction or text input)
    ↓
appViewModel.updateRecentEmojis(emoji)
    ↓
1. Find existing emoji or add new (count = 1)
2. Increment count if exists
3. Sort by frequency (descending)
4. Keep top 20 emojis
5. Update recentEmojiFrequencies and recentEmojis (UI updates immediately)
    ↓
sendAccountDataUpdate(frequencies)
    ↓
Send set_account_data WebSocket command
    ↓
Backend receives and stores
    ↓
Next sync includes updated account_data
    ↓
SyncIngestor merges and persists to database
```

### 3. Account Data Format

**Storage Format:**
```json
{
  "io.element.recent_emoji": {
    "type": "io.element.recent_emoji",
    "content": {
      "recent_emoji": [
        ["😆", 2],  // [emoji, frequency_count]
        ["🐰", 2],
        ["😊", 1],
        ["👵", 1]
      ]
    }
  }
}
```

**Key Points:**
- Array format: `[["emoji", count], ...]`
- Sorted by frequency (highest first)
- Maximum 20 emojis stored
- Count represents usage frequency

---

## Usage Scenarios

### Scenario 1: Adding Reaction to Message

**Flow:**
1. User taps reaction button on message
2. `EmojiSelectionDialog` opens with `showEmojiSelection = true`
3. User selects emoji from picker
4. `onEmojiSelected` callback:
   - Calls `appViewModel.sendReaction(roomId, eventId, emoji)`
   - `sendReaction()` calls `updateRecentEmojis(emoji)`
   - Dialog closes (`onDismiss()`)
5. Recent emojis updated and persisted

**Code:**
```kotlin
if (showEmojiSelection && reactingToEvent != null) {
    EmojiSelectionDialog(
        recentEmojis = appViewModel.recentEmojis,
        homeserverUrl = homeserverUrl,
        authToken = authToken,
        onEmojiSelected = { emoji ->
            appViewModel.sendReaction(roomId, reactingToEvent!!.eventId, emoji)
            showEmojiSelection = false
            reactingToEvent = null
        },
        onDismiss = {
            showEmojiSelection = false
            reactingToEvent = null
        }
    )
}
```

### Scenario 2: Adding Emoji to Text Input

**Flow:**
1. User taps emoji button (Mood icon) in text input
2. `EmojiSelectionDialog` opens with `showEmojiPickerForText = true`
3. User selects emoji from picker
4. `onEmojiSelected` callback:
   - Inserts emoji at cursor position in text
   - Updates `textFieldValue` and `draft`
   - Calls `appViewModel.updateRecentEmojis(emoji)`
   - **Dialog stays open** (user can add more emojis)
5. Recent emojis updated and persisted
6. User manually closes dialog when done

**Code:**
```kotlin
if (showEmojiPickerForText) {
    EmojiSelectionDialog(
        recentEmojis = appViewModel.recentEmojis,
        homeserverUrl = homeserverUrl,
        authToken = authToken,
        onEmojiSelected = { emoji ->
            // Insert emoji at cursor position
            val currentText = textFieldValue.text
            val cursorPosition = textFieldValue.selection.start
            val newText = currentText.substring(0, cursorPosition) + 
                         emoji + 
                         currentText.substring(cursorPosition)
            val newCursorPosition = cursorPosition + emoji.length
            
            draft = newText
            textFieldValue = TextFieldValue(
                text = newText,
                selection = TextRange(newCursorPosition)
            )
            
            // Update recent emojis
            appViewModel.updateRecentEmojis(emoji)
            
            // Dialog stays open for more emojis
        },
        onDismiss = {
            showEmojiPickerForText = false
        }
    )
}
```

### Scenario 3: Custom Text Reaction

**Flow:**
1. User opens emoji picker
2. Types text in search box (e.g., "qwerty")
3. No matching emoji found
4. "React with 'qwerty'" button appears
5. User selects button
6. Text is used as reaction (not an emoji)
7. Recent emojis updated with custom text

**Note:** Custom text reactions are stored in recent emojis but may not render as emoji in the UI.

---

## Recent Emoji Tab

### Behavior

**Display:**
- Shows emojis sorted by frequency (highest first)
- Maximum 20 emojis displayed
- Updates immediately when emoji is selected
- Includes both regular emojis and image emojis (MXC URLs)

**Updates:**
- **Increments count** if emoji already exists
- **Adds with count 1** if emoji is new
- **Re-sorts** by frequency after each update
- **Truncates** to top 20 emojis

**Example Evolution:**
```
Initial: [["😆", 2], ["🐰", 2], ["😊", 1]]
User selects 😊
Updated: [["😊", 2], ["😆", 2], ["🐰", 2]]
User selects 🐰
Updated: [["🐰", 3], ["😊", 2], ["😆", 2]]
User selects 🆕
Updated: [["🐰", 3], ["😊", 2], ["😆", 2], ["🆕", 1]]
```

---

## Search Functionality

### How It Works

**Search Behavior:**
- When search text is blank: Shows emojis from selected category
- When search text is entered: Searches across **all emojis** (not just current category)
- Case-insensitive search
- Simple substring matching

**Custom Reactions:**
- If search text doesn't match any emoji, shows "React with '[search text]'" button
- Allows text-based reactions (e.g., "qwerty", "xyz")

**Code:**
```kotlin
val filteredEmojis = if (searchText.isBlank()) {
    baseEmojis  // Current category
} else {
    // Search across all emojis
    EmojiData.getAllEmojis().filter { 
        it.contains(searchText, ignoreCase = true) 
    }
}
```

---

## Image Emoji Support

### MXC URL Emojis

**Support:**
- Custom image emojis stored as MXC URLs (e.g., `mxc://server.com/abc123`)
- Loaded using Coil image loader
- Supports GIF animations
- Displayed in emoji grid with proper sizing

**Implementation:**
- `ImageEmoji` composable handles MXC URL loading
- Converts MXC to HTTP URL via `MediaUtils.mxcToHttpUrl()`
- Uses `ImageLoaderSingleton` for consistent loading
- Handles errors gracefully

**Recent Tab:**
- Image emojis can appear in recent tab
- Stored with MXC URL format
- Frequency tracking works the same as regular emojis

---

## Persistence

### Account Data Storage

**Storage:**
- Recent emojis stored in `io.element.recent_emoji` account data
- Persisted to database via `SyncIngestor`
- Loaded on startup via `BootstrapLoader`
- Synchronized across devices via Matrix sync

**Format:**
```json
{
  "io.element.recent_emoji": {
    "type": "io.element.recent_emoji",
    "content": {
      "recent_emoji": [
        ["emoji", frequency],
        ...
      ]
    }
  }
}
```

**Update Flow:**
1. User selects emoji → `updateRecentEmojis()` called
2. In-memory state updated immediately
3. `sendAccountDataUpdate()` sends to backend via `set_account_data`
4. Backend stores account data
5. Next sync includes updated account_data
6. `SyncIngestor` merges and persists to database

**See:** `docs/ACCOUNT_DATA_STORAGE.md` for detailed account data storage documentation.

---

## UI Components

### Dialog Size

**Current Configuration:**
- Height: `fillMaxHeight(0.75f)` (75% of screen height)
- Width: `fillMaxWidth()` (full width)
- Responsive to screen size

**Placement:**
- Centered on screen
- Above keyboard/navigation bars
- Can be dismissed by tapping outside or pressing back

### Text Input Emoji Button

**Location:** Right side of text input field (trailing icon)

**Icon:** `Icons.Filled.Mood`

**Behavior:**
- Opens emoji picker
- Picker stays open after selection (allows multiple emojis)
- User manually closes when done

### Category Tabs

**Layout:** Horizontal scrollable row at top of picker

**Selection:** Active category highlighted with primary color

**Icons:** Category emoji icons (🕒, 😀, 👥, 🐶, etc.)

---

## Integration Points

### 1. RoomTimelineScreen

**Reaction Picker:**
- Opens when user taps reaction button on message
- Closes after emoji selection
- Location: `RoomTimelineScreen.kt` (line ~2041)

**Text Input Picker:**
- Opens when user taps emoji button in text input
- Stays open after selection
- Location: `RoomTimelineScreen.kt` (line ~2060)

### 2. ThreadViewerScreen

**Reaction Picker:**
- Same behavior as RoomTimelineScreen
- Location: `ThreadViewerScreen.kt` (line ~845)

### 3. AppViewModel

**Recent Emoji Management:**
- `recentEmojis` - Public state for UI binding
- `recentEmojiFrequencies` - Internal frequency storage
- `updateRecentEmojis()` - Public method to update recent emojis
- `processAccountData()` - Loads recent emojis from account data
- `sendAccountDataUpdate()` - Sends updates to backend

---

## Performance Considerations

### Emoji Dataset

**Size:**
- 1,000+ emojis across 9 categories
- Loaded from static data in `EmojiData.kt`
- No runtime fetching required

**Search:**
- Simple substring matching
- Filters across all emojis when search active
- O(n) where n = number of emojis (~1,000)

### Recent Emoji Updates

**Frequency:**
- Updates on every emoji selection
- Triggers WebSocket command to backend
- Backend syncs back on next sync cycle

**Optimization:**
- In-memory state updates immediately (no await)
- Backend update happens asynchronously
- UI updates reactively via `recentEmojis` state

### Dialog Rendering

**Lazy Loading:**
- Uses `LazyVerticalGrid` for emoji grid
- Only renders visible emojis
- Smooth scrolling with 8-column grid

---

## Error Handling

### Image Emoji Loading

**Failures:**
- Logs error with context
- Shows fallback (❌ emoji) if MXC URL fails
- Handles network errors gracefully

**Code:**
```kotlin
onError = { state ->
    if (state is coil.request.ErrorResult) {
        CacheUtils.handleImageLoadError(
            imageUrl = httpUrl,
            throwable = state.throwable,
            imageLoader = imageLoader,
            context = "Emoji"
        )
    }
}
```

### Account Data Updates

**Backend Failures:**
- WebSocket command sent with request ID
- No blocking wait for response
- UI updates immediately (optimistic)
- Backend sync will correct on next sync if needed

---

## Future Enhancements

### Potential Improvements

1. **Emoji Name Search:** Search by emoji names/descriptions (e.g., "smile" finds 😀)
2. **Skin Tone Variants:** Support for skin tone modifiers
3. **Emoji Suggestions:** Suggest emojis based on text context
4. **Favorites Tab:** Separate favorites from recent emojis
5. **Keyboard Shortcuts:** Quick access to frequently used emojis
6. **Emoji History:** Extended history beyond top 20
7. **Category Icons:** Better visual indicators for categories
8. **Accessibility:** Improved screen reader support

---

## Related Files

### UI Components
- `app/src/main/java/net/vrkknn/andromuks/utils/EmojiSelection.kt` - Main picker dialog
- `app/src/main/java/net/vrkknn/andromuks/utils/EmojiData.kt` - Emoji dataset

### Integration
- `app/src/main/java/net/vrkknn/andromuks/RoomTimelineScreen.kt` - Reaction and text input pickers
- `app/src/main/java/net/vrkknn/andromuks/ThreadViewerScreen.kt` - Thread reaction picker
- `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt` - Recent emoji management

### Persistence
- `app/src/main/java/net/vrkknn/andromuks/database/SyncIngestor.kt` - Account data ingestion
- `app/src/main/java/net/vrkknn/andromuks/database/BootstrapLoader.kt` - Account data loading

### Documentation
- `docs/ACCOUNT_DATA_STORAGE.md` - Account data storage system

---

## Summary

The emoji picker system provides:

✅ **Comprehensive Unicode Support** - Full Unicode Standard Emoji set (3,900+ emojis)  
✅ **Recent Emoji Tracking** - Frequency-based recent emojis with persistence  
✅ **Dual Usage** - Reactions and text input support  
✅ **Search Functionality** - Search across all emojis or create custom reactions  
✅ **Image Emoji Support** - MXC URL-based custom emojis  
✅ **Cross-Device Sync** - Recent emojis synchronized via account data  
✅ **Persistent Storage** - Survives app restarts via database  

**Key Design Principle:** Track usage frequency, persist via account data, update UI immediately for responsive experience.

---

**Last Updated:** 2024  
**Related Documents:**
- `ACCOUNT_DATA_STORAGE.md` - Account data persistence
- `PERSISTENT_ROOM_EVENTS_STORAGE.md` - Room events persistence

