package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.ExecApi
import org.json.JSONObject

/**
 * Lifecycle owner for **replay-safe** RPC requests: retries, transport choice, de-duplication and
 * teardown handling. Feature code submits a logical request and gets exactly one answer; this class
 * decides how many times, and over which transport, that request actually goes out.
 *
 * ## Why this exists
 *
 * The backend always answers a command it received — `response` or `error` (see
 * <https://spec.mau.fi/gomuks/rpc.html>). So a request is never completed by a *clock*; it is
 * completed by an *event*. The wall-clock timeouts this replaces (a fixed 10 s in `getEvent`) were
 * guessing, and guessed wrong in the one case that matters: `get_event` "uses the database if
 * possible, but will fetch from the homeserver if the event isn't found locally", and a homeserver
 * round-trip for an old event can outlive any fixed budget. The timeout then fired, the caller was
 * told "no such event", and the real response — which arrived moments later — was dropped on the
 * floor because its request_id had already been removed from the routing map.
 *
 * The only states where an answer genuinely cannot arrive are local and observable:
 *
 * | Signal | Meaning | Action |
 * |---|---|---|
 * | `response` frame | backend answered | terminal success |
 * | `error` frame | backend answered "no" | terminal failure, negative-cached |
 * | send returned non-SUCCESS | never left the device | retry / switch transport |
 * | socket torn down in flight | answer can never arrive | park, reissue on reconnect |
 * | command gate closed | answer *will* arrive, late | take `/exec` instead of waiting |
 *
 * ## Transport
 *
 * The WebSocket is preferred whenever it is connected *and* [AppViewModel.canSendCommandsToBackend]
 * is true. Otherwise the request goes over the HTTP `/exec` endpoint, which is independent of the
 * socket: this covers both the socket being down and the cold-start window where commands would
 * otherwise sit in `pendingCommandsQueue` behind the whole initial sync. Both transports converge on
 * the same routing maps and the same [AppViewModel.handleResponse] dispatcher, so a caller cannot
 * tell which one served it — see [ExecCommandCoordinator] for the equivalence that makes this work.
 *
 * No idempotency envelope (`txn_id`) is attached: only replay-safe, read-only commands belong here,
 * and re-running one costs a round-trip and nothing else. Non-idempotent commands (`send_message`
 * and friends) must NOT use this class — they keep their existing send-once paths.
 *
 * ## Guarantees
 *
 * - **Exactly one callback** per submitted request, on the main thread.
 * - **Coalescing**: concurrent submissions sharing a [RpcSpec.dedupKey] issue one network request and
 *   fan the single answer out to every caller.
 * - **First terminal answer wins**: a late WebSocket response racing an `/exec` answer for the same
 *   logical request is ignored, not double-delivered.
 * - **Bounded**: [MAX_ATTEMPTS] dispatches per request. A connection loss parks the request and
 *   refunds the attempt, so a flapping socket cannot exhaust the budget on its own.
 * - **Negative cache**: a definitive "no" is remembered for [NEGATIVE_TTL_MS] so a re-composing UI
 *   cannot hammer the backend for an event that genuinely does not exist. Cleared on reconnect,
 *   because "not found" may have been a property of the old connection.
 */
internal class RpcResilienceCoordinator(private val vm: AppViewModel) {

    companion object {
        /** Dispatch attempts per logical request before it is failed as exhausted. */
        private const val MAX_ATTEMPTS = 3

        /** How long a definitive "backend answered no" is remembered. Cleared early on reconnect. */
        private const val NEGATIVE_TTL_MS = 5 * 60 * 1000L

        private const val TAG = "Andromuks"
    }

    /**
     * One logical request. [register] must insert [requestId] into whatever request-tracking map
     * [AppViewModel.handleResponse] uses to route this command, wiring its handler to call `deliver`
     * with the parsed payload (or `null` when the backend had no usable answer). [unregister] must
     * remove it again. Both are called on the main thread.
     */
    internal class RpcSpec(
        val command: String,
        val dedupKey: String,
        val data: Map<String, Any>,
        val register: (requestId: Int, deliver: (Any?) -> Unit) -> Unit,
        val unregister: (requestId: Int) -> Unit,
    )

