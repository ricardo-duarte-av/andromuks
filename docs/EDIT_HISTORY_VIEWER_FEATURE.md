# Edit History Viewer Feature

## 🎯 Overview

Messages that have been edited are displayed with a **different bubble color** to indicate modification. Users can long-press the message and tap the **italic "e"** button in the menu to view the complete edit history.

## 🎨 UI Design

### Visual Indicator: Bubble Color Change

**My Messages:**
- Normal: `primaryContainer` (blue)
- **Edited: `secondaryContainer` (purple/teal)** ← Visually distinct!

**Others' Messages:**
- Normal: `surfaceVariant` (light gray)
- **Edited: `surfaceContainerHighest` (darker gray)** ← Subtle difference

### Long-Press Menu

```
      Long Press Message
              ↓
┌──────────────────────────────────┐
│  😊  💬  ✏️  🗑️  e  │  ← Tap italic "e"
└──────────────────────────────────┘
```

**Menu Buttons:**
- 😊 React
- 💬 Reply  
- ✏️ Edit (only on your messages)
- 🗑️ Delete (only if permitted)
- **e** Edit History (only if message edited) ← **NEW!**

## 📱 Edit History Dialog

When the "e" is tapped, shows a dialog with all versions:

```
┌─────────────────────────────────┐
│ Message History            ✕    │
│ 3 versions                       │
├─────────────────────────────────┤
│                                  │
│ Latest Edit        Oct 17, 14:35 │
│ ┌─────────────────────────────┐ │
│ │ This is version 3 (edited)  │ │
│ └─────────────────────────────┘ │
│ ────────────────────────────────│
│                                  │
│ Edit #2            Oct 17, 14:32 │
│ ┌─────────────────────────────┐ │
│ │ This is version 2 (edited)  │ │
│ └─────────────────────────────┘ │
│ ────────────────────────────────│
│                                  │
│ Original           Oct 17, 14:30 │
│ ┌─────────────────────────────┐ │
│ │ This is the original text   │ │
│ └─────────────────────────────┘ │
│                                  │
│         [Close]                  │
└─────────────────────────────────┘
```

### Version Display

Each version shows:
- **Label**: "Original", "Edit #1", "Edit #2", or "Latest Edit"
- **Badge**: "Current" tag on the active version
- **Timestamp**: Full date/time (e.g., "Oct 17, 14:35:23")
- **Content**: The message text from that version
- **Highlight**: Latest version has a subtle background color

## 🔧 Technical Implementation

### 1. Bubble Color Logic

```kotlin
// Check if message has been edited (O(1) lookup)
val hasBeenEdited = remember(event.eventId, appViewModel?.updateCounter) {
    appViewModel?.isMessageEdited(event.eventId) ?: false
}

val bubbleColor = if (actualIsMine) {
    if (hasBeenEdited) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.primaryContainer
} else if (mentionsMe) {
    MaterialTheme.colorScheme.tertiaryContainer
} else {
    if (hasBeenEdited) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surfaceVariant
}
```

### 2. Long-Press Menu Integration

```kotlin
// MessageBubbleWithMenu.kt
@Composable
fun MessageBubbleWithMenu(
    event: TimelineEvent,
    appViewModel: AppViewModel? = null,
    // ... other params
) {
    // Check if edited (O(1))
    val hasBeenEdited = appViewModel?.isMessageEdited(event.eventId) ?: false
    
    // Show menu on long press
    Row {
        IconButton(onClick = { onReact() }) { /* React */ }
        IconButton(onClick = { onReply() }) { /* Reply */ }
        if (canEdit) IconButton(onClick = { onEdit() }) { /* Edit */ }
        if (canDelete) IconButton(onClick = { onDelete() }) { /* Delete */ }
        
        // NEW: Edit History button (only if edited)
        if (hasBeenEdited) {
            IconButton(onClick = { showEditHistory = true }) {
                Text("e", fontStyle = Italic)  // Italic "e"
            }
        }
    }
}
```

### 3. Integration Points

