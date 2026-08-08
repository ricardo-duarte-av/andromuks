package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.RoomPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RoomPermissions].
 *
 * These predicates were inlined inside composables until now, which is precisely why one copy of
 * `canPin` was wrong: it compared against `events_default` (0 in most rooms) instead of
 * `state_default`, so it offered pinning to everyone. Pulling them out is what makes them testable
 * at all, so the wrong-default case is pinned explicitly below.
 *
 * The other thing worth pinning is that the unknown-power-levels fallback is **not** uniform:
 * sending fails open, everything else fails closed. That asymmetry is deliberate and easy to
 * "tidy" into a bug.
 */
class RoomPermissionsTest {

    private val me = "@me:example.org"
    private val them = "@them:example.org"

    private fun levels(
        users: Map<String, Int> = emptyMap(),
        usersDefault: Int = 0,
        events: Map<String, Int> = emptyMap(),
        eventsDefault: Int = 0,
        stateDefault: Int = 50,
        redact: Int = 50,
        kick: Int = 50,
        ban: Int = 50,
    ) = PowerLevelsInfo(
        users = users,
        usersDefault = usersDefault,
        redact = redact,
        kick = kick,
        ban = ban,
        invite = 50,
        events = events,
        eventsDefault = eventsDefault,
        stateDefault = stateDefault,
    )

    // ------------------------------------------------------------------ powerLevelOf

    @Test
    fun `power level prefers the explicit entry, then users_default`() {
        val pl = levels(users = mapOf(me to 75), usersDefault = 10)

        assertEquals(75, RoomPermissions.powerLevelOf(pl, me))
        assertEquals(10, RoomPermissions.powerLevelOf(pl, them))
    }

    @Test
    fun `power level is zero when unknown`() {
        assertEquals(0, RoomPermissions.powerLevelOf(null, me))
        assertEquals(0, RoomPermissions.powerLevelOf(levels(usersDefault = 10), null))
    }

    // ------------------------------------------------------------------ canSendMessage

    @Test
    fun `sending fails OPEN when power levels are unknown`() {
        // A slow state fetch must not lock someone out of a room they can post in.
        assertTrue(RoomPermissions.canSendMessage(null, me, false))
        assertTrue(RoomPermissions.canSendMessage(levels(), null, false))
    }

    @Test
    fun `sending is refused below the required level`() {
        val pl = levels(events = mapOf("m.room.message" to 50), usersDefault = 0)

        assertFalse(RoomPermissions.canSendMessage(pl, me, false))
        assertTrue(RoomPermissions.canSendMessage(pl.copy(users = mapOf(me to 50)), me, false))
    }

    @Test
    fun `an E2EE room is checked against m_room_encrypted, not m_room_message`() {
        // The client sends m.room.encrypted in E2EE rooms, so that is what the server enforces —
        // m.room.message never reaches it. A room can set the two to different levels.
        val pl = levels(
            users = mapOf(me to 25),
            events = mapOf("m.room.message" to 0, "m.room.encrypted" to 50),
        )

        assertTrue(RoomPermissions.canSendMessage(pl, me, false))
        assertFalse(RoomPermissions.canSendMessage(pl, me, isEncrypted = true))
    }

    @Test
    fun `unknown encryption is treated as unencrypted`() {
        val pl = levels(users = mapOf(me to 25), events = mapOf("m.room.encrypted" to 50))

        assertTrue(RoomPermissions.canSendMessage(pl, me, isEncrypted = null))
    }

    // ------------------------------------------------------------------ canRedactOthers

    @Test
    fun `redacting others fails CLOSED when power levels are unknown`() {
        assertFalse(RoomPermissions.canRedactOthers(null, me))
    }

    @Test
    fun `redacting others compares against the room redact level only`() {
        // Not against the target's power level — that is a kick/ban rule, not a redaction one.
        val pl = levels(users = mapOf(me to 50, them to 100), redact = 50)

        assertTrue(RoomPermissions.canRedactOthers(pl, me))
    }

    // ------------------------------------------------------------------ canPin

    @Test
    fun `pinning falls back to state_default, not events_default`() {
        // The bug this whole helper exists to prevent: events_default is 0 in most rooms, so
        // comparing against it offers pinning to everybody.
        val pl = levels(usersDefault = 0, eventsDefault = 0, stateDefault = 50)

        assertFalse(RoomPermissions.canPin(pl, me))
    }

    @Test
    fun `an explicit pinned_events level wins over state_default`() {
        val pl = levels(users = mapOf(me to 25), events = mapOf("m.room.pinned_events" to 25), stateDefault = 100)

        assertTrue(RoomPermissions.canPin(pl, me))
    }

    @Test
    fun `pinning fails CLOSED when power levels are unknown`() {
        assertFalse(RoomPermissions.canPin(null, me))
    }

    // ------------------------------------------------------------------ kick / ban

    @Test
    fun `kicking requires clearing the kick level AND outranking the target`() {
        val pl = levels(users = mapOf(me to 50, them to 50), kick = 50)

        // Equal power: cleared the kick bar, but you cannot kick a peer.
        assertFalse(RoomPermissions.canKick(pl, me, them))
        assertTrue(RoomPermissions.canKick(pl.copy(users = mapOf(me to 51, them to 50)), me, them))
    }

    @Test
    fun `kicking is refused below the kick level even when outranking the target`() {
        val pl = levels(users = mapOf(me to 40, them to 0), kick = 50)

        assertFalse(RoomPermissions.canKick(pl, me, them))
    }

    @Test
    fun `banning follows the same rule against the ban level`() {
        val pl = levels(users = mapOf(me to 50, them to 0), ban = 100)

        assertFalse(RoomPermissions.canBan(pl, me, them))
        assertTrue(RoomPermissions.canBan(pl.copy(ban = 50), me, them))
    }

    @Test
    fun `kick and ban fail CLOSED when power levels are unknown`() {
        assertFalse(RoomPermissions.canKick(null, me, them))
        assertFalse(RoomPermissions.canBan(null, me, them))
    }

    // ------------------------------------------------------------------ moderator redact

    @Test
    fun `moderator redact fails CLOSED where the message-level check would not`() {
        // canRedactOthers(null, ...) is also false here, but the distinction matters if its
        // fallback ever changes: the moderation screen must never offer a refused action.
        assertFalse(RoomPermissions.canRedactAsModerator(null, me))
        assertTrue(RoomPermissions.canRedactAsModerator(levels(users = mapOf(me to 50), redact = 50), me))
    }
}
