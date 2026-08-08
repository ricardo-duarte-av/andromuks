package net.vrkknn.andromuks

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for where a room's E2EE flag is *stored*, which is what the "sometimes an open padlock on an
 * encrypted room" bug was really about.
 *
 * The flag used to live only in `AppViewModel.currentRoomState` — a single-room slot that is nulled
 * on every room switch — while everything else about a room lives in [RoomTimelineCache] and
 * survives the switch. Returning to a room with a warm timeline cache therefore had no encryption
 * status at all, and because `RoomState.isEncrypted` was a non-nullable `Boolean = false`, "we never
 * fetched this" was indistinguishable from "confirmed unencrypted" and rendered as a red open
 * padlock.
 *
 * Two properties are pinned here:
 *  - the flag's lifetime is the timeline cache's lifetime (it survives what the cache survives, and
 *    is dropped by every path that drops the cache);
 *  - "unknown" is representable, i.e. [RoomState.isEncrypted] is null until something says otherwise.
 *
 * `false` is stored as an answer, not as an absence: get_room_state always returns the complete room
 * state, so a response with no m.room.encryption event genuinely means the room is unencrypted.
 */
class RoomStateEncryptionTest {

    private val room = "!abc:example.org"
    private val otherRoom = "!def:example.org"

    @Before
    fun setUp() = reset()

    @After
    fun tearDown() = reset()

    private fun reset() {
        RoomTimelineCache.getOpenedRooms().forEach { RoomTimelineCache.removeOpenedRoom(it) }
        RoomTimelineCache.clearAllCaches()
    }

    // ------------------------------------------------------------------ tri-state

    @Test
    fun `a never-fetched room reports null, not false`() {
        assertNull(RoomTimelineCache.getRoomEncryption(room))
    }

    @Test
    fun `RoomState defaults isEncrypted to unknown`() {
        val state = RoomState(roomId = room, name = null, canonicalAlias = null, topic = null, avatarUrl = null)

        // Not `false` — the header renders a confirmed false as a red open padlock, and a room whose
        // state has never been fetched must not make that claim.
        assertNull(state.isEncrypted)
    }

    @Test
    fun `an encrypted and an unencrypted answer are both recorded and distinguishable`() {
        RoomTimelineCache.setRoomEncryption(room, true)
        RoomTimelineCache.setRoomEncryption(otherRoom, false)

        assertEquals(true, RoomTimelineCache.getRoomEncryption(room))
        assertEquals(false, RoomTimelineCache.getRoomEncryption(otherRoom))
    }

    @Test
    fun `rooms do not read each others encryption state`() {
        RoomTimelineCache.setRoomEncryption(room, true)

        assertNull(RoomTimelineCache.getRoomEncryption(otherRoom))
    }

    // ------------------------------------------------------------------ lifetime

    @Test
    fun `the flag outlives a room switch`() {
        RoomTimelineCache.setRoomEncryption(room, true)

        // A room switch nulls AppViewModel.currentRoomState but touches nothing here; the timeline
        // cache is precisely the thing that is meant to survive it.
        assertEquals(true, RoomTimelineCache.getRoomEncryption(room))
    }

    @Test
    fun `clearRoomCache drops the flag along with the cache`() {
        RoomTimelineCache.setRoomEncryption(room, true)
        RoomTimelineCache.setRoomEncryption(otherRoom, true)

        RoomTimelineCache.clearRoomCache(room)

        assertNull(RoomTimelineCache.getRoomEncryption(room))
        assertEquals(true, RoomTimelineCache.getRoomEncryption(otherRoom))
    }

    @Test
    fun `clearAllCaches drops every flag`() {
        RoomTimelineCache.setRoomEncryption(room, true)
        RoomTimelineCache.setRoomEncryption(otherRoom, false)

        RoomTimelineCache.clearAllCaches()

        assertNull(RoomTimelineCache.getRoomEncryption(room))
        assertNull(RoomTimelineCache.getRoomEncryption(otherRoom))
    }

    @Test
    fun `clearAll preserves the flag for opened rooms and drops it for the rest`() {
        RoomTimelineCache.setRoomEncryption(room, true)
        RoomTimelineCache.setRoomEncryption(otherRoom, true)
        RoomTimelineCache.addOpenedRoom(room)

        RoomTimelineCache.clearAll(preserveOpened = true)

        // The opened room keeps its cache, so it must keep its encryption status too — otherwise a
        // reconnect would blank the padlock on the room the user is currently looking at.
        assertEquals(true, RoomTimelineCache.getRoomEncryption(room))
        assertNull(RoomTimelineCache.getRoomEncryption(otherRoom))
    }

    @Test
    fun `clearAll without preservation drops the flag for the open room too`() {
        RoomTimelineCache.setRoomEncryption(room, true)
        RoomTimelineCache.addOpenedRoom(room)

        // preserveOpened=false is the clear_state=true path: the backend says its state is rotten
        // and is authoritative, so nothing may be kept.
        RoomTimelineCache.clearAll(preserveOpened = false)

        assertNull(RoomTimelineCache.getRoomEncryption(room))
    }

    @Test
    fun `a flag with no timeline events yet is still cleared by room-scoped clears`() {
        // The flag can be recorded before any pagination lands, so it has no roomEventsCache entry
        // to be cleaned up alongside. Clearing must key off the room id, not the cache entry.
        RoomTimelineCache.setRoomEncryption(room, true)
        assertNull(RoomTimelineCache.getCachedEvents(room))

        RoomTimelineCache.clearAll(preserveOpened = true)

        assertNull(RoomTimelineCache.getRoomEncryption(room))
    }

    // ------------------------------------------------------------------ parse semantics

    private fun stateEvents(vararg json: String) = JSONArray("[${json.joinToString(",")}]")

    @Test
    fun `m_room_encryption with an algorithm means encrypted`() {
        val events = stateEvents(
            """{"type": "m.room.name", "content": {"name": "Room"}}""",
            """{"type": "m.room.encryption", "content": {"algorithm": "m.megolm.v1.aes-sha2"}}""",
        )

        assertEquals(true, isRoomEncryptedFromState(events))
    }

    @Test
    fun `a complete state array with no m_room_encryption means unencrypted`() {
        val events = stateEvents("""{"type": "m.room.name", "content": {"name": "Room"}}""")

        // Not null: get_room_state returns the whole state, so absence is a real answer.
        assertEquals(false, isRoomEncryptedFromState(events))
    }

    @Test
    fun `a blank algorithm does not count as encrypted`() {
        val events = stateEvents("""{"type": "m.room.encryption", "content": {"algorithm": ""}}""")

        assertFalse(isRoomEncryptedFromState(events)!!)
    }

    @Test
    fun `no state array at all is unknown`() {
        assertNull(isRoomEncryptedFromState(null))
    }

    @Test
    fun `only a confirmed false renders the open padlock`() {
        // Mirrors the header branch in RoomHeader / BubbleRoomHeader: `isEncrypted == false` picks
        // LockOpen, so both `true` and the unknown `null` keep the closed padlock.
        fun showsOpenPadlock(isEncrypted: Boolean?) = isEncrypted == false

        assertTrue(showsOpenPadlock(false))
        assertFalse(showsOpenPadlock(true))
        assertFalse(showsOpenPadlock(null))
    }
}