The edit color system is integrated into:
- ✅ Regular text messages (m.room.message)
- ✅ Encrypted text messages (m.room.encrypted)
- ✅ Media messages (via MessageBubbleWithMenu)
- ✅ Encrypted media messages
- ✅ Stickers (m.sticker)
- ✅ All message types with long-press menus!

### 3. Version Extraction

```kotlin
private fun extractBodyFromVersion(version: MessageVersion): String {
    return when {
        // For edit events, extract from m.new_content
        version.event.content?.has("m.new_content") == true -> {
            version.event.content?.optJSONObject("m.new_content")?.optString("body")
        }
        
        // For encrypted edits
        version.event.decrypted?.has("m.new_content") == true -> {
            version.event.decrypted?.optJSONObject("m.new_content")?.optString("body")
        }
        
        // For originals
        else -> version.event.content?.optString("body")
    }
}
```

## 📊 Performance

All operations are **O(1)**:

| Operation | Complexity | Details |
|-----------|-----------|---------|
| Check if edited | **O(1)** | `versioned.versions.size > 1` |
| Get version history | **O(1)** | `appViewModel.getMessageVersions()` |
| Display versions | **O(v)** | Where v = # of versions (typically 1-3) |

No timeline scanning needed!

## 🎬 User Flow

1. User sees a message with **different colored bubble** (edited messages)
2. User **long-presses** the message
3. Menu appears with icons including italic **"e"** button
4. User taps the **"e"** button
5. Dialog opens showing complete edit history
6. User can read all versions chronologically
7. User closes dialog

## 💡 Example Usage

### Scenario: Message edited 3 times

```
Original (14:30): "Hello wrld"
Edit 1 (14:31):   "Hello world"
Edit 2 (14:32):   "Hello world!"
Edit 3 (14:35):   "Hello everyone!"
```

**Timeline Display:**
```
Alice                      14:35
┌────────────────────────────────┐
│ Hello everyone!                │ ← Purple/teal bubble (edited color)
└────────────────────────────────┘
```

**When long-pressed, then "e" tapped in menu:**
```
Message History
3 versions

Latest Edit        Oct 17, 14:35:23
┌────────────────────────────────┐
│ Hello everyone!                │ ← Highlighted
└────────────────────────────────┘

Edit #2            Oct 17, 14:32:45
┌────────────────────────────────┐
│ Hello world!                   │
└────────────────────────────────┘

Edit #1            Oct 17, 14:31:12
┌────────────────────────────────┐
│ Hello world                    │
└────────────────────────────────┘

Original           Oct 17, 14:30:00
┌────────────────────────────────┐
│ Hello wrld                     │
└────────────────────────────────┘
```

## 🔍 Edge Cases

### Message Edited Once
- Shows "e" indicator
- History shows: "Latest Edit" and "Original"

### Message Never Edited
- No "e" indicator shown
- Only the current version exists

### Message Edited Then Deleted
- No "e" indicator shown (deletion takes precedence)
- Shows deletion message instead

### Edit on Encrypted Message
- Works the same way
- Extracts content from `decrypted.m.new_content`

## ⚡ Benefits

1. **Subtle Visual Indicator**: Edited messages use a different color (no UI clutter)
2. **No Layout Issues**: Color change doesn't affect positioning or indentation
3. **Transparency**: Users can see all edits made to messages via long-press
4. **Accountability**: Clear history of what changed and when
5. **Context**: Understand conversation evolution
6. **Performance**: O(1) lookups, no impact on timeline rendering
7. **Completeness**: Works for all message types (text, media, stickers)
8. **Clean UX**: No additional UI elements, uses existing long-press pattern

## 🚀 Future Enhancements

### Version Comparison (Diff View)
```kotlin
fun showDiff(v1: MessageVersion, v2: MessageVersion) {
    val diff = DiffUtils.diff(v1.body, v2.body)
    // Highlight changes in green/red
}
```

### Edit Timestamps in Timeline
```
Alice                      14:35 (edited)
[Message bubble]
```

### Filter by Editor
```
"Show only edits by Alice"
```

---

**Result:** Users can now view the complete edit history of any edited message with a simple tap on the "e" indicator, providing full transparency and context for message edits.

