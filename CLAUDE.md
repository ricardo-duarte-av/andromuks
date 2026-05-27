# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Andromuks is a Matrix protocol chat client for Android, built with Jetpack Compose. It connects to a Matrix homeserver via WebSocket and provides real-time messaging, media sharing, reactions, replies, spaces, and push notifications via FCM.

- **App ID**: `pt.aguiarvieira.andromuks`
- **Min SDK**: 24 (Android 7.0), **Target SDK**: 36
- **Architecture**: Single-Activity (Compose + NavController), MVVM + Coordinator Pattern

## Build Commands

```bash
# Debug build + install to connected device
./debug-build.sh
# Equivalent: ./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

# App Bundle (for Play Store)
./bundle-build.sh

# Full assemble
./gradlew assemble --stacktrace --no-daemon --parallel --build-cache

# Unit tests
./gradlew test

# Instrumentation tests
./gradlew connectedAndroidTest

# Single test class
./gradlew test --tests "net.vrkknn.andromuks.SomeTest"
```

Build produces arm64-v8a only (ABI splits enabled). JVM heap is configured at 4GB (`-Xmx4096m`).

## Architecture Overview

### Layered Architecture

```
UI (Compose Screens)
    ↓
AppViewModel  ←→  18+ Coordinator classes  (feature-specific orchestration)
    ↓
12+ Singleton Cache classes  (in-memory state stores, single source of truth)
    ↓
WebSocketService (foreground service) → SyncRepository (event flow)
    ↓
OkHttp WebSocket (Matrix protocol)
```

### Core Classes

- **`AppViewModel`** — Master state holder. Processes sync events, manages timeline rendering, coordinates all subsystems. Extremely large (the central hub). See **[docs/APPVIEWMODEL.md](docs/APPVIEWMODEL.md)** for a full reference of every state field and function.
- **`WebSocketService`** — Foreground service keeping the Matrix connection alive. Monitors health via 15s ping / 60s timeout. Handles reconnection with exponential backoff and is network-change-aware.
- **`SyncRepository`** — Sits between the WebSocket and AppViewModel; coordinates event flow. The `_events` SharedFlow has `extraBufferCapacity = 1024` and `BufferOverflow.DROP_LATEST` — burst traffic sheds the newest arrivals. `sync_complete` messages bypass `_events` entirely and go to a separate `syncCompleteChannel` (UNLIMITED).
- **`SyncBatchProcessor`** — Batches `sync_complete` messages while the app is backgrounded and flushes them as a single unit on foreground resume (500 ms delay, then `flushBatchLocked`). While a batch is in flight `shouldSkipTimelineRebuild = true` suppresses per-event `buildTimelineFromChain()` calls; `triggerDeferredRebuild()` performs a single rebuild when the batch ends. `timelineRefreshTrigger` in AppViewModel is incremented twice on foreground: once immediately (show cached state) and once after the flush (pick up batch events). `RoomTimelineScreen` watches it with **a single `LaunchedEffect`** that calls `requestRoomTimeline()` and then unconditionally updates `lastKnownRefreshTrigger` — do not split this into two effects with the same key, as the second would race to update the tracker before the first can act on it. **Sidecar bypass:** when `sidecarModeEnabled = true` (mirrored from `useSidecarMode` by `SettingsCoordinator`), `processSyncComplete` always takes the immediate path regardless of app visibility, so no `batchJob` is ever scheduled. The WS is torn down 15 s after backgrounding in sidecar mode, so there is no ongoing stream to batch — the delayed flusher would only fire a wasted wakeup against a dead socket. Anything missed while disconnected comes back via `clear_state` + resync on reconnect, not from a replay queue.
- **Coordinator classes** (18+) — Each owns a specific feature domain (reactions, read receipts, typing, FCM, uploads, navigation, settings, etc.). They decompose AppViewModel complexity.
- **Cache classes** (12+) — `RoomTimelineCache`, `RoomListCache`, `RoomMemberCache`, `ProfileCache`, `SpaceListCache`, `RoomAccountDataCache`, etc. Singleton in-memory stores shared across Activities. `IntelligentMediaCache` is used by `ConversationsApi` (shortcut icons) and `PersonsApi`/`EnhancedNotificationDisplay` (notification avatars) — **not** for timeline media or UI avatar display, both of which delegate entirely to Coil's built-in disk/memory cache. `RoomAccountDataCache` holds per-room account data (e.g. `fi.mau.gomuks.preferences`) populated from sync.

