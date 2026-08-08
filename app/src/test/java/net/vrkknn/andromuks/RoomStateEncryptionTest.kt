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
 * Tests for how a room's E2EE flag is represented and parsed — the "sometimes an open padlock on an
 * encrypted room" bug.
 *
 * That bug had two halves. The first was storage: the flag lived only in
 * `AppViewModel.currentRoomState`, a single-room slot nulled on every room switch, so returning to a
 * room had no encryption status at all. That half is now [RoomStateStore]'s problem and is covered by
 * `RoomStateStoreTest`.
 *
 * The second half is what this file still pins: `RoomState.isEncrypted` was a non-nullable
 * `Boolean = false`, so "never fetched" was indistinguishable from "confirmed unencrypted" and
 * rendered as a red open padlock. Unknown must stay representable, and only a confirmed `false` may
 * render as unencrypted.
 *
 * `false` is an answer, not an absence: get_room_state returns the complete room state, so a response
 * carrying no m.room.encryption event genuinely means the room is unencrypted.
 */
class RoomStateEncryptionTest {

    private val room = "!abc:example.org"
    private val otherRoom = "!def:example.org"

    @Before
    fun setUp() = RoomStateStore.clearMemory()

    @After
    fun tearDown() = RoomStateStore.clearMemory()

    private fun stateEvents(vararg json: String) = JSONArray("[${json.joinToString(",")}]")

    private fun stateWithEncryption(roomId: String, isEncrypted: Boolean?) = RoomState(
        roomId = roomId,
        name = null,
        canonicalAlias = null,
        topic = null,
        avatarUrl = null,
        isEncrypted = isEncrypted,
    )

    // ------------------------------------------------------------------ tri-state

    @Test
    fun `a never-fetched room reports null, not false`() {
        assertNull(RoomStateStore.getParsed(room)?.isEncrypted)
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
        RoomStateStore.ingestFullState(room, stateEvents(), stateWithEncryption(room, true))
        RoomStateStore.ingestFullState(otherRoom, stateEvents(), stateWithEncryption(otherRoom, false))

        assertEquals(true, RoomStateStore.getParsed(room)?.isEncrypted)
        assertEquals(false, RoomStateStore.getParsed(otherRoom)?.isEncrypted)
    }

    @Test
    fun `rooms do not read each others encryption state`() {
        RoomStateStore.ingestFullState(room, stateEvents(), stateWithEncryption(room, true))

        assertNull(RoomStateStore.getParsed(otherRoom)?.isEncrypted)
    }

    // ------------------------------------------------------------------ parse semantics

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
