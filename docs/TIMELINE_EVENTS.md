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
| `m.reaction` | Not added to chain; processed by reaction coordinator | — |

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

## Known Gaps

- `m.room.member` profile-hint events also flow through `updateMemberProfilesFromEvents` (line ~5337), which uses `timelineRowid >= 0L` as its filter. This is intentional: profile hints should update the member cache even though they are not rendered. Do not change that filter to `> 0L`.
- `ChatBubbleScreen.kt` sort does not use the `Long.MAX_VALUE` substitution. This is an existing inconsistency — not a bug unless pending echoes are ever supported in bubble windows.