### Cold-start cache-first rendering

On cold start, the app paints `RoomListScreen` from the persisted room cache before the WebSocket even connects. The flow:

1. **MainActivity.onCreate** calls `loadCachedProfiles(context)` which initializes `RoomMetadataStore` (SQLite) and runs `RoomListCache.hydrateFromDisk()` (read rows into the singleton).
2. **AuthCheck.LaunchedEffect(Unit)** re-runs the hydrate on `Dispatchers.IO` (idempotent), then calls `populateRoomMapFromCache()` on Main — this copies the singleton into `roomMap` and flips `spacesLoaded = true` (via `SyncRoomsCoordinator.populateRoomMapFromCache`).
3. A separate `LaunchedEffect(appViewModel.spacesLoaded, …)` in AuthCheck observes the flip and calls `navController.navigate("room_list")`. AuthCheck itself renders only a blank `Box` — it's logic-only; the visible loading overlay lives in MainActivity.
4. **MainActivity** wraps `RoomListScreen` in `AnimatedVisibility(visible = cacheReady || (isStartupComplete && showRoomList))` where `cacheReady = spacesLoaded`. The `cacheReady` short-circuit is what allows RoomListScreen to compose before the WebSocket init completes. The `enter` transition is `tween(0)` (instant) for the same reason.
5. AuthCheck's main effect then **`delay(32)`** (one frame) so Compose can paint, then continues with `initializeFCM(... )` on IO and the WebSocket connect. This keeps the heavy startup work from competing with the first paint.
6. The `auth_check ↔ room_list` NavHost transitions are `EnterTransition.None / ExitTransition.None` — there is no shared-element avatar to fly any more, so the fade is dead weight.

**`RoomMetadataStore` v2 schema** adds `sort_ts INTEGER NOT NULL DEFAULT 0`. `RoomListCache.persistMetadata` calls `RoomMetadataStore.upsertSortTs(roomId, ts)` for every `RoomItem` carrying a `sortingTimestamp`; `hydrateFromDisk` restores it. The cached room list therefore sorts by last activity even before the WS lands, and the user's most recently active room naturally floats to the top.

**`clear_state` reset is currently disabled** in `SyncRoomsCoordinator.processSyncCompleteAtomic`. The first `sync_complete` after WS open arrives with `clear_state=true`, which previously called `handleClearStateReset()` and wiped the just-populated `roomMap`. Skipping the reset lets cached rooms survive the WS init; subsequent `sync_complete` messages merge on top. To restore the original behavior, uncomment the `handleClearStateReset()` call (single line). Risk: rooms the user has been removed from since last sync may linger until the next clear_state actually runs.

**`RoomListScreen.stableSection` invariant**: holding the cached list visible during initial sync requires `appViewModel.initialSyncProcessingComplete` (NOT `initialSyncComplete`) — the latter flips earlier and exposes mid-merge state. The stableSection updater guards on `!initialSyncProcessingComplete && hadContent` and adds `initialSyncProcessingComplete` to its `LaunchedEffect` keys so the final apply fires once when processing ends.

### Entry Points

- **`MainActivity`** — Primary entry, hosts Compose NavController, initializes AppViewModel and WebSocket.
- **`ShortcutActivity`** — Handles Android app shortcuts for direct room access.
- **`ChatBubbleActivity`** — Hosts chat bubble windows (Android 11+).
- **`FCMService`** — Handles Firebase push notifications when app is backgrounded.

### Key Screens

