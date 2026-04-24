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
