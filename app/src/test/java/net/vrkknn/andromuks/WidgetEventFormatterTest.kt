package net.vrkknn.andromuks

import net.vrkknn.andromuks.widget.WidgetEventFormatter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the single line the room widget paints per event.
 *
 * The widget cannot fall back on anything richer — one line is all it has — so an event shape that
 * formats to an empty string or a raw `geo:` URI is a visible defect. These cover the msgtypes the
 * timeline actually renders, plus the two transformations the widget performs that reply previews
 * do not: local edit resolution and redaction handling.
 */
class WidgetEventFormatterTest {

    private val room = "!room:example.org"
    private val alice = "@alice:example.org"

    private fun event(content: String, type: String = "m.room.message", eventId: String = "\$evt-1", rowid: Long = 10L, extra: String = "") =
        TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "room_id": "$room",
                  "event_id": "$eventId",
                  "sender": "$alice",
                  "type": "$type",
                  "timestamp": 1700000000000,
                  "timeline_rowid": $rowid,
                  "content": $content
                  $extra
                }
                """.trimIndent(),
            ),
        )

    private fun textEvent(body: String, eventId: String = "\$evt-1", rowid: Long = 10L) =
        event("""{ "msgtype": "m.text", "body": ${JSONObject.quote(body)} }""", eventId = eventId, rowid = rowid)

    @Test
    fun `plain text renders its body`() {
        assertEquals("Hello there", WidgetEventFormatter.format(textEvent("Hello there")))
    }

    @Test
    fun `media messages render a described placeholder rather than a filename`() {
        val cases = mapOf(
            "m.image" to "📷 Sent a photo",
            "m.video" to "📹 Sent a video",
            "m.audio" to "🎶 Sent an audio",
            "m.file" to "📁 Sent a file",
            "m.location" to "📍 Shared a location",
        )
        for ((msgtype, expected) in cases) {
            val e = event("""{ "msgtype": "$msgtype", "body": "raw-body.bin" }""")
            assertEquals("msgtype $msgtype", expected, WidgetEventFormatter.format(e))
        }
    }

    @Test
    fun `sticker renders its name`() {
        val e = event("""{ "msgtype": "m.sticker", "body": "party parrot" }""")
        assertEquals("🎨 party parrot", WidgetEventFormatter.format(e))
    }

    @Test
    fun `emote renders its action text`() {
        val e = event("""{ "msgtype": "m.emote", "body": "waves" }""")
        assertEquals("waves", WidgetEventFormatter.format(e))
    }

    @Test
    fun `a message with an empty body still produces something to draw`() {
        val e = event("""{ "msgtype": "m.text", "body": "" }""")
        assertTrue(WidgetEventFormatter.format(e).isNotBlank())
    }

    @Test
    fun `redacted message reads as deleted rather than showing stale content`() {
        // A redacted event arrives with its content emptied; without explicit handling this would
        // format as "Empty message".
        val e = event(
            """{ }""",
            extra = """, "redacted_by": "${'$'}redaction-1"""",
        )
        assertEquals("Message deleted", WidgetEventFormatter.format(e))
    }

    @Test
    fun `reply quote fallback is stripped so the reply text is what shows`() {
        val body = "> <@bob:example.org> original message\n\nmy actual reply"
        assertEquals("my actual reply", WidgetEventFormatter.format(textEvent(body)))
    }

    @Test
    fun `edit is applied to the original message`() {
        val original = textEvent("before", eventId = "\$orig", rowid = 10L)
        val edit = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "room_id": "$room",
                  "event_id": "${'$'}edit-1",
                  "sender": "$alice",
                  "type": "m.room.message",
                  "timestamp": 1700000001000,
                  "timeline_rowid": 11,
                  "content": {
                    "msgtype": "m.text",
                    "body": "* after",
                    "m.new_content": { "msgtype": "m.text", "body": "after" },
                    "m.relates_to": { "rel_type": "m.replace", "event_id": "${'$'}orig" }
                  }
                }
                """.trimIndent(),
            ),
        )

        val edits = WidgetEventFormatter.collectEdits(listOf(original, edit))

        assertEquals("after", WidgetEventFormatter.format(original, edits))
    }

    @Test
    fun `latest edit wins when a message is edited twice`() {
        val original = textEvent("v1", eventId = "\$orig", rowid = 10L)
        fun editAt(rowid: Long, body: String, id: String) = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "room_id": "$room",
                  "event_id": "$id",
                  "sender": "$alice",
                  "type": "m.room.message",
                  "timestamp": 1700000002000,
                  "timeline_rowid": $rowid,
                  "content": {
                    "msgtype": "m.text",
                    "body": "* $body",
                    "m.new_content": { "msgtype": "m.text", "body": "$body" },
                    "m.relates_to": { "rel_type": "m.replace", "event_id": "${'$'}orig" }
                  }
                }
                """.trimIndent(),
            ),
        )

        // Deliberately out of order: "newest" must be decided by timeline_rowid, not list position.
        val edits = WidgetEventFormatter.collectEdits(
            listOf(original, editAt(12L, "v3", "\$e2"), editAt(11L, "v2", "\$e1")),
        )

        assertEquals("v3", WidgetEventFormatter.format(original, edits))
    }

    @Test
    fun `redaction outranks a pending edit`() {
        val original = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "room_id": "$room",
                  "event_id": "${'$'}orig",
                  "sender": "$alice",
                  "type": "m.room.message",
                  "timestamp": 1700000000000,
                  "timeline_rowid": 10,
                  "content": { "msgtype": "m.text", "body": "before" },
                  "redacted_by": "${'$'}redaction-1"
                }
                """.trimIndent(),
            ),
        )
        val edits = mapOf("\$orig" to JSONObject("""{ "msgtype": "m.text", "body": "after" }"""))

        assertEquals("Message deleted", WidgetEventFormatter.format(original, edits))
    }

    @Test
    fun `collectEdits ignores non-edit relations`() {
        val reply = TimelineEvent.fromJson(
            JSONObject(
                """
                {
                  "room_id": "$room",
                  "event_id": "${'$'}reply",
                  "sender": "$alice",
                  "type": "m.room.message",
                  "timestamp": 1700000000000,
                  "timeline_rowid": 11,
                  "content": {
                    "msgtype": "m.text",
                    "body": "a reply",
                    "m.relates_to": { "m.in_reply_to": { "event_id": "${'$'}orig" } }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(WidgetEventFormatter.collectEdits(listOf(reply)).isEmpty())
    }

    @Test
    fun `very long messages are capped`() {
        val formatted = WidgetEventFormatter.format(textEvent("x".repeat(1_000)))

        assertTrue("expected a cap, got ${formatted.length}", formatted.length <= 301)
        assertTrue(formatted.endsWith("…"))
    }
}
