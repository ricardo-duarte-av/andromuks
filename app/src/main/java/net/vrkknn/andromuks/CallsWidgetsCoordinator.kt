package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class IncomingCallInfo(val roomId: String, val callerId: String, val callIntent: String, val expiresAt: Long)

/**
 * Element Call UI state and widget WebSocket commands — [AppViewModel].
 */
internal class CallsWidgetsCoordinator(private val vm: AppViewModel) {

    private companion object {
        /** How long Element Call gets to clear its own membership before we end the call anyway. */
        const val HANGUP_GRACE_MS = 3000L
    }

    fun setCallActive(active: Boolean) = with(vm) {
        callActiveInternal = active
        // Mirror into the process-global tracker the WebSocketService consults (see CallTracker).
        if (active) CallTracker.onCallStarted(callActiveRoomId) else CallTracker.onCallEnded(callActiveRoomId)
    }

    fun isCallActive(): Boolean = vm.callActiveInternal

    fun setCallReadyForPip(ready: Boolean) = with(vm) {
        val wasReady = callReadyForPipInternal
        callReadyForPipInternal = ready
        // First time media is flowing: start the notification's elapsed-time counter. Element Call
        // can sit in its lobby for a while, and counting that as call time would be a lie.
        if (ready && !wasReady) {
            callConnectedAtMs = System.currentTimeMillis()
            refreshCallNotification()
        }
    }

    fun isCallReadyForPip(): Boolean = vm.callReadyForPipInternal

    fun setCallMiniPip(active: Boolean, roomId: String = "") = with(vm) {
        callMiniPipActive = active
        if (!active) callPersistentWebView = null
    }

    fun startCall(roomId: String, intent: String = "video") = with(vm) {
        // Decide before we join, because joining puts our own membership in activeCallRooms.
        // Element X only rings when it *starts* a call in a DM: joining a call already in progress
        // notifies rather than summoning everyone a second time.
        val isDirectMessage = getRoomById(roomId)?.isDirectMessage == true
        val joiningExistingCall = activeCallRooms.contains(roomId)
        callSendNotificationType = if (isDirectMessage && !joiningExistingCall) "ring" else "notification"
        callActiveRoomId = roomId
        callIntent = if (intent == "audio") "audio" else "video"
        callActiveInternal = true
        CallTracker.onCallStarted(roomId)
        callMiniPipActive = false
        callReadyForPipInternal = false
        callConnectedAtMs = 0L
        callPersistentWebView = null
        incomingCallInfo = null
        CallForegroundService.hangupHandler = { requestGracefulHangup() }
        refreshCallNotification()
    }

    /**
     * (Re)post the ongoing-call notification for the current call. Safe to call repeatedly — the
     * service only promotes itself to the foreground once and refreshes the notification after that.
     */
    private fun refreshCallNotification() = with(vm) {
        val context = appContext ?: return@with
        val room = getRoomById(callActiveRoomId)
        CallForegroundService.start(
            context = context,
            roomId = callActiveRoomId,
            roomName = room?.name?.takeIf { it.isNotBlank() } ?: callActiveRoomId,
            avatarUrl = room?.avatarUrl,
            connectedAt = callConnectedAtMs,
        )
    }

    fun handleRtcNotification(roomId: String, senderId: String, content: JSONObject, eventTimestamp: Long) = with(vm) {
        if (senderId == currentUserId) return@with
        if (callActiveInternal) return@with // Already in a call
        val senderTs = content.optLong("sender_ts", eventTimestamp)
        val lifetime = content.optLong("lifetime", 30_000L)
        val expiresAt = senderTs + lifetime
        if (System.currentTimeMillis() >= expiresAt) return@with // Already expired
        val callIntent = content.optString("m.call.intent", "video")
        incomingCallInfo = IncomingCallInfo(
            roomId = roomId,
            callerId = senderId,
            callIntent = callIntent,
            expiresAt = expiresAt,
        )
    }

    fun dismissIncomingCall() = with(vm) {
        incomingCallInfo = null
    }

    /** Show the incoming-call banner for a ring that arrived as a push rather than over sync. */
    fun showIncomingCall(info: IncomingCallInfo) = with(vm) {
        if (callActiveInternal) return@with
        if (System.currentTimeMillis() >= info.expiresAt) return@with
        incomingCallInfo = info
    }

    /**
     * Leave the call the way Element Call's own leave button does, rather than tearing the WebView
     * down underneath it.
     *
     * This matters because clearing our `org.matrix.msc3401.call.member` state is Element Call's
     * job: killing the WebView ends the call locally but leaves our membership standing in the room
     * until the server applies the delayed leave event, so nobody — including our own timeline —
     * sees us leave. Asking it to hang up produces the state event, which comes back to us through
     * the usual signals and ends the call for real.
     *
     * If there is no WebView to ask, or Element Call does not act on it, fall back to ending the
     * call locally so the user is never stuck in a call they asked to leave.
     */
    fun requestGracefulHangup() = with(vm) {
        val asked = callHangupRequester?.invoke() ?: false
        if (!asked) {
            endCall()
            return@with
        }
        val leavingRoomId = callActiveRoomId
        viewModelScope.launch {
            delay(HANGUP_GRACE_MS)
            if (callActiveInternal && callActiveRoomId == leavingRoomId) {
                android.util.Log.w(
                    "Andromuks",
                    "CallsWidgetsCoordinator: Element Call did not leave within ${HANGUP_GRACE_MS}ms, ending locally",
                )
                endCall()
            }
        }
    }

    fun endCall() = with(vm) {
        appContext?.let { CallForegroundService.stop(it) }
        CallForegroundService.hangupHandler = null
        callConnectedAtMs = 0L
        CallTracker.onCallEnded(callActiveRoomId)
        callActiveInternal = false
        callReadyForPipInternal = false
        callMiniPipActive = false
        callActiveRoomId = ""
        callPersistentWebView = null
        incomingCallInfo = null
        setWidgetToDeviceHandler(null)
    }

    fun sendWidgetCommand(command: String, data: Any?, onResult: (Result<Any?>) -> Unit) = with(vm) {
        val requestId = WebSocketService.allocateRequestId()
        val deferred = CompletableDeferred<Any?>()
        widgetCommandRequests[requestId] = deferred

        val result = sendRawWebSocketCommand(command, requestId, data)
        if (result != WebSocketResult.SUCCESS) {
            widgetCommandRequests.remove(requestId)
            onResult(Result.failure(IllegalStateException("WebSocket not connected")))
            return@with
        }

        viewModelScope.launch {
            val response = withTimeoutOrNull(30_000L) { deferred.await() }
            if (response == null) {
                widgetCommandRequests.remove(requestId)
                onResult(Result.failure(java.util.concurrent.TimeoutException("Widget command timeout")))
            } else {
                onResult(Result.success(response))
            }
        }
        Unit
    }
}
