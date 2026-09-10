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

### Voice vs video, and who rings

The room header's call button opens a two-item menu — **Voice call** / **Video call** (worded "Join
with …" when a call is already running) — and passes the choice to `startCall(roomId, intent)`, which
stores it as `AppViewModel.callIntent`. Returning to a call we are already in skips the menu.

That choice, plus whether the room is a DM, is handed to Element Call through **its own URL
parameters** — the names come from the bundle it serves, not from guesswork:

| Param | Effect inside Element Call |
|---|---|
| `callIntent` = `audio` \| `video` | `calculateInitialMuteState` → `videoEnabled: callIntent != "audio"`, so a voice call opens with the camera off; also switches Android audio routing to the earpiece instead of the speaker |
| `sendNotificationType` = `ring` \| `notification` | Passed to `joinRTCSession` as `notificationType`, i.e. what Element Call stamps on the `rtc.notification` it sends. `ring` additionally makes it wait for pickup |

Note `intent=join_existing`, which gomuks web passes, is **not a value this build recognises at
all** — the parameter it reads is `callIntent`, and the only value it compares against is `audio`.

**Why we decide `ring` rather than Element Call**: left alone it sends `notification_type:
"notification"` for every call including DMs, so a call to one person never rings anybody. It cannot
do better — in widget mode it has no way to know the room is a DM. `RoomItem.isDirectMessage` does,
so `CallOverlay` resolves it and passes the answer down.

`ElementCallJsBridge.stampCallNotification` then enforces the same decision on the outgoing event as
a belt-and-braces measure (the URL parameter is the mechanism; the stamp guarantees the outcome even
if a future build renames it) and sets the ring lifetime:

| Room | `notification_type` | `lifetime` |
|---|---|---|
| DM (any member count) | `ring` | 90 s, matching Element X — Element Call's 30 s is too short to answer |
| Group room | `notification` | left as Element Call sent it |

The receiving side is [Push: ringing while backgrounded](#push-ringing-while-backgrounded); a `ring`
is what makes it ring rather than post a quiet "started a call" notification.



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

**Hanging up from outside Element Call** (the notification's "Hang up") must not take the WebView
down directly. Clearing `org.matrix.msc3401.call.member` is EC's job, so killing the WebView ends the
call locally while our membership stands until the server applies the delayed leave event — nobody,
including our own timeline, sees us leave. `requestGracefulHangup` instead sends EC the
`im.vector.hangup` toWidget action, which is exactly what its own leave button does: EC clears its
membership, and that comes back through signals (1) and (2) above and ends the call for real. A 3 s
timeout ends the call locally anyway, so a wedged WebView can never trap the user in a call they
asked to leave.

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
  same process while a call runs, so a plain callback is enough. The handler routes through
  `requestGracefulHangup` rather than `endCall`, so Element Call clears its own membership and the
  room actually sees us leave (see [Ending a call](#ending-a-call)).
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

### Push: ringing while backgrounded

The sync path above only works while the app is alive and syncing. For a backgrounded or dead app
the same event has to arrive as a **high-priority FCM push**, which the backend must be built to
send — stock gomuks drops every event that is not `m.room.message`/`m.sticker` in
`formatPushNotificationMessage`.

Our backend carries these as ordinary entries in the push's `messages` array, with an extra `rtc`
object mirroring the event's `notification_type`:

```json
{ "room_id": "…", "room_name": "Alice", "sender": {…}, "self": {…}, "text": "Incoming call",
  "rtc": { "type": "ring", "expires_at": 1757505630000 } }
```

| `rtc.type` | Meaning | What we do |
|---|---|---|
| `ring` | A call asking to be answered now | `IncomingCallRinger.ring` — `CallStyle.forIncomingCall`, ringtone channel, full-screen intent |
| `notification` | Someone started a call | `IncomingCallRinger.notifyCallStarted` — ordinary notification, tap opens the room, "Join call" joins |
| `cancel` | Caller gave up / answered elsewhere | Cancels a showing ring |

`rtc.expires_at` is absolute ms (the event's `sender_ts + lifetime`), and falls back to
`timestamp + 30 s`. Everything else comes from the normal message fields.

**`countRtcMessages` splits the array**: entries with an `rtc` object are handled by
`handleCallNotification` and `handleMessageNotification` skips them, so a call never also posts as a
chat message or burns `/exec` enrichment on an event with no body to render.

Guards mirror `handleRtcNotification` — never for our own call, never while already in one
(`CallTracker.anyCallActive()`), never past `expires_at` — plus one it does not need: if the app is
in the foreground the WebSocket delivers the same event and shows the banner, so the push is skipped
to avoid doubling up.

**Ringing** needs three things beyond an ordinary notification: an `IMPORTANCE_HIGH` channel whose
sound is the device ringtone under `USAGE_NOTIFICATION_RINGTONE` (ring volume, follows ringer mode),
`CallStyle.forIncomingCall` for the Answer/Decline affordances, and a **full-screen intent** to take
over the lockscreen. The last is a privilege: from Android 14, apps that are not calling or alarm
apps have `USE_FULL_SCREEN_INTENT` denied by default. `IncomingCallRinger.canRing` reports it (and is
logged on every ring) and the ring degrades to a heads-up notification rather than failing.

`setTimeoutAfter(remaining)` retires either notification even if our process is gone, so a missed
call can never ring forever. **Declining is purely local** (`CallActionReceiver`): MatrixRTC has no
"rejected" event — `rtc.notification` is a hint, not an invite — so declining just stops the ring.

**Answering from a dead process**: a ring routinely starts the process, so Answer and the
full-screen intent arrive before there is an `AppViewModel`. `MainActivity.offerCallActionFromIntent`
parks them in `PendingCallAction` (Compose state) and a `LaunchedEffect` next to `CallOverlay` drains
it once the ViewModel exists — Answer calls `startCall`, the full-screen intent shows the existing
`IncomingCallBanner` via `showIncomingCall`. The full-screen intent also flips
`setShowWhenLocked`/`setTurnScreenOn` on, for that intent only.

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
- **`im.vector.hangup`** (toWidget, sent by us) — asks EC to leave the call; the host exposes it as
  `window.__andromuksWidgetHost.requestHangup()`.
- Synthetic delay IDs (prefixed `andromuks-`) are used for delayed events that the backend creates on our behalf, so we don't forward update requests for them.
