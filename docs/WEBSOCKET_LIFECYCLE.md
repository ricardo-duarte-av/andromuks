# WebSocket Lifecycle & Recovery

## Connection States (`ConnectionState.kt`)

| State | Meaning |
|---|---|
| `Disconnected` | No active socket, no reconnection pending |
| `Connecting(n)` | TCP/TLS/WebSocket dial in progress |
| `Initializing(runId, pending, received)` | run_id received; consuming initial sync_completes |
| `Ready` | Fully connected and synced |
| `QuickReconnecting(runId, lastEventId, n)` | Resume path (last_received_event in URL) |
| `FullReconnecting` | Cold resync path (clears resume state) |
| `WaitingForNetwork(lastEventId)` | No network; will resume or cold-connect when link returns |

Helper extensions live in `ConnectionState.kt`: `isReady()`, `isDisconnected()`, `isConnecting()`, `isReconnectingPhase()`, `isDialOrSyncing()`, `isActive()`.

## Reconnection Entry Points

| Trigger | Source |
|---|---|
| `onNetworkAvailable` | `NetworkMonitor` → `WebSocketService.startNetworkMonitoring()` |
| `onNetworkLost` | `NetworkMonitor` → enters `WaitingForNetwork` |
| `onNetworkTypeChanged` / `onNetworkIdentityChanged` | `NetworkMonitor` → `scheduleReconnection()` |
| Ping timeout / message timeout | `WebSocketService` ping/pong loop |
| `onFailure` / `onClosed` | OkHttp WebSocket callbacks |
| Unified health check (adaptive tick) | Detects stuck `Connecting`, `Reconnecting`, or `Disconnected` |
| `START_STICKY` restart | Triggers stuck-DISCONNECTED recovery after 5s grace period |

## `onFailure`: superseded-socket guard, and who owns scheduling

Two rules, both learned from "the socket dies on a weak network and never comes back":

**1. `onFailure` must ignore a superseded socket.** `WebSocketService.isSupersededWebSocket(ws)` is true
when the service holds a *different, live* socket. On a lossy link a slow dial gets superseded by a
reconnection, and the abandoned dial's `onFailure` arrives seconds after the replacement succeeded —
without the guard it called `clearWebSocket()` on the healthy connection, and the next dial raced the
same way. `onClosing` has had the equivalent check (via `isActiveWebSocket`) all along; `onFailure`
never did.

The predicate is deliberately **not** `!isActiveWebSocket(ws)`. The service only stores a socket in
`setWebSocket`, i.e. on `onOpen`, so a dial that fails *before* opening is never "active" — and its
failure genuinely does need teardown and a reconnect. Only "someone else is live and it isn't you" may
be ignored. The guard sits after the per-dial resource cleanup (decompressor + parse queues, which must
always run) and **before** the 401 and TLS branches: a 401 from an abandoned dial is not evidence the
live connection's token is bad, and `handleUnauthorizedError()` navigates to login.

**2. The service schedules the reconnection, not a ViewModel.** `NetworkUtils.onFailure` calls
`WebSocketService.scheduleReconnection(...)` directly. It used to delegate entirely to
`AppViewModel.handleConnectionFailure`, which meant: with **no** ViewModel attached (always-on
background) a failure cleared the socket and did nothing else; with **two** attached (main + bubble)
each scheduled a competing attempt; and the ViewModel's `networkType == NONE` early-return skipped the
service's correct `WaitingForNetwork` handling. `handleConnectionFailure` / `handleTlsError` now only
keep error counters and reporting. A `CERTIFICATE_ERROR` still never auto-reconnects.

## `scheduleReconnection()` Flow

1. If `currentNetworkType == NONE` → set `WaitingForNetwork`, add to `pendingReconnectionReasons`, return.
2. Atomic lock check: drop if already reconnecting within 10s; reset after 30s stall.
3. Launch `reconnectionJob`: wait for `NET_CAPABILITY_VALIDATED`, then call `invokeReconnectionCallback()`.

`invokeReconnectionCallback()` reads credentials from `SharedPreferences` (no AppViewModel required), picks the primary/attached ViewModel if available, and calls `connectWebSocket()`.

The backoff is `BASE_RECONNECTION_DELAY_MS shl (min(attempt, 7) + 1)`, capped at 120s. The exponent
**must** be clamped and the shift **must** be on a `Long`: this was `1000L * (1 shl attemptCount)`, an
`Int` shift, while `MAX_RECONNECTION_ATTEMPTS` is 99 — at attempt 31 it produced `Int.MIN_VALUE`,
`minOf` picked the negative, and `delay(negative)` returned immediately, so attempts 31+ hot-looped with
no backoff at all.

`pendingReconnectionReasons` is written and read under `pendingReconnectionLock` (it was previously
written under `reconnectionLock` — a different monitor). It is drained by `processPendingReconnections()`,
which snapshots-and-clears inside the lock and calls `scheduleReconnection` **outside** it, and which is
invoked both when a ViewModel becomes primary and from `onNetworkAvailable` — the moment those triggers
were queued for.

## Startup / `START_STICKY` Restart Recovery

**Known gap (fixed):** `NetworkMonitor.start()` calls `updateCurrentNetworkState()` before registering the Android callback. Android then delivers `onAvailable()` with `previousType = WIFI`, so `wasOffline = false` and `onNetworkAvailable` is never called. `WebSocketService.currentNetworkType` would stay `NONE` even on a device with an active network.

**Fix in `startNetworkMonitoring()`:** After `networkMonitor?.start()`, seed `currentNetworkType` / `lastNetworkType` from `networkMonitor.getCurrentNetworkType()` if it is non-NONE.

**Fix in stuck-DISCONNECTED health check:** The check now also fires when `connectionLostAt == 0` (service never had a connection this process lifetime) and `serviceStartTime` is >5s ago — covering cold `START_STICKY` restarts.

