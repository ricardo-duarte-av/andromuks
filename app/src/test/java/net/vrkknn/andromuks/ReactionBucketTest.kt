package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.applyReactionToBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for reaction bucket maths and the atomic cache primitives.
 *
 * These exercise [applyReactionToBucket] and [MessageReactionsCache] directly rather than going
 * through [ReactionCoordinator]: org.json and android.util.Log are stubbed (and throw) on the
 * unit-test classpath, which is why the bucket transform is a pure function separate from JSON
 * parsing, and why these tests only touch the non-logging cache entry points ([MessageReactionsCache.mutate],
 * [MessageReactionsCache.merge], [MessageReactionsCache.getReactions]).
 */
class ReactionBucketTest {

    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"

    private fun reaction(sender: String, emoji: String = "👍", target: String = "\$target") = ReactionEvent(
        roomId = "!room:example.org",
        eventId = "\$reaction-$sender-$emoji",
        sender = sender,
        emoji = emoji,
        relatesToEventId = target,
        timestamp = 1_000L,
    )

    /** Drops a bucket without touching the logging entry points. */
    private fun evict(eventId: String) = MessageReactionsCache.mutate(eventId) { emptyList() }

    @Test
    fun `first reaction creates a bucket`() {
        val result = applyReactionToBucket(reaction(alice), emptyList())

        assertEquals(1, result.size)
        assertEquals(1, result[0].count)
        assertEquals(listOf(alice), result[0].users)
    }

    @Test
    fun `second sender joins the same emoji`() {
        val first = applyReactionToBucket(reaction(alice), emptyList())
        val second = applyReactionToBucket(reaction(bob), first)

        assertEquals(1, second.size)
        assertEquals(2, second[0].count)
        assertEquals(setOf(alice, bob), second[0].users.toSet())
    }

    @Test
    fun `re-applying the same sender is a no-op, never a removal`() {
        // Matrix has no "un-react" event — removal is a redaction, handled by removeReaction. A
        // second m.reaction for the same (sender, emoji, target) is always a re-delivery. This used
        // to toggle the sender back off and silently ate the badge.
        val added = applyReactionToBucket(reaction(alice), emptyList())
        val again = applyReactionToBucket(reaction(alice), added)

        assertEquals(1, again.size)
        assertEquals(1, again[0].count)
        assertEquals(listOf(alice), again[0].users)
    }

    @Test
    fun `a superseded send does not remove the reaction it duplicates`() {
        // The reported bug. Your own send arrives first as a pending copy (gomuks delivers it with
        // send_error/transaction_id set) and is later superseded by the confirmed event, which
        // carries a DIFFERENT event id — so the event-id dedup guard does not catch it. Applying
        // the second one as a toggle made your own reactions disappear.
        val pending = reaction(alice).copy(eventId = "\u0024pending")
        val confirmed = reaction(alice).copy(eventId = "\u0024confirmed")

        val afterPending = applyReactionToBucket(pending, emptyList())
        val afterConfirmed = applyReactionToBucket(confirmed, afterPending)

        assertEquals(1, afterConfirmed.size)
        assertEquals(1, afterConfirmed[0].count)
        assertEquals(listOf(alice), afterConfirmed[0].users)
    }

    @Test
    fun `distinct emoji live in separate buckets`() {
        val thumbs = applyReactionToBucket(reaction(alice, "👍"), emptyList())
        val both = applyReactionToBucket(reaction(alice, "🎉"), thumbs)

        assertEquals(2, both.size)
    }

    /** A bucket as the aggregated repair builds it: authoritative count, no named users. */
    private fun countOnly(emoji: String = "👍", count: Int) = MessageReaction(emoji = emoji, count = count, users = emptyList())

    @Test
    fun `live add on a count-only bucket increments instead of resetting to one`() {
        val result = applyReactionToBucket(reaction(alice), listOf(countOnly(count = 5)))

        assertEquals(1, result.size)
        assertEquals(6, result[0].count)
        assertEquals(listOf(alice), result[0].users)
    }

    @Test
    fun `live add on a count-only bucket of one does not stall the badge`() {
        // The reported symptom: tapping an existing badge sent fine but the count never moved,
        // because count was recomputed as userReactions.size == 1.
        val result = applyReactionToBucket(reaction(alice), listOf(countOnly(count = 1)))

        assertEquals(2, result[0].count)
    }

