package net.vrkknn.andromuks

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [RoomTimelineCache] — the per-room event store behind timeline restore.
 *
 * The load-bearing property is **bucket routing**. A room's cache is not one list but five:
 * `events` (real timeline rows), `redactionEvents`, `reactionEvents`, `pollEvents` and
 * `replyContextEvents`. Reactions and poll satellites must never reach `events`, or they render as
 * timeline rows; but they must still be *stored*, or a later redaction of one cannot be resolved and
 * the reaction can never be removed (see docs/REACTIONS.md — `findEventForReply` searching every
 * bucket is what makes reaction redaction work at all).
 *
 * Note that insertion order is not preserved: the cache re-sorts by (timelineRowid, timestamp,
 * eventId) on every add, and several callers depend on the result — [RoomTimelineCache.markAllStale]
 * freezes the tail as its freshness anchor, and the per-room trim drops from the head. Tests
 * therefore give events distinct rowids whenever their order matters.
 */
class RoomTimelineCacheTest {

    private val room = "!abc:example.org"
    private val otherRoom = "!gomuks2fjNJgXSZ-lZPoQWB_2za-KW_l2Hs6roxWKk4"
    private val alice = "@alice:example.org"

    private companion object {
        const val MSG = "m.room.message"
    }

    /** Parses a raw event JSON literal — the escape hatch for shapes the builders below don't cover. */
    private fun evRaw(json: String): TimelineEvent = TimelineEvent.fromJson(JSONObject(json.trimIndent()))

    /**
     * A plain timeline event. [rowid] doubles as `timeline_rowid` and [ts] as the timestamp, because
     * cache order is decided by (timelineRowid, timestamp, eventId) — see
     * `events are ordered by rowid, then timestamp, then event id`.
     */
    private fun ev(id: String, type: String = MSG, rowid: Long = 1L, ts: Long = 1000L): TimelineEvent = evRaw(
        """
        {"room_id": "$room", "event_id": "$id", "sender": "$alice", "type": "$type",
         "rowid": $rowid, "timeline_rowid": $rowid, "timestamp": $ts, "content": {"body": "$id"}}
        """,
    )

    /** Same, but belonging to [roomId] — for the cross-room contamination guard. */
    private fun evIn(roomId: String, id: String): TimelineEvent = evRaw(
        """
        {"room_id": "$roomId", "event_id": "$id", "sender": "$alice", "type": "$MSG",
         "rowid": 1, "timeline_rowid": 1, "timestamp": 1000, "content": {"body": "$id"}}
        """,
    )

    private fun reaction(id: String, target: String = "${'$'}msg"): TimelineEvent = evRaw(
        """
        {"room_id": "$room", "event_id": "$id", "sender": "$alice", "type": "m.reaction",
         "rowid": 1, "timeline_rowid": 1, "timestamp": 1000,
         "content": {"m.relates_to": {"rel_type": "m.annotation", "event_id": "$target", "key": "👍"}}}
        """,
    )

    private fun pollResponse(id: String, target: String = "${'$'}poll"): TimelineEvent = evRaw(
        """
        {"room_id": "$room", "event_id": "$id", "sender": "$alice", "type": "m.poll.response",
         "rowid": 1, "timeline_rowid": 1, "timestamp": 1000,
         "relation_type": "m.reference", "relates_to": "$target",
         "content": {"m.poll.response": {"answers": ["a"]}}}
        """,
    )

    private fun redaction(id: String, redacts: String, reason: String? = null): TimelineEvent = evRaw(
        """
        {"room_id": "$room", "event_id": "$id", "sender": "$alice", "type": "m.room.redaction",
         "rowid": 1, "timeline_rowid": 1, "timestamp": 5000,
         "content": {"redacts": "$redacts"${if (reason == null) "" else ", \"reason\": \"$reason\""}}}
        """,
    )

    /** `${'$'}msg` carrying additional top-level JSON — used for redaction/reaction-aggregate merges. */
    private fun msgWith(extra: String): TimelineEvent = evRaw(
        """
        {"room_id": "$room", "event_id": "${'$'}msg", "sender": "$alice", "type": "$MSG",
         "rowid": 1, "timeline_rowid": 1, "timestamp": 1000, "content": {"body": "msg"}, $extra}
        """,
    )

