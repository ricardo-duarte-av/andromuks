# Narrator Functions Guide

## Overview

The `NarratorFunctions.kt` file contains composable functions that render system events and special message types in a narrator style within the Matrix chat timeline. These functions provide a consistent way to display room activities, user actions, and special message types with proper formatting and user interaction capabilities.

## Core Functions

### 1. SystemEventNarrator

**Purpose**: Renders system events like room member changes, room updates, and pinned events.

**Features**:
- Displays actor avatar (clickable for user navigation)
- Shows formatted system event descriptions
- Handles different event types with appropriate messaging
- Smart timestamp formatting (time for today, date+time for other days)

**Event Types Handled**:
- `m.room.member`: User joins, leaves, invites, bans, kicks, profile changes
- `m.room.name`: Room name changes
- `m.room.topic`: Room topic changes  
- `m.room.avatar`: Room avatar changes
- `m.room.pinned_events`: Pinned event changes with detailed comparison

**Parameters**:
```kotlin
fun SystemEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel? = null,
    roomId: String,
    onUserClick: (String) -> Unit = {}
)
```

### 2. EmoteEventNarrator

**Purpose**: Renders emote messages (`m.emote`) in narrator style, showing the action as if the user performed it.

**Features**:
- Displays actor avatar (clickable for user navigation)
- Shows emote text in narrator format: "[Display name] [action text]"
- Context menu for reply, react, edit, delete actions
- Smart timestamp formatting

**Example**: "Alice really likes onions" (for `/me really likes onions`)

**Parameters**:
```kotlin
fun EmoteEventNarrator(
    event: TimelineEvent,
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    onReply: () -> Unit = {},
    onReact: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onUserClick: (String) -> Unit = {}
)
```

### 3. PinnedEventNarration

**Purpose**: Handles pinned event narration with detailed event loading and display.

**Features**:
- Shows which event was pinned
- Clickable event ID that loads and displays the full event
- Dialog with complete event information
- User navigation support in the dialog
- Loading states and error handling

**Parameters**:
```kotlin
private fun PinnedEventNarration(
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    pinnedEventId: String,
    appViewModel: AppViewModel?,
    roomId: String,
    onUserClick: (String) -> Unit = {}
)
```

### 4. UnpinnedEventNarration

**Purpose**: Handles unpinned event narration with detailed event loading and display.

**Features**:
- Shows which event was unpinned
- Clickable event ID that loads and displays the full event
- Dialog with complete event information
- User navigation support in the dialog
- Loading states and error handling

**Parameters**:
```kotlin
private fun UnpinnedEventNarration(
    displayName: String,
    avatarUrl: String?,
    homeserverUrl: String,
    authToken: String,
    unpinnedEventId: String,
    appViewModel: AppViewModel?,
    roomId: String,
    onUserClick: (String) -> Unit = {}
)
```

## Pinned Events Logic

The pinned events handling includes sophisticated logic to detect changes:

### Change Detection
- **New pins**: Events added to the pinned list
- **Unpins**: Events removed from the pinned list  
- **Mixed changes**: Both pins and unpins in the same event

### Rendering Logic
```kotlin
when {
    newlyPinned.isNotEmpty() && unpinned.isEmpty() -> {
        // Only new pins - show specific event or count
    }
    unpinned.isNotEmpty() && newlyPinned.isEmpty() -> {
        // Only unpins - show specific event or count
    }
    newlyPinned.isNotEmpty() && unpinned.isNotEmpty() -> {
        // Mixed changes - show generic message
    }
}
```

## Smart Timestamp Formatting

### formatSmartTimestamp Function

**Purpose**: Intelligently formats timestamps based on when the event occurred.

**Logic**:
- **Today's events**: Shows time only (e.g., "14:30")
- **Other days**: Shows date and time (e.g., "15/12/2024 14:30")

