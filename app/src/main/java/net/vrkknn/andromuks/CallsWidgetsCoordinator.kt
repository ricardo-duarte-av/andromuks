package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class IncomingCallInfo(val roomId: String, val callerId: String, val callIntent: String, val expiresAt: Long)

/**
 * Element Call UI state and widget WebSocket commands — [AppViewModel].
 */
internal class CallsWidgetsCoordinator(private val vm: AppViewModel) {

    fun setCallActive(active: Boolean) = with(vm) {
        callActiveInternal = active
        // Mirror into the process-global tracker the WebSocketService consults (see CallTracker).
        if (active) CallTracker.onCallStarted(callActiveRoomId) else CallTracker.onCallEnded(callActiveRoomId)
    }

    fun isCallActive(): Boolean = vm.callActiveInternal

    fun setCallReadyForPip(ready: Boolean) = with(vm) {
        callReadyForPipInternal = ready
    }

    fun isCallReadyForPip(): Boolean = vm.callReadyForPipInternal

    fun setCallMiniPip(active: Boolean, roomId: String = "") = with(vm) {
        callMiniPipActive = active
        if (!active) callPersistentWebView = null
    }

    fun startCall(roomId: String) = with(vm) {
        callActiveRoomId = roomId
        callActiveInternal = true
        CallTracker.onCallStarted(roomId)
        callMiniPipActive = false
        callReadyForPipInternal = false
        callPersistentWebView = null
        incomingCallInfo = null
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

    fun endCall() = with(vm) {
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
