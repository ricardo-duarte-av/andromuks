# Gomuks backend issues affecting Andromuks

Findings from reading the [gomuks](https://github.com/tulir/gomuks) source while investigating
notifications that stay up after the conversation was read in another Matrix client.

Everything here is **observed from the source**, not a statement about upstream intent. All
references are pinned to commit
[`7828fd5`](https://github.com/tulir/gomuks/tree/7828fd5ef9a77ed6760f48a4f107f6d46e5b8432)
(2026-08-21); line numbers will drift.

Each entry records the upstream behaviour, what it costs Andromuks, the local workaround, and what
an upstream fix would look like. **When one of these is fixed upstream, the matching workaround
becomes dead weight and should be removed** — that traceability is the point of this file.

See [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md) for the client-side architecture these work
around.

---

## 1. The dismiss push is gated on a pre-update count that is sometimes already zero

**Where:** `pkg/hicli/sync.go`, in `processSyncRoom`.

```go
dismissNotifications := room.UnreadNotifications > 0 && updatedRoom.UnreadNotifications == 0 && len(newNotifications) == 0
```

The first term reads the room's **pre-update** in-memory unread notification count. Immediately
below the assignment, upstream's own comment concedes the problem:

```go
// TODO why is *old* unread count sometimes zero when processing the read receipt that is making it zero?
```

It interacts with the recount a few lines above, which is itself skipped when the cached counts are
already zero:

```go
if !room.UnreadCounts.IsZero() && ((len(newOwnReceipts) > 0 && newUnreadCounts.IsZero()) || unreadMessagesWereMaybeRedacted) {
    updatedRoom.UnreadCounts, err = h.DB.Room.CalculateUnreads(ctx, room.ID, h.Account.UserID)
```

**Effect on Andromuks:** when `room.UnreadNotifications` is already zero, `dismissNotifications`
stays false and **no dismiss is emitted at all** — not over FCM, not over the WebSocket, not to the
desktop channel. There is nothing for a client to react to, however correct its FCM handling is.
This is the single largest cause of the reported symptom.

**Local workaround:** two, because this one is not recoverable from the push stream.

- `NotificationSyncReconciler`'s second arm treats "this room has a notification posted **and**
  `sync_complete` now reports all its unread counters at zero **and** the sync carried no new
  notifications for it" as equivalent to a dismiss.
- `NotificationImageWorker.verifyRoomRead` asks the server directly over `/exec get_receipts` and
  takes the notification down when our own `m.read` receipt is found — the only arm that works in
  battery-saver mode, where there is no socket.

**Upstream fix:** resolve the TODO — recalculate or read through to the stored count before
evaluating the condition, so the dismiss decision does not depend on a cache that may already have
been zeroed.

---

## 2. Dismisses are capped at ten per sync and the remainder is dropped

**Where:** `pkg/gomuks/push.go`, `SendPushNotifications`.

```go
for _, room := range sync.Rooms {
    if room.DismissNotifications && len(push.Dismiss) < 10 {
        push.Dismiss = append(push.Dismiss, PushDismiss{RoomID: room.Meta.ID})
    }
```

The eleventh and subsequent dismissible rooms in one sync are silently discarded. There is no
overflow batch and no retry — the backend never re-sends a dismiss.

**Effect on Andromuks:** marking many rooms read at once (a "mark all read" in another client, or
catching up after being away) clears the first ten notifications and strands the rest indefinitely.

**Local workaround:** `NotificationSyncReconciler` reads `dismiss_notifications` per room straight
off `sync_complete`, which has no cap — the cap exists only in the push-payload builder.

**Upstream fix:** the payload splitter directly below already knows how to emit multiple pushes
(`PushNotification.Split`); the dismiss list could be chunked the same way instead of truncated.

---

## 3. Dismiss-only payloads ship at normal FCM priority

**Where:** `pkg/gomuks/push.go`. `PushNotification.Split` computes `HasImportant` purely from
message `Sound` flags:

```go
hasSound = hasSound || pn.OrigMessages[i].Sound
```

A payload with no messages never enters that loop, so `HasImportant` is false and
`SendFCMPush(ctx, token, devicePayload, notif.HasImportant)` requests normal priority.

**Effect on Andromuks:** Android Doze defers normal-priority FCM to the next maintenance window.
A dismiss can therefore arrive minutes to hours after the read, or effectively never on a device
that stays idle. This is why the notification often clears "when I pick the phone up" rather than
when the message was actually read.

**Local workaround:** the socket path (`NotificationSyncReconciler`) is not subject to FCM
prioritisation at all, and `NotificationImageWorker.verifyRoomRead` covers the case where the socket
is down.

**Upstream fix:** dismisses are user-visible state changes and arguably deserve high priority in
their own right; at minimum `HasImportant` could be true when `len(Dismiss) > 0`.

---

## 4. `PushDismiss` carries only a room id

**Where:** `pkg/gomuks/push.go`.

```go
type PushDismiss struct {
    RoomID id.RoomID `json:"room_id"`
}
```

No event id, no timestamp, no read-up-to marker.

**Effect on Andromuks:** a client cannot order a dismiss against the message it is meant to clear.
If FCM delivers the dismiss *before* the message it follows — possible when a high-priority message
is downgraded under quota pressure and reordered past the normal-priority dismiss — the client has
nothing to compare and the notification lingers. This is the "one unsolved edge" documented in
[docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md); `NotificationDismissTracker` works around it with a
local wall-clock tombstone, which is the best ordering obtainable on-device.

**Local workaround:** `NotificationImageWorker.verifyRoomRead` sidesteps ordering entirely by asking
the server for read state rather than inferring it from push arrival order.

**Upstream fix:** add a read-up-to `event_id` (or the receipt timestamp) to `PushDismiss`. That would
make the dismiss self-ordering and let every client drop its local heuristics.

---

## 5. Web push cannot dismiss at all

**Where:** `pkg/gomuks/push.go`, `SendWebPush`.

```go
if !important {
    // Dismissing notifications isn't supported currently
    return
}
```

**Effect on Andromuks:** none directly — Andromuks is an FCM client. Recorded because it is the same
root cause as issue 3 (dismiss payloads are classified as unimportant) and because it means a
Webmuks tab and Andromuks will disagree about which conversations are still notifying.

**Upstream fix:** the adjacent `Topic` TODO in the same function
(*"use topics for collapsing pending notifications on read receipt?"*) is the natural mechanism.

---

## How the workarounds line up

| Upstream issue | `NotificationSyncReconciler` (socket) | `verifyRoomRead` (`/exec`) | Durable tombstone |
|---|---|---|---|
| 1. Dismiss never emitted | yes (unread-zero arm) | yes | — |
| 2. Ten-dismiss cap | yes | yes | — |
| 3. Normal-priority Doze delay | yes | yes | — |
| 4. Unorderable dismiss | — | yes | partial (wall-clock) |
| 5. Web push dismissal | not applicable | not applicable | not applicable |

The socket arm is inert in battery-saver mode (the WebSocket is torn down there), which is precisely
why the `/exec` arm exists as well.
