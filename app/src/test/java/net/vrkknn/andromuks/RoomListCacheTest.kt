package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.RoomMetadataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [RoomListCache] — the room list every AppViewModel reads, plus the write-through to
 * [RoomMetadataStore] that lets cold-start surfaces (notifications, bubbles, shortcuts) render
 * before the first `sync_complete`.
 *
 * No SQLite runs here: `RoomMetadataStore` only touches disk once `initialize(context)` has supplied
 * a helper, and guards every write on `helper == null`. Its in-memory mirror still updates, which is
 * exactly the layer these tests assert on.
 */
class RoomListCacheTest {

    private val roomA = "!a:example.org"
    private val roomB = "!b:example.org"
    private val v12Room = "!gomuks2fjNJgXSZ-lZPoQWB_2za-KW_l2Hs6roxWKk4"

    private fun room(id: String, name: String = "Room $id", sortTs: Long? = null) = RoomItem(
        id = id,
        name = name,
        messagePreview = null,
        messageSender = null,
        unreadCount = null,
        highlightCount = null,
        avatarUrl = null,
        sortingTimestamp = sortTs,
    )

    @Before
    fun setUp() = reset()

    @After
    fun tearDown() = reset()

    private fun reset() {
        RoomListCache.clear()
        RoomMetadataStore.clearAll()
    }

    // ---------------------------------------------------------------- basic storage

    @Test
    fun `rooms round-trip`() {
        RoomListCache.updateRoom(room(roomA, "Alpha"))

        assertEquals("Alpha", RoomListCache.getRoom(roomA)?.name)
        assertNull(RoomListCache.getRoom(roomB))
        assertEquals(1, RoomListCache.getRoomCount())
    }

    @Test
    fun `updateRoom replaces an existing entry`() {
        RoomListCache.updateRoom(room(roomA, "Before"))
        RoomListCache.updateRoom(room(roomA, "After"))

        assertEquals("After", RoomListCache.getRoom(roomA)?.name)
        assertEquals(1, RoomListCache.getRoomCount())
    }

    @Test
    fun `updateRooms merges rather than replacing`() {
        RoomListCache.updateRoom(room(roomA))

        RoomListCache.updateRooms(mapOf(roomB to room(roomB)))

        assertEquals(2, RoomListCache.getRoomCount())
        assertNotNull(RoomListCache.getRoom(roomA))
    }

    @Test
    fun `removeRoom drops only that room`() {
        RoomListCache.updateRoom(room(roomA))
        RoomListCache.updateRoom(room(roomB))

        RoomListCache.removeRoom(roomA)

        assertNull(RoomListCache.getRoom(roomA))
        assertNotNull(RoomListCache.getRoom(roomB))
    }

    @Test
    fun `v12 room ids are handled like any other key`() {
        RoomListCache.updateRoom(room(v12Room, "Modern"))

        assertEquals("Modern", RoomListCache.getRoom(v12Room)?.name)
        RoomListCache.removeRoom(v12Room)
        assertNull(RoomListCache.getRoom(v12Room))
    }

    @Test
    fun `getAllRooms is an unmodifiable live view`() {
        // Deliberately a view, not a copy — callers iterate it on hot paths and an O(N) copy per
        // read was measurable. Unmodifiable so a caller cannot mutate the cache through it.
        RoomListCache.updateRoom(room(roomA))
        val view = RoomListCache.getAllRooms()

        assertThrows(UnsupportedOperationException::class.java) {
            (view as MutableMap<String, RoomItem>).remove(roomA)
        }

        RoomListCache.updateRoom(room(roomB))
        assertEquals("a live view reflects later writes", 2, view.size)
    }

    // ---------------------------------------------------------------- latest event

    @Test
    fun `latest event only advances forward`() {
        // mark_read always needs a target, and a late-arriving older event must not rewind it.
        RoomListCache.updateLatestEvent(roomA, "\$new", 2000L)
        RoomListCache.updateLatestEvent(roomA, "\$old", 1000L)

        assertEquals("\$new", RoomListCache.getLatestEventId(roomA))
    }

    @Test
    fun `a newer event replaces the latest`() {
        RoomListCache.updateLatestEvent(roomA, "\$first", 1000L)
        RoomListCache.updateLatestEvent(roomA, "\$second", 2000L)

        assertEquals("\$second", RoomListCache.getLatestEventId(roomA))
    }

    @Test
    fun `an equal timestamp does not replace the latest`() {
        RoomListCache.updateLatestEvent(roomA, "\$first", 1000L)
        RoomListCache.updateLatestEvent(roomA, "\$tie", 1000L)

        assertEquals("\$first", RoomListCache.getLatestEventId(roomA))
    }