`LoginScreen` → `RoomListScreen` (or `SimplerRoomListScreen`) → `RoomTimelineScreen` / `ThreadViewerScreen` / `MentionsScreen` / `RoomMakerScreen`

**Share-to-room flow:** Android share intents land in `MainActivity`, which sets `pendingShare` and `pendingShareNavigationRequested = true` in AppViewModel. `MainActivity`'s `LaunchedEffect(pendingShareNavigationRequested)` navigates to `SimplerRoomListScreen` immediately and calls `markPendingShareNavigationHandled()`. AuthCheck's navigation callback guards on `pendingShare != null` (not `pendingShareNavigationRequested`) to avoid redirecting back to `room_list` after the flag has been cleared. `navigateToRoomListIfNeeded` similarly guards against redirecting while a share is in progress.

**Direct Share fast path (pre-selected room):** When the user taps a Direct Share shortcut in the Android share sheet, `Intent.EXTRA_SHORTCUT_ID` carries the room ID. `processShareIntent` reads it as `shortcutRoomId` and calls `setPendingShare(..., autoSelectRoomId = shortcutRoomId)`, which stores the ID in `pendingShareTargetRoomId` (observable `mutableStateOf`, `private set`). `SimplerRoomListScreen` watches this field: once `showRooms = true` and the target room appears in `allRooms`, it calls `selectPendingShareRoom`, `navigateToRoomWithCache`, and navigates directly to `room_timeline/<id>` — no manual room selection required. `setDirectRoomNavigation` is **not** called for Direct Share, so AuthCheck does not race with this path. The user sees `SimplerRoomListScreen` (with its loading indicator) immediately on cold start instead of a black screen.

Android chat bubbles use `ChatBubbleActivity` → `BubbleTimelineScreen`.

**Secondary VM timeline refresh:** When `SyncEvent.RoomListSingletonReplicated` fires on a non-primary VM, the handler refreshes `timelineEvents`/`eventChainMap` for `currentRoomId` only (via `restoreFromLruCache`). Iterating any larger "rooms ever opened in this VM" set is unsafe: only one room's data is bound to `timelineEvents` at a time, so multiple `restoreFromLruCache` calls would clobber each other (last write wins) and could leave the screen rendering a non-current room's timeline. Other rooms' singleton caches stay fresh on their own via `appendEventsToCachedRoom` in the sync ingestor — they just don't need to touch this VM's `timelineEvents` until the user navigates to them. Bubble VMs only ever host a single room, so the same single-room refresh is correct for them too without needing a role-specific guard.

## Key Libraries

- **UI**: Jetpack Compose, Material 3, Accompanist, Navigation Compose
- **Networking**: OkHttp 4.x (WebSocket + HTTP)
- **Images**: Coil (loading + caching), BlurHash (placeholders)
- **Video**: ExoPlayer / Media3
- **Camera**: CameraX
- **Background**: WorkManager, BroadcastReceiver
- **Notifications**: Firebase Cloud Messaging (FCM)
- **Serialization**: Kotlin Serialization (kotlinx.serialization)

## Project Structure Notes

- `app/src/main/java/net/vrkknn/andromuks/` — all source code
- `app/src/main/java/net/vrkknn/andromuks/utils/` — utility functions (media, HTML, formatting, avatars, encryption, etc.)
- `app/src/main/java/net/vrkknn/andromuks/ui/` — reusable Compose components and theme
- `app/src/main/java/net/vrkknn/andromuks/car/` — Android Automotive OS integration
- `docs/` — architecture and optimization documentation files (useful for understanding past decisions)

## Coordinator Pattern

When adding features, follow the existing Coordinator pattern: create a `*Coordinator.kt` class that holds the logic, give it a reference to AppViewModel or relevant caches, and call into it from AppViewModel. This keeps AppViewModel from growing further.

## Version Management

`versionCode` is computed dynamically in `app/build.gradle.kts` based on seconds since 2024-01-01 epoch, plus a Play Store offset. `versionName` (e.g., `1.0.73`) is set manually. Bump `versionName` in `app/build.gradle.kts` for releases.

