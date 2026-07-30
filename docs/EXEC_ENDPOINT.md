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
- **Auth** is the existing `gomuks_auth` cookie (token from `CredentialStore`), with an HTTP-basic
  fallback on `401` (see below).

Upstream now serves the **entire** RPC command set over `/exec`, not just the handful we happened to
use first — see [the RPC spec's command list](https://spec.mau.fi/gomuks/rpc.html). Note the spec
does not document `/exec` itself; it mentions the endpoint only as the request channel for the SSE
transport.

**The governing rule** (confirmed with the gomuks dev): *`/exec` is the same as using the WebSocket
for the command — parameters and response are identical, except where explicitly noted.* The only
noted exception today is the optional `?txn_id&start_ts` query pair (below), which has no WebSocket
equivalent. So the spec's per-command request/response docs are authoritative for `/exec` too, and a
command's quirks travel with it: an argument that is subtly conditional over the socket is just as
conditional here (see the `paginate_manual` `since` trap in
[THREADS.md](THREADS.md#paginate_manual-parameters--since-is-only-emptyable-for-threads)).

Everything lives in [`ExecApi.kt`](../app/src/main/java/net/vrkknn/andromuks/utils/ExecApi.kt).

## Callers

| Caller | Command | Retried? | Idempotency envelope |
|---|---|---|---|
| `NotificationReplyReceiver` (battery-saver quick reply) | `send_message` via `ExecApi.sendMessage` | yes | **yes** |
| `NotificationMarkReadReceiver` (battery-saver mark-read) | `mark_read` via `ExecApi.markRead` | yes | yes (cheap no-op) |
| `NotificationMuteReceiver` (mute action, no ViewModel) | `update_push_rule` via `ExecApi.muteRoom` | yes | yes (cheap no-op) |
| `ExecCommandCoordinator` (FCM timeline hydrate) | `paginate` etc. | no | no |
| `NotificationImageWorker` | `get_event` | no | no |
| `NotificationEnrichment` (missing room name) | `get_room_summary` via `ExecApi.callObject` | no | no — read-only |
| `RpcResilienceCoordinator` (replay-safe reads: socket down **or** command gate still closed) | `get_event` | yes | no — read-only, see below |

`RpcResilienceCoordinator` is the one caller that uses `/exec` as a *routine alternative transport*
rather than an offline fallback: a replay-safe read issued while `canSendCommandsToBackend` is false
takes HTTP instead of queueing behind the initial sync. It deliberately omits the envelope — re-running
a read costs a round-trip and nothing else, so there is nothing to de-duplicate. See
[RPC_RESILIENCE.md](RPC_RESILIENCE.md).

## Two consumption styles

`/exec` responses are consumed one of two ways, and picking the wrong one is the main foot-gun:

- **`ExecCommandCoordinator.execute(command, data, register)`** — plumbs the response through
  `AppViewModel.handleResponse` under a synthetic negative `request_id`, so it lands in the app's
  caches exactly as the WebSocket path would. Use this whenever the result should update app state.
- **`ExecApi.callObject(creds, command, body)`** — returns the response object directly, or null on
  any failure. Use this from components that have no ViewModel to plumb into (notification
  enrichment, workers, receivers). It deliberately writes to no cache: these paths run outside the
  sync pipeline and must not race it.

## HTTP-basic fallback

Server commit [`fdcb9b0c`](https://github.com/gomuks/gomuks/commit/fdcb9b0c) ("server: allow using
any endpoint with basic auth") makes `AuthMiddleware` fall back to HTTP basic auth when the session
cookie is absent or rejected, on every endpoint except `/auth`.

This matters because `/exec` callers are background components — a `BroadcastReceiver` handling a
notification action, a `Worker` — that cannot run the interactive re-auth flow. Previously an
expired token silently lost the user's reply. `ExecApi.execRaw` now retries once under
`Authorization: Basic` built from `CredentialStore.loadCredentials()`.

Two rules keep this from being a security downgrade:

1. **Cookie first, basic only on `401`.** The token is revocable and scoped; the password is
   neither. A `401` also proves the command did not run, so the second attempt cannot double-execute
   — and it reuses the same idempotency envelope, preserving that guarantee even when the first
   attempt's response was merely lost.
2. **HTTPS only.** `Credentials.isSecureTransport` gates it, so a cleartext homeserver URL can never
   put the account password on the wire. Such a deployment keeps the old behaviour (`401` → give
   up), which is the correct trade.

The credentials are resolved through `Credentials.basicAuthProvider`, a **lazy** provider rather
than a value. That is load-bearing: the lookup is a Keystore decrypt (~20 ms of disk I/O plus
crypto) and `ExecApi.readCredentials` is called on the main thread by
`RpcResilienceCoordinator.dispatchOverExec`. The provider runs only on the `401` path, which is
always off the main thread. `readCredentials` itself only *probes* for stored credentials via
`CredentialStore.hasCredentials` (a plain SharedPreferences read) so that `Credentials.isValid()`
can report "we can authenticate somehow" without paying for the decrypt.

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
