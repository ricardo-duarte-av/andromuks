package net.vrkknn.andromuks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TimelineEvent.fromJson] and the payload accessors built on it.
 *
 * This is the codebase's central parser — every feature downstream reads what it produces — and it
 * carries most of the plaintext-vs-E2EE branching, which is where bugs in this area keep appearing.
 * It could not be tested at all until a real `org.json` was put on the unit-test classpath; see the
 * `testOptions` comment in `app/build.gradle.kts`.
 */
class TimelineEventParsingTest {

    private val alice = "@alice:example.org"
    private val room = "!room:example.org"

    private fun event(body: String) = TimelineEvent.fromJson(JSONObject(body.trimIndent()))

    // ---------------------------------------------------------------- scalar fields

    @Test
    fun `timestamp prefers timestamp over origin_server_ts`() {
        val e = event("""{"timestamp": 111, "origin_server_ts": 222}""")

        assertEquals(111L, e.timestamp)
    }

    @Test
    fun `timestamp falls back to origin_server_ts when absent or zero`() {
        // The DB stores origin_server_ts while the sync frame uses timestamp; both must work.
        assertEquals(222L, event("""{"origin_server_ts": 222}""").timestamp)
        assertEquals(222L, event("""{"timestamp": 0, "origin_server_ts": 222}""").timestamp)
    }

    @Test
    fun `missing timestamp is zero, not an exception`() {
        assertEquals(0L, event("""{"event_id": "${'$'}a"}""").timestamp)
    }

    @Test
    fun `blank optional strings become null rather than empty strings`() {
        // optString returns "" for absent keys; every optional field takeIf{isNotBlank} to avoid
        // empty-string sentinels leaking into `if (x != null)` checks all over the app.
        val e = event(
            """
            {"event_id": "${'$'}a", "state_key": "", "redacted_by": "", "decrypted_type": "",
             "transaction_id": "", "redaction_sender": "", "redaction_reason": ""}
            """,
        )

        assertNull(e.stateKey)
        assertNull(e.redactedBy)
        assertNull(e.decryptedType)
        assertNull(e.transactionId)
        assertNull(e.redactionSender)
        assertNull(e.redactionReason)
    }

    @Test
    fun `absent keys yield empty strings for required fields`() {
        val e = event("""{}""")

        assertEquals("", e.roomId)
        assertEquals("", e.eventId)
        assertEquals("", e.sender)
        assertEquals("", e.type)
        assertNull(e.content)
    }

    @Test
    fun `redactionTimestamp of zero is null`() {
        assertNull(event("""{"redaction_timestamp": 0}""").redactionTimestamp)
        assertEquals(99L, event("""{"redaction_timestamp": 99}""").redactionTimestamp)
    }

    // ---------------------------------------------------------------- aggregated reactions

    @Test
    fun `aggregated reactions are read from the top level`() {
        val e = event("""{"event_id": "${'$'}a", "reactions": {"👍": 3}}""")

        assertEquals(3, e.aggregatedReactions?.optInt("👍"))
    }

    @Test
    fun `aggregated reactions fall back to content dot reactions`() {
        val e = event("""{"event_id": "${'$'}a", "content": {"body": "hi", "reactions": {"🎉": 2}}}""")

        assertEquals(2, e.aggregatedReactions?.optInt("🎉"))
    }

    @Test
    fun `top-level reactions are copied into content`() {
        // Deliberate: several render paths read content.reactions rather than the parsed field.
        // Pinned because it mutates the caller's JSONObject, which is easy to break unknowingly.
        val e = event("""{"event_id": "${'$'}a", "content": {"body": "hi"}, "reactions": {"👍": 1}}""")

        assertEquals(1, e.content?.optJSONObject("reactions")?.optInt("👍"))
        assertSame(e.aggregatedReactions, e.content?.optJSONObject("reactions"))
    }

    // ---------------------------------------------------------------- relations

    @Test
    fun `top-level relation keys win over the payload`() {
        val e = event(
            """
            {"event_id": "${'$'}a", "relation_type": "m.thread", "relates_to": "${'$'}root",
             "content": {"m.relates_to": {"rel_type": "m.replace", "event_id": "${'$'}other"}}}
            """,
        )

        assertEquals("m.thread", e.relationType)
        assertEquals("${'$'}root", e.relatesTo)
    }

