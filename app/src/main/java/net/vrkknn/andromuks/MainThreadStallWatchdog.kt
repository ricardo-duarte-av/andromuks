package net.vrkknn.andromuks

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Detects a stalled main thread while the app is in the foreground and records it to Androlog.
 *
 * Exists to split a "rendered-but-frozen" UI report into its two possible causes (GH #40): either
 * the main thread is blocked — this fires, with a stack of where it is stuck — or the main thread
 * is fine and input is being swallowed by something on screen, in which case this stays silent.
 * An ANR alone cannot make that call: it only fires after an input event waits ~5 s, some ROMs
 * suppress the dialog, and users rarely wait for it.
 *
 * A background thread posts a no-op to the main Looper every [PROBE_INTERVAL_MS] and checks it ran.
 * The report is written from the watchdog thread *during* the stall, so it survives even when the
 * user gives up and swipes the app away before the main thread recovers.
 *
 * Cost is one tiny Runnable per second, and only between [start] (onResume) and [stop] (onPause).
 */
object MainThreadStallWatchdog {

    private const val CATEGORY = "Stall"
    private const val PROBE_INTERVAL_MS = 1_000L
    private const val STALL_THRESHOLD_MS = 2_000L
    private const val STACK_FRAMES = 15

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var executor: ScheduledExecutorService? = null
    private var tickFuture: ScheduledFuture<*>? = null

    // Guarded by [lock]. 0 = no probe outstanding.
    private var probePostedAt = 0L
    private var probeReported = false

    fun start() {
        synchronized(lock) {
            if (tickFuture != null) return
            val exec = executor ?: java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "MainStallWatchdog").apply { isDaemon = true }
            }.also { executor = it }
            probePostedAt = 0L
            probeReported = false
            tickFuture = exec.scheduleWithFixedDelay(::tick, PROBE_INTERVAL_MS, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        synchronized(lock) {
            tickFuture?.cancel(false)
            tickFuture = null
            probePostedAt = 0L
            probeReported = false
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val waited: Long
        synchronized(lock) {
            if (tickFuture == null) return
            val postedAt = probePostedAt
            if (postedAt == 0L) {
                probePostedAt = now
                mainHandler.post { onProbeRan(now) }
                return
            }
            waited = now - postedAt
            if (waited < STALL_THRESHOLD_MS || probeReported) return
            // A debugger breakpoint parks the main thread too; that is not a stall.
            if (Debug.isDebuggerConnected()) return
            probeReported = true
        }
        Androlog(CATEGORY, "main thread UNRESPONSIVE for ${waited}ms — stuck at:\n${mainStack()}")
    }

    private fun onProbeRan(postedAt: Long) {
        val waited = SystemClock.uptimeMillis() - postedAt
        val reported: Boolean
        synchronized(lock) {
            if (probePostedAt != postedAt) return // stop()/start() raced us; this probe is stale
            reported = probeReported
            probePostedAt = 0L
            probeReported = false
        }
        if (reported) Androlog(CATEGORY, "main thread recovered after ${waited}ms")
    }

    private fun mainStack(): String = Looper.getMainLooper().thread.stackTrace
        .take(STACK_FRAMES)
        .joinToString("\n") { "  at $it" }
}
