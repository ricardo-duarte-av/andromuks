package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.POLL_START_UNSTABLE
import net.vrkknn.andromuks.utils.isPollSatelliteEvent
import net.vrkknn.andromuks.utils.parsePollEnd
import net.vrkknn.andromuks.utils.parsePollResponse
import net.vrkknn.andromuks.utils.parsePollStart
import net.vrkknn.andromuks.utils.pollEventType
import net.vrkknn.andromuks.utils.pollRelatesToEventId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the MSC3381 poll parsers.
 *
 * [PollAggregationTest] covers `computePollResults`, the half of PollFunctions that was reachable
 * before a real `org.json` was on the unit-test classpath — its header notes the parsers were
 * deliberately left untested for exactly that reason. This is the other half.
 *
 * Both the stable (`m.poll.*`) and unstable (`org.matrix.msc3381.poll.*`) prefixes are in the wild,
 * for the event type *and* the content key, so most cases are exercised in both flavours.
 */
class PollParsingTest {

    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"
    private val room = "!room:example.org"

    private fun event(body: String) = TimelineEvent.fromJson(JSONObject(body.trimIndent()))

    private fun pollStart(inner: String, type: String = POLL_START_UNSTABLE, contentKey: String = POLL_START_UNSTABLE) = event(
        """
        {"event_id": "${'$'}poll", "sender": "$alice", "room_id": "$room", "type": "$type",
         "content": {"$contentKey": $inner}}
        """,
    )

    private val threeAnswers = """
        {"question": {"org.matrix.msc1767.text": "Lunch?"},
         "answers": [{"id": "a", "org.matrix.msc1767.text": "Pizza"},
                     {"id": "b", "org.matrix.msc1767.text": "Sushi"},
                     {"id": "c", "org.matrix.msc1767.text": "Salad"}]}
    """.trimIndent()

    // ---------------------------------------------------------------- type resolution

    @Test
    fun `pollEventType accepts both prefixes and looks through decryption`() {
        assertEquals("m.poll.start", pollEventType(event("""{"type": "m.poll.start"}""")))
        assertEquals(
            "org.matrix.msc3381.poll.response",
            pollEventType(event("""{"type": "org.matrix.msc3381.poll.response"}""")),
        )
        assertEquals(
            "m.poll.end",
            pollEventType(event("""{"type": "m.room.encrypted", "decrypted_type": "m.poll.end"}""")),
        )
        assertNull(pollEventType(event("""{"type": "m.room.message"}""")))
    }

    @Test
    fun `only responses and ends are satellite events`() {
        assertFalse(isPollSatelliteEvent(event("""{"type": "m.poll.start"}""")))
        assertTrue(isPollSatelliteEvent(event("""{"type": "m.poll.response"}""")))
        assertTrue(isPollSatelliteEvent(event("""{"type": "m.poll.end"}""")))
        assertFalse(isPollSatelliteEvent(event("""{"type": "m.room.message"}""")))
    }

    // ---------------------------------------------------------------- parsePollStart

    @Test
    fun `parses an unstable-prefix poll start`() {
        val start = parsePollStart(pollStart(threeAnswers))

        assertNotNull(start)
        assertEquals("Lunch?", start!!.question)
        assertEquals(listOf("a", "b", "c"), start.answers.map { it.id })
        assertEquals(listOf("Pizza", "Sushi", "Salad"), start.answers.map { it.text })
        assertFalse(start.isStablePrefix)
    }

    @Test
    fun `parses a stable-prefix poll start and records the flavour`() {
        val start = parsePollStart(pollStart(threeAnswers, type = "m.poll.start", contentKey = "m.poll.start"))

        assertNotNull(start)
        // Responses must echo the flavour the start used, so this is not cosmetic.
        assertTrue(start!!.isStablePrefix)
    }

    @Test
    fun `a stable content key under an unstable type still parses`() {
        // pollContent tries both keys on both content and decrypted; senders do mix them.
        val start = parsePollStart(pollStart(threeAnswers, contentKey = "m.poll.start"))

        assertNotNull(start)
        assertEquals(3, start!!.answers.size)
    }

    @Test
    fun `parses a poll start from decrypted content`() {
        val e = event(
            """
            {"event_id": "${'$'}poll", "sender": "$alice", "type": "m.room.encrypted",
             "decrypted_type": "m.poll.start", "content": {"ciphertext": "AwgA…"},
             "decrypted": {"m.poll.start": $threeAnswers}}
            """,
        )

        assertEquals(3, parsePollStart(e)?.answers?.size)
    }

