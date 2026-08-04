package net.vrkknn.andromuks

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SpaceListCache].
 *
 * Two things here are easy to break silently. First, **order is not the map's order**: the cache
 * keeps a separate `spaceOrder` list so the sidebar matches the server's `top_level_spaces` rather
 * than `ConcurrentHashMap` iteration order. Second, `space_edges` is stored as a serialised string
 * on purpose — to avoid a JSONObject deep copy on every write — so every read returns a fresh
 * object and callers cannot mutate the cache by accident.
 */
class SpaceListCacheTest {

    private fun space(id: String, name: String = id) = SpaceItem(id = id, name = name, avatarUrl = null, rooms = emptyList())

    @Before
    fun setUp() = SpaceListCache.clear()

    @After
    fun tearDown() = SpaceListCache.clear()

    // ---------------------------------------------------------------- ordering

    @Test
    fun `getAllSpaces preserves the order it was given, not map order`() {
        // Deliberately not alphabetical, and not hash order.
        val ordered = listOf(space("!zulu"), space("!alpha"), space("!mike"))

        SpaceListCache.updateSpaces(ordered)

        assertEquals(listOf("!zulu", "!alpha", "!mike"), SpaceListCache.getAllSpaces().map { it.id })
    }

    @Test
    fun `updateSpaces replaces the previous list rather than merging`() {
        SpaceListCache.updateSpaces(listOf(space("!a"), space("!b")))

        SpaceListCache.updateSpaces(listOf(space("!c")))

        assertEquals(listOf("!c"), SpaceListCache.getAllSpaces().map { it.id })
        assertNull(SpaceListCache.getSpace("!a"))
        assertEquals(1, SpaceListCache.getSpaceCount())
    }

    @Test
    fun `updateSpace appends a new space to the end of the order`() {
        SpaceListCache.updateSpaces(listOf(space("!a"), space("!b")))

        SpaceListCache.updateSpace(space("!c"))

        assertEquals(listOf("!a", "!b", "!c"), SpaceListCache.getAllSpaces().map { it.id })
    }

    @Test
    fun `updateSpace on an existing space replaces it in place without reordering`() {
        SpaceListCache.updateSpaces(listOf(space("!a", "First"), space("!b"), space("!c")))

        SpaceListCache.updateSpace(space("!a", "Renamed"))

        assertEquals(listOf("!a", "!b", "!c"), SpaceListCache.getAllSpaces().map { it.id })
        assertEquals("Renamed", SpaceListCache.getSpace("!a")?.name)
    }

    @Test
    fun `removeSpace drops it from both the map and the order`() {
        SpaceListCache.updateSpaces(listOf(space("!a"), space("!b"), space("!c")))

        SpaceListCache.removeSpace("!b")

        assertEquals(listOf("!a", "!c"), SpaceListCache.getAllSpaces().map { it.id })
        assertNull(SpaceListCache.getSpace("!b"))
        assertEquals(2, SpaceListCache.getSpaceCount())
    }

    @Test
    fun `removing an unknown space is a no-op`() {
        SpaceListCache.updateSpaces(listOf(space("!a")))

        SpaceListCache.removeSpace("!nothing")

        assertEquals(listOf("!a"), SpaceListCache.getAllSpaces().map { it.id })
    }

    @Test
    fun `v12 space ids are handled like any other key`() {
        val v12 = "!gomuks2fjNJgXSZ-lZPoQWB_2za-KW_l2Hs6roxWKk4"
        SpaceListCache.updateSpaces(listOf(space(v12), space("!legacy:example.org")))

        assertEquals(listOf(v12, "!legacy:example.org"), SpaceListCache.getAllSpaces().map { it.id })
        assertEquals(v12, SpaceListCache.getSpace(v12)?.id)
    }

    // ---------------------------------------------------------------- space_edges

    @Test
    fun `space edges round-trip`() {
        val edges = JSONObject("""{"!parent": [{"child_id": "!child"}]}""")

        SpaceListCache.setSpaceEdges(edges)

        assertEquals(1, SpaceListCache.getSpaceEdges()?.optJSONArray("!parent")?.length())
    }

    @Test
    fun `each read of space edges returns a fresh object`() {
        // Stored as a string precisely so readers can't mutate the cache; if this ever returned the
        // stored instance, one caller editing the edges would silently rewrite everyone's copy.
        SpaceListCache.setSpaceEdges(JSONObject("""{"!parent": ["!child"]}"""))

        val first = SpaceListCache.getSpaceEdges()
        first?.put("!injected", "oops")
        val second = SpaceListCache.getSpaceEdges()

        assertNotSame(first, second)
        assertFalse(second?.has("!injected") == true)
    }

    @Test
    fun `space edges can be cleared by setting null`() {
        SpaceListCache.setSpaceEdges(JSONObject("""{"!parent": ["!child"]}"""))

        SpaceListCache.setSpaceEdges(null)

        assertNull(SpaceListCache.getSpaceEdges())
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `an empty cache reports empty`() {
        assertTrue(SpaceListCache.isEmpty())
        assertEquals(0, SpaceListCache.getSpaceCount())
        assertTrue(SpaceListCache.getAllSpaces().isEmpty())
        assertNull(SpaceListCache.getSpaceEdges())
    }

    @Test
    fun `clear drops spaces, order and edges together`() {
        SpaceListCache.updateSpaces(listOf(space("!a")))
        SpaceListCache.setSpaceEdges(JSONObject("""{"!a": []}"""))

        SpaceListCache.clear()

        assertTrue(SpaceListCache.isEmpty())
        assertTrue(SpaceListCache.getAllSpaces().isEmpty())
        assertNull(SpaceListCache.getSpaceEdges())

        // The order list must be cleared too, or a later updateSpace would resurrect stale ids.
        SpaceListCache.updateSpace(space("!b"))
        assertEquals(listOf("!b"), SpaceListCache.getAllSpaces().map { it.id })
    }
}
