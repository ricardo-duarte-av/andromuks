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

**Skipped during initial sync** (`!initialSyncProcessingComplete`) — paginate provides authoritative receipts when rooms are first opened, so processing sync receipts before the timeline is loaded is wasted work.

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
