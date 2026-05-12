# Element Call

Element Call is the Matrix video/audio calling system, running as a WebView (WebRTC) inside the app.

---

## Architecture

### Key files

| File | Role |
|---|---|
| `CallOverlay.kt` | Full-screen WebView overlay; handles foreground/background toggling via `zIndex` |
| `ElementCallScreen.kt` | Navigation entry point; also hosts `ElementCallJsBridge`, `isRoomEncryptedFromState`, `isCallActiveInRoomState` |
| `CallsWidgetsCoordinator.kt` | Coordinator owning all call state and operations on AppViewModel |
| `IncomingCallBanner.kt` | Floating banner shown when an incoming call notification arrives |
| `RoomTimelineScreen.kt` (RoomHeader) | Call button — pulsing green when a call is live, "Return to call" when we're in it |
| `NarratorFunctions.kt` (CallMemberEventNarrator) | Timeline narrator for call join/leave events |

### State fields on AppViewModel (managed by CallsWidgetsCoordinator)

| Field | Type | Meaning |
|---|---|---|
| `callActiveInternal` | `Boolean` | We are currently in a call |
| `callReadyForPipInternal` | `Boolean` | WebRTC negotiation complete; safe to background |
| `callMiniPipActive` | `Boolean` | Call is backgrounded (WebView hidden behind NavHost) |
| `callActiveRoomId` | `String` | Room ID of the active call |
| `callPersistentWebView` | `WebView?` | The WebView kept alive across navigation |
| `incomingCallInfo` | `IncomingCallInfo?` | Non-null while an incoming call banner should be shown |
| `activeCallRooms` | `Set<String>` | Room IDs where a call is currently ongoing (from room state) |

---

## Call lifecycle

### Starting a call
`appViewModel.startCall(roomId)` sets `callActiveInternal = true`, clears mini-pip, and is called either by the user tapping the header button or by `IncomingCallBanner`'s "Join" button.

### Backgrounding (mini-pip)
Pressing **Back** while in a call sets `callMiniPipActive = true`. `CallOverlay` responds by switching its `zIndex` from `10f` to `-1f` — the WebView becomes invisible behind the NavHost but is never resized or reparented, preserving WebRTC's EGL surface.

### Returning to call
The call button in `RoomHeader` detects `callActiveInternal && callActiveRoomId == roomId` and calls `setCallMiniPip(false, "")` to bring the overlay back to the foreground.

### Ending a call
Element Call's JS bridge fires `onCallEnded` when the user hangs up inside the WebView. `CallsWidgetsCoordinator.endCall()` clears all call state including `callPersistentWebView`.

### Critical: WebRTC EGL surface
**Never resize or reparent the WebView container while WebRTC is active.** The EGL surface is bound to the View's exact size and position. Use `zIndex` toggling (`10f` ↔ `-1f`) to show/hide the call; do not change size, shape, or parent.

### BackHandler composition order
`BackHandler` in `CallOverlay` must be declared **before** any early return. If it sits after `if (!isActive) return`, the composition slot order changes on the `false→true` transition and the handler may not register reliably.

---

## Incoming call detection

### Real-time: org.matrix.msc4075.rtc.notification
When a sync event of type `org.matrix.msc4075.rtc.notification` (or its encrypted form with `decryptedType`) arrives for another user, `CallsWidgetsCoordinator.handleRtcNotification` checks:
- Not from ourselves
- Not already in a call
- `sender_ts + lifetime` is in the future (default lifetime 30 s)

If all pass, `incomingCallInfo` is set and `IncomingCallBanner` appears.

These events are **not shown in the timeline** (filtered out in `processTimelineEvents`) and are **not pushed via FCM** — they only work when the app is in the foreground and sync is live.

### Room-state: org.matrix.msc3401.call.member
`activeCallRooms` tracks which rooms have an ongoing call based on room state events. It is updated in two places:

1. **sync_complete** — `SyncRoomsCoordinator.processParsedSyncResult` scans each room's `state["org.matrix.msc3401.call.member"]` map, resolves the rowids to events via the room's `events` array, and adds/removes the room from `activeCallRooms` depending on whether any entry has non-empty content.

2. **get_room_state response** — `parseRoomStateFromEvents` scans the full state snapshot for call.member events with non-empty content and updates `activeCallRooms` accordingly.

`RoomHeader` uses `activeCallRooms.contains(roomId)` to show a pulsing primary-colored icon when a call is active in the room, even if we are not in it.

---

## Timeline narrator (call.member events)

`org.matrix.msc3401.call.member` events are shown in the timeline as narrator rows:
- Non-empty content → "**User** joined a video/voice call" (primary-colored icon)
- Empty content → "**User** left the video/voice call" (muted icon)

The type is present in the `allowedEventTypes` sets of both `RoomTimelineScreen` and `TimelineCacheCoordinator` so it passes all filters including pagination.

`timeline_rowid = 0` on the event object is resolved by `SyncIngestor` using the `timeline` array mapping (event_rowid → timeline_rowid) before the event is stored in cache.

---

## .well-known base URL resolution

On connect, `CallsWidgetsCoordinator.refreshElementCallBaseUrlFromWellKnown` fetches `<homeserver>/.well-known/matrix/client`, parses `org.matrix.msc4143.rtc_foci`, finds the first `livekit` entry, and derives the Element Call base URL as `<origin>/room`. This is stored in `wellKnownElementCallBaseUrl` and takes precedence over the manually configured URL.

---

## Widget protocol (JS bridge)

`ElementCallJsBridge` implements the Matrix Widget API between the WebView and the gomuks backend:

- **`update_delayed_event`** — responded to immediately (empty `{}`) to prevent Element Call's ~11 s timeout from killing the WebSocket. The actual update is forwarded to gomuks fire-and-forget.
- **`send_state` / `set_state`** for `call.member` — state key is auto-filled as `_<userId>_<deviceId>_m.call`; `membershipID` is injected; non-empty content triggers `setCallReadyForPip(true)`.
- **`get_room_timeline`** — filters call.member events; detects own disconnect (empty content + `origin_server_ts > screenOpenTimestamp`) and calls `onCallEnded`.
- Synthetic delay IDs (prefixed `andromuks-`) are used for delayed events that the backend creates on our behalf, so we don't forward update requests for them.
