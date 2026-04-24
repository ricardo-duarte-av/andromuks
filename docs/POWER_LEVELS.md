# Power Levels

## Parsing

Power levels are parsed from `m.room.power_levels` state events into `PowerLevelsInfo` (`RoomItem.kt`) and stored on `RoomState.powerLevels`. Parsing happens in `AppViewModel.parseRoomStateFromEvents`.

## Key Matrix Spec Rules

| Field | Default when missing | Notes |
|---|---|---|
| `users_default` | 0 | Default PL for users not listed in `users`. Parsed with `optInt("users_default", 0)`. |
| `events_default` | 0 | Default PL for non-state event types not in `events`. |
| `state_default` | **50** | Default PL for **state** event types not in `events`. Stored in `PowerLevelsInfo.stateDefault`. |

- `m.room.pinned_events` is a **state event** — its required PL falls back to `stateDefault`, not `eventsDefault`.
- There is **no cap on the number of pinned events** in the Matrix spec. The only real limit is the 64 KB event size. Do not add an artificial count limit.
- Permission to pin and unpin is purely `myPowerLevel >= pinnedEventsPowerLevel`. Pin and unpin require the same PL.

## `canPin` Computation

Computed in `ReplyFunctions.kt` and `NarratorFunctions.kt` (shared by all timeline screens).

Fallback chain for `pinnedEventsPowerLevel`:
```
events["m.room.pinned_events"] ?: stateDefault ?: 50
```

## Known Gap

Live `m.room.power_levels` timeline events are not yet propagated to update `currentRoomState.powerLevels`. Power levels are only set on initial room state load via `parseRoomStateFromEvents`.
