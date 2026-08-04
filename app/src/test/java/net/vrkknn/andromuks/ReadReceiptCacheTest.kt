package net.vrkknn.andromuks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ReadReceiptCache] — see docs/RECEIPTS.md.
 *
 * The cache keeps two structures per room and they must not drift:
 *   forward index  roomId → eventId → receipts
 *   inverted index roomId → userId  → eventId
 *
 * The inverted index exists to replace an O(events × users) scan that ran every time a receipt moved
 * during sync, so it is a real correctness surface, not a convenience: if it disagrees with the
 * forward index, a receipt renders on the wrong message or on two messages at once.
 */
class ReadReceiptCacheTest {

    private val room = "!abc:example.org"
    private val otherRoom = "!xyz:example.org"
    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"

    private fun receipt(userId: String, eventId: String, ts: Long = 1_000L, roomId: String = room) =
        ReadReceipt(userId = userId, eventId = eventId, timestamp = ts, receiptType = "m.read", roomId = roomId)

    @Before
    fun setUp() = ReadReceiptCache.clear()

    @After
    fun tearDown() = ReadReceiptCache.clear()

    // ---------------------------------------------------------------- round trip

    @Test
    fun `setForRoom stores receipts and the inverted index`() {
        ReadReceiptCache.setForRoom(
            room,
            mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1")), "${'$'}e2" to listOf(receipt(bob, "${'$'}e2"))),
            mapOf(alice to "${'$'}e1", bob to "${'$'}e2"),
        )

        assertEquals(2, ReadReceiptCache.getForRoom(room).size)
        assertEquals(alice, ReadReceiptCache.getForRoom(room)["${'$'}e1"]?.single()?.userId)
        assertEquals("${'$'}e1", ReadReceiptCache.getUserEventId(room, alice))
        assertEquals("${'$'}e2", ReadReceiptCache.getUserEventId(room, bob))
    }

    @Test
    fun `several users can share one event`() {
        ReadReceiptCache.setForRoom(
            room,
            mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1"), receipt(bob, "${'$'}e1"))),
            mapOf(alice to "${'$'}e1", bob to "${'$'}e1"),
        )

        assertEquals(2, ReadReceiptCache.getForRoom(room)["${'$'}e1"]?.size)
    }

    @Test
    fun `empty receipt lists are not stored`() {
        ReadReceiptCache.setForRoom(
            room,
            mapOf("${'$'}e1" to emptyList(), "${'$'}e2" to listOf(receipt(alice, "${'$'}e2"))),
            mapOf(alice to "${'$'}e2"),
        )

        val stored = ReadReceiptCache.getForRoom(room)
        assertEquals(1, stored.size)
        assertFalse(stored.containsKey("${'$'}e1"))
    }

    @Test
    fun `an unknown room reads as empty rather than null`() {
        assertTrue(ReadReceiptCache.getForRoom("!nothing:example.org").isEmpty())
        assertNull(ReadReceiptCache.getUserEventId("!nothing:example.org", alice))
    }

    // ---------------------------------------------------------------- room partitioning

    @Test
    fun `setForRoom replaces only the room it is given`() {
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1"))), mapOf(alice to "${'$'}e1"))
        ReadReceiptCache.setForRoom(
            otherRoom,
            mapOf("${'$'}o1" to listOf(receipt(alice, "${'$'}o1", roomId = otherRoom))),
            mapOf(alice to "${'$'}o1"),
        )

        // The same user legitimately has a receipt in each room; they must not overwrite each other.
        assertEquals("${'$'}e1", ReadReceiptCache.getUserEventId(room, alice))
        assertEquals("${'$'}o1", ReadReceiptCache.getUserEventId(otherRoom, alice))
        assertEquals(setOf(room, otherRoom), ReadReceiptCache.getRoomIds())
    }

    @Test
    fun `setForRoom fully replaces the previous state of that room`() {
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1"))), mapOf(alice to "${'$'}e1"))

        // Alice's receipt moved forward; the old event must not linger.
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e2" to listOf(receipt(alice, "${'$'}e2"))), mapOf(alice to "${'$'}e2"))

        assertEquals(setOf("${'$'}e2"), ReadReceiptCache.getForRoom(room).keys)
        assertEquals("${'$'}e2", ReadReceiptCache.getUserEventId(room, alice))
    }

    @Test
    fun `clearRoom drops both indexes for that room only`() {
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1"))), mapOf(alice to "${'$'}e1"))
        ReadReceiptCache.setForRoom(
            otherRoom,
            mapOf("${'$'}o1" to listOf(receipt(bob, "${'$'}o1", roomId = otherRoom))),
            mapOf(bob to "${'$'}o1"),
        )

        ReadReceiptCache.clearRoom(room)

        assertTrue(ReadReceiptCache.getForRoom(room).isEmpty())
        assertNull(ReadReceiptCache.getUserEventId(room, alice))
        assertEquals(setOf(otherRoom), ReadReceiptCache.getRoomIds())
        assertEquals("${'$'}o1", ReadReceiptCache.getUserEventId(otherRoom, bob))
    }

    // ---------------------------------------------------------------- clearForEventIds

