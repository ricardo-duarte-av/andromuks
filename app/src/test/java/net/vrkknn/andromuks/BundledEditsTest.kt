package net.vrkknn.andromuks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [editTargetOf] / [latestEditsByTarget] classify the `m.replace` events gomuks now appends to
 * `related_events`. Events are parsed with [TimelineEvent.fromJson] from backend-shaped JSON so a
 * parser change that stops populating the relation fields fails here too.
 */
class BundledEditsTest {

    private fun plainEdit(id: String, target: String, ts: Long, body: String) = TimelineEvent.fromJson(
        JSONObject(
            """
            {
              "rowid": 10, "timeline_rowid": -1, "room_id": "!r:x", "event_id": "$id",
              "sender": "@a:x", "type": "m.room.message", "timestamp": $ts,
              "relates_to": "$target", "relation_type": "m.replace",
              "content": {
                "msgtype": "m.text", "body": "* $body",
                "m.new_content": { "msgtype": "m.text", "body": "$body" },
                "m.relates_to": { "rel_type": "m.replace", "event_id": "$target" }
              }
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `plain edit resolves its target from content`() {
        assertEquals("\$orig", editTargetOf(plainEdit("\$e1", "\$orig", 1000, "fixed")))
    }

    @Test
    fun `encrypted edit resolves its target from decrypted`() {
        val event = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "rowid": 11, "timeline_rowid": -1, "room_id": "!r:x", "event_id": "${'$'}enc",
                  "sender": "@a:x", "type": "m.room.encrypted", "timestamp": 1,
                  "decrypted_type": "m.room.message",
                  "content": { "algorithm": "m.megolm.v1.aes-sha2", "ciphertext": "xyz" },
                  "decrypted": {
                    "msgtype": "m.text", "body": "* hi",
                    "m.new_content": { "msgtype": "m.text", "body": "hi" },
                    "m.relates_to": { "rel_type": "m.replace", "event_id": "${'$'}orig" }
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("\$orig", editTargetOf(event))
    }

    @Test
    fun `reply is not an edit`() {
        val reply = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "rowid": 12, "timeline_rowid": 5, "room_id": "!r:x", "event_id": "${'$'}reply",
                  "sender": "@a:x", "type": "m.room.message", "timestamp": 1,
                  "content": {
                    "msgtype": "m.text", "body": "yes",
                    "m.relates_to": { "m.in_reply_to": { "event_id": "${'$'}orig" } }
                  }
                }
                """.trimIndent(),
            ),
        )
        assertNull(editTargetOf(reply))
    }

    @Test
    fun `latest edit per target wins`() {
        val older = plainEdit("\$e1", "\$orig", 1000, "first")
        val newer = plainEdit("\$e2", "\$orig", 2000, "second")
        val other = plainEdit("\$e3", "\$other", 1500, "third")

        val latest = latestEditsByTarget(listOf(newer, older, other))

        assertEquals(2, latest.size)
        assertEquals("\$e2", latest["\$orig"]?.eventId)
        assertEquals("\$e3", latest["\$other"]?.eventId)
    }
}
