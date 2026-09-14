package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.SLOW_LINK_THRESHOLD_MS
import net.vrkknn.andromuks.utils.isLinkSlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "slow link" predicate behind the header indicator.
 *
 * The header used to be binary — connected or not — so a socket trickling data on weak Wi-Fi showed
 * the same icon as a healthy one while the user looked at stale messages. These pin the three signals
 * and, just as importantly, that a healthy link with a fast pong and quiet traffic is *not* slow.
 */
class LinkQualityTest {

    private val now = 1_000_000L
    private val over = SLOW_LINK_THRESHOLD_MS + 1

    @Test
    fun `healthy link is not slow`() {
        assertFalse(
            isLinkSlow(now, lastPingAt = now - 2_000, lastPongAt = now - 1_900, lastLagMs = 100, lastFrameAt = now - 1_900, lastBytesAt = now - 1_900),
        )
    }

    @Test
    fun `idle link with no traffic since the last frame is not slow`() {
        // Nothing to send is not the same as slow: no bytes after the last frame, pong answered.
        assertFalse(
            isLinkSlow(now, lastPingAt = now - 14_000, lastPongAt = now - 13_900, lastLagMs = 100, lastFrameAt = now - 60_000, lastBytesAt = now - 60_000),
        )
    }

    @Test
    fun `overdue pong is slow`() {
        assertTrue(isLinkSlow(now, lastPingAt = now - over, lastPongAt = now - 20_000, lastLagMs = 100, lastFrameAt = 0, lastBytesAt = 0))
    }

    @Test
    fun `a ping that has only just been sent is not slow yet`() {
        assertFalse(isLinkSlow(now, lastPingAt = now - 1_000, lastPongAt = now - 20_000, lastLagMs = 100, lastFrameAt = now - 1_000, lastBytesAt = 0))
    }

    @Test
    fun `slow last round trip is slow`() {
        assertTrue(isLinkSlow(now, lastPingAt = now - 9_000, lastPongAt = now - 1_000, lastLagMs = over, lastFrameAt = now - 1_000, lastBytesAt = now - 1_000))
    }

    @Test
    fun `a frame crawling in is slow`() {
        assertTrue(isLinkSlow(now, lastPingAt = 0, lastPongAt = 0, lastLagMs = null, lastFrameAt = now - over, lastBytesAt = now - 200))
    }

    @Test
    fun `bytes that already completed a recent frame are not trickling`() {
        assertFalse(isLinkSlow(now, lastPingAt = 0, lastPongAt = 0, lastLagMs = null, lastFrameAt = now - 3_000, lastBytesAt = now - 200))
    }
}
