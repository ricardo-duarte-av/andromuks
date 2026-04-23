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

**`attachToExistingWebSocketIfAvailable` invariant:** When `AuthCheck` takes the "already connected" path (service running, `isAlreadyConnected = true`), it calls `attachToExistingWebSocketIfAvailable()`, which populates `roomMap` from `RoomListCache` and fires navigation immediately when `spacesLoaded = true`. This new AppViewModel instance will never receive `init_complete` (the service already processed it), so `initialSyncComplete` must be explicitly set to `true` here alongside `spacesLoaded`. Without it, `RoomListScreen`'s room-update guard (`!initialSyncComplete && hadContent → return`) permanently blocks all room list updates, leaving the UI stuck with whatever partial snapshot was in `RoomListCache` at attachment time (e.g. 3–4 rooms during a mid-sync attach).

### Key Screens

`LoginScreen` → `RoomListScreen` (or `SimplerRoomListScreen`) → `RoomTimelineScreen` / `ThreadViewerScreen` / `MentionsScreen` / `RoomMakerScreen`

Android chat bubbles use `ChatBubbleActivity` → `BubbleTimelineScreen`.

**Bubble VM timeline isolation invariant:** When `SyncEvent.RoomListSingletonReplicated` fires, secondary VM instances refresh their timeline by calling `restoreFromLruCache(roomId)` for every room in `RoomTimelineCache.getOpenedRooms()`. That set is a **singleton** shared across all Activities. A bubble VM must **not** iterate it — doing so causes `restoreFromLruCache` to overwrite `timelineEvents` with whatever room the main app last opened (the last call wins), making the bubble render the wrong room's messages. Bubble VMs (`instanceRole == BUBBLE`) only refresh `currentRoomId`. See `AppViewModel.kt` `RoomListSingletonReplicated` handler.

### State Management

State flows via Kotlin `StateFlow`/`MutableStateFlow` and Compose `mutableStateOf`. The Cache singletons serve as the in-process source of truth across Activities (since multiple Activity instances can coexist). SharedPreferences is used for persisted cross-process/restart state.

Sync events are queued until `init_complete` arrives, then processed in batch. Each coordinator handles its domain independently.

**`currentRoomState` clearing invariant:** `AppViewModel.currentRoomState` is a global `mutableStateOf` read directly by `RoomTimelineScreen` to populate the room header. It must be cleared to `null` whenever `currentRoomId` changes to a *different* room. This is done in `updateCurrentRoomIdInPrefs` (the single choke-point for `currentRoomId` changes). Without this, direct room-to-room navigation (A → B without going back through the list) leaves `currentRoomState` holding room A's data while room B's screen is composing, causing the wrong room name/avatar to appear in the header. The `DisposableEffect` guard in `RoomTimelineScreen` (`if currentRoomId == roomId`) intentionally skips `clearCurrentRoomId()` in this case (because `currentRoomId` is already room B), so the clearing must happen at the `updateCurrentRoomIdInPrefs` site instead.

**`timelineEvents` clearing invariant:** `AppViewModel.timelineEvents` is also a global `mutableStateOf` and suffers the same stale-data problem as `currentRoomState`. It is cleared to `emptyList()` in `updateCurrentRoomIdInPrefs` alongside `currentRoomState` whenever the room changes. Without this, the `isWarmTimelineReturn` guard in `RoomTimelineScreen.LaunchedEffect(roomId)` evaluates `currentRoomId==roomId && timelineEvents.isNotEmpty()` as **true** for the wrong reason: `currentRoomId` has been updated synchronously to room B, but `timelineEvents` still holds room A's events because the `navigateToRoomWithCache` coroutine hasn't run yet. The screen then skips `awaitRoomDataReadiness` and renders room A's timeline under room B's header. Clearing is skipped for `BUBBLE` instances which restore their own timeline state. The paired guard is in `RoomTimelineScreen.LaunchedEffect(roomId)`: `isAlreadyLoaded` uses `timelineEvents.isNotEmpty() || isTimelineLoading` so a second `navigateToRoomWithCache` call is not fired when events are empty but a load is already in flight.

