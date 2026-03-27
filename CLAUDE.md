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
- **`FCMService`** — Handles Firebase push notifications when app is backgrounded.

### Key Screens

`LoginScreen` → `RoomListScreen` (or `SimplerRoomListScreen`) → `RoomTimelineScreen` / `BubbleTimelineScreen` / `ThreadViewerScreen` / `MentionsScreen`

### State Management

State flows via Kotlin `StateFlow`/`MutableStateFlow` and Compose `mutableStateOf`. The Cache singletons serve as the in-process source of truth across Activities (since multiple Activity instances can coexist). SharedPreferences is used for persisted cross-process/restart state.

Sync events are queued until `init_complete` arrives, then processed in batch. Each coordinator handles its domain independently.

### WebSocket Connection Lifecycle

The foreground service (`WebSocketService`) maintains the socket. On disconnect, it reconnects with backoff. `NetworkMonitor` detects WiFi/mobile switches and triggers reconnection. Health is maintained via OkHttp ping/pong.

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

## Version Management

`versionCode` is computed dynamically in `app/build.gradle.kts` based on seconds since 2024-01-01 epoch, plus a Play Store offset. `versionName` (e.g., `1.0.73`) is set manually. Bump `versionName` in `app/build.gradle.kts` for releases.
