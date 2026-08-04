package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.threadRelatesTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [threadRelatesTo] — the single `m.relates_to` builder every thread send path now shares.
 *
 * Regression cover for GH #28, where the media path derived `is_falling_back` from whether a reply
 * *target resolved* rather than from whether the user actually replied. Since an anchor can
 * essentially always be resolved, that made the flag permanently `false`, and every attachment sent
 * into a thread claimed to be a deliberate reply to whichever message happened to be last.
 */
class ThreadRelationsTest {

    private val root = "\$threadRoot"
    private val target = "\$someMessage"

    @Suppress("UNCHECKED_CAST")
    private fun inReplyTo(map: Map<String, Any>): String? = (map["m.in_reply_to"] as? Map<String, Any>)?.get("event_id") as? String

    @Test
    fun `a thread anchor is marked as falling back`() {
        val relates = threadRelatesTo(root, target, isFallback = true)

        assertEquals("m.thread", relates["rel_type"])
        assertEquals(root, relates["event_id"])
        assertEquals(true, relates["is_falling_back"])
        assertEquals(target, inReplyTo(relates))
    }

    @Test
    fun `a genuine reply inside a thread is not marked as falling back`() {
        val relates = threadRelatesTo(root, target, isFallback = false)

        assertEquals(false, relates["is_falling_back"])
        assertEquals(target, inReplyTo(relates))
    }

    @Test
    fun `the flag is independent of whether a target was supplied`() {
        // The #28 bug in one assertion: these two differ only in intent, and the flag must follow
        // intent rather than the presence of an anchor.
        assertEquals(true, threadRelatesTo(root, target, isFallback = true)["is_falling_back"])
        assertEquals(false, threadRelatesTo(root, target, isFallback = false)["is_falling_back"])
    }

    @Test
    fun `an empty thread anchors on its own root`() {
        // Second #28 defect: m.in_reply_to used to be omitted entirely when nothing resolved, so the
        // fallback the flag refers to was missing and non-threaded clients had nothing to anchor to.
        val relates = threadRelatesTo(root, replyToEventId = null, isFallback = true)

        assertTrue(relates.containsKey("m.in_reply_to"))
        assertEquals(root, inReplyTo(relates))
    }

    @Test
    fun `a blank target is treated as absent`() {
        assertEquals(root, inReplyTo(threadRelatesTo(root, "", isFallback = true)))
        assertEquals(root, inReplyTo(threadRelatesTo(root, "   ", isFallback = true)))
    }

    @Test
    fun `m_in_reply_to is always present`() {
        // Non-threaded clients rely on it to place the message at all.
        listOf(target, null, "").forEach { candidate ->
            listOf(true, false).forEach { fallback ->
                assertTrue(
                    "missing m.in_reply_to for target=$candidate fallback=$fallback",
                    threadRelatesTo(root, candidate, fallback).containsKey("m.in_reply_to"),
                )
            }
        }
    }

    @Test
    fun `the relation carries exactly the four expected keys`() {
        // Extra keys would be sent verbatim to the homeserver.
        assertEquals(
            setOf("rel_type", "event_id", "is_falling_back", "m.in_reply_to"),
            threadRelatesTo(root, target, isFallback = true).keys,
        )
    }
}
