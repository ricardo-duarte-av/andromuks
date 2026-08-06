# Reactions — Lifecycle, Storage, and Redaction

## Storage

Reactions are **never added to `timelineEvents`** or the `eventChainMap`. They live in two places:

| Store | Purpose |
|---|---|
| `RoomTimelineCache` (via `mergePaginatedEvents`) | Persistence — reactions survive room navigation and are replayed on re-open |
| `AppViewModel.messageReactions: Map<String, List<MessageReaction>>` | In-memory rendering state — key is the target event ID |

### Write invariant

`messageReactions` reads straight through to the `MessageReactionsCache` singleton. **Every incremental change must go through `MessageReactionsCache.mutate(eventId) { … }` or `merge(incoming) { … }`**, which perform the read-modify-write inside the cache lock and are scoped to one target event. The whole-map setter (`setAll`) is for resets and cold hydration only.

This is not stylistic. The live path used to read the entire map, transform a copy, and hand it back to `setAll` from a deferred `Dispatchers.Main` coroutine. `processSyncEventsArray` walks a sync batch synchronously, so every reaction in the batch read the same pre-batch snapshot and each queued a whole-map overwrite — and since `setAll` clears before it writes, the losing writes *deleted* the winners rather than merely being skipped. In a batch of N reactions, N−1 were lost. Symptom: other people's reactions simply never appeared until the room was re-paginated or reopened, worst in busy rooms. Regression tests live in `app/src/test/java/net/vrkknn/andromuks/ReactionBucketTest.kt`.

### Reactivity

`messageReactions` is **not** Compose state; reading it registers no snapshot read. `AppViewModel.reactionUpdateCounter` is the sole repaint signal, and every consumer must key a `remember` on it — the ~10 `ReactionBadges` call sites in `TimelineEventItem`, `MessageMenuBar`, and the reaction-details dialogs in `RoomTimelineScreen` / `BubbleTimelineScreen` / `ThreadViewerScreen` / `EventContextScreen`.

Nothing needs to bump the counter by hand. `MessageReactionsCache` notifies registered listeners on every mutation, and each `AppViewModel` registers one in `init` (removed in `onCleared`) that bumps its own counter. That also covers the direct cache clears in `RoomTimelineCache`, `TimelineCacheCoordinator` and `SyncRoomsCoordinator`, which previously left stale badges painted.

## Live Sync Path (`m.reaction` via `sync_complete`)

### The ingest must not be gated on a room-list delta

`SyncRoomsCoordinator.processParsedSyncResult` has an early return for
`!hasRoomChanges && !accountDataChanged && !memberStateChanged`. Timeline ingest —
`checkAndUpdateCurrentRoomTimelineOptimized` → `updateTimelineFromSync` →
`processSyncEventsArray` — used to sit *after* it, so that return skipped it entirely. Both calls
now go through `ingestTimelineEventsFromSync`, which runs on **every** sync_complete.

A reaction is exactly the payload that trips this. gomuks delivers `m.reaction` in a room object
with a `timeline`/`events`/`receipts` and **no `meta` and no `account_data`**, and
`SpaceRoomParser.parseSyncUpdate` `continue`s past meta-less room objects that carry no
`account_data`, so `updatedRooms`/`newRooms`/`removedRoomIds` all come back empty →
`hasRoomChanges == false`. The reaction never reached `messageReactions`.

