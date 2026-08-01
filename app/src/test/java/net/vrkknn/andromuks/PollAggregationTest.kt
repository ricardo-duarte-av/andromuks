package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.PollAnswer
import net.vrkknn.andromuks.utils.PollEndInfo
import net.vrkknn.andromuks.utils.PollStartInfo
import net.vrkknn.andromuks.utils.PollVote
import net.vrkknn.andromuks.utils.computePollResults
import net.vrkknn.andromuks.utils.isAuthorizedPollEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the MSC3381 vote aggregator.
 *
 * These deliberately exercise [computePollResults] directly rather than the JSON parsers: org.json
 * is stubbed (and throws) on the unit-test classpath, which is exactly why PollFunctions keeps
 * parsing and aggregation in separate functions.
 */
class PollAggregationTest {

    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"
    private val carol = "@carol:example.org"

    private fun poll(maxSelections: Int = 1, undisclosed: Boolean = false, creator: String = alice) = PollStartInfo(
        eventId = "\$poll",
        sender = creator,
        question = "Lunch?",
        answers = listOf(
            PollAnswer("a", "Pizza"),
            PollAnswer("b", "Sushi"),
            PollAnswer("c", "Salad"),
        ),
        maxSelections = maxSelections,
        isUndisclosed = undisclosed,
        isStablePrefix = false,
    )

    private fun vote(id: String, voter: String, answers: List<String>, ts: Long) = PollVote(
        eventId = id,
        voter = voter,
        answerIds = answers,
        timestamp = ts,
    )

