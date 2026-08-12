package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.RoomPermissions
import net.vrkknn.andromuks.utils.parsePowerLevels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [parsePowerLevels], pinning that levels are read as **Long**.
 *
 * Matrix bounds a power level only by the canonical-JSON integer range (±2^53), and servers use the
 * top of it: Conduit grants a room-version-12 creator `9007199254740990` in `users`, and marks
 * state events nobody may send as `9007199254740991`. Read with `optInt` those truncate — the
 * creator's own level came out as **-2**, below `users_default`, so the account that owned the room
 * was reported as its least privileged member and every moderation control disappeared.
 */
class PowerLevelsParsingTest {

    private val creator = "@daedric:daedric.net"

    /** Real content, from the room that surfaced the truncation. */
    private fun conduitContent() = JSONObject(
        """
        {
          "events": {
            "m.room.canonical_alias": 9007199254740991,
            "m.room.encryption": 9007199254740991,
            "m.room.tombstone": 9007199254740991
          },
          "invite": 50,
          "users": { "$creator": 9007199254740990 }
        }
        """.trimIndent(),
    )

    @Test
    fun `a level near the canonical-JSON ceiling survives parsing`() {
        val pl = parsePowerLevels(conduitContent())!!

        assertEquals(9007199254740990L, pl.users[creator])
        assertEquals(9007199254740991L, pl.events["m.room.tombstone"])
    }

    @Test
    fun `the holder of a huge level outranks everyone instead of being reported at -2`() {
        val pl = parsePowerLevels(conduitContent())!!
        val noCreators = emptySet<String>()

        assertTrue(RoomPermissions.powerLevelOf(pl, noCreators, creator) > 0)
        assertTrue(RoomPermissions.canSendMessage(pl, noCreators, creator, isEncrypted = false))
        assertTrue(RoomPermissions.canPin(pl, noCreators, creator))
        assertTrue(RoomPermissions.canRedactOthers(pl, noCreators, creator))
        assertTrue(RoomPermissions.canKick(pl, noCreators, creator, "@someone:example.org"))
    }

    @Test
    fun `the ceiling levels render as infinity, ordinary ones as themselves`() {
        // 2^53-2 and 2^53-1 are how servers spell "unreachable"; printing the digits says nothing.
        assertEquals("∞", RoomPermissions.formatPowerLevel(9007199254740990L))
        assertEquals("∞", RoomPermissions.formatPowerLevel(9007199254740991L))
        assertEquals("∞", RoomPermissions.formatPowerLevel(RoomPermissions.CREATOR_POWER_LEVEL))

        assertEquals("100", RoomPermissions.formatPowerLevel(100L))
        assertEquals("0", RoomPermissions.formatPowerLevel(0L))
        assertEquals("-1", RoomPermissions.formatPowerLevel(-1L))
        assertEquals("9007199254740989", RoomPermissions.formatPowerLevel(9007199254740989L))
    }

    @Test
    fun `an unspecified level falls back to the spec default`() {
        val pl = parsePowerLevels(JSONObject("{}"))!!

        assertEquals(0L, pl.usersDefault)
        assertEquals(0L, pl.eventsDefault)
        assertEquals(50L, pl.stateDefault)
        assertEquals(50L, pl.redact)
        assertEquals(50L, pl.kick)
        assertEquals(50L, pl.ban)
        assertEquals(50L, pl.invite)
    }
}
