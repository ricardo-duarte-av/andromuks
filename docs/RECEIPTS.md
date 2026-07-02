# Read Receipts Architecture

## Overview

Read receipts show which users have read up to a given message. Each user has exactly one read receipt per room — it always sits on the latest event they have read. Avatars are rendered inline next to message bubbles in `TimelineEventItem`.

```
Matrix sync_complete / paginate
    ↓
ReceiptFunctions  (processing logic)
    ↓
AppViewModel.readReceipts  (global in-memory map: eventId → List<ReadReceipt>)
    ↓
ReadReceiptCache  (singleton, survives ViewModel recreation)
    ↓
TimelineEventItem  (renders InlineReadReceiptAvatars / AnimatedInlineReadReceiptAvatars)
```

## Data Model

`ReadReceipt` (defined in `TimelineEvent.kt`):
- `userId` — Matrix user ID of the reader
- `eventId` — the event they last read
- `timestamp` — receipt timestamp
- `receiptType` — e.g. `m.read`
- `roomId` — which room this receipt belongs to (used to prevent cross-room corruption)

`AppViewModel.readReceipts` is a **global** `MutableMap<String, MutableList<ReadReceipt>>` keyed by `eventId`. It is **not** per-room; all rooms share the same map. Cross-room safety is enforced by checking `ReadReceipt.roomId` during update operations.

## Invariant: One receipt per user per room

A user must appear on **at most one event** across the entire `readReceipts` map for a given room. Every write path must enforce this:

- `processReadReceiptsFromSyncComplete` — scans all events and removes the user's old receipt before adding the new one.
- `processReadReceiptsFromPaginate` — authoritative per-event replacement (called via `TimelineCacheCoordinator`, not via `ReceiptFunctions`).
- `populateReadReceiptsFromCache` — additive merge that **must** evict the user from other events before placing them on the new event (see fix below).

## Two Update Paths

### 1. `sync_complete` — incremental, moves receipts

Called from `SyncRoomsCoordinator.processParsedSyncResult` for rooms that are actively cached or currently open.

`ReceiptFunctions.processReadReceiptsFromSyncComplete`:
1. For each receipt in the payload, search `readReceipts` for the user's existing entry (same room).
2. If found on a different event, mark it for removal and record the old event for animation.
3. Remove all marked receipts.
4. Add the receipt to the new event.
5. Fire `onMovementDetected` callback to trigger slide animation.

After processing all rooms, calls `ReadReceiptCache.setAll(readReceipts)` once.

**Gated per room** — only processed when `RoomTimelineCache.isRoomActivelyCached(roomId) || currentRoomId == roomId`. Rooms that have never been opened have no timeline cache, so receipts for them are skipped; paginate provides authoritative receipts when those rooms are first opened.

A previous guard (`initialSyncProcessingComplete`) was removed because it was set asynchronously inside `onInitComplete()`'s launched coroutine, creating a race on reconnect: the first resume sync_complete could arrive while `initialSyncProcessingComplete` was still `false`, silently dropping receipts for already-cached rooms. The per-room cache check is both sufficient and race-free.

### 2. Paginate — authoritative, per-event replacement

Handled inline in `TimelineCacheCoordinator` (not via `ReceiptFunctions.processReadReceiptsFromPaginate`). For each event returned by paginate:
- Build `authoritativeReceipts: Map<eventId, List<ReadReceipt>>` from the server response.
- Apply to `readReceipts` on the main thread inside `synchronized(readReceiptsLock)`: replace per-event, remove empty events.
- Calls `ReadReceiptCache.setAll(readReceipts)` after all changes.

Paginate is considered authoritative for the events it returns. A user appearing on event $X in a paginate response means their latest read is $X — but paginate does **not** explicitly say "remove user from $Y". Cross-event dedup is therefore the responsibility of each write path.

## `ReadReceiptCache` — singleton across ViewModel instances

`ReadReceiptCache` mirrors the full `readReceipts` map and is updated (via `setAll`) after every paginate and every sync_complete batch. It allows a new `AppViewModel` instance (e.g., after activity recreation, or a bubble VM) to recover the full receipt state without waiting for the next sync.

`populateReadReceiptsFromCache` is called:
- On VM `init` (readReceipts is empty — straightforward add from cache).
- On `RoomListSingletonReplicated` (for secondary/bubble VMs — readReceipts may already be populated from a prior cache load, so stale positions must be evicted before adding).

## Known Bug Fixed: Receipt Accumulation

**Symptom:** A user's read receipt avatar appeared on multiple message bubbles simultaneously instead of moving.

