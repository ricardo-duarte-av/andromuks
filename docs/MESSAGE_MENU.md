# Message Menu (`utils/MessageMenuBar.kt`)

## Overview

The message long-press menu is implemented as a bottom bar (`MessageMenuBar`) driven by `MessageMenuConfig`. It has 6 fixed action buttons (React, Reply, Edit, Delete, Original, History) plus a **More (+)** dropdown.

## More Dropdown Items (in order)

| Item | Condition |
|---|---|
| Pin / Unpin | Gated on `canPin` / `canUnpin` |
| Reactions | Enabled only when the event has reactions |
| Source | Opens raw JSON in `CodeViewer` |
| Copy | Copies rendered text to clipboard |
| Text | Opens rendered text in `CodeViewer` |
| Thread | Navigates to `ThreadViewerScreen`; only shown when `onViewInThread != null` |
| Delivery Info | Shown only for bridge messages with send-status data (`onShowBridgeDeliveryInfo != null`) |
| Send Error | Shown only when the event has a local send error |

## Thread Item Wiring

`onViewInThread` in `MessageMenuConfig` is populated in:
- `RoomTimelineScreen` (`onShowMenu` callback, line ~3634)
- `BubbleTimelineScreen` (`onShowMenu` callback, line ~2906)

Both only set it when `event.isThreadMessage()` returns `true`. The callback URL-encodes both `roomId` and the thread root event ID, then navigates to `"thread_viewer/$encodedRoomId/$encodedThreadRoot"`.

The Thread item uses `Icons.AutoMirrored.Filled.Message` as its icon.
