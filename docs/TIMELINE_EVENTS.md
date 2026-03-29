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

Timeline events are sorted by:

```kotlin
compareBy(
    { if (it.timelineRowid > 0L) it.timelineRowid else Long.MAX_VALUE },
    { it.timestamp },
    { it.eventId }
)
```

The `Long.MAX_VALUE` substitution for `timelineRowid <= 0` places **pending local echoes** (which have `timelineRowid = 0` because they are not yet persisted) at the **newest end** of the timeline. Without this, they would sort to position 0 — the oldest position — and scroll off screen (the `LazyColumn` uses `reverseLayout = true`).

This sort is applied in:
- `RoomTimelineScreen.kt` — `processTimelineEvents`
- `BubbleTimelineScreen.kt` — `bubbleProcessTimelineEvents`

Note: `ChatBubbleScreen.kt` still uses the old simple sort (`compareBy({ it.timelineRowid }, ...)`) — pending echoes are not expected in that screen.

## Pending Local Echoes

Pending echoes are synthetic events inserted client-side before the server round-trip completes. They are identified by `event_id` starting with `~`. See **[docs/MESSAGE_SENDING.md](MESSAGE_SENDING.md)** for the full lifecycle.

Key properties at insertion time:
- `eventId` starts with `~`
- `timelineRowid = 0` (not in DB)
- `timestamp` = time of send (used as tiebreak in the sort above)

## `eventChainMap` — Central Data Structure

`eventChainMap` is a `LinkedHashMap<String, TimelineEvent>` keyed by `eventId`. It is the single source of truth for everything `buildTimelineFromChain()` renders.

- Managed by `EditVersionCoordinator`
- `addNewEventToChain(event)` deduplicates by `eventId` — safe to call multiple times for the same event
- `buildTimelineFromChain()` / `executeTimelineRebuild()` rebuilds `timelineEvents` state on the Main dispatcher

## Known Gaps

- `m.room.member` profile-hint events also flow through `updateMemberProfilesFromEvents` (line ~5337), which uses `timelineRowid >= 0L` as its filter. This is intentional: profile hints should update the member cache even though they are not rendered. Do not change that filter to `> 0L`.
- `ChatBubbleScreen.kt` sort does not use the `Long.MAX_VALUE` substitution. This is an existing inconsistency — not a bug unless pending echoes are ever supported in bubble windows.
