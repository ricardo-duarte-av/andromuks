package net.vrkknn.andromuks

import android.os.Handler
import android.os.Looper

/**
 * Invoked from non-composable callbacks (e.g. Coil onSuccess) after media layout changes
 * so the timeline can re-scroll to bottom when attached. Room/Bubble screens register
 * the callback; MediaFunctions calls [notifyAfterLayoutSettled] on image load.
 */
object TimelineMediaLayoutCallback {
    @Volatile
    var callback: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Post after layout so LazyColumn can remeasure with new aspect ratio. */
    fun notifyAfterLayoutSettled() {
        val cb = callback ?: return
        mainHandler.postDelayed({ cb.invoke() }, 48)
    }
}
