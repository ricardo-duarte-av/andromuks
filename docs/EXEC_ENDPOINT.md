# The `/exec` HTTP endpoint (`ExecApi`)

`POST <homeserver_url>/_gomuks/exec/{command}` runs a single gomuks RPC command over plain HTTP,
outside the persistent WebSocket. It exists for the moments the socket is closed — battery-saver
mode while backgrounded, and FCM wake-ups that must touch app state before the socket reconnects.

- **Request body** is the command's `data` field, byte-identical to a WebSocket frame's `data`.
- **Response body** is the command's result, byte-identical to the `data` of a WebSocket `response`
  frame. That equivalence is what lets [`ExecCommandCoordinator`](../app/src/main/java/net/vrkknn/andromuks/ExecCommandCoordinator.kt)
  plumb `/exec` responses through the same `AppViewModel.handleResponse` dispatcher the socket uses.
- **Status codes**: `200` success, `418` command error, `401` missing/invalid auth cookie,
  `400` idempotency-envelope rejection (see below).
- **Auth** is the existing `gomuks_auth` cookie (token from `CredentialStore`).

Everything lives in [`ExecApi.kt`](../app/src/main/java/net/vrkknn/andromuks/utils/ExecApi.kt).

## Callers

| Caller | Command | Retried? | Idempotency envelope |
|---|---|---|---|
| `NotificationReplyReceiver` (battery-saver quick reply) | `send_message` via `ExecApi.sendMessage` | yes | **yes** |
| `NotificationMarkReadReceiver` (battery-saver mark-read) | `mark_read` via `ExecApi.markRead` | yes | yes (cheap no-op) |
| `ExecCommandCoordinator` (FCM timeline hydrate) | `paginate` etc. | no | no |
| `NotificationImageWorker` | `get_event` | no | no |
| `RpcResilienceCoordinator` (replay-safe reads: socket down **or** command gate still closed) | `get_event` | yes | no — read-only, see below |

`RpcResilienceCoordinator` is the one caller that uses `/exec` as a *routine alternative transport*
rather than an offline fallback: a replay-safe read issued while `canSendCommandsToBackend` is false
takes HTTP instead of queueing behind the initial sync. It deliberately omits the envelope — re-running
a read costs a round-trip and nothing else, so there is nothing to de-duplicate. See
[RPC_RESILIENCE.md](RPC_RESILIENCE.md).

## Idempotency / de-duplication (`txn_id`)

Upstream gomuks commit *"server: add optional transaction ID to /exec request"*
([`d7506029`](https://github.com/gomuks/gomuks/commit/d7506029a62299515356f72061d36ae35e9c9590))
added an optional `?txn_id=<id>&start_ts=<clientMillis>` query pair to `/exec`.

Server behaviour (`pkg/gomuks/execbuffer.go`):

- **No `txn_id`** → the command runs exactly as before. Omitting the params is fully backward
  compatible; an older server ignores them entirely.
- **With `txn_id`** → the server keeps a **5-minute** execution buffer keyed by `txn_id`. The first
  request runs the command and caches its result/error; any request carrying the **same** `txn_id`
  within the window (concurrent *or* later) blocks on the in-flight call and returns the first
  attempt's cached outcome instead of re-executing. This is de-duplication at the RPC layer, not the
  Matrix transaction layer.
- **`start_ts`** (device wall-clock, millis) is required whenever `txn_id` is set and drives two
  clock-skew guards, both keyed off the 5-min buffer lifetime:
  - clock **> 30 s ahead** → HTTP 400 `FI.MAU.GOMUKS.TIME_DESYNC`
  - clock **> 2.5 min behind** → HTTP 400 `FI.MAU.GOMUKS.REQUEST_EXPIRED`

### How `ExecApi` uses it

The envelope only matters for commands we **retry**, because de-dup only pays off when the same
`txn_id` is reused across attempts. So:

- `sendMessage` and `markRead` go through the private `execWithIdempotentRetry` helper. It mints
  one `Idempotency` (random `txn_id` + `start_ts` captured **once**) and retries transient
  `NetworkError`s under that **same** envelope (`MAX_RETRY_ATTEMPTS`, `RETRY_BACKOFF_MS` backoff).
  A landed-but-unacknowledged first attempt — the classic double-send trap for the non-idempotent
  `send_message` — is collapsed by the server on retry instead of sending twice.
- `execRaw` takes an optional `idempotency: Idempotency? = null`. Passing `null` (the default) keeps
  the pre-existing single-shot, no-`txn_id` behaviour, so `ExecCommandCoordinator` and
  `NotificationImageWorker` are unaffected.

### The two design constraints, and why

1. **Reuse the *same* envelope across retries.** A fresh `txn_id` per attempt would de-dup nothing.
   `Idempotency` is built once per logical action and threaded through every retry unchanged —
   including `start_ts`, so a slow retry can't age past the server's expiry window on its own clock
   value.
2. **A rejected envelope must not wedge the action.** `TIME_DESYNC` / `REQUEST_EXPIRED` mean the
   command *never ran*. `execRaw` surfaces them as `ExecResult.IdempotencyRejected` (distinct from
   `HttpError`), and `execWithIdempotentRetry` falls back to a **single plain call with no
   `txn_id`** so a mis-clocked device still gets its message/receipt out (a plain call skips the
   clock check entirely).

### Adding a new retried `/exec` command

Build its `data` `JSONObject` and route it through `execWithIdempotentRetry` (not a bare
`execRaw`). Only do this for **non-idempotent** or retry-worthy commands; read-only commands and
anything plumbed back through the WebSocket dispatcher (`ExecCommandCoordinator`) should keep using
`execRaw` with no envelope.

## Upstream references

- Server commit: <https://github.com/gomuks/gomuks/commit/d7506029a62299515356f72061d36ae35e9c9590>
- RPC API spec: <https://spec.mau.fi/gomuks/rpc.html>
