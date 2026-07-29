package net.vrkknn.andromuks.utils

import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * A spend limit for the `/exec` calls made while enriching one notification.
 *
 * Enrichment is optional work on a latency-critical path: the user is waiting for the notification,
 * not for it to be perfect. Left ungoverned, a single push could fan out into history backfill plus
 * a reply-parent lookup plus member-state lookups — half a dozen serial HTTP round-trips before
 * anything reaches the shade. This makes the trade explicit: enrich what fits, post regardless.
 *
 * Two independent limits, because they fail differently:
 *  - **[maxCalls]** bounds load on the homeserver when the network is fast (a burst of pushes must
 *    not turn into a request storm).
 *  - **[deadlineAtMs]** bounds the user-visible delay when the network is slow, which is the case
 *    that actually hurts — one call over a dead radio can burn the whole OkHttp read timeout.
 *
 * Thread-safe: enrichment steps may run concurrently, and both limits are checked atomically.
 * A budget is per-notification and single-use — build a fresh one per push, never share.
 */
class ExecBudget(private val maxCalls: Int, private val deadlineAtMs: Long) {
    private val spent = AtomicInteger(0)

    /**
     * Claims one call against the budget. Returns false when the caller should skip its enrichment
     * step and let the notification go out as-is; callers must treat that as a normal outcome, not
     * an error.
     */
    fun tryConsume(step: String): Boolean {
        if (System.currentTimeMillis() >= deadlineAtMs) {
            if (BuildConfig.DEBUG) Log.d(TAG, "budget: '$step' skipped — deadline passed")
            return false
        }
        val used = spent.incrementAndGet()
        if (used > maxCalls) {
            spent.decrementAndGet()
            if (BuildConfig.DEBUG) Log.d(TAG, "budget: '$step' skipped — call limit ($maxCalls) reached")
            return false
        }
        return true
    }

    /** Milliseconds left before the deadline, floored at zero. */
    fun remainingMs(): Long = (deadlineAtMs - System.currentTimeMillis()).coerceAtLeast(0L)

    companion object {
        private const val TAG = "ExecBudget"

        /**
         * Default ceiling for enriching one notification. Five covers the current worst case — a
         * missing room name, the history page, a reply parent outside it, and two unresolved
         * senders — and the common case spends one or two. Beyond this the marginal notification
         * quality is not worth the delay.
         */
        const val DEFAULT_MAX_CALLS = 5

        /**
         * Default wall-clock allowance. Deliberately shorter than `ExecApi`'s 20 s read timeout: a
         * single stalled request must not hold the notification hostage, and Android gives an FCM
         * receiver only a short window to do its work.
         */
        const val DEFAULT_DEADLINE_MS = 4_000L

        /** Builds the standard per-notification budget, starting the clock now. */
        fun forNotification(maxCalls: Int = DEFAULT_MAX_CALLS, allowanceMs: Long = DEFAULT_DEADLINE_MS): ExecBudget =
            ExecBudget(maxCalls, System.currentTimeMillis() + allowanceMs)
    }
}
