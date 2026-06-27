# Observability — Crash Reporting & Performance Monitoring

Andromuks ships two **opt-in** Firebase observability features, both off by default and toggled
from the **switches** group of `SettingsScreen`:

| Feature | SDK | Coordinator | Pref | Default |
|---|---|---|---|---|
| Crash + non-fatal error reporting | Firebase Crashlytics | `ErrorReportingCoordinator` | `crash_reporting_enabled` | `false` |
| Performance monitoring | Firebase Performance | `PerformanceMonitoringCoordinator` | `performance_monitoring_enabled` | `false` |

Both reuse the existing Firebase project that backs FCM (`app/google-services.json`, app ID
`pt.aguiarvieira.andromuks`) — no extra project setup. For the *local*, release-safe event log (a
different thing entirely — on-device, no network), see [ANDROLOG.md](ANDROLOG.md). For the pref
storage mechanics (SharedPreferences keys, `loadSettings`, reactivity), see
[SETTINGS_PREFS.md](SETTINGS_PREFS.md#crash-reporting-opt-in-crash_reporting_enabled).

## Opt-in / privacy model

This is a Matrix client, so collection is **opt-in and off by default**, enforced in two layers:

1. **Manifest** disables automatic collection at build time:
   - `firebase_crashlytics_collection_enabled = false`
   - `firebase_performance_collection_enabled = false` (deliberately **not** `_deactivated`, which
     would be permanent and impossible to turn on at runtime)
2. **Runtime** flips it on/off when the user toggles the switch, and the SDKs persist that choice on
   their own. We additionally **re-assert our SharedPref into the SDK on every launch** (in
   `SettingsCoordinator.loadSettings` → each coordinator's `applyPersistedState`) so our pref stays
   the single source of truth even after a reinstall/data-clear that wipes the SDK's own state.

Reports carry only diagnostics — stack traces, device/OS, app state, aggregate timings — **never
message content, room names, or account identifiers**. The Settings copy says as much to the user.

## Crash reporting (`ErrorReportingCoordinator`)

`app/src/main/java/net/vrkknn/andromuks/ErrorReportingCoordinator.kt`

- **Instance methods** (vm-bound): `applyPersistedState()`, `setEnabled(Boolean)`.
- **Companion (stateless, process-wide)** — callable from layers without an `AppViewModel`
  (`WebSocketService`, `SyncRepository`):
  - `report(throwable, message?)` — logs an optional breadcrumb, then `recordException`.
  - `log(message)` — breadcrumb only.
  - `setKey(key, value)` — custom key on subsequent reports.

All companion calls are **no-ops while collection is disabled**, so call sites need no guard.

### Captured automatically
Uncaught exceptions (hard crashes) are captured with no code, and **uploaded on the next app
launch** (Crashlytics batches the report as the process dies). So after a crash, reopen the app to
send it.

### Collection must be primed at process start (not just on the late VM path)
Crashlytics only fetches and caches its backend **settings config** once collection is *enabled*,
and it cannot send (nor, on a fresh install, finalize) a crash report until that config is cached.
Our opt-in `applyPersistedState()` runs late and off-main (inside `loadSettings` on `Dispatchers.IO`),
which left no time for the async settings fetch before an early crash — the on-crash send blocked
~3 s on the crashing thread and then bailed with `Cannot send reports. Timed out while fetching
settings.` (a release-only symptom, because debug builds usually had the config cached from a prior
session). To fix this, `AndromuksApplication.onCreate` → `primeFirebaseObservability()` asserts the
persisted `crash_reporting_enabled` / `performance_monitoring_enabled` flags into the SDKs
**synchronously at process start** (prefs are pre-warmed just before), giving the settings fetch the
whole session to complete. The per-VM `applyPersistedState()` calls still run later and remain
authoritative; they're idempotent.

### Instrumented non-fatals (`report(...)`)
Added at existing `Log.e` failure points where an exception is caught and swallowed:

| File | Sites |
|---|---|
| `WebSocketService` | service-initiated reconnect, `sendCommand`, `connectWebSocket` |
| `SyncRepository` | `sync_complete` pipeline failure |
| `AppViewModel` | incoming + ack message apply; 3 `processInitialSyncComplete` crash paths; per-message `onInitComplete` crash; `reconnectAfterReauth` |

### Deobfuscation
R8 obfuscates release stack traces. The Crashlytics Gradle plugin uploads the mapping file;
`mappingFileUploadEnabled` is pinned **explicitly** in `app/build.gradle.kts` (`true` on `release`,
`false` on `debug`) so an accidental flip can't silently ship unreadable crashes.

### Test trigger
The **Crash Reporting** Settings card has a **"Crash the app"** button (enabled only while reporting
is on) that throws an uncaught `RuntimeException` to verify the pipeline end-to-end. Reopen the app
afterwards to upload the report.

## Performance monitoring (`PerformanceMonitoringCoordinator`)

`app/src/main/java/net/vrkknn/andromuks/PerformanceMonitoringCoordinator.kt`

- **Instance methods** (vm-bound): `applyPersistedState()`, `setEnabled(Boolean)`.
- **Companion (stateless):** `startTrace(name): Trace?` / `stopTrace(trace, attribute?)` — no-ops
  while collection is off.

### Captured automatically
App start time, foreground/background, **HTTP** request timing (auto-instruments OkHttp), and screen
rendering (slow/frozen frames).

### Custom `ws_connect` trace
Firebase auto-instruments HTTP but **not** OkHttp WebSockets — our primary transport. To close that
gap, `NetworkUtils.connectToWebsocket` wraps the connection establishment in a custom trace:

- **Started** right before `client.newWebSocket(...)`.
- **Stopped** in `onOpen` (attribute `outcome=success`) or `onFailure` (`outcome=failure`).

The `connectTrace` local is captured by the `WebSocketListener` closure (Kotlin captures the mutable
local by reference), set back to `null` once stopped. It measures TCP + TLS + HTTP-upgrade latency,
filterable in the console by the `outcome` attribute.

### Sampling
Firebase Performance **samples sessions** in production — not every launch reports — so it surfaces
aggregate trends, not per-incident timings (the opposite of Crashlytics).

## Where to monitor

| What | Where |
|---|---|
| Crashes & non-fatals | Firebase console → **Run → Crashlytics** |
| Performance (incl. `ws_connect`) | Firebase console → **Run → Performance** |

Console: `https://console.firebase.google.com/project/<project-id>/{crashlytics,performance}`

- **First-data delay:** Performance Monitoring has a ~12 h processing lag for the first data after
  enabling (and for a new app version's first report); afterwards roughly hourly. To confirm a trace
  fires locally without waiting, filter logcat for `FirebasePerformance` (it logs trace durations).
- **Crashlytics** is near-real-time once the post-crash relaunch uploads the report.

## Android vitals (no code)

A complementary, **zero-SDK** baseline: Play Console → **Quality → Android vitals** aggregates
OS-collected metrics (ANRs, crash rate, excessive wakeups/wake locks, slow/frozen frames, startup
time, battery) for **Play-distributed** installs only. It needs no app changes and carries no
per-user data, so it's a safe always-on floor with no consent gate — unlike the opt-in Firebase
features above. Together: Firebase gives in-app, opt-in, customizable signals (the `ws_connect`
trace); Android vitals gives a passive field baseline; the `:baselineprofile` + Macrobenchmark
module covers the lab side.
