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

## Critical Invariant: Catch-Up Merge Is Contiguity-Gated (Merge vs Drop-and-Replace)

`handleBackgroundPrefetch` fetches the **latest** events (`max_timeline_id = 0`). Before folding them into the cache it must decide whether the fetched window is *contiguous* with the cached window — and the decision is made by **shared event id**, not by rowid:

```kotlin
val existingIds = RoomTimelineCache.getCachedEventIds(roomId)   // regular events only
val contiguous = existingIds.isEmpty() || timelineList.any { it.eventId in existingIds }
if (contiguous) RoomTimelineCache.mergePaginatedEvents(roomId, timelineList)   // append, keep history
else            RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, timelineList) // drop stale, replace
```

- **Shares ≥1 id (or cache empty) → merge.** Fewer than a page of new events arrived while we were away, so the latest window reaches back far enough to re-include the cache's newest event(s). The shared event stitches the two windows with no hole; append and keep the older history.
- **Shares nothing while cache is non-empty → gap → drop and replace.** More than a full page (`INITIAL_ROOM_PAGINATE_LIMIT`) arrived while backgrounded, so the fetched window sits entirely above the cached one with unfetched events in between.

**Why a blind `mergePaginatedEvents` is wrong here (do not "simplify" back to it):** `addEventsToCache` dedupes by id and sorts by `timelineRowid`. Appending a non-contiguous window therefore produces a timeline that *looks* continuous but has a silent hole — e.g. cache `1–100` + response `300–350` renders as one list with `101–299` missing and **no gap marker**. Worse, backward-paginate keys off the *oldest* cached rowid, so scrolling up fetches below `#1` and can never refetch `101–299`. Dropping the stale window (Option 1) keeps the timeline honest; the user re-paginates upward to refetch history on demand. The gap branch logs at `Log.i` ("non-contiguous … dropping stale cache") because it is rare and worth seeing in a logcat dump.

Why id-overlap rather than a rowid-range compare: rowids are not consecutive (state events, redactions, reactions consume them too), so a `responseOldestRowid > cacheNewestRowid` test is fiddly and error-prone. A shared id is exact.

**Note:** the same gate also covers the notification-open background merge — it routes through `handleBackgroundPrefetch` too.

## Warm Re-open Must Not Wipe the Timeline (`forceFreshPaginate`)

When the WebSocket was down at room-open time (`needsFreshTimelinePaginate()` true — batterySaver linger, cold resume), the room still re-opens with a populated cache via delta-replay reconnect. The cache-render branch in `requestRoomTimeline` is therefore **not** gated on `!forceFreshPaginate`: a non-empty cache renders immediately and a `backgroundPrefetchRequests` paginate merges newer events on top (see the contiguity gate above). Only a genuinely empty cache falls through to the foreground `timelineRequests` paginate, where the full-screen loader is the correct state.

Two companion guards keep this flash-free:
- `navigateToRoomWithCache` only does `timelineEvents = emptyList()` when `getCachedEventCount(roomId) == 0`. Clearing with a cache present would produce a one-frame "empty room" flash before the async rebuild swaps the events back.
- `RoomTimelineScreen`'s loader gate is `!readinessCheckComplete || (timelineItems.isEmpty() && (isLoading || !hasInitialSnapCompleted))` — a bare `isTimelineLoading=true` (briefly true during the async rebuild) must never paint the spinner over an already-populated list. Room *switches* clear `timelineEvents` synchronously, so `timelineItems.isEmpty()` still gates the loader correctly on a true room change.

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