---

## Upstream API References

When investigating protocol behaviour, message shapes, or backend fields, consult these before guessing:

| Reference | URL |
|---|---|
| Gomuks WebSocket RPC API (commands, events, data types) | https://docs.mau.fi/gomuks/api/rpc.html |
| mautrix-go / hicli Go package docs (database types, Receipt, etc.) | https://pkg.go.dev/go.mau.fi/gomuks/pkg/hicli |

---

## Feature Documentation

| Topic | Doc |
|---|---|
| Startup & AuthCheck navigation gate | [docs/AUTHCHECK.md](docs/AUTHCHECK.md) |
| WebSocket lifecycle, reconnection, No-VM race, drain-sentinel | [docs/WEBSOCKET_LIFECYCLE.md](docs/WEBSOCKET_LIFECYCLE.md) |
| AppViewModel state fields & functions | [docs/APPVIEWMODEL.md](docs/APPVIEWMODEL.md) |
| `currentRoomState` and `timelineEvents` clearing invariants | [docs/STATE_INVARIANTS.md](docs/STATE_INVARIANTS.md) |
| Timeline event rendering rules, rowid gates, related_events, reply jump navigation | [docs/TIMELINE_EVENTS.md](docs/TIMELINE_EVENTS.md) |
| Timeline paginate routing (`TimelineCacheCoordinator`) | [docs/TIMELINE_PAGINATE.md](docs/TIMELINE_PAGINATE.md) |
| Message sending protocol, local echo, error states | [docs/MESSAGE_SENDING.md](docs/MESSAGE_SENDING.md) |
| Message long-press menu (`MessageMenuBar`) | [docs/MESSAGE_MENU.md](docs/MESSAGE_MENU.md) |
| Power levels parsing and `canPin` | [docs/POWER_LEVELS.md](docs/POWER_LEVELS.md) |
| User profile architecture (two caches, resolution order, "do we have it?" sentinel, rendering fallback, UserInfoScreen bypass) | [docs/USER_PROFILES.md](docs/USER_PROFILES.md) |
| Read receipts invariants and update paths | [docs/RECEIPTS.md](docs/RECEIPTS.md) |
| Push notifications (FCM, two-phase images, image auth token) | [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md) |
| Bridge support (detection, tab, badges, per-message profiles) | [docs/bridges.md](docs/bridges.md) |
| Room display name & avatar resolution (m.heroes) | [docs/ROOM_DISPLAY.md](docs/ROOM_DISPLAY.md) |
| RoomListScreen composable reference | [docs/ROOMLISTSCREEN.md](docs/ROOMLISTSCREEN.md) |
| Sticky date indicator | [docs/STICKY_DATE_INDICATOR.md](docs/STICKY_DATE_INDICATOR.md) |
| HTML rendering (`HtmlMessageText`, tables) | [docs/HTML_RENDERING.md](docs/HTML_RENDERING.md) |
| Offline connection indicator (CloudOff, placement per screen) | [docs/OFFLINE_INDICATOR.md](docs/OFFLINE_INDICATOR.md) |
| Timeline media loading (Coil vs IntelligentMediaCache split, ImageRequest stability, BlurHash) | [docs/MEDIA_LOADING.md](docs/MEDIA_LOADING.md) |
| Settings & preferences (4-scope gomuks prefs, resolution order, reactivity, UI screens) | [docs/SETTINGS_PREFS.md](docs/SETTINGS_PREFS.md) |
| Emoji picker, search, generated data files, JVM 64 KB chunk pattern | [docs/EMOJI.md](docs/EMOJI.md) |
| Reactions lifecycle, storage, redaction path, `removeReaction` internals | [docs/REACTIONS.md](docs/REACTIONS.md) |
| Element Call (WebView/WebRTC, call state, incoming banners, timeline narrator, widget protocol) | [docs/ELEMENT_CALL.md](docs/ELEMENT_CALL.md) |
