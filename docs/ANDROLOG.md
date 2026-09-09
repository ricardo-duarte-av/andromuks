# Androlog — persistent, release-safe event log

`Androlog` is a lightweight, app-wide log for **cherry-picked events** that must survive into release builds. It exists because the project's R8 config (`app/proguard-rules.pro`) strips `Log.d`/`Log.v`/`Log.isLoggable` from release APKs — so the chatty debug diagnostics that are invaluable while developing are simply gone when a user hits a problem on a release build. Androlog entries are kept in memory and persisted to `SharedPreferences`, viewable on a dedicated Settings screen and exportable, in **both debug and release**.

It is *not* a replacement for logcat. Use it sparingly, for the handful of events you actually want to inspect after the fact on a real device.

Androlog is **on-device and offline** — nothing leaves the phone. For *remote*, opt-in crash and performance reporting (Firebase Crashlytics / Performance), see [OBSERVABILITY.md](OBSERVABILITY.md).

## API

`Androlog` is a process-wide singleton object (`app/src/main/java/net/vrkknn/andromuks/Androlog.kt`). Call it from anywhere via the invoke operator:

```kotlin
Androlog("Notifications", "Failed to download notification image: HTTP 404 Not found")
```

`Androlog.log(category, text)` is the explicit equivalent. Each entry records:

| Field | Source |
|---|---|
| `timestamp` | `System.currentTimeMillis()` at log time |
| `category` | caller-provided free text (e.g. `"Notifications"`) |
| `text` | the log message |

Other members:

- `Androlog.getEntries(): List<Entry>` — snapshot for the UI.
- `Androlog.clear()` — wipe in-memory + persisted log.
- `Androlog.init(context)` — called once in `AndromuksApplication.onCreate()`. Loads persisted entries and flushes anything logged before a context was available.

Each call also mirrors to logcat via `Log.i("Androlog", "[$category] $text")`. `Log.i` survives R8, so the same line is visible in a live logcat dump too.

## Storage & limits

- In-memory list capped at `MAX_ENTRIES = 200` (oldest dropped first).
- Persisted to `SharedPreferences("AndromuksAndrologPrefs")` under key `androlog`, as a JSON array of `{timestamp, category, text}`. Written with `apply()` (best-effort, non-blocking) — mirrors the WebSocket activity-log pattern in [`DiagnosticsCoordinator`](../app/src/main/java/net/vrkknn/andromuks/DiagnosticsCoordinator.kt).
- Calls made before `init()` are buffered in memory and persisted on the next `init()`; persisted entries load ahead of those pre-init entries.
- Thread-safe: all list mutation is `synchronized` on a private lock.

## UI

`AndrologScreen` (`app/src/main/java/net/vrkknn/andromuks/AndrologScreen.kt`), reached from **Settings → WebSocket Debug → "Androlog" → View Androlog** (nav route `"androlog"`, registered in `MainActivity`). It mirrors `ReconnectionLogScreen`:

- Entries listed newest-first; each card shows the timestamp, a category chip, and the text.
- **Export** writes a `timestamp | category | text` `.txt` via the system document picker.
- **Clear** (trash icon) wipes the log.

## Current instrumentation

### `"ReplyResolution"` / `"RPC"`