    @Test
    fun `answer text falls back through the extensible-text forms`() {
        val start = parsePollStart(
            pollStart(
                """
                {"answers": [{"id": "a", "m.text": "plain string"},
                             {"id": "b", "m.text": [{"mimetype": "text/html", "body": "<b>rich</b>"},
                                                    {"mimetype": "text/plain", "body": "rich"}]},
                             {"id": "c", "body": "legacy body"},
                             {"id": "d"}]}
                """.trimIndent(),
            ),
        )

        assertNotNull(start)
        assertEquals("plain string", start!!.answers[0].text)
        // The array form takes the first entry with a non-blank body, whatever its mimetype.
        assertEquals("<b>rich</b>", start.answers[1].text)
        assertEquals("legacy body", start.answers[2].text)
        // No text at all falls back to the id, so an answer is never rendered blank.
        assertEquals("d", start.answers[3].text)
    }

    @Test
    fun `question falls back to outer content then to a placeholder`() {
        val fromOuter = event(
            """
            {"event_id": "${'$'}p", "sender": "$alice", "type": "m.poll.start",
             "content": {"body": "Lunch fallback",
                         "m.poll.start": {"answers": [{"id": "a", "body": "Pizza"}]}}}
            """,
        )
        assertEquals("Lunch fallback", parsePollStart(fromOuter)?.question)

        val noQuestion = pollStart("""{"answers": [{"id": "a", "body": "Pizza"}]}""")
        assertEquals("Poll", parsePollStart(noQuestion)?.question)
    }

    @Test
    fun `duplicate answer ids keep the first occurrence only`() {
        // Duplicates would make a vote ambiguous — two answers would claim the same id.
        val start = parsePollStart(
            pollStart(
                """{"answers": [{"id": "a", "body": "First"}, {"id": "a", "body": "Second"},
                                        {"id": "b", "body": "Other"}]}""",
            ),
        )

        assertEquals(listOf("a", "b"), start?.answers?.map { it.id })
        assertEquals("First", start?.answers?.first()?.text)
    }

    @Test
    fun `answers are capped at the MSC3381 limit of 20`() {
        val many = (1..25).joinToString(",") { """{"id": "a$it", "body": "Answer $it"}""" }
        val start = parsePollStart(pollStart("""{"answers": [$many]}"""))

        assertEquals(20, start?.answers?.size)
    }

    @Test
    fun `max_selections defaults to 1 and is clamped to the answer count`() {
        assertEquals(1, parsePollStart(pollStart(threeAnswers))?.maxSelections)

        val two = pollStart(threeAnswers.dropLast(1) + """, "max_selections": 2}""")
        assertEquals(2, parsePollStart(two)?.maxSelections)

        // More selections than answers is meaningless; clamp so the UI can't offer phantom picks.
        val tooMany = pollStart(threeAnswers.dropLast(1) + """, "max_selections": 99}""")
        assertEquals(3, parsePollStart(tooMany)?.maxSelections)

        // Zero or negative would disable voting entirely.
        val zero = pollStart(threeAnswers.dropLast(1) + """, "max_selections": 0}""")
        assertEquals(1, parsePollStart(zero)?.maxSelections)
    }

    @Test
    fun `undisclosed kind is recognised in both prefixes`() {
        val unstable = pollStart(threeAnswers.dropLast(1) + """, "kind": "org.matrix.msc3381.poll.undisclosed"}""")
        assertTrue(parsePollStart(unstable)!!.isUndisclosed)

        val stable = pollStart(threeAnswers.dropLast(1) + """, "kind": "m.poll.undisclosed"}""")
        assertTrue(parsePollStart(stable)!!.isUndisclosed)

        val disclosed = pollStart(threeAnswers.dropLast(1) + """, "kind": "m.poll.disclosed"}""")
        assertFalse(parsePollStart(disclosed)!!.isUndisclosed)

        // Absent kind means disclosed — never hide results by accident.
        assertFalse(parsePollStart(pollStart(threeAnswers))!!.isUndisclosed)
    }

    @Test
    fun `a malformed poll start parses to null rather than a broken poll`() {
        assertNull("wrong event type", parsePollStart(event("""{"type": "m.room.message", "content": {}}""")))
        assertNull("no poll content", parsePollStart(event("""{"type": "m.poll.start", "content": {}}""")))
        assertNull("no answers array", parsePollStart(pollStart("""{"question": {"body": "?"}}""")))
        assertNull("empty answers", parsePollStart(pollStart("""{"answers": []}""")))
        assertNull("answers with no ids", parsePollStart(pollStart("""{"answers": [{"body": "no id"}]}""")))
    }

