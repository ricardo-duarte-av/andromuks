# Polls (MSC3381)

Andromuks renders polls as message bubbles, lets the user vote, and can create and close polls.

## Wire format

Both prefix flavours are accepted, for the event `type` **and** for the content key:

| Purpose | Unstable | Stable |
|---|---|---|
| Poll definition | `org.matrix.msc3381.poll.start` | `m.poll.start` |
| A user's vote | `org.matrix.msc3381.poll.response` | `m.poll.response` |
| Closing the poll | `org.matrix.msc3381.poll.end` | `m.poll.end` |

A response echoes whichever flavour the poll start used (`PollStartInfo.isStablePrefix`).

```jsonc
// poll.start content
{
  "org.matrix.msc3381.poll.start": {
    "kind": "org.matrix.msc3381.poll.disclosed",   // or …undisclosed
    "max_selections": 2,
    "question": { "org.matrix.msc1767.text": "🐟️?" },
    "answers": [ { "id": "3bHnpptB", "org.matrix.msc1767.text": "🐟️" }, … ]
  }
}

// poll.response content — relates to the start via m.reference
{
  "m.relates_to": { "event_id": "$poll", "rel_type": "m.reference" },
  "org.matrix.msc3381.poll.response": { "answers": ["3bHnpptB"] }
}
```

Text is read as `org.matrix.msc1767.text`, then `m.text` (string or array of `{mimetype, body}`),
then a plain `body`. An empty `answers` array is a **withdrawal**, which is what tapping a selected
option sends.

### E2EE: never switch on `event.type`

In an encrypted room the live sync frame arrives as `type = "m.room.encrypted"` with the real type in
`decrypted_type` and the poll content in `decrypted`. (A resolved event fetched from the gomuks DB
shows the poll type at the top level with an `encrypted` block alongside — do not let that mislead
you about the sync path.)

**Always route polls through `pollEventType(event)`**, which looks through `decryptedType`. Switching
on `event.type` silently breaks encrypted rooms in two distinct ways, both of which shipped and had
to be fixed:

- `MessageTypeContent` matched its `"m.room.encrypted"` branch before the poll branch and rendered
  the generic "Encrypted message" placeholder. Polls are therefore dispatched *before* that `when`.
- In the render filters, poll responses/ends pass the `allowedEventTypes` whitelist in an E2EE room,
  because `m.room.encrypted` is whitelisted. They are dropped structurally via
  `isPollSatelliteEvent`, next to the unconditional redaction drop, rather than by type.

> **There are two render filters, not one.** `processTimelineEvents` (RoomTimelineScreen) is shared
> by the room timeline *and* the thread viewer, but `BubbleTimelineScreen` carries its own
> near-duplicate `bubbleProcessTimelineEvents`. Any structural filter rule has to be added to both.
> Likewise `allowedEventTypes` is declared separately in all three screens.

## Aggregation rules

All implemented in `computePollResults` (`utils/PollFunctions.kt`), which is pure and unit-tested in
`app/src/test/.../PollAggregationTest.kt`:

- **One response *event* counts per user — latest `origin_server_ts` wins**, ties broken by event id
  so the result is deterministic regardless of delivery order. That single event carries a *set* of
  answer ids, so the per-user record is a set, not one answer.
- `max_selections` defaults to **1**; a voter's selection is truncated to the first N valid ids.
- Answer ids not defined by the start are dropped. A response whose ids are *all* invalid is
  **spoiled** and counts for nothing.
- The **earliest authorized** `poll.end` closes the poll; responses timestamped after it are ignored.
  An end is authorized only from the poll's creator or a user with power level ≥ the room's `redact`
  level (`isAuthorizedPollEnd`). A poll cannot be reopened by a later end event.
- `kind: …undisclosed` hides per-option counts and bars until the poll ends (`resultsHidden`).
- Redacted responses and ends are ignored (`parsePollResponse`/`parsePollEnd` return null).

## Architecture

Poll responses and ends are **satellite events**: they mutate a poll bubble's rendering but are never
timeline rows themselves. That is structurally identical to reactions, so the pipeline mirrors
`ReactionCoordinator` / `MessageReactionsCache` / `RoomCache.reactionEvents`.

```
poll.start   → cache.events  → timeline row → RoomPollMessageContent bubble
poll.response│
poll.end     ┘→ RoomCache.pollEvents → PollCoordinator.recomputePoll → PollCache → bubble
```

| Concern | Location |
|---|---|
| Parsing + aggregation (pure) | `utils/PollFunctions.kt` |
| UI (bubble body + voter dialog) | `utils/PollMessageContent.kt` |
| Bubble chrome wrapper | `RoomPollMessageContent` in `TimelineEventItem.kt` |
| Orchestration, voting, RPC | `PollCoordinator.kt` |
| Derived results cache | `PollCache.kt` (poll start eventId → `PollResults`) |
| Raw satellite events | `RoomCache.pollEvents` in `RoomTimelineCache.kt` |

### Full recompute, never deltas

