package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.RoomStateStore
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [RoomStateStore]'s parsing and its two RAM tiers.
 *
 * **Scope note.** The SQLite half is not covered here and cannot be: there is no Robolectric on the
 * unit-test classpath, so `android.database.sqlite` is the stubbed android.jar which
 * `isReturnDefaultValues = true` turns into null/0 rather than something that behaves like a
 * database. In these tests `RoomMetadataStore.writableDbOrNull()` is therefore null and every
 * `persist*` call early-returns, which is exactly what makes the RAM assertions below safe to run.
 * Disk behaviour (the replace transaction, the migration, hydration) is emulator work — see the
 * instrumentation gap tracked in GH issue #20.
 *
 * What *is* pinned here is the logic most likely to rot: member exclusion at every entry point, and
 * the replace-vs-merge distinction between a full `get_room_state` answer and a targeted
 * `get_specific_room_state` one. Getting that backwards resurrects removed state events.
 */
class RoomStateStoreTest {

    private val room = "!abc:example.org"
    private val otherRoom = "!def:example.org"

    @Before
    fun setUp() = RoomStateStore.clearMemory()

    @After
    fun tearDown() = RoomStateStore.clearMemory()

    private fun stateArray(vararg json: String) = JSONArray("[${json.joinToString(",")}]")

    private fun ev(type: String, stateKey: String = "", body: String = """"x":1""") =
        """{"type":"$type","state_key":"$stateKey","content":{$body},"sender":"@a:example.org","event_id":"${'$'}e","timestamp":17}"""

    private fun parsed(roomId: String = room, name: String? = "Room") =
        RoomState(roomId = roomId, name = name, canonicalAlias = null, topic = null, avatarUrl = null)

    // ------------------------------------------------------------------ flatten