    @Test
    fun `relations fall back to content m_relates_to when the top level is absent`() {
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.message",
             "content": {"m.relates_to": {"rel_type": "m.thread", "event_id": "${'$'}root"}}}
            """,
        )

        assertEquals("m.thread", e.relationType)
        assertEquals("${'$'}root", e.relatesTo)
        assertTrue(e.isThreadMessage())
    }

    @Test
    fun `E2EE thread relations are read from decrypted`() {
        // Without this fallback a thread message in an encrypted room loses isThreadMessage() and
        // renders without its thread border, because the encrypted frame has no top-level relation.
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.encrypted", "decrypted_type": "m.room.message",
             "content": {"ciphertext": "AwgA…"},
             "decrypted": {"body": "hi", "m.relates_to": {"rel_type": "m.thread", "event_id": "${'$'}root"}}}
            """,
        )

        assertEquals("m.thread", e.relationType)
        assertEquals("${'$'}root", e.relatesTo)
        assertTrue(e.isThreadMessage())
    }

    @Test
    fun `relations fall back to the encrypted block`() {
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.encrypted",
             "encrypted": {"m.relates_to": {"rel_type": "m.reference", "event_id": "${'$'}poll"}}}
            """,
        )

        assertEquals("m.reference", e.relationType)
        assertEquals("${'$'}poll", e.relatesTo)
    }

    @Test
    fun `isThreadMessage requires both a thread relation and a target`() {
        assertFalse(event("""{"relation_type": "m.thread"}""").isThreadMessage())
        assertFalse(event("""{"relates_to": "${'$'}root"}""").isThreadMessage())
        assertFalse(event("""{"relation_type": "m.replace", "relates_to": "${'$'}root"}""").isThreadMessage())
    }

    // ---------------------------------------------------------------- orig_content reply recovery

    @Test
    fun `an edited reply recovers its in_reply_to from orig_content`() {
        // hicli pre-applies the edit (m.new_content -> content) when paginating an edited E2EE
        // reply, but m.new_content carries no m.in_reply_to, so the reply relation is dropped.
        // orig_content holds the pre-edit content and is used to put it back.
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.message",
             "content": {"body": "edited", "msgtype": "m.text"},
             "orig_content": {"m.relates_to": {"m.in_reply_to": {"event_id": "${'$'}replied"}}}}
            """,
        )

        assertEquals("${'$'}replied", e.getReplyInfo()?.eventId)
    }

    @Test
    fun `orig_content recovery does not overwrite an in_reply_to already present`() {
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.message",
             "content": {"body": "x", "m.relates_to": {"m.in_reply_to": {"event_id": "${'$'}current"}}},
             "orig_content": {"m.relates_to": {"m.in_reply_to": {"event_id": "${'$'}stale"}}}}
            """,
        )

        assertEquals("${'$'}current", e.getReplyInfo()?.eventId)
    }

    @Test
    fun `orig_content recovery preserves a sibling relation in content`() {
        // The injected m.in_reply_to must be merged into the existing m.relates_to, not replace it,
        // or an edited reply inside a thread would lose its thread relation.
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.message",
             "content": {"body": "x", "m.relates_to": {"rel_type": "m.thread", "event_id": "${'$'}root"}},
             "orig_content": {"m.relates_to": {"m.in_reply_to": {"event_id": "${'$'}replied"}}}}
            """,
        )

        assertEquals("m.thread", e.relationType)
        assertEquals("${'$'}root", e.relatesTo)
        assertEquals("${'$'}replied", e.content?.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id"))
    }

    // ---------------------------------------------------------------- getReplyInfo

    @Test
    fun `getReplyInfo reads a plaintext reply`() {
        val e = event(
            """
            {"event_id": "${'$'}a", "sender": "$alice", "room_id": "$room", "type": "m.room.message",
             "content": {"msgtype": "m.text", "body": "sure",
                         "m.relates_to": {"m.in_reply_to": {"event_id": "${'$'}replied"}}}}
            """,
        )

        val reply = e.getReplyInfo()
        assertNotNull(reply)
        assertEquals("${'$'}replied", reply!!.eventId)
        assertEquals(alice, reply.sender)
        assertEquals("sure", reply.body)
        assertEquals("m.text", reply.msgType)
    }

    @Test
    fun `getReplyInfo reads an E2EE reply from decrypted`() {
        val e = event(
            """
            {"event_id": "${'$'}a", "sender": "$alice", "type": "m.room.encrypted",
             "decrypted_type": "m.room.message", "content": {"ciphertext": "AwgA…"},
             "decrypted": {"msgtype": "m.text", "body": "sure",
                           "m.relates_to": {"m.in_reply_to": {"event_id": "${'$'}replied"}}}}
            """,
        )

        assertEquals("${'$'}replied", e.getReplyInfo()?.eventId)
    }

    @Test
    fun `a thread fallback is not a reply`() {
        // is_falling_back marks the synthetic reply threads use for un-threaded clients. Treating it
        // as a real reply would render a duplicate quote above every thread message.
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.message",
             "content": {"body": "x", "m.relates_to": {"rel_type": "m.thread", "event_id": "${'$'}root",
                         "is_falling_back": true, "m.in_reply_to": {"event_id": "${'$'}prev"}}}}
            """,
        )

        assertNull(e.getReplyInfo())
    }

    @Test
    fun `getReplyInfo is null for non-message events and for messages with no relation`() {
        assertNull(event("""{"type": "m.reaction", "content": {"m.relates_to": {"m.in_reply_to": {"event_id": "${'$'}x"}}}}""").getReplyInfo())
        assertNull(event("""{"type": "m.room.message", "content": {"body": "hi"}}""").getReplyInfo())
    }

    // ---------------------------------------------------------------- getThreadInfo

    @Test
    fun `getThreadInfo returns the root and the fallback reply`() {
        val e = event(
            """
            {"event_id": "${'$'}a", "type": "m.room.message",
             "content": {"body": "x", "m.relates_to": {"rel_type": "m.thread", "event_id": "${'$'}root",
                         "is_falling_back": true, "m.in_reply_to": {"event_id": "${'$'}prev"}}}}
            """,
        )

        val info = e.getThreadInfo()
        assertNotNull(info)
        assertEquals("${'$'}root", info!!.threadRootEventId)
        assertEquals("${'$'}prev", info.fallbackReplyToEventId)
    }

    @Test
    fun `getThreadInfo is null when the event is not a thread message`() {
        assertNull(event("""{"type": "m.room.message", "content": {"body": "hi"}}""").getThreadInfo())
    }

    // ---------------------------------------------------------------- getMessagePayload

    @Test
    fun `getMessagePayload picks decrypted for encrypted messages and content otherwise`() {
        val encrypted = event(
            """
            {"type": "m.room.encrypted", "decrypted_type": "m.room.message",
             "content": {"ciphertext": "AwgA…"}, "decrypted": {"body": "plain"}}
            """,
        )
        assertEquals("plain", encrypted.getMessagePayload()?.optString("body"))

        val plain = event("""{"type": "m.room.message", "content": {"body": "hi"}}""")
        assertEquals("hi", plain.getMessagePayload()?.optString("body"))
    }

    @Test
    fun `getMessagePayload falls back to decrypted when there is no content`() {
        val e = event("""{"type": "m.sticker", "decrypted": {"body": "sticker"}}""")

        assertEquals("sticker", e.getMessagePayload()?.optString("body"))
    }

    // ---------------------------------------------------------------- round trip

    @Test
    fun `toRawJsonObject round-trips the fields it carries`() {
        val original = event(
            """
            {"rowid": 7, "timeline_rowid": 8, "room_id": "$room", "event_id": "${'$'}a",
             "sender": "$alice", "type": "m.room.message", "timestamp": 1700000000000,
             "content": {"body": "hi"}, "redacted_by": "${'$'}red", "transaction_id": "tx1"}
            """,
        )

        val reparsed = TimelineEvent.fromJson(original.toRawJsonObject())

        assertEquals(original.rowid, reparsed.rowid)
        assertEquals(original.timelineRowid, reparsed.timelineRowid)
        assertEquals(original.eventId, reparsed.eventId)
        assertEquals(original.sender, reparsed.sender)
        assertEquals(original.type, reparsed.type)
        assertEquals(original.timestamp, reparsed.timestamp)
        assertEquals(original.redactedBy, reparsed.redactedBy)
        assertEquals(original.transactionId, reparsed.transactionId)
        assertEquals("hi", reparsed.content?.optString("body"))
    }
}