    @Test
    fun `clearForEventIds removes the events and de-indexes their users`() {
        ReadReceiptCache.setForRoom(
            room,
            mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1")), "${'$'}e2" to listOf(receipt(bob, "${'$'}e2"))),
            mapOf(alice to "${'$'}e1", bob to "${'$'}e2"),
        )

        ReadReceiptCache.clearForEventIds(room, setOf("${'$'}e1"))

        assertEquals(setOf("${'$'}e2"), ReadReceiptCache.getForRoom(room).keys)
        assertNull(ReadReceiptCache.getUserEventId(room, alice))
        assertEquals("${'$'}e2", ReadReceiptCache.getUserEventId(room, bob))
    }

    @Test
    fun `clearForEventIds keeps the index entry of a user who has since moved on`() {
        // Eviction is by event, but the inverted index is by user. If Alice's receipt already moved
        // to $e2, evicting the stale $e1 must not un-index her — that would lose a live receipt and
        // her avatar would vanish from the timeline until the next full receipt refresh.
        ReadReceiptCache.setForRoom(
            room,
            mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1")), "${'$'}e2" to listOf(receipt(alice, "${'$'}e2"))),
            mapOf(alice to "${'$'}e2"),
        )

        ReadReceiptCache.clearForEventIds(room, setOf("${'$'}e1"))

        assertEquals("${'$'}e2", ReadReceiptCache.getUserEventId(room, alice))
    }

    @Test
    fun `clearForEventIds ignores unknown events and unknown rooms`() {
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1"))), mapOf(alice to "${'$'}e1"))

        ReadReceiptCache.clearForEventIds(room, setOf("${'$'}missing"))
        ReadReceiptCache.clearForEventIds("!nothing:example.org", setOf("${'$'}e1"))
        ReadReceiptCache.clearForEventIds(room, emptySet())

        assertEquals(setOf("${'$'}e1"), ReadReceiptCache.getForRoom(room).keys)
        assertEquals("${'$'}e1", ReadReceiptCache.getUserEventId(room, alice))
    }

    // ---------------------------------------------------------------- snapshot safety

    @Test
    fun `getForRoom returns copies that cannot mutate the cache`() {
        // Callers iterate and transform these; the internal lists are mutable, so handing out live
        // references would let a caller corrupt the cache or trip a ConcurrentModificationException.
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1"))), mapOf(alice to "${'$'}e1"))

        val snapshot = ReadReceiptCache.getForRoom(room)
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e2" to listOf(receipt(bob, "${'$'}e2"))), mapOf(bob to "${'$'}e2"))

        assertEquals(setOf("${'$'}e1"), snapshot.keys)
        assertEquals(setOf("${'$'}e2"), ReadReceiptCache.getForRoom(room).keys)
    }

    @Test
    fun `setForRoom copies the maps it is handed`() {
        val receipts = mutableMapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1")))
        val index = mutableMapOf(alice to "${'$'}e1")
        ReadReceiptCache.setForRoom(room, receipts, index)

        receipts["${'$'}e2"] = listOf(receipt(bob, "${'$'}e2"))
        index[bob] = "${'$'}e2"

        assertEquals(setOf("${'$'}e1"), ReadReceiptCache.getForRoom(room).keys)
        assertNull(ReadReceiptCache.getUserEventId(room, bob))
    }

    @Test
    fun `v12 room ids are handled like any other key`() {
        // Room version 12 dropped the ":server" suffix, so a room id can be a bare opaque string
        // with no colon. Nothing here may split or normalise a room id — it is an opaque map key.
        val v12Room = "!gomuks2fjNJgXSZ-lZPoQWB_2za-KW_l2Hs6roxWKk4"
        ReadReceiptCache.setForRoom(v12Room, mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1", roomId = v12Room))), mapOf(alice to "${'$'}e1"))
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e9" to listOf(receipt(alice, "${'$'}e9"))), mapOf(alice to "${'$'}e9"))

        assertEquals("${'$'}e1", ReadReceiptCache.getUserEventId(v12Room, alice))
        assertEquals("${'$'}e9", ReadReceiptCache.getUserEventId(room, alice))
        assertEquals(setOf(v12Room, room), ReadReceiptCache.getRoomIds())

        ReadReceiptCache.clearRoom(v12Room)

        assertNull(ReadReceiptCache.getUserEventId(v12Room, alice))
        assertEquals("${'$'}e9", ReadReceiptCache.getUserEventId(room, alice))
    }

    @Test
    fun `clear empties every room`() {
        ReadReceiptCache.setForRoom(room, mapOf("${'$'}e1" to listOf(receipt(alice, "${'$'}e1"))), mapOf(alice to "${'$'}e1"))
        ReadReceiptCache.setForRoom(
            otherRoom,
            mapOf("${'$'}o1" to listOf(receipt(bob, "${'$'}o1", roomId = otherRoom))),
            mapOf(bob to "${'$'}o1"),
        )

        ReadReceiptCache.clear()

        assertTrue(ReadReceiptCache.getRoomIds().isEmpty())
        assertTrue(ReadReceiptCache.getForRoom(room).isEmpty())
        assertNull(ReadReceiptCache.getUserEventId(otherRoom, bob))
    }
}
