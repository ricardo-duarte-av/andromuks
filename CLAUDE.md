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

- **`AppViewModel`** — Master state holder. Processes sync events, manages timeline rendering, coordinates all subsystems. Extremely large (the central hub).
- **`WebSocketService`** — Foreground service keeping the Matrix connection alive. Monitors health via 15s ping / 60s timeout. Handles reconnection with exponential backoff and is network-change-aware.
- **`SyncRepository`** — Sits between the WebSocket and AppViewModel; coordinates event flow.
- **Coordinator classes** (18+) — Each owns a specific feature domain (reactions, read receipts, typing, FCM, uploads, navigation, settings, etc.). They decompose AppViewModel complexity.
- **Cache classes** (12+) — `RoomTimelineCache`, `RoomListCache`, `RoomMemberCache`, `ProfileCache`, `SpaceListCache`, etc. Singleton in-memory stores shared across Activities.

### Entry Points

- **`MainActivity`** — Primary entry, hosts Compose NavController, initializes AppViewModel and WebSocket.
- **`ShortcutActivity`** — Handles Android app shortcuts for direct room access.
- **`ChatBubbleActivity`** — Hosts chat bubble windows (Android 11+).
- **`FCMService`** — Handles Firebase push notifications when app is backgrounded. See **[docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md)**.

### Startup & Navigation Gate

See **[docs/AUTHCHECK.md](docs/AUTHCHECK.md)** for full documentation.

`AuthCheckScreen` (`AuthCheck.kt`) is the first screen shown after launch. It validates credentials, connects the WebSocket, and navigates to the correct destination once startup is complete. It owns the `navigationHandled` guard and registers the navigation callback with `AppViewModel` before initiating the WebSocket connection.

**Critical invariant:** `AppViewModel.setNavigationCallback()` resets `navigationCallbackTriggered = false` on every call. This is intentional — it allows a retained ViewModel to fire navigation again after activity recreation. Do not remove that reset.

### Key Screens

`LoginScreen` → `RoomListScreen` (or `SimplerRoomListScreen`) → `RoomTimelineScreen` / `ThreadViewerScreen` / `MentionsScreen`

Android chat bubbles use `ChatBubbleActivity` → `BubbleTimelineScreen`.

### State Management

State flows via Kotlin `StateFlow`/`MutableStateFlow` and Compose `mutableStateOf`. The Cache singletons serve as the in-process source of truth across Activities (since multiple Activity instances can coexist). SharedPreferences is used for persisted cross-process/restart state.

Sync events are queued until `init_complete` arrives, then processed in batch. Each coordinator handles its domain independently.

### WebSocket Connection Lifecycle

See **[docs/WEBSOCKET_LIFECYCLE.md](docs/WEBSOCKET_LIFECYCLE.md)** for full documentation.

The foreground service (`WebSocketService`) maintains the socket. On disconnect, it reconnects with backoff. `NetworkMonitor` detects WiFi/mobile switches and triggers reconnection. Health is maintained via OkHttp ping/pong.

**Critical startup invariant:** After a `START_STICKY` restart, `NetworkMonitor` pre-seeds its own internal state in `start()` via `updateCurrentNetworkState()`. This means the initial Android `onAvailable()` callback fires with `previousType = WIFI`, not NONE, so `onNetworkAvailable` is never called and `WebSocketService.currentNetworkType` stays `NONE`. The fix: `startNetworkMonitoring()` explicitly seeds `currentNetworkType` from `networkMonitor.getCurrentNetworkType()` after `start()`. The stuck-DISCONNECTED health check also handles the cold-start case (`connectionLostAt == 0`) by using `serviceStartTime` as a fallback.

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
- `docs/` — 70+ architecture and optimization documentation files (useful for understanding past decisions)

## Coordinator Pattern

When adding features, follow the existing Coordinator pattern: create a `*Coordinator.kt` class that holds the logic, give it a reference to AppViewModel or relevant caches, and call into it from AppViewModel. This keeps AppViewModel from growing further.

## Power Levels

Power levels are parsed from `m.room.power_levels` state events into `PowerLevelsInfo` (`RoomItem.kt`) and stored on `RoomState.powerLevels`. Parsing happens in `AppViewModel.parseRoomStateFromEvents`.

**Key Matrix spec rules to preserve:**

- `users_default` — default PL for users not listed in `users`. Missing key means 0. Parsed with `optInt("users_default", 0)`.
- `events_default` — default PL for non-state event types not in `events`. Missing key means 0.
- `state_default` — default PL for **state** event types not in `events`. Missing key means **50** (not 0). Stored in `PowerLevelsInfo.stateDefault`.
- `m.room.pinned_events` is a **state event** — its required PL falls back to `stateDefault`, not `eventsDefault`.
- There is **no cap on the number of pinned events** in the Matrix spec. The only real limit is the 64 KB event size. Do not add an artificial count limit.
- Permission to pin and unpin is purely `myPowerLevel >= pinnedEventsPowerLevel`. Pin and unpin require the same PL.

