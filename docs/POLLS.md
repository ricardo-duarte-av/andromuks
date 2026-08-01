# Polls (MSC3381)

Andromuks renders polls as message bubbles and lets the user vote. **Creating** a poll is not
supported — there is no compose-side entry point.

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

In E2EE rooms gomuks surfaces the decrypted type at the top level, but `pollEventType()` also looks
at `decryptedType`, so an `m.room.encrypted` wrapper is handled either way.

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
- Result bars use the played/unplayed alpha pairing established by the audio waveform
  (`WaveformSeekBar`, `utils/MediaFunctions.kt`) rather than a `LinearProgressIndicator`; there is no
  determinate progress indicator anywhere else in the app.
- Bars are scaled against the **leading** option, not the total, so the front-runner reads as a full
  bar and relative standing is easy to compare in a 300dp bubble. The exact share is spelled out as a
  percentage alongside.
- Tapping a vote count opens `PollVotersDialog`, which reuses the `ReactionDetailsDialog` shape and
  its opportunistic `requestUserProfileOnDemand` effect so avatars fill in.

Room-list preview and notification text render as `📊 <question>` (`SpaceRoomParser`,
`NotificationDataParser`). Poll responses and ends explicitly return `null` there so a vote never
becomes a room's preview text.
