# Debugging: WebSocketService never revives after FCM-tap from long background

**Status:** candidate fix landed 2026-05-28 — `FCMService` PendingIntent flags changed from
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` to `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`
(`FCMService.kt:785`). See "Current theory" below. **Verify on device** with the test plan
at the bottom of this doc before closing.

The older "spurious 401 auto-logout" theory is **down-weighted** — see "Why the 401 theory
was wrong" below. The diagnostic `DIAG-WS-START` Log.i breadcrumbs remain in place and are
still useful if this fix doesn't pan out.

---

## Confirmed variant (2026-06-10): no dial after FCM-tap behind a biometric lock — FIXED

A distinct, reliably-reproducible cause of the same "socket never dialed, pulsing red
`CloudOff` on the header" symptom, specific to **battery-saver mode + the biometric
app-lock (or a device keyguard)**:

1. Battery-saver tore the socket down ~15 s after the last background (`scheduleBatterySaverLinger`)
   and stopped the foreground service. At tap time there is no service and no socket — the red
   indicator is honest.
2. The FCM tap opens `MainActivity` onto a **restored back stack** whose top is a `room_timeline`
   (Compose Navigation persists/restores it across process death). So `auth_check` — the start
   destination and the only cold-start caller of `initializeWebSocketConnection()` — never
   composes (the documented [AuthCheck-bypass gap](WEBSOCKET_LIFECYCLE.md#cold-start-dialer--authcheck-bypass-watchdog)).
3. `onNewIntent` only recovers a *stuck* socket (`isConnectionStuck()`), not a cleanly *down*
   one — with the service gone that returns `false`, so its dial is skipped (`MainActivity.kt:939`).
4. The biometric lock holds the activity **below `RESUMED`** while the prompt is up (it drives
   `ON_STOP` — see the `promptInFlight` re-lock guard in `BiometricLock.kt`). So:
   - `onResume → onAppBecameVisible` (the battery-saver foreground re-dialer) is gated on
     `isAtLeast(RESUMED)` / `::appViewModel.isInitialized` and doesn't run during the lock.
   - The **cold-start watchdog** (then a one-shot `LaunchedEffect(Unit)` + `delay(2_500)`) *did*
     run, but fired while the app was still backgrounded behind the lock. Its dial chain ends in
     `startWebSocketService() → context.startForegroundService()`, which is **illegal from the
     background on Android 12+** (`ForegroundServiceStartNotAllowedException`). The exception was
     swallowed by a generic `catch (Exception)` at `AppViewModel.kt`, so no service was created,
     `connectWebSocket` bailed on `waitForServiceInstance`, and — being one-shot — the watchdog
     never retried. The socket stayed down for the whole process.

**Root cause:** every automatic dialer assumed "notification tapped ⇒ app foregrounded", but
with a lock that's false until *after* unlock. During the only windows where a dial was
attempted, the activity was in the background, so AuthCheck was bypassed, `onAppBecameVisible`
was gated off, and the watchdog's FGS start was denied-and-swallowed.

### The fix (Option 2 + Option 3)

- **Option 2 — `AppNavigation` watchdog is now `RESUMED`-gated (`MainActivity.kt`).** Replaced
  the one-shot `LaunchedEffect(Unit)` + `delay(2_500)` with
  `LaunchedEffect(lifecycleOwner) { lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { … } }`.
  The dial block now runs only once the activity is `RESUMED` (so `startForegroundService` is
  legal by construction) and **re-arms on every foreground transition** (so a dial still owed
  after unlock/return is retried instead of lost). Gating on `RESUMED` — not on the lock being
  dismissed — is deliberate: the biometric overlay is drawn inside the (already-`RESUMED`)
  activity, so dialing while it's still showing is fine; the socket connects in the background
  while the UI stays covered.
- **Option 3 — the FGS denial is no longer silent (`AppViewModel.startWebSocketService`).** Added
  a dedicated `catch (e: ForegroundServiceStartNotAllowedException)` (before the generic
  `catch (Exception)`) that logs at `Log.w` with a `DIAG-WS-START` marker. Any *remaining*
  background-dial attempt now surfaces in the breadcrumbs instead of going dark.

### Confirming from logs

```bash
grep -nE "FCMOpen|DIAG-WS-START|startWebSocketService DENIED|ForegroundServiceStartNotAllowed|service instance never appeared|onAppBecameVisible" /tmp/andromuks-*.log
```

- `startWebSocketService DENIED - ForegroundServiceStartNotAllowed` present → a dialer still
  fired while backgrounded (pre-fix signature, or a path the `RESUMED` gate doesn't cover).
- `onAppBecameVisible: …` absent across the tap → the lock kept the app below `RESUMED`, so the
  foreground re-dialer never ran (expected pre-fix).

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

## Current theory (2026-05-28): FCM PendingIntent used `CLEAR_TASK`

**Decisive observation from the user:** the wedge is recovered by **tapping the launcher
icon** — no process kill required, no login screen, credentials intact. Whatever broke the
FCM-tap path leaves the existing process recoverable from the normal warm-resume entry.

### What differs between FCM tap and launcher tap

| | FCM tap (old) | Launcher tap |
|---|---|---|
| Intent flags | `FLAG_ACTIVITY_NEW_TASK \| FLAG_ACTIVITY_CLEAR_TASK` (`FCMService.kt:785`) | default launcher flags |
| Effect on existing task | wipes the back stack, **destroys & recreates MainActivity** | brings existing MainActivity to front via `onNewIntent` |
| Activity-scoped state | new ViewModelStore → **new AppViewModel** | preserved |
| Lifecycle | `onCreate` from scratch | `onNewIntent` + `onResume` only |

So when the process is frozen and an FCM is tapped, AMS thaws, then `CLEAR_TASK` destroys
the old MainActivity (and its AppViewModel), and constructs a fresh MainActivity /
AppViewModel — while sharing the process with stale singletons (`WebSocketService` class
statics, `RoomTimelineCache`, `RoomMetadataStore`, etc.). This **"fresh VM + stale process
singletons"** hybrid is the suspected wedge: one or more of the silent bails in
`AppViewModel.initializeWebSocketConnection` (`:10938`, `:10944`, `:10954`, `:10900`) and
`WebSocketService.connectWebSocket` (`:1614`, `:1626`, `:1632`) fires from inherited stale
state on the brand-new VM.

The launcher tap doesn't recreate anything — same MainActivity, same VM, same singleton
state — so the bad hybrid never materialises, and the in-memory revival path runs cleanly.

### The fix

Change `FCMService.kt:785` from

```kotlin
flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
```

to

```kotlin
flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
```

Rationale:

- MainActivity has no `launchMode` (default `standard`) and the app effectively has one
  Activity — Compose owns all internal navigation. So MainActivity is always at the top of
  its task when it exists. `SINGLE_TOP` on that = `onNewIntent`, deterministically.
- `onNewIntent` is the documented warm-start FCM-tap path
  (`NOTIFICATIONS.md:124-173`) — it already has all the right invariants
  (`setDirectRoomNavigation` not `navigateToRoomWithCache`, same-room guard,
  `launchSingleTop` defence-in-depth).
- `CLEAR_TASK` was the **only** site in the codebase using it on a MainActivity
  PendingIntent. Every other PendingIntent into MainActivity (4 sites in
  `WebSocketService.kt`, 1 in `EnhancedNotificationDisplay.kt`) already uses
  `NEW_TASK | SINGLE_TOP`. The FCM site was an inconsistency, present since
  `ae3aea71 Add Firebase Cloud Messaging support to the app` with no follow-up commits
  tying it to a specific defect.
- Back-stack hygiene (System Back exits the app for FCM-opened rooms) is preserved by
  the existing Compose-nav layer: `popUpTo("auth_check") { inclusive = true }` in
  `navigateToRoomTimelineForExternalEntry` (`NOTIFICATIONS.md:160-173`) and the
  `openedViaDirectNotification` flag (`STATE_INVARIANTS.md:70-80`). Neither depends on
  Activity flags.

### Why the 401 theory was wrong

The earlier theory (a handshake 401 wipes `gomuks_auth_token` via
`AppViewModel.handleUnauthorizedError`) is incompatible with the launcher-tap recovery:
if credentials had been wiped, the launcher tap would land on `LoginScreen`
(`AuthCheck.kt:41/365` reads null token → login route), not on a working app. The
"rebuild → login screen" observation that originally seeded the theory was probably a
separate event (a real expiry, or rebuild-specific behaviour) and got over-fit.

### Test plan

See "How to verify the fix on device" at the bottom of this doc.

---

## Older lead (2026-05-28, down-weighted): credentials were wiped — spurious-401 auto-logout

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
      → context.startForegroundService(intent)  or  startService(intent) (FGS-latch) ← FGS background-start denial now logged (DIAG-WS-START, "startWebSocketService DENIED"); see the biometric-lock variant above
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

The 20-hour-old buffer is now gone (logcat -c clobbered it). Two capture modes —
prefer the **live tail** so the next repro is caught automatically without
remembering to dump before clearing.

### Live tail (set up before backgrounding)

Filter by **UID**, not PID or tag. UID is stable for the install, so the tail
survives the process being reaped and restarted (exactly what may happen here),
and it picks up system-side messages tagged differently — `ActivityManager`
killing the service, `ForegroundServiceDidNotStartInTimeException`,
`ForegroundServiceStartNotAllowed`, OkHttp WebSocket failures, native crashes —
all of which a tag filter (`Andromuks:*`) would drop.

```bash
PKG=pt.aguiarvieira.andromuks
UID=$(adb shell cmd package list packages -U $PKG | sed 's/.*uid://')
adb logcat -b all -v threadtime --uid=$UID | tee /tmp/andromuks-live-$(date +%s).log
```

### Post-hoc dump (if the bug is already wedged)

Do this **before** any `logcat -c` or rebuild:

```bash
adb logcat -b all -d > /tmp/andromuks-wedged-$(date +%s).log
adb shell dumpsys activity processes pt.aguiarvieira.andromuks > /tmp/andromuks-procs-$(date +%s).txt
adb shell dumpsys activity services pt.aguiarvieira.andromuks  > /tmp/andromuks-svcs-$(date +%s).txt
```

### Decisive grep

```bash
grep -nE "401 Unauthorized detected|Handling 401 Unauthorized error|Failure reason|DIAG-WS-START|pingNowWithWatchdog|re-dialling|connectWebSocket|startWebSocketService|ForegroundServiceStartNotAllowed|ForegroundServiceDidNotStartInTime" \
  /tmp/andromuks-*.log