### WebSocket Connection Lifecycle

See **[docs/WEBSOCKET_LIFECYCLE.md](docs/WEBSOCKET_LIFECYCLE.md)** for full documentation.

The foreground service (`WebSocketService`) maintains the socket. On disconnect, it reconnects with backoff. `NetworkMonitor` detects WiFi/mobile switches and triggers reconnection. Health is maintained via OkHttp ping/pong.

**Critical startup invariant:** After a `START_STICKY` restart, `NetworkMonitor` pre-seeds its own internal state in `start()` via `updateCurrentNetworkState()`. This means the initial Android `onAvailable()` callback fires with `previousType = WIFI`, not NONE, so `onNetworkAvailable` is never called and `WebSocketService.currentNetworkType` stays `NONE`. The fix: `startNetworkMonitoring()` explicitly seeds `currentNetworkType` from `networkMonitor.getCurrentNetworkType()` after `start()`. The stuck-DISCONNECTED health check also handles the cold-start case (`connectionLostAt == 0`) by using `serviceStartTime` as a fallback.

**No-VM race invariant:** The service can connect and receive the initial `init_complete` + `sync_complete` batch before any `AppViewModel` is attached (e.g. auto-start via boot / `START_STICKY` / health-check recovery). `SyncRepository.processSyncCompletePipeline()` buffers up to 500 such messages in `noVmBuffer` instead of dropping them. The buffer is epoch-tracked: `WebSocketService.updateConnectionState()` calls `SyncRepository.clearSyncBuffer()` on every `Disconnected` transition so stale messages are never replayed into a new session. When a VM attaches, `AppViewModel.attachToExistingWebSocketIfAvailable()` calls `SyncRepository.takeBufferedMessages()` to retrieve the buffered messages and merges them with any messages in `initialSyncCompleteQueue`. All messages are then processed synchronously inside a `Dispatchers.Default` coroutine; navigation fires via `populateFromCacheAndNavigateAfterAttach()` only after processing completes (on the Main thread). **Critical:** do NOT use `triggerBufferedSyncDrain()` here — it re-enqueues to the async channel, which means navigation fires before rooms are populated, causing them to pop in one-by-one. Without this fix: the room list is empty on app open after automatic background startup, and rooms appear one-by-one as subsequent live `sync_complete` cycles arrive.

**`initialSyncPhase` / drain-sentinel attach-gate invariant:** `initialSyncPhase` must stay `false` until navigation is about to fire. The SyncRepository pipeline (running concurrently on `Dispatchers.IO`) may still have messages in `syncCompleteChannel` that were not yet moved to `noVmBuffer` at the moment `takeBufferedMessages()` is called — so `noVmBuffer` can appear empty even though messages are in transit. With `initialSyncPhase = false`, any pipeline-dispatched messages land in `initialSyncCompleteQueue` instead of being applied to the UI directly. To know exactly when the pipeline has finished dispatching all in-transit messages, `attachToExistingWebSocketIfAvailable` enqueues a `DRAIN_SENTINEL` into `syncCompleteChannel` immediately after registering the VM. Because the channel is FIFO and the pipeline is single-threaded, when the sentinel is processed all prior messages have been dispatched and are sitting in `initialSyncCompleteQueue`. The sentinel callback (on IO thread) dispatches to Main, takes a snapshot of the queue, batch-processes it on `Dispatchers.Default`, sets `initialSyncPhase = true`, and then calls `populateFromCacheAndNavigateAfterAttach()`. This guarantees the room list is fully populated before the user sees `RoomListScreen`.

