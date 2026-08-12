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

    /** A room with no privileged creators — every room below version 12, and most above it. */
    private val noCreators = emptySet<String>()

    private fun levels(
        users: Map<String, Long> = emptyMap(),
        usersDefault: Long = 0,
        events: Map<String, Long> = emptyMap(),
        eventsDefault: Long = 0,
        stateDefault: Long = 50,
        redact: Long = 50,
        kick: Long = 50,
        ban: Long = 50,
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
        val pl = levels(users = mapOf(me to 75L), usersDefault = 10L)

        assertEquals(75L, RoomPermissions.powerLevelOf(pl, noCreators, me))
        assertEquals(10L, RoomPermissions.powerLevelOf(pl, noCreators, them))
    }

    @Test
    fun `power level is zero when unknown`() {
        assertEquals(0L, RoomPermissions.powerLevelOf(null, noCreators, me))
        assertEquals(0L, RoomPermissions.powerLevelOf(levels(usersDefault = 10L), noCreators, null))
    }

    // ------------------------------------------------------------------ canSendMessage

    @Test
    fun `sending fails OPEN when power levels are unknown`() {
        // A slow state fetch must not lock someone out of a room they can post in.
        assertTrue(RoomPermissions.canSendMessage(null, noCreators, me, false))
        assertTrue(RoomPermissions.canSendMessage(levels(), noCreators, null, false))
    }

    @Test
    fun `sending is refused below the required level`() {
        val pl = levels(events = mapOf("m.room.message" to 50L), usersDefault = 0L)

        assertFalse(RoomPermissions.canSendMessage(pl, noCreators, me, false))
        assertTrue(RoomPermissions.canSendMessage(pl.copy(users = mapOf(me to 50L)), noCreators, me, false))
    }

    @Test
    fun `an E2EE room is checked against m_room_encrypted, not m_room_message`() {
        // The client sends m.room.encrypted in E2EE rooms, so that is what the server enforces —
        // m.room.message never reaches it. A room can set the two to different levels.
        val pl = levels(
            users = mapOf(me to 25L),
            events = mapOf("m.room.message" to 0L, "m.room.encrypted" to 50L),
        )

        assertTrue(RoomPermissions.canSendMessage(pl, noCreators, me, false))
        assertFalse(RoomPermissions.canSendMessage(pl, noCreators, me, isEncrypted = true))
    }

    @Test
    fun `unknown encryption is treated as unencrypted`() {
        val pl = levels(users = mapOf(me to 25L), events = mapOf("m.room.encrypted" to 50L))

        assertTrue(RoomPermissions.canSendMessage(pl, noCreators, me, isEncrypted = null))
    }

    // ------------------------------------------------------------------ canRedactOthers

    @Test
    fun `redacting others fails CLOSED when power levels are unknown`() {
        assertFalse(RoomPermissions.canRedactOthers(null, noCreators, me))
    }

    @Test
    fun `redacting others compares against the room redact level only`() {
        // Not against the target's power level — that is a kick/ban rule, not a redaction one.
        val pl = levels(users = mapOf(me to 50L, them to 100L), redact = 50L)

        assertTrue(RoomPermissions.canRedactOthers(pl, noCreators, me))
    }

    // ------------------------------------------------------------------ canPin

    @Test
    fun `pinning falls back to state_default, not events_default`() {
        // The bug this whole helper exists to prevent: events_default is 0 in most rooms, so
        // comparing against it offers pinning to everybody.
        val pl = levels(usersDefault = 0L, eventsDefault = 0L, stateDefault = 50L)

        assertFalse(RoomPermissions.canPin(pl, noCreators, me))
    }

    @Test
    fun `an explicit pinned_events level wins over state_default`() {
        val pl = levels(users = mapOf(me to 25L), events = mapOf("m.room.pinned_events" to 25L), stateDefault = 100L)

        assertTrue(RoomPermissions.canPin(pl, noCreators, me))
    }

    @Test
    fun `pinning fails CLOSED when power levels are unknown`() {
        assertFalse(RoomPermissions.canPin(null, noCreators, me))
    }

    // ------------------------------------------------------------------ kick / ban

    @Test
    fun `kicking requires clearing the kick level AND outranking the target`() {
        val pl = levels(users = mapOf(me to 50L, them to 50L), kick = 50L)

        // Equal power: cleared the kick bar, but you cannot kick a peer.
        assertFalse(RoomPermissions.canKick(pl, noCreators, me, them))
        assertTrue(RoomPermissions.canKick(pl.copy(users = mapOf(me to 51L, them to 50L)), noCreators, me, them))
    }

    @Test
    fun `kicking is refused below the kick level even when outranking the target`() {
        val pl = levels(users = mapOf(me to 40L, them to 0L), kick = 50L)

        assertFalse(RoomPermissions.canKick(pl, noCreators, me, them))
    }

    @Test
    fun `banning follows the same rule against the ban level`() {
        val pl = levels(users = mapOf(me to 50L, them to 0L), ban = 100L)

        assertFalse(RoomPermissions.canBan(pl, noCreators, me, them))
        assertTrue(RoomPermissions.canBan(pl.copy(ban = 50L), noCreators, me, them))
    }

    @Test
    fun `kick and ban fail CLOSED when power levels are unknown`() {
        assertFalse(RoomPermissions.canKick(null, noCreators, me, them))
        assertFalse(RoomPermissions.canBan(null, noCreators, me, them))
    }

    // ------------------------------------------------------------------ moderator redact

    @Test
    fun `moderator redact fails CLOSED where the message-level check would not`() {
        // canRedactOthers(null, ...) is also false here, but the distinction matters if its
        // fallback ever changes: the moderation screen must never offer a refused action.
        assertFalse(RoomPermissions.canRedactAsModerator(null, noCreators, me))
        assertTrue(RoomPermissions.canRedactAsModerator(levels(users = mapOf(me to 50L), redact = 50L), noCreators, me))
    }

    // ---------------------------------------------------------------------- creators

    private fun roomState(creator: String? = null, additionalCreators: List<String> = emptyList(), roomVersion: String? = "12") = RoomState(
        roomId = "!room:example.org",
        name = null,
        canonicalAlias = null,
        topic = null,
        avatarUrl = null,
        creator = creator,
        additionalCreators = additionalCreators,
        roomVersion = roomVersion,
    )

    @Test
    fun `creators are the create sender plus additional creators`() {
        val creators = RoomPermissions.creatorsOf(
            roomState(creator = me, additionalCreators = listOf(them, "@third:example.org")),
        )

        assertEquals(setOf(me, them, "@third:example.org"), creators)
    }

    @Test
    fun `a creator repeated in additional creators appears once`() {
        // Real rooms do this — the profile that prompted the fix lists the create sender in
        // additional_creators as well.
        assertEquals(setOf(me), RoomPermissions.creatorsOf(roomState(creator = me, additionalCreators = listOf(me))))
    }

    @Test
    fun `rooms older than v12 have no privileged creators`() {
        // Creating a room bought no standing before MSC4289, so the create event says nothing
        // about power and additional_creators means nothing even if present.
        listOf("11", "10", "1").forEach { version ->
            assertTrue(
                RoomPermissions.creatorsOf(
                    roomState(creator = me, additionalCreators = listOf(them), roomVersion = version),
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `an unknown or unparseable room version yields no creators`() {
        assertTrue(RoomPermissions.creatorsOf(roomState(creator = me, roomVersion = null)).isEmpty())
        assertTrue(RoomPermissions.creatorsOf(roomState(creator = me, roomVersion = "org.example.v12")).isEmpty())
    }

    @Test
    fun `no room state means no creators`() {
        assertTrue(RoomPermissions.creatorsOf(null).isEmpty())
    }

    @Test
    fun `blank creator entries are dropped`() {
        assertTrue(RoomPermissions.creatorsOf(roomState(creator = "", additionalCreators = listOf(" "))).isEmpty())
    }

    // ------------------------------------------------------- creators outrank everything

    /** A creator, as they actually appear: absent from `users` entirely. */
    private val creators = setOf(me)

    @Test
    fun `a creator outranks every level the room can express`() {
        val pl = levels(users = mapOf(them to 100L), usersDefault = 0L)

        assertEquals(RoomPermissions.CREATOR_POWER_LEVEL, RoomPermissions.powerLevelOf(pl, creators, me))
        assertTrue(RoomPermissions.powerLevelOf(pl, creators, me) > RoomPermissions.powerLevelOf(pl, creators, them))
    }

    @Test
    fun `a creator may send, redact and pin regardless of the levels`() {
        val pl = levels(
            usersDefault = 0L,
            events = mapOf("m.room.message" to 100L, "m.room.pinned_events" to 100L),
            redact = 100L,
        )

        assertTrue(RoomPermissions.canSendMessage(pl, creators, me, false))
        assertTrue(RoomPermissions.canRedactOthers(pl, creators, me))
        assertTrue(RoomPermissions.canPin(pl, creators, me))
        assertTrue(RoomPermissions.canRedactAsModerator(pl, creators, me))
    }

    @Test
    fun `a creator may kick and ban someone the levels place above them`() {
        // The creator is not in `users` at all, so without the creator rule this is 0 vs 100.
        val pl = levels(users = mapOf(them to 100L), usersDefault = 0L, kick = 100L, ban = 100L)

        assertTrue(RoomPermissions.canKick(pl, creators, me, them))
        assertTrue(RoomPermissions.canBan(pl, creators, me, them))
    }

    @Test
    fun `nobody may kick or ban a creator`() {
        val pl = levels(users = mapOf(them to 100L), usersDefault = 0L, kick = 0L, ban = 0L)

        // `them` is PL 100 against a creator who is not in `users` — and still cannot touch them.
        assertFalse(RoomPermissions.canKick(pl, creators, them, me))
        assertFalse(RoomPermissions.canBan(pl, creators, them, me))
    }

    @Test
    fun `a creator may not kick or ban another creator`() {
        // Equals at CREATOR_POWER_LEVEL, and the rule is strictly-above.
        val both = setOf(me, them)
        val pl = levels(kick = 0L, ban = 0L)

        assertFalse(RoomPermissions.canKick(pl, both, me, them))
        assertFalse(RoomPermissions.canBan(pl, both, me, them))
    }

    @Test
    fun `a creator is privileged even when power levels are unknown`() {
        // Their standing comes from m.room.create, so not having fetched m.room.power_levels
        // says nothing about them — unlike everyone else, who still fails closed.
        assertTrue(RoomPermissions.canKick(null, creators, me, them))
        assertTrue(RoomPermissions.canBan(null, creators, me, them))
        assertTrue(RoomPermissions.canRedactAsModerator(null, creators, me))

        assertFalse(RoomPermissions.canKick(null, creators, them, me))
        assertFalse(RoomPermissions.canRedactAsModerator(null, creators, them))
    }
}
