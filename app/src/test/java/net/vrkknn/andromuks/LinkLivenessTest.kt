package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.CountingInputStream
import net.vrkknn.andromuks.utils.isLinkAlive
import net.vrkknn.andromuks.utils.latestInboundActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Byte-level liveness for the WebSocket.
 *
 * OkHttp reports a WebSocket message only once its whole frame has arrived, so on a weak link a
 * large `sync_complete` trickling in looked like silence and the watchdogs tore down a socket that
 * was actively delivering data. Counting raw reads fixes that, and these tests pin the two rules it
 * depends on: every non-empty read is traffic (end-of-stream is not), and the later of the frame and
 * byte clocks is what the watchdogs compare against.
 */
class LinkLivenessTest {

    @Test
    fun `bulk reads report their size`() {
        val reads = mutableListOf<Int>()
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(10))) { reads += it }

        assertEquals(4, stream.read(ByteArray(4), 0, 4))
        assertEquals(6, stream.read(ByteArray(8), 0, 8))

        assertEquals(listOf(4, 6), reads)
    }

    @Test
    fun `single byte reads count one each`() {
        val reads = mutableListOf<Int>()
        val stream = CountingInputStream(ByteArrayInputStream(byteArrayOf(7, 8))) { reads += it }

        assertEquals(7, stream.read())
        assertEquals(8, stream.read())

        assertEquals(listOf(1, 1), reads)
    }

    @Test
    fun `end of stream is not traffic`() {
        val reads = mutableListOf<Int>()
        val stream = CountingInputStream(ByteArrayInputStream(ByteArray(0))) { reads += it }

        assertEquals(-1, stream.read())
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))

        assertTrue(reads.isEmpty())
    }

    @Test
    fun `latest activity is whichever clock moved last`() {
        assertEquals(2_000L, latestInboundActivity(lastFrameAt = 1_000L, lastBytesAt = 2_000L))
        assertEquals(3_000L, latestInboundActivity(lastFrameAt = 3_000L, lastBytesAt = 2_000L))
        // Byte counting that never fires (e.g. TLS bypassing the stream) falls back to frames.
        assertEquals(1_000L, latestInboundActivity(lastFrameAt = 1_000L, lastBytesAt = 0L))
    }

    @Test
    fun `link is alive only if something arrived after the probe`() {
        assertTrue(isLinkAlive(lastActivityAt = 1_001L, since = 1_000L))
        assertFalse(isLinkAlive(lastActivityAt = 1_000L, since = 1_000L))
        assertFalse(isLinkAlive(lastActivityAt = 0L, since = 1_000L))
    }
}
