package net.vrkknn.andromuks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [decideMarkRead] — the guard that decides whether an auto `mark_read` actually goes out.
 *
 * These encode the two failures behind a room whose unread badge could not be cleared by re-opening
 * it: the old guard suppressed every repeat of an event id forever (so one bad receipt wedged the
 * room as unread for the rest of the process), and it did nothing to stop a target that was *older*
 * than one already sent.
 */
class MarkReadDecisionTest {

    private val newest = MarkReadTarget("\$newest", 4497337L)
    private val older = MarkReadTarget("\$older", 4497320L)

    @Test
    fun `first mark_read for a room is always sent`() {
        val decision = decideMarkRead(newest, lastSent = null, roomConfirmedRead = false)
        assertEquals(MarkReadDecision.Send(newest), decision)
    }

    @Test
    fun `repeat of the same event is suppressed once the room is confirmed read`() {
        val decision = decideMarkRead(newest, lastSent = newest, roomConfirmedRead = true)
        assertEquals("duplicate", (decision as MarkReadDecision.Suppress).reason)
    }

    @Test
    fun `repeat of the same event is re-sent while the room still reads as unread`() {
        // The regression: re-opening a room whose receipt never actually landed must retry.
        val decision = decideMarkRead(newest, lastSent = newest, roomConfirmedRead = false)
        assertEquals(MarkReadDecision.Send(newest), decision)
    }

    @Test
    fun `an older target never rewinds a newer one`() {
        val decision = decideMarkRead(older, lastSent = newest, roomConfirmedRead = false)
        assertEquals("rewind", (decision as MarkReadDecision.Suppress).reason)
    }

    @Test
    fun `a newer target advances past the last sent one`() {
        val decision = decideMarkRead(newest, lastSent = older, roomConfirmedRead = true)
        assertEquals(MarkReadDecision.Send(newest), decision)
    }

    @Test
    fun `an unknown rowid is sent rather than guessed at`() {
        val unknown = MarkReadTarget("\$fromNotification", MarkReadTarget.ROWID_UNKNOWN)
        assertTrue(decideMarkRead(unknown, lastSent = newest, roomConfirmedRead = false) is MarkReadDecision.Send)
        assertTrue(decideMarkRead(newest, lastSent = unknown, roomConfirmedRead = false) is MarkReadDecision.Send)
    }

    @Test
    fun `an unknown rowid repeating a confirmed-read event is still suppressed`() {
        val unknown = MarkReadTarget("\$fromNotification", MarkReadTarget.ROWID_UNKNOWN)
        val decision = decideMarkRead(unknown, lastSent = unknown, roomConfirmedRead = true)
        assertEquals("duplicate", (decision as MarkReadDecision.Suppress).reason)
    }
}
