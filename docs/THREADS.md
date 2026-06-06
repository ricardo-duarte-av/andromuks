# Threads (`ThreadViewerScreen`)

How threads are rendered, how a thread is opened from the timeline, and how a brand-new thread is started from a single message.

## Data model

A thread is a set of messages that carry an `m.thread` relation pointing at a **root** event:

| Piece | Where it lives |
|---|---|
| Thread root | A normal message — it has **no** `m.thread` relation itself |
| Thread reply | `m.relates_to.rel_type = m.thread`, `m.relates_to.event_id = <root>` |

`TimelineEvent.isThreadMessage()` returns `true` only for **replies** (`relationType == "m.thread" && relatesTo != null`). It is `false` for the root — the root is just a plain message that happens to have replies hanging off it. See [docs/TIMELINE_EVENTS.md](TIMELINE_EVENTS.md) for the relation field layout.

## How `ThreadViewerScreen` builds its list

Route: `thread_viewer/{roomId}/{threadRootId}` (registered in `MainActivity`). The screen takes `roomId` + `threadRootEventId` and assembles its messages from two sources, merged and deduped by `eventId`, sorted by timestamp:

1. **`liveThreadMessages`** — `derivedStateOf { appViewModel.getThreadMessages(roomId, threadRootEventId) }`. `getThreadMessages` reads the loaded `timelineEvents` (or `RoomTimelineCache` when the timeline has been cleared), adds the **root first** (found by id), then appends every reply where `isThreadMessage() && relatesTo == root`. Compose tracks the read, so this recomputes automatically as sync delivers new replies.
2. **`backfilledThreadEvents`** — fetched once on open via `appViewModel.paginateThread(...)` (the `paginate_manual` thread variant). `paginate_manual` returns **only replies**, never the root, so the root is sourced separately: if it's already in the loaded timeline the live merge surfaces it; otherwise a `get_event` fetches it explicitly.

The live copy wins on conflict (it carries sync-applied edits/reactions). `threadRootEvent` is then `threadMessages.firstOrNull { it.eventId == threadRootEventId }`.

## Opening a thread from the long-press menu

The More dropdown ([docs/MESSAGE_MENU.md](MESSAGE_MENU.md)) has two mutually-exclusive thread entries, gated on `event.isThreadMessage()`:

- **Thread** (reply → its root): `onViewInThread`, target = `getThreadInfo().threadRootEventId`.
- **Start thread / View thread** (non-reply → itself as root): `onStartOrViewThread`, target = `event.eventId`.

Both navigate to `thread_viewer/$roomId/$encodedRoot` and set `appViewModel.threadReturnScrollEventId` for scroll-back.

## Starting a brand-new thread

Selecting **Start thread** on a plain message opens `ThreadViewerScreen` with that message's id as `threadRootEventId`. No special "new thread" mode is needed, because:

- The root is on-screen (it was just long-pressed), so it's in `timelineEvents` → `getThreadMessages` returns exactly `[root]`, and `threadRootEvent` resolves to it. The screen renders the single message plus the composer.
- `paginateThread` runs on open for an event that has no thread relation yet. This is harmless: it returns no replies, and since the root is loaded the screen takes the `backfilledThreadEvents = events` (empty) branch — the root still comes from `liveThreadMessages`, so there is no blank-screen window.
- The thread **materializes on the first send**: the composer calls `sendThreadReply(roomId, text, threadRootEventId = <root>)`, the homeserver creates the `m.thread` relation, the reply syncs back as a thread message, and `getThreadMessages` picks it up via `liveThreadMessages`.

### Start vs. View label

`MessageMenuConfig.startThreadIsExisting` drives the label: `AppViewModel.hasThreadReplies(roomId, eventId)` scans the same loaded-event source as `getThreadMessages` for any reply with `relatesTo == eventId`. A root that already has replies loaded reads **View thread**; a plain message reads **Start thread**. This is best-effort, bounded by what is currently loaded — a root whose replies have all scrolled out of the loaded window may still read "Start thread", but the navigation target is identical either way, so it opens the existing thread correctly.
