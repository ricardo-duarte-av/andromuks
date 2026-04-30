# Timeline Events — Rendering Rules, Ordering & Edge Cases

## What Gets Rendered in the Timeline

Only events with a **positive `timelineRowid`** (`> 0`) are treated as real timeline entries. Events with `timelineRowid <= 0` are state/profile data and must not be rendered.

| `timelineRowid` value | Meaning | Action |
|-----------------------|---------|--------|
| `> 0` | Real timeline event (persisted in DB) | Add to `eventChainMap`, render |
| `0` | Not in timeline (profile hint, state event sent alongside a message) | Update caches only, do NOT render |
| `-1` | State-only event (old sentinel) | Update caches only, do NOT render |

### Resolution Step (`resolveTimelineRowidsFromRoomData`)

In `sync_complete`, many events arrive with `timeline_rowid: 0`. The room data includes a separate `timeline` array that maps `event_rowid → timeline_rowid`:

```json
"timeline": [{"timeline_rowid": 2007101, "event_rowid": 2731132}]
```

`resolveTimelineRowidsFromRoomData` patches each event's `timeline_rowid` in-place using this mapping **before** the events array is processed. Events whose `rowid` does not appear in the mapping keep `timeline_rowid: 0` — they are not timeline events.

**Profile-hint member events** are the canonical example: the backend includes `m.room.member` events alongside messages so the app can resolve the sender's display name/avatar without a separate request. Their `rowid` is not in the `timeline` mapping so they stay at `timeline_rowid: 0` after resolution.

## Event Types and Their Handling

| Type | `timelineRowid > 0` | `timelineRowid <= 0` |
|------|---------------------|----------------------|
| `m.room.message`, `m.room.encrypted`, `m.sticker` | Rendered | Should not occur; would not be rendered |
| `m.room.member` | Rendered as join/leave/invite narrator row | Cache-only (display name + avatar updated in `RoomMemberCache` and `ProfileCache`) |
| `m.room.redaction` | Added to chain (needed for `findLatestRedactionEvent`) | — |
| `m.room.pinned_events`, `m.room.name`, `m.room.topic`, `m.room.avatar` | Rendered as narrator row | — |
| `m.reaction` | Not added to chain; processed by reaction coordinator (see [docs/REACTIONS.md](REACTIONS.md)) | — |

## Sort Order

Timeline events are sorted by `timelineRowid` when it is positive (server-defined order); when `timelineRowid` is 0 or -1 (unresolved), events fall back to `timestamp` ordering. Applied in both `RoomTimelineScreen.kt` and `BubbleTimelineScreen.kt`:

```kotlin
Comparator { a, b ->
    if (a.timelineRowid > 0L && b.timelineRowid > 0L) {
        val cmp = a.timelineRowid.compareTo(b.timelineRowid)
        if (cmp != 0) return@Comparator cmp
    }
    compareValuesBy(a, b, { it.timestamp }, { it.eventId })
}
```

Pending echoes (`~`-prefixed `eventId`) have `timelineRowid = 0` and are therefore sorted by timestamp until `sync_complete` delivers their confirmed event with a real rowid — no special-casing needed.

Note: `EventContextScreen.kt` uses a simpler `compareBy({ it.timelineRowid }, ...)` sort — no pending echoes expected there.

## Pending Local Echoes

Pending echoes are synthetic events inserted client-side before the server round-trip completes. They are identified by `event_id` starting with `~`. See **[docs/MESSAGE_SENDING.md](MESSAGE_SENDING.md)** for the full lifecycle.

Key properties at insertion time:
- `eventId` starts with `~`
- `timelineRowid = 0` (not in DB)
- `timestamp` = time of send (used as tiebreak in the sort above)

## The `timelineRowid <= 0` Gate — Enforcement Points

The "do not render" rule for `timelineRowid <= 0` member events is enforced at three levels:

### 1. `RoomTimelineCache.addEventsToCache` (primary gate — all paths hit this)

