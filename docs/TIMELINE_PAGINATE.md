# Timeline Paginate Routing (`TimelineCacheCoordinator`)

## Three Request Maps

`TimelineCacheCoordinator` maintains three maps for in-flight paginate requests:

| Map | Use case | Response handler | `clearExisting` |
|---|---|---|---|
| `timelineRequests` | Initial room-open paginate (true fresh load, no cached events) | `handleTimelineResponse` → `buildEditChainsFromEvents` | `true` — clears `eventChainMap` and rebuilds from response only |
| `backgroundPrefetchRequests` | Catch-up / background paginate | `handleBackgroundPrefetch` → merges into `RoomTimelineCache`, then `processCachedEvents(getCachedEventsForTimeline(roomId))` | n/a — rebuilds from full merged cache |
| `paginateRequests` | User-triggered pull-to-paginate (older history) | `buildEditChainsFromEvents` | `false` — appends to existing `eventChainMap` |

## Critical Invariant: Catch-Up Paginates Must Use `backgroundPrefetchRequests`

The two "catch-up" paginates sent at room-open time in `requestRoomTimeline` must **never** use `timelineRequests`:

1. **Cache-hit path** (room has cached events): sends a paginate to fetch any newer events from the server. The cached events are already showing; this paginate only fills gaps.
2. **LRU-restore path** (room restored from LRU): sends a paginate to pull events the LRU restore may have missed.

**Why this matters:** If tracked as `timelineRequests`, the response calls `buildEditChainsFromEvents(clearExisting=true)`, which clears `eventChainMap` and loses any events that arrived via `sync_complete` in the window between when the paginate was sent and when the response arrived.

**Visible symptom in bridge rooms:** The bridge delivers messages with a delay relative to when the server processes the paginate. Messages from the other party vanish after the user sends a new message. Reopening the room restores them (because `processCachedEvents` reads from the full cache).

## Thread Safety of Request Maps

The four request-tracking maps (`timelineRequests`, `paginateRequests`, `paginateRequestMaxTimelineIds`, `backgroundPrefetchRequests`) are declared as `ConcurrentHashMap` in `AppViewModel`. This is required because `handleResponse` runs on `Dispatchers.Default` (see below), so these maps are written from a background thread while potentially being read from other coroutines.

## Auto-Pagination (Scroll-Triggered)

`RoomTimelineScreen` and `BubbleTimelineScreen` each contain a `LaunchedEffect(listState, roomId)` with a `snapshotFlow` that monitors how many rendered events are above the viewport. With `reverseLayout=true`, "above" means items with index > last visible index.

**Trigger condition** (all must be true):
- Fewer than **60 rendered events** above the viewport
- `hasLoadedInitialBatch && hasInitialSnapCompleted` — not during initial room load
- `!pendingScrollRestoration` — not mid-pagination anchor restore
- `!appViewModel.isPaginating` — no request already in flight
- `appViewModel.hasMoreMessages` — server has not signalled end of history

When triggered, it captures the same scroll anchor as pull-to-refresh (`highestVisibleIndexBeforePagination`, `anchorScrollOffsetForRestore`, `expectedTimelineSizeBeforePagination`) and calls `requestPaginationWithSmallestRowId(roomId, limit = 100)`. The existing scroll-restoration path handles re-anchoring after events arrive.

**Loop behaviour for heavily-filtered rooms:** After each paginate settles, `timelineItems` changes and `snapshotFlow` re-evaluates. If the received batch produces few rendered events (many are reactions, call state, redactions, etc.) and the threshold is still not met, it fires again automatically — until the threshold is met or `hasMoreMessages` becomes false.

`INITIAL_ROOM_PAGINATE_LIMIT` is **100** (not 50) so the initial room load provides enough rendered events that auto-pagination does not fire immediately on open.

## Response Processing Thread

`handleResponse` (and therefore `handleTimelineResponse` + `handlePaginationMerge`) runs on **`Dispatchers.Default`**, not `Dispatchers.Main`. This keeps JSON parsing, `eventChainMap` rebuilds, and cache operations off the UI thread.

Compose `mutableStateOf` writes (`isPaginating`, `hasMoreMessages`, `isTimelineLoading`, etc.) are thread-safe from any thread — the snapshot system buffers them and applies on the next composition frame on Main. No `withContext(Dispatchers.Main)` wrappers are needed for these writes.

## `eventChainMap` Synchronization

`handlePaginationMerge` holds `synchronized(eventChainMap)` for the entire clear-and-rebuild block:

```
synchronized(eventChainMap) {
    eventChainMap.clear()
    editEventsMap.clear()
    // rebuild loop
    processVersionedMessages(...)
    processEditRelationships()
}
buildTimelineFromChain()  // outside lock — async, manages its own synchronized read
```

This is necessary because `buildTimelineFromChain` (also on `Dispatchers.Default`) takes its own `synchronized(eventChainMap)` snapshot when it runs. Without the write-side lock, it could snapshot a half-rebuilt map. `buildTimelineFromChain` is called **outside** the lock because it is async — it fires a new coroutine and returns immediately.

## Cache Trim Threshold Invariant

`RoomTimelineCache.MAX_EVENTS_PER_ROOM` (the per-room cap applied to *closed* rooms on every `addEventsToCache` / `mergePaginatedEvents`) **must equal `AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT`** (currently 100).

**Why they must match:** `navigateToRoomWithCache`'s cache-fast-path gate is `cachedEventCount >= INITIAL_ROOM_PAGINATE_LIMIT`. If `MAX_EVENTS_PER_ROOM` is smaller than the paginate limit, every closed room's cache gets trimmed below the gate as soon as a non-current room is touched by a sync_complete. The next visit then has to fall through to `requestRoomTimeline` and re-paginate — even though the events it would re-fetch are still in the (now half-page) cache.

**Historical bug:** A previous commit raised `INITIAL_ROOM_PAGINATE_LIMIT` from 50 → 100 but left `MAX_EVENTS_PER_ROOM` at 50, with a stale "matches initial paginate limit (= 50)" comment. Closed rooms were silently truncated to half a page, all room-reopens missed the cache-fast-path, and every reopen issued a full paginate-then-merge that returned 100% duplicates. Raised to 100 to match; comment now explicitly tells future readers to keep the two in sync.

**Opened rooms are exempt:** `isRoomOpened(roomId)` (RoomTimelineScreen current room + any BubbleTimelineScreen bubbles) bypasses the trim entirely — their caches grow unboundedly within the room session and are saved to LRU on navigation away.
