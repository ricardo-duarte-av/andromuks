# Room State

Per-room Matrix state: how it is fetched, parsed, cached in RAM, persisted, and read.

## The problem this replaced

Until v1.1.22 the app had **no per-room state cache**. `parseRoomStateFromEvents` scanned the whole
`get_room_state` array, kept nine fields in `AppViewModel.currentRoomState` — a *single-room slot*
nulled on every room switch — and discarded the array. `roomStatesCache` had been declared for
exactly this purpose and was never written, so `getRoomState()` returned `null` for the life of the
process.

Everything that needed another room's state, or this room's state after a switch, therefore had
nothing to read. The symptoms looked unrelated to each other:

- an open padlock on encrypted rooms (the header rendered "unknown" as "unencrypted");
- Element Call joining encrypted rooms with `perParticipantE2EE=false`;
- permission checks failing open on cold start, because power levels were never persisted;
- moderation buttons missing unless the profile was opened from the currently-open room, behind a
  1.5 s polling loop that could never succeed;
- three merge sites in `AppViewModel` that existed only to rebuild what the nulling destroyed — one
  of which preserved state events the room had *removed*, since it could not distinguish "absent
  from this response" from "deleted".

## Shape

```
get_room_state ──▶ parseCompleteRoomStateFromEvents ──▶ RoomStateStore
  (complete)                                              ├─ parsedStates : RoomState per room  (all rooms, snapshot-backed)
                                                          ├─ rawStates    : type → JSON         (LRU, 24 rooms)
                                                          └─ room_state   : SQLite              (everything, never trimmed)

get_specific_room_state ──▶ ingestPartialState ──▶ rawStates + SQLite (merge, no delete)

currentRoomState  =  RoomStateStore.getParsed(currentRoomId)     ← a derived view, not storage
```

### Complete vs partial — the load-bearing distinction

`get_room_state` returns the room's **entire** state; its only options concern the member list
(`include_members` / `fetch_members`) and `refetch`. So a state event absent from a response means
the room genuinely no longer has it, and the store **replaces** what it holds for that room.

`get_specific_room_state` returns **only the keys it was asked for** and says nothing about the rest.
Those responses **merge**.

Feeding a partial response to the full parser would delete every state event it did not mention.
That is why the parser is named `parseCompleteRoomStateFromEvents` — the requirement is stated at
every call site. Its two callers are both `get_room_state` (`include_members` false and true).

## RAM tiers

| Tier | Contents | Scope | Why |
|---|---|---|---|
| `parsedStates` | `RoomState` per room | **all** rooms | Small. Headers, permission checks and the room list want it for any room. Snapshot-backed, so a composable reading one room subscribes to that key alone. |
| `rawStates` | raw per-type JSON | LRU, 24 rooms | Rarely read, and only for the room in front of the user. Non-resident rooms fall back to disk. |

Nothing is trimmed on **disk** — RAM is where the bound applies.

## `m.room.member` is never cached

Excluded at every entry point, not just at the DB boundary. A large room would be tens of thousands
of rows; `get_room_state` is issued with `include_members=false` on the bulk path so the data is
usually absent anyway; and a partial member list held as though complete is worse than no cache.

Membership stays in `RoomMemberCache` / `ProfileCache`. `RoomInfoScreen` gets its member list as a
**transient** returned alongside the parse (`requestRoomStateWithMembers` yields
`(state, members, error)`) — including invited, left and banned users, which `RoomMemberCache` does
not hold.

## Staleness contract

**The persisted copy is always treated as possibly stale.** It exists so the UI can paint before the
socket is up; it is never authoritative. Callers hydrate from it *and* issue a `get_room_state`
regardless of what hydration produced.

## Persistence

`room_state` table in `room_metadata.db` (`DB_VERSION` 6), alongside `room_metadata`. One database,
because the WAL + single-writer story that makes the fire-and-forget write path safe depends on
there being exactly one SQLite file.

