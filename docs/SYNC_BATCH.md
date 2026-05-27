# SyncBatchProcessor

Batches `sync_complete` messages while the app is backgrounded and flushes them as a single unit on foreground resume.

## Always-on mode (default)

- While the app is backgrounded, every arriving `sync_complete` is appended to `batchQueue` instead of being applied directly. A `batchJob` coroutine is scheduled with a 500 ms delay; on expiry it runs `flushBatchLocked` to apply the entire queue atomically.
- During a flush, `shouldSkipTimelineRebuild = true` suppresses per-event `buildTimelineFromChain()` calls so the visible timeline doesn't churn while merging dozens of events.
- `triggerDeferredRebuild()` then performs a single rebuild for `currentRoomId` when the batch ends.
- `timelineRefreshTrigger` in `AppViewModel` is incremented twice on foreground:
  1. Immediately, to show the cached state.
  2. After the batch flush, to pick up the just-merged events.
- `RoomTimelineScreen` watches `timelineRefreshTrigger` with **a single `LaunchedEffect`** that calls `requestRoomTimeline()` and then unconditionally updates `lastKnownRefreshTrigger`. Do not split this into two effects with the same key — the second would race to update the tracker before the first can act on it.

## Sidecar-mode bypass

When `sidecarModeEnabled = true` (mirrored from `useSidecarMode` by `SettingsCoordinator`), `processSyncComplete` always takes the immediate path regardless of app visibility, so **no `batchJob` is ever scheduled**.

The WebSocket is torn down 15 s after backgrounding in sidecar mode, so there is no ongoing stream to batch — the delayed flusher would only fire a wasted wakeup against a dead socket. Anything missed while disconnected comes back via `clear_state` + resync on reconnect (or `last_received_event` resume if eligible), not from a replay queue.

See [WEBSOCKET_LIFECYCLE.md](WEBSOCKET_LIFECYCLE.md#work-modes-always-on-vs-sidecar-battery-saver) for the broader work-mode discussion.