The central choke point. Every event that enters `cache.events` passes through here, regardless of whether it came from `addEventsFromSync`, `mergePaginatedEvents`, `appendEventsToCachedRoom`, or `seedCacheWithPaginatedEvents`.

```kotlin
if (event.type == "m.room.member" && event.timelineRowid <= 0L) {
    val isKick = event.stateKey != null &&
        event.sender != event.stateKey &&
        event.content?.optString("membership") == "leave"
    if (!isKick) continue
}
```

### 2. `RoomTimelineCache.parseEventsFromArray` (pre-filter for the `addEventsFromSync` path)

Redundant with the gate above, but kept as an early exit. Uses the same `<= 0` and kick-exception logic.

### 3. `AppViewModel.processSyncEventsArray` (live-sync path for the currently open room)

Routes `timelineRowid <= 0` member events to a cache-only update (display name / avatar) instead of `addNewEventToChain`.

### Why `SyncIngestor` does not filter

`SyncIngestor.processRoom` collects **all** parsed events into `eventsForCacheUpdate` before passing them to `onEventsForCachedRoom` → `addEventsToCache`. Filtering was intentionally not added there to keep the ingestor simple; the gate in `addEventsToCache` handles it.

### The kick exception

Kicks (`sender != stateKey` + `membership=leave`) with `timelineRowid <= 0` are allowed through. These are state events that represent a user being kicked and should appear as a narrator row in the timeline.

## `eventChainMap` — Central Data Structure

`eventChainMap` is a `LinkedHashMap<String, TimelineEvent>` keyed by `eventId`. It is the single source of truth for everything `buildTimelineFromChain()` renders.

- Managed by `EditVersionCoordinator`
- `addNewEventToChain(event)` deduplicates by `eventId` — safe to call multiple times for the same event
- `buildTimelineFromChain()` / `executeTimelineRebuild()` rebuilds `timelineEvents` state on the Main dispatcher

## `related_events` — Reply-Context Events

The paginate and `sync_complete` responses include a `related_events` array alongside the main `events` array. These are events the backend fetched specifically so the client can render reply previews — they were not part of the current response window.

**Critical rule:** `related_events` must **not** appear as standalone timeline items. They are typically pre-join or out-of-window history events with `timeline_rowid: -1`; inserting them into `cache.events` would cause them to appear at wrong positions in the timeline and create duplicate date-divider keys (crash).

### Storage — `replyContextEvents` bucket

`RoomCache` has a dedicated `replyContextEvents` / `replyContextEventIds` pair. Both paths that receive `related_events` route them here:

| Path | Call |
|------|------|
| Paginate response (`TimelineCacheCoordinator`) | `RoomTimelineCache.addReplyContextEvents(roomId, events)` |
| `sync_complete` (`SyncIngestor`) | `RoomTimelineCache.addReplyContextEvents(roomId, relatedEventsList)` |

`addReplyContextEvents` skips any event already in `cache.eventIds` (already a real timeline event, no context copy needed) and does **not** add to `cache.events`, so these events are invisible to `getCachedEventsForTimeline()`.

### Lookup — `findEventForReply`

Reply preview composables (`TimelineEventItem.kt`) look up the referenced event via:

```kotlin
RoomTimelineCache.findEventForReply(roomId, replyInfo.eventId)
```

This searches `cache.events` first, then `cache.replyContextEvents`, so previews work whether the target is a normal timeline event or a reply-context-only entry.

### Missing reply target detection

After processing a paginate response, the coordinator checks whether any reply targets are still absent and fetches them via `get_event`. The IDs already delivered as `related_events` are included in the "already known" set so they are not re-fetched.

## `timelineRowid` Merge in Cache

When an event already in `cache.events` with an unresolved rowid (`<= 0`) is seen again from a paginate response with a **positive** rowid, the cache updates the stored copy:

