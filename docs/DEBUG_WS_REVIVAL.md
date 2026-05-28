# Debugging: WebSocketService never revives after FCM-tap from long background

**Status:** open. Diagnostic logs landed. **New lead (2026-05-28): likely a spurious-401
auto-logout, not a service-revival failure.** See "New lead" section below — verify against a
captured logcat next session.

## The bug

After the app has been backgrounded/idle for a long time (Doze-territory), tapping
an FCM notification opens MainActivity → RoomTimelineScreen, but:

- Room name, avatar, timeline all stay blank.
- The "no connection" pulsing indicator is visible.
- The foreground-service notification ("WebSocket connected" / similar) never reappears.
- Subsequent FCM messages arrive (decryption succeeds, `FCMService` logs them) but the
  system notification never renders — the FCM handler reaches `Showing notification for
  room: ...` and then goes silent. No `EnhancedNotificationDisplay` logs follow.

The app appears to enter this state permanently for the lifetime of the process.

## What we know

`dumpsys activity processes pt.aguiarvieira.andromuks` while stuck showed:

```
oom adj: max=1001 curRaw=900 setRaw=900 cur=900 set=900   # CACHED_APP_MIN_ADJ
cached=true empty=false
isFrozen=true                                              # kernel cgroup freeze
lastCompactTime=... lastCompactProfile=FULL
Services:
  - ServiceRecord{... pt.aguiarvieira.andromuks/.FCMService ...}
                          # ★ WebSocketService is NOT in the list ★
```

`dumpsys activity service net.vrkknn.andromuks.WebSocketService` → `No services match`.

So: **WebSocketService was never created in this process lifetime** (or was reaped very
quickly and never restarted). With no foreground service, the process drops to cached →
gets compacted → gets frozen. Once frozen, FCM is allowed to briefly thaw the process to
deliver, but the moment the handler hits its first coroutine suspension point (the
`Dispatchers.IO` jump inside `FCMService.ensureNotificationDisplay`), the process is
refrozen and the continuation never resumes. That is why FCMs stop rendering.

So the bug to fix is: **why does WebSocketService never come back?**

## New lead (2026-05-28): credentials were wiped — spurious-401 auto-logout

**New fact from the user:** after rebuilding and relaunching the app, they were greeted by
the **login screen** — i.e. the stored credentials were gone. A plain `adb install` (what
`debug-build.sh` does — no uninstall) does **not** clear SharedPreferences, so the app must
have wiped its own credentials *while running, before the relaunch*.

### Only one path wipes the login-gating token

The login gate is `AndromuksAppPrefs` → `gomuks_auth_token` (+ `homeserver_url`), read at
`AuthCheck.kt:41` / `:365`. A grep of every `.remove(...)` / `.clear()` against the real
prefs file found exactly one writer that removes it:

- `AppViewModel.handleUnauthorizedError()` — `AppViewModel.kt:5500`
  - removes `gomuks_auth_token`, `homeserver_url`, `ws_run_id`, then `editor.commit()`
    (synchronous, permanent).
  - Called from exactly one place: `NetworkUtils.kt:717`, inside the WebSocket
    `onFailure` handler, **whenever the handshake response is HTTP 401**
    (`response?.code == 401 || (t is ProtocolException && msg contains "401")`,
    `NetworkUtils.kt:709`).

The other two `clearCredentials()` are red herrings — they operate on *separate* prefs
files and never touch `gomuks_auth_token`:

- `FCMNotificationManager.clearCredentials()` (`:291`) → `fcm_prefs`
- `WebClientPushIntegration.clearCredentials()` (`:302`) → `web_client_prefs`

### The unified theory (fits every symptom)

```
long background → reconnect → WS handshake → response.code == 401
  → NetworkUtils.kt:711   "401 Unauthorized detected"            (Log.e, survives R8)
  → handleUnauthorizedError()  AppViewModel.kt:5500
       "Handling 401 Unauthorized error"                         (Log.e, survives R8)
       remove gomuks_auth_token / homeserver_url / ws_run_id; commit()
```

After the wipe: WS revival can never succeed (no token → the bails downstream fire), so no
FGS → cached → compacted → frozen → FCM handler dies at its first `Dispatchers.IO` suspend.
That is the documented "never revives" symptom. Then the next real app open / rebuild reads
a null token at `AuthCheck.kt:365` → **login screen**. Matches the new fact exactly.

So the real bug is probably **not** "why doesn't the service revive" but **"why did a 401
log us out, and was that 401 even legitimate?"** The 401 wipe is unconditional, immediate,
and permanent — a *single* handshake 401 (genuine expiry **or** a spurious one: reconnect
after Doze dialing with a stale/empty cookie, `run_id` mismatch, or the gomuks backend
transiently 401ing during its own startup) bricks the install. No retry, no confirmation, no
transient-vs-real distinction.

