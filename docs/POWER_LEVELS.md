# Power Levels

## Parsing

Power levels are parsed from `m.room.power_levels` state events into `PowerLevelsInfo` (`RoomItem.kt`) and stored on `RoomState.powerLevels`, which lives in `RoomStateStore` — see [ROOM_STATE.md](ROOM_STATE.md). Parsing happens once, in `utils/RoomStateParsing.kt` `parsePowerLevels`, called from `AppViewModel.parseCompleteRoomStateFromEvents`.

There used to be **two** classes named `PowerLevelsInfo` and **three** parsers for them. The copy in `UserInfo` omitted `state_default`, so it fell back to the class default of 50 rather than the room's value — meaning `canPin` computed through that path compared against the wrong number in any room that sets `state_default`.

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

`RoomPermissions.canPin` (`utils/RoomPermissions.kt`), used by both message menus. Fallback chain:
```
events["m.room.pinned_events"] ?: stateDefault ?: 50
```

## Unknown power levels

The fallback is deliberately **not uniform**. `canSendMessage` fails **open**; every moderation
predicate fails **closed**. See the class doc in `RoomPermissions.kt` and `RoomPermissionsTest`.

## Known Gap

Live `m.room.power_levels` timeline events are still not propagated into the store — power levels are
set from `get_room_state` responses only. They now at least survive a room switch and a cold start,
so the window is "until the next state fetch" rather than "until you navigate away".