Unlike reactions, **no incremental updates are applied**. Every change re-runs `computePollResults`
from the raw stores. A poll has at most one effective vote per user, so this is cheap — and it makes
redaction, out-of-order delivery and superseded votes correct by construction rather than by
bookkeeping. Notably, `ReactionCoordinator.removeReaction` needs a `redactedReactionEventIds` guard
to stop a double-delivered redaction from over-decrementing a count; the poll path cannot have that
class of bug.

### Out-of-order delivery is free

Responses are stored keyed by their poll start id whether or not the start has arrived. If the start
lands later (common when paginating upward), `loadPollsForRoom` ingests it and recomputes, and the
buffered votes are picked up. No orphan-placeholder machinery is needed (contrast
`EditVersionCoordinator`, which does build placeholders for early redactions).

### Ingest paths

| Path | Where |
|---|---|
| Live sync | `AppViewModel.processSyncEventsArray` — poll branch; starts go to `addNewEventToChain`, satellites are merged to cache + ingested. Touched polls are recomputed once after the loop. |
| Redaction | The cache-lookup block in the same function — poll satellites are never in `timelineEvents`, so the redacted event is resolved via `RoomTimelineCache.findEventForReply`. |
| Paginate | `TimelineCacheCoordinator.processEventsArray` — satellites are filtered out of `timelineList` into `paginatedPollEvents`, then **explicitly re-merged** via `mergePaginatedEvents`. |
| Room open | `processCachedEvents` and `handleInitialTimelineBuild` call `loadPollsForRoom(..., forceReload = true)`. |
| On demand | `requestPollDetails` → `get_related_events` with `relation_type = "m.reference"`. |

> The explicit paginate re-merge is load-bearing, exactly as it is for reactions. Without it,
> paginated votes are dropped and the counts vanish on the next room open.

### Why `m.reference` is *not* in `requiresFullRerender`

`TimelineCacheCoordinator.appendEventsToCachedRoom` invalidates a room's saved `processedState` when
a batch contains an edit, a reaction or a redaction. Poll responses are deliberately **excluded**:
they never enter `cache.events` or `eventChainMap`, so the processed timeline state stays valid.
Broadening that predicate to `m.reference` would also match every Beeper
`com.beeper.message_send_status` event and thrash the processed-state cache for no benefit.

## Creating a poll — via gomuks, not by hand

Typing a bare `/poll` opens the full-screen `PollMakerScreen` (nav route `poll_maker/{roomId}`,
registered in `MainActivity`). Detection mirrors `/pmp`: `isBarePollCommand(draft)` sets a flag in
`onValueChange`, and a `LaunchedEffect` clears the draft and navigates — deferred so we never
navigate from inside the text field's edit commit.

**We do not build the `poll.start` event.** `PollCoordinator.sendPollCreate` sends an ordinary
`send_message` whose `base_content` carries an MSC4391 command envelope, and gomuks builds the event
server-side (`pkg/hicli/commands.go` → `handleCmdPoll`). This is what webmuks does, so following it
keeps us interoperable — and because it is a `send_message`, poll creation rides the existing local
echo instead of the echo-less `send_event`.

```jsonc
"base_content": {
  "msgtype": "m.text",
  "body": "/poll \"Lunch?\" 1 \"Pizza\" \"Sushi\"",
  "org.matrix.msc4391.command": {
    "command": "poll",
    "arguments": { "question": "Lunch?", "max_selections": 1, "options": ["Pizza", "Sushi"] }
  }
},
"mentions": { "user_ids": ["@gomuks"], "room": false }
```

Three things about that envelope:

- **`@gomuks` has no domain part.** That is gomuks' internal local-bot address, not a malformed
  MXID. Sent verbatim, matching webmuks. MSC4391 wants the bot mentioned so commands can't be picked
  up by the wrong one.
- **`max_selections` is always sent, and always in range.** `handleCmdPoll` clamps with
  `if maxSelections <= 0 || maxSelections > len(options) { maxSelections = len(options) }`, and Go
  unmarshals a *missing* field to `0` — so omitting it produces a poll where every option is
  selectable, **not** the `DefaultValue: 1` the command schema advertises. That default lives only in
  `cmdspec/commands.go`; the handler never reads it.
- **The `body` fallback quotes every option.** MSC4391 treats it as non-authoritative and optional,
  but webmuks' own example is lossy (unquoted `Fourth Option` reads back as two options), so we quote
  each argument instead of reproducing that.

We require ≥2 options client-side even though gomuks accepts 1 — a poll with one option offers no
choice. `max_selections` is a mode toggle in the UI ("Single answer" / "Multiple answers" with a
count), never a raw number field.

The `body` uses the `/poll@gomuks …` fallback syntax webmuks produces, and quotes an argument only
when it contains whitespace or a quote — bare words stay bare so the output matches webmuks, while
multi-word options still survive a shell-style re-parse.

### Threads and bubbles

Both are first-class. A poll started inside a thread adds the thread relation to the command, so
gomuks roots the resulting `poll.start` in the thread:

```jsonc
"relates_to": {
  "rel_type": "m.thread",
  "event_id": "$threadRoot",
  "is_falling_back": true,
  "m.in_reply_to": { "event_id": "$latestEventInThread" }
}
```