**Root cause:** `populateReadReceiptsFromCache` was a pure additive merge. It checked "is this user already on THIS event?" but never removed them from other events. When the cache was refreshed (user X moved from $eventOld → $eventNew via sync_complete), a subsequent call to `populateReadReceiptsFromCache` (e.g. from `RoomListSingletonReplicated`) would add user X to $eventNew while leaving them stranded on $eventOld.

**Fix (in `ReadReceiptsTypingCoordinator.populateReadReceiptsFromCache`):** Before placing any receipt from the cache onto an event, call `evictUserFromOtherEvents` which scans all other entries in `readReceipts` and removes the user (same-room check: `existing.roomId.isBlank() || existing.roomId == receipt.roomId`). This mirrors the dedup logic in `processReadReceiptsFromSyncComplete`.

## Bridge Receipt Remapping

Matrix bridge bots (e.g. mautrix) send `m.read` receipts for their own `com.beeper.message_send_status` events, which never appear in the timeline. Both the sync_complete and paginate paths remap these to the original message event ID using `AppViewModel.bridgeStatusEventToMessageId`. A remapped receipt also triggers an implicit `"delivered"` status update for the message.

## Receipt Flattening (nearest rendered event)

A user's `m.read` receipt always sits on the **very last event they interacted with** — which is frequently *not* an event the client renders as a standalone, avatar-hosting row: a reaction, a redaction, an edit (`m.replace`), a bridge `com.beeper.message_send_status` event, or a membership event the user has chosen to hide. Without flattening, the avatar would key to an event id that no bubble looks up and silently disappear.

Mirroring webmuks' `receipt_flattening`, the app collapses such receipts onto the **nearest rendered event at or before** the true target. Key properties:

- **`readReceipts` stays authoritative** — receipts remain keyed by their true event id. Flattening is a *display-time* remap only, so the one-receipt-per-user-per-room invariant and the ingestion paths are untouched.
- **The anchor is the rendered set, not a fixed type list.** Anchors are exactly the events that render given the current filter settings — derived from `sortedEvents` minus `m.reaction` (which is whitelisted but skipped at render). Because that set already resolves all four show/hide preference scopes (`showHiddenEvents`, `showMembershipEvents`, `renderContextEvents`, …), flattening is automatically settings-aware: turning **show membership events** on makes a membership narrator its own anchor and moves the avatar onto that narrator line; turning it off flattens the receipt onto the previous message bubble.
- **Computed at timeline-build time.** In the `produceState` block of both `RoomTimelineScreen` and `BubbleTimelineScreen`, one pass walks the full ordered timeline (`timelineEvents.sortedWith(timelineOrder)` — the same comparator that orders the rendered list): each rendered event becomes the current anchor; each non-rendered event appends its id to that anchor's absorbed list. The result is attached to each rendered row as `TimelineItem.Event.absorbedReceiptEventIds` (and `BubbleTimelineItem.Event`).
- **Gathered at the single choke point.** `ReceiptFunctions.gatherFlattenedReceipts(anchorEventId, absorbedEventIds, roomId, map)` looks up the anchor's own receipts plus every absorbed event's receipts, applies the cross-room guard, and dedups by user. Used by both the bubble path (`TimelineEventItem`) and the narrator path (`SystemEventNarrator`).

An edge case matching webmuks: a receipt on a non-rendered event that sorts *before* the first rendered row in the loaded window has no anchor and stays orphaned until pagination brings a rendered event before it — the same behavior as the unread divider.

This generalises the older `bridgeStatusEventToMessageId` remap (see below), which remains as an ingestion-time fast path; the two are additive and dedup by user, so no double counting.

## Rendering

`AnimatedInlineReadReceiptAvatars` (in `ReceiptFunctions.kt`) wraps `InlineReadReceiptAvatars` with enter/exit animations keyed on `receiptAnimationTrigger`. Avatars are computed in `TimelineEventItem` via:

```kotlin
val readReceipts = remember(event.eventId, event.roomId, appViewModel?.readReceiptsUpdateCounter) {
    ReceiptFunctions.getReadReceipts(event.eventId, appViewModel.getReadReceiptsMap())
        .filter { it.eventId == event.eventId && (it.roomId == event.roomId || it.roomId.isBlank()) }
}
```

The `roomId` filter prevents cross-room leakage if two rooms happen to share an event ID.

The message sender is always excluded from the displayed avatars (`filteredReceipts = receipts.filter { it.userId != messageSender }`).

Up to 3 avatars are shown; a `+N` chip appears for the remainder.

## Threading

All writes to `readReceipts` are guarded by `synchronized(readReceiptsLock)`. Paginate applies its changes on the main thread (inside `withContext(Dispatchers.Main)`) after computing the diff on a background thread. Sync_complete processes receipts on whatever coroutine the sync pipeline runs on, also inside the lock.
