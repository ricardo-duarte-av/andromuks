# Offline Connection Indicator

A pulsing `CloudOff` icon in `MaterialTheme.colorScheme.error` is shown in every screen's header when the WebSocket is not in `ConnectionState.Ready`. It is driven by `SyncRepository.connectionState.collectAsState()` and uses an `infiniteRepeatable` alpha animation (0.4 → 1.0, 800 ms, `RepeatMode.Reverse`) inside an `AnimatedVisibility`.

## Placement per Screen

| Screen | Position |
|---|---|
| `RoomListScreen` | Between the user Column and the Create Room (`AddCircle`) icon; button order: CloudOff · AddCircle · Notifications · Settings |
| `RoomTimelineScreen` (`RoomHeader`) | To the left of the `Notifications` icon button |
| `BubbleTimelineScreen` (`BubbleRoomHeader`) | First item in the trailing icons Row, before the "Open in app" button |
| `ThreadViewerScreen` | Trailing item in the header Row, after the thread title Column |

## Connection State Source

Sourced directly from `SyncRepository.connectionState` (a `StateFlow<ConnectionState>`). `isReady()` is the extension function in `ConnectionState.kt` that returns `true` only for `ConnectionState.Ready`.
