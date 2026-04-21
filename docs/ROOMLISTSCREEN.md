# RoomListScreen Reference

`RoomListScreen.kt` is the main hub screen of Andromuks. It is shown after login/auth-check and hosts the room list, tab navigation, search, room invitations, and the header with user identity and action buttons. The file is ~3 300 lines.

---

## Table of Contents

1. [Top-level Composables](#top-level-composables)
2. [Header](#header)
3. [Search](#search)
4. [Tab / Section System](#tab--section-system)
5. [RoomListContent](#roomlistcontent)
6. [RoomListItem](#roomlistitem)
7. [Spaces & Bridges Content](#spaces--bridges-content)
8. [Room Invitations](#room-invitations)
9. [Pull-to-Refresh](#pull-to-refresh)
10. [Key LaunchedEffects & Guards](#key-launchedeffects--guards)
11. [Navigation Helpers](#navigation-helpers)
12. [Utility Composables & Functions](#utility-composables--functions)

---

## Top-level Composables

| Composable | Purpose |
|---|---|
| `RoomListScreen` | Root composable. Holds all local state, all LaunchedEffects, and orchestrates the header, search, tab content, and tab bar. |
| `RoomListContent` | `LazyColumn` of filtered rooms. Handles fast-scroll detection and per-item avatar loading cutoff. |
| `SpacesListContent` | `LazyColumn` of spaces, filtered by search query. Shown in the Spaces tab when no space is currently entered. |
| `BridgesListContent` | `LazyColumn` of bridges (treated as pseudo-spaces). Shown in the Bridges tab when no bridge is currently entered. |
| `RoomListItem` | Individual room row: avatar, name, preview, timestamp, unread badge, context menu. |
| `SpaceListItem` | Individual space/bridge row: avatar, name, aggregated unread/highlight counts. |
| `InviteListItem` | Pending room invitation card, shown above the room list. |
| `TabBar` | Bottom navigation bar with section tabs. |
| `TabButton` | Single tab button with optional badge. |
| `ScrollToTopFab` | Floating action button; appears when the list is scrolled down. |
| `LeaveRoomDialog` | Confirmation dialog for leaving a room with optional reason text field. |

---

## Header

The header is a `Row` with `fillMaxWidth()` at the top of `RoomListScreen`.

**Left — user identity (weight(1f), clickable → `user_info/{userId}`):**
- User avatar (40 dp, shared-element key `STARTUP_SHARED_AVATAR_KEY` for fly-in from `AuthCheckScreen`)
- Display name (`titleMedium`)
- User ID (`bodySmall`, shown when display name is present)
- Sync indicator: pulsing blue dot while `isProcessingPendingItems`, `roomListUpdateCounter` changed, or `isProcessingBatch`
- RUSH label with batch count when `isProcessingBatch = true` (error color, debug / performance visibility)

**Right — action buttons:**
- `CloudOff` icon (error color, pulsing alpha 0.4→1.0 at 800 ms): shown via `AnimatedVisibility` when `connectionState` is not `Ready`
- `AddCircle` icon → navigates to `"room_maker"` (Create Room)
- `Notifications` icon → navigates to `"mentions"`
- `Settings` icon → navigates to `"settings"`

---

## Search

A pill-shaped `TextField` (`CircleShape`, `tonalElevation = 1.dp`) below the header.

- Placeholder: "Search rooms…"
- Filters the active section's room list in real-time (no debounce) by case-insensitive room name match inside `RoomListContent` / `SpacesListContent` / `BridgesListContent`.

---

## Tab / Section System

### RoomSectionType

| Value | Content |
|---|---|
| `HOME` | All non-space rooms in joined order |
| `SPACES` | Spaces list; entering a space switches to its rooms |
| `DIRECT_CHATS` | Direct message rooms only |
| `UNREAD` | Rooms with `unreadCount > 0` or `highlightCount > 0` |
| `FAVOURITES` | Rooms tagged `m.favourite` (shown only when `showAllRoomListTabs = true`) |
| `BRIDGES` | Mautrix bridges as pseudo-spaces (shown only when `showAllRoomListTabs = true`) |

The Mentions section is accessed via the bell icon button in the header, not a tab.

### Tab animation

`AnimatedContent` with `contentKey` set to the section **type** (not the data), so data updates within the same tab do not re-trigger slide transitions. The slide direction is determined by comparing the ordinal of the current vs. previous `RoomSectionType`: moving right → slide in from right, moving left → slide in from left.

### Animation guard

Tab-switch data updates are gated by a 450 ms delay (`animationGeneration` `LaunchedEffect`): when `isAnimationInProgress = true`, any incoming `stableSection` update is parked in `pendingSectionUpdate` and applied only after the animation finishes. Rapid tab switches cancel the previous delay and restart the timer.

Tab buttons are disabled while `isProcessingBatch = true` to prevent animation conflicts during bulk sync processing.

---

## RoomListContent

Renders a `LazyColumn` of rooms matching the current search query.

### Fast-scroll detection

Scroll speed is sampled via `LazyListState.firstVisibleItemIndex` changes. If the user scrolls more than 10 items in under 100 ms, `isScrollingFast = true`. During fast scrolling, `shouldLoadAvatar = false` is passed to each `RoomListItem`, suppressing image decoding and reducing jank.

### Avatar cutoff

`avatarLoadCutoff` tracks `firstVisibleItemIndex + 25`. Items beyond this index receive `shouldLoadAvatar = false`, avoiding unnecessary Coil decode requests for rooms far outside the viewport.

### Sticky-top scroll

When `effectiveInitialSyncComplete` transitions to `true`, `RoomListContent` calls `scrollToItem(0)` on the active `LazyListState`. This fixes the viewport shift that occurs when rooms prepended to the top during initial sync push `firstVisibleItemIndex` upward.

### Back handler

When `currentSpaceId` or `currentBridgeId` is set (user has entered a space/bridge), a `BackHandler` exits back to the space/bridge list instead of navigating up the stack.

---

## RoomListItem

The individual room row composable.

### Visual elements

| Element | Detail |
|---|---|
| Avatar | 48 dp circle; shared-element tagged with room ID for smooth fly-in to `RoomTimelineScreen` |
| Bridge badge | 16 dp protocol avatar overlaid at bottom-right of the avatar |
| Room name | `titleSmall`, bold when `highlightCount > 0` |
| Message preview | `"SenderName: preview text"`, `bodySmall`, 1 line, ellipsis |
| Timestamp | `formatTimeAgo()`: HH:mm for today, "Yesterday", or "Xd/w/y ago" |
| Unread badge | Pill with count; primary color for unread, error color for highlights |
| Low-priority icon | `NotificationsOff` (16 dp) to the left of the unread badge |
| Timeline cache icon | `Memory` (16 dp) when the room's timeline is actively cached in `RoomTimelineCache` |

### Interactions

- **Single tap** → navigate to `room_timeline/{roomId}`; set `currentRoomId`; prefetch room snapshot.
- **Long press** → open context menu (modal dialog with fade + scale animation).

### Context menu actions

- Favourite toggle (`Switch`)
- Low Priority toggle (`Switch`)
- Add home screen shortcut
- Room Info → `room_info/{roomId}`
- Mark Read
- Leave → opens `LeaveRoomDialog`

### Performance memoisation

- Timestamp string: `remember(room.sortingTimestamp, timestampUpdateTrigger)`
- Sender display name: `remember(room.messageSender, appViewModel.memberUpdateCounter)`
- Avatar wrapped in `key(room.id)` to prevent wrong-avatar flicker on shared-element transitions

---

## Spaces & Bridges Content

`SpacesListContent` and `BridgesListContent` are thin wrappers: each renders a `LazyColumn` of `SpaceListItem` rows, filtered by search query.

`SpaceListItem` shows:
- Space avatar (48 dp)
- Space name
- Room count
- Aggregate unread / highlight badge across all rooms in the space

Tapping a space sets `appViewModel.currentSpaceId` (or `currentBridgeId`) and switches the tab content to a `RoomListContent` scoped to that space's rooms, with a zoom-in transition.

---

## Room Invitations

When `appViewModel.pendingInvites` is non-empty, a horizontal invite cards section is rendered **above** the room list (inside the same `LazyColumn`). Each card uses `InviteListItem`:

- Room avatar, room name, inviter display name
- Tapping calls the `onInviteClick` callback, which opens an invite-accept/decline dialog handled by `RoomListScreen`.

---

## Pull-to-Refresh

Pull-to-refresh is enabled only when `listState.firstVisibleItemIndex == 0` (user is at the top of the list). Pulling shows a confirmation dialog with three options:

| Option | Action |
|---|---|
| Full Refresh | Clears caches and reconnects WebSocket |
| Quick Refresh | Re-fetches room state without clearing caches |
| Cancel | Dismisses the dialog |

---

## Key LaunchedEffects & Guards

### Unified stableSection updater

`LaunchedEffect(roomListUpdateCounter, roomSummaryUpdateCounter, currentSpaceId, currentBridgeId)` is the single place where `stableSection` is rebuilt. It:

1. Detects section type changes and sets `isAnimationInProgress = true`.
2. If an animation is in progress, parks the update in `pendingSectionUpdate` and returns.
3. Anti-flicker: does **not** replace a populated room list with an empty one.
4. Fine-grained diff: if only unread counts changed (not order or membership), updates counts in-place without reordering; if only order changed, reuses existing `RoomItem` instances to avoid avatar re-renders.

### initialSyncComplete safety net

`LaunchedEffect(initialSyncComplete)` fires once when `initialSyncComplete` becomes `true`. If `stableSection.rooms` is still empty at that point (e.g., mid-sync attach path), it reloads the section from `AppViewModel` immediately. This prevents the room-update guard (`!initialSyncComplete && hadContent → return`) from permanently blocking updates. See CLAUDE.md for the full invariant.

### Opportunistic profile loading

`LaunchedEffect(messageSendersKey, effectiveInitialSyncComplete)` — after sync is complete, collects the first 50 unique message sender IDs from the current room list and requests their display names if not already in `ProfileCache`. Runs only once per set of visible senders.

### Smart timestamp update

`LaunchedEffect(displayedSection.rooms, updateInterval)` — an infinite loop that increments `smartTimestampUpdateCounter` on a computed interval: 1 minute if the most-recently-active room has a timestamp from today, otherwise 24 hours. The interval is recalculated whenever the room list changes.

### Navigation LaunchedEffects

| Key | Purpose |
|---|---|
| `Unit` (auth pop) | Pops `auth_check` off the back stack after the shared-element avatar fly-in animation completes (600 ms delay). Also checks whether the ViewModel's `roomMap` is stale from a cold start triggered by a notification or shortcut, and reloads from `RoomListCache` if so. |
| `Unit` (direct room nav) | If the app was opened via a shortcut or notification with a target room ID, navigates directly to that room once spaces are loaded and WebSocket is ready. Uses a 10-second timeout fallback. |
| `navigationTrigger` | Handles `FLAG_ACTIVITY_SINGLE_TOP` re-entry (`onNewIntent`): navigates to the new target room without recreating `MainActivity`. |

---

## Navigation Helpers

**`NavController.navigateToRoomTimelineForExternalEntry(roomId)`** — extension function for opening a room from a notification or shortcut. Pops `auth_check` and `room_list` off the back stack so pressing Back exits the task entirely rather than returning to the list.

---

## Utility Composables & Functions

| Name | Purpose |
|---|---|
| `formatTimeAgo(timestamp)` | Formats a Unix-ms timestamp as HH:mm (same day), "Yesterday", or "Xd / Xw / Xy ago". Used in `RoomListItem`. |
| `usernameFromMatrixId(userId)` | Extracts the local part from `@username:server` Matrix IDs. |
| `LeaveRoomDialog` | `AlertDialog` with an optional free-text reason field. Calls `appViewModel.leaveRoom(roomId, reason)` on confirm. |
| FOREGROUND_REFRESH receiver | A `BroadcastReceiver` registered for `"net.vrkknn.andromuks.FOREGROUND_REFRESH"`. Refreshes the UI from cache when the app returns to the foreground (e.g., after being resumed from a notification). |
