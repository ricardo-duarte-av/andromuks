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
