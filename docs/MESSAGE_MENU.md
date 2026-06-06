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
| Thread | Navigates to `ThreadViewerScreen` for a message that is **itself a thread reply**; only shown when `onViewInThread != null` |
| Start thread / View thread | Navigates to `ThreadViewerScreen` rooted at **this** message; only shown when `onStartOrViewThread != null`. Label is "View thread" when `startThreadIsExisting`, otherwise "Start thread" |
| Delivery Info | Shown only for bridge messages with send-status data (`onShowBridgeDeliveryInfo != null`) |
| Send Error | Shown only when the event has a local send error |

## Thread Item Wiring

There are two mutually-exclusive thread entries, gated on whether the long-pressed event is *itself* a thread reply:

- **Thread** (`onViewInThread`) — shown when `event.isThreadMessage()` is `true`. The message is a reply, so it navigates to its **root** (`event.getThreadInfo().threadRootEventId`). Wired in `RoomTimelineScreen` and `BubbleTimelineScreen` `onShowMenu` callbacks.
- **Start thread / View thread** (`onStartOrViewThread`) — shown when `event.isThreadMessage()` is `false`. The message becomes the **root**, so it navigates to its own `eventId`. Wired in `RoomTimelineScreen` only (`onShowMenu` callback). The `startThreadIsExisting` flag (from `AppViewModel.hasThreadReplies`) toggles the label between "View thread" (already has replies loaded) and "Start thread" (none).

Both callbacks URL-encode `roomId` and the target root event ID, set `appViewModel.threadReturnScrollEventId`, then navigate to `"thread_viewer/$encodedRoomId/$encodedThreadRoot"`. Both items use `Icons.AutoMirrored.Filled.Message` as their icon.

See **[docs/THREADS.md](THREADS.md)** for how `ThreadViewerScreen` renders a thread (and why a single-root "new" thread works).