**Critical `setWebSocket` ordering invariant:** In `setWebSocket` (the OkHttp `onOpen` handler), `serviceInstance.webSocket` must be assigned **before** calling `updateConnectionState(ConnectionState.Ready)`. The monitoring coroutine periodically calls `detectAndRecoverStateCorruption()` on a different thread. If it runs between the state update and the webSocket assignment, it sees `Ready + webSocket==null`, wrongly resets state to `Disconnected`, and the notification gets permanently stuck at "Connecting..." even though the socket is alive and delivering messages. The safe order: set `webSocket`, `connectionStartTime`, `lastMessageReceivedTimestamp`, `lastPongTimestamp` first — then call `updateConnectionState(Ready)`.

**Persistent notification desync:** The notification text in release builds is driven purely by `connectionState` (not by the `isConnected` parameter passed to `updateConnectionStatus`). It uses a dedup key `"$currentState-$callbackMissing"` and skips updates when the key hasn't changed. If state gets stuck at `Disconnected` (e.g., via the corruption detector race above), every subsequent `updateConnectionStatus` call returns early and the notification never recovers until the next actual state transition.

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

**`timelineRowid = -1`:** used as either "unresolved sentinel" (event arrived before its mapping was available) or "reply-context event" (from the `related_events` key in paginate/sync responses). Both cases must not be rendered as standalone timeline items. Sort comparators guard against `-1` poisoning the sort by using `> 0L` (not `!= 0L`) — events with `<= 0` rowids fall back to timestamp ordering. When a cached event with `<= 0` rowid is seen again from a paginate with a positive rowid, the cache updates the stored copy so sorting resolves correctly.

**`related_events`:** paginate and `sync_complete` responses include a `related_events` array — reply target events the backend fetched so the client can render reply previews. These must **not** appear in the timeline. They are stored in `RoomCache.replyContextEvents` (a separate bucket, never returned by `getCachedEventsForTimeline`) via `RoomTimelineCache.addReplyContextEvents()`. Reply preview lookup uses `RoomTimelineCache.findEventForReply()`, which searches both `cache.events` and `cache.replyContextEvents`.

**Exception:** `updateMemberProfilesFromEvents` intentionally uses `>= 0L` — profile hints should update the member cache. Do not change that filter.

## Sticky Date Indicator (`utils/StickyDateIndicator.kt`)

A pill-shaped overlay that appears at the top of each timeline screen (below the header) showing the date of the oldest visible event. Used in `RoomTimelineScreen`, `BubbleTimelineScreen`, `ThreadViewerScreen`, and `EventContextScreen`.

**Behaviour:**
- Slides in (down) + fades in when the user scrolls toward older content and the oldest visible event is not from today.
- Resets the 3-second auto-hide timer on every item-boundary crossing while scrolling toward older content.
- Hides immediately (slide up + fade out) when scrolling toward newer content.
- Auto-hides after 3 seconds of no scroll activity.

**Key parameters:**
- `oldestVisibleDate: String?` — `"dd / MM / yyyy"` formatted date of the oldest on-screen event.
- `scrollPositionKey: Int` — changes on every item-boundary crossing (driven by `listState.firstVisibleItemIndex`).
- `reverseScrollLayout: Boolean` — `true` for `reverseLayout=true` LazyColumns (`RoomTimeline`, `BubbleTimeline`) where increasing index = older content; `false` for top-down layouts (`ThreadViewer`, `EventContext`).

**Clipping invariant:** Each host screen wraps its LazyColumn in a `Box` with `.clipToBounds()`. Without this, the slide-in animation renders the pill *above* the header instead of emerging from behind it.

**Layout differences by screen:**

| Screen | `reverseLayout` | "oldest" item index | `reverseScrollLayout` |
|--------|----------------|--------------------|-----------------------|
| `RoomTimelineScreen` | `true` (items reversed) | `maxOfOrNull { it.index }` from `visibleItemsInfo` | `true` |
| `BubbleTimelineScreen` | `true` (items reversed) | `maxOfOrNull { it.index }` from `visibleItemsInfo` | `true` |
| `ThreadViewerScreen` | `false` | `firstVisibleItemIndex` | `false` |
| `EventContextScreen` | `false` | `firstVisibleItemIndex` | `false` |

