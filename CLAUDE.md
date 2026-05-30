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

### Logging in release builds

`app/proguard-rules.pro` declares `android.util.Log.d`, `Log.v`, and `Log.isLoggable` as having no side effects, so R8 strips them (and their argument-construction expressions) from release APKs. `Log.i`, `Log.w`, and `Log.e` survive — use them when a message should be visible in a user-supplied logcat dump (info), or signals an actual problem (warn/error). `Log.d` is for chatty diagnostics that are debug-only.

This means `if (BuildConfig.DEBUG) Log.d(...)` gates are redundant in release (R8 strips them either way), but they're still valuable in *debug* to avoid the per-call string-formatting cost — see the "Logging in release builds" note above and the empty-timeline race postmortem in [docs/STATE_INVARIANTS.md](docs/STATE_INVARIANTS.md#dont-mutate-currentroomid-from-background-init-paths) for why that cost matters.

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
- **`SyncBatchProcessor`** — Batches `sync_complete` messages while backgrounded and flushes them on foreground resume (always-on mode) or bypasses batching entirely (battery-saver mode). See **[docs/SYNC_BATCH.md](docs/SYNC_BATCH.md)** for the 500 ms flush, `timelineRefreshTrigger` double-increment, and the single-`LaunchedEffect` invariant in `RoomTimelineScreen`.
- **Coordinator classes** (18+) — Each owns a specific feature domain (reactions, read receipts, typing, FCM, uploads, navigation, settings, etc.). They decompose AppViewModel complexity.
- **Cache classes** (12+) — `RoomTimelineCache`, `RoomListCache`, `RoomMemberCache`, `ProfileCache`, `SpaceListCache`, `RoomAccountDataCache`, etc. Singleton in-memory stores shared across Activities. `IntelligentMediaCache` is used by `ConversationsApi` (shortcut icons) and `PersonsApi`/`EnhancedNotificationDisplay` (notification avatars) — **not** for timeline media or UI avatar display, both of which delegate entirely to Coil's built-in disk/memory cache. `RoomAccountDataCache` holds per-room account data (e.g. `fi.mau.gomuks.preferences`) populated from sync.

### Cold-start cache-first rendering

The app paints `RoomListScreen` from the persisted room cache before the WebSocket connects. See **[docs/AUTHCHECK.md](docs/AUTHCHECK.md#cold-start-cache-first-rendering)** for the full flow (hydrate → `populateRoomMapFromCache` → `spacesLoaded` → paint), the `RoomMetadataStore` v2 schema, the disabled-`clear_state`-reset note, and **[docs/ROOMLISTSCREEN.md](docs/ROOMLISTSCREEN.md#stablesection-invariant--holding-the-cached-list-during-initial-sync)** for the `stableSection` invariant that depends on `initialSyncProcessingComplete` (not `initialSyncComplete`).

### Entry Points

- **`MainActivity`** — Primary entry, hosts Compose NavController, initializes AppViewModel and WebSocket.
- **`ShortcutActivity`** — Handles Android app shortcuts for direct room access.
- **`ChatBubbleActivity`** — Hosts chat bubble windows (Android 11+).
- **`FCMService`** — Handles Firebase push notifications when app is backgrounded.

### Key Screens

`LoginScreen` → `RoomListScreen` (or `SimplerRoomListScreen`) → `RoomTimelineScreen` / `ThreadViewerScreen` / `MentionsScreen` / `RoomMakerScreen`

Android share intents (`ACTION_SEND` / Direct Share) route through `SimplerRoomListScreen`. See **[docs/SHARE_FLOW.md](docs/SHARE_FLOW.md)** for the generic picker flow and the `EXTRA_SHORTCUT_ID` fast path that skips it.

Android chat bubbles use `ChatBubbleActivity` → `BubbleTimelineScreen`.

Secondary VMs (`ShortcutActivity`, `ChatBubbleActivity`) refresh `timelineEvents` for `currentRoomId` only on `RoomListSingletonReplicated` — see **[docs/WEBSOCKET_LIFECYCLE.md](docs/WEBSOCKET_LIFECYCLE.md#secondary-vm-timeline-refresh)**.

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
| Startup, AuthCheck navigation gate, cold-start cache-first rendering | [docs/AUTHCHECK.md](docs/AUTHCHECK.md) |
| WebSocket lifecycle, reconnection, No-VM race, drain-sentinel, shared `request_id` allocator, secondary-VM timeline refresh | [docs/WEBSOCKET_LIFECYCLE.md](docs/WEBSOCKET_LIFECYCLE.md) |
| `SyncBatchProcessor` (always-on batching, battery-saver bypass, `timelineRefreshTrigger`) | [docs/SYNC_BATCH.md](docs/SYNC_BATCH.md) |
| Android share intents (`ACTION_SEND`, Direct Share fast path) | [docs/SHARE_FLOW.md](docs/SHARE_FLOW.md) |
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