```kotlin
if (event.timelineRowid > 0L && existingEvent.timelineRowid <= 0L) {
    updatedEvent = updatedEvent.copy(timelineRowid = event.timelineRowid)
}
```

This handles the case where an event enters the cache via `sync_complete` before the `timeline` mapping can resolve its rowid (stored as `-1`), and the paginate response later brings the real positive rowid. Without this update the event stays permanently at `-1` and sorts incorrectly.

The condition uses `<= 0L` (not `== 0L`) because both `0` and `-1` are "unresolved" sentinels. The incoming value must be `> 0L` to be accepted as authoritative.

## `positiveEvents` Filter in Pagination Tracking

When `TimelineCacheCoordinator` tracks the oldest `timelineRowid` seen in a paginate response (used as the cursor for the next pull-to-refresh), it filters to events with `timelineRowid > 0L`:

```kotlin
val positiveEvents = timelineList.filter { it.timelineRowid > 0L }
val oldestInResponse = positiveEvents.minOfOrNull { it.timelineRowid }
```

Using `!= 0L` here would include `-1` entries, causing `oldestInResponse` to be `-1` and the next pagination request to use an invalid cursor.

## Date Dividers and Sticky Date Indicator

`buildTimelineFromChain()` inserts `TimelineItem.DateDivider` (and `BubbleTimelineItem.DateDivider`) items between consecutive events whose dates differ. These are synthetic UI items with no `timelineRowid`; they are never stored in the cache.

A `StickyDateIndicator` composable (`utils/StickyDateIndicator.kt`) reads the date of the oldest visible item (event or date-divider) and displays a pill-shaped overlay below the header. See **Sticky Date Indicator** in `CLAUDE.md` for full behavioural and layout documentation.

## Reply Jump Navigation

When the user taps the reply-preview banner on a message, `onScrollToMessage` fires with the target `eventId`. Two paths:

### Target is in the current timeline

The target index is found in `timelineItems`. The current scroll position is pushed onto `jumpBackStack` before scrolling:

```kotlin
jumpBackStack.addLast(listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
coroutineScope.launch { listState.scrollToItem(reversedIndex); highlightedEventId = eventId }
```

`BackHandler` (in `RoomTimelineScreen` and `BubbleTimelineScreen`) pops the stack and scrolls back on Back press. **While `jumpBackStack` is non-empty the handler is enabled, which suppresses Android's Predictive Back animation** — the Back gesture performs the in-list scroll-back instead.

### Target is NOT in the current timeline

The event pre-dates the loaded window. NavController navigates to `EventContextScreen`:

```kotlin
pendingEventContextScrollRestore = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
navController.navigate("event_context/$encodedRoomId/$encodedEventId")
```

`EventContextScreen` fetches the event plus ±5 neighbours via `getEventContext` and displays them in a standalone list, scrolled to the target event.

Scroll restoration on return uses a `LaunchedEffect(navController)` + `snapshotFlow` on `currentBackStackEntry?.destination?.route`. When the route reverts to the originating screen's route, `pendingEventContextScrollRestore` is consumed and `listState.scrollToItem(index, offset)` restores the exact position:

| Screen | Route prefix checked |
|--------|---------------------|
| `RoomTimelineScreen` | `room_timeline` |
| `BubbleTimelineScreen` | `chat_bubble` |
| `ThreadViewerScreen` | `thread_viewer` |

This path does **not** add a `BackHandler`, so Android's Predictive Back animation works normally when navigating through `EventContextScreen`.

## Known Gaps

- `m.room.member` profile-hint events also flow through `updateMemberProfilesFromEvents` (line ~5337), which uses `timelineRowid >= 0L` as its filter. This is intentional: profile hints should update the member cache even though they are not rendered. Do not change that filter to `> 0L`.
- `ChatBubbleScreen.kt` sort does not use the `Long.MAX_VALUE` substitution. This is an existing inconsistency — not a bug unless pending echoes are ever supported in bubble windows.