    @Test
    fun `latest events are per room and unknown rooms read null`() {
        RoomListCache.updateLatestEvent(roomA, "\$ea", 1000L)
        RoomListCache.updateLatestEvent(roomB, "\$eb", 1000L)

        assertEquals("\$ea", RoomListCache.getLatestEventId(roomA))
        assertEquals("\$eb", RoomListCache.getLatestEventId(roomB))
        assertNull(RoomListCache.getLatestEventId("!unknown:example.org"))
    }

    // ---------------------------------------------------------------- metadata write-through

    @Test
    fun `updating a room mirrors its metadata for cold start`() {
        RoomListCache.updateRoom(room(roomA, "Alpha", sortTs = 5000L))

        val row = RoomMetadataStore.getRow(roomA)
        assertEquals("Alpha", row?.name)
        assertEquals(5000L, row?.sortTs)
    }

    @Test
    fun `persist false skips the metadata write`() {
        // The sync_complete apply loop defers to one batched flush instead of ~500 single writes.
        RoomListCache.updateRoom(room(roomA, "Alpha", sortTs = 5000L), persist = false)

        assertNull(RoomMetadataStore.getRow(roomA))
        assertNotNull("the in-memory cache is still updated", RoomListCache.getRoom(roomA))
    }

    @Test
    fun `a name that is just the room id is not persisted`() {
        // The parser falls back to the raw room id when it cannot resolve a real name; persisting
        // that would show a raw id on the next cold start instead of nothing.
        RoomListCache.updateRoom(room(roomA, name = roomA, sortTs = 5000L))

        assertNull(RoomMetadataStore.getRow(roomA)?.name)
    }

    @Test
    fun `a room with nothing worth persisting writes no row`() {
        RoomListCache.updateRoom(room(roomA, name = roomA))

        assertNull(RoomMetadataStore.getRow(roomA))
    }

    @Test
    fun `removing a room also drops its persisted metadata`() {
        // Without this, hydrateFromDisk resurrects a room you left on the next cold start.
        RoomListCache.updateRoom(room(roomA, "Alpha", sortTs = 5000L))
        assertNotNull(RoomMetadataStore.getRow(roomA))

        RoomListCache.removeRoom(roomA)

        assertNull(RoomMetadataStore.getRow(roomA))
    }

    @Test
    fun `the persisted sort timestamp only moves forward`() {
        RoomListCache.updateRoom(room(roomA, "Alpha", sortTs = 5000L))
        RoomListCache.updateRoom(room(roomA, "Alpha", sortTs = 1000L))

        assertEquals(5000L, RoomMetadataStore.getRow(roomA)?.sortTs)
    }

    // ---------------------------------------------------------------- hydration

    @Test
    fun `hydrateFromDisk seeds rooms the cache does not already hold`() {
        RoomListCache.updateRoom(room(roomA, "Alpha", sortTs = 5000L))
        RoomListCache.clear() // process restart: memory gone, persisted metadata survives

        RoomListCache.hydrateFromDisk()

        val hydrated = RoomListCache.getRoom(roomA)
        assertEquals("Alpha", hydrated?.name)
        assertEquals(5000L, hydrated?.sortingTimestamp)
        // Stubs carry identity only — sync_complete fills the rest in within a second or two.
        assertNull(hydrated?.messagePreview)
        assertNull(hydrated?.unreadCount)
    }

    @Test
    fun `hydrateFromDisk never overwrites a warm in-memory room`() {
        // A live AppViewModel's data is strictly better than a disk stub.
        RoomListCache.updateRoom(room(roomA, "Persisted", sortTs = 5000L))
        RoomListCache.clear()
        RoomListCache.updateRoom(room(roomA, "Live from sync", sortTs = 9000L), persist = false)

        RoomListCache.hydrateFromDisk()

        assertEquals("Live from sync", RoomListCache.getRoom(roomA)?.name)
    }

    @Test
    fun `hydrateFromDisk with nothing persisted is a no-op`() {
        RoomListCache.hydrateFromDisk()

        assertEquals(0, RoomListCache.getRoomCount())
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `clear drops rooms and latest events together`() {
        RoomListCache.updateRoom(room(roomA))
        RoomListCache.updateLatestEvent(roomA, "\$e", 1000L)

        RoomListCache.clear()

        assertEquals(0, RoomListCache.getRoomCount())
        assertNull(RoomListCache.getLatestEventId(roomA))
    }

    @Test
    fun `isSuspiciouslySmall flags an empty or single-room cache`() {
        assertTrue(RoomListCache.isSuspiciouslySmall())

        RoomListCache.updateRoom(room(roomA))
        assertTrue(RoomListCache.isSuspiciouslySmall())

        RoomListCache.updateRoom(room(roomB))
        assertFalse(RoomListCache.isSuspiciouslySmall())
    }
}
