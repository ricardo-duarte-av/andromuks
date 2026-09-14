package net.vrkknn.andromuks.utils

/** How long the link may lag before the header says so. */
const val SLOW_LINK_THRESHOLD_MS = 5_000L

/**
 * Whether an *open* WebSocket is too slow to trust as live, so the header can say "slow" instead of
 * showing the same connected icon as a healthy link. All times use [System.currentTimeMillis]; 0
 * means "never".
 *
 * Any one of these makes the link slow:
 * - **Pong overdue** — the latest ping has waited longer than the threshold. On a dead link this
 *   shows before the missed-pong teardown, which is correct: the user is looking at stale data.
 * - **Last round-trip was slow** — the most recent pong took longer than the threshold.
 * - **Trickling** — bytes have arrived since the last complete frame, and that frame is older than
 *   the threshold: a large frame is crawling in.
 */
fun isLinkSlow(now: Long, lastPingAt: Long, lastPongAt: Long, lastLagMs: Long?, lastFrameAt: Long, lastBytesAt: Long): Boolean {
    val pongOverdue = lastPingAt > lastPongAt && now - lastPingAt > SLOW_LINK_THRESHOLD_MS
    val slowRoundTrip = lastLagMs != null && lastLagMs > SLOW_LINK_THRESHOLD_MS
    val trickling = lastBytesAt > lastFrameAt && now - lastFrameAt > SLOW_LINK_THRESHOLD_MS
    return pongOverdue || slowRoundTrip || trickling
}
