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

## Bridge Support (Mautrix / Beeper)

The app has first-class support for Matrix bridges (e.g. Mautrix bridges for WhatsApp, Telegram, etc.).

### Bridge Detection

Bridge info is parsed from `m.bridge` / `uk.half-shot.bridge` state events inside `AppViewModel.parseBridgeInfoEvent()` and `parseRoomStateFromEvents()`. The result is a `BridgeInfo` object stored on `RoomState.bridgeInfo`. Key fields:

- `BridgeInfo.protocol` — network protocol info (id, displayName, avatarUrl, externalUrl)
- `BridgeInfo.channel` — individual channel within the protocol
- `BridgeInfo.roomType` / `roomTypeV2` — `"dm"` means it's a DM-style bridge room (`com.beeper.room_type` / `.v2` in the event content)
- `BridgeInfo.hasRenderableIcon` — true if the bridge has either an avatarUrl or displayName to render

Bridge info (avatar URL + display name) is also persisted to `SharedPreferences` via `BridgeInfoCache` (keyed by `roomId`), so it survives app restarts without re-fetching room state.

`RoomItem.bridgeProtocolAvatarUrl` stores the mxc:// URL of the bridge protocol avatar for the room (null for non-bridged rooms). This is the field used to identify bridged rooms throughout the UI.

### Bridges Tab in RoomListScreen

In `RoomListScreen`, the **Bridges** tab (`RoomSectionType.BRIDGES`) groups bridged rooms by their `bridgeProtocolAvatarUrl`. Each unique avatar URL becomes a pseudo-space (a `SpaceItem`) representing one bridge network. Tapping a bridge network (`currentBridgeId`) filters the room list to show only rooms on that network. `AppViewModel.exitBridge()` clears `currentBridgeId`. The tab animates between the bridge network list and the filtered room list using `AnimatedVisibility`. Room items in this tab show a badge with the bridge protocol icon overlaid on the room avatar (rendered in `BridgeDecorations.kt` via `BridgeNetworkBadge`).

### Bridge Icon in Timeline Top Bar

In `RoomTimelineScreen` and `BubbleTimelineScreen`, the top bar normally shows a **Refresh** icon button. When the current room is bridged (`roomState?.bridgeInfo?.hasRenderableIcon == true`), the refresh icon is replaced by a `BridgeNetworkBadge` (from `ui/components/BridgeDecorations.kt`). The badge shows the bridge protocol avatar and still triggers `onRefreshClick` when tapped. `BridgeNetworkBadge` also has an optional non-clickable variant (no `onClick`). Additionally, `BridgeBackgroundLayer` renders a blurred, low-opacity version of the bridge avatar as a subtle background in the timeline.

### Per-Message Bridge Profiles

Mautrix bridges can attach `com.beeper.per_message_profile` to individual message events, overriding the sender's display name and avatar for that specific message (used for ghost users representing external network contacts). Handled in `TimelineEventItem`, `RoomTimelineScreen`, `BubbleTimelineScreen`, `ThreadViewerScreen`, and `ChatBubbleScreen`.

### Bridge Send Status (`com.beeper.message_send_status`)

Some bridges (when configured) send `com.beeper.message_send_status` events to confirm that a message sent from Matrix has reached the other network. These are **not** displayed in the timeline — they update the sender's message bubble with a delivery status icon.

**Event structure:**
```json
{
  "type": "com.beeper.message_send_status",
  "content": {
    "m.relates_to": { "rel_type": "m.reference", "event_id": "$original-event-id" },
    "status": "SUCCESS",
    "delivered_to_users": ["@bridgeuser:homeserver"]  // only present on delivery confirmation
  }
}
```

**Two status levels:**
- **`"sent"`** — bridge confirmed the message reached the other network (`status == "SUCCESS"`, no `delivered_to_users`). Shown as a single `Check` icon.
- **`"delivered"`** — other network confirmed delivery to at least one recipient device (`status == "SUCCESS"` + non-empty `delivered_to_users`). Shown as a `DoneAll` (double-check) icon.

**Status is never downgraded** — once "delivered", a second "sent" event is ignored.

**Implementation:**
- `AppViewModel.messageBridgeSendStatus: Map<String, String>` — eventId → status, observed by Compose.
- `AppViewModel.bridgeSendStatusCounter` — incremented on every update to trigger recomposition.
- `AppViewModel.processBridgeSendStatus(relatedEventId, hasDeliveredToUsers)` — called from both live sync and paginated history.
- **Live sync:** handled in `AppViewModel.processSyncEventsArray()` — the `com.beeper.message_send_status` branch.
- **Paginated history:** handled in `TimelineCacheCoordinator.processEventsArray()` — after the `m.reaction` branch, before `allowedEventTypes` filtering.
- **UI:** `TimelineEventItem` reads `appViewModel.messageBridgeSendStatus[event.eventId]` (keyed on `bridgeSendStatusCounter`) and renders a small icon below the timestamp in the avatar column for own messages. Applies to both non-consecutive (with avatar) and consecutive messages.
- **State cleared** on full reset and on per-room cache clear alongside `messageReactions`.
- These events are **not** stored in the timeline or added to `allowedEventTypes` — they are side-effect-only.

## Version Management

`versionCode` is computed dynamically in `app/build.gradle.kts` based on seconds since 2024-01-01 epoch, plus a Play Store offset. `versionName` (e.g., `1.0.73`) is set manually. Bump `versionName` in `app/build.gradle.kts` for releases.
