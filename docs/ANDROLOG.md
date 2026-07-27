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

When adding new probes, keep the category short and stable (it renders as a chip and groups related events when scanning the export).
