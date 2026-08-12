# In-room bot commands (MSC4391)

Bots advertise their commands as state events; the client offers them in the `/` autocomplete,
parses the typed arguments against the declared types, and sends a structured JSON envelope inside
an ordinary `m.room.message`. Users who do not know a bot's syntax get a typed form instead.

Spec: [MSC4391](https://github.com/Gnuxie/matrix-doc/blob/gnuxie/simplified-in-room-bot-commands/proposals/4391-simplified-in-room-bot-commands).

## The MSC prose is not the contract — `cmdschema` is

**mautrix-go's `event/cmdschema` package is the de-facto reference implementation.** gomuks declares
its own built-in commands as `cmdschema.EventContent` values in `pkg/hicli/cmdspec/commands.go`, and
webmuks parses against the same package. Our Kotlin port follows *it*, not a literal reading of the
MSC, because that is what bots and other clients will actually agree with.

Divergences that matter, all reproduced deliberately:

| MSC prose | `cmdschema`, and therefore us |
|---|---|
| `literal` carries a `literal_type` field | **No such field.** The JSON type of `value` *is* the type. |
| — | `aliases: []string` on the command |
| — | `fi.mau.tail_parameter` — one parameter swallows the rest of the line |
| — | `fi.mau.default_value` per parameter |
| optional-parameter order "not significant" | Optional non-tail parameters are **never bound positionally** — only via `--key=value` |
| arrays "may appear in any place" | A mid-list array needs `<a b c>` delimiters; an undelimited mid-list array takes exactly one item |
| — | A bare `--flag` on a boolean-accepting parameter means `true` |
| — | The prefix may carry the owner MXID: `/ban@bot:example.org …` |
| state_key is "a padded base64 SHA256" | `base64.StdEncoding(sha256(command + ownerMxid))`, exactly |

## Why `send_message` and not `send_event`

`hicli.SendMessage` (`pkg/hicli/send.go`) intercepts a command envelope **only** when the mention
list is exactly `[@gomuks]`:

```go
hasCommand := base != nil && base.MSC4391BotCommand != nil
if hasCommand && mentions.Has(cmdspec.FakeGomuksSender) && len(mentions.UserIDs) == 1 {
    return h.ProcessCommand(ctx, roomID, base.MSC4391BotCommand, base, relatesTo)
}
```

A command addressed to a real bot therefore falls through to the ordinary send path, where
`base_content` is merged into the outgoing content verbatim. So we ride `send_message` — which has
local echo — instead of the echo-less `send_event`. This is the same route `/poll` has always taken
(`PollCoordinator.sendPollCreate`), except that `/poll` deliberately inserts **no** echo: gomuks
swallows its own commands and replaces them with a `poll.start`, whereas a third-party bot command is
a real message that stays in the timeline.

Outgoing shape:

```jsonc
{
  "command": "send_message",
  "data": {
    "room_id": "!room:example.org",
    "text": "",
    "base_content": {
      "msgtype": "m.text",
      "body": "/ban @alice:example.org 42 \"lots of spam\"",   // non-authoritative fallback
      "org.matrix.msc4391.command": {
        "command": "ban",
        "arguments": { "user": "@alice:example.org", "timeout": 42, "reason": "lots of spam" }
      }
    },
    "mentions": { "user_ids": ["@bot:example.org"], "room": false },
    "url_previews": []
  }
}
```

## Files

| Concern | File |
|---|---|
| Schema model, state-event parsing, all validation | `utils/BotCommandSchema.kt` |
| `base64Std(sha256(command + sender))` and its hand-rolled encoder | `utils/BotCommandStateKey.kt` |
| Text → typed arguments (port of `parse.go`) | `utils/BotCommandParse.kt` |
| Arguments → fallback body (port of `stringify.go`) | `utils/BotCommandStringify.kt` |
| Built-in shadowing, joined-sender filter, ordering | `utils/BotCommandResolution.kt` |
| Per-room index | `BotCommandCache.kt` |
| Ingest + send orchestration | `BotCommandCoordinator.kt` |
| `/` detection shared by the three composers | `utils/ComposerCommandDetection.kt` |
| Composer state holder + overlays | `utils/ComposerBotCommands.kt` |
| Signature strip, argument sheet | `utils/BotCommandSignatureStrip.kt`, `utils/BotCommandArgumentSheet.kt` |
| Suggestion rows | `utils/CommandSuggestionList.kt` |

## Discovery and freshness

`parseCompleteRoomStateFromEvents` gains one `when` arm and calls
`botCommandCoordinator.ingestFullState(roomId, events)` **unconditionally** after the loop — the
cache replaces rather than merges, so an empty result is what clears a departed bot's commands.
Because `loadAllRoomStatesAfterInitComplete` sweeps `get_room_state` for every room at startup, the
index refills itself within a second of connecting; there is **no SQLite table** for it.

Live updates come from `updateRoomStateFromTimelineEvents`, whose only call site is gated on
`roomId == currentRoomId`. That is exactly the room whose composer is on screen, so it is sufficient;
per [STATE_INVARIANTS.md](STATE_INVARIANTS.md) the gate must not be widened. A bot registering a
command while you are in a *different* room appears on the next `get_room_state`.

The one remaining gap — a cold-started `ChatBubbleActivity`/`ShortcutActivity` opening a room the
sweep has not reached — is closed by `rememberComposerCommandState`, which requests room state on
mount when `BotCommandCache.isIndexed(roomId)` is false.

## Security-relevant rules

- **The `state_key` hash is verified and a mismatch drops the description.** It is the MSC's only
  anti-squatting mechanism: without it any joined user could plant a description that appears to come
  from the bot. `parseBotCommandDescription(verifyStateKey = …)` is the kill switch if a real bot
  gets the concatenation wrong; mismatches are logged at `Log.w` with both keys.
- **Built-in commands win.** A bot advertising `myroomnick` would otherwise break this client's own
  command. Note the practical cost: our built-ins are `/ban /kick /invite /redact /join /alias` —
  a moderation bot's entire vocabulary. Those stay reachable only through the qualified
  `/ban@bot:example.org` form, which `BotCommand.parsePrefix` supports.
- **The advertising bot must be in the room**, but the check *fails open* when the member list has
  not loaded — absence from `RoomMemberCache` is not proof of non-membership.
- **Descriptions are untrusted remote text.** Rendered as plain text with `maxLines` clamps, never as
  HTML, in both the suggestion row and the argument sheet.

## Rendering

None. A command message renders through its `body` fallback like any other `m.text`, exactly as
`/poll` does. `TimelineEventItem.kt` is untouched.

## Tests

Everything except the Compose layer is pure and covered:
`BotCommandStateKeyTest` (RFC 4648 vectors + independently computed hashes),
`BotCommandParsingTest` (schemas and every rejection rule), `BotCommandParseTest` (the `parse.go`
grammar), `BotCommandCoercionTest` (loose input in, canonical values out), `BotCommandStringifyTest`
(round-trips), `BotCommandPrecedenceTest`, `ComposerCommandDetectionTest` (which also pins the
behaviour of the three `detectCommand` copies it replaced).

`base64Encode` is hand-rolled because `java.util.Base64` is API 26 (minSdk 24) and
`android.util.Base64` returns null under `isReturnDefaultValues` — a state-key test would otherwise
pass while hashing nothing.