**Where `canPin` is computed:** `ReplyFunctions.kt` and `NarratorFunctions.kt` (shared by all timeline screens). The `pinnedEventsPowerLevel` fallback chain is: `events["m.room.pinned_events"] ?: stateDefault ?: 50`.

**Known gap:** live `m.room.power_levels` timeline events are not yet propagated to update `currentRoomState.powerLevels`. Power levels are only set on initial room state load via `parseRoomStateFromEvents`.

## Timeline Event Rendering

See **[docs/TIMELINE_EVENTS.md](docs/TIMELINE_EVENTS.md)** for full documentation.

**Critical rule:** only events with `timelineRowid > 0` are rendered. `timelineRowid = 0` means "not a timeline event" — the backend sends events with `timeline_rowid: 0` as profile hints alongside messages (e.g. `m.room.member` for sender lookup). These must update caches only and never be added to `eventChainMap`. `resolveTimelineRowidsFromRoomData` resolves the real `timeline_rowid` from the room's `timeline` mapping before events are processed; events not present in that mapping stay at `0`.

**Exception:** `updateMemberProfilesFromEvents` intentionally uses `>= 0L` — profile hints should update the member cache. Do not change that filter.

## Message Sending

See **[docs/MESSAGE_SENDING.md](docs/MESSAGE_SENDING.md)** for full documentation.

Covers the 4-stage protocol flow (`send_message` → `response` → `send_complete` → `sync_complete`), local echo (pending placeholder bubbles with `~`-prefixed `event_id`), visual states (pending/failed/confirmed), sort position fix for `timelineRowid=0`, and user actions on pending/failed echoes.

**Critical invariant:** `send_error: "not sent"` inside an event is a backend placeholder — it is **not** a send failure. Real failures come from the outer `error` field in `send_complete`.

## Push Notifications (FCM)

See **[docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md)** for full documentation.

`FCMService` receives encrypted FCM payloads, decrypts them with the stored push key, and delegates to `EnhancedNotificationDisplay` to post `MessagingStyle` notifications.

**Critical invariant:** Never block notification posting on a network download during Doze mode. `EnhancedNotificationDisplay` checks `PowerManager.isDeviceIdleMode` before any avatar download — if true, it skips the download and uses a fallback avatar immediately. Removing this guard causes notifications to batch and fire all at once when the screen turns on.

FCM `high_priority` is set by the gomuks backend based on whether the push rule has `sound: true`. If a room's notifications only arrive on screen wake, verify the Matrix push rule for that room includes sound.

## Bridge Support (Mautrix / Beeper)

See **[docs/bridges.md](docs/bridges.md)** for full documentation.

The app has first-class support for Matrix bridges (e.g. Mautrix bridges for WhatsApp, Telegram, etc.). Key areas covered in that doc:
- Bridge detection (`BridgeInfo`, `BridgeInfoCache`, `parseBridgeInfoEvent`)
- Bridges tab in `RoomListScreen` (`RoomSectionType.BRIDGES`)
- Bridge icon / background in timeline top bar (`BridgeNetworkBadge`, `BridgeBackgroundLayer`)
- Per-message bridge profiles (`com.beeper.per_message_profile`)
- Bridge send status (`com.beeper.message_send_status`) — delivery icons, Delivery Info dialog, functional members (`io.element.functional_members` / MSC4171)

## Offline Connection Indicator

A pulsing `CloudOff` icon in `MaterialTheme.colorScheme.error` is shown in every screen's header when the WebSocket is not in `ConnectionState.Ready`. It is driven by `SyncRepository.connectionState.collectAsState()` and uses an `infiniteRepeatable` alpha animation (0.4 → 1.0, 800 ms, `RepeatMode.Reverse`) inside an `AnimatedVisibility`.

**Placement per screen:**
- `RoomListScreen` — between the user Column and the Mentions icon button in the top bar Row
- `RoomTimelineScreen` (`RoomHeader`) — to the left of the video call (`Videocam`) icon button
- `BubbleTimelineScreen` (`BubbleRoomHeader`) — first item in the trailing icons Row, before the "Open in app" button
- `ThreadViewerScreen` — trailing item in the header Row, after the thread title Column

The connection state is sourced directly from `SyncRepository.connectionState` (a `StateFlow<ConnectionState>`). `isReady()` is the extension function in `ConnectionState.kt` that returns `true` only for `ConnectionState.Ready`.

## Version Management

`versionCode` is computed dynamically in `app/build.gradle.kts` based on seconds since 2024-01-01 epoch, plus a Play Store offset. `versionName` (e.g., `1.0.73`) is set manually. Bump `versionName` in `app/build.gradle.kts` for releases.
