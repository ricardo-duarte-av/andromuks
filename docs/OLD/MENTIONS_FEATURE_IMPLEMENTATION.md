# Mentions Feature Implementation

## Overview
This document describes the implementation of a mentions feature that displays all times the user was mentioned across all rooms, rendered chronologically.

## Architecture

### 1. Navigation
- **Tab**: Added `MENTIONS` to `RoomSectionType` enum
- **Tab Button**: Added "Mentions" tab in `RoomListScreen` TabBar
- **Route**: Added `"mentions"` route in `MainActivity.kt`
- **Screen**: Created `MentionsScreen.kt` similar to `RoomTimelineScreen.kt`

### 2. Data Flow
```
User opens Mentions tab
    ↓
MentionsScreen loads
    ↓
AppViewModel.requestMentionsList() → Backend returns [{room_id, event_id}, ...]
    ↓
For each {room_id, event_id}:
    AppViewModel.requestEventByRoomAndId(roomId, eventId) → Backend returns event JSON
    ↓
Convert to TimelineEvent objects
    ↓
Sort chronologically by timestamp
    ↓
Group by date
    ↓
Render with date dividers, room context, sender info, message content
```

### 3. Backend API Methods (in AppViewModel)

#### `requestMentionsList()`
- Sends WebSocket command: `"get_mentions"`
- Returns: List of `MentionEntry(roomId: String, eventId: String)`
- Handles response via callback: `mentions_list_response`

#### `requestEventByRoomAndId(roomId: String, eventId: String)`
- Sends WebSocket command: `"get_event"`
- Parameters: `{"room_id": roomId, "event_id": eventId}`
- Returns: `TimelineEvent` object
- Handles response via callback: `get_event_response`

### 4. Data Models

```kotlin
data class MentionEntry(
    val roomId: String,
    val eventId: String,
    val timestamp: Long = 0L // Will be populated from event
)

data class MentionEvent(
    val mentionEntry: MentionEntry,
    val event: TimelineEvent,
    val roomName: String?,
    val roomAvatarUrl: String?
)
```

### 5. UI Components

#### MentionsScreen
- Similar structure to `RoomTimelineScreen`
- Uses `LazyColumn` for scrollable list
- Date dividers (same as timeline)
- Each mention shows:
  - Room name/avatar (or room_id if name not available)
  - Sender name/avatar (or sender_id if name not available)
  - Message content (using existing `TimelineEventItem` or custom component)
  - Clickable to navigate to room timeline at that event

### 6. Features
- **Lazy Loading**: Fetch mentions when screen opens/switches to
- **Chronological Order**: Sort by event timestamp (newest first or oldest first?)
- **Date Grouping**: Group mentions by date with dividers
- **Room Context**: Show which room each mention came from
- **Navigation**: Clicking a mention navigates to `room_timeline/{roomId}` and scrolls to that event
- **Pull to Refresh**: Refresh mentions list from backend
- **Loading States**: Show loading indicator while fetching

## Implementation Steps

1. ✅ Add `MENTIONS` to `RoomSectionType` enum
2. ✅ Add Mentions tab button in `RoomListScreen`
3. ✅ Add mentions route in `MainActivity.kt`
4. ⏳ Create `MentionsScreen.kt`
5. ⏳ Add backend API methods in `AppViewModel`
6. ⏳ Create data models for mention entries
7. ⏳ Implement chronological rendering with date dividers

## Backend Contract

### Request: `get_mentions`
```json
{
  "command": "get_mentions",
  "request_id": 123
}
```

### Response: `mentions_list_response`
```json
{
  "request_id": 123,
  "mentions": [
    {"room_id": "!abc:example.com", "event_id": "$xyz123"},
    {"room_id": "!def:example.com", "event_id": "$xyz456"}
  ]
}
```

### Request: `get_event`
```json
{
  "command": "get_event",
  "request_id": 124,
  "data": {
    "room_id": "!abc:example.com",
    "event_id": "$xyz123"
  }
}
```

### Response: `get_event_response`
```json
{
  "request_id": 124,
  "event": {
    // Standard Matrix event JSON
  }
}
```