    private class Pending(
        val spec: RpcSpec,
        val callbacks: MutableList<(Any?) -> Unit> = mutableListOf(),
        var attempts: Int = 0,
        var inFlightRequestId: Int? = null,
        var settled: Boolean = false,
        var parked: Boolean = false,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private val negativeCache = mutableMapOf<String, Long>()

    /**
     * Submit a replay-safe request. [callback] fires exactly once, on the main thread, with the
     * parsed payload or `null` if the request could not be answered.
     */
    fun submit(spec: RpcSpec, callback: (Any?) -> Unit) {
        val existing: Pending?
        synchronized(lock) {
            val negativeUntil = negativeCache[spec.dedupKey]
            if (negativeUntil != null) {
                if (System.currentTimeMillis() < negativeUntil) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(TAG, "RpcResilience: ${spec.dedupKey} short-circuited by negative cache")
                    }
                    deliverOnMain(callback, null)
                    return
                }
                negativeCache.remove(spec.dedupKey)
            }

            existing = pending[spec.dedupKey]
            if (existing != null) {
                existing.callbacks.add(callback)
            } else {
                val created = Pending(spec)
                created.callbacks.add(callback)
                pending[spec.dedupKey] = created
            }
        }

        if (existing != null) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(TAG, "RpcResilience: coalesced onto in-flight ${spec.dedupKey}")
            }
            return
        }
        dispatch(spec.dedupKey)
    }

    /**
     * The socket went away. Every request that was waiting on it can no longer be answered, so park
     * them for reissue and refund the attempt — a dropped connection is not the request's fault.
     * Called from [AppViewModel.onWebSocketCleared].
     */
    fun onConnectionLost() {
        val toUnregister = mutableListOf<Pair<RpcSpec, Int>>()
        synchronized(lock) {
            for (p in pending.values) {
                val requestId = p.inFlightRequestId ?: continue
                toUnregister.add(p.spec to requestId)
                p.inFlightRequestId = null
                p.parked = true
                if (p.attempts > 0) p.attempts--
            }
        }
        if (toUnregister.isEmpty()) return
        vm.viewModelScope.launch(Dispatchers.Main) {
            for ((spec, requestId) in toUnregister) {
                spec.unregister(requestId)
            }
            android.util.Log.w(
                TAG,
                "RpcResilience: connection lost with ${toUnregister.size} request(s) in flight — parked for reissue",
            )
        }
    }

    /**
     * The backend is reachable again (socket up and the command gate open). Clear the negative cache
     * — a "not found" may have been a property of the previous connection — reissue parked requests,
     * and nudge any UI keyed on [AppViewModel.rpcRetryGeneration].
     */
    fun onConnectionReady() {
        val parkedKeys: List<String>
        synchronized(lock) {
            negativeCache.clear()
            parkedKeys = pending.filterValues { it.parked && it.inFlightRequestId == null }.keys.toList()
            parkedKeys.forEach { pending[it]?.parked = false }
        }
        vm.viewModelScope.launch(Dispatchers.Main) { vm.rpcRetryGeneration++ }
        if (parkedKeys.isEmpty()) return
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "RpcResilience: reissuing ${parkedKeys.size} parked request(s)")
        }
        parkedKeys.forEach { dispatch(it) }
    }

    private fun dispatch(dedupKey: String) {
        vm.viewModelScope.launch(Dispatchers.Main) {
            val p = synchronized(lock) { pending[dedupKey] } ?: return@launch
            if (p.settled) return@launch

            val attempts = synchronized(lock) { p.attempts }
            if (attempts >= MAX_ATTEMPTS) {
                android.util.Log.w(
                    TAG,
                    "RpcResilience: ${p.spec.command} exhausted after $attempts attempt(s): $dedupKey",
                )
                Androlog("RPC", "${p.spec.command} exhausted after $attempts attempts ($dedupKey)")
                settle(dedupKey, null, negativeCacheIt = false)
                return@launch
            }
            synchronized(lock) { p.attempts++ }

            val webSocketUsable = vm.isWebSocketConnected() && vm.canSendCommandsToBackend
            if (webSocketUsable) {
                dispatchOverWebSocket(dedupKey, p)
            } else {
                dispatchOverExec(dedupKey, p)
            }
        }
    }

    /** Main thread. */
    private fun dispatchOverWebSocket(dedupKey: String, p: Pending) {
        val requestId = WebSocketService.allocateRequestId()
        synchronized(lock) { p.inFlightRequestId = requestId }
        p.spec.register(requestId) { payload -> onDelivered(dedupKey, requestId, payload) }

        val result = vm.sendWebSocketCommand(p.spec.command, requestId, p.spec.data)
        if (result == WebSocketResult.SUCCESS) return

        // The send did not happen, so no frame will ever carry this request_id. Undo the
        // registration and try the other transport rather than waiting for an answer that the
        // device never asked for.
        p.spec.unregister(requestId)
        synchronized(lock) { p.inFlightRequestId = null }
        android.util.Log.w(
            TAG,
            "RpcResilience: ${p.spec.command} send failed ($result) for $dedupKey — falling back to /exec",
        )
        dispatchOverExec(dedupKey, p)
    }

    /** Main thread. */
    private fun dispatchOverExec(dedupKey: String, p: Pending) {
        val context = vm.appContext
        if (context == null) {
            park(dedupKey, p, "no app context for /exec")
            return
        }
        val creds = ExecApi.readCredentials(context)
        if (!creds.isValid()) {
            park(dedupKey, p, "no /exec credentials")
            return
        }

        // Synthetic id from the dedicated negative space, exactly as ExecCommandCoordinator does, so
        // an /exec response in flight across a reconnect cannot collide with a reissued WS id.
        val requestId = WebSocketService.allocateExecRequestId()
        synchronized(lock) { p.inFlightRequestId = requestId }
        p.spec.register(requestId) { payload -> onDelivered(dedupKey, requestId, payload) }

        val body = JSONObject(p.spec.data)
        vm.viewModelScope.launch(Dispatchers.IO) {
            when (val result = ExecApi.execRaw(creds, p.spec.command, body)) {
                // Route through the same dispatcher the socket uses; it will reach the handler
                // registered above and call onDelivered for us.
                is ExecApi.ExecResult.Success -> vm.handleResponse(requestId, result.data)

                // The command ran and the backend said no. Terminal.
                is ExecApi.ExecResult.CommandError -> withContext(Dispatchers.Main) {
                    p.spec.unregister(requestId)
                    synchronized(lock) { p.inFlightRequestId = null }
                    android.util.Log.w(TAG, "RpcResilience: /exec ${p.spec.command} error: ${result.message}")
                    settle(dedupKey, null, negativeCacheIt = true)
                }

                // Transport-level failures: the command may never have run. Park for reissue.
                else -> withContext(Dispatchers.Main) {
                    p.spec.unregister(requestId)
                    synchronized(lock) { p.inFlightRequestId = null }
                    park(dedupKey, p, "/exec ${p.spec.command} failed: $result")
                }
            }
        }
    }

    /** Main thread. Hold the request until the connection comes back rather than burning attempts. */
    private fun park(dedupKey: String, p: Pending, reason: String) {
        synchronized(lock) {
            if (p.settled) return
            p.parked = true
            if (p.attempts > 0) p.attempts--
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "RpcResilience: parked $dedupKey — $reason")
        }
    }

    /**
     * A transport delivered an answer. [requestId] identifies the attempt, so a late answer from a
     * superseded attempt (e.g. a WS response racing an /exec answer) is discarded instead of
     * overwriting the one already delivered.
     */
    private fun onDelivered(dedupKey: String, requestId: Int, payload: Any?) {
        val stale = synchronized(lock) {
            val p = pending[dedupKey]
            p == null || p.settled || p.inFlightRequestId != requestId
        }
        if (stale) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(TAG, "RpcResilience: ignoring superseded answer for $dedupKey (requestId=$requestId)")
            }
            return
        }
        // payload == null means the backend answered but had nothing usable (or an error frame was
        // routed to the handler). Either way it answered, so this is terminal and worth remembering.
        settle(dedupKey, payload, negativeCacheIt = payload == null)
    }

    private fun settle(dedupKey: String, payload: Any?, negativeCacheIt: Boolean) {
        val callbacks: List<(Any?) -> Unit>
        synchronized(lock) {
            val p = pending[dedupKey] ?: return
            if (p.settled) return
            p.settled = true
            pending.remove(dedupKey)
            callbacks = p.callbacks.toList()
            if (negativeCacheIt) {
                negativeCache[dedupKey] = System.currentTimeMillis() + NEGATIVE_TTL_MS
            }
        }
        for (callback in callbacks) {
            deliverOnMain(callback, payload)
        }
    }

    private fun deliverOnMain(callback: (Any?) -> Unit, payload: Any?) {
        vm.viewModelScope.launch(Dispatchers.Main) { callback(payload) }
    }
}
