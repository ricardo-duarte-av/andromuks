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
| Unified health check (every 1s) | Detects stuck `Connecting`, `Reconnecting`, or `Disconnected` |
| `START_STICKY` restart | Triggers stuck-DISCONNECTED recovery after 5s grace period |

## `scheduleReconnection()` Flow

1. If `currentNetworkType == NONE` → set `WaitingForNetwork`, add to `pendingReconnectionReasons`, return.
2. Atomic lock check: drop if already reconnecting within 10s; reset after 30s stall.
3. Launch `reconnectionJob`: wait for `NET_CAPABILITY_VALIDATED`, then call `invokeReconnectionCallback()`.

`invokeReconnectionCallback()` reads credentials from `SharedPreferences` (no AppViewModel required), picks the primary/attached ViewModel if available, and calls `connectWebSocket()`.

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

## `setWebSocket` Ordering Invariant

In `setWebSocket` (the OkHttp `onOpen` handler), `serviceInstance.webSocket` must be assigned **before** calling `updateConnectionState(ConnectionState.Ready)`.

The monitoring coroutine periodically calls `detectAndRecoverStateCorruption()` on a different thread. If it runs between the state update and the webSocket assignment, it sees `Ready + webSocket==null`, wrongly resets state to `Disconnected`, and the notification gets permanently stuck at "Connecting..." even though the socket is alive and delivering messages.

**Safe order:** set `webSocket`, `connectionStartTime`, `lastMessageReceivedTimestamp`, `lastPongTimestamp` first — then call `updateConnectionState(Ready)`.

## Persistent Notification Desync

The notification text in release builds is driven purely by `connectionState` (not by the `isConnected` parameter passed to `updateConnectionStatus`). It uses a dedup key `"$currentState-$callbackMissing"` and skips updates when the key hasn't changed.

If state gets stuck at `Disconnected` (e.g., via the corruption detector race above), every subsequent `updateConnectionStatus` call returns early and the notification never recovers until the next actual state transition.

## Unified Monitoring (every 1s, `startUnifiedMonitoring()`)

Runs on `serviceScope` inside the service instance. Performs four checks per tick:

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
- A chat bubble being open extends the lifetime: `scheduleBatterySaverLinger` re-checks `BubbleTracker.anyBubbleOpen()` at expiry and skips teardown if any bubble is alive. `cancelBatterySaverLinger` is called whenever a surface (main activity or bubble) becomes visible.
- Notification reply and mark-as-read while disconnected are routed through the gomuks backend's official one-off command endpoint `POST <homeserver>/_gomuks/exec/{command}` (`ExecApi.sendMessage` → `send_message`, `ExecApi.markRead` → `mark_read`); the raw JSON body is the command's `data`, identical to the WebSocket frame's `data`, authed with the same `gomuks_auth` cookie. FCM provides push delivery, so no socket is needed in steady-state.
- Other state-updating commands take the same `/exec` route while disconnected via `ExecCommandCoordinator`, which allocates a synthetic `request_id`, registers it in the same request-tracking map(s) the WS path uses, and feeds the parsed body back through `handleResponse`/`handleError` (the `/exec` body is byte-identical to a WS `response` frame's `data`, so no handler changes are needed): `paginate` (timeline-cache hydration on FCM wake-up, `paginateViaExec`) and `get_specific_room_state` (on-demand user-profile fetch — `flushProfileBatch` falls back to `/exec` when the socket is down; see [USER_PROFILES.md](USER_PROFILES.md#when-get_specific_room_state-is-requested)).
- **No batching:** `SyncBatchProcessor.batterySaverModeEnabled = true` makes `processSyncComplete` always take the immediate path, even while backgrounded. Within the 15 s linger window every arriving `sync_complete` is applied straight to the caches; no `batchJob` is ever scheduled, so there is no future wakeup queued against a socket that is about to die. Any sync the user missed while disconnected is re-delivered on the next connect — the backend sends `clear_state=true` followed by a fresh sync (or a resume via `last_received_event` if eligible), not a replay of the missed stream.
- At linger expiry the service flips `PREF_BATTERY_SAVER_USER_DISCONNECTED` and either calls `primaryVm.markForceFreshPaginateAfterWsDown()` (if a VM is attached) or sets `PREF_FORCE_FRESH_TIMELINE_PAGINATE` (consumed by the next VM open via `consumeForceFreshTimelinePaginatePending`). The next room open then bypasses the timeline cache fast path and paginates fresh, so a stale snapshot from before the disconnect cannot leak into the UI.
- On foreground resume, `onAppBecameVisible` clears `PREF_BATTERY_SAVER_USER_DISCONNECTED`, reschedules `WebSocketHealthCheckWorker`, and runs the same `pingNowWithWatchdog` re-dial that the always-on path uses.

The setting can be toggled at runtime. The lifecycle change takes effect on the next background/foreground transition; no service restart is forced. No connectivity probe is needed when enabling battery-saver mode — `/_gomuks/exec` is served by the main gomuks backend, which is reachable whenever the homeserver is.

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
| Ping interval | 15s | OkHttp ping cadence |
| Ping timeout | 60s | Max time to wait for pong |
| `NETWORK_CHANGE_DEBOUNCE_MS` | — | Debounce for rapid network events |
| `NETWORK_VALIDATION_TIMEOUT_MS` | — | Max wait for `NET_CAPABILITY_VALIDATED` |
| `INIT_COMPLETE_TIMEOUT_MS_BASE` | — | Max wait for `init_complete` after run_id |
| Reconnect stuck guard | 30s | Reset stuck reconnection lock |
| Stuck-Disconnected delay | 5s | Grace period before health-check recovery |
