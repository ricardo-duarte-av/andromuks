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

## Replies & thread relations on attachments (`buildMediaRelatesTo`)

Text replies set `content.m.relates_to.m.in_reply_to` via `textContent(text, replyToEventId)`. Every **media/attachment** send variant (`sendMediaMessage`/image, video, audio, file, sticker, location) builds the same `relates_to` through one shared helper, `MessageSendCoordinator.buildMediaRelatesTo(roomId, threadRootEventId, replyToEventId)`:

- **Thread send** (`threadRootEventId != null`): an `m.thread` relation whose `m.in_reply_to` target is the explicit `replyToEventId` or the thread's last message; `is_falling_back` is set when neither resolves.
- **Plain reply** (`threadRootEventId == null`, `replyToEventId != null`): a bare `m.in_reply_to` — identical in shape to a text reply. This is what lets you reply to a message *with* an image/video/audio/file.
- **Neither**: `null` (no relation).

This was previously inlined per-function and gated on `threadRootEventId != null` only, so a plain reply target was silently dropped — attachments never went out as replies. The UI side (`RoomTimelineScreen` `MediaPreviewDialog.onSend`) captures `replyingToEvent` at send time and threads `threadRootEventId`/`replyToEventId` through; camera and voice capture share that path.

`insertMediaEcho` embeds the **same** `buildMediaRelatesTo` result into the optimistic placeholder's `content`, so the pending bubble renders the reply quote / thread relation immediately — `getReplyInfo()`/`getThreadInfo()` read it from `content.m.relates_to`, not from the TimelineEvent-level `relationType`/`relatesTo` fields. Reusing the helper keeps echo and confirmed event byte-identical.

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
3. Call `markTimelineEntrancePlayed(event.eventId)` for the confirmed `$`-prefixed ID — pre-marks the entrance as already played so the confirmed event swaps its bubble color in place without re-running the slide-in animation (the pending echo already played it)
4. Proceed normally — `addNewEventToChain(event)` inserts the `$`-prefixed confirmed event

**On failure** (`error != null`):
1. Look up the pending echo in `eventChainMap` via `pendingEchoMap`
2. Update its `localContent` with the `send_error` string
3. Keep the `~`-prefixed echo in the chain (it becomes a "failed" bubble)
4. Return early — do **not** insert a `$`-prefixed event

### Deduplication (step 4 — sync_complete)

`addNewEventToChain` deduplicates by `eventId`. When `sync_complete` re-delivers the `$`-prefixed event that `send_complete` already inserted, the guard returns early.

**`timeline_rowid` upgrade**: `send_complete` delivers the confirmed event with `timeline_rowid: 0`. `sync_complete` delivers the same event with a real rowid (e.g. `207133`) resolved from the room's `timeline` mapping. The deduplication guard upgrades the stored entry when `event.timelineRowid > 0 && existingBubble.timelineRowid <= 0`, so the final entry always ends up with the real rowid regardless of arrival order.

### Race condition — sync_complete arrives before send_complete

`sync_complete` can arrive before `send_complete` (their request IDs are consecutive and the network can reorder them). When this happens:

1. `response` (step 2) inserts the `~`-prefixed pending echo into `eventChainMap` and `pendingEchoMap`.
2. `sync_complete` (step 4) calls `addNewEventToChain` for the `$`-prefixed confirmed event. Without a guard, both the `~` echo and the `$` event now coexist in `eventChainMap`. Both share the same `transactionId`, so they produce the same `stableKey` in the `LazyColumn` → **crash**: `IllegalArgumentException: Key was already used`.
3. `send_complete` (step 3) would have evicted the echo, but it arrives too late.

**Fix (applied in `EditVersionCoordinator.addNewEventToChain`)**: Before inserting a confirmed (`$`-prefixed) event, check whether its `transactionId` matches a pending echo in `pendingEchoMap`. If so, evict the echo from both `eventChainMap` and `pendingEchoMap` immediately, and pre-mark the entrance as played. When `send_complete` later arrives, `pendingEchoMap.remove(txId)` returns null and the eviction block is skipped harmlessly.