**Implementation**:
```kotlin
private fun formatSmartTimestamp(timestamp: Long): String {
    val eventDate = java.util.Date(timestamp)
    val today = java.util.Date()
    
    val eventCalendar = java.util.Calendar.getInstance()
    val todayCalendar = java.util.Calendar.getInstance()
    
    eventCalendar.time = eventDate
    todayCalendar.time = today
    
    val isToday = eventCalendar.get(java.util.Calendar.YEAR) == todayCalendar.get(java.util.Calendar.YEAR) &&
                  eventCalendar.get(java.util.Calendar.DAY_OF_YEAR) == todayCalendar.get(java.util.Calendar.DAY_OF_YEAR)
    
    return if (isToday) {
        // Show time for today
        val timeFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        timeFormatter.format(eventDate)
    } else {
        // Show date and time for other days
        val dateTimeFormatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        dateTimeFormatter.format(eventDate)
    }
}
```

## User Navigation Integration

### Avatar Clickability

All narrator functions now support user navigation through clickable avatars:

**Implementation**:
```kotlin
AvatarImage(
    mxcUrl = avatarUrl,
    homeserverUrl = homeserverUrl,
    authToken = authToken,
    fallbackText = displayName,
    size = 20.dp,
    userId = event.sender,
    displayName = displayName,
    modifier = Modifier.clickable { onUserClick(event.sender) }
)
```

### Navigation Pattern

The `onUserClick` callback follows the standard navigation pattern:
```kotlin
onUserClick = { userId ->
    navController.navigate("user_info/${java.net.URLEncoder.encode(userId, "UTF-8")}")
}
```

## Integration Points

### TimelineEventItem.kt
- `SystemEventNarrator` called for system events
- `EmoteEventNarrator` called for emote messages
- Both receive `onUserClick` parameter for navigation

### ChatBubbleScreen.kt  
- `EmoteEventNarrator` called for emote messages in chat bubbles
- Uses no-op `onUserClick` since chat bubbles don't support navigation

### RoomTimelineScreen.kt
- All narrator functions receive the navigation callback
- Enables consistent user navigation across the timeline

## Event Type Coverage

### System Events
- **Member Events**: Join, leave, invite, ban, kick, profile changes
- **Room Events**: Name, topic, avatar changes
- **Pinned Events**: Pin, unpin, mixed changes

### Message Types
- **Emote Messages**: `/me` commands rendered as narrator text
- **Encrypted Emotes**: Same handling for encrypted emote messages

## Styling and UX

### Visual Design
- **Avatars**: Small (20dp), clickable, consistent styling
- **Text**: Italic, muted colors for narrator text
- **Timestamps**: Smart formatting based on date
- **Layout**: Horizontal layout with avatar, text, and timestamp

### User Interactions
- **Avatar Clicks**: Navigate to user info
- **Event ID Clicks**: Load and display full event details
- **Context Menus**: Available for emote messages
- **Loading States**: Proper feedback during event loading

## Performance Considerations

### Lazy Loading
- Event details loaded only when requested
- Caching of loaded events to avoid re-fetching
- Error handling for failed event loads

### Memory Management
- Proper cleanup of loaded events
- Efficient state management with `remember`
- Minimal recomposition through stable keys

## Future Enhancements

### Potential Improvements
- **Thread Support**: Handle thread-related system events
- **Reaction Support**: Show reactions on narrator events
- **Rich Formatting**: Support for formatted text in narrator events
- **Accessibility**: Better screen reader support for narrator text

### Extensibility
- **Custom Event Types**: Easy addition of new system event types
- **Theme Integration**: Consistent theming across all narrator functions
- **Localization**: Support for different date/time formats

## Usage Examples

### Basic System Event
```kotlin
SystemEventNarrator(
    event = timelineEvent,
    displayName = "Alice",
    avatarUrl = "mxc://example.com/avatar123",
    homeserverUrl = "https://matrix.example.com",
    authToken = "syt_...",
    appViewModel = appViewModel,
    roomId = "!room123:example.com",
    onUserClick = { userId -> navController.navigate("user_info/$userId") }
)
```

### Emote Message
```kotlin
EmoteEventNarrator(
    event = emoteEvent,
    displayName = "Bob",
    avatarUrl = "mxc://example.com/avatar456",
    homeserverUrl = "https://matrix.example.com",
    authToken = "syt_...",
    onReply = { /* handle reply */ },
    onReact = { /* handle reaction */ },
    onEdit = { /* handle edit */ },
    onDelete = { /* handle delete */ },
    onUserClick = { userId -> navController.navigate("user_info/$userId") }
)
```

This comprehensive guide covers all aspects of the narrator functions system, from basic usage to advanced features and integration patterns.