    private fun redactedReaction(id: String, redactedBy: String): TimelineEvent = evRaw(
        """
        {"room_id": "$room", "event_id": "$id", "sender": "$alice", "type": "m.reaction",
         "rowid": 1, "timeline_rowid": 1, "timestamp": 1000, "redacted_by": "$redactedBy",
         "content": {"m.relates_to": {"rel_type": "m.annotation", "event_id": "${'$'}msg", "key": "👍"}}}
        """,
    )

    @Before
    fun setUp() = reset()

    @After
    fun tearDown() = reset()

    private fun reset() {
        RoomTimelineCache.getOpenedRooms().forEach { RoomTimelineCache.removeOpenedRoom(it) }
        RoomTimelineCache.clearAllCaches()
    }

    // ---------------------------------------------------------------- bucket routing

    @Test
    fun `reactions and poll satellites never enter the timeline bucket`() {
        RoomTimelineCache.mergePaginatedEvents(
            room,
            listOf(ev("${'$'}msg"), reaction("${'$'}react"), pollResponse("${'$'}vote"), redaction("${'$'}red", "${'$'}msg")),
        )

        assertEquals(listOf("${'$'}msg"), RoomTimelineCache.getCachedEvents(room)?.map { it.eventId })
        assertEquals(listOf("${'$'}react"), RoomTimelineCache.getCachedReactionEvents(room).map { it.eventId })
        assertEquals(listOf("${'$'}vote"), RoomTimelineCache.getCachedPollEvents(room).map { it.eventId })
        assertEquals(listOf("${'$'}red"), RoomTimelineCache.getRedactionEvents(room).map { it.eventId })
    }

    @Test
    fun `a poll start is a real timeline row`() {
        // Only responses and ends are satellites; the start renders as a bubble.
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}poll", type = "m.poll.start")))

