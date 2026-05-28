# Debugging: WebSocketService never revives after FCM-tap from long background

**Status:** open. Diagnostic logs landed; awaiting next repro.

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
