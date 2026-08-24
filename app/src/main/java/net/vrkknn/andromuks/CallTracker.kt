package net.vrkknn.andromuks

import android.util.Log

/**
 * Singleton tracker for active Element Calls, mirroring [BubbleTracker].
 *
 * Call state itself lives on [AppViewModel] (`callActiveInternal` / `callActiveRoomId`), which is
 * per-ViewModel and therefore invisible to [WebSocketService] — the service has no ViewModel of its
 * own and may be running with none attached at all. The battery-saver teardown paths need a
 * process-global answer to "is the user on a call right now?", because tearing the socket down
 * under an active call kills the widget's signalling channel. That is what this object provides.
 *
 * Kept deliberately dumb: [CallsWidgetsCoordinator] owns the lifecycle and mirrors every
 * start/end into here, exactly as `ChatBubbleActivity` mirrors into [BubbleTracker].
 */
object CallTracker {
    private const val TAG = "CallTracker"

    /** Room ids with a call currently joined on this device. */
    private val activeCalls = mutableSetOf<String>()

    /** Stand-in key for a call whose room id is not known at the call site. */
    private const val UNKNOWN_ROOM = "<unknown>"

    fun onCallStarted(roomId: String) {
        synchronized(activeCalls) {
            activeCalls.add(roomId.ifBlank { UNKNOWN_ROOM })
            if (BuildConfig.DEBUG) Log.d(TAG, "Call started in $roomId (total active: ${activeCalls.size})")
        }
    }

    fun onCallEnded(roomId: String) {
        synchronized(activeCalls) {
            activeCalls.remove(roomId.ifBlank { UNKNOWN_ROOM })
            // A call can end without the room id the start used (endCall clears callActiveRoomId
            // first in some paths), so a blank id ends whatever is left rather than leaking a
            // permanent "call active" that would pin the WebSocket up forever.
            if (roomId.isBlank()) activeCalls.clear()
            if (BuildConfig.DEBUG) Log.d(TAG, "Call ended in $roomId (total active: ${activeCalls.size})")
        }
    }

    /** Whether any call is joined on this device — the battery-saver teardown gate. */
    fun anyCallActive(): Boolean {
        synchronized(activeCalls) {
            return activeCalls.isNotEmpty()
        }
    }

    fun clear() {
        synchronized(activeCalls) {
            activeCalls.clear()
        }
    }
}