```sql
CREATE TABLE room_state (
  room_id TEXT NOT NULL, type TEXT NOT NULL, state_key TEXT NOT NULL,
  content TEXT NOT NULL, sender TEXT, event_id TEXT, timestamp INTEGER,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (room_id, type, state_key)
);
```

Row shape is Matrix's own state shape, so arbitrary event types persist without a migration each
time. That is the point: real rooms carry keys this app has never parsed —
`com.beeper.room_features`, `com.beeper.disappearing_timer`, `io.element.functional_members` — and
they are now kept rather than discarded.

Writes follow `RoomMetadataStore`'s hygiene: dirty-check → sync mirror → `ioScope.launch` → one
transaction, `INSERT-OR-IGNORE` then `UPDATE` (never `CONFLICT_REPLACE`, which nulls columns absent
from the values).

### Lifecycle

| Event | Effect |
|---|---|
| Room pruned (`RoomListCache.removeRoom`, incl. the `clear_state` stale-prune) | RAM + rows dropped |
| Logout | RAM + rows dropped, alongside `RoomMetadataStore.clearAll()` |
| `clear_state` | Survives, like room metadata — it is a paint optimisation refreshed by the same batch |
| DB downgrade | Table dropped and recreated (see `onDowngrade`) |

## `currentRoomState` is a derived view

```kotlin
val currentRoomState: RoomState?
    get() = currentRoomId.takeIf { it.isNotEmpty() }?.let { RoomStateStore.getParsed(it) }
```

There is nothing left for a room switch to destroy — it changes which key is read. Both reads are
snapshot state, so it stays reactive and recomposes only readers of the room that changed.

**Do not reintroduce a setter.** Writes go to the store keyed by `roomId`; that is what makes it
impossible for an update for room A to land on room B, or to be erased by navigating away.

The sync `meta` delta is a **separate channel**: it updates the parsed tier only. It is not a state
event, so it must never reach the raw tier or the `room_state` table — those stay the exclusive
record of what `get_room_state` returned. `meta`-derived name/avatar persistence remains
`RoomMetadataStore`'s job, which is the **cold-start paint projection**: one-directional, fed by both
channels, never read back as state truth.

## One model, one parser

`RoomState` (`RoomItem.kt`) is the single model. It absorbed the fields that used to live only in
`RoomInfoScreen`'s `RoomStateInfo`: `altAliases`, `creator`, `roomVersion`, `historyVisibility`,
`joinRule`, `serverAcl`, `parentSpace`, `urlPreviewsDisabled`.

Per-type parsing lives in `utils/RoomStateParsing.kt` — `parseRoomTopic`, `parsePowerLevels`,
`parseServerAcl`, `parseRoomMembers`, `parseStringList`. One implementation each; the topic fallback
previously had three and the power-levels parse three, and both sets had drifted.

Nulls mean **"not observed"**, never a negative fact. `RoomState.isEncrypted` is `Boolean?` for
exactly this reason: `null` renders as a neutral padlock, and only a confirmed `false` renders as
unencrypted.

## Permissions

`utils/RoomPermissions.kt` holds the power-level predicates. The unknown-power-levels fallback is
**not uniform**, deliberately:

- `canSendMessage` **fails open** — a slow state fetch must not lock someone out of a room they can
  post in, and the server rejects a wrong guess anyway.
- Everything else **fails closed** — offering a moderation action the server will refuse is worse
  than hiding one.

See [POWER_LEVELS.md](POWER_LEVELS.md).

## Testing

`RoomStateStoreTest` (parsing, RAM tiers, replace-vs-merge, member exclusion, LRU),
`RoomStateEncryptionTest` (tri-state, padlock rule), `RoomPermissionsTest` (predicates and their
asymmetric fallbacks).

The SQLite half is **not** covered: without Robolectric, `android.database.sqlite` is the stub
`android.jar` that `isReturnDefaultValues` turns into null/0. That is also what makes the RAM tests
safe — `writableDbOrNull()` is null, so every `persist*` early-returns and nothing touches disk. Disk
behaviour is emulator work (GH issue #20).
