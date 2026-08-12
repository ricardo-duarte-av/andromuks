package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.parseWebSocketMessageWithKotlinx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the kotlinx.serialization → org.json conversion that every inbound WebSocket frame goes
 * through ([parseWebSocketMessageWithKotlinx]).
 *
 * The bug these pin down: the converter decided a primitive's type by sniffing its *text*, because
 * `JsonPrimitive.content` strips the quotes from a JSON string and a bare literal alike. Any string
 * that merely looked numeric or boolean was silently re-typed — and one of those re-types took the
 * whole connection down with it. Kotlin's `toDoubleOrNull` accepts the full `Double.valueOf`
 * grammar, so the displayname `"NaN"` became `Double.NaN`, which `org.json`'s `put()` rejects
 * outright. The JSONException unwound the entire conversion, so ONE member event with a
 * `"NaN"` displayname made a whole ~350 KB `sync_complete` frame unparseable; the ordered
 * dispatcher skipped it, the ~99 rooms it carried never reached the room list, and the
 * `clear_state` diff-prune then deleted them from the persisted cache for not appearing in the
 * batch. Observed live against a 670-room account: `@genesixx:erwanleboucher.dev`, whose previous
 * displayname (still in `unsigned.prev_content` of their join event) is literally `NaN`.
 *
 * Like [ReactionEventParsingTest] these also pin the build setup: they assert on parsed *values*,
 * so a missing real `org.json` on the unit-test classpath fails them loudly rather than vacuously.
 */
class WebSocketFrameParsingTest {