`formatDate()` (defined in `RoomTimelineScreen.kt`, `internal` visibility) is used for timestamp → `"dd / MM / yyyy"` conversion in Room, Thread, and EventContext screens. `BubbleTimelineScreen` uses `java.text.SimpleDateFormat` inline (different file, same package).

## HTML Rendering (`utils/html.kt`, `utils/HtmlTableRenderer.kt`)

`HtmlMessageText` is the main composable for rendering Matrix `formatted_body` HTML. It parses HTML into a tree of `HtmlNode` via `HtmlParser`, builds an `AnnotatedString` from non-table nodes, and renders it in a `Text()` with custom gesture handling for links, spoilers, and code blocks.

**Table rendering:** `<table>` nodes are extracted from the parsed tree before the `AnnotatedString` is built (`tableNodes` / `nonTableNodes` split). Each table is rendered as a tappable `HtmlTablePreviewCard` (shows row/column count + column header preview). Tapping opens `HtmlTableDialog` — a full-screen dialog with `HtmlTableContent`: a `LazyColumn` (vertical scroll) wrapped in `horizontalScroll`, with auto-computed column widths (clamped 80–220dp), alternating row colors, and column dividers. Parsing logic lives in `parseTableNode()` in `HtmlTableRenderer.kt`.

**Known limitation:** if a message interleaves text and tables (text → table → more text), the non-table text nodes are all rendered together above the table cards. The relative ordering of text-after-table is lost. This is acceptable for typical Matrix messages where tables are at the end or occupy the whole message body.

## Message Sending

See **[docs/MESSAGE_SENDING.md](docs/MESSAGE_SENDING.md)** for full documentation.

Covers the 4-stage protocol flow (`send_message` → `response` → `send_complete` → `sync_complete`), local echo (pending placeholder bubbles with `~`-prefixed `event_id`), visual states (pending/failed/confirmed), sort position fix for `timelineRowid=0`, and user actions on pending/failed echoes.

**Critical invariant:** `send_error: "not sent"` inside an event is a backend placeholder — it is **not** a send failure. Real failures come from the outer `error` field in `send_complete`.

## Push Notifications (FCM)

See **[docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md)** for full documentation.

`FCMService` receives encrypted FCM payloads, decrypts them with the stored push key, and delegates to `EnhancedNotificationDisplay` to post `MessagingStyle` notifications.

**Critical invariant — avatar downloads:** Never block notification posting on a network download during Doze mode. `EnhancedNotificationDisplay` checks `PowerManager.isDeviceIdleMode` before any avatar download — if true, it skips the download and uses a fallback avatar immediately. Removing this guard causes notifications to batch and fire all at once when the screen turns on.

**Critical invariant — image messages:** Image notifications use a **two-phase approach** to avoid the same Doze restriction. Phase 1 (FCM callback): post the notification immediately with the text body only — no image download. Phase 2 (`NotificationImageWorker`): a `CoroutineWorker` constrained to `NetworkType.CONNECTED` downloads the image via `IntelligentMediaCache`, extracts the existing `MessagingStyle` from the active notification, and re-posts with the same notification ID + `setSilent(true)`. WorkManager's `JobScheduler` backing grants network access outside Doze. Do **not** move the image download back into the FCM callback — it will silently fail during Doze and fall back to text-only. See **[docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md)** for the full two-phase protocol.

FCM `high_priority` is set by the gomuks backend based on whether the push rule has `sound: true`. If a room's notifications only arrive on screen wake, verify the Matrix push rule for that room includes sound.