**Fix (applied in `RoomTimelineScreen.kt` and `BubbleTimelineScreen.kt` `stableKey`)**: Changed from `transactionId ?: ... ?: eventId` to simply `eventId`. Since `eventId` is unique per event, duplicate `LazyColumn` keys are structurally impossible even if both a `~` echo and its `$` confirmation are transiently present in the list.

## Send-time placeholders & the lifecycle state machine

`send_message` is **non-idempotent**: the backend mints a fresh `transaction_id` on every call and there is **no client-supplied idempotency key** (confirmed against the gomuks RPC spec — `SendMessageParams` has no txn field). Re-sending therefore creates a *duplicate* event. Two consequences:

1. **We never auto-retry or buffer `send_message`.** `PersistenceCoordinator.checkAcknowledgmentTimeouts()` and `retryPendingWebSocketOperations()` both **drop** any `send_message` op (matched by `data["command"]`, covering `command_*` and any stale `offline_*`) instead of re-sending it. And `WebSocketCommandSender` no longer offline-buffers `send_message` at all: when the WebSocket is down, the send is **not** queued (`queueOfflineRetry` only accepts idempotent `mark_read`) — the send-time placeholder times out to Failed and the user resends manually. Without this, an airplane-mode send failed red **and then sent anyway on reconnect** from the `offline_send_message` queue — a duplicate. (The one legitimate buffer is `pendingCommandsQueue` for the *connected-but-pre-init* window, which re-sends once with the **same** `request_id`, so the placeholder reconciles and nothing duplicates.) Together these fix a duplication storm where a missed `response` ack spawned an endless chain of new events.