    @Test
    fun `counts one vote per answer`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(
                vote("\$1", alice, listOf("a"), 100),
                vote("\$2", bob, listOf("a"), 200),
                vote("\$3", carol, listOf("b"), 300),
            ),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(2, results.countFor("a"))
        assertEquals(1, results.countFor("b"))
        assertEquals(0, results.countFor("c"))
        assertEquals(3, results.totalVoters)
        assertEquals(setOf("a"), results.myAnswerIds)
        assertEquals(2, results.topCount)
    }

    @Test
    fun `latest vote per user wins`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(
                vote("\$1", alice, listOf("a"), 100),
                vote("\$2", alice, listOf("b"), 500),
                vote("\$3", alice, listOf("c"), 300),
            ),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(0, results.countFor("a"))
        assertEquals(1, results.countFor("b"))
        assertEquals(0, results.countFor("c"))
        assertEquals(1, results.totalVoters)
        assertEquals(setOf("b"), results.myAnswerIds)
    }

    @Test
    fun `equal timestamps break the tie deterministically by event id`() {
        val votes = listOf(
            vote("\$aaa", alice, listOf("a"), 100),
            vote("\$zzz", alice, listOf("b"), 100),
        )
        val forward = computePollResults(poll(), votes, emptyList(), alice)
        val reversed = computePollResults(poll(), votes.reversed(), emptyList(), alice)

        // Higher event id wins, regardless of the order events were delivered in.
        assertEquals(setOf("b"), forward.myAnswerIds)
        assertEquals(forward.myAnswerIds, reversed.myAnswerIds)
    }

    @Test
    fun `selection is truncated to max_selections`() {
        val results = computePollResults(
            start = poll(maxSelections = 2),
            votes = listOf(vote("\$1", alice, listOf("a", "b", "c"), 100)),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(setOf("a", "b"), results.myAnswerIds)
        assertEquals(1, results.countFor("a"))
        assertEquals(1, results.countFor("b"))
        assertEquals(0, results.countFor("c"))
        // One voter, even though they picked two answers.
        assertEquals(1, results.totalVoters)
    }

    @Test
    fun `unknown answer ids are dropped`() {
        val results = computePollResults(
            start = poll(maxSelections = 2),
            votes = listOf(vote("\$1", alice, listOf("nope", "b"), 100)),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(setOf("b"), results.myAnswerIds)
        assertEquals(1, results.totalVoters)
    }

    @Test
    fun `a vote of only invalid ids is spoiled and counts for nothing`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(vote("\$1", alice, listOf("nope", "also-nope"), 100)),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(0, results.totalVoters)
        assertTrue(results.myAnswerIds.isEmpty())
    }

    @Test
    fun `empty answers withdraws the vote`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(
                vote("\$1", alice, listOf("a"), 100),
                vote("\$2", alice, emptyList(), 200),
            ),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(0, results.countFor("a"))
        assertEquals(0, results.totalVoters)
        assertTrue(results.myAnswerIds.isEmpty())
    }

    @Test
    fun `duplicate answer ids in one vote count once`() {
        val results = computePollResults(
            start = poll(maxSelections = 2),
            votes = listOf(vote("\$1", alice, listOf("a", "a"), 100)),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(1, results.countFor("a"))
        assertEquals(setOf("a"), results.myAnswerIds)
    }

    @Test
    fun `votes after the poll ends are ignored`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(
                vote("\$1", alice, listOf("a"), 100),
                vote("\$2", bob, listOf("b"), 900),
            ),
            ends = listOf(PollEndInfo("\$end", alice, timestamp = 500)),
            myUserId = alice,
        )

        assertEquals(1, results.countFor("a"))
        assertEquals(0, results.countFor("b"))
        assertEquals(1, results.totalVoters)
        assertTrue(results.isEnded)
        assertEquals(500L, results.endedAt)
    }

    @Test
    fun `an end from an unauthorized sender is ignored`() {
        val powerLevels = PowerLevelsInfo(users = mapOf(alice to 100), usersDefault = 0, redact = 50)
        val results = computePollResults(
            start = poll(creator = alice),
            votes = listOf(vote("\$1", bob, listOf("a"), 900)),
            ends = listOf(PollEndInfo("\$end", bob, timestamp = 500)),
            myUserId = alice,
            powerLevels = powerLevels,
        )

        assertFalse(results.isEnded)
        assertNull(results.endedAt)
        // The late vote counts, because the poll was never validly closed.
        assertEquals(1, results.countFor("a"))
    }

    @Test
    fun `a moderator with redact power may end someone else's poll`() {
        val powerLevels = PowerLevelsInfo(users = mapOf(bob to 50), usersDefault = 0, redact = 50)
        val end = PollEndInfo("\$end", bob, timestamp = 500)

        assertTrue(isAuthorizedPollEnd(end, poll(creator = alice), powerLevels))
        assertTrue(computePollResults(poll(creator = alice), emptyList(), listOf(end), alice, powerLevels).isEnded)
    }

    @Test
    fun `the earliest authorized end wins`() {
        val results = computePollResults(
            start = poll(),
            votes = emptyList(),
            ends = listOf(
                PollEndInfo("\$late", alice, timestamp = 900),
                PollEndInfo("\$early", alice, timestamp = 300),
            ),
            myUserId = alice,
        )

        assertEquals(300L, results.endedAt)
    }

    @Test
    fun `undisclosed polls hide results until they end`() {
        val votes = listOf(vote("\$1", alice, listOf("a"), 100))

        val open = computePollResults(poll(undisclosed = true), votes, emptyList(), alice)
        assertTrue(open.resultsHidden)

        val closed = computePollResults(
            start = poll(undisclosed = true),
            votes = votes,
            ends = listOf(PollEndInfo("\$end", alice, timestamp = 500)),
            myUserId = alice,
        )
        assertFalse(closed.resultsHidden)
    }

    @Test
    fun `disclosed polls never hide results`() {
        val results = computePollResults(poll(), emptyList(), emptyList(), alice)
        assertFalse(results.resultsHidden)
    }

    @Test
    fun `an optimistic local vote overrides the server state`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(vote("\$1", alice, listOf("a"), 100)),
            ends = emptyList(),
            myUserId = alice,
            localVote = vote("~local", alice, listOf("b"), 150),
        )

        assertTrue(results.hasLocalVote)
        assertEquals(setOf("b"), results.myAnswerIds)
        assertEquals(0, results.countFor("a"))
        assertEquals(1, results.countFor("b"))
    }

    @Test
    fun `a confirmed server vote beats a stale optimistic one`() {
        // The server echo is newer than the optimistic vote, so the optimism is spent — this is the
        // case that a naive "local always wins" overlay would get wrong.
        val results = computePollResults(
            start = poll(),
            votes = listOf(vote("\$1", alice, listOf("a"), 500)),
            ends = emptyList(),
            myUserId = alice,
            localVote = vote("~local", alice, listOf("b"), 100),
        )

        assertFalse(results.hasLocalVote)
        assertEquals(setOf("a"), results.myAnswerIds)
    }

    @Test
    fun `an optimistic withdrawal removes the local user from the counts`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(
                vote("\$1", alice, listOf("a"), 100),
                vote("\$2", bob, listOf("a"), 100),
            ),
            ends = emptyList(),
            myUserId = alice,
            localVote = vote("~local", alice, emptyList(), 150),
        )

        assertEquals(1, results.countFor("a"))
        assertEquals(1, results.totalVoters)
        assertTrue(results.myAnswerIds.isEmpty())
    }

    @Test
    fun `voter lists are exposed per answer in a stable order`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(
                vote("\$2", bob, listOf("a"), 200),
                vote("\$1", alice, listOf("a"), 100),
            ),
            ends = emptyList(),
            myUserId = alice,
        )

        assertEquals(listOf(alice, bob), results.votesByAnswer["a"]?.map { it.voter })
    }

    @Test
    fun `a poll with no votes reports empty state`() {
        val results = computePollResults(poll(), emptyList(), emptyList(), myUserId = alice)

        assertEquals(0, results.totalVoters)
        assertEquals(0, results.topCount)
        assertFalse(results.isEnded)
        assertTrue(results.myAnswerIds.isEmpty())
    }

    @Test
    fun `a null local user has no selection`() {
        val results = computePollResults(
            start = poll(),
            votes = listOf(vote("\$1", alice, listOf("a"), 100)),
            ends = emptyList(),
            myUserId = null,
        )

        assertTrue(results.myAnswerIds.isEmpty())
        assertEquals(1, results.countFor("a"))
    }
}
