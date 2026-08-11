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

## Creators (room version 12+)

From room version 12 (MSC4289) the room's **creators** hold power that `m.room.power_levels` never
expresses. They are the sender of `m.room.create` plus everyone listed in that event's
`additional_creators`, and they are deliberately **absent from `users`** — so reading power levels
alone reports them at `users_default`, which is usually 0.

`RoomPermissions.creatorsOf(state)` is the only place that knows the rule; every predicate takes the
resulting `Set<String>` and `powerLevelOf` returns `CREATOR_POWER_LEVEL` (`Int.MAX_VALUE`) for a
member of it. Consequences that fall out of that:

- A creator clears every threshold — send, redact, pin, kick, ban — **including when power levels
  are unknown**, because their standing does not come from that event at all. Everyone else still
  fails closed there.
- **No one can kick or ban a creator**, not even another creator: creators are equals at
  `CREATOR_POWER_LEVEL` and the kick/ban rule is strictly-above.
- The room-info member list sorts creators to the top and badges them `PL: ∞`, rendered from the
  create event because their power level really is *absent* rather than zero.

The gate is the **room version**, not the presence of `additional_creators`: that key carries no
meaning in a room whose version does not define it, and must not confer standing there. A room whose
version we cannot parse has no privileged creators.

## Unknown power levels

The fallback is deliberately **not uniform**. `canSendMessage` fails **open**; every moderation
predicate fails **closed**. See the class doc in `RoomPermissions.kt` and `RoomPermissionsTest`.

## Known Gap

Live `m.room.power_levels` timeline events are still not propagated into the store — power levels are
set from `get_room_state` responses only. They now at least survive a room switch and a cold start,
so the window is "until the next state fetch" rather than "until you navigate away".
