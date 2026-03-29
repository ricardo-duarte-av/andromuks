# Message Sending — Protocol, Local Echo & Error Handling

## Protocol Flow

Sending a message involves four distinct stages, all linked by `transaction_id`:

```
1. Client → {"command":"send_message","request_id":11,"data":{...}}

2. Server → {"command":"response","request_id":11,"data":{
       "event_id":"~hicli-mautrix-go_..._26",   ← tilde prefix = local pending ID
       "transaction_id":"hicli-mautrix-go_..._26",
       "type":"m.room.message",
       "send_error":"not sent",                  ← backend placeholder, NOT a real error
       ...
   }}

3. Server → {"command":"send_complete","request_id":-33523,"data":{
       "event":{
           "event_id":"$CWn...",                 ← real server-assigned ID
           "transaction_id":"hicli-...",
           "send_error":"not sent",              ← still "not sent" even on success
           ...
       },
       "error":null                              ← null = success, non-null string = failure
   }}

4. Server → {"command":"sync_complete",...}      ← same $CWn... arrives again via sync
```

**Key linking rules:**
- `transaction_id` ties step 2 → step 3 → step 4
- `event_id` starting with `~` = pending local echo (not yet confirmed by server)
- `event_id` starting with `$` = server-confirmed event
- `send_complete.data.error` (outer field) is the canonical success/failure signal — **not** `send_error` inside the event
- `send_error: "not sent"` inside the event is a backend placeholder always present; it does **not** indicate failure

## Local Echo (Pending Placeholders)

When `response` arrives (step 2), the app inserts a pending placeholder immediately so the user sees their message without waiting for the full round-trip.

### Data Structures

- **`pendingEchoMap`** (`AppViewModel`): `mutableMapOf<String, String>()` — maps `transactionId → pending ~eventId`
- **`eventChainMap`** (`EditVersionCoordinator`): `LinkedHashMap<String, TimelineEvent>` — keyed by `eventId`; the canonical source of truth for the rendered timeline

### Insertion (step 2 — `handleMessageResponse`)

Only triggers when:
- `response` is not an error
- `roomId == currentRoomId`
- Event type is `m.room.message`, `m.room.encrypted`, or `m.sticker`
- `event_id` starts with `~` and `transactionId` is non-null

Write order matters (race-condition guard): `eventChainMap` is written **before** `pendingEchoMap`. If `processSendCompleteEvent` fires between the two writes, it must not find a txId without a matching chain entry.

### Eviction / Confirmation (step 3 — `processSendCompleteEvent`)

**On success** (`error == null`):
1. Look up `pendingEchoMap.remove(transactionId)` → `pendingEventId`
2. Remove `eventChainMap[pendingEventId]` (evict the `~`-prefixed placeholder)
3. Proceed normally — `addNewEventToChain(event)` inserts the `$`-prefixed confirmed event

**On failure** (`error != null`):
1. Look up the pending echo in `eventChainMap` via `pendingEchoMap`
2. Update its `localContent` with the `send_error` string
3. Keep the `~`-prefixed echo in the chain (it becomes a "failed" bubble)
4. Return early — do **not** insert a `$`-prefixed event

### Deduplication (step 4 — sync_complete)

`addNewEventToChain` already deduplicates by `eventId`. When `sync_complete` re-delivers the `$`-prefixed event that `send_complete` already inserted, the guard returns early. No special handling needed.

## Visual States

Pending/failed state is derived entirely from `event.eventId` and `event.localContent` in `ReplyFunctions.kt`:

```kotlin
val isPendingEcho = event.eventId.startsWith("~")
val isFailedEcho  = event.localContent?.optString("send_error")?.isNotBlank() == true
```

Color priority (order matters — a failed echo also starts with `~`):

| State | Color |
|-------|-------|
| Failed | `errorContainer` |
| Pending | `tertiaryContainer` |
| Normal | `bubbleColor` |

## Sort Position of Pending Echoes

Pending echoes have `timelineRowid = 0` (not yet persisted to DB). Since the timeline sorts by `timelineRowid` ascending, a zero value would sort to the "oldest" position — pushed off screen with `reverseLayout = true`.

Fix: substitute `Long.MAX_VALUE` when `timelineRowid <= 0`, placing pending echoes at the newest end:

```kotlin
val sorted = eventsWithoutEdits.sortedWith(compareBy(
    { if (it.timelineRowid > 0L) it.timelineRowid else Long.MAX_VALUE },
    { it.timestamp },
    { it.eventId }
))
```

Applied in both `RoomTimelineScreen.kt` (`processTimelineEvents`) and `BubbleTimelineScreen.kt` (`bubbleProcessTimelineEvents`).

## User Actions on Pending/Failed Echoes

- **Delete / Dismiss**: calls `AppViewModel.dismissPendingEcho(eventId)` — removes from `eventChainMap` and `pendingEchoMap`, then rebuilds timeline. This is purely in-memory; no network call.
- **Reply, React, Edit**: no-ops for pending/failed echoes (callbacks are replaced with `{}`).
- **Send Error menu item**: visible in the `+/More` dropdown when `event.localContent` contains a non-blank `send_error`. Shows an `AlertDialog` with the rejection reason.
- **Pin**: disabled for pending/failed echoes (`canPin && !isFailedEcho && !isPendingEcho`).

## Files Involved

| File | Role |
|------|------|
| `AppViewModel.kt` | `pendingEchoMap` declaration; `handleMessageResponse` (echo insertion); `processSendCompleteEvent` (eviction/failure); `dismissPendingEcho` |
| `utils/NetworkUtils.kt` | Calls `processSendCompleteEvent(event, error)` — passes outer `error` field from `send_complete` |
| `utils/ReplyFunctions.kt` | Derives `isPendingEcho`, `isFailedEcho`; applies bubble colors; disables actions; wires `effectiveOnDelete` |
| `utils/MessageMenuBar.kt` | "Send Error" dropdown item + `AlertDialog` |
| `RoomTimelineScreen.kt` | Sort fix for `timelineRowid = 0` |
| `BubbleTimelineScreen.kt` | Sort fix for `timelineRowid = 0` |
