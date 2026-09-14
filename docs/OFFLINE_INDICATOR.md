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

## Slow Link Indicator

Connected is not the same as fresh. On weak Wi-Fi the socket can be `Ready` while messages trickle in, and a binary indicator showed the same "all good" state as a healthy link. So a third state exists: a **static** `NetworkCheck` icon in `colorScheme.tertiary` (`ui/components/SlowLinkIcon`), shown when `connectionState.isReady() && SyncRepository.linkSlow`. It does not pulse — it is a caveat about freshness, not an error.

- `RoomListScreen` — `ConnectionStatusIndicator` shows it *instead of* the green `CloudDone`.
- `RoomTimelineScreen`, `BubbleTimelineScreen`, `ThreadViewerScreen` — beside the `CloudOff` slot (the two are mutually exclusive, since one needs `Ready` and the other its absence).

`linkSlow` is computed by `WebSocketService.refreshLinkSlow()` on every unified-monitoring tick from the pure `utils/LinkQuality.isLinkSlow`: slow when the latest pong is overdue by more than `SLOW_LINK_THRESHOLD_MS` (5 s), when the last round-trip took longer than that, or when bytes are arriving but no complete frame has for that long (a large frame crawling in — see [WEBSOCKET_LIFECYCLE.md](WEBSOCKET_LIFECYCLE.md#weak-links-byte-level-liveness)). The monitoring tick drops to `MONITOR_INTERVAL_READY_VISIBLE_MS` (2 s) while `Ready` and a surface is visible so the icon reacts promptly; it stays at 15 s in the background. `clearWebSocket` resets the flag, and each change is logged to Androlog category `"ping"`.
