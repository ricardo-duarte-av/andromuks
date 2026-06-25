package net.vrkknn.andromuks

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.ExecApi
import org.json.JSONObject

/**
 * Runs gomuks RPC commands over the HTTP `/_gomuks/exec/{command}` endpoint and feeds their
 * responses back through the *same* dispatcher the WebSocket uses
 * ([AppViewModel.handleResponse] / [AppViewModel.handleError]).
 *
 * This is used while the WebSocket is down (battery-saver mode) for commands whose result must
 * still update app state — e.g. hydrating the timeline cache via `paginate` from an FCM wake-up.
 *
 * ## Why this works without re-implementing anything
 *
 * The WebSocket `response` dispatcher routes purely by *which request-tracking map holds the
 * request_id* (see [AppViewModel.handleResponse]); it contains no transport logic. And the `/exec`
 * HTTP response body is byte-identical to the `data` field of a WebSocket `response` frame. So we:
 *
 *  1. Allocate a synthetic `request_id` (a local routing token — it never goes on the wire).
 *  2. Register it in the same map(s) the WebSocket path uses for this command, via [register].
 *  3. POST the command over HTTP and route the parsed body through `handleResponse` (success) or
 *     `handleError` (command/HTTP/network error). `handleError` removes the id from its map, so a
 *     failed call cleans up after itself exactly like a WS error frame would.
 *
 * Adding a new command is just: build its `data` JSONObject and supply a [register] lambda that
 * mirrors the bookkeeping its WebSocket counterpart does.
 */
internal class ExecCommandCoordinator(private val vm: AppViewModel) {
    private val tag = "ExecCommandCoordinator"

    /**
     * Generic bridge: execute [command] with [data] over `/exec` and plumb the response into the
     * WebSocket dispatcher under a synthetic request_id.
     *
     * @param register Registers the synthetic `requestId` in the same request-tracking map(s) the
     *   WebSocket path uses for [command], so [AppViewModel.handleResponse] can route the payload
     *   to the correct handler. Called synchronously, before the network request is issued.
     */
    fun execute(
        command: String,
        data: JSONObject,
        register: (requestId: Int) -> Unit,
    ) {
        val context = vm.appContext
        if (context == null) {
            Log.w(tag, "execute($command): no app context")
            return
        }
        val creds = ExecApi.readCredentials(context)
        if (!creds.isValid()) {
            Log.w(tag, "execute($command): missing credentials")
            return
        }

        // Synthetic request_id: a local routing token only. The /exec endpoint ignores request_id;
        // the WS dispatcher uses it to find the registered handler.
        val requestId = WebSocketService.allocateRequestId()
        register(requestId)

        vm.viewModelScope.launch(Dispatchers.Default) {
            when (val result = ExecApi.execRaw(creds, command, data)) {
                is ExecApi.ExecResult.Success ->
                    vm.handleResponse(requestId, result.data)
                is ExecApi.ExecResult.CommandError ->
                    vm.handleError(requestId, result.message)
                is ExecApi.ExecResult.AuthMissing ->
                    vm.handleError(requestId, "exec $command: auth cookie missing")
                is ExecApi.ExecResult.HttpError ->
                    vm.handleError(requestId, "exec $command: HTTP ${result.code} ${result.message}")
                is ExecApi.ExecResult.NetworkError ->
                    vm.handleError(requestId, "exec $command: ${result.message}")
            }
        }
    }

    /**
     * Paginate [roomId] over `/exec` and route the result through the normal timeline-response
     * path, hydrating [net.vrkknn.andromuks.RoomTimelineCache]. Mirrors the WebSocket paginate in
     * [AppViewModel.requestPaginationWithSmallestRowId]: same `data` shape and same bookkeeping
     * ([AppViewModel.paginateRequests] + [AppViewModel.paginateRequestMaxTimelineIds], the latter
     * used for progress detection in `handleTimelineResponse`).
     *
     * @param maxTimelineId Cursor; events with `rowid < maxTimelineId` are returned. `0` means
     *   "the most recent events".
     */
    fun paginate(
        roomId: String,
        maxTimelineId: Long,
        limit: Int = AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT,
        expectedEventId: String? = null,
        freshnessProbeAnchor: String? = null,
    ) {
        val data = JSONObject().apply {
            put("room_id", roomId)
            put("max_timeline_id", maxTimelineId)
            put("limit", limit)
            put("reset", false)
        }
        execute("paginate", data) { requestId ->
            vm.paginateRequests[requestId] = roomId
            vm.paginateRequestMaxTimelineIds[requestId] = maxTimelineId
            // A freshness probe routes its response into handlePaginationMerge's freshness-probe fast
            // path, which decides contiguous-vs-gap against this anchor BEFORE any merge/render.
            // Mutually exclusive with the FCM-hydrate expectedEventId path below.
            if (freshnessProbeAnchor != null) {
                vm.freshnessProbeAnchors[requestId] = freshnessProbeAnchor
            } else if (expectedEventId != null) {
                // When set, handlePaginationMerge verifies this event_id landed in the response and
                // escalates to a full paginate if it didn't (small FCM-hydration window overflow).
                vm.hydrateExpectedEventIds[requestId] = expectedEventId
            }
        }
    }
}
