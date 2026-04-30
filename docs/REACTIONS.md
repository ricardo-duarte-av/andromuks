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

## Historical / Cache Restore Path

When a room is opened or `restoreFromLruCache` is called, `ReactionCoordinator.restoreReactionsFromCache` iterates over cached `m.reaction` events and calls `processReactionFromTimeline` for each. That function skips events where `event.redactedBy != null`, so reactions that arrived already-redacted are never added.

## `removeReaction` Internals

`ReactionCoordinator.removeReaction`:
1. Removes the logical key from `processedReactions` (so the sender can re-react with the same emoji later without being blocked by the dedup guard)
2. Looks up `messageReactions[relatesToEventId]` — returns early if the target message has no reactions
3. Finds the emoji bucket and the user's entry; removes the user
4. If the bucket is now empty, removes it entirely
5. Increments `reactionUpdateCounter` and `updateCounter` to trigger UI refresh

## `MessageReaction` Data Model

```kotlin
data class MessageReaction(
    val emoji: String,
    val count: Int,
    val users: List<String>,           // user IDs for quick membership check
    val userReactions: List<UserReaction>  // userId + timestamp for ordering
)
```

There is no per-reaction `eventId` stored in `MessageReaction`. Reaction identity for removal is therefore `(sender, emoji, relatesToEventId)`, not event ID.