```

- Hits on the 401 lines → confirmed; grab the `Failure reason: ...` line above
  (`NetworkUtils.kt:706`) for the underlying `response.code` / exception to tell
  genuine vs spurious.
- No 401 hits but `DIAG-WS-START` lines present → it's a bail in the revival
  chain, not 401.

## How to verify the fix on device

The wedge is hard to reproduce on demand because it requires long-background Doze. The
plan below has two parts: a **fast structural test** that proves the new flag actually
routes through `onNewIntent` (catches the change if it broke something obvious), and a
**slow real-world test** that confirms the wedge no longer happens.

### Part 1 — fast structural test (5 minutes, do this first)

Build and install debug:

```bash
./debug-build.sh
```

We need to confirm that an FCM-style tap delivers via `onNewIntent` on the existing
MainActivity, not by recreating it. Simulate the PendingIntent without waiting for a real
FCM:

```bash
# Open the app via launcher first, navigate to any room, leave it foregrounded.
adb shell am start -n pt.aguiarvieira.andromuks/.MainActivity \
  -a android.intent.action.VIEW \
  --activity-single-top --activity-new-task \
  --es room_id "!some-room-id:server" \
  --ez direct_navigation true \
  --ez from_notification true
```

Watch logcat while you fire it:

```bash
adb logcat -v threadtime Andromuks:* AndroidRuntime:E *:S
```

You should see `MainActivity: onNewIntent - …` lines (`MainActivity.kt:885`), **not**
`MainActivity: onCreate - …` lines. If you see `onCreate`, the flag isn't doing what we
expect — stop and investigate.

Repeat with a real notification: send yourself a Matrix message from another client, tap
the notification. Same expectation: `onNewIntent`, room opens, timeline paints.

### Part 2 — real-world long-background test

This is the actual bug. Two approaches:

**Natural reproduction** (slow, what users hit): leave the device idle overnight or
across a workday (≥ 8 h, ideally with the screen off and the device unplugged so Doze
kicks in). Don't touch Andromuks. When you come back, before tapping anything, capture
state in case it's wedged:

```bash
APP_UID=$(adb shell dumpsys package pt.aguiarvieira.andromuks | grep -oE 'userId=[0-9]+' | head -1 | cut -d= -f2)
adb logcat -b all -v uid,threadtime -d > /tmp/andromuks-pre-tap-$(date +%s).log
adb shell dumpsys activity processes pt.aguiarvieira.andromuks > /tmp/andromuks-procs-$(date +%s).txt
adb shell dumpsys activity services  pt.aguiarvieira.andromuks > /tmp/andromuks-svcs-$(date +%s).txt
```

Then tap an FCM notification. Expectations:

- Timeline paints (room name, avatar, messages) from cache within ~1 s.
- WS reconnects within a few seconds; the FGS notification ("WebSocket connected")
  reappears.
- Subsequent FCMs render normally.

If any of those fail, capture another log dump immediately and grep for `DIAG-WS-START`
markers to see which bail fired.

**Forced reproduction** (faster, less realistic): force Doze on a connected device:

```bash
# Put device in Doze idle state without waiting hours
adb shell dumpsys deviceidle force-idle deep
# Confirm
adb shell dumpsys deviceidle | grep "mState="
# (should print mState=IDLE)