    @Test
    fun `flatten keeps type, state key, content and metadata`() {
        val events = stateArray(ev("m.room.name", body = """"name":"Hello""""))

        val flat = RoomStateStore.flatten(events)

        assertEquals(1, flat.size)
        assertEquals("m.room.name", flat[0].type)
        assertEquals("", flat[0].stateKey)
        assertEquals("Hello", flat[0].content.optString("name"))
        assertEquals("@a:example.org", flat[0].sender)
        assertEquals(17L, flat[0].timestamp)
    }

    @Test
    fun `flatten keeps event types the app has never heard of`() {
        // The whole reason the table is (type, state_key, content) rather than typed columns.
        val events = stateArray(
            ev("com.beeper.room_features", body = """"threads":true"""),
            ev("io.element.functional_members"),
            ev("com.beeper.disappearing_timer", body = """"seconds":86400"""),
        )

        val flat = RoomStateStore.flatten(events)

        assertEquals(3, flat.size)
        assertEquals(true, flat[0].content.optBoolean("threads"))
        assertEquals(86400, flat[2].content.optInt("seconds"))
    }

    @Test
    fun `flatten preserves the state key for keyed events`() {
        val flat = RoomStateStore.flatten(stateArray(ev("m.space.parent", stateKey = "!space:example.org")))

        assertEquals("!space:example.org", flat[0].stateKey)
    }

    @Test
    fun `flatten drops member events`() {
        val events = stateArray(
            ev("m.room.member", stateKey = "@bob:example.org"),
            ev("m.room.name"),
        )

        val flat = RoomStateStore.flatten(events)

        assertEquals(1, flat.size)
        assertEquals("m.room.name", flat[0].type)
    }

    @Test
    fun `flatten skips malformed entries without losing the rest`() {
        val events = JSONArray(
            """[{"no_type":true}, {"type":"m.room.name"}, ${ev("m.room.topic")}]""",
        )

        // Entry 1 has no type, entry 2 has no content object; the third must still survive.
        val flat = RoomStateStore.flatten(events)

        assertEquals(1, flat.size)
        assertEquals("m.room.topic", flat[0].type)
    }

    // ------------------------------------------------------------------ parsed tier

    @Test
    fun `an unseen room reports null parsed state`() {
        assertNull(RoomStateStore.getParsed(room))
    }

    @Test
    fun `ingesting a full state publishes the parsed state`() {
        RoomStateStore.ingestFullState(room, stateArray(ev("m.room.name")), parsed(name = "Hello"))

        assertEquals("Hello", RoomStateStore.getParsed(room)?.name)
    }

    @Test
    fun `rooms do not read each others parsed state`() {
        RoomStateStore.ingestFullState(room, stateArray(ev("m.room.name")), parsed())

        assertNull(RoomStateStore.getParsed(otherRoom))
    }

    // ------------------------------------------------------------------ raw tier

    @Test
    fun `raw content is readable by type and state key`() {
        RoomStateStore.ingestFullState(
            room,
            stateArray(ev("m.room.join_rules", body = """"join_rule":"invite"""")),
            parsed(),
        )

        assertEquals("invite", RoomStateStore.getRawContent(room, "m.room.join_rules")?.optString("join_rule"))
        assertTrue(RoomStateStore.isRawResident(room))
    }

    @Test
    fun `member content is never readable even if asked for`() {
        RoomStateStore.ingestFullState(
            room,
            stateArray(ev("m.room.member", stateKey = "@bob:example.org")),
            parsed(),
        )

        assertNull(RoomStateStore.getRawContent(room, "m.room.member", "@bob:example.org"))
    }

    @Test
    fun `a full state response replaces rather than merges`() {
        RoomStateStore.ingestFullState(
            room,
            stateArray(ev("m.room.topic"), ev("m.room.server_acl")),
            parsed(),
        )

        // The room removed its server ACL; the next full response simply omits it.
        RoomStateStore.ingestFullState(room, stateArray(ev("m.room.topic")), parsed())

        assertNull(RoomStateStore.getRawContent(room, "m.room.server_acl"))
        assertTrue(RoomStateStore.getRawContent(room, "m.room.topic") != null)
    }

    @Test
    fun `a targeted response merges rather than replaces`() {
        RoomStateStore.ingestFullState(room, stateArray(ev("m.room.topic")), parsed())

        // get_specific_room_state answers only for what it was asked; it says nothing about topic.
        RoomStateStore.ingestPartialState(room, stateArray(ev("m.room.join_rules")))

        assertTrue(RoomStateStore.getRawContent(room, "m.room.topic") != null)
        assertTrue(RoomStateStore.getRawContent(room, "m.room.join_rules") != null)
    }

    // ------------------------------------------------------------------ eviction

    @Test
    fun `forgetRoom drops both tiers for that room only`() {
        RoomStateStore.ingestFullState(room, stateArray(ev("m.room.topic")), parsed())
        RoomStateStore.ingestFullState(otherRoom, stateArray(ev("m.room.topic")), parsed(otherRoom))

        RoomStateStore.forgetRoom(room)

        assertNull(RoomStateStore.getParsed(room))
        assertFalse(RoomStateStore.isRawResident(room))
        assertEquals("Room", RoomStateStore.getParsed(otherRoom)?.name)
        assertTrue(RoomStateStore.isRawResident(otherRoom))
    }

    @Test
    fun `the raw tier is LRU-bounded but the parsed tier is not`() {
        // 30 rooms through a 24-room raw bound. The parsed state is small and wanted for every
        // room (headers, permissions, room list), so only the raw JSON is allowed to age out.
        repeat(30) { i ->
            val id = "!room$i:example.org"
            RoomStateStore.ingestFullState(id, stateArray(ev("m.room.topic")), parsed(id))
        }

        assertEquals(30, RoomStateStore.allParsed().size)
        assertFalse(RoomStateStore.isRawResident("!room0:example.org"))
        assertTrue(RoomStateStore.isRawResident("!room29:example.org"))
    }

    @Test
    fun `clearMemory empties both tiers`() {
        RoomStateStore.ingestFullState(room, stateArray(ev("m.room.topic")), parsed())

        RoomStateStore.clearMemory()

        assertTrue(RoomStateStore.allParsed().isEmpty())
        assertFalse(RoomStateStore.isRawResident(room))
    }
}
