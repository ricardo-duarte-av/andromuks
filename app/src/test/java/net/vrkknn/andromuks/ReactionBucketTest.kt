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
    fun `re-applying the same sender toggles them back off`() {
        val added = applyReactionToBucket(reaction(alice), emptyList())
        val toggled = applyReactionToBucket(reaction(alice), added)

        assertTrue(toggled.isEmpty())
    }

    @Test
    fun `distinct emoji live in separate buckets`() {
        val thumbs = applyReactionToBucket(reaction(alice, "👍"), emptyList())
        val both = applyReactionToBucket(reaction(alice, "🎉"), thumbs)

        assertEquals(2, both.size)
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
