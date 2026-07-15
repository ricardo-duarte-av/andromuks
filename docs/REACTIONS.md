# Reactions — Lifecycle, Storage, and Redaction

## Storage

Reactions are **never added to `timelineEvents`** or the `eventChainMap`. They live in two places:

| Store | Purpose |
|---|---|
| `RoomTimelineCache` (via `mergePaginatedEvents`) | Persistence — reactions survive room navigation and are replayed on re-open |
| `AppViewModel.messageReactions: Map<String, List<MessageReaction>>` | In-memory rendering state — key is the target event ID |

## Live Sync Path (`m.reaction` via `sync_complete`)

In `AppViewModel.processSyncEventsArray`, when `event.type == "m.reaction"`:

1. `RoomTimelineCache.mergePaginatedEvents(roomId, listOf(event))` — cached for persistence
2. If `event.redactedBy != null` → `removeReaction(reactionEvent)` (handles the case where the backend already applied the redaction before delivery)
3. Otherwise → `processReactionEvent(reactionEvent)` → updates `messageReactions`

`processReactionEvent` uses a `processedReactions: MutableSet<String>` dedup guard keyed on `"${sender}_${emoji}_${relatesToEventId}"` to prevent double-counting when the same reaction arrives in multiple sync batches. The dedup guard is LRU-trimmed to the last 100 entries.

## Paginate Path (`m.reaction` in a paginate/timeline response)

A paginate response carries reactions **twice**: as a flattened `reactions: {emoji: count}` map on the target message event, *and* as the individual `m.reaction` events (with their event IDs and `m.relates_to`) inside the `events` array. `TimelineCacheCoordinator.processEventsArray` handles the individual events:

1. `reactionCoordinator.processReactionFromTimeline(event)` → `processReactionEvent(isHistorical = true)` → per-user buckets in `messageReactions` (skips events with `redactedBy != null`)
2. The event is collected into `paginatedReactionEvents` and, after the loop, merged via `RoomTimelineCache.mergePaginatedEvents` into `cache.reactionEvents`

**Step 2 is load-bearing and easy to miss:** reaction events are filtered out of `timelineList` (they must never render as timeline rows), and every *other* cache write in this path operates on `timelineList`. Without the explicit merge, paginated reaction events are processed into `messageReactions` but **never persisted to `RoomTimelineCache.reactionEvents`** — so a later live `m.room.redaction` for one of them can't be resolved by `findEventForReply` and the reaction can never be removed until the room is re-paginated. (The live sync path avoids this because it merges the full event set, including reactions, via `mergePaginatedEvents`.)

## Reaction Redaction Path (`m.room.redaction` via `sync_complete`)

**Critical invariant:** when `m.room.redaction` arrives, the live sync handler looks for the redacted event in `timelineEvents`. Because reactions are **not** in `timelineEvents`, this lookup always fails for reaction redactions.

The handler therefore also looks the event up in `RoomTimelineCache.findEventForReply()`. If found and it is a `m.reaction`, it calls `removeReaction()` with the extracted sender/emoji/relatesToEventId:

```kotlin
val cachedRedacted = RoomTimelineCache.findEventForReply(roomId, redactsEventId)
if (cachedRedacted != null && cachedRedacted.type == "m.reaction") {
    val reactionEvent = extractReactionEventFromTimeline(cachedRedacted)
    if (reactionEvent != null) removeReaction(reactionEvent)
}
```

Without this second lookup, a reaction that was added before its redaction arrived would persist in `messageReactions` indefinitely.

**`findEventForReply` must search `reactionEvents`:** Reaction events are stored in `cache.reactionEvents`, a list separate from `cache.events` and `cache.replyContextEvents`. `findEventForReply` searches all three buckets. If it only searched `events`/`replyContextEvents`, the lookup above would always return `null` for reaction redactions and the removal would silently be skipped.

## Historical / Cache Restore Path

When a room is opened or `restoreFromLruCache` is called, `ReactionCoordinator.restoreReactionsFromCache` iterates over cached `m.reaction` events and calls `processReactionFromTimeline` for each. That function skips events where `event.redactedBy != null`, so reactions that arrived already-redacted are never added.

## `removeReaction` Internals

`ReactionCoordinator.removeReaction`:
1. **Idempotency guard** — adds the redacted reaction's `eventId` to `AppViewModel.redactedReactionEventIds` (LRU-trimmed to 200/100) and returns early if it was already present. A single logical redaction reaches `removeReaction` twice: once from the `m.room.redaction` handler (via `findEventForReply`) and once from the `m.reaction`-with-`redactedBy` branch when the backend re-sends the reaction event in the same sync batch. Both derive the same reaction `eventId`, so the guard collapses them into one decrement. (Falls back to the logical `sender_emoji_target` key only if `eventId` is blank.)
2. Removes the logical key from `processedReactions` (so the sender can re-react with the same emoji later without being blocked by the dedup guard)
3. Looks up `messageReactions[relatesToEventId]` — returns early if the target message has no reactions
4. Finds the emoji bucket. Removes the sender from `users`/`userReactions` **if present**, then **decrements `count` regardless**: `newCount = (count - 1).coerceAtLeast(userReactions.size)`
5. If `newCount <= 0`, removes the bucket entirely; otherwise writes it back with the new count
6. Increments `reactionUpdateCounter` and `updateCounter` to trigger UI refresh

### Why step 4 decrements count even when the sender isn't found

A `MessageReaction` bucket has two possible shapes:

| Shape | Origin | `users` / `userReactions` | `count` |
|---|---|---|---|
| **Per-user** | live `m.reaction` via `processReactionEvent` | populated | `== userReactions.size` |
| **Count-only** | backend-normalized `aggregatedReactions` via `applyAggregatedReactionsFromEvents` (runs at room open, initial build, related-events) | **empty** | from the backend |

The old code identified the reaction to strip solely by finding the sender in `userReactions` and returned early when absent. That silently failed for **count-only** buckets, which have no per-user entries. Repro: react A, react B, delete A while the room is opening — the aggregation snapshot (taken before the delete propagates) populates `{A, B}` as count-only buckets, then the live redaction of A no-ops, leaving **A + B**. Decrementing `count` unconditionally (guarded for idempotency by step 1) fixes both shapes.

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
