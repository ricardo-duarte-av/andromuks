# Android Bubble Implementation (Repository + WebSocket Queue)

This document captures the architecture that now powers chat bubbles in Andromuks.  
It supersedes the previous “issues” write‑up and explains how bubbles reuse the same
state and WebSocket connection as the main timeline screens.

## High-Level Flow

```
WebSocketService (foreground service)
        │
        ▼
NetworkUtils.kt  ──► WebSocket callback queue
        │                     │
        │                     ├─ AppViewModel (Main Activity)
        │                     └─ AppViewModel (Bubble Activity)
        ▼
SyncIngestor + RoomRepository (single source of truth)
        │
        ├─ RoomTimelineScreen (main app)
        └─ BubbleTimelineScreen (chat bubble)
```

### Key building blocks

| Component | Responsibility |
| --- | --- |
| **RoomRepository** | Singleton `StateFlow` holder for rooms, timelines, profiles, receipts, etc. |
| **AppViewModel** | Activity-scoped orchestrator that mirrors repository state, routes commands to the WebSocket, and exposes UI helpers. |
| **WebSocketService** | Foreground service that keeps the gomuks socket alive, hosts the callback queue and pinger. |
| **NetworkUtils** | Dispatches every incoming message to **all** registered AppViewModel instances. |
| **BubbleTimelineScreen** | Clone of `RoomTimelineScreen` optimised for bubble UX. |

Both activities create their own `AppViewModel`, but because the heavy lifting lives in
`RoomRepository` and the callback queue fans out messages, they render the same data
and stay in sync.

## WebSocket Callback Queue

- `WebSocketService` now tracks a list of registered listeners (identified by `viewModelId`).
- Each AppViewModel registers on start (`setWebSocket(...)`) and unregisters in `onCleared`.
- Incoming messages (e.g. `sync_complete`, `typing`, `run_id`) are cloned and forwarded to
  every registered ViewModel, so bubbles receive the exact same dispatches as the main app.
- Outgoing commands (`sendMessage`, `paginate`, etc.) flow through the same service methods,
  so there is still only one physical WebSocket connection.

## Repository Integration

1. **Phase 1 – Repository foundation**  
   `RoomRepository` exposes `StateFlow`s for room summaries, timelines, members, profiles.

2. **Phase 2 – ViewModel observers**  
   `AppViewModel` subscribes to the repository instead of keeping isolated copies.
   Any change persisted by `SyncIngestor` (or loaded by `BootstrapLoader`) appears in both
   activities immediately.

3. **Phase 4 – WebSocket queue**  
   Finalises the shared WebSocket access so bubbles and the main activity never race to own
   the callback.

## BubbleTimelineScreen vs RoomTimelineScreen

- `BubbleTimelineScreen` is a near line-for-line clone of `RoomTimelineScreen`.
- Only bubble-specific tweaks remain (compact header actions, keyboard behaviour, open-in-app).
- **Important:** whenever behaviour changes in one screen, mirror it in the other.  
  Both files now contain an explicit comment referencing this doc.

## Message Alignment / Current User

- The bubble screen resolves the current user via `AppViewModel.currentUserId`.
- If the ViewModel has not populated it yet, the screen falls back to
  `SharedPreferences` (`current_user_id`) so outbound messages still render on the right.

## Keyboard Behaviour

- The main timeline still performs explicit keyboard compensation.  
- Bubbles rely on the system to resize the floating window, so the IME scroll shim is removed.

## Lifecycle Notes

- `ChatBubbleActivity` marks the ViewModel as a bubble instance, reuses repository state,
  and calls `attachToExistingWebSocketIfAvailable()` during `onResume`.
- Back press or close icon only minimises the bubble (`moveTaskToBack(true)`); the service keeps
  running.
- An “Open in app” header action launches `MainActivity` with direct navigation extras.

## When Adding New Timeline Behaviour

1. Update both `RoomTimelineScreen.kt` and `BubbleTimelineScreen.kt`.  
   (Search for the “Keep this screen in sync” comment.)
2. Consider whether the change belongs in the repository or in the screen.
3. Confirm that the bubble still receives the expected WebSocket messages (logcat tag
   `WebSocketService` for callback registration).
4. Run `./gradlew compileDebugKotlin` to catch mismatched imports after each edit.

## Troubleshooting Checklist

| Symptom | Likely Cause | Fix |
| --- | --- | --- |
| Bubble shows history but not new messages | ViewModel not registered, or callback queue not running | Check `WebSocketService.getRegisteredViewModels()` logs |
| Own messages render on the left | `current_user_id` blank | Ensure gomuks client_state landed, or bubble fallback is in place |
| Bubble timelines diverge from main app | One screen updated without mirroring | Sync logic between timeline files |
| Memory spikes when opening bubble | Investigate `RoomTimelineCache` size, profile cache trimming | Use `logProfileCacheStats` utilities |

## References

- `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`
- `app/src/main/java/net/vrkknn/andromuks/RoomRepository.kt`
- `app/src/main/java/net/vrkknn/andromuks/WebSocketService.kt`
- `app/src/main/java/net/vrkknn/andromuks/BubbleTimelineScreen.kt`
- `app/src/main/java/net/vrkknn/andromuks/RoomTimelineScreen.kt`
- `app/src/main/java/net/vrkknn/andromuks/ChatBubbleActivity.kt`

Keep these components aligned to ensure bubbles remain a first-class citizen in the Andromuks UX.