    @Test
    fun `historical replay fills in users without inflating an aggregated count`() {
        var bucket = listOf(countOnly(count = 2))
        bucket = applyReactionToBucket(reaction(alice), bucket, isHistorical = true)
        bucket = applyReactionToBucket(reaction(bob), bucket, isHistorical = true)

        assertEquals(2, bucket[0].count)
        assertEquals(setOf(alice, bob), bucket[0].users.toSet())
    }

    @Test
    fun `historical replay still grows a purely per-user bucket`() {
        val seeded = applyReactionToBucket(reaction(alice), emptyList(), isHistorical = true)
        val grown = applyReactionToBucket(reaction(bob), seeded, isHistorical = true)

        assertEquals(2, grown[0].count)
    }

    @Test
    fun `a re-delivery against a mixed bucket leaves the backend count intact`() {
        // count 5 from the backend, but only alice is named locally. A re-delivered reaction from
        // alice must not decrement the four reactors the bucket knows about only as a number.
        val mixed = listOf(countOnly(count = 5).copy(users = listOf(alice), userReactions = listOf(UserReaction(alice, 1L))))

        val result = applyReactionToBucket(reaction(alice), mixed)

        assertEquals(1, result.size)
        assertEquals(5, result[0].count)
        assertEquals(listOf(alice), result[0].users)
    }

    @Test
    fun `mutate reports whether the bucket actually changed`() {
        val target = "\$mutate-target"
        evict(target)

        assertTrue(MessageReactionsCache.mutate(target) { applyReactionToBucket(reaction(alice), it) })
        assertFalse(MessageReactionsCache.mutate(target) { it })

        evict(target)
    }

    /**
     * Regression test for the lost-update race: reactions used to be applied by reading the whole
     * `eventId -> reactions` map, transforming the copy and handing it back to `setAll`, which
     * clears before it writes. Concurrent (or merely batched) writers therefore deleted each
     * other's entries. Every writer below must survive.
     */
    @Test
    fun `concurrent writers on distinct targets all survive`() {
        val writerCount = 32
        val targets = (0 until writerCount).map { "\$concurrent-target-$it" }
        targets.forEach { evict(it) }

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writerCount)
        try {
            targets.forEach { target ->
                pool.execute {
                    start.await()
                    MessageReactionsCache.mutate(target) { existing ->
                        applyReactionToBucket(reaction(alice, target = target), existing)
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        targets.forEach { target ->
            val bucket = MessageReactionsCache.getReactions(target)
            assertEquals("bucket for $target was clobbered", 1, bucket.size)
            assertEquals(1, bucket[0].count)
        }

        targets.forEach { evict(it) }
    }

    /**
     * Two reactions arriving in the same `sync_complete` batch land on the same target. Both must be
     * present afterwards — under the old whole-map write the second overwrote the first.
     */
    @Test
    fun `batched reactions on one target accumulate`() {
        val target = "\$batched-target"
        evict(target)

        MessageReactionsCache.mutate(target) { applyReactionToBucket(reaction(alice, target = target), it) }
        MessageReactionsCache.mutate(target) { applyReactionToBucket(reaction(bob, target = target), it) }

        val bucket = MessageReactionsCache.getReactions(target)
        assertEquals(1, bucket.size)
        assertEquals(2, bucket[0].count)
        assertEquals(setOf(alice, bob), bucket[0].users.toSet())

        evict(target)
    }

    @Test
    fun `merge unions in emoji the local bucket never saw`() {
        val target = "\$merge-target"
        evict(target)
        MessageReactionsCache.mutate(target) { applyReactionToBucket(reaction(alice, "👍", target), it) }

        val changed = MessageReactionsCache.merge(
            mapOf(target to listOf(MessageReaction(emoji = "🎉", count = 3, users = emptyList()))),
        ) { existing, incoming -> existing + incoming.filter { candidate -> existing.none { it.emoji == candidate.emoji } } }

        assertTrue(changed)
        assertEquals(2, MessageReactionsCache.getReactions(target).size)

        evict(target)
    }
}