2. **Failure is surfaced to the user, not papered over.** Every user send (`message / reply / thread reply / image / video / audio / file / sticker / location` — *not* reactions) inserts an optimistic placeholder **at send time** via `LocalEchoCoordinator`, which walks this state machine (encoded in the placeholder's `local_content`):

| State | Trigger | Color | Elevation |
|-------|---------|-------|-----------|
| `Sending` | send issued (immediate) | `tertiaryContainer` | lifted (6.dp) |
| `Sent` | `response` arrives | `tertiaryContainer` | flat |
| `Confirmed` | `send_complete` ok / `sync_complete` evicts | `bubbleColor` (real bubble) | flat |
| `Failed` | see triggers below | `errorContainer` | flat |

**Identity.** The placeholder keeps a stable client-local id (`~local-<uuid>`) as its `eventChainMap`/LazyColumn key for its whole life, so `Sending → Sent` never recreates the item (animation continuity). The leading `~` makes `isPendingEcho` apply. On the `response` we learn the backend `transaction_id` and register `pendingEchoMap[txId] = localId`, so the **existing** reconciliation paths (`EditVersionCoordinator.addNewEventToChain` for `sync_complete`, `processSendCompleteEvent` for `send_complete`) evict/fail the echo by `transaction_id` exactly as before.

**Correlation.** The `response` echoes our `request_id`, which is the only frame that bridges our send to the backend's `transaction_id` (`send_complete`/`sync_complete` carry the txId but not our request_id). `handleMessageResponse` (messageRequests path) and `handleOutgoingRequestResponse` (the mentions `sendMessage` overload) both call `localEchoCoordinator.onResponse(requestId, txId)`.

**Failure triggers (three):**
1. **No `response`** within `RESPONSE_TIMEOUT_MS` (20 s) — the backend never received the send. (The `response` is fast even in E2EE; encryption latency lives in `send_complete`.)
2. **`send_complete` with a Matrix-server error** (e.g. event too large) — handled by `processSendCompleteEvent`. **No `sync_complete` follows in this case**, because the event was never committed.
3. **Backstop** `CONFIRM_BACKSTOP_MS` (90 s) after `Sent` — covers a dropped `send_complete` *error* frame; generous so E2EE's slower confirmation never trips it falsely. Layer 2 (below) makes it almost never fire.

## Layer 2 — non-lossy ack flow

`response` / `send_complete` / `error` frames previously rode `SyncRepository._events` — a `MutableSharedFlow(extraBufferCapacity = 1024, onBufferOverflow = DROP_LATEST)` shared with high-volume `to_device`/`typing` traffic. A burst (e.g. a flood of encryption key events) could overflow that buffer and **evict an ack**, stranding the request: a send's echo would never confirm and would falsely time out to `Failed`.

These three frames now route to a **dedicated** `SyncRepository.ackEvents` flow (`emitPriorityIncomingWebSocketMessage`, `NetworkUtils.dispatchParsedWebSocketMessage`). Because acks are one-per-user-action, their own buffer effectively never fills, so they're isolated from sync/key bursts. Fan-out is preserved — every attached VM collects `ackEvents` too (a second collector in `AppViewModel.init`) and routes by `request_id`, so bubble VMs still reconcile their own sends. `sync_complete` continues to bypass both flows via the `UNLIMITED` `syncCompleteChannel`.

## Visual States

Pending/failed state is derived entirely from `event.eventId` and `event.localContent` in `ReplyFunctions.kt`:

```kotlin
val isPendingEcho = event.eventId.startsWith("~")
val isFailedEcho  = event.localContent?.optString("send_error")?.isNotBlank() == true
val isSendingEcho = isPendingEcho && !isFailedEcho &&
    event.localContent?.optString("local_send_state") == "sending"
```

Color priority (order matters — a failed echo also starts with `~`):

| State | Color |
|-------|-------|
| Failed | `errorContainer` |
| Pending (sending/sent) | `tertiaryContainer` |
| Normal | `bubbleColor` |

`MessageBubbleWithMenu` animates both the bubble color (`animateColorAsState`) and the shadow elevation (`animateDpAsState`, lifted only while `isSendingEcho`), so the `Sending → Sent` settle and `Sent → Failed` recolor morph smoothly. Both `RoomTimelineScreen` and `BubbleTimelineScreen` render through this shared composable.

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
| `LocalEchoCoordinator.kt` | Send-time placeholders + `Sending→Sent→Confirmed/Failed` state machine, response/confirm watchdog timers |
| `PersistenceCoordinator.kt` | Layer 1: drops timed-out `command_send_message` instead of re-sending (non-idempotent) |
| `MessageSendCoordinator.kt` | Inserts the placeholder for every send variant (`textContent`/`insertMediaEcho` helpers); `buildMediaRelatesTo` builds reply/thread `relates_to` shared by sends and echoes |
| `AppViewModel.kt` | `pendingEchoMap` declaration; `handleMessageResponse` / `handleOutgoingRequestResponse` (upgrade Sending→Sent, legacy echo fallback); `processSendCompleteEvent` (eviction/failure + watchdog cancel); `dismissPendingEcho` |
| `EditVersionCoordinator.kt` | `addNewEventToChain` — deduplication + pending echo eviction when confirmed event arrives via sync before send_complete |
| `utils/NetworkUtils.kt` | Calls `processSendCompleteEvent(event, error)` — passes outer `error` field from `send_complete` |
| `utils/ReplyFunctions.kt` | Derives `isPendingEcho`, `isFailedEcho`; applies bubble colors; disables actions; wires `effectiveOnDelete` |
| `utils/MessageMenuBar.kt` | "Send Error" dropdown item + `AlertDialog` |
| `RoomTimelineScreen.kt` | Sort fix for `timelineRowid = 0`; `stableKey` uses `eventId` (not `transactionId`) to prevent duplicate LazyColumn keys |
| `BubbleTimelineScreen.kt` | Sort fix for `timelineRowid = 0`; `stableKey` uses `eventId` (not `transactionId`) to prevent duplicate LazyColumn keys |
