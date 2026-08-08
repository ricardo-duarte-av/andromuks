# Per-Message Profiles (outgoing) — MSC4461 revision 3

How *we* send messages under someone else's name/avatar. For the **incoming** side — rendering
`com.beeper.per_message_profile` that bridges and other clients attach — see
[bridges.md](bridges.md#per-message-bridge-profiles).

- [MSC4144](https://github.com/matrix-org/matrix-spec-proposals/pull/4144) defines the profile that
  rides on a message event.
- [MSC4461](https://github.com/matrix-org/matrix-spec-proposals/pull/4461) defines how a client
  *stores* reusable profiles in account data. It has been revised twice: rev-2 on 2026-07-30 (map →
  array with triggers, gomuks `951bac5`), then **rev-3 on 2026-08-06** (`trigger` object →
  `triggers` array with suffixes, plus `default_profile_id` and room-scoped storage; gomuks
  `c416f431`, mautrix-go `5a1f9e0dbc0a`). Everything below is the post-rev-3 shape.

All of the client-side logic lives in `utils/PerMessageProfileEditor.kt`.

## Storage

The same event type works in **global** account data and in **room** account data. Written to both
keys in each scope, read preferring the unstable one:

| Key | Notes |
|---|---|
| `fi.mau.msc4461.per_message_profiles.v3` | Unstable rev-3 key — the **only** one gomuks reads (`event.AccountDataPerMessageProfiles`). |
| `m.per_message_profiles` | Stable key. Same name in every revision but an incompatible shape each time, so readers sniff rather than trust the key. |
| `fi.mau.msc4461.per_message_profiles.v2` | Rev-2 unstable key (`trigger: {prefix: [...]}`). Read once for migration, then blanked. |
| `fi.mau.msc4461.per_message_profiles` | Rev-1 unstable key (shortcode → profile map). Read once for migration, then blanked. |

```json
{
  "default_profile_id": "black_cat",
  "profiles": [
    {
      "id": "black_cat",
      "displayname": "🐈‍⬛",
      "avatar_url": "mxc://…",
      "triggers": [
        { "prefix": "mrrp:" },
        { "prefix": "meow ", "suffix": " meow" }
      ]
    }
  ]
}
```

`triggers` is client-private and **MUST NOT** be copied into an outgoing message —
`PerMessageProfileEntry.toContentMap()` exists precisely so the send path can never leak it.
Conversely, rev-3 says every *other* field is copied, including ones this client doesn't model, so
`PerMessageProfileEntry.extras` carries unknown keys through both a read/write round-trip and the
send path rather than dropping them.

### Trigger matching

A trigger fires when the text starts with its `prefix` **and** ends with its `suffix`; either side
may be absent, and both matched ends are stripped from the body. All enforced by gomuks
(`PerMessageProfilesEventContent.Match`), mirrored in our UI copy:

- Matching is **case-sensitive** and **verbatim**: `"cat:"` does not match `cat: meow`; `"cat: "`
  does. The editor never trims trigger strings.
- Prefix and suffix must not overlap — gomuks requires `len(input) >= len(prefix) + len(suffix)`.
- Order is priority: all triggers of the first profile beat the second profile's, and **room-scoped
  profiles are matched before global ones**. The editor and the picker therefore render the stored
  order and never sort.

### Default profile

`default_profile_id` names the profile to use when no trigger matched and the user didn't pick one.
It has three distinct states, and `readNullableString` exists because `optString` cannot tell the
first two apart:

| Value | Meaning |
|---|---|
| absent / `null` | Fall through to the global value. |
| `""` | Explicitly no profile in this scope, *suppressing* the global value. |
| an id | Use that profile, looked up in room storage first, then global. |

Mirrored by `resolveDefaultPerMessageProfile(roomId)`, which follows mautrix-go's
`PickPerMessageProfile`: room triggers → global triggers → room default → global default.

### v1/v2 → v3 migration

`migrateLegacyProfilesIfNeeded` runs from `PerMessageProfileEditorScreen`'s `LaunchedEffect`, for
the global scope only (room storage is new in rev-3, so it has no legacy shape). When the rev-3 key
holds nothing but an older revision does, the older data is rewritten in the rev-3 shape and the
superseded unstable keys are blanked. Rev-2 `trigger.prefix` entries become one single-prefix
trigger each; rev-1 `shortcode → {…}` entries become a `"<shortcode>: "` prefix — the colon
convention gomuks used before `951bac5`. `readGlobalPerMessageProfiles()` also converts older data
read-only, so the picker keeps working before the editor is ever opened.

Migration makes the profiles invisible to a gomuks older than `c416f431`, which reads only the rev-2
key. Dual-writing is not an escape: rev-2 has no way to express a suffix trigger.

### Room account data ingest

`SpaceRoomParser.applyRoomAccountData` allowlists which per-room account data types reach
`RoomAccountDataCache`; both per-message-profile keys are on that list. Without it the room-scoped
profiles would exist server-side but be invisible to the picker and the chip.

## Sending

Two paths, both landing on `com.beeper.per_message_profile` in the event content:

1. **Trigger / default (server-side).** The user types `cat: meow`; we send the text unchanged and
   gomuks' `PickPerMessageProfile` strips the trigger and attaches the profile — or, when nothing
   matches, attaches the resolved `default_profile_id`. Nothing in the client is involved.
2. **Picker (client-side).** The composer arms a profile (`selectedPmpProfile`) and passes
   `perMessageProfile` into `sendMessage` / `sendReply` / `sendThreadReply`
   (`MessageSendCoordinator.kt`), which adds

   ```kotlin
   "base_content" to mapOf("msgtype" to "m.text", "com.beeper.per_message_profile" to profile)
   ```

   gomuks copies `base_content` into the outgoing content *after* its own matching, so an explicit
   pick survives. The same map goes into `textContent()` so the local echo bubble renders with the
   profile immediately.

**A resolved default is never armed into `base_content`.** Because `base_content` beats gomuks'
matching, doing so would make a typed `cat:` prefix silently lose to the room default. The default
is surfaced as a passive chip and nothing more.

`/pmp` and `/profile` **no longer reach the backend** — gomuks deleted those commands in `951bac5`,
and raw `/pmp …` text now trips its leading-slash guard. `SlashCommandsCoordinator` resolves the
profile itself (by `id` or by a trigger prefix, room profiles first) and sends via path 2, or
returns `false` on a bare command so the composer opens the picker.

## UI

- **Editor** — `PerMessageProfileEditorScreen`, one screen serving both scopes:
  - route `per_message_profile_editor` (global), reached from the "Per-Message Profiles" button in
    `UserInfo.kt`, enabled only on your own profile;
  - route `per_message_profile_editor/{roomId}` (room), reached from the button beside "Room
    Preferences" in `RoomInfo.kt`.

  Both offer id, display name, avatar upload, an add/remove list of prefix+suffix triggers, and a
  "Default profile" card. The room scope's default picker additionally offers "Use the global
  default" (unset) and "None in this room" (`""`), and lists global profiles as legal targets.
- **Picker** — `PerMessageProfilePicker`, opened by a bare `/pmp` / `/profile` draft
  (`isBarePerMessageProfileCommand`) or by tapping the chip. Takes `roomId` and lists room profiles
  above global ones, with "This room" / "All rooms" headers shown only when both scopes are
  non-empty.
- **Chips** above the composer input in `RoomTimelineScreen`, `ThreadViewerScreen` and
  `BubbleTimelineScreen`:
  - `PerMessageProfileChip` — a profile armed for the next send. Shows "Sending as …", reopens the
    picker on tap, disarms on ✕, and is cleared after every send (armed for one message only).
  - `PerMessageProfileDefaultChip` — the resolved default for this room. Shows "Sending as … by
    default", reopens the picker on tap, and has no ✕ because there is nothing armed to clear. Only
    rendered when no profile is armed.

## Fallbacks

gomuks adds the `Name: ` display fallback only when writing to the wire (`addFallbacks`) and strips
it again on sync (`RemovePerMessageProfileFallback`), so the client never sees or handles
`has_fallback`.
