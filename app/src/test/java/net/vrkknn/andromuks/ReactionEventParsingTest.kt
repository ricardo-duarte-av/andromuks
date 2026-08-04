package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.extractReactionEventFromTimeline
import net.vrkknn.andromuks.utils.isReactionEvent
import net.vrkknn.andromuks.utils.reactionContent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser-level tests for reaction detection and extraction, covering the E2EE-wrapped shape.
 *
 * These exist as much to pin the *build setup* as the behaviour: they parse real JSON through
 * [TimelineEvent.fromJson], which was impossible until a real `org.json` was put on the unit-test
 * classpath (the android.jar stub throws — or, with `isReturnDefaultValues`, silently returns null).
 * If someone drops that dependency, these fail loudly rather than passing vacuously — note the
 * assertions on parsed *values*, not just on null-ness.
 *
 * The bug they guard: every ingest site used to test the raw `event.type`, so a reaction wrapped as
 * `m.room.encrypted` with the real type in `decrypted_type` was not recognised as a reaction at all.
 * It fell through to the message branch, became a timeline row, and was cached as a message.
 */
class ReactionEventParsingTest {

    private val alice = "@alice:example.org"
    private val room = "!room:example.org"
    private val target = "\$target-event"

    private fun plaintextReaction(emoji: String = "👍") = JSONObject(
        """
        {
          "room_id": "$room",
          "event_id": "${'$'}reaction-1",
          "sender": "$alice",
          "type": "m.reaction",
          "timestamp": 1700000000000,
          "content": {
            "m.relates_to": { "rel_type": "m.annotation", "event_id": "$target", "key": "$emoji" }
          }
        }
        """.trimIndent(),
    )

    /** How an encrypted room delivers the same reaction: ciphertext in content, real payload in decrypted. */
    private fun wrappedReaction(emoji: String = "👍") = JSONObject(
        """
        {
          "room_id": "$room",
          "event_id": "${'$'}reaction-2",
          "sender": "$alice",
          "type": "m.room.encrypted",
          "decrypted_type": "m.reaction",
          "timestamp": 1700000000001,
          "content": { "algorithm": "m.megolm.v1.aes-sha2", "ciphertext": "AwgAEnB…" },
          "decrypted": {
            "m.relates_to": { "rel_type": "m.annotation", "event_id": "$target", "key": "$emoji" }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `org_json is the real implementation, not the android stub`() {
        // Guards the build setup these tests depend on: the stub returns null/0 for everything.
        val parsed = JSONObject("""{"a": 1, "b": {"c": "x"}}""")
        assertEquals(1, parsed.optInt("a"))
        assertEquals("x", parsed.optJSONObject("b")?.optString("c"))
    }

    @Test
    fun `plaintext reaction is recognised`() {
        val event = TimelineEvent.fromJson(plaintextReaction())

        assertEquals("m.reaction", event.type)
        assertTrue(isReactionEvent(event))
    }

    @Test
    fun `E2EE-wrapped reaction is recognised`() {
        val event = TimelineEvent.fromJson(wrappedReaction())

        assertEquals("m.room.encrypted", event.type)
        assertEquals("m.reaction", event.decryptedType)
        assertTrue("wrapped reaction must not be treated as a message", isReactionEvent(event))
    }

    @Test
    fun `an ordinary encrypted message is not a reaction`() {
        val event = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "room_id": "$room", "event_id": "${'$'}msg", "sender": "$alice",
                  "type": "m.room.encrypted", "decrypted_type": "m.room.message",
                  "content": { "ciphertext": "AwgAEnB…" },
                  "decrypted": { "msgtype": "m.text", "body": "hi" }
                }
                """.trimIndent(),
            ),
        )

        assertFalse(isReactionEvent(event))
    }

    @Test
    fun `reactionContent reads decrypted for wrapped events and content otherwise`() {
        assertNotNull(reactionContent(TimelineEvent.fromJson(plaintextReaction()))?.optJSONObject("m.relates_to"))

        val wrapped = reactionContent(TimelineEvent.fromJson(wrappedReaction()))
        // The ciphertext content has no m.relates_to; reading it instead of `decrypted` is the bug.
        assertNotNull(wrapped?.optJSONObject("m.relates_to"))
        assertEquals(target, wrapped?.optJSONObject("m.relates_to")?.optString("event_id"))
    }

    @Test
    fun `extract yields the same reaction from both shapes`() {
        val fromPlain = extractReactionEventFromTimeline(TimelineEvent.fromJson(plaintextReaction()))
        val fromWrapped = extractReactionEventFromTimeline(TimelineEvent.fromJson(wrappedReaction()))

        assertNotNull(fromPlain)
        assertNotNull(fromWrapped)
        assertEquals(target, fromPlain!!.relatesToEventId)
        assertEquals(target, fromWrapped!!.relatesToEventId)
        assertEquals("👍", fromWrapped.emoji)
        assertEquals(alice, fromWrapped.sender)
    }

    @Test
    fun `a non-annotation relation is not a reaction payload`() {
        // m.replace (an edit) shares the m.relates_to shape but must not become a reaction.
        val event = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "room_id": "$room", "event_id": "${'$'}edit", "sender": "$alice",
                  "type": "m.reaction",
                  "content": { "m.relates_to": { "rel_type": "m.replace", "event_id": "$target", "key": "👍" } }
                }
                """.trimIndent(),
            ),
        )

        assertNull(extractReactionEventFromTimeline(event))
    }

    @Test
    fun `a reaction with no relation extracts to null rather than throwing`() {
        val event = TimelineEvent.fromJson(
            JSONObject("""{"room_id": "$room", "event_id": "${'$'}x", "sender": "$alice", "type": "m.reaction", "content": {}}"""),
        )

        assertNull(extractReactionEventFromTimeline(event))
    }
}
