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
| `CallForegroundService.kt` | Ongoing-call `CallStyle` notification + the microphone/camera foreground service |
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
| `callConnectedAtMs` | `Long` | Wall clock when media started flowing, or 0 while connecting; drives the notification chronometer |
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
`CallsWidgetsCoordinator.endCall()` clears all call state including `callPersistentWebView`, which
drops `CallOverlay` out of the composition; its `AndroidView.onRelease` then blanks and `destroy()`s
the WebView so WebRTC releases the mic/camera.

Getting `onCallEnded` to fire reliably needs **three** independent signals, because Element Call's
own leave path varies by version and URL params:

1. **`im.vector.hangup` / `io.element.close`** — the widget actions EC sends on leave. The host page
   forwards anything matching `hangup` / `leave` / `call_ended` / `close` to the bridge, synthesising
   a `requestId` when EC omits one (the bridge drops messages without one). The call URL sets
   `returnToLobby=false` so EC actually emits `io.element.close` instead of parking on its lobby
   screen.
2. **Own `call.member` cleared** — EC clears its own membership state exactly when it leaves, so a
   `send_state`/`set_state` for `org.matrix.msc3401.call.member` with **empty content**, our own
   state key, and **no** `delay_ms`/`delay` is treated as a hangup. The delay exclusion matters: the
   scheduled "leave" fallback registered at join time is the same event with a delay attached.
3. **Room-state poll** — the host polls `read_state` for call.member every 5 s;
   `normalizeWidgetResponse` spots our own cleared membership with `origin_server_ts >
   screenOpenTimestamp` and ends the call. This is server truth but only fires if the backend
   actually returns cleared state events.

Without (1) and (2) the WebView stayed parked on EC's "disconnected" screen with
`callActiveInternal` still true — back-press only backgrounded it, and the header button offered
"Return to call" rather than a fresh join, so the call could never be re-entered.

### Ongoing-call notification (`CallForegroundService`)

While a call is active, `CallsWidgetsCoordinator` runs `CallForegroundService`, a foreground service
whose notification is a `NotificationCompat.CallStyle.forOngoingCall`:

- **Tapping it** sends `ACTION_RETURN_TO_CALL` to `MainActivity`, which calls
  `setCallMiniPip(false, roomId)`. The WebView is persistent, so the call comes back instantly —
  nothing reloads and nothing re-joins. Before this, a backgrounded call could only be recovered by
  navigating to the room and pressing the header button, and could not be hung up at all.
- **"Hang up"** goes to the service, which invokes `CallForegroundService.hangupHandler` — set by
  `startCall`, cleared by `endCall` — on the main thread. Service and ViewModel are always in the
  same process while a call runs, so a plain callback is enough.
- **The chronometer** starts from `callConnectedAtMs`, set the first time `setCallReadyForPip(true)`
  fires, so it measures the call rather than time spent in Element Call's lobby. Until then the
  notification reads "Connecting…" with no counter.
- **The avatar** is a cache-only `IntelligentMediaCache` lookup off the main thread; the notification
  is posted immediately without one and refreshed if a cached file exists. A call notification never
  waits on the network.

The service declares `foregroundServiceType="microphone|camera"` — from Android 14 an app may only
keep capturing while it is not visible if such a service runs, so this is what makes a backgrounded
call survive leaving the app, and `CallStyle` only keeps its system treatment (the status-bar chip)
while it is a foreground service notification. At runtime the claimed types are narrowed to the
permissions actually granted (Element Call requests the camera only for video), because starting
with a type whose permission is missing throws; if `startForeground` fails anyway the service falls
back to a plain notification rather than taking the call down with it.

`onTaskRemoved` tears the notification down: the call lives in MainActivity's WebView, so a swiped
task means there is nothing left to return to.

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

## Which Element Call deployment we load

`CallOverlay` resolves the base URL in exactly two steps:

1. `elementCallBaseUrl` — the deployment configured in Settings, loaded as `<base>/room`.
2. Otherwise `<gomuks backend>/element-call-embedded/index.html` — the build the gomuks backend
   serves, which is the one gomuks web itself loads.

There is **no third-party fallback**. `call.element.io` is configured for someone else's homeserver,
and the old `.well-known` derivation (origin of `org.matrix.msc4143.rtc_foci[].livekit_service_url`
plus `/room`) assumed Element Call is hosted on the SFU's origin, which is usually a 404 — both are
gone. If neither URL is available the overlay shows "No Element Call deployment available" instead
of loading a stranger's deployment.

Both forms take the same parameters, in the URL hash, exactly as gomuks web passes them
(`buildElementCallUrl`); only `parentUrl` and `widgetId` sit in the query string.

## SFU discovery — MSC4515 `get_rtc_transports`

Current Element Call builds no longer read `org.matrix.msc4143.rtc_foci` from `.well-known`. They
discover the SFU by calling `_unstable_getRTCTransports()`, which **in widget mode is not an HTTP
request** — it is the fromWidget action `org.matrix.msc4515.get_rtc_transports`, and it is only
attempted when the host advertises `org.matrix.msc4515` in `supported_api_versions`. The only other
source is Element Call's own `config.json` (`livekit.livekit_service_url`), which the gomuks-served
build does not set.

So the host page must advertise `org.matrix.msc4515` and answer the action; `ElementCallJsBridge`
maps it to the gomuks `get_rtc_transports` command (gomuks proxies MSC4143
`/_matrix/client/unstable/org.matrix.msc4143/rtc/transports` with the real access token, which the
WebView does not have). Without this Element Call reports `MISSING_MATRIX_RTC_TRANSPORT` — "Call is
not supported. The server is not configured to work with Element Call" — on every join, while native
clients and gomuks web work fine.

## Room state push (`update_state`) must be retried

Element Call registers its `update_state` listener only once its own client has started, some time
after the capabilities handshake. The host's first room-state push races that, and a push that loses
is rejected with an "Unexpected action" error and silently dropped — leaving Element Call with **no
`m.room.member` events at all**, which shows up as the local participant rendering a letter avatar
instead of their profile picture.

`ensureRoomStatePushed` therefore re-pushes every 2 s (up to 8 attempts) until one `update_state` is
acked without an error, and pushes again on `io.element.join`. The host also logs the member count,
whether our own member event is present, and whether it carries `avatar_url`, so a debug-build
logcat (`CallOverlay: console …`) says which of those went wrong.

---

## Widget protocol (JS bridge)

`ElementCallJsBridge` implements the Matrix Widget API between the WebView and the gomuks backend:

- **`update_delayed_event`** — responded to immediately (empty `{}`) to prevent Element Call's ~11 s timeout from killing the WebSocket. The actual update is forwarded to gomuks fire-and-forget.
- **`send_state` / `set_state`** for `call.member` — state key is auto-filled as `_<userId>_<deviceId>_m.call`; `membershipID` is injected; non-empty content triggers `setCallReadyForPip(true)`.
- **`org.matrix.msc4515.get_rtc_transports`** — answered from the gomuks `get_rtc_transports` command; see the SFU discovery section above.
- **`get_room_timeline`** — filters call.member events; detects own disconnect (empty content + `origin_server_ts > screenOpenTimestamp`) and calls `onCallEnded`.
- Synthetic delay IDs (prefixed `andromuks-`) are used for delayed events that the backend creates on our behalf, so we don't forward update requests for them.