`is_falling_back: true` sits *alongside* `m.in_reply_to` on purpose: per the threads spec that pairing
means "this in_reply_to is a fallback for non-threaded clients, not a real reply", which is what a
poll posted into a thread is. Note the app's shared `MessageSendCoordinator.buildMediaRelatesTo`
instead sets `is_falling_back` only when there is *no* reply target — a discrepancy worth a separate
look; `PollCoordinator.pollThreadRelatesTo` deliberately does not inherit it.

**Votes need no thread relation at all** — a vote inside a thread carries only the `m.reference` to
the poll start, exactly like one in the main timeline, so `sendPollResponse` is unchanged.

Three composers trigger `/poll`, each with its own `pendingPollMaker` flag and `LaunchedEffect`:
`RoomTimelineScreen`, `ThreadViewerScreen` (passes `?threadRoot=`), and `BubbleTimelineScreen`.
`ChatBubbleActivity` runs a **separate NavHost**, so it registers its own `poll_maker` route —
MainActivity's graph is not reachable from a bubble.

### Why there is no disclosed/undisclosed control

gomuks hardcodes `Kind: "org.matrix.msc3381.disclosed"` in `handleCmdPoll`, and `pollParams` has no
`kind` field. There is no argument to pass. We *render* undisclosed polls correctly, but cannot
create one without abandoning the command envelope for a hand-built `send_event` — and with it the
local echo. Not worth it for a kind gomuks' own clients can't produce.

## Ending a poll

`PollCoordinator.sendPollEnd` builds `poll.end` and sends it via `send_event`. Unlike creation there
is no alternative: gomuks has **no** poll-end command, and mautrix-go does not even define a poll-end
content type (`event/content.go` registers only `EventUnstablePollStart` and
`EventUnstablePollResponse`). Element does honour `poll.end` on receive, and so do we, so sending it
ourselves is interoperable.

The "End poll" entry appears in the message long-press menu only when `canEndPoll` is true — which
mirrors the receive-side `isAuthorizedPollEnd` check, so the UI never offers an action that every
client (ours included) would then ignore. It is keyed on `pollUpdateCounter`, so it disappears the
moment someone else's end event lands.

## Voting

`PollCoordinator.toggleAnswer` → `sendPollResponse`, which sends `send_event` (not `send_message` —
polls are a custom event type, so `ReactionCoordinator.sendReaction` is the precedent). The **full**
new selection is sent each time, since a response replaces the sender's previous one entirely.

- `max_selections == 1` behaves like a radio button.
- At the cap on a multi-select poll, tapping a new option is **ignored** rather than silently
  evicting an earlier pick — a surprise deselect is worse than an ignored tap.
- Votes on an ended poll are rejected.

### Optimistic local echo

The optimistic vote is stored in `AppViewModel.pollLocalVotes`, **separate from the real votes**, and
overlaid at aggregation time. Putting it in the normal vote list would risk it beating the server's
own copy under the latest-wins rule whenever the device clock runs ahead of the homeserver's. The
overlay retires as soon as a server response from the local user that is not older arrives (or
immediately, on send failure, which also rolls the UI back).

## Rendering

- Poll start types are in `allowedEventTypes` in `RoomTimelineScreen`, `BubbleTimelineScreen` and
  `ThreadViewerScreen`, and in the `isNarratorEvent` exclusion set in `TimelineEventItem`. The latter
  is what earns a poll the avatar, sender name, timestamp, read receipts and long-press menu.
- `PollMessageContent` takes the bubble's `contentColor`, matching the `LocationMessageContent`
  contract, so it contrasts correctly in every bubble variant. Everything else comes from
  `MaterialTheme.colorScheme` — the app defaults to Material You dynamic colour, so nothing is
  hardcoded.
- Each answer is a card: label, count and percentage on the first line, a 6dp result bar underneath.
  The bar uses the played/unplayed alpha pairing established by the audio waveform
  (`WaveformSeekBar`, `utils/MediaFunctions.kt`) rather than a `LinearProgressIndicator`; there is no
  determinate progress indicator anywhere else in the app. The fill is `colorScheme.primary` for the
  user's own picks and a muted `contentColor` otherwise.

### Percentages are shares of votes cast, not of voters

`PollResults.percentFor` / `fractionFor` divide by **`totalVotes`** (the sum of all per-answer
counts), *not* by `totalVoters`. On a `max_selections > 1` poll a single voter contributes several
votes, so dividing by voters lets the percentages sum past 100 — one voter picking two of four
options would read 100%/100%/0%/0%. Dividing by votes cast gives the correct 50%/50%/0%/0%, and the
bars always add up to one full width.
- Tapping a vote count opens `PollVotersDialog`, which reuses the `ReactionDetailsDialog` shape and
  its opportunistic `requestUserProfileOnDemand` effect so avatars fill in.

Room-list preview and notification text render as `📊 <question>` (`SpaceRoomParser`,
`NotificationDataParser`). Poll responses and ends explicitly return `null` there so a vote never
becomes a room's preview text.