    // ---------------------------------------------------------------- parsePollResponse

    private fun response(
        answers: String = """["a"]""",
        sender: String = bob,
        extra: String = "",
        relation: String = """"relation_type": "m.reference", "relates_to": "${'$'}poll",""",
    ) = event(
        """
        {"event_id": "${'$'}vote", "sender": "$sender", "type": "m.poll.response",
         "timestamp": 1700000000000, $relation $extra
         "content": {"m.poll.response": {"answers": $answers}}}
        """,
    )

    @Test
    fun `parses a vote`() {
        val parsed = parsePollResponse(response())

        assertNotNull(parsed)
        val (pollId, vote) = parsed!!
        assertEquals("${'$'}poll", pollId)
        assertEquals(bob, vote.voter)
        assertEquals(listOf("a"), vote.answerIds)
        assertEquals(1700000000000L, vote.timestamp)
    }

    @Test
    fun `an empty answers array is a withdrawal, not a parse failure`() {
        // Tapping a selected option sends this; dropping it would leave the vote stuck on screen.
        val parsed = parsePollResponse(response(answers = "[]"))

        assertNotNull(parsed)
        assertTrue(parsed!!.second.answerIds.isEmpty())
    }

    @Test
    fun `a redacted vote does not parse`() {
        assertNull(parsePollResponse(response(extra = """"redacted_by": "${'$'}red",""")))
    }

    @Test
    fun `a response with no answers array does not parse`() {
        val e = event(
            """
            {"event_id": "${'$'}v", "sender": "$bob", "type": "m.poll.response",
             "relation_type": "m.reference", "relates_to": "${'$'}poll",
             "content": {"m.poll.response": {}}}
            """,
        )

        assertNull(parsePollResponse(e))
    }

    @Test
    fun `a response with no relation does not parse`() {
        assertNull(parsePollResponse(response(relation = "")))
    }

    @Test
    fun `blank answer ids are dropped`() {
        val parsed = parsePollResponse(response(answers = """["a", "", "b"]"""))

        assertEquals(listOf("a", "b"), parsed?.second?.answerIds)
    }

    // ---------------------------------------------------------------- parsePollEnd

    @Test
    fun `parses a poll end`() {
        val e = event(
            """
            {"event_id": "${'$'}end", "sender": "$alice", "type": "m.poll.end", "timestamp": 1700000009999,
             "relation_type": "m.reference", "relates_to": "${'$'}poll", "content": {}}
            """,
        )

        val parsed = parsePollEnd(e)
        assertNotNull(parsed)
        assertEquals("${'$'}poll", parsed!!.first)
        assertEquals(alice, parsed.second.sender)
        assertEquals(1700000009999L, parsed.second.timestamp)
    }

    @Test
    fun `a redacted end does not parse`() {
        val e = event(
            """
            {"event_id": "${'$'}end", "sender": "$alice", "type": "m.poll.end", "redacted_by": "${'$'}red",
             "relation_type": "m.reference", "relates_to": "${'$'}poll", "content": {}}
            """,
        )

        assertNull(parsePollEnd(e))
    }

    // ---------------------------------------------------------------- pollRelatesToEventId

    @Test
    fun `relation resolves from the top level, from content, and from a bare relates_to`() {
        assertEquals(
            "${'$'}poll",
            pollRelatesToEventId(event("""{"relation_type": "m.reference", "relates_to": "${'$'}poll"}""")),
        )

        assertEquals(
            "${'$'}poll",
            pollRelatesToEventId(
                event("""{"content": {"m.relates_to": {"rel_type": "m.reference", "event_id": "${'$'}poll"}}}"""),
            ),
        )

        // Some senders omit rel_type on the nested object but gomuks still resolves relates_to.
        assertEquals("${'$'}poll", pollRelatesToEventId(event("""{"relates_to": "${'$'}poll"}""")))
    }

    @Test
    fun `relation resolves from decrypted content`() {
        val e = event(
            """
            {"type": "m.room.encrypted", "decrypted_type": "m.poll.response",
             "content": {"ciphertext": "AwgA…"},
             "decrypted": {"m.relates_to": {"rel_type": "m.reference", "event_id": "${'$'}poll"}}}
            """,
        )

        assertEquals("${'$'}poll", pollRelatesToEventId(e))
    }

    @Test
    fun `an unrelated event has no poll relation`() {
        assertNull(pollRelatesToEventId(event("""{"type": "m.room.message", "content": {"body": "hi"}}""")))
    }
}