# Wait ~5-10 minutes for the app's WS to drop and the process to be frozen.
# Verify:
adb shell dumpsys activity processes pt.aguiarvieira.andromuks | grep -E "isFrozen|oom adj|cached"
# (expect isFrozen=true and cached=true; WebSocketService missing from the service list)

# Now trigger an FCM by sending yourself a message from another client.
# Wait for the notification to arrive (Doze allows high-priority FCM).
# Tap it.
```

Same expectations as the natural test. Reset Doze afterwards:

```bash
adb shell dumpsys deviceidle unforce
```

Note: the forced-Doze path doesn't exercise the full freezer/cached pipeline identically
to a real overnight idle — the kernel cgroup freeze and TRIM_MEMORY_COMPLETE pressure
arrive in a different order — so a green forced-Doze run is necessary but not sufficient.
Plan to also run the natural test at least once.

### What "fixed" looks like

- After the long-background tap, the room paints (cached events visible immediately,
  WS reconnect fills the gap).
- The "WebSocket connected" FGS notification reappears.
- `dumpsys activity services pt.aguiarvieira.andromuks` lists `WebSocketService`.
- Subsequent FCMs render notifications normally (no silent drops after
  `"Showing notification for room: …"`).

### What "still broken" looks like

Symptoms identical to before: blank timeline, persistent "no connection" indicator, no
FGS notification, subsequent FCMs decrypt but never display. If this is what you see,
the `CLEAR_TASK` flag was a red herring — fall back to the `DIAG-WS-START` breadcrumbs
to determine which bail in the revival chain fired.

## When this is solved

Remove every `// DIAG-WS-START:` line and its accompanying `Log.i` (one `grep -rn`,
delete, done) and delete this doc.
