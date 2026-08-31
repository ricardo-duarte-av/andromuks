package net.vrkknn.andromuks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [timelineEventOrder], the single comparator every timeline sort and bisect goes
 * through.
 *
 * The case that matters is `timelineRowid == 0`. It means "not yet persisted", which makes such an
 * event the *newest* in the room — but a raw numeric sort puts 0 below every positive rowid, i.e.
 * at the oldest end, which under `reverseLayout = true` is off the top of the screen. That was
 * GH #32: a just-sent message vanished after the device slept, because `send_complete` delivers the
 * confirmed event at rowid 0 and backgrounding stops the `sync_complete` that would upgrade it.
 *
 * Negative rowids are a *different* regime — real Chronicled backfill positions, oldest-first — so
 * the substitution must apply to 0 alone, never to `<= 0`.
 */
class TimelineEventOrderTest {

    private fun event(eventId: String, timelineRowid: Long, timestamp: Long = 1_000L) = TimelineEvent(
        rowid = 0L,
        timelineRowid = timelineRowid,
        roomId = "!room:example.org",
        eventId = eventId,
        sender = "@alice:example.org",
        type = "m.room.message",
        timestamp = timestamp,
        content = null,
    )

    private fun sortedIds(vararg events: TimelineEvent) = events.toList()
        .sortedWith(timelineEventOrder)
        .map { it.eventId }

    @Test
    fun `confirmed event still at rowid 0 sorts newest, not oldest`() {
        // The GH #32 regression. send_complete hands us the real $-prefixed event with
        // timeline_rowid 0; until sync_complete upgrades it, it must still render at the bottom.
        val older = event("\$older", 207_133L)
        val justSent = event("\$justSent", 0L)

        assertEquals(listOf("\$older", "\$justSent"), sortedIds(justSent, older))
    }

    @Test
    fun `negative rowids are backfill and stay oldest-first`() {
        // Chronicled backfill: lower is older. A blanket `rowid <= 0 -> MAX_VALUE` substitution
        // would wrongly fling these to the newest end.
        val backfillOld = event("\$backfillOld", -20L)
        val backfillNew = event("\$backfillNew", -5L)
        val live = event("\$live", 100L)

        assertEquals(
            listOf("\$backfillOld", "\$backfillNew", "\$live"),
            sortedIds(live, backfillNew, backfillOld),
        )
    }

    @Test
    fun `positive rowids keep ascending server order`() {
        val a = event("\$a", 1L)
        val b = event("\$b", 2L)
        val c = event("\$c", 3L)

        assertEquals(listOf("\$a", "\$b", "\$c"), sortedIds(c, a, b))
    }

    @Test
    fun `pending echo sorts after a confirmed rowid-0 event at the same timestamp`() {
        // Both collapse to the newest end; the ~ tiebreak keeps the un-acked echo visually last.
        val confirmed = event("\$confirmed", 0L, timestamp = 5_000L)
        val echo = event("~local-abc", 0L, timestamp = 5_000L)

        assertEquals(listOf("\$confirmed", "~local-abc"), sortedIds(echo, confirmed))
    }

    @Test
    fun `rowid-0 events fall back to timestamp order among themselves`() {
        val first = event("\$first", 0L, timestamp = 1_000L)
        val second = event("\$second", 0L, timestamp = 2_000L)

        assertEquals(listOf("\$first", "\$second"), sortedIds(second, first))
    }

    @Test
    fun `equal keys tiebreak deterministically on event id`() {
        val a = event("\$aaa", 7L, timestamp = 1_000L)
        val b = event("\$bbb", 7L, timestamp = 1_000L)

        assertEquals(listOf("\$aaa", "\$bbb"), sortedIds(b, a))
        assertEquals(listOf("\$aaa", "\$bbb"), sortedIds(a, b))
    }
}
