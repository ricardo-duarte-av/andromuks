package net.vrkknn.andromuks

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallsManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Registers our calls with the system through Jetpack Telecom.
 *
 * A `CallStyle` notification only *looks* like a call to the user; the operating system has no idea
 * one is happening. Registering with Telecom is what makes a call real to everything that renders
 * calls but is not our UI: **Android Auto**, Wear, Bluetooth car kits and headset answer/hang-up
 * buttons — and it is what lets the OS arbitrate when a cellular call arrives mid-call, instead of
 * both ringing over each other.
 *
 * This is a *self-managed* registration: we keep drawing our own call UI (the Element Call WebView),
 * Telecom just knows the call exists and owns its lifecycle. Jetpack's `CallsManager` wraps the
 * `ConnectionService` plumbing that would otherwise be required.
 *
 * **Everything here is best-effort.** A device without Telecom support, a revoked permission, or a
 * refused registration must never stop a call from working — the call is the WebView's, not
 * Telecom's, so every failure path logs and carries on.
 */
internal class CallTelecomCoordinator(private val vm: AppViewModel) {

    private var callsManager: CallsManager? = null
    private var registered = false
    private var sessionJob: Job? = null

    /** Completed when our own call ends, which is what releases the Telecom session. */
    private var endSignal: CompletableDeferred<Unit>? = null

    /**
     * Tell Telecom a call has begun. [answeringIncoming] matters: an incoming call is what a car or
     * watch offers to answer, an outgoing one is simply in progress.
     */
    fun onCallStarted(roomId: String, displayName: String, isVideo: Boolean, answeringIncoming: Boolean) = with(vm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@with
        val context = appContext ?: return@with
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MANAGE_OWN_CALLS not granted; the call stays invisible to the system")
            return@with
        }
        endCallSession()

        val manager = callsManager ?: CallsManager(context).also { callsManager = it }
        if (!registered) {
            runCatching {
                manager.registerAppWithTelecom(
                    CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING,
                )
                registered = true
            }.onFailure {
                Log.w(TAG, "Could not register with Telecom", it)
                return@with
            }
        }

        // A self-managed call still needs an address. Matrix has no dialable one, so the room id
        // stands in: it is stable, unique, and never shown as a phone number anywhere.
        val attributes = CallAttributesCompat(
            displayName = displayName,
            address = Uri.fromParts("matrix", roomId, null),
            direction = if (answeringIncoming) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            },
            callType = if (isVideo) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        )

        val signal = CompletableDeferred<Unit>()
        endSignal = signal
        sessionJob = viewModelScope.launch {
            runCatching {
                manager.addCall(
                    callAttributes = attributes,
                    onAnswer = { Log.i(TAG, "Telecom answered the call") },
                    onDisconnect = {
                        // The car, the watch, a headset button, or the OS making room for a cellular
                        // call. Leave properly so the room sees us go.
                        Log.i(TAG, "Telecom disconnected the call")
                        vm.requestGracefulHangup()
                    },
                    onSetActive = { Log.i(TAG, "Telecom set the call active") },
                    onSetInactive = { Log.i(TAG, "Telecom set the call inactive") },
                ) {
                    // The block is not itself suspending — CallControlScope is a CoroutineScope, so
                    // the session's work is launched inside it and addCall stays suspended until the
                    // call is disconnected.
                    launch {
                        setActive()
                        // Hold the Telecom session open for exactly as long as our call runs.
                        signal.await()
                        disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    }
                }
            }.onFailure { Log.w(TAG, "Telecom session failed", it) }
        }
        Log.i(TAG, "Registered a ${if (isVideo) "video" else "voice"} call with Telecom for $roomId")
    }

    /** Our call ended — release the Telecom session. Safe to call when there isn't one. */
    fun onCallEnded() {
        endCallSession()
    }

    private fun endCallSession() {
        endSignal?.complete(Unit)
        endSignal = null
        sessionJob = null
    }

    private companion object {
        const val TAG = "CallTelecom"
    }
}
