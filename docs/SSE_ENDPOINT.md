# The `/sse` endpoint (Server-Sent Events)

> **Status: not used by Andromuks.** We connect over the WebSocket (`/websocket`). This doc records
> what the endpoint does and a future off-socket use we identified, because it is **not yet in the
> gomuks docs**. Source: gomuks commit
> [`22ad9bd`](https://github.com/gomuks/gomuks/commit/22ad9bd2aea78865ee8ac31596cb18e312709a2a)
> *"server: add support for SSE"* (`pkg/gomuks/sse.go`, `sseutil.go`).

## What it is

`SSE` = **Server-Sent Events** (the W3C `EventSource` protocol). It's an *alternative transport* for
the same event stream the WebSocket carries — not a new command. Two routes were added:

- **`GET /sse`** — opens a `text/event-stream`. Emits the same init handshake as `/websocket`
  (run ID, client state, sync status, image auth token, then resume-data *or* initial sync, then
  `init_complete`), then streams live events. Refreshes the image auth token every 30 min and sends
  a keepalive ping (`:\n\n`) every ~15 s. **It never closes on its own** — it stays open until the
  client disconnects or the server cancels it.
- **`POST /sse/ping`** — the ack/keepalive companion (see "Why the split" below).

Wire framing (`sseutil.go`): events are `data:<json>\n`; events that carry a request id are prefixed
`id:<runID>_<requestID>\n`; pings are the SSE comment line `:\n\n`. Headers:
`Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive`.

## Why the split into two routes

A WebSocket is **full-duplex**; SSE is **server → client only**. So the client → server direction the
WebSocket normally carries is fanned out to separate HTTP calls:

| Direction | WebSocket | SSE equivalent |
|---|---|---|
| Server → client events | `/websocket` | `GET /sse` |
| Client → server commands (RPC) | `/websocket` | `POST /_gomuks/exec/{command}` (see [EXEC_ENDPOINT.md](EXEC_ENDPOINT.md)) |
| Client → server acks | `/websocket` | `POST /sse/ping` |
| Reconnect / resume | client-implemented | browser `EventSource` auto |

`POST /sse/ping` takes `run_id`, `listener_id`, `last_received_event` (query params) and calls
`EventBuffer.SetLastAckedID(...)` so the server can drop delivered events and know where to resume.
The `listener_id` is handed to the client in the init handshake via the new `RunData.listener_id`
field (`jsoncmd/events.go`; unset for non-SSE connections).

## Resume semantics

`GET /sse` reads the `Last-Event-ID` request header, formatted `<runID>_<requestID>`:

- If `runID` matches the server's current run **and** the event is still in the `EventBuffer`, the
  server replays only the buffered *delta* since that point (cheap reconnect).
- If `runID` differs (server restarted) or the buffer entry has been reclaimed, `resumeFrom` resets
  to `0` → **full initial sync**.

Resume is designed for the browser `EventSource` dropping and reconnecting seconds later (and keeping
its listener alive via `/sse/ping` acks). It is **not** a polling-delta mechanism: an abandoned
listener's buffer is reclaimed, and gaps that span a server restart force a full resync.

## The "automatic reconnection" angle

The whole point for the **web frontend**: the browser `EventSource` API auto-reconnects on drop and
auto-resends `Last-Event-ID`, so the web client gets reconnection + resumption for free. There is no
Android equivalent — OkHttp's SSE module does not auto-reconnect — so this benefit does not transfer
to us. Our `WebSocketService` already owns reconnection (15 s ping / 60 s timeout, exponential
backoff, network-change aware), which is why the WebSocket remains the right transport for the app.

## Potential future use for Andromuks: off-socket snapshot refresh

A **fresh** `GET /sse` (no valid `Last-Event-ID`) runs `GetInitialSync(ctx, 100)` and streams the
`sync_complete` payloads before `init_complete`. That snapshot **is** the full room list + all
account data (global and per-room) — identical to what the WebSocket delivers on connect. There is
no discrete `/exec` command that returns the whole room list; that snapshot only comes from the sync
stream, so `/sse` is the pragmatic way to fetch it off-socket.

**Idea:** a `WorkManager`-driven periodic pull — open `GET /sse`, parse the stream, accumulate
`sync_complete` until `init_complete`, then **cancel the request** — to refresh the local room/
account-data cache/DB *without* raising the WebSocket or the foreground service. Fits the
battery-saver window (socket deliberately closed) and background freshness top-ups. A plain
cancellable HTTP GET drops into `WorkManager` far more naturally than a WebSocket does.

**Caveats — size the timer accordingly:**

- Each cold pull is a **full initial sync**: same server work and payload as a WebSocket connect.
  Cheap in *client lifecycle/complexity*, not in *bytes*. Think tens-of-minutes-to-hours freshness
  top-ups, not near-real-time polling (use the WebSocket for that).
- The resume/delta path won't help a coarse timer (see "Resume semantics") — you'll almost always
  transfer the full snapshot.
- It's a second ingestion transport to own: needs an SSE line parser (or `okhttp-sse`) plus the
  discipline to abort after `init_complete`.

**Integration seam if implemented:** feed the parsed `sync_complete` payloads into the *exact* same
cache/DB handlers the WebSocket sync path already calls, and just swap the transport underneath — so
the two paths can't diverge.

## Upstream references

- Commit: <https://github.com/gomuks/gomuks/commit/22ad9bd2aea78865ee8ac31596cb18e312709a2a>
- RPC API spec (WebSocket; SSE not yet documented upstream): <https://spec.mau.fi/gomuks/rpc.html>
- Related off-socket HTTP transport: [EXEC_ENDPOINT.md](EXEC_ENDPOINT.md)