How reply previews resolved their target event, and the retry behaviour underneath. Full tier table
and how to read the numbers: [RPC_RESILIENCE.md](RPC_RESILIENCE.md#reading-the-instrumentation).

| Location | What it logs |
|---|---|
| `ReplyResolutionTracker.record` — `fetched` | A reply target needed a `get_event` round-trip, i.e. the backend's `related_events` did not cover it. Rare by design; a rising count means an ingest path is losing reply context and the failsafe is masking it. |
| `ReplyResolutionTracker.record` — `unresolved` | Nothing resolved the target; the preview rendered "Reply to unknown event". |
| `ReplyResolutionTracker.record` — rolling summary | Every 100 resolutions, a one-line tier breakdown. |
| `RpcResilienceCoordinator.settle` | A request succeeded only on a later attempt (first attempt could not be answered). |
| `RpcResilienceCoordinator.dispatch` | A request exhausted its attempt budget. |

### `"LocalEcho"`

The send-placeholder reconciliation race. Both entries concern the window where a confirmed event is
processed before the `response` that would link it to its placeholder — see
[MESSAGE_SENDING.md](MESSAGE_SENDING.md#race-condition--sync_complete-arrives-before-response-transient-duplicate).
Release-visible because the race only reproduces in the field.

| Location | What it logs |
|---|---|
| `LocalEchoCoordinator.supersedeOldestOutstanding` | A confirmed own-message event arrived whose `transaction_id` could not be resolved, so a placeholder was hidden on spec. Notes the request id and how many sends were in flight. A hide with no matching eviction line after it means the guess was wrong and the placeholder should have reappeared as Failed. |
| `LocalEchoCoordinator.onResponse` — eviction guard | The `response` landed after the confirmed event and evicted the placeholder by `transaction_id`. This is the line that closes a supersede: seeing them paired is the fix working. |

### `"Notifications"`

| Location | What it logs |
|---|---|
| `EnhancedNotificationDisplay.showEnhancedNotification` (Phase 2 enqueue gate) | `hasImage` is true but no download URL could be resolved (`deferredHttpUrl == null`), so `NotificationImageWorker` is never enqueued and the notification stays text-only forever. |
| `NotificationImageWorker.doWork` — notification gone | The notification was dismissed / marked read before the worker ran, so the image update is skipped (`Result.success`). |
| `NotificationImageWorker.doWork` — download threw | Download raised an exception; notes the attempt number and whether it will retry or has given up after 3 attempts. |
| `NotificationImageWorker.doWork` — file missing | Download returned no usable file; notes retry-vs-give-up. |
| `NotificationImageWorker.doWork` — FileProvider failed | Could not wrap the downloaded file in a `content://` URI. |
| `NotificationImageWorker.doWork` — MessagingStyle missing/empty | Could not extract `MessagingStyle` from the active notification, or it had no messages to upgrade. |
| `NotificationImageWorker.doWork` — success | The notification was updated with the image. Lets you distinguish "worker never ran" from "worker ran and succeeded" when reading the log. |

The two-phase image flow these probes cover is documented in [docs/NOTIFICATIONS.md](NOTIFICATIONS.md).

### `"WSDial"`

Every silent or quiet exit in the WebSocket dial-and-retry chain. Companion to the `"FCMOpen"`
category: `FCMOpen` covers the *navigation* half of a notification tap, `WSDial` covers the
*socket* half. Together they make a "room renders but the socket never comes up" field repro
diagnosable from an Androlog export alone, with no adb attached — which is the whole point, since
that wedge happens once in a blue moon. Background and the earlier fixed variants:
[DEBUG_WS_REVIVAL.md](DEBUG_WS_REVIVAL.md).

These sites all previously logged to logcat only (the `DIAG-WS-START` breadcrumbs and friends);
the Androlog line is added alongside, not instead.

| Location | What it logs |
|---|---|
| `AppViewModel.startWebSocketService` — entry | Whether an FGS start or the plain-`startService` fallback was chosen, plus the SDK level. |
| `AppViewModel.startWebSocketService` — `appContext == null` | The service was never asked to start. |
| `AppViewModel.startWebSocketService` — FGS denied | `ForegroundServiceStartNotAllowedException`: a dialer fired while below `RESUMED`. |
| `AppViewModel.startWebSocketService` — generic throw | Any other failure to start the service, with exception class and message. |
| `AppViewModel.initializeWebSocketConnection` — non-primary | This VM is not `PRIMARY`, so it declined to dial. |
| `AppViewModel.initializeWebSocketConnection` — already connected | Attached to an existing socket instead of dialing. |
| `AppViewModel.initializeWebSocketConnection` — delegating | The dial actually proceeded to `WebSocketService`. Its **absence** after a `startWebSocketService` line is the tell. |
| `WebSocketService.connectWebSocket` — no service instance | `waitForServiceInstance` timed out after 5 s; the service start was silently dropped. |
| `WebSocketService.connectWebSocket` — already connected / already connecting | Which of the two post-delegation bails claimed the dial. |
| `WebSocketService.scheduleReconnection` — parked | No network; the trigger was queued into `WaitingForNetwork`. |
| `WebSocketService.scheduleReconnection` — skipped | A retry was dropped by the already-reconnecting or min-interval guard. |
| `WebSocketService.scheduleReconnection` — gave up | `MAX_RECONNECTION_ATTEMPTS` reached; **no further retries will ever be scheduled**. A terminal line. |
| `WebSocketService.scheduleReconnection` — scheduled | Attempt number, backoff delay and network type for a retry that was actually armed. |
| reconnect job — aborted | Network went `NONE` after the backoff; the job ended in `Disconnected` without retrying. |
| `WebSocketService.pingNowWithWatchdog` — watchdog | The resume health-check ping saw no traffic in 3 s and declared the socket dead. A watchdog line with no `scheduleReconnection` line after it means the recovery was lost (e.g. the service scope was cancelled mid-`delay`). |
| `WebSocketService.startHardConnectingTimeout` — hard timeout | Stuck in `Connecting` past the hard ceiling; forcing recovery. |
| `NetworkUtils` WebSocket `onFailure` | The decisive one: exception class, message and HTTP code for a dial that died. |

Volume is bounded by the reconnect ladder (exponential backoff to a 120 s ceiling), so even a
sustained bad-link session cannot flood the 200-entry buffer and evict the `FCMOpen` lines that
give the tap its context.

When adding new probes, keep the category short and stable (it renders as a chip and groups related events when scanning the export).