### Next session — verify, then decide

1. Confirm the 401 actually fired. Both lines are `Log.e` (survive R8). In a captured log:
   ```bash
   grep -E "401 Unauthorized detected|Handling 401 Unauthorized error" /tmp/andromuks-wedged-*.log
   ```
   - **Present** → confirmed; pivot the fix to the 401 path. Capture the accompanying
     `response.code` / `t.message` from `NetworkUtils.kt:706` ("Failure reason: ...") to learn
     whether the 401 was genuine or spurious.
   - **Absent** (and the log is complete, not a post-`logcat -c` fragment) → 401 was not the
     trigger; fall back to the bail-elimination work below.
2. If confirmed spurious: candidate fixes — don't wipe on the *first* 401 (require N
   consecutive, or a confirming re-auth attempt); never drop `homeserver_url`/`ws_run_id` on a
   transient failure; gate the wipe on the backend actually rejecting a *well-formed* auth
   (verify the reconnect even sent the cookie/token).

## Why we can't tell yet which bail killed it

The 19:16 reconnect path *did* log the entry: `pingNowWithWatchdog returned false →
re-dialling WebSocket` (ViewModelLifecycleCoordinator.kt:243). After that, no further
WS-lifecycle logs were emitted. The chain that should follow:

```
ViewModelLifecycleCoordinator.kt:248  initializeWebSocketConnection(url, token)
  → AppViewModel.kt:10938  if (instanceRole != PRIMARY) return                       ← silent bail #1
  → AppViewModel.kt:10944  if (isWebSocketConnected()) return                        ← silent bail #2
  → AppViewModel.kt:10954  startWebSocketService()
      → AppViewModel.kt:10900  appContext?.let { ... } else silent                   ← silent bail #3
      → context.startForegroundService(intent)  or  startService(intent) (FGS-latch) ← potentially silent
  → AppViewModel.kt:10978  WebSocketService.connectWebSocket(...)
      → WebSocketService.kt:1614  waitForServiceInstance(5_000L) ?: return           ← silent unless captured
      → WebSocketService.kt:1626  if (isWebSocketConnected()) return                 ← debug-only log
      → WebSocketService.kt:1632  if (state is Connecting) return                    ← debug-only log
      → WebSocketService.kt:1664  Network not validated → return                     ← warn-level (visible)
```

Eliminated so far:

- **FGS denial latch (`foregroundStartNotAllowedForThisProcess`)** is NOT the cause.
  `adb logcat -b all -d | grep -iE "ForegroundServiceStartNotAllowed"` returned nothing,
  and `dumpsys` showed no `mFgsStartTempAllowList` entries that would hint at it.

Still in the running: bails #1, #2, #3, and the `waitForServiceInstance` timeout.

## What the diagnostic patch adds

Every silent bail in the WS-revival chain now emits an unconditional `Log.i("Andromuks", ...)`.
`Log.i` survives R8 stripping (proguard-rules.pro keeps `Log.i`/`w`/`e`; only `Log.d` and
`Log.v` are stripped), so release builds are equally diagnosable.

Search markers (`DIAG-WS-START`) make removal trivial once we have the answer:

```bash
grep -rn "DIAG-WS-START" app/src/
```

Each marked line is paired with a one-line comment pointing back to this doc.

Sites instrumented:

| File | Line area | What it tells us |
|---|---|---|
| `AppViewModel.kt` `startWebSocketService` | top + FGS fallback branch | appContext null? FGS or plain startService chosen? |
| `AppViewModel.kt` `initializeWebSocketConnection` | role/already-connected/delegate | which of the three early returns fired |
| `WebSocketService.kt` `connectWebSocket` | service-instance/already-connected/already-connecting | post-delegation bails |

## How to capture a clean repro

The 20-hour-old buffer is now gone (logcat -c clobbered it). Next time the bug is
reproducible, capture **before** clearing:

```bash
adb logcat -b all -d > /tmp/andromuks-wedged-$(date +%s).log
adb shell dumpsys activity processes pt.aguiarvieira.andromuks > /tmp/andromuks-procs-$(date +%s).txt
adb shell dumpsys activity services pt.aguiarvieira.andromuks  > /tmp/andromuks-svcs-$(date +%s).txt
```

Then `grep -E "DIAG-WS-START|pingNowWithWatchdog|re-dialling|connectWebSocket|startWebSocketService|state="`
on the captured log will show exactly which bail fired.

## When this is solved

Remove every `// DIAG-WS-START:` line and its accompanying `Log.i` (one `grep -rn`,
delete, done) and delete this doc.
