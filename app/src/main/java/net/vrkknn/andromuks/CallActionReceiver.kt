package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Declining an incoming call from the ring notification.
 *
 * A receiver rather than an Activity because declining must not bring the app to the front, and it
 * has to work when the process was started by the push itself and no ViewModel exists. Matrix has no
 * "call rejected" event for MatrixRTC — `org.matrix.msc4075.rtc.notification` is a hint, not an
 * invite — so declining is purely local: stop ringing. The caller sees us not join, and the ring
 * would have retired itself at the event's lifetime anyway.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncomingCallRinger.ACTION_DECLINE) return
        Log.i("Andromuks", "CallActionReceiver: incoming call declined")
        IncomingCallRinger.cancel(context)
    }
}
