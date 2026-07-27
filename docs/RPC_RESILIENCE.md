# RPC resilience — retries, transport fallback, and why there are no timeouts

`RpcResilienceCoordinator` owns the *lifecycle* of **replay-safe** RPC requests: how many times one is
sent, over which transport, and what happens when the connection dies underneath it. Feature code
submits a logical request and gets exactly one answer.

Source: [`RpcResilienceCoordinator.kt`](../app/src/main/java/net/vrkknn/andromuks/RpcResilienceCoordinator.kt).

## The rule: completion is an event, never a clock

The backend always answers a command it received — `response` or `error`
([RPC spec](https://spec.mau.fi/gomuks/rpc.html)). So there is nothing for a wall-clock timeout to
measure. The states where an answer genuinely cannot arrive are all local and observable:

| Signal | Meaning | Action |
|---|---|---|
| `response` frame | backend answered | terminal success |
| `error` frame | backend answered "no" | terminal failure, negative-cached |
| send returned non-`SUCCESS` | never left the device | retry / switch transport |
| socket torn down in flight | answer can never arrive | park, reissue on reconnect |
| command gate closed (`canSendCommandsToBackend == false`) | answer *will* arrive, late | take `/exec` instead of waiting |

### The bug this replaced

`getEvent` used to arm a fixed 10 s timeout that removed the request from `eventRequests` and called
back with `null`. Two things made that actively harmful:

1. `get_event` "uses the database if possible, but will fetch from the homeserver if the event isn't
   found locally" — a homeserver round-trip for an old event routinely outlives 10 s.
2. When the real response then arrived, `handleEventResponse` found no entry for its `request_id` and
   returned silently. **The answer was received and discarded.**

The caller (a reply preview) latched that `null` into a permanent `fetchFailed` flag and rendered
"Reply to unknown event" for an event the backend had just successfully returned. See
[TIMELINE_EVENTS.md](TIMELINE_EVENTS.md) for the reply-target resolution order this feeds.

## Replay safety

Only **replay-safe** commands belong here — re-asking costs a round-trip and nothing else. Currently
migrated:

| Command | Caller | Fails fast when unreachable? |
|---|---|---|
| `get_event` | reply-target resolution, paginate/sync prefetch | no — background, self-heals on reconnect |
| `get_event_context` | `EventContextScreen` | yes |
| `paginate_manual` | `ThreadViewerScreen` | yes |
| `search_local` / `search_server` | `SearchResultsScreen` | yes |
| `paginate` (gallery) | media gallery | yes |
| `get_mentions` | `MentionsScreen` | yes |

**Replay-unsafe** commands (`send_message`, redactions, state sets) must NOT use this class. They
keep their existing send-once paths, and their offline handling stays with
`WebSocketCommandSender.queueOfflineRetry` / the `LocalEchoCoordinator` placeholder — see
[MESSAGE_SENDING.md](MESSAGE_SENDING.md).

## Transport selection

WebSocket when it is connected **and** the command gate is open; `/exec` otherwise. The `/exec`
endpoint is independent of the socket, which covers two distinct cases:

- **Socket down** — battery-saver, reconnect backoff, network flap.
- **Cold start** — the socket is up but `canSendCommandsToBackend` is still false, so a WS command
  would sit in `pendingCommandsQueue` behind the entire initial sync. A reply-target lookup takes
  HTTP instead and resolves immediately.

Both transports converge on the same request-tracking maps and the same `AppViewModel.handleResponse`
dispatcher, so callers cannot tell which one served them. No `txn_id` envelope is attached — that is
for retried *non-idempotent* commands only (see [EXEC_ENDPOINT.md](EXEC_ENDPOINT.md)).

## Guarantees

- **Exactly one callback** per submitted request, on the main thread.
- **Coalescing** by `RpcSpec.dedupKey`: fifteen reply previews pointing at the same message issue one
  request and share the answer.
- **First terminal answer wins** — a late WS response racing an `/exec` answer for the same logical
  request is discarded, not double-delivered. Enforced by matching the delivering `requestId` against
  the attempt currently in flight.
- **Bounded**: `MAX_ATTEMPTS = 3`. A connection loss *refunds* the attempt (it isn't the request's
  fault), so a flapping socket cannot exhaust the budget on its own.
- **Negative cache**: a definitive "no" is remembered for 5 minutes so a re-composing UI cannot
  hammer the backend for an event that does not exist. Cleared on reconnect, because "not found" may
  have been a property of the old connection.

## `parkWhenUnreachable` — the one thing that is still allowed to give up

When *no* transport is reachable (socket down and `/exec` unusable too), there is a genuine choice:

- `true` (default) — park and reissue on reconnect. Right for background work like reply-target
  lookups. Nobody is watching, and it heals itself.
- `false` — settle `null` immediately. Right for anything a user is waiting on. A search must not
  spin indefinitely because the device is offline; the screen shows its empty/error state and the
  user can retry.

This is **not** a timeout in disguise. Once a request is actually out, we wait as long as the backend
needs. The flag only decides whether to wait for the *network* to come back. A connection loss with a
`false` request in flight settles it right away for the same reason.

## Error frames must be wired before a timeout is removed

`searchRequests`, `threadPaginateRequests` and `galleryPaginateRequests` had **no** `handleError`
branch — an `error` frame fell through to the "Unknown error requestId" log and the request stayed
pending. Their per-call timeouts were quietly doing double duty as the error path (reported to the
user as "no results", ~15 s late); the gallery had no timeout at all, so its spinner never stopped.

If you migrate another command, check `handleError` covers its map first. Removing a timeout from a
command whose errors are unhandled turns a slow failure into a permanent one.

## UI contract: three states, not two

A resolution is *resolved*, *resolving*, or *unavailable*. Composables must not render "unavailable"
while a lookup is in flight — that is what made a transient miss look like data loss. In the reply
path, `rememberReplyTargetEvent` returns `ReplyTargetResolution(event, resolving)` and `ReplyPreview`
renders a placeholder for the resolving case.

Composables must also **not latch failure locally**. Instead, key the retry `LaunchedEffect` on
`AppViewModel.rpcRetryGeneration`, which the coordinator bumps whenever the backend becomes reachable
again. The coordinator's negative cache — not a per-composable boolean — is what stops re-asking.

## Reading the instrumentation

`ReplyResolutionTracker` counts how each reply preview got its target, under the Androlog category
`ReplyResolution`. The tiers, and what they mean:

| Tier | Meaning |
|---|---|
| `timeline` | found among events the screen was already rendering |
| `cache` | `RoomTimelineCache`, real timeline event |
| `replyContext` | the reply-context bucket — the backend's `related_events` did its job |
| `reaction` | the reaction bucket |
| `fetched` | needed a `get_event` round-trip |
| `unresolved` | nothing found; the preview showed "Reply to unknown event" |

**`fetched` is a defect signal, not a success.** The backend supplies a reply's target alongside the
reply itself in `related_events`, so a healthy session resolves essentially everything from the first
three tiers. A climbing `fetched` count means some ingest path is losing that context and the failsafe
is covering for it — the preview looks right while the underlying leak stays invisible. That is
precisely how the original bug hid.

Individual `fetched` and `unresolved` resolutions each write an Androlog entry (they should be rare);
a rolling summary lands every 100 resolutions:

```
resolved 100: timeline=71 cache=22 replyContext=6 reaction=0 fetched=1 unresolved=0
```

`RpcResilienceCoordinator` adds `RPC`-category entries when a request succeeds only on a later attempt
(the situation the old fixed timeout mis-reported as "no such event") and when one exhausts its
budget.

## Adding a command

```kotlin
rpcResilience.submit(
    RpcResilienceCoordinator.RpcSpec(
        command = "get_event",
        dedupKey = "get_event:$roomId:$eventId",
        data = mapOf("room_id" to roomId, "event_id" to eventId),
        // Register the id in whatever map handleResponse routes this command by, wiring its
        // handler to call `deliver`.
        register = { requestId, deliver ->
            eventRequests[requestId] = roomId to { event: TimelineEvent? -> deliver(event) }
        },
        unregister = { requestId -> eventRequests.remove(requestId) },
    ),
) { payload -> callback(payload as? TimelineEvent) }
```

Do not add a timeout. If you think you need one, the question to answer first is *which of the five
signals above* you are actually waiting on.

## Where "connection ready" is signalled

`onConnectionReady()` is what unparks requests, clears the negative cache and bumps
`rpcRetryGeneration`. It must fire on **every** path that opens the command gate, and there are two:

- `flushPendingCommandsQueue()` — the gate transitioning closed→open (initial connect, `sync_status`
  early unblock, room-state load completing).
- `setWebSocket()` — a **resume reconnection** sets `canSendCommandsToBackend = isReconnecting`
  *by assignment*, never touching the flush path. This one was missed initially, which meant parked
  requests were never reissued on the most common reconnect path — the exact self-healing property
  the design exists for.

The `setWebSocket` call sits *after* `WebSocketService.setWebSocket(webSocket)`: until the service
holds the socket, `isWebSocketConnected()` returns false and reissued requests would be routed over
`/exec` instead of the connection just established.

If you add another way to open the gate, signal the registry from it too.

## Payload convention

The delivery channel is a single `Any?`. A **non-null** payload means the backend answered —
*including* answering with an error, which is why `EventContextResult` carries the server's message
instead of collapsing to null. Retrying a command the backend explicitly refused is pointless, so
those settle terminally and are not negative-cached. A **null** delivery means no answer was
obtainable at all (attempts exhausted, or unreachable with `parkWhenUnreachable = false`).

## Not migrated, and why

No per-request wall-clock timers remain in `AppViewModel`. What is left is deliberately different:

- **`requestRoomProfilesForRender`'s 5 s** is a *render deadline*, not a request timeout: it decides
  when to paint the timeline anyway rather than what a request means. Keep it.
- **`requestFullUserInfo`'s 10 s** covers an aggregate of three independent sub-requests, so it is a
  screen-level budget rather than an RPC lifecycle. Migrating it means migrating its parts first.
- **Fire-and-forget commands** with no per-request callback (`get_room_state`, profile fetches,
  `mark_read`) never had timeouts and settle through their own state paths. They would need a
  delivery hook like the one `get_mentions` gained before they could join.
