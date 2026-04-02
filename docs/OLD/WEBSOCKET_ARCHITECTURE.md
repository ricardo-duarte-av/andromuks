# WebSocket Architecture

This document describes how the Andromuks WebSocket connection, reconnection, and connection lifetime work.

## Connection Health

**The connection is marked good when the WebSocket connects.** We do not use `run_id` or `init_complete` as indicators of WebSocket health. As soon as the WebSocket handshake completes (`onOpen`), the connection is considered healthy and ready.

## Ping and Message Timeout

- **Ping interval**: Every 15 seconds, with the first ping 15 seconds after WebSocket connect
- **Message timeout**: The connection is marked bad/stale after **60 seconds without receiving ANY message** (pong, run_id, sync_complete, or any other message from the server)
- Any message received from the server resets the 60-second timer
- If no message is received for 60 seconds, the connection is closed and reconnection is triggered

## Connection Parameters

### Initial Connection
- **Do NOT pass `run_id`** on the first connection
- The backend will send `run_id` in the first message; we store it for future reconnections

### Reconnection
- **Pass `run_id` and `last_received_event`** as URL query parameters
- `run_id`: From SharedPreferences (received from backend in previous session)
- `last_received_event`: The `request_id` from the last sync_complete we processed (enables incremental sync on reconnect)

Example reconnection URL:
```
wss://homeserver.example.com/_gomuks/websocket?run_id=abc123&last_received_event=-42&compress=1
```

## Network Changes

When Android reports a network change (WiFi ↔ Mobile, network lost, network available):

1. **Close the WebSocket** immediately
2. **Wait for Android to mark the connection stable** (NET_CAPABILITY_VALIDATED, 2 second timeout)
3. **Try WebSocket connection** even if validation times out (might be slow network)
4. Treat as a fresh connection flow: connection is good when WebSocket connects

The NetworkMonitor observes:
- `onNetworkAvailable`: Network became available (offline → online)
- `onNetworkLost`: Network lost (online → offline)
- `onNetworkTypeChanged`: Network type changed (e.g., WiFi → Mobile)
- `onNetworkIdentityChanged`: Same type but different identity (e.g., different WiFi AP)

On any meaningful network change, the WebSocket is closed and reconnection is scheduled. 

**Backend health check**: Before connecting, a simple HTTP GET is performed to check if the backend is reachable. However, **this does not block the connection attempt** - if the health check fails (common on cellular networks), the WebSocket connection is still attempted. The WebSocket connection will fail fast if the backend is truly unreachable, and retry with backoff.

## WebSocket Close Codes

We still handle standard WebSocket close codes:

- **1000** (Normal Closure): Connection closed normally
- **1001** (Going Away): Server/client is shutting down
- **1002** (Protocol Error): Protocol violation
- **1003** (Unsupported Data): Received unsupported data type
- **1006** (Abnormal Closure): No close frame received
- **1008** (Policy Violation): Policy violation
- **1011** (Internal Error): Server error

The `handleWebSocketClosing` method processes these codes and triggers reconnection when appropriate.

## State Machine (Simplified)

- **Disconnected**: No connection
- **Reconnecting**: Waiting to reconnect (network lost, timeout, etc.)
- **Ready**: WebSocket connected and healthy

We no longer use intermediate states (Connecting, Connected, Initializing) for connection health. The connection goes directly to Ready when the WebSocket connects.

## Key Components

| Component | Responsibility |
|-----------|----------------|
| **WebSocketService** | Manages connection state, ping loop, message timeout, reconnection scheduling |
| **NetworkUtils** | Builds WebSocket URL, connects via OkHttp, routes messages to ViewModels |
| **NetworkMonitor** | Observes network changes, triggers reconnection when network becomes available |
| **WebSocketHealthCheckWorker** | Periodic (15 min) backup check; restarts service if killed, triggers reconnection if disconnected |

## Message Flow

1. **Any message received** → `WebSocketService.onMessageReceived()` resets 60s timer
2. **Ping sent** → Every 15s (first at 15s after connect)
3. **Pong received** → Resets 60s timer, updates lag metric
4. **60s without message** → Close WebSocket, schedule reconnection
5. **Network change** → Close WebSocket, schedule reconnection when network stable

## AppViewModel Integration

The AppViewModel still uses `init_complete` for application-level logic:
- Queuing sync_complete messages until init_complete
- Knowing when the backend is ready to accept commands
- Clearing caches and processing initial sync

This is separate from WebSocket health. The WebSocket is considered healthy as soon as it connects; the AppViewModel's readiness for commands depends on init_complete (or first sync_complete on reconnection with last_received_event).
