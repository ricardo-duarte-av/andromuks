package net.vrkknn.andromuks

/**
 * Typed causes for WebSocket reconnection / restart.
 * Routing and guards must use these types — not substrings of log messages.
 */
sealed class ReconnectTrigger {
    object NetworkLost : ReconnectTrigger()

    /** Link is back; used where we must not tear down local resume state (e.g. skip aggressive clear). */
    object NetworkAvailable : ReconnectTrigger()

    object PingTimeout : ReconnectTrigger()

    /** Push wake / high-level external wake (no ViewModel — service must not recurse into the same path). */
    object FcmPush : ReconnectTrigger()

    /** Failsafe / background recovery that is allowed to use the normal reconnect path. */
    object FailsafeReconnection : ReconnectTrigger()

    object ServiceRestarted : ReconnectTrigger()

    object UserRequested : ReconnectTrigger()

    data class NetworkTypeChanged(
        val from: WebSocketService.NetworkType,
        val to: WebSocketService.NetworkType
    ) : ReconnectTrigger()

    // --- Additional structured causes (avoid [Unclassified] where possible) ---

    data class WebSocketClosed(val code: Int, val remoteReason: String) : ReconnectTrigger()

    object ValidationTimeoutRetry : ReconnectTrigger()

    data class HealthCheckRecovery(val detail: String) : ReconnectTrigger()

    data class NetworkValidated(val networkType: WebSocketService.NetworkType) : ReconnectTrigger()

    object StuckConnectingRecovery : ReconnectTrigger()

    object RunIdTimeout : ReconnectTrigger()

    object HardConnectingTimeout : ReconnectTrigger()

    /** No application traffic for 60s (message stale / ping loop). */
    object MessageTimeout : ReconnectTrigger()

    data class DnsFailure(val attempt: Int) : ReconnectTrigger()

    object NetworkUnreachableFallback : ReconnectTrigger()

    data class TlsFailure(val attempt: Int) : ReconnectTrigger()

    object WorkManagerHealthCheck : ReconnectTrigger()

    /** e.g. [triggerBackendHealthCheck] when socket not ready — not an FCM push. */
    object ExternalTriggerNotConnected : ReconnectTrigger()

    /**
     * Last resort for legacy call sites; prefer adding a [ReconnectTrigger] variant.
     */
    data class Unclassified(val detail: String) : ReconnectTrigger()
}

/** Stable message for logs, toasts, and [WebSocketService] trace injection. */
fun ReconnectTrigger.toLogString(): String = when (this) {
    is ReconnectTrigger.NetworkLost -> "Network lost"
    is ReconnectTrigger.NetworkAvailable -> "Network restored"
    is ReconnectTrigger.PingTimeout -> "Ping timeout"
    is ReconnectTrigger.FcmPush -> "FCM push wake"
    is ReconnectTrigger.FailsafeReconnection -> "Failsafe reconnection"
    is ReconnectTrigger.ServiceRestarted -> "Service restarted"
    is ReconnectTrigger.UserRequested -> "User requested"
    is ReconnectTrigger.NetworkTypeChanged -> "Network type changed: $from → $to"
    is ReconnectTrigger.WebSocketClosed -> "WebSocket closed (code=$code): $remoteReason"
    is ReconnectTrigger.ValidationTimeoutRetry -> "Validation timeout retry"
    is ReconnectTrigger.HealthCheckRecovery -> "Health check recovery: $detail"
    is ReconnectTrigger.NetworkValidated -> "Network validated: $networkType"
    is ReconnectTrigger.StuckConnectingRecovery -> "Network change: recovering from stuck CONNECTING state"
    is ReconnectTrigger.RunIdTimeout -> "Run ID timeout — reconnecting"
    is ReconnectTrigger.HardConnectingTimeout -> "Hard timeout recovery from stuck Connecting state"
    is ReconnectTrigger.MessageTimeout -> "60 second message timeout"
    is ReconnectTrigger.DnsFailure -> "DNS resolution failure (attempt $attempt)"
    is ReconnectTrigger.NetworkUnreachableFallback -> "Network unreachable (fallback retry)"
    is ReconnectTrigger.TlsFailure -> "TLS error (attempt $attempt)"
    is ReconnectTrigger.WorkManagerHealthCheck -> "WorkManager health check — connection lost"
    is ReconnectTrigger.ExternalTriggerNotConnected -> "External trigger — WebSocket not connected"
    is ReconnectTrigger.Unclassified -> detail
}

/**
 * When a reconnection is already in progress (10–30s window), these may preempt a slow attempt.
 */
fun ReconnectTrigger.interruptsSlowReconnection(): Boolean = when (this) {
    is ReconnectTrigger.NetworkTypeChanged,
    is ReconnectTrigger.NetworkLost,
    is ReconnectTrigger.NetworkAvailable,
    is ReconnectTrigger.NetworkValidated,
    is ReconnectTrigger.StuckConnectingRecovery -> true
    else -> false
}