**Image auth token invariant:** The gomuks backend sends an `image_auth_token` (JWT, `image_only: true`) via WebSocket after connecting. This JWT is required for downloading `?encrypted=true` media — `gomuks_auth_token` alone is rejected for encrypted content. `AppViewModel.updateImageAuthToken` persists it to `AndromuksAppPrefs / image_auth_token` on every receipt. `FCMService` reads this key at startup and passes it to `EnhancedNotificationDisplay`, falling back to `gomuks_auth_token` only on first launch. Without the JWT, image notifications render as text-only ("Sent an image") in release builds.

## Timeline Paginate Routing (`TimelineCacheCoordinator`)

`TimelineCacheCoordinator` maintains three maps for in-flight paginate requests:

- **`timelineRequests`** — initial room-open paginates (true fresh loads, no cached events). Responses call `buildEditChainsFromEvents(timelineList, clearExisting=true)` via `handleTimelineResponse`, which **clears `eventChainMap`** and rebuilds from only the paginate response list.
- **`backgroundPrefetchRequests`** — catch-up / background paginates. Responses call `handleBackgroundPrefetch`, which merges the response into `RoomTimelineCache` and then calls `processCachedEvents(getCachedEventsForTimeline(roomId))` — rebuilding from the **full merged cache**.
- **`paginateRequests`** — user-triggered pull-to-paginate (older history). Responses call `buildEditChainsFromEvents(timelineList, clearExisting=false)` — appends to existing `eventChainMap`.

**Critical invariant:** The two "catch-up" paginates sent at room-open time in `requestRoomTimeline` must use `backgroundPrefetchRequests`, never `timelineRequests`:

1. **Cache-hit path** (room has cached events): sends a paginate to "fetch any newer events from server." The cached events are already showing; this paginate only fills gaps.
2. **LRU-restore path** (room restored from LRU): sends a paginate to pull events the LRU restore may have missed.

Both are background operations, not fresh loads. If tracked as `timelineRequests`, the paginate response clears `eventChainMap` and loses any events that arrived via sync_complete in the window between when the paginate was sent and when the response arrived. This race manifests visibly in bridge rooms — where the bridge delivers messages with a delay relative to when the server processes the paginate — as messages from the other party vanishing after the user sends a new message. Reopening the room restores them (because `processCachedEvents` reads from the full cache). The fix is confirmed in `TimelineCacheCoordinator.kt` (`requestRoomTimeline`).

## Bridge Support (Mautrix / Beeper)

See **[docs/bridges.md](docs/bridges.md)** for full documentation.

The app has first-class support for Matrix bridges (e.g. Mautrix bridges for WhatsApp, Telegram, etc.). Key areas covered in that doc:
- Bridge detection (`BridgeInfo`, `BridgeInfoCache`, `parseBridgeInfoEvent`)
- Bridges tab in `RoomListScreen` (`RoomSectionType.BRIDGES`)
- Bridge icon / background in timeline top bar (`BridgeNetworkBadge`, `BridgeBackgroundLayer`)
- Per-message bridge profiles (`com.beeper.per_message_profile`)
- Bridge send status (`com.beeper.message_send_status`) — delivery icons, Delivery Info dialog, functional members (`io.element.functional_members` / MSC4171)

## Read Receipts

See **[docs/RECEIPTS.md](docs/RECEIPTS.md)** for full documentation.

Read receipts track which users have read up to a given message. Key invariants:

- Each user has **exactly one receipt per room** — it must appear on only one event at a time.
- `AppViewModel.readReceipts` is a **global map** (`eventId → List<ReadReceipt>`) shared across all rooms; `ReadReceipt.roomId` is used to prevent cross-room corruption.
- **`processReadReceiptsFromSyncComplete`** is the incremental path — it finds and removes the user's old receipt before adding the new one, and fires `onMovementDetected` for animation.
- **Paginate** (in `TimelineCacheCoordinator`) is authoritative per-event — it replaces receipts for returned events but does not explicitly clear the user from other events.
- **`populateReadReceiptsFromCache`** merges from `ReadReceiptCache` — it **must** evict the user from other events before placing them (done via `evictUserFromOtherEvents`). Failing to do so causes receipt accumulation: the avatar appears on multiple bubbles simultaneously instead of moving.
- `ReadReceiptCache` is a singleton that mirrors `readReceipts` and is updated (via `setAll`) after every sync_complete batch and every paginate. It allows new VM instances to recover receipt state without waiting for the next sync.

