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
OkHttp WebSocket (Matrix protocol) + SQLite (BootstrapLoader)
```

### Core Classes

- **`AppViewModel`** — Master state holder. Processes sync events, manages timeline rendering, coordinates all subsystems. Extremely large (the central hub). See **[docs/APPVIEWMODEL.md](docs/APPVIEWMODEL.md)** for a full reference of every state field and function.
- **`WebSocketService`** — Foreground service keeping the Matrix connection alive. Monitors health via 15s ping / 60s timeout. Handles reconnection with exponential backoff and is network-change-aware.
- **`SyncRepository`** — Sits between the WebSocket and AppViewModel; coordinates event flow. The `_events` SharedFlow has `extraBufferCapacity = 1024` and `BufferOverflow.DROP_LATEST` — burst traffic sheds the newest arrivals. `sync_complete` messages bypass `_events` entirely and go to a separate `syncCompleteChannel` (UNLIMITED).
- **Coordinator classes** (18+) — Each owns a specific feature domain (reactions, read receipts, typing, FCM, uploads, navigation, settings, etc.). They decompose AppViewModel complexity.
- **Cache classes** (12+) — `RoomTimelineCache`, `RoomListCache`, `RoomMemberCache`, `ProfileCache`, `SpaceListCache`, `RoomAccountDataCache`, etc. Singleton in-memory stores shared across Activities. `IntelligentMediaCache` is used by `ConversationsApi` (shortcut icons) and `PersonsApi`/`EnhancedNotificationDisplay` (notification avatars) — **not** for timeline media or UI avatar display, both of which delegate entirely to Coil's built-in disk/memory cache. `RoomAccountDataCache` holds per-room account data (e.g. `fi.mau.gomuks.preferences`) populated from sync.

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

**Bubble VM timeline isolation invariant:** When `SyncEvent.RoomListSingletonReplicated` fires, secondary VM instances refresh their timeline by calling `restoreFromLruCache(roomId)` for every room in `RoomTimelineCache.getOpenedRooms()`. That set is a **singleton** shared across all Activities. A bubble VM must **not** iterate it — doing so causes `restoreFromLruCache` to overwrite `timelineEvents` with whatever room the main app last opened. Bubble VMs (`instanceRole == BUBBLE`) only refresh `currentRoomId`. See `AppViewModel.kt` `RoomListSingletonReplicated` handler.

## Key Libraries

- **UI**: Jetpack Compose, Material 3, Accompanist, Navigation Compose
- **Networking**: OkHttp 4.x (WebSocket + HTTP)
- **Images**: Coil (loading + caching), BlurHash (placeholders)
- **Video**: ExoPlayer / Media3
- **Camera**: CameraX
- **Background**: WorkManager, BroadcastReceiver
- **Notifications**: Firebase Cloud Messaging (FCM)
- **Serialization**: Kotlin Serialization (kotlinx.serialization)
- **Database**: SQLite via Android framework (BootstrapLoader pattern)

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
