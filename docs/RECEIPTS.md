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

## Receipt Accumulation — the invariant every write path must uphold

**Symptom:** A user's read receipt avatar appears on multiple message bubbles simultaneously instead of moving.

**Rule:** every path that *places* a receipt must first *evict* that user from wherever they currently sit, using the per-room inverted index (`readReceiptsIndex`, `roomId → userId → eventId`). Placing without evicting is what strands the old avatar. There is no path for which "the user cannot already be somewhere else" is safe to assume.

The write paths, and where each does it:

| Path | Location |
|---|---|
| sync_complete | `ReceiptFunctions.processReadReceiptsFromSyncComplete` — evicts via `userIndex` |
| cache merge (secondary VMs) | `ReceiptFunctions.mergeCachedReceiptsIntoRoom`, called from `ReadReceiptsTypingCoordinator.populateReadReceiptsFromCache` |
| paginate (authoritative) | `TimelineCacheCoordinator` — paginate never says "remove user from Y", so the dedup is this path's own job |
| bridge remap | `SyncRoomsCoordinator` (both the in-sync and post-sync remaps) — the index write must sit **outside** the per-user dedup guard, or a deduped remap leaves the index pointing at a deleted status event and the *next* move fails to evict |

**This has regressed twice.** It was first fixed by adding `evictUserFromOtherEvents` to both branches of the cache merge. `ebe80e77` ("partition ReadReceiptCache by room; add O(1) inverted index") then replaced that helper with index-based eviction in only *one* of the two branches, and the bug came back.

It came back **bubble-only**, which is what makes it easy to miss: `populateReadReceiptsFromCache` is the only receipt write path a **secondary** ViewModel has. Bubbles and `ShortcutActivity` never process sync receipts themselves — they reload from the replicated singleton cache on every `RoomListSingletonReplicated`, i.e. on every sync. The primary VM calls the same function once at `init`, when its map is still empty, so it never accumulates and the bug is invisible in the main app.

The merge is therefore a pure function with no ViewModel state, so the invariant is unit-tested directly — see `ReceiptMergeTest`. Add a case there for any new write path.

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

## Our Own Read Position (`mark_read`)

Opening a room auto-issues `mark_read` for its newest event. The target and the decision to send are
governed by three invariants, all learned from a room whose unread badge could not be cleared no
matter how many times it was opened.

### 1. The read position is monotonic in `timelineRowid`

`timelineRowid` is gomuks' insertion order and the only ordering authority for a receipt target —
`origin_server_ts` is spoofable, arbitrary on bridged events, and non-monotonic. Every auto-mark path
picks its target with `TimelineCacheCoordinator.latestRowidEventId`, and `decideMarkRead`
(`MarkReadDecision.kt`) rejects any candidate whose rowid is known to be **lower** than the last
position we sent for that room. A rowid of `MarkReadTarget.ROWID_UNKNOWN` (0) means the event wasn't
in the timeline cache; nothing can be concluded about it, so it is allowed through.

Historically several paths supplied timestamp-ordered targets and could rewind the receipt behind a
newer one — leaving the room unread server-side with no way for the room-open path to notice. Those
are gone; if you add a new `mark_read` caller, take its target by rowid.

Notification actions (`markRoomAsReadFromNotification`) carry the event that *fired* the
notification, which for a multi-message notification is not the room's newest event. They resolve to
the newer of that event and the newest event we hold, and they participate in the same last-sent
position, so a notification action and an open-room mark cannot rewind each other.

### 2. A repeat is only redundant while the room is confirmed read

`AppViewModel.lastMarkReadSent` (roomId → `MarkReadTarget`) exists to avoid re-sending the same
position. It used to suppress **every** repeat unconditionally and was never invalidated, so a single
receipt that didn't take wedged the room as unread for the rest of the process: re-opening it cleared
the badge in `roomMap` and sent nothing at all. `decideMarkRead` now suppresses a repeat only when
`AppViewModel.isRoomConfirmedRead(roomId)` — i.e. while the backend agrees the room is read. If the
backend still reports unread, re-opening the room re-sends.

### 3. `RoomListCache` is untouched server truth; `locallyReadRooms` reconciles it

`optimisticallyClearUnreadCounts` clears `unreadCount`/`highlightCount` in `roomMap`, `allRooms` and
`spaceList` — deliberately **not** in `RoomListCache`, which mirrors the backend's counts verbatim
and is therefore what answers "does the server still think this room is unread?" for invariant 2.

`AppViewModel.locallyReadRooms` bridges the two: the optimistic clear adds the room,
`SyncRoomsCoordinator.reconcileLocallyReadFlag` removes it the moment a parsed sync reports
`unread_messages`/`unread_highlights` > 0. `populateRoomMapFromCache` strips the counts for rooms in
that set — without it, the cache copies pre-read counts straight back into `roomMap` and the badge
reappears on the next entry into `RoomListScreen` (once per back-navigation out of the room).

### Diagnosing

Every send and every suppression writes an `Androlog("ReadReceipts", …)` line with room, event,
rowid, receipt type and — for a suppression — the reason (`rewind` / `duplicate`). See
[ANDROLOG.md](ANDROLOG.md); those survive R8 and are readable from the in-app viewer. The
backgrounded-app gate is intentionally **not** logged there: it fires on every sync batch while
backgrounded and would flood the 200-entry ring.
