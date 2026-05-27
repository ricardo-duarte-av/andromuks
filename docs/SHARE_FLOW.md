# Share-to-Room Flow

How Android `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intents are routed to the right room.

## Generic share flow (room picker)

1. Android share intents land in `MainActivity`, which sets `pendingShare` and `pendingShareNavigationRequested = true` on `AppViewModel`.
2. `MainActivity`'s `LaunchedEffect(pendingShareNavigationRequested)` navigates to `SimplerRoomListScreen` immediately and calls `markPendingShareNavigationHandled()` (clears the request flag, leaves `pendingShare` set).
3. `AuthCheck`'s navigation callback guards on `pendingShare != null` (**not** `pendingShareNavigationRequested`) to avoid redirecting back to `room_list` after the request flag has been cleared. `navigateToRoomListIfNeeded` has the same `pendingShare != null` guard at the top as defence-in-depth.
4. User picks a room → `SimplerRoomListScreen` calls `selectPendingShareRoom(roomId)` → `navigateToRoomWithCache(roomId)` → `navigate("room_timeline/<id>")`.

## Direct Share fast path (pre-selected room)

When the user taps a **Direct Share** shortcut in the Android share sheet, `Intent.EXTRA_SHORTCUT_ID` carries the target room ID — the room is already known and the picker can be skipped.

1. `MainActivity.processShareIntent` reads `EXTRA_SHORTCUT_ID` as `shortcutRoomId` and calls `setPendingShare(..., autoSelectRoomId = shortcutRoomId)`.
2. `setPendingShare` stores the ID in `pendingShareTargetRoomId` (observable `mutableStateOf`, `private set`).
3. `SimplerRoomListScreen` watches this field via `LaunchedEffect(showRooms, pendingShareTargetRoomId)`. Once `showRooms = true` and the target room appears in `allRooms`, it calls `selectPendingShareRoom`, `navigateToRoomWithCache`, and navigates directly to `room_timeline/<id>` — no manual room selection required.
4. `setDirectRoomNavigation` is **not** called for Direct Share, so `AuthCheck` does not race with this path.

The user sees `SimplerRoomListScreen` (with its loading indicator) immediately on cold start instead of a black screen, then the navigation flips to the target room as soon as the room map populates.

## Why `SimplerRoomListScreen` instead of `RoomListScreen`?

`SimplerRoomListScreen` is a stripped-down picker optimised for the share use case: no spaces sidebar, no badges/receipts, no long-press menus. It composes faster and avoids triggering the full room-list machinery just to pick a destination for a one-shot share.