        assertEquals(listOf("${'$'}poll"), RoomTimelineCache.getCachedEvents(room)?.map { it.eventId })
        assertTrue(RoomTimelineCache.getCachedPollEvents(room).isEmpty())
    }

    @Test
    fun `an E2EE-wrapped reaction is routed to the reaction bucket`() {
        // Routing on the raw type would drop this into `events` and render it as a message.
        val wrapped = TimelineEvent.fromJson(
            JSONObject(
                """
                {"room_id": "$room", "event_id": "${'$'}enc", "sender": "$alice", "type": "m.room.encrypted",
                 "decrypted_type": "m.reaction", "rowid": 1, "timeline_rowid": 1, "timestamp": 1000,
                 "content": {"ciphertext": "AwgA…"},
                 "decrypted": {"m.relates_to": {"rel_type": "m.annotation", "event_id": "${'$'}msg", "key": "👍"}}}
                """.trimIndent(),
            ),
        )

        RoomTimelineCache.mergePaginatedEvents(room, listOf(wrapped))

        assertEquals(listOf("${'$'}enc"), RoomTimelineCache.getCachedReactionEvents(room).map { it.eventId })
        assertNull(RoomTimelineCache.getCachedEvents(room))
    }

    @Test
    fun `getCachedEventsForTimeline adds redactions but not reactions or polls`() {
        // Redactions are needed so buildTimelineFromChain can stamp redactedBy onto their targets.
        RoomTimelineCache.mergePaginatedEvents(
            room,
            listOf(ev("${'$'}msg"), redaction("${'$'}red", "${'$'}msg"), reaction("${'$'}react"), pollResponse("${'$'}vote")),
        )

        val forTimeline = RoomTimelineCache.getCachedEventsForTimeline(room).map { it.eventId }

        assertEquals(setOf("${'$'}msg", "${'$'}red"), forTimeline.toSet())
    }

    @Test
    fun `an empty cache reads as null so the caller paginates`() {
        assertNull(RoomTimelineCache.getCachedEvents(room))
        assertTrue(RoomTimelineCache.getCachedEventsForTimeline(room).isEmpty())
        assertEquals(0, RoomTimelineCache.getCachedEventCount(room))
    }

    @Test
    fun `events are ordered by rowid, then timestamp, then event id`() {
        // Insertion order is NOT preserved. Several callers depend on this ordering — most visibly
        // markAllStale, which freezes the tail of `events` as the freshness anchor, and
        // trimRoomToMaxEvents, which drops from the head.
        RoomTimelineCache.mergePaginatedEvents(
            room,
            listOf(
                ev("${'$'}c", rowid = 3, ts = 1000),
                ev("${'$'}a", rowid = 1, ts = 1000),
                ev("${'$'}b", rowid = 2, ts = 1000),
            ),
        )
        assertEquals(listOf("${'$'}a", "${'$'}b", "${'$'}c"), RoomTimelineCache.getCachedEvents(room)?.map { it.eventId })

        reset()

        // Equal rowids fall back to timestamp, then to event id as a deterministic last resort.
        RoomTimelineCache.mergePaginatedEvents(
            room,
            listOf(
                ev("${'$'}z", rowid = 1, ts = 3000),
                ev("${'$'}y", rowid = 1, ts = 1000),
                ev("${'$'}x", rowid = 1, ts = 1000),
            ),
        )
        assertEquals(listOf("${'$'}x", "${'$'}y", "${'$'}z"), RoomTimelineCache.getCachedEvents(room)?.map { it.eventId })
    }

    // ---------------------------------------------------------------- findEventForReply

    @Test
    fun `findEventForReply searches every bucket and reports which answered`() {
        // This is what makes reaction redaction work: reactions are never in `events`, so the
        // m.room.redaction handler resolves its target through here. A lookup that only searched
        // `events` would silently skip the removal and the reaction would render forever.
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg"), reaction("${'$'}react"), pollResponse("${'$'}vote")))
        RoomTimelineCache.addReplyContextEvents(room, listOf(ev("${'$'}ctx")))

        assertEquals(
            RoomTimelineCache.ReplySource.TIMELINE,
            RoomTimelineCache.findEventForReplyWithSource(room, "${'$'}msg")?.second,
        )
        assertEquals(
            RoomTimelineCache.ReplySource.REPLY_CONTEXT,
            RoomTimelineCache.findEventForReplyWithSource(room, "${'$'}ctx")?.second,
        )
        assertEquals(
            RoomTimelineCache.ReplySource.REACTION,
            RoomTimelineCache.findEventForReplyWithSource(room, "${'$'}react")?.second,
        )
        assertEquals(
            RoomTimelineCache.ReplySource.POLL,
            RoomTimelineCache.findEventForReplyWithSource(room, "${'$'}vote")?.second,
        )
    }

    @Test
    fun `findEventForReply misses cleanly and does not cross rooms`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))

        assertNull(RoomTimelineCache.findEventForReply(room, "${'$'}nope"))
        assertNull(RoomTimelineCache.findEventForReply(otherRoom, "${'$'}msg"))
        assertNotNull(RoomTimelineCache.findEventForReply(room, "${'$'}msg"))
    }

    // ---------------------------------------------------------------- upsert semantics

    @Test
    fun `re-sending a reaction replaces it rather than duplicating`() {
        // The backend re-sends the same event_id with updated state (e.g. redacted_by added), and
        // the room-open replay must see the current server-side state, not the first copy.
        RoomTimelineCache.mergePaginatedEvents(room, listOf(reaction("${'$'}react")))
        RoomTimelineCache.mergePaginatedEvents(room, listOf(redactedReaction("${'$'}react", "${'$'}red")))

        val stored = RoomTimelineCache.getCachedReactionEvents(room)
        assertEquals(1, stored.size)
        assertEquals("${'$'}red", stored.single().redactedBy)
    }

    @Test
    fun `re-sending a poll satellite replaces it rather than duplicating`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(pollResponse("${'$'}vote")))
        RoomTimelineCache.mergePaginatedEvents(room, listOf(pollResponse("${'$'}vote")))

        assertEquals(1, RoomTimelineCache.getCachedPollEvents(room).size)
    }

    @Test
    fun `a repeated timeline event is deduplicated`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))

        assertEquals(1, RoomTimelineCache.getCachedEventCount(room))
    }

    @Test
    fun `re-delivery merges aggregated reactions onto the existing event`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))
        RoomTimelineCache.mergePaginatedEvents(room, listOf(msgWith("\"reactions\": {\"👍\": 3}")))

        val stored = RoomTimelineCache.getCachedEvents(room)?.single()
        assertEquals(3, stored?.aggregatedReactions?.optInt("👍"))
    }

    @Test
    fun `re-delivery merges redactedBy onto the existing event`() {
        // sync_complete sends the original event carrying redacted_by; without this merge the
        // redaction never renders.
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))
        RoomTimelineCache.mergePaginatedEvents(room, listOf(msgWith("\"redacted_by\": \"${'$'}red\"")))

        assertEquals("${'$'}red", RoomTimelineCache.getCachedEvents(room)?.single()?.redactedBy)
    }

    // ---------------------------------------------------------------- redaction enrichment

    @Test
    fun `a redaction earlier in the batch enriches the event it redacts`() {
        // Stamped at insert so a cache restore can render "Removed by X for Y" with no second lookup.
        RoomTimelineCache.mergePaginatedEvents(
            room,
            listOf(redaction("${'$'}red", "${'$'}msg", reason = "spam"), msgWith("\"redacted_by\": \"${'$'}red\"")),
        )

        val stored = RoomTimelineCache.getCachedEvents(room)?.single()
        assertEquals(alice, stored?.redactionSender)
        assertEquals("spam", stored?.redactionReason)
        assertEquals(5000L, stored?.redactionTimestamp)
    }

    @Test
    fun `redacted_because is the fallback when no redaction event is cached`() {
        // Matrix spec shape, used by paginate responses that carry no separate redaction event.
        val redacted = msgWith(
            """"redacted_by": "${'$'}red",
               "unsigned": {"redacted_because": {"sender": "@mod:example.org", "origin_server_ts": 7000,
                                                 "content": {"reason": "off-topic"}}}""",
        )

        RoomTimelineCache.mergePaginatedEvents(room, listOf(redacted))

        val stored = RoomTimelineCache.getCachedEvents(room)?.single()
        assertEquals("@mod:example.org", stored?.redactionSender)
        assertEquals("off-topic", stored?.redactionReason)
        assertEquals(7000L, stored?.redactionTimestamp)
    }

    // ---------------------------------------------------------------- safety filters

    @Test
    fun `events belonging to another room are dropped`() {
        // Guards against cross-room cache contamination if a caller passes the wrong roomId.
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}mine"), evIn(otherRoom, "${'$'}theirs")))

        assertEquals(listOf("${'$'}mine"), RoomTimelineCache.getCachedEvents(room)?.map { it.eventId })
        assertNull(RoomTimelineCache.getCachedEvents(otherRoom))
    }

    @Test
    fun `state-only member events are dropped but paginated ones are kept`() {
        // rowid 0 (invalid) and -1 (state-only profile hint) are not timeline rows. Values below -1
        // are legitimate paginated events and must survive.
        RoomTimelineCache.mergePaginatedEvents(
            room,
            listOf(
                ev("${'$'}m0", type = "m.room.member", rowid = 0),
                ev("${'$'}mNeg1", type = "m.room.member", rowid = -1),
                ev("${'$'}mNeg2", type = "m.room.member", rowid = -2),
                ev("${'$'}mPos", type = "m.room.member", rowid = 5),
            ),
        )

        assertEquals(setOf("${'$'}mNeg2", "${'$'}mPos"), RoomTimelineCache.getCachedEvents(room)?.map { it.eventId }?.toSet())
    }

    @Test
    fun `blank event ids are ignored`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("")))

        assertNull(RoomTimelineCache.getCachedEvents(room))
    }

    // ---------------------------------------------------------------- staleness epochs

    @Test
    fun `markAllStale flags cached rooms and freezes the youngest event as the anchor`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}older", rowid = 1), ev("${'$'}newest", rowid = 2)))

        val epoch = RoomTimelineCache.markAllStale()

        assertTrue(RoomTimelineCache.isMightBeStale(room))
        assertEquals(epoch, RoomTimelineCache.staleEpochFor(room))
        assertEquals("${'$'}newest", RoomTimelineCache.staleAnchorFor(room))
    }

    @Test
    fun `an uncached room is never stale`() {
        RoomTimelineCache.markAllStale()

        // Nothing cached means nothing to distrust — the open path paginates it anyway.
        assertFalse(RoomTimelineCache.isMightBeStale(room))
        assertEquals(0, RoomTimelineCache.staleEpochFor(room))
        assertNull(RoomTimelineCache.staleAnchorFor(room))
    }

    @Test
    fun `a probe from a superseded epoch cannot mark the room fresh`() {
        // The race guard: an in-flight freshness probe must not clear staleness that a *later*
        // WebSocket drop introduced, or a room with a real gap would be trusted.
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))
        val firstEpoch = RoomTimelineCache.markAllStale()
        val secondEpoch = RoomTimelineCache.markAllStale()

        assertFalse("stale probe from the old epoch must be refused", RoomTimelineCache.clearStaleIfEpoch(room, firstEpoch))
        assertTrue(RoomTimelineCache.isMightBeStale(room))

        assertTrue(RoomTimelineCache.clearStaleIfEpoch(room, secondEpoch))
        assertFalse(RoomTimelineCache.isMightBeStale(room))
    }

    @Test
    fun `clearing staleness twice reports false the second time`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))
        val epoch = RoomTimelineCache.markAllStale()

        assertTrue(RoomTimelineCache.clearStaleIfEpoch(room, epoch))
        assertFalse(RoomTimelineCache.clearStaleIfEpoch(room, epoch))
    }

    // ---------------------------------------------------------------- opened rooms

    @Test
    fun `opened rooms are tracked and released`() {
        // Membership here is the exemption flag for every memory bound in this class, so a leak
        // makes a room permanently un-evictable and un-trimmable.
        assertFalse(RoomTimelineCache.isRoomOpened(room))

        RoomTimelineCache.addOpenedRoom(room)
        assertTrue(RoomTimelineCache.isRoomOpened(room))
        assertEquals(setOf(room), RoomTimelineCache.getOpenedRooms())

        RoomTimelineCache.removeOpenedRoom(room)
        assertFalse(RoomTimelineCache.isRoomOpened(room))
    }

    @Test
    fun `reading a cache does not mark the room opened`() {
        // getCachedEvents used to call addOpenedRoom, which permanently exempted every room a
        // background sync touched from eviction.
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg")))

        RoomTimelineCache.getCachedEvents(room)

        assertFalse(RoomTimelineCache.isRoomOpened(room))
    }

    // ---------------------------------------------------------------- clearing

    @Test
    fun `clearRoomCache drops every bucket for that room only`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}msg"), reaction("${'$'}react"), pollResponse("${'$'}vote")))
        RoomTimelineCache.mergePaginatedEvents(otherRoom, listOf(evIn(otherRoom, "${'$'}other")))

        RoomTimelineCache.clearRoomCache(room)

        assertNull(RoomTimelineCache.getCachedEvents(room))
        assertTrue(RoomTimelineCache.getCachedReactionEvents(room).isEmpty())
        assertTrue(RoomTimelineCache.getCachedPollEvents(room).isEmpty())
        assertEquals(listOf("${'$'}other"), RoomTimelineCache.getCachedEvents(otherRoom)?.map { it.eventId })
    }

    @Test
    fun `event ids are exposed for the whole room`() {
        RoomTimelineCache.mergePaginatedEvents(room, listOf(ev("${'$'}a"), ev("${'$'}b")))

        assertEquals(setOf("${'$'}a", "${'$'}b"), RoomTimelineCache.getCachedEventIds(room))
    }
}
