package net.vrkknn.andromuks

/**
 * Single source of truth for WebSocket / sync lifecycle.
 * Illegal combinations (e.g. "reconnecting" + "ready") are unrepresentable — do not mirror with booleans.
 *
 * Transport-only concerns (e.g. [WebSocketService] ping dedupe) stay out of this type.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()

    /** TCP/TLS/WebSocket dial in progress (includes OkHttp connect). */
    data class Connecting(val attemptNumber: Int) : ConnectionState()

    /**
     * run_id received; waiting for init_complete / sync_completes per backend contract.
     */
    data class Initializing(
        val runId: String,
        val pendingSyncCount: Int,
        val receivedSyncCount: Int
    ) : ConnectionState()

    object Ready : ConnectionState()

    /** Resume path: run_id + last_received_event in URL; backend may skip init_complete. */
    data class QuickReconnecting(
        val runId: String,
        val lastEventId: Int,
        val attemptNumber: Int
    ) : ConnectionState()

    /** Cold / full resync path (clears local resume state before dial). */
    object FullReconnecting : ConnectionState()

    /** No network; [lastEventId] tells us whether to quick- or full-reconnect when link returns. */
    data class WaitingForNetwork(val lastEventId: Int) : ConnectionState()
}

fun ConnectionState.isDisconnected(): Boolean = this is ConnectionState.Disconnected

fun ConnectionState.isConnecting(): Boolean = this is ConnectionState.Connecting

fun ConnectionState.isInitializing(): Boolean = this is ConnectionState.Initializing

fun ConnectionState.isReady(): Boolean = this is ConnectionState.Ready

/** Dial in flight or consuming initial sync (replaces old intermediate `Connected` state). */
fun ConnectionState.isDialOrSyncing(): Boolean = isConnecting() || isInitializing()

/** Backing off or waiting to dial again (includes offline wait). */
fun ConnectionState.isReconnectingPhase(): Boolean =
    this is ConnectionState.QuickReconnecting ||
        this is ConnectionState.FullReconnecting ||
        this is ConnectionState.WaitingForNetwork

/** @deprecated Prefer explicit checks; "connected" socket phase before Ready was removed from the model. */
fun ConnectionState.isConnectedLegacy(): Boolean = false

fun ConnectionState.isActive(): Boolean =
    this is ConnectionState.Connecting ||
        this is ConnectionState.Initializing ||
        this is ConnectionState.Ready ||
        isReconnectingPhase()

fun ConnectionState.lastEventIdOrZero(): Int = when (this) {
    is ConnectionState.QuickReconnecting -> lastEventId
    is ConnectionState.WaitingForNetwork -> lastEventId
    else -> 0
}

/** Same meaning as previous [WebSocketState.getLastReceivedRequestId] helper — ID for resume URL. */
fun ConnectionState.getLastReceivedRequestId(): Int = lastEventIdOrZero()