## Cold-start Dialer & AuthCheck-Bypass Watchdog

The **normal cold-start dialer** is `AuthCheckScreen` (`AuthCheck.kt`): its `LaunchedEffect(Unit)` calls `appViewModel.initializeWebSocketConnection()` for the primary instance once stored credentials are confirmed. `auth_check` is the nav graph's `startDestination`, so on a fresh launch it composes first and owns the dial.

**The gap:** Compose Navigation (`rememberNavController()`) persists and restores its back stack across process death. If the previous session left a non-`auth_check` destination on top (e.g. a `room_timeline` the user was reading), the restored `NavHost` mounts that destination directly — `auth_check` never composes, its connect `LaunchedEffect` never runs, and the socket is never dialed. `awaitRoomDataReadiness` then polls with `websocketReady=false`, times out after 15s, and the timeline is stuck on "Room loading…".

**Watchdog (`AppNavigation` in `MainActivity.kt`):** A lifetime-scoped `LaunchedEffect(Unit)`, placed at the top of `AppNavigation` *outside* the `BiometricLockGate` wrapper so it runs regardless of which destination is mounted or whether the app is still biometrically locked. It waits ~2.5s (to let AuthCheck dial on the normal path), then — if the socket is still down, this is the primary instance, and credentials exist — calls the same idempotent `initializeWebSocketConnection()`.

**Why it can't double-dial:** `initializeWebSocketConnection()` no-ops for non-primary instances and attaches instead of dialing when already connected. `connectWebSocket()` independently skips when already connected and when a dial is already `Connecting`. So even if the watchdog races AuthCheck, whichever reaches the service first wins and the other no-ops. It is one-shot: a failed dial hands off to the service's normal reconnection/backoff.

## No-VM Race: Service Starts Without AppViewModel

**Race:** The service can connect and receive the full initial `init_complete` + `sync_complete` batch before any `AppViewModel` is attached. This happens when the service is auto-started via `START_STICKY`, `BootStartReceiver`, `ServiceStartWorker`, or the stuck-DISCONNECTED health check recovery — all of which call `connectWebSocket(null)`. With no VM attached, `SyncRepository.processSyncCompletePipeline()` previously dropped every `sync_complete` (logged as `"no AppViewModel to process"`), leaving `RoomListCache` empty.

**Symptom:** User opens app after automatic startup → `isWebSocketConnected() = true` → `attachToExistingWebSocketIfAvailable()` → `populateRoomMapFromCache()` returns empty → room list is blank. Rooms then appear one-by-one only as subsequent live `sync_complete` cycles arrive from the backend.

**Fix — `SyncRepository` no-VM buffer:**
- `processSyncCompletePipeline()` now buffers up to 500 `sync_complete` messages in `noVmBuffer` instead of dropping them.
- The buffer is epoch-tracked (`noVmBufferEpoch`). `WebSocketService.updateConnectionState()` calls `SyncRepository.clearSyncBuffer()` on every `Disconnected` transition, advancing the epoch so messages from a stale connection are never replayed into a new session.
- When a VM attaches, `attachToExistingWebSocketIfAvailable()` calls `SyncRepository.takeBufferedMessages()` to retrieve the buffered messages and merges them with any messages in `initialSyncCompleteQueue`. All messages are then processed synchronously inside a `Dispatchers.Default` coroutine; navigation fires via `populateFromCacheAndNavigateAfterAttach()` only after processing completes (on the Main thread).

**Critical:** do **NOT** use `triggerBufferedSyncDrain()` here — it re-enqueues to the async channel, which means navigation fires before rooms are populated, causing them to pop in one-by-one.

## `initialSyncPhase` / Drain-Sentinel Attach-Gate

`initialSyncPhase` must stay `false` until navigation is about to fire. The SyncRepository pipeline (running concurrently on `Dispatchers.IO`) may still have messages in `syncCompleteChannel` that were not yet moved to `noVmBuffer` at the moment `takeBufferedMessages()` is called — so `noVmBuffer` can appear empty even though messages are in transit. With `initialSyncPhase = false`, any pipeline-dispatched messages land in `initialSyncCompleteQueue` instead of being applied to the UI directly.

To know exactly when the pipeline has finished dispatching all in-transit messages, `attachToExistingWebSocketIfAvailable` enqueues a `DRAIN_SENTINEL` into `syncCompleteChannel` immediately after registering the VM. Because the channel is FIFO and the pipeline is single-threaded, when the sentinel is processed all prior messages have been dispatched and are sitting in `initialSyncCompleteQueue`. The sentinel callback (on IO thread) dispatches to Main, takes a snapshot of the queue, batch-processes it on `Dispatchers.Default`, sets `initialSyncPhase = true`, and then calls `populateFromCacheAndNavigateAfterAttach()`. This guarantees the room list is fully populated before the user sees `RoomListScreen`.

## Cold-start `init_complete` Drain-Sentinel (cross-pipeline ordering)

`sync_complete` and `init_complete` travel **different pipelines with no ordering guarantee between them**: `sync_complete` goes through the single-threaded `syncCompleteChannel`, while `init_complete` arrives via the `_events` SharedFlow (`applyIncomingWebSocketMessageForViewModel`). On a cold start the server sends all initial `sync_complete` messages and *then* `init_complete`, but because `init_complete` takes the faster `_events` path it can overtake `sync_complete` messages still draining through the channel.

If `init_complete` called `AppViewModel.onInitComplete()` directly, `onInitComplete` would snapshot a **partial** `initialSyncCompleteQueue`: the late `sync_complete` messages would then be processed one-by-one as "real-time" updates after `initialSyncPhase = true`, and — critically — the `clear_state` diff-prune would harvest an incomplete `seenRoomIds` and wrongly prune rooms that were in the initial payload but still in flight (observed: 324 of 621 rooms falsely pruned).

