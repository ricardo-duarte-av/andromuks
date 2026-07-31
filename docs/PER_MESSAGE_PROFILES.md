# Per-Message Profiles (outgoing) — MSC4461 revision 2

How *we* send messages under someone else's name/avatar. For the **incoming** side — rendering
`com.beeper.per_message_profile` that bridges and other clients attach — see
[bridges.md](bridges.md#per-message-bridge-profiles).

- [MSC4144](https://github.com/matrix-org/matrix-spec-proposals/pull/4144) defines the profile that
  rides on a message event.
- [MSC4461](https://github.com/matrix-org/matrix-spec-proposals/pull/4461) defines how a client
  *stores* reusable profiles in account data. It was **revised on 2026-07-30** (map → array with
  triggers); gomuks followed in commit `951bac5`. Everything below is the post-revision shape.

## Storage

Account data, global. Written to both keys, read preferring the unstable one:

| Key | Notes |
|---|---|
| `fi.mau.msc4461.per_message_profiles.v2` | Unstable rev-2 key — the **only** one gomuks reads (`event.AccountDataPerMessageProfiles`). |
| `m.per_message_profiles` | Stable key. Same name in rev-1 and rev-2 but an incompatible shape, so readers must check for a `profiles` array. |
| `fi.mau.msc4461.per_message_profiles` | Rev-1 unstable key. Read once for migration, then blanked. |

```json
{
  "profiles": [
    {
      "id": "black_cat",
      "displayname": "🐈‍⬛",
      "avatar_url": "mxc://…",
      "trigger": { "prefix": ["mrrp:", "cat: "] }
    }
  ]
}
```

`trigger` is client-private and **MUST NOT** be copied into an outgoing message —
`PerMessageProfileEntry.toContentMap()` exists precisely so the send path can never leak it.

Matching rules (all enforced by gomuks, mirrored in our UI copy):

- Prefixes are **case-sensitive** and matched **verbatim**: `"cat:"` does not match `cat: meow`;
  `"cat: "` does. The editor never trims them.
- Order is priority: all prefixes of the first profile beat the second profile's. The editor and the
  picker therefore render the stored order and never sort.
- The matched prefix is stripped from the body before sending.

### v1 → v2 migration

`migrateLegacyProfilesIfNeeded` runs from `PerMessageProfileEditorScreen`'s `LaunchedEffect`. When
no key holds array-shaped content but rev-1 data exists, each `shortcode → {id, displayname,
avatar_url}` becomes an entry with `trigger.prefix = ["<shortcode>: "]` — the colon convention
gomuks used before `951bac5` — then the old unstable key is blanked. `readPerMessageProfiles()` also
converts rev-1 data read-only, so the picker keeps working before the editor is ever opened.

## Sending

Two paths, both landing on `com.beeper.per_message_profile` in the event content:

1. **Trigger prefix (server-side).** The user types `cat: meow`; we send the text unchanged and
   gomuks' `StoredProfilesEventContent.Match` strips the prefix and attaches the profile. Nothing in
   the client is involved.
2. **Picker (client-side).** The composer arms a profile (`selectedPmpProfile`) and passes
   `perMessageProfile` into `sendMessage` / `sendReply` / `sendThreadReply`
   (`MessageSendCoordinator.kt`), which adds

   ```kotlin
   "base_content" to mapOf("msgtype" to "m.text", "com.beeper.per_message_profile" to profile)
   ```

   gomuks copies `base_content` into the outgoing content *after* its own prefix matching, so an
   explicit pick survives. The same map goes into `textContent()` so the local echo bubble renders
   with the profile immediately.

`/pmp` and `/profile` **no longer reach the backend** — gomuks deleted those commands in `951bac5`,
and raw `/pmp …` text now trips its leading-slash guard. `SlashCommandsCoordinator` resolves the
profile itself (by `id` or by a prefix) and sends via path 2, or returns `false` on a bare command so
the composer opens the picker.

## UI

- **Editor** — `PerMessageProfileEditorScreen` (route `per_message_profile_editor`): id, display
  name, avatar upload, and an add/remove list of trigger prefixes.
- **Picker** — `PerMessageProfilePicker`, opened by a bare `/pmp` / `/profile` draft
  (`isBarePerMessageProfileCommand`) or by tapping the chip.
- **Chip** — `PerMessageProfileChip` above the composer input in `RoomTimelineScreen`,
  `ThreadViewerScreen` and `BubbleTimelineScreen`. Shows "Sending as …", reopens the picker on tap,
  disarms on ✕, and is cleared after every send (armed for one message only).

## Fallbacks

gomuks adds the `Name: ` display fallback only when writing to the wire (`addFallbacks`) and strips
it again on sync (`RemovePerMessageProfileFallback`), so the client never sees or handles
`has_fallback`.
