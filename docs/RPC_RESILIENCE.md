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

Only **replay-safe** commands belong here — re-asking costs a round-trip and nothing else:
`get_event`, `get_event_context`, `get_room_state`, `paginate`, `get_related_events`, `search_*`,
`mark_read`.

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

## UI contract: three states, not two

A resolution is *resolved*, *resolving*, or *unavailable*. Composables must not render "unavailable"
while a lookup is in flight — that is what made a transient miss look like data loss. In the reply
path, `rememberReplyTargetEvent` returns `ReplyTargetResolution(event, resolving)` and `ReplyPreview`
renders a placeholder for the resolving case.

Composables must also **not latch failure locally**. Instead, key the retry `LaunchedEffect` on
`AppViewModel.rpcRetryGeneration`, which the coordinator bumps whenever the backend becomes reachable
again. The coordinator's negative cache — not a per-composable boolean — is what stops re-asking.

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

## Still to migrate

`getEventContext` and several other request maps still use per-call timers and are still orphaned on
socket teardown (`onWebSocketCleared` drains only the coordinator's registry). Moving them onto
`RpcSpec` is mechanical and tracked as follow-up work.