The escape hatch was the *other* flags, which is why the bug looked random: the backend ships the
reactor's `m.room.member` alongside the reaction, so `populateMemberCacheFromSync` returns true and
the sync gets through — but only the **first** time a given user reacts in a session, since after
that the profile is unchanged. Your own profile is always already cached, and the `send_complete`
handler deliberately defers your own reactions to `sync_complete`, so your own reactions failed
every time. Reopening or paginating the room "fixed" it because both replay the reaction from the
DB / server instead of from this path — the usual live-path signature described under
[the aggregated repair](#the-aggregated-repair-and-why-reopening-a-room-fixes-reactions).

Everything else that rides a `meta`-less room object was lost the same way: poll votes, reaction
redactions, `com.beeper.message_send_status`. Plain messages were never affected — they carry
`meta` (the room preview and sorting timestamp change), so `hasRoomChanges` was always true.

### Per-event handling

In `AppViewModel.processSyncEventsArray`, when `isReactionEvent(event)`:

1. `RoomTimelineCache.mergePaginatedEvents(roomId, listOf(event))` — cached for persistence
2. If `event.redactedBy != null` → `removeReaction(reactionEvent)` (handles the case where the backend already applied the redaction before delivery)
3. Otherwise → `processReactionEvent(reactionEvent)` → updates `messageReactions`

`processReactionEvent` dedups on the **reaction's own event ID** in `processedReactions: MutableSet<String>` (a `LinkedHashSet`, trimmed oldest-first to the last 100 entries), falling back to `"${sender}_${emoji}_${relatesToEventId}"` only when no event ID is available. What actually gets delivered twice is the same event, so that is the true idempotency key.

It used to key on the logical `sender_emoji_target` triple, which **latched**: the key was only ever cleared by a matching redaction, so if that redaction was missed the user's next identical reaction was silently dropped at the guard forever. Symptom: the reaction sends fine and never renders. Missed redactions were made likely by the E2EE routing bug below, which kept the reaction out of `cache.reactionEvents` so `findEventForReply` could not resolve what the redaction pointed at.

### An `m.reaction` never removes

`applyReactionToBucket` is **idempotent**: a second `m.reaction` for the same
`(sender, emoji, target)` is a no-op, not a toggle. Removal has exactly one source — a
`m.room.redaction` of the reaction event, routed to `removeReaction`. Tapping a badge in this app
sends a *new* `m.reaction` and lets the backend redact; there is no client-side un-react.

It used to toggle, and that quietly ate reactions. The event-id dedup guard catches re-delivery of
the *same* event, but not a second event for the same logical reaction — which is exactly what your
own sends produce. gomuks delivers a pending copy first:

```jsonc
{"event_id": "$ufRXIO…", "sender": "@you:…", "type": "m.reaction",
 "timeline_rowid": -1, "transaction_id": "hicli-mautrix-go_…", "send_error": "not sent",
 "content": {"m.relates_to": {"rel_type": "m.annotation", "event_id": "$target", "key": "❤️"}}}
```

…and later supersedes it with the confirmed event under a different id. The badge appeared, then
vanished. That is why lost reactions were overwhelmingly *your own*: other people's reactions reach
you once, already confirmed. Note `send_error` here is top-level, while the app only reads it from
`local_content`, so nothing marks this copy as provisional — the ingest must be idempotent instead.

The `send_complete` handler's existing "skip our own reactions … prevents the double processing that
causes the toggle behavior" guard was an earlier patch for this same bug on a different path.

### E2EE-wrapped reactions

In an encrypted room a reaction can arrive as `m.room.encrypted` with the real type in `decrypted_type`, and with `m.relates_to` in the `decrypted` payload rather than in `content` (which holds ciphertext). **Every ingest, cache-routing and render-filter site must test `isReactionEvent(event)` and read `reactionContent(event)`**, never `event.type` / `event.content` directly — same rule polls follow via `pollEventType`, and redactions via their explicit `m.room.encrypted && decryptedType == "m.room.redaction"` arm.

Getting this wrong is quiet and compounding: the wrapped reaction misses the reaction branch, falls through to the message branch, is pushed into the event chain as a timeline row (the render skip misses it for the same reason), and is cached in `cache.events` instead of `cache.reactionEvents` — so it neither renders on its target nor survives a reopen, and later redactions of it cannot resolve.

### Rooms open in a secondary VM

`processSyncEventsArray` runs for `currentRoomId` only. For rooms open in a chat bubble or `ShortcutActivity`, `checkAndUpdateCurrentRoomTimelineOptimized` calls `ReactionCoordinator.ingestReactionsFromSync(roomId, events)`, which applies reactions and the reaction half of redaction handling for those rooms. Both paths funnel into the shared `handleSyncReactionEvent` so they cannot drift.

Without it, refreshing `RoomTimelineCache` alone left bubble badges frozen: reaction state is a separate singleton keyed by target event ID, not part of the timeline cache. See [docs/WEBSOCKET_LIFECYCLE.md](WEBSOCKET_LIFECYCLE.md#state-that-lives-outside-the-timeline-cache-needs-its-own-ingest).

## Paginate Path (`m.reaction` in a paginate/timeline response)

A paginate response carries reactions **twice**: as a flattened `reactions: {emoji: count}` map on the target message event, *and* as the individual `m.reaction` events (with their event IDs and `m.relates_to`) inside the `events` array. `TimelineCacheCoordinator.processEventsArray` handles the individual events:

1. `reactionCoordinator.processReactionFromTimeline(event)` → `processReactionEvent(isHistorical = true)` → per-user buckets in `messageReactions` (skips events with `redactedBy != null`). `isHistorical` also tells `applyReactionToBucket` it may fill in `users` but must not inflate `count` — see [bucket shapes](#the-two-bucket-shapes).
2. The event is collected into `paginatedReactionEvents` and, after the loop, merged via `RoomTimelineCache.mergePaginatedEvents` into `cache.reactionEvents`

**Step 2 is load-bearing and easy to miss:** reaction events are filtered out of `timelineList` (they must never render as timeline rows), and every *other* cache write in this path operates on `timelineList`. Without the explicit merge, paginated reaction events are processed into `messageReactions` but **never persisted to `RoomTimelineCache.reactionEvents`** — so a later live `m.room.redaction` for one of them can't be resolved by `findEventForReply` and the reaction can never be removed until the room is re-paginated. (The live sync path avoids this because it merges the full event set, including reactions, via `mergePaginatedEvents`.)

### Reactions must never become timeline rows

Every screen that builds `TimelineItem`s has to skip them: `RoomTimelineScreen`, `BubbleTimelineScreen` and `ThreadViewerScreen` (which whitelists `m.reaction` in `allowedEventTypes` and long lacked the skip). A reaction that slips through renders nothing — `TimelineEventItem` returns early — but still emits a date divider, becomes the `previousEvent` that breaks `isConsecutive` grouping for the next message, and shifts sticky-date and scroll indices. An E2EE-wrapped one renders as an empty undecryptable bubble instead. Use `isReactionEvent`, not `event.type`.

## Reaction Redaction Path (`m.room.redaction` via `sync_complete`)

**Critical invariant:** when `m.room.redaction` arrives, the live sync handler looks for the redacted event in `timelineEvents`. Because reactions are **not** in `timelineEvents`, this lookup always fails for reaction redactions.

The handler therefore also looks the event up in `RoomTimelineCache.findEventForReply()`. If found and it is a `m.reaction`, it calls `removeReaction()` with the extracted sender/emoji/relatesToEventId:

```kotlin
val cachedRedacted = RoomTimelineCache.findEventForReply(roomId, redactsEventId)
if (cachedRedacted != null && isReactionEvent(cachedRedacted)) {
    val reactionEvent = extractReactionEventFromTimeline(cachedRedacted)
    if (reactionEvent != null) removeReaction(reactionEvent)
}
```

Without this second lookup, a reaction that was added before its redaction arrived would persist in `messageReactions` indefinitely.

**`findEventForReply` must search `reactionEvents`:** Reaction events are stored in `cache.reactionEvents`, a list separate from `cache.events` and `cache.replyContextEvents`. `findEventForReply` searches all three buckets. If it only searched `events`/`replyContextEvents`, the lookup above would always return `null` for reaction redactions and the removal would silently be skipped.

## Historical / Cache Restore Path

When a room is opened or `restoreFromLruCache` is called, `ReactionCoordinator.loadReactionsForRoom` iterates over cached reaction events (`RoomTimelineCache.getCachedReactionEvents`) and calls `processReactionFromTimeline` for each. That function skips events where `event.redactedBy != null`, so reactions that arrived already-redacted are never added.

## `removeReaction` Internals

`ReactionCoordinator.removeReaction`:
1. **Idempotency guard** — adds the redacted reaction's `eventId` to `AppViewModel.redactedReactionEventIds` (LRU-trimmed to 200/100) and returns early if it was already present. A single logical redaction reaches `removeReaction` twice: once from the `m.room.redaction` handler (via `findEventForReply`) and once from the `m.reaction`-with-`redactedBy` branch when the backend re-sends the reaction event in the same sync batch. Both derive the same reaction `eventId`, so the guard collapses them into one decrement. (Falls back to the logical `sender_emoji_target` key only if `eventId` is blank.)
2. Mutates the target's bucket under `MessageReactionsCache.mutate`. Finds the emoji bucket — no-op if absent. Removes the sender from `users`/`userReactions` **if present**, then **decrements `count` regardless**: `newCount = (count - 1).coerceAtLeast(userReactions.size)`
3. If `newCount <= 0`, removes the bucket entirely; otherwise writes it back with the new count
4. The cache change listener bumps `reactionUpdateCounter` to trigger UI refresh

It no longer un-latches `processedReactions`: with event-ID dedup, a re-add after a redaction is a new event with a new ID.

### The two bucket shapes

Every bucket transform has to survive both, and **`count` must never be recomputed from `userReactions.size`**:

| Shape | Origin | `users` / `userReactions` | `count` |
|---|---|---|---|
| **Per-user** | live `m.reaction` via `processReactionEvent` | populated | `== userReactions.size` |
| **Count-only** | backend-normalized `aggregatedReactions` via `applyAggregatedReactionsFromEvents` (runs at room open, initial build, related-events) | **empty** | from the backend |

Right after a room opens, buckets are the **count-only** kind, because `applyAggregatedReactionsFromEvents` has just rebuilt them from the backend's flattened counts.

**On removal:** the old code identified the reaction to strip solely by finding the sender in `userReactions` and returned early when absent. That silently failed for count-only buckets. Repro: react A, react B, delete A while the room is opening — the aggregation snapshot (taken before the delete propagates) populates `{A, B}` as count-only buckets, then the live redaction of A no-ops, leaving **A + B**. Decrementing `count` unconditionally (guarded for idempotency by step 1) fixes both shapes.

**On add:** `applyReactionToBucket` had the mirror-image bug for years longer. It recomputed `count = updatedUserReactions.size` on both branches. Against a count-only bucket the sender is never found in the empty `userReactions`, so the add branch ran and reset `count` to **1** regardless of what the backend said. Symptom: tapping a badge someone else placed sent fine but the count never moved (1 → 1), and on a busier message it visibly dropped (5 → 1). Now:

- **live add** — `count = maxOf(count + 1, userReactions.size)`
- **historical replay** — `count = maxOf(count, userReactions.size)`; the aggregated count already includes these events, so a replay may only fill in `users`, never inflate
- **toggle-off** — `count = (count - 1).coerceAtLeast(userReactions.size)`, mirroring `removeReaction`, so a mixed bucket does not discard reactors it knows about only as a number

### The aggregated repair, and why reopening a room "fixes" reactions

`applyAggregatedReactionsFromEvents` (room open, initial build, `related_events`) reconciles each bucket against the backend's flattened `reactions` map: take the larger count per emoji, union in any emoji no individual event was seen for. It is a full repair from server truth, and the live path has **no equivalent**. That is why every live-path defect above presented identically — invisible until close/reopen or a paginate — and why a live-path regression can hide for a long time.

## `MessageReaction` Data Model

```kotlin
data class MessageReaction(
    val emoji: String,
    val count: Int,
    val users: List<String>,           // user IDs for quick membership check
    val userReactions: List<UserReaction>  // userId + timestamp for ordering
)
```

There is no per-reaction `eventId` stored in `MessageReaction`. Which *bucket* a removal targets is located by `(emoji, relatesToEventId)`, and the sender is removed from that bucket when present. The redacted reaction's own `eventId` (carried on the `ReactionEvent`, not on `MessageReaction`) is used only as the idempotency key in `redactedReactionEventIds` — see [`removeReaction` Internals](#removereaction-internals).