Fix: on `init_complete`, `applyIncomingWebSocketMessageForViewModel` enqueues a `DRAIN_SENTINEL` instead of calling `onInitComplete()` directly. The ordered dispatcher `trySend`s every initial `sync_complete` into the channel before `init_complete` is emitted to `_events`, so the sentinel sits at the channel tail; its callback runs `onInitComplete()` only after every initial `sync_complete` has been dispatched into the queue. `onInitComplete` then sees the complete payload. (`WebSocketService.onInitCompleteReceived()` — connection health, ping start, timeout cancel — still fires immediately on the dispatcher; only the VM's queue-processing entry point is deferred.) See [AUTHCHECK.md](AUTHCHECK.md#clear_state-staleness-handled-by-diff-prune-not-purge).

## Global `request_id` Allocator

The Matrix backend tracks `request_id` per WebSocket connection, not per client process. When multiple `AppViewModel` instances (one per `ComponentActivity`: `MainActivity`, `ShortcutActivity`, `ChatBubbleActivity`) all attach to the same socket, they must share a single ID space. Otherwise a freshly-created secondary VM allocates `request_id=1, 2, 3, …` while the long-running primary VM is already deep into the hundreds, the server sees colliding IDs, and responses route to the wrong VM — manifesting as shortcut/widget opens stuck on "Room loading…" or rendering an empty timeline.

**Implementation:**

- A single `AtomicInteger globalRequestIdCounter` lives in `WebSocketService`'s companion object.
- `WebSocketService.allocateRequestId()` is the only writer; `peekNextRequestId()` reads without incrementing (used by the Settings diagnostics screen).
- `WebSocketService.setWebSocket` calls `resetRequestIdCounter()` on every new connection so IDs restart at 1 per socket — that's the only reset site. Per-VM `setWebSocket` callbacks must **not** reset (each attached VM would clobber IDs the others have already issued on the same socket).
- All ~75 call sites across `AppViewModel`, `NavigationCoordinator`, `TimelineCacheCoordinator`, `FcmPushCoordinator`, etc. call `WebSocketService.allocateRequestId()`. `AppViewModel.getAndIncrementRequestId()` / `getNextRequestId()` / `getCurrentRequestId()` are thin pass-throughs.

`utils/RoomJoiner.kt`'s `RoomJoinerWebSocket` class keeps a separate `AtomicInteger` parameter, but that class is currently dead code (never constructed) — the actual join-room flow routes through `AppViewModel` and uses the shared allocator.

## Secondary VM timeline refresh

When `SyncEvent.RoomListSingletonReplicated` fires on a non-primary `AppViewModel` (e.g., a `ShortcutActivity` / `ChatBubbleActivity` VM attached as `SECONDARY`/`BUBBLE`), the handler refreshes `timelineEvents` / `eventChainMap` for `currentRoomId` **only**, via `restoreFromLruCache(currentRoomId)`.

**Do not** iterate any larger "rooms ever opened in this VM" set: only one room's data is bound to `timelineEvents` at a time, so multiple `restoreFromLruCache` calls would clobber each other (last write wins) and could leave the screen rendering a non-current room's timeline.

Other rooms' singleton caches stay fresh on their own via `appendEventsToCachedRoom` in the sync ingestor — they just don't need to touch this VM's `timelineEvents` until the user navigates to them. Bubble VMs only ever host a single room, so the same single-room refresh is correct for them too without needing a role-specific guard.

### State that lives outside the timeline cache needs its own ingest

Refreshing `RoomTimelineCache` is not enough for anything held in a *separate* singleton. `processSyncEventsArray` — which is where the sync dispatch handles reactions, polls, redactions and the rest — runs for `currentRoomId` **only**, so a room open in a bubble was getting cache updates and nothing else.

Reactions were the concrete casualty: badges in a bubble stayed frozen at whatever the last full room open produced. `checkAndUpdateCurrentRoomTimelineOptimized` now also calls `ReactionCoordinator.ingestReactionsFromSync(bubbleRoomId, events)` for each non-current open room. That is safe because reaction render state is keyed by *target event ID*, not by room, and `MessageReactionsCache`'s change listener repaints every registered VM. See [docs/REACTIONS.md](REACTIONS.md).

**Polls need the mirror-image fix, and the difference matters.** Reaction state is a singleton keyed by target event ID, so the primary VM can ingest on a secondary VM's behalf. Poll state is not: `pollStartInfos` / `pollVoteEvents` / `pollEndEvents` are per-VM `AppViewModel` fields, and `computePollResults` validates `m.poll.end` against `currentRoomState.powerLevels` — which in the primary VM belongs to a *different* room. So the primary VM must **not** recompute polls for anyone else. Instead the secondary VM rebuilds its own, in the `RoomListSingletonReplicated` handler right after `restoreFromLruCache`, via `pollCoordinator.loadPollsForRoom(currentRoomId, timelineEvents, forceReload = true)`. The raw response/end events it reads are already replicated: `addEventsToCache` routes poll satellites into `RoomTimelineCache.pollEvents` on the same sync path the bubble loop uses.

The rule for anything added to that dispatch with state of its own: if the store is a **singleton**, ingest for other open rooms from the primary VM and repaint via a cache listener; if the store is **per-VM**, or deriving it needs per-room state the primary VM does not hold, rebuild it in the secondary VM after its cache restore. Getting neither is the silent failure mode — the room's cache stays fresh and the feature quietly freezes.

## `setWebSocket` Ordering Invariant

In `setWebSocket` (the OkHttp `onOpen` handler), `serviceInstance.webSocket` must be assigned **before** calling `updateConnectionState(ConnectionState.Ready)`.

The monitoring coroutine periodically calls `detectAndRecoverStateCorruption()` on a different thread. If it runs between the state update and the webSocket assignment, it sees `Ready + webSocket==null`, wrongly resets state to `Disconnected`, and the notification gets permanently stuck at "Connecting..." even though the socket is alive and delivering messages.

**Safe order:** set `webSocket`, `connectionStartTime`, `lastMessageReceivedTimestamp`, `lastPongTimestamp` first — then call `updateConnectionState(Ready)`.

## Persistent Notification Desync

The notification text in release builds is driven purely by `connectionState` (not by the `isConnected` parameter passed to `updateConnectionStatus`). It uses a dedup key `"$currentState-$callbackMissing"` and skips updates when the key hasn't changed.

If state gets stuck at `Disconnected` (e.g., via the corruption detector race above), every subsequent `updateConnectionStatus` call returns early and the notification never recovers until the next actual state transition.

## Unified Monitoring (adaptive tick, `startUnifiedMonitoring()`)

Runs on `serviceScope` inside the service instance.

**Tick rate is adaptive**: `MONITOR_INTERVAL_TRANSIENT_MS` (1 s) while `connectionState` is *not* `Ready`, `MONITOR_INTERVAL_READY_MS` (15 s) once it is. Every sub-second bound this loop enforces — stuck `Connecting` (>3 s), stuck `Reconnecting`, stuck `Disconnected` — can only fire while the connection is unhealthy, so the fast tick is only needed then. `Ready` is the steady state and lasts the whole life of the foreground service; a flat 1 s tick there was ~86,400 no-op wake-ups a day.

The expensive checks (state corruption, primary-ViewModel health, `validateCallbacks`) run on their own `DEEP_CHECK_INTERVAL_MS` (30 s) cadence, measured against `SystemClock.elapsedRealtime()` rather than a tick counter — with a variable tick, a counter would silently change that cadence.

Checks performed:

1. **Callback validation** (every tick) — warns if credentials missing and not `Ready`.
2. **State corruption + primary ViewModel health** (every 30 ticks) — promotes stale primaries.
3. **Stuck Connecting** — `>3s` in `Connecting` with no active timeout jobs → `clearWebSocket` + `scheduleReconnection`.
4. **Stuck Reconnecting** — `>60s` in a `isReconnectingPhase()` state → same.
5. **Stuck Disconnected** — `Disconnected`, no reconnect job, network available, credentials present, and (`connectionLostAt > 5s ago` OR `serviceStartTime > 5s ago`) → `scheduleReconnection`.
6. **Notification staleness** (every 60s) — forces notification update if not `Ready`.

## Network Monitor (`utils/NetworkMonitor.kt`)

Reports only meaningful changes:
- **Offline → Online** (`onNetworkAvailable`)
- **Online → Offline** (`onNetworkLost`)
- **Type change** WiFi ↔ Mobile (`onNetworkTypeChanged`)
- **Identity change** WiFi AP α → WiFi AP β (`onNetworkIdentityChanged`)

Ignores transient validation blips on the same network. Uses `NET_CAPABILITY_VALIDATED` + `NET_CAPABILITY_INTERNET` to confirm real connectivity before reporting "available".

The `onNetworkAvailable` callback is debounced by `NETWORK_CHANGE_DEBOUNCE_MS` and waits for `NET_CAPABILITY_VALIDATED` before scheduling reconnection.

## Work Modes: Always-On vs BatterySaver (Battery Saver)

The app supports two connection modes, controlled by the `useBatterySaverMode` setting (Settings → "Battery saver mode"). They differ in how the WebSocket is maintained when the app is backgrounded and how incoming `sync_complete` traffic is processed.

### Always-On (persistent foreground service)

- WebSocket stays connected at all times once the service is up; `WebSocketService` runs as a foreground service with a persistent notification.
- While backgrounded, incoming `sync_complete` messages are **batched** by `SyncBatchProcessor`: queued in `batchQueue` and flushed on either (a) the configurable interval (default 5 min, `backgroundPurgeIntervalMinutes`), (b) the configurable size threshold (default 500, `backgroundPurgeMessageThreshold`), or (c) immediately when the app returns to the foreground or an FCM tap arrives.
- During a flush, `_shouldSkipTimelineRebuild = true` defers per-event timeline rebuilds; `triggerDeferredRebuild()` issues a single rebuild for `currentRoomId` once the batch completes.
- On foreground resume, `ViewModelLifecycleCoordinator.onAppBecameVisible` runs the universal health check (`pingNowWithWatchdog`) and re-dials if the socket is unhealthy.

### BatterySaver (battery saver)

- WebSocket is closed ~15 s after the last UI surface backgrounds (`scheduleBatterySaverLinger(BATTERY_SAVER_LINGER_MS_DEFAULT = 15_000L)`). The 15 s linger gives the user a grace window to switch back without paying for a fresh handshake.
- **Screen-off is a separate, shorter trigger.** `ACTION_SCREEN_OFF` (the service's own `screenStateReceiver`) arms the same linger with `BATTERY_SAVER_SCREEN_OFF_LINGER_MS = 3_000L` and `screenOff = true`. The 15 s app-switch grace has no meaning once the screen is off — the user cannot come back without turning it on, which produces `ACTION_SCREEN_ON` → unlock → `onResume` → re-dial. All the wait buys there is the accidental lock (pocket, mis-hit power button), which 3 s covers. See "The linger must count wall-clock time" below for why leaving this to the 15 s timer was actively harmful.
- A chat bubble being open extends the lifetime: `scheduleBatterySaverLinger` re-checks `BubbleTracker.anyBubbleOpen()` at expiry and skips teardown if any bubble is alive. `cancelBatterySaverLinger` is called whenever a surface (main activity or bubble) becomes visible.
- Notification reply and mark-as-read while disconnected are routed through the gomuks backend's official one-off command endpoint `POST <homeserver>/_gomuks/exec/{command}` (`ExecApi.sendMessage` → `send_message`, `ExecApi.markRead` → `mark_read`); the raw JSON body is the command's `data`, identical to the WebSocket frame's `data`, authed with the same `gomuks_auth` cookie. FCM provides push delivery, so no socket is needed in steady-state.
- Other state-updating commands take the same `/exec` route while disconnected via `ExecCommandCoordinator`, which allocates a synthetic `request_id`, registers it in the same request-tracking map(s) the WS path uses, and feeds the parsed body back through `handleResponse`/`handleError` (the `/exec` body is byte-identical to a WS `response` frame's `data`, so no handler changes are needed): `paginate` (timeline-cache hydration on FCM wake-up, `paginateViaExec`) and `get_specific_room_state` (on-demand user-profile fetch — `flushProfileBatch` falls back to `/exec` when the socket is down; see [USER_PROFILES.md](USER_PROFILES.md#when-get_specific_room_state-is-requested)).
- **No batching:** `SyncBatchProcessor.batterySaverModeEnabled = true` makes `processSyncComplete` always take the immediate path, even while backgrounded. Within the 15 s linger window every arriving `sync_complete` is applied straight to the caches; no `batchJob` is ever scheduled, so there is no future wakeup queued against a socket that is about to die. Any sync the user missed while disconnected is re-delivered on the next connect — a **catchup sync** (compact delta via `last_server_ts`; see [Catchup Sync](#catchup-sync-fast-reconnect-whenever-the-process-survived) below), falling back to a full `clear_state=true` sync when no `last_server_ts` has been recorded (a killed process) — not a replay of the missed stream.
- At linger expiry the service flips `PREF_BATTERY_SAVER_USER_DISCONNECTED` and either calls `primaryVm.markForceFreshPaginateAfterWsDown()` (if a VM is attached) or sets `PREF_FORCE_FRESH_TIMELINE_PAGINATE` (consumed by the next VM open via `consumeForceFreshTimelinePaginatePending`). The next room open then bypasses the timeline cache fast path and paginates fresh, so a stale snapshot from before the disconnect cannot leak into the UI.
- On foreground resume, `onAppBecameVisible` clears `PREF_BATTERY_SAVER_USER_DISCONNECTED`, reschedules `WebSocketHealthCheckWorker`, and runs the same `pingNowWithWatchdog` re-dial that the always-on path uses.

### The linger must count wall-clock time (and re-check its guards)

`delay()` schedules against `System.nanoTime()` (CLOCK_MONOTONIC), which **does not advance while the
device is suspended**. A bare `delay(15_000)` armed at screen-off therefore only counted the crumbs of
CPU time our own heartbeat alarm bought us — and that alarm is `setExactAndAllowWhileIdle`, which Doze
throttles to roughly one firing per 9–15 minutes. The countdown could take hours of wall time, or
effectively complete only when the user picked the phone up. Two failures, both reported as one bug
("sleep with a room open, unlock, permanent offline indicator"):

1. **The mode stopped saving battery.** The socket, the 45 s ping loop and the wake alarms that
   battery-saver exists to stop all stayed alive overnight, burning the Doze exact-alarm quota to keep
   a connection the user had explicitly asked us to drop.
2. **The teardown landed at unlock.** "Linger expires" and "user unlocks" became the *same event*,
   which is why it reproduced every time rather than occasionally. `cancelBatterySaverLinger()` on
   resume cannot help: the body past the `delay` has no suspension point, so a teardown that has
   already started runs to completion — after `onAppBecameVisible`'s `pingNowWithWatchdog()` had
   already seen a healthy socket and skipped its re-dial branch. Nothing re-dialled afterwards (the
   `AppNavigation` watchdog only covers the first 2.5 s after RESUMED), so the socket stayed down.

The fix is three-part and each part is load-bearing:

- **Deadline in `SystemClock.elapsedRealtime()`**, re-arming `delay` until it passes. The wait can no
  longer complete early in wall-clock terms.
- **A partial wake lock for the screen-off window** (`acquireLingerWakeLock`, released in a `finally`
  so cancellation cannot leak it). Three seconds of CPU is nothing next to holding the socket open all
  night, and it makes the teardown happen *while the user is away* rather than at the next unlock.
- **`lingerSkipReason()` re-checked at expiry**, not just the old bubble check: a bubble open, a call
  active (`CallTracker` — the service has no ViewModel, so per-VM call state is invisible to it), the
  screen back on (screen-off trigger only), or any surface visible again. This is the last line of
  defence against a stale timer tearing down a live session, and it must stay exhaustive.

Bubbles and active calls remain exempt from teardown entirely, at arm time and at expiry.

The setting can be toggled at runtime. The lifecycle change takes effect on the next background/foreground transition; no service restart is forced. No connectivity probe is needed when enabling battery-saver mode — `/_gomuks/exec` is served by the main gomuks backend, which is reachable whenever the homeserver is.

## Catchup Sync (fast reconnect whenever the process survived)

A **cold connect** is the expensive reconnect: the backend sends `clear_state=true` followed by the entire room list (observed as 7–8 × ~500 KB `sync_complete` messages). We only pay it when we have to — a fresh launch, a killed process, or a forced fresh. Whenever the socket drops but the **app process is still alive** (so the RAM `last_server_ts` is set), the reconnect is instead a **catchup connect**: a single compact delta, `catchup:true`, and **no `clear_state`**.

### The three connect modes

| Mode | URL params | Backend response |
|---|---|---|
| Cold (fresh launch / killed process / forced fresh) | none | `clear_state=true` full initial sync |
| Resume (network blip, socket was up) | `run_id` + `last_received_event` | replay of buffered events since the cursor; **no** `init_complete` (first `sync_complete` acts as it) |
| **Catchup (process survived, socket down)** | `last_server_ts` | one `sync_complete` with `catchup:true` (no `clear_state`), then a normal `init_complete` |

Catchup keys on `last_server_ts` **only** — `run_id` is irrelevant to the backend's catchup decision. We deliberately do **not** send `last_received_event`, so the backend skips stream-resume event replay (which can be arbitrarily large after a long background) and instead diffs its DB by modification timestamp.

### The catchup-vs-cold decision: RAM-only `last_server_ts` (Option B)

The connect mode is chosen **solely** by whether a `last_server_ts` is present — and that value is deliberately **RAM-only, never persisted to disk**. This is the load-bearing invariant:

- `WebSocketService.lastServerTimestamp` is a plain field, zeroed on every fresh process. `getLastServerTimestamp()` returns it verbatim with **no** disk fallback.
- **Non-zero ⇒ this exact process ran a prior sync ⇒ every RAM cache it was tracking survived** (global `account_data`, recent emojis, `m.direct`, the room map, …) ⇒ a catchup delta is safe to apply on top.
- **Zero ⇒ fresh/killed process ⇒ those caches are gone** ⇒ a cold connect must re-send the full authoritative state.

Persisting the timestamp to disk (as an earlier design did) breaks this: the timestamp would resurrect after a kill **without** the data it stands for — a *false sense of hydration*. A catchup would then land on empty caches and silently drop any `account_data` the delta didn't re-send (unchanged keys are omitted — e.g. recent emojis vanished). RAM-only ties the signal's lifetime to the data's lifetime, so the two can never disagree.

Because the decision reads only this signal — **not how the app was launched** — an FCM notification open, a launcher tap, and a shortcut behave identically: catchup iff the process survived.

### High-water `server_timestamp`

Every `sync_complete` — initial batch and live — carries a top-level `data.server_timestamp` (epoch millis). Example live payload:

```json
{"command":"sync_complete","request_id":-1908,"data":{
  "since":"s12974186_…","server_timestamp":1784494882970,
  "rooms":{ "!SAwLbTTygRriVkdOVq:matrix.org":{ "meta":{ … }, "receipts":{ … } } }
}}
```

`SyncRoomsCoordinator.processSyncCompleteAtomic` records the max into `WebSocketService.lastServerTimestamp` (RAM only; helpers `updateLastServerTimestamp` / `getLastServerTimestamp` / `clearLastServerTimestamp`). `clearCredentialsAndNavigateToLogin` zeroes it via `clearLastServerTimestamp()` (and drops the legacy `last_server_ts` pref that pre-Option-B builds wrote) so a different account logging in gets a full sync, never a catchup diffed against the previous account's data.

### Account data: user vs room, and how each is delivered

Two distinct kinds of "account data" are easy to conflate — they live in different stores, ride different fields of `sync_complete`, and are handled by different client code:

| Kind | Examples | Wire location | Client path |
|---|---|---|---|
| **User (global) account data** | `m.direct`, `m.push_rules`, `io.element.recent_emoji`, ignore list, emote/image packs | top-level `account_data` in `sync_complete` (`SyncComplete.AccountData`) | `SyncRoomsCoordinator.processAccountData` → `AccountDataCache.setAllAccountData` |
| **Room account data** | `m.tag`, `m.fully_read` | inside a room object (`SyncRoom.AccountData`) — **never** a standalone top-level field | `SpaceRoomParser.applyRoomAccountData` (reads each room's `account_data` sub-object) |

Both kinds are **push-delivered by the backend via `sync_complete`** — you never poll for them. Room account data specifically arrives bundled in a room object on all three occasions:

1. **Live** — a favourite/read-marker change on another client flows through the homeserver `/sync` into hicli, which emits a `sync_complete` whose `SyncRoom.AccountData` carries the new `m.tag`. That room object is often **meta-less** (only account data changed).
2. **Catchup connect** — `GetAllRoomSince` → changed rooms as meta-less `account_data`-only objects.
3. **`clear_state=true`** — `getInitialSyncRoom` → `GetAllRoom` bundles each room's account data with its full object.

All three funnel through the same `applyRoomAccountData` → `authoritativeTagRoomIds` → authoritative-merge path (see below), so tag adds/removals behave identically regardless of which occasion delivered them.

> **Not delivered this way:** `get_room_state` returns **only room *state* events** (`[]*database.Event` — members, power levels, encryption, **`m.bridge`**, name/topic/avatar). It carries **no** account data, so opening a room does **not** refresh `m.tag`/`m.fully_read`. (`m.bridge` is a *state* event, which is why bridge info *does* refresh on room open while tags do not.) Tag/read-marker state for a room is only ever as fresh as the last `sync_complete` that included that room's object.

### What a catchup payload contains

`GetCatchupSync` (gomuks `pkg/hicli/init.go`) returns, since the timestamp:
- **rooms** whose metadata changed — each a full room object (meta present); **plus** rooms where only account data changed, sent as a room object with **only `account_data`, `meta` absent**
- **account_data** — changed global account data
- **top_level_spaces** + **space_edges** — full replace (all of them)
- **invited_rooms** — invites received/updated since (delta)
- **left_rooms** — rooms left / invites declined since (from the backend's `left_room` table)
- **server_timestamp** — the new high-water mark

Because `account_data` and per-room `account_data` are **delta with full-replace semantics** (the whole content of a changed event is re-sent), a catchup *does* carry tag/DM **removals** made on another client while disconnected: an un-favourite arrives as the room's new `m.tag` (now without `m.favourite`); an un-DM arrives as the full new global `m.direct`.

### Sticky flag merge (favourite / low-priority / DM) must honour removals

The room-list merge historically OR-ed these flags (`room.isFavourite || existing.isFavourite`) to stop a metadata-only sync — which parses tags to `false` when it omits `m.tag` — from wrongly clearing a favourite. But OR can't represent a *removal*: a catchup-delivered un-favourite would be re-stuck. The fix threads `SyncUpdateResult.authoritativeTagRoomIds` — the set of rooms whose `m.tag` was actually present in the delta — from `SpaceRoomParser.parseSyncUpdate` through every accumulator (`SyncBatchProcessor`, `onInitComplete`, attach-pending) to the final apply in `SyncRoomsCoordinator.processParsedSyncResult`. For a room **in** the set the incoming favourite/low-priority value wins (honouring removals); otherwise the OR-preserve is kept. DM stays OR-preserve everywhere because `m.direct` reconciles it authoritatively (both directions) via `updateRoomsDirectMessageStatus`.

Complementarily, the flags are persisted in `RoomMetadataStore` v4 (see [AUTHCHECK.md](AUTHCHECK.md)), so a room **absent** from the catchup delta (unchanged, so never re-sent) keeps its correct section membership across process death instead of hydrating flag-less.

### Wiring (client)

- `initializeWebSocketConnection` resolves `catchupSince` directly from `WebSocketService.getLastServerTimestamp()` (RAM-only): non-zero and not-resuming ⇒ catchup. There is **no** one-shot request flag — the decision is a pure function of the RAM signal, so every caller (`AuthCheck`, `MainActivity`, the foreground re-dial) gets the same behaviour without opting in. `catchupSince` threads through `WebSocketService.connectWebSocket` → `NetworkUtils.connectToWebsocket`, which emits `last_server_ts` and marks `setReconnectingWithLastReceivedEvent(false)` (catchup ends with a real `init_complete`).
- `ViewModelLifecycleCoordinator.onAppBecameVisible`'s re-dial branch (socket down on foreground) just calls `initializeWebSocketConnection`. Because that path only runs while the process is alive, the RAM `last_server_ts` is set and it naturally gets a catchup — but the decision lives in one place, keeping FCM / launcher / shortcut / re-dial uniform.

### `clear_state=true` wipes global account data (but not the room list)

`clear_state=true` means "your state is rotten, here is the authoritative set from scratch". Under Option B it only ever arrives on a genuine **cold connect** (RAM `last_server_ts` was 0). On its arrival, `SyncRoomsCoordinator.clearGlobalAccountStateOnClearState()` drops the RAM caches that have **no other reconciliation** and would otherwise stale-merge with the incoming payload: global `account_data` (`AccountDataCache`), recent emojis, `m.direct`, ignored users, emoji/sticker packs. The authoritative values are re-sent within the same init batch.

The **room / space list is deliberately not wiped** here — it keeps the cache-first no-flash design: painted from the disk `RoomMetadataStore` and reconciled non-destructively by `pruneStaleRoomsAfterClearState` (add/remove from the authoritative batch). `RoomMetadataStore`'s disk rows are a startup-paint optimization brought up to date by that same batch, so they stay safe across a `clear_state`. (`handleClearStateReset`, the old wholesale purge, stays unwired.) The same reasoning covers the `room_state` table — it is refreshed by the `get_room_state` requests the batch triggers, so it survives too; `pruneStaleRoomsAfterClearState` drops the rows for rooms it prunes, via `RoomListCache.removeRoom`. See [ROOM_STATE.md](ROOM_STATE.md).

### Why a catchup applies safely as a delta

A catchup batch has **no `clear_state`**, so `isClearState = false` throughout ingest and it flows through the normal incremental path on top of the kept caches (`roomMap` is not cleared on re-dial). The critical safety property: the **stale-room diff-prune** in `onInitComplete` runs only when `isClearStateBatch` is true (msg 1 had `clear_state`) — a `catchup` batch never sets it, so the prune (which would wrongly delete every room absent from the delta) is structurally skipped. Deletions instead come from the explicit `left_rooms` list (→ `removedRoomIds`, which also removes declined invites from `PendingInvitesCache`).

Meta-less account-data-only room objects are applied by `SpaceRoomParser.parseSyncUpdate` via `applyRoomAccountData` (tags / `m.fully_read` / `fi.mau.gomuks.preferences` → caches) instead of being dropped. See [SETTINGS_PREFS.md](SETTINGS_PREFS.md) for the account-data caches.

A catchup connect is visible in a release logcat dump via `Log.i` (`"processSyncCompleteAtomic: catchup=true batch RECEIVED …"`).

## Service Lifetime & Auto-Restart

- `START_STICKY` — Android re-creates the service after process kill with a null intent.
- `AutoRestartReceiver` — fires on `onDestroy`, schedules `ServiceStartWorker`.
- `BootStartReceiver` — fires on `BOOT_COMPLETED`, schedules `ServiceStartWorker`.
- `AutoRestartWorker` — periodic (30 min) WorkManager job; restarts if service not running.
- `WebSocketHealthCheckWorker` — additional periodic health check.
- `ServiceStartWorker` — one-off WorkManager task that starts the service at elevated priority.

Battery optimization exemption is recommended for reliable background operation. The service checks and logs its optimization status on start (`checkBatteryOptimizationStatus()`).

## Key Constants

| Constant | Value | Purpose |
|---|---|---|
| `PING_INTERVAL_FOREGROUND_MS` | 15s | App-level ping cadence while the app is visible |
| `PING_INTERVAL_BACKGROUND_MS` | 45s | Ping cadence while backgrounded — see the constraint below |
| `MESSAGE_TIMEOUT_FOREGROUND_MS` | 60s | No message at all for this long ⇒ stale, re-dial |
| `MESSAGE_TIMEOUT_BACKGROUND_MS` | 135s | Same, scaled to the background ping interval |
| `PONG_CLEAR_INFLIGHT_MS` | 5s | Clears `pingInFlight` so the next ping may be sent (cadence only) |
| `PONG_DEADLINE_MS` | 10s | No inbound traffic within this long of a ping ⇒ one missed pong |
| `MAX_CONSECUTIVE_PING_TIMEOUTS` | 3 | Missed pongs in a row before tearing down and re-dialling |
| `BASE_RECONNECTION_DELAY_MS` | 500ms | Backoff base; delay is `BASE << (min(attempt,7) + 1)`, capped at 120s |
| `MONITOR_INTERVAL_TRANSIENT_MS` | 1s | Unified-monitoring tick while not `Ready` |
| `MONITOR_INTERVAL_READY_MS` | 15s | Unified-monitoring tick once `Ready` |
| `DEEP_CHECK_INTERVAL_MS` | 30s | Cadence of the expensive monitoring checks |
| `NETWORK_CHANGE_DEBOUNCE_MS` | — | Debounce for rapid network events |
| `NETWORK_VALIDATION_TIMEOUT_MS` | — | Max wait for `NET_CAPABILITY_VALIDATED` |
| `INIT_COMPLETE_TIMEOUT_MS_BASE` | — | Max wait for `init_complete` after run_id |
| Reconnect stuck guard | 30s | Reset stuck reconnection lock |
| Stuck-Disconnected delay | 5s | Grace period before health-check recovery |

## The backend ignores RFC 6455 ping frames — never set OkHttp's `pingInterval`

The WebSocket `OkHttpClient` is built bare (`OkHttpClient.Builder().build()`), so `pingInterval` is `0`
and OkHttp sends no protocol-level ping frames. **This is deliberate and must stay that way.** The
gomuks backend does not answer WebSocket control-frame pings. OkHttp fails a connection with
`SocketTimeoutException` when a pong does not arrive within one interval, so enabling `pingInterval`
against a backend that never pongs would tear down every *healthy* connection on a fixed timer.

It looks like an oversight — especially when hunting half-open-TCP detection on weak networks — and it
is not. The app-level JSON `{"command":"ping"}` loop is the only liveness channel. `connectTimeout` and
`readTimeout(0)` on that builder remain safe to set.

## Missed-pong detection (`consecutivePingTimeouts`)

`startPongTimeout` is a two-stage watchdog armed by each `sendPing()`:

- **Stage 1**, at `PONG_CLEAR_INFLIGHT_MS`, clears `pingInFlight` so the next ping may be sent. This is
  cadence regulation only and is **not** gated on connection state — it used to be, and whenever the
  state left `Ready` at exactly that moment the flag latched `true`. The ping loop's only send gate is
  `if (!pingInFlight)`, so pings then stopped for the entire life of the connection.
- **Stage 2**, at `PONG_DEADLINE_MS`, is the liveness check. If `lastMessageReceivedTimestamp` has not
  advanced past the moment the ping was sent, `consecutivePingTimeouts` is incremented; at
  `MAX_CONSECUTIVE_PING_TIMEOUTS` the socket is cleared and `ReconnectTrigger.PingTimeout` scheduled.
  Any inbound traffic counts as liveness, not just the matching pong.

Before this existed, `consecutivePingTimeouts` was reset in five places and incremented in none, so a
**half-open TCP connection** — no FIN, the normal failure on a lossy link — was only ever caught by
`MESSAGE_TIMEOUT_*`, i.e. 60s foreground / 135s background. `MESSAGE_TIMEOUT_*` remains the backstop.

`clearWebSocket` resets `consecutivePingTimeouts` along with the other per-connection health fields;
without that, a teardown caused by hitting the limit would leave the counter at its limit and the first
missed pong on the *new* connection would immediately tear that one down too.

## Ping cadence — the 60s backend constraint

`WebSocketService.pingIntervalMs()` / `messageTimeoutMs()` scale on app visibility via `anySurfaceVisible()`, which **ORs two sources** because neither alone is complete:

- `WebSocketService.isAppVisible` (set by `setAppVisibility`) — the only source that knows about **chat bubbles**, since `ChatBubbleActivity` calls it but never touches `NotificationSuppressionState`.
- `NotificationSuppressionState.isAppVisible(context)` — process-wide and `AtomicBoolean` + prefs backed, so it survives the No-VM race and a service instance recreated without anyone re-asserting visibility.

Erring toward "visible" is the safe direction: the cost is a more frequent ping, not a dropped connection. The interval is re-read every loop iteration, so a foreground/background transition takes effect on the next tick without restarting the loop.

**The gomuks backend has a hardcoded 60 s ping timeout.** If we do not ping within 60 s the server drops the connection. The background interval is therefore **45 s**, not 60 s — 15 s of margin absorbs clock skew, radio latency and a doze-deferred coroutine. Do not raise it to 60 s "to save one more ping": that is exactly at the cutoff and will cause disconnect/reconnect churn that costs far more battery than it saves.

`MESSAGE_TIMEOUT_*` **must** scale with the ping interval. It is the "no message of any kind arrived" staleness bound, kept at 3× the interval. If the background ping is lengthened while the timeout stays at 60 s, the timeout fires on roughly the same cadence as the ping and the loop re-dials continuously — strictly worse than the flat 15 s ping it replaced. This coupling is the reason the earlier adaptive-ping implementation was reverted.

Two related battery details:

- The heartbeat `AlarmManager` fallback (`scheduleHeartbeatAlarm`) is armed **only** from `sendPing()`. It used to be armed from `handlePong()` as well, which meant two binder round-trips per ping cycle for an alarm that by design should almost never fire (and which is quota-tracked on Android 12+ via `setExactAndAllowWhileIdle`). It uses the same visibility-scaled interval, so it stays just behind the coroutine.
- `acquireHeartbeatWakeLock()` releases any existing lock before replacing the field; otherwise overlapping alarms orphaned the previous `WakeLock` until its own 10 s timeout expired.

Battery-saver mode is **mostly** unaffected — the socket is normally torn down ~15 s after backgrounding, so the ping loop is not running. The exception is an **open chat bubble**: `scheduleBatterySaverLinger` re-checks `anyBubbleOpen()` at expiry and skips teardown, so the socket stays alive indefinitely and the ping cadence does apply. That is exactly why `anySurfaceVisible()` has to consult `WebSocketService.isAppVisible` — a bubble-only session would otherwise be treated as backgrounded while the user is actively typing in it.
