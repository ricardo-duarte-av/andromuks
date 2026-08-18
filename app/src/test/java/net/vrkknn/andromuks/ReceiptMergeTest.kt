package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.ReceiptFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ReceiptFunctions.mergeCachedReceiptsIntoRoom] — see docs/RECEIPTS.md.
 *
 * The invariant under test is "one receipt per user per room": a user's avatar sits on exactly the
 * latest event they have read, and moving it must *remove* the old placement. This merge is the only
 * receipt write path a secondary ViewModel (chat bubble, ShortcutActivity) has, so an eviction
 * missed here shows up as the same avatar rendered on two message bubbles at once.
 */
class ReceiptMergeTest {

    private val e1 = "\$e1"
    private val e2 = "\$e2"
    private val e3 = "\$e3"
    private val room = "!abc:example.org"
    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"

    private fun receipt(userId: String, eventId: String, ts: Long = 1_000L) =
        ReadReceipt(userId = userId, eventId = eventId, timestamp = ts, receiptType = "m.read", roomId = room)

    /** Every user appears on at most one event across the whole room map. */
    private fun assertNoDuplicates(map: Map<String, MutableList<ReadReceipt>>) {
        val placements = map.values.flatten().map { it.userId }
        assertEquals(
            "a user is placed on more than one event: $map",
            placements.distinct().size,
            placements.size,
        )
    }

    /** The inverted index must agree with the forward map, or the *next* move fails to evict. */
    private fun assertIndexAgrees(map: Map<String, MutableList<ReadReceipt>>, index: Map<String, String>) {
        for ((eventId, receipts) in map) {
            for (r in receipts) {
                assertEquals("index disagrees for ${r.userId}", eventId, index[r.userId])
            }
        }
    }

    @Test
    fun `first population places receipts and builds the index`() {
        val map = mutableMapOf<String, MutableList<ReadReceipt>>()
        val index = mutableMapOf<String, String>()

        val changed = ReceiptFunctions.mergeCachedReceiptsIntoRoom(
            map,
            index,
            mapOf(e1 to listOf(receipt(alice, e1)), e2 to listOf(receipt(bob, e2))),
        )

        assertEquals(setOf(e1, e2), changed.toSet())
        assertEquals(alice, map[e1]?.single()?.userId)
        assertEquals(bob, map[e2]?.single()?.userId)
        assertIndexAgrees(map, index)
    }

    /**
     * The regression this whole test class exists for. The target event is absent from the live map
     * (the common case on a secondary VM: the cache has advanced to an event this VM has never seen
     * a receipt for), so the merge takes the "place fresh" path — which used to skip eviction
     * entirely and leave Alice on both events.
     */
    @Test
    fun `moving a user to an event not yet in the map evicts the old placement`() {
        val map = mutableMapOf(e1 to mutableListOf(receipt(alice, e1)))
        val index = mutableMapOf(alice to e1)

        ReceiptFunctions.mergeCachedReceiptsIntoRoom(map, index, mapOf(e2 to listOf(receipt(alice, e2))))

        assertNull("alice must not remain on the old event", map[e1])
        assertEquals(alice, map[e2]?.single()?.userId)
        assertEquals(e2, index[alice])
        assertNoDuplicates(map)
    }

    @Test
    fun `moving a user to an event that already has other receipts evicts the old placement`() {
        val map = mutableMapOf(
            e1 to mutableListOf(receipt(alice, e1)),
            e2 to mutableListOf(receipt(bob, e2)),
        )
        val index = mutableMapOf(alice to e1, bob to e2)

        ReceiptFunctions.mergeCachedReceiptsIntoRoom(map, index, mapOf(e2 to listOf(receipt(alice, e2))))

        assertNull(map[e1])
        assertEquals(setOf(alice, bob), map[e2]?.map { it.userId }?.toSet())
        assertNoDuplicates(map)
        assertIndexAgrees(map, index)
    }

    @Test
    fun `an event keeps its other readers when one of them moves away`() {
        val map = mutableMapOf(e1 to mutableListOf(receipt(alice, e1), receipt(bob, e1)))
        val index = mutableMapOf(alice to e1, bob to e1)

        ReceiptFunctions.mergeCachedReceiptsIntoRoom(map, index, mapOf(e2 to listOf(receipt(alice, e2))))

        assertEquals(bob, map[e1]?.single()?.userId)
        assertEquals(alice, map[e2]?.single()?.userId)
        assertNoDuplicates(map)
        assertIndexAgrees(map, index)
    }

    @Test
    fun `repeated merges of the same snapshot are idempotent and report no change`() {
        val map = mutableMapOf<String, MutableList<ReadReceipt>>()
        val index = mutableMapOf<String, String>()
        val snapshot = mapOf(e1 to listOf(receipt(alice, e1), receipt(bob, e1)))

        ReceiptFunctions.mergeCachedReceiptsIntoRoom(map, index, snapshot)
        val secondPass = ReceiptFunctions.mergeCachedReceiptsIntoRoom(map, index, snapshot)

        assertTrue("a no-op merge must not report changes", secondPass.isEmpty())
        assertEquals(2, map[e1]?.size)
        assertNoDuplicates(map)
    }

    /**
     * A cache snapshot is a whole-room picture, so one merge can carry several users moving to
     * several different events at once. Iteration order must not be able to strand anyone.
     */
    @Test
    fun `several users moving in one merge all land exactly once`() {
        val map = mutableMapOf(e1 to mutableListOf(receipt(alice, e1), receipt(bob, e1)))
        val index = mutableMapOf(alice to e1, bob to e1)

        ReceiptFunctions.mergeCachedReceiptsIntoRoom(
            map,
            index,
            linkedMapOf(e2 to listOf(receipt(bob, e2)), e3 to listOf(receipt(alice, e3))),
        )

        assertNull(map[e1])
        assertEquals(bob, map[e2]?.single()?.userId)
        assertEquals(alice, map[e3]?.single()?.userId)
        assertNoDuplicates(map)
        assertIndexAgrees(map, index)
    }

    @Test
    fun `empty cached lists are ignored and never create empty entries`() {
        val map = mutableMapOf<String, MutableList<ReadReceipt>>()
        val index = mutableMapOf<String, String>()

        val changed = ReceiptFunctions.mergeCachedReceiptsIntoRoom(map, index, mapOf(e1 to emptyList()))

        assertTrue(changed.isEmpty())
        assertTrue(map.isEmpty())
        assertTrue(index.isEmpty())
    }
}