    /** The exact shape that killed the frame: a member event whose prev displayname is "NaN". */
    @Test
    fun `member event with NaN displayname parses instead of throwing`() {
        val frame = """
            {
              "command": "sync_complete",
              "request_id": 0,
              "data": {
                "rooms": {
                  "!bdbbTYhctZTWzoTzn9:federated.nexus": {
                    "meta": { "room_id": "!bdbbTYhctZTWzoTzn9:federated.nexus", "name": "Nexus Client" },
                    "events": [
                      {
                        "type": "m.room.member",
                        "state_key": "@genesixx:erwanleboucher.dev",
                        "content": { "displayname": "Erwan", "membership": "join" },
                        "unsigned": {
                          "prev_content": { "displayname": "NaN", "membership": "join" }
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val json = parseWebSocketMessageWithKotlinx(frame)

        val room = json.getJSONObject("data")
            .getJSONObject("rooms")
            .getJSONObject("!bdbbTYhctZTWzoTzn9:federated.nexus")
        assertEquals("Nexus Client", room.getJSONObject("meta").getString("name"))

        val member = room.getJSONArray("events").getJSONObject(0)
        assertEquals("Erwan", member.getJSONObject("content").getString("displayname"))
        assertEquals(
            "NaN",
            member.getJSONObject("unsigned").getJSONObject("prev_content").getString("displayname"),
        )
    }

    /** `Infinity` and overflowing bare numbers hit the same `put()` rejection as NaN. */
    @Test
    fun `Infinity-shaped strings and overflowing numbers do not throw`() {
        val frame = """
            {
              "command": "sync_complete",
              "data": {
                "a": "Infinity",
                "b": "-Infinity",
                "c": "NaN",
                "overflow": 1e999
              }
            }
        """.trimIndent()

        val data = parseWebSocketMessageWithKotlinx(frame).getJSONObject("data")

        assertEquals("Infinity", data.getString("a"))
        assertEquals("-Infinity", data.getString("b"))
        assertEquals("NaN", data.getString("c"))
        // A bare number that overflows to Double.POSITIVE_INFINITY is kept as its raw text rather
        // than thrown away with the rest of the frame.
        assertEquals("1e999", data.getString("overflow"))
    }

    /**
     * Quoted strings keep their type even when the text looks like something else. Every value here
     * was being silently re-typed in the real payload: 665 rooms carried `room_version: "12"`,
     * space edges carried `order: " 0000"`, and a string equal to `"null"` became `JSONObject.NULL`.
     */
    @Test
    fun `strings that look like literals stay strings`() {
        val frame = """
            {
              "command": "sync_complete",
              "data": {
                "room_version": "12",
                "order": " 0000",
                "looks_null": "null",
                "looks_true": "true",
                "looks_false": "false",
                "looks_float": "1.0",
                "phone": "+351912271966",
                "big": "1786523108807015783"
              }
            }
        """.trimIndent()

        val data = parseWebSocketMessageWithKotlinx(frame).getJSONObject("data")

        assertEquals("12", data.get("room_version"))
        assertEquals(" 0000", data.get("order"))
        assertEquals("null", data.get("looks_null"))
        assertFalse("a quoted \"null\" must not become JSONObject.NULL", data.isNull("looks_null"))
        assertEquals("true", data.get("looks_true"))
        assertEquals("false", data.get("looks_false"))
        assertEquals("1.0", data.get("looks_float"))
        assertEquals("+351912271966", data.get("phone"))
        assertEquals("1786523108807015783", data.get("big"))
    }

    /** Bare literals must still be converted — the room list reads these as numbers and booleans. */
    @Test
    fun `bare literals keep their JSON types`() {
        val frame = """
            {
              "command": "sync_complete",
              "data": {
                "clear_state": true,
                "marked_unread": false,
                "sorting_timestamp": 1786523936792,
                "preview_event_rowid": 5244860,
                "unread_messages": 0,
                "timeline_rowid": -1,
                "ratio": 1.5,
                "missing": null
              }
            }
        """.trimIndent()

        val data = parseWebSocketMessageWithKotlinx(frame).getJSONObject("data")

        assertTrue(data.getBoolean("clear_state"))
        assertFalse(data.getBoolean("marked_unread"))
        // Must survive as Long: this one does not fit in an Int.
        assertEquals(1786523936792L, data.getLong("sorting_timestamp"))
        assertEquals(5244860L, data.getLong("preview_event_rowid"))
        assertEquals(0, data.getInt("unread_messages"))
        assertEquals(-1L, data.getLong("timeline_rowid"))
        assertEquals(1.5, data.getDouble("ratio"), 0.0)
        assertTrue(data.isNull("missing"))
    }

    /** gomuks terminates every frame with a newline; trailing whitespace must not fail the parse. */
    @Test
    fun `trailing newline is tolerated`() {
        val json = parseWebSocketMessageWithKotlinx("{\"command\":\"init_complete\",\"request_id\":0}\n")
        assertEquals("init_complete", json.getString("command"))
    }

    /**
     * Deep nesting must cost only the offending subtree, never the frame. The converter recurses,
     * so nesting depth in an event's `content` — arbitrary JSON from any user on the federation —
     * is stack depth on a parse worker. Losing the frame here would cost ~99 rooms; losing one
     * absurd blob costs nothing anyone will miss.
     */
    @Test
    fun `nesting past the depth cap drops only that subtree`() {
        val depth = 400
        val nested = "[".repeat(depth) + "1" + "]".repeat(depth)
        val frame = """
            {
              "command": "sync_complete",
              "data": {
                "rooms": {
                  "!keepme:example.org": { "meta": { "name": "Still Here" } }
                },
                "pathological": $nested
              }
            }
        """.trimIndent()

        val data = parseWebSocketMessageWithKotlinx(frame).getJSONObject("data")

        // The rest of the frame survives — that is the whole point.
        assertEquals(
            "Still Here",
            data.getJSONObject("rooms").getJSONObject("!keepme:example.org")
                .getJSONObject("meta").getString("name"),
        )
        // The over-deep value is present but truncated to null rather than expanded.
        assertTrue(data.has("pathological"))
    }

    /** Nesting that legitimate Matrix events reach (edit → reply → formatted content) must survive. */
    @Test
    fun `realistic nesting is well within the cap`() {
        val depth = 20
        val frame = """{"command":"sync_complete","data":{"v":${"[".repeat(depth)}42${"]".repeat(depth)}}}"""

        var node = parseWebSocketMessageWithKotlinx(frame).getJSONObject("data").getJSONArray("v")
        repeat(depth - 1) { node = node.getJSONArray(0) }

        assertEquals(42L, node.getLong(0))
    }
}
