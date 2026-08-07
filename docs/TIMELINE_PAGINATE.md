# Timeline Paginate Routing (`TimelineCacheCoordinator`)

> **Performance traces:** the room-open probe and full paginates are wrapped in Firebase Performance
> traces (`open_room_probe`, `open_room_full` with a `trigger` attribute of `no_cache` / `sparse_cache`
> / `probe_stale`). If you add or move a room-open paginate dispatch/drop site, keep the
> `startOpenRoomTrace` / `stopOpenRoomTrace` calls paired. See
> [OBSERVABILITY.md](OBSERVABILITY.md#custom-websocket-rpc-traces-requestresponse).

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

## The Anchored Freshness Probe Must Always Terminate

There are **two** freshness probes, and they are not interchangeable:

| Probe | Fired by | Transport / tracking | Response handler |
|---|---|---|---|
| Rowid probe (`limit=1`) | `requestRoomTimeline` cache-hit / LRU-restore | WS, `freshnessCheckRequests` | `handleFreshnessCheckResponse` |
| **Anchored** probe (`limit=FRESHNESS_PROBE_LIMIT`) | `navigateToRoomWithCache`, `mightBeStale` branch | **`/exec`**, `paginateRequests` + `freshnessProbeAnchors` | `handlePaginationMerge` fast path |

The anchored probe is the one that runs after an intentional WS drop, and the open path commits to it
before it is sent: `timelineEvents` is emptied and `isTimelineLoading` left true, on the promise that a
probe *response* will build the verified timeline. So every way the probe can end must reach that
promise. Three did not, and all three ended in a room stuck on a spinner until it was re-entered:

1. **`/exec` pre-flight failure.** `ExecCommandCoordinator.execute` returned early — logging only — when
   the app context or credentials were missing, *before* calling `register`. Nothing was in flight and
   nothing knew. It now allocates and registers first, so a pre-flight failure takes the same
   `handleError` path as a network one.
2. **Error frames.** The anchored probe lands in `paginateRequests`, whose error branch only cleared
   `isPaginating` — not the anchor, the pending epoch, or the loading flag (contrast the
   `freshnessCheckRequests` branch right below it, which does all three).
3. **Empty responses.** `processEventsArray`'s zero-displayable-events early return never reaches
   `handlePaginationMerge`, so the anchor and epoch entries leaked.

All three now funnel into `recoverFromFailedFreshnessProbe`, which releases the bookkeeping, renders the
cached window if there is one (an unverified tail beats an indefinite spinner) or clears
`isTimelineLoading` if the reseed already purged it, and **leaves `mightBeStale` set** — we learned
nothing about this cache, so the next open must probe again.

**Consuming the pending epoch is gated on `requestId < 0`** (or on the request actually holding an
anchor). The escalated reseed carries no anchor and is identifiable only by transport: `/exec` ids are
negative, user pull-to-paginate ids are positive. Without that gate a failed backward-scroll paginate
consumes the epoch of a probe that is still alive, and that probe's terminal merge then never clears
`mightBeStale`.

### Duplicate Anchored Probes Corrupt the Cache

A single room open invokes the open path **twice** — the `RoomListScreen` tap and `RoomTimelineScreen`'s
mount effect, whose `isAlreadyLoaded` guard is defeated by the `forceFreshPaginateAfterWsDown` that a
batterySaver resume always sets. `sendFreshnessProbe` has dedupped on `freshnessCheckRequests` for
exactly this reason since it was written; the anchored branch called `paginateViaExec` directly and had
no guard.

Two probes produce two GAP verdicts, and the loser's `clearTimelineEventsForReseed` lands *after* the
winner's reseed has already refilled the cache — wiping it again. `freshnessProbePendingEpoch[roomId]`
is now the in-flight signal: it covers the whole probe → reseed → terminal-merge lifecycle (the merge
removes it, and so does every failure path above), so the second open skips and lets the first finish.

## Thread Safety of Request Maps

The four request-tracking maps (`timelineRequests`, `paginateRequests`, `paginateRequestMaxTimelineIds`, `backgroundPrefetchRequests`) are declared as `ConcurrentHashMap` in `AppViewModel`. This is required because `handleResponse` runs on `Dispatchers.Default` (see below), so these maps are written from a background thread while potentially being read from other coroutines.

## Critical Invariant: The Connection-Loss Purge Must Only Touch **Positive** Request IDs

`TimelineCacheCoordinator.onConnectionLost(epoch)` runs when the socket dies and drops every request-map
entry belonging to the dead connection. It filters to `requestId > 0` — this is not defensive, it is
required.

`/exec` allocates **negative** ids via `WebSocketService.allocateExecRequestId()` specifically so an
in-flight HTTP response survives a reconnect (the WS counter is reset to 0 on every `setWebSocket`, so
positive ids would collide). `ExecCommandCoordinator` writes those negative ids straight into
`paginateRequests`. Purging them would orphan a battery-saver paginate that is still perfectly capable
of completing. Likewise `roomsWithPendingPaginate` is released only for rooms that have **no** remaining
entry in any of the four maps, so a room still riding an `/exec` id keeps its duplicate-suppression
reservation.

**The `epoch` guard.** `WebSocketService.currentConnectionEpoch()` is bumped inside
`resetRequestIdCounter()`, in lockstep with the id space. The purge captures the epoch at teardown and
re-checks it before acting. Without it: request ids restart at 1 on every socket, the Main-thread half
of the purge runs on a queued task, and on a weak link the replacement dial can open and issue ids
`1..n` in that window — so the purge would delete the **new** connection's entries. For the same reason
the concurrent-map half runs *synchronously* from `clearWebSocket`
(`AppViewModel.onWebSocketTornDownSync`) rather than waiting for the Main hop; only thread-safe state
may be touched there.

**Why the purge exists at all.** These requests set their in-flight state *before* the send and clear it
*only* from the response handler, and there is deliberately no wall-clock timeout
(docs/RPC_RESILIENCE.md — "completion is an event, never a clock"). A `paginate` in flight when the
socket died therefore left `isPaginating` true forever, and because
`requestPaginationWithSmallestRowId` early-returns on that flag, pagination was dead for **every** room,
process-wide, until the app restarted.

**The parked set is not a queue you may drain-and-clear.** `drainDeferredRoomPaginates` removes only
the rooms it actually retries; rooms without a live surface stay parked. It runs on every "backend
reachable" transition, and the earliest of those (`setWebSocket`) fires the instant the socket opens —
on a notification cold-open that is normally *before* the deferred navigation lands, so the room being
opened is not yet `currentRoomId`. A snapshot-and-clear drain therefore discards exactly the entry
that mattered, and `onInitComplete` then finds nothing. Because the deferred attempt left
`isTimelineLoading = true`, `RoomTimelineScreen`'s `isAlreadyLoaded` guard reads that as "a load is
already running" and issues nothing — a permanently blank timeline that only re-entering the room
fixes (dispose clears `currentRoomId`, so the guard fails and the normal open path runs). This shipped
once; don't reintroduce it.

**The drain retries the foreground room only.** It must not reissue for a room that is merely
"opened" (`RoomTimelineCache.isRoomOpened`, which also covers bubbles). A reissue for a non-foreground
room reserves `roomsWithPendingPaginate`, and its response seeds cache *without rendering* because
`handleInitialTimelineBuild` builds only when `roomId == currentRoomId`. If navigation to that room
then lands a moment later — the normal case on a notification tap, where the previous room is still on
screen when the socket comes up — the real open hits the "paginate already pending" guard, which
empties `timelineEvents`, sets `isTimelineLoading = true` and sends nothing. The room stays blank until
re-entered. This shipped once, as a widening added on a guess; don't reintroduce it.

Since no drain signal is guaranteed to fire *after* navigation lands, the room open itself is the last
certain claimant: `RoomTimelineScreen` calls `AppViewModel.claimDeferredPaginate(roomId)` and ORs the
result into `mustFetchFreshTimeline`, which defeats the `isAlreadyLoaded` guard. That is what covers
bubbles and any other non-foreground surface.

`isTimelineLoading` is deliberately **not** cleared for parked rooms: they are queued into
`roomsAwaitingInitCompletePaginate` for reissue by `AppViewModel.drainDeferredRoomPaginates`, exactly
like the "WebSocket was already down at send time" branch. Clearing it with an empty `timelineEvents`
would repaint the loader gate as an *empty room* instead of a spinner. Backward-history
`paginateRequests` are **not** reissued — that was a scroll gesture whose intent is gone, and replaying
it would fight scroll-anchor restoration.

## Auto-Pagination (Buffer Refill)

`RoomTimelineScreen` and `BubbleTimelineScreen` each contain a `LaunchedEffect(listState, roomId)` with a `snapshotFlow` that monitors how many rendered events sit above the viewport. With `reverseLayout=true`, "above" means items with index > last visible index. The count comes from `listState.layoutInfo.totalItemsCount`, i.e. it is **post-filter** — hidden and membership events do not count toward it and are not fetched toward it.

**Burst model** (three constants, declared next to the effect):

| Constant | Value | Meaning |
|---|---|---|
| `REFILL_TRIGGER` | 10 | Arm a burst when the buffer falls to this many items above the viewport |
| `REFILL_TARGET` | 50 | Keep fetching until at least this many items sit above the viewport |
| `MAX_REFILL_ROUNDS` | 20 | Safety cap on rounds per burst |
| `initialFillTarget` | 15 | Target for a burst armed from an *empty* timeline (see below) |
| `lowYieldRound` | 5 | A round adding fewer renderable items than this counts as unproductive |

**Two targets.** A burst armed from `total == 0` is filling the screen, not building a scroll
buffer, and stops at `initialFillTarget`. Chasing the full `REFILL_TARGET` there costs roughly ten
extra round-trips on a sparse room and holds the progress bar up long after the messages are
readable. Once the user scrolls up and the buffer falls through `REFILL_TRIGGER`, the falling edge
arms an ordinary `REFILL_TARGET` burst.

**Arming.** Primarily falling-edge (`prevItemsAbove > REFILL_TRIGGER && itemsAbove <= REFILL_TRIGGER`) — edge detection, not a level check, so a burst that hit the cap cannot instantly re-arm while still below the trigger. Two additional entries exist because neither can ever produce a falling edge:

- `total == 0` — nothing rendered at all. This is the heavily-filtered room that needs digging most.
- `lastVisible >= total - 1` — the user is parked at the top of what is loaded, so scrolling up resumes fetching after a capped burst.

All three are suppressed while `refillStalled` (see below).

**Per-round request** (all must hold): `hasLoadedInitialBatch && hasInitialSnapCompleted`, `!pendingScrollRestoration`, `!isPaginating`, `!isTimelineLoading`, `hasMoreMessages`, and `roomId == currentRoomId` (guards stale composition during the navigation crossfade). It captures the same scroll anchor as pull-to-refresh and calls `requestPaginationWithSmallestRowId(roomId, limit = roundLimit)`.

**Escalating page size.** `roundLimit` starts at 100 and widens to 250 then 500 as `barrenRoundStreak` grows. A round is "barren" when it adds fewer than `lowYieldRound` renderable items — the signature of a long stretch of hidden membership events. Zero-yield alone is too strict: a barren stretch dribbling out two or three messages per 100 events would never escalate. Any round that yields rows resets the streak. This crosses a months-long membership desert in a handful of round-trips instead of twenty.

**Stall + manual escape.** When a burst ends with `hasMoreMessages` still true and the buffer still below target, `refillStalled` is set. It suppresses auto re-arming (otherwise the cap buys nothing: each round's `isPaginating` false-edge would re-arm the burst and a barren room would loop forever) and drives a "Load older messages" affordance under the room header. The stall clears when the buffer recovers above `REFILL_TRIGGER` or when the user taps that affordance.

`INITIAL_ROOM_PAGINATE_LIMIT` is **100** (not 50) so a normal room load provides enough rendered events that auto-pagination does not fire immediately on open.

### Postmortem: the zero-renderable deadlock

Group rooms hide membership events by default (`resolveShowMembershipEvents`). An archived, read-only room whose recent history is nothing but join/leave events therefore produced a 100-event window that filtered down to **zero** renderable items, and the app hung on a permanent "Room loading…".

Two independent guards conspired:

1. Every assignment of `hasInitialSnapCompleted` sat behind `timelineItems.isNotEmpty()`, and the loader gate was `timelineItems.isEmpty() && (isLoading || !hasInitialSnapCompleted)`. With no items the flag never flipped, so the loader never released.
2. The refill effect required `hasInitialSnapCompleted` **and** `timelineItems.isNotEmpty()`, so it could not paginate out of the barren window either.

The fixes, which must not be undone:

- The initial-scroll effect has an explicit empty-completion branch: once `readinessCheckComplete && !isLoading` with zero items, it sets `hasInitialSnapCompleted`/`hasLoadedInitialBatch` and skips the scroll.
- The loader gate ANDs `!hasInitialSnapCompleted` with `isLoading` instead of ORing it, so the loader releases once the first batch lands regardless of item count.
- **The refill effect must never regain a `timelineItems.isNotEmpty()` guard.** `total == 0` with `hasMoreMessages == true` is precisely the case it exists to paginate through; `roomId == currentRoomId` covers the navigation-crossfade concern that guard was standing in for.
- An empty timeline renders a `TimelineEmptyState` ("Looking for older messages…" while refilling, otherwise "No messages to show" plus a load-older button) rather than a blank list, and the header progress bar is driven by `isPaginating || isRefillingBuffer` so background rounds are visible.

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