## Offline Connection Indicator

A pulsing `CloudOff` icon in `MaterialTheme.colorScheme.error` is shown in every screen's header when the WebSocket is not in `ConnectionState.Ready`. It is driven by `SyncRepository.connectionState.collectAsState()` and uses an `infiniteRepeatable` alpha animation (0.4 → 1.0, 800 ms, `RepeatMode.Reverse`) inside an `AnimatedVisibility`.

**Placement per screen:**
- `RoomListScreen` — between the user Column and the Create Room (`AddCircle`) icon button in the top bar Row; button order is: CloudOff · AddCircle · Notifications · Settings
- `RoomTimelineScreen` (`RoomHeader`) — to the left of the `Notifications` icon button
- `BubbleTimelineScreen` (`BubbleRoomHeader`) — first item in the trailing icons Row, before the "Open in app" button
- `ThreadViewerScreen` — trailing item in the header Row, after the thread title Column

The connection state is sourced directly from `SyncRepository.connectionState` (a `StateFlow<ConnectionState>`). `isReady()` is the extension function in `ConnectionState.kt` that returns `true` only for `ConnectionState.Ready`.

## RoomListScreen

See **[docs/ROOMLISTSCREEN.md](docs/ROOMLISTSCREEN.md)** for a full composable reference (header, tabs, search, room item, spaces/bridges, guards, navigation helpers).

### Room list update guard

`LaunchedEffect(roomListUpdateCounter, ...)` in `RoomListScreen` has an early-return guard:

```kotlin
if (!uiState.initialSyncComplete) {
    val hadContent = stableSection.rooms.isNotEmpty() || stableSection.spaces.isNotEmpty()
    if (hadContent) return@LaunchedEffect
}
```

This prevents flicker during the initial sync by blocking reorder/content updates while `initialSyncComplete = false`. **It only works correctly if `initialSyncComplete` actually becomes `true`.** If it stays `false` (e.g., from the "already connected" attach path — see above), the guard permanently blocks all updates. The safety net (`LaunchedEffect(initialSyncComplete, ...)`) only fires when `stableSection.rooms.isEmpty()`, so it does not rescue a partially-populated list.

### Scroll position after incremental room loading

Rooms are sorted most-recent-first. When rooms arrive in batches and are prepended to the `LazyColumn`, Compose's key-based scroll preservation shifts `firstVisibleItemIndex` upward. The sticky-top guard in `RoomListContent`:

```kotlin
LaunchedEffect(firstRoomId) {
    if (listState.firstVisibleItemIndex <= 1) {
        listState.animateScrollToItem(0)
    }
}
```

…silently fails because `firstVisibleItemIndex` is already `> 1` by the time `firstRoomId` changes. The fix is a `LaunchedEffect(effectiveInitialSyncComplete)` in `RoomListScreen` that calls `scrollToItem(0)` when sync first completes — this is the definitive "startup done, settle at top" moment.

### `stableSection` initialization

`stableSection` is initialized synchronously via `remember { appViewModel.getCurrentRoomSection() }`. This is intentional — it ensures rooms appear immediately on composition without waiting for a `LaunchedEffect`. Do not change this to a `LaunchedEffect`-only initialization, as it would cause a visible empty-list flash on every navigation back from a room.

## Version Management

`versionCode` is computed dynamically in `app/build.gradle.kts` based on seconds since 2024-01-01 epoch, plus a Play Store offset. `versionName` (e.g., `1.0.73`) is set manually. Bump `versionName` in `app/build.gradle.kts` for releases.
