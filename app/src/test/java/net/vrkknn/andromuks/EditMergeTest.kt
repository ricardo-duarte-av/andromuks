package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.parseMediaMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergedEditedContent] applies `m.new_content` as a replacement, never an overlay.
 *
 * The first two cases are issue #29: a WhatsApp-bridged message that failed to decrypt posts an
 * HTML `m.notice`, then edits itself into the real `m.image`. An overlay left the notice's
 * `format`/`formatted_body` on the image content, and the renderer preferred that stale
 * `formatted_body` over the edit's own `body` — drawing the decrypt-failure text as the caption.
 */
class EditMergeTest {

    /** The pre-edit notice, verbatim from event `$4zmoGMa4…` in the bug report. */
    private fun undecryptableNotice() = JSONObject(
        """
        {
          "body": "Decrypting message from WhatsApp failed, waiting for sender to re-send...",
          "format": "org.matrix.custom.html",
          "formatted_body": "Decrypting message from WhatsApp failed, waiting for sender to re-send... (<a href=\"https://faq.whatsapp.com/\">learn more</a>)",
          "fi.mau.whatsapp.undecryptable": true,
          "m.mentions": {},
          "msgtype": "m.notice"
        }
        """.trimIndent(),
    )

    private fun imageNewContent(body: String?) = JSONObject(
        """
        {
          "filename": "image.jpg",
          "info": { "h": 2040, "mimetype": "image/jpeg", "size": 283814, "w": 1530 },
          "m.mentions": {},
          "msgtype": "m.image",
          "url": "mxc://matrixbridg.es/mTBldlJhvDtcpWWuAjwnFCOp"
        }
        """.trimIndent(),
    ).also { if (body != null) it.put("body", body) }

    @Test
    fun `captioned image edit drops the notice formatting and keeps its own caption`() {
        val merged = mergedEditedContent(undecryptableNotice(), imageNewContent("Troca de Pcm's"))

        assertEquals("m.image", merged.optString("msgtype"))
        assertEquals("mxc://matrixbridg.es/mTBldlJhvDtcpWWuAjwnFCOp", merged.optString("url"))
        assertEquals("image.jpg", merged.optString("filename"))
        assertEquals(1530, merged.optJSONObject("info")?.optInt("w"))
        assertEquals("Troca de Pcm's", merged.optString("body"))
        assertFalse("stale format must not survive the edit", merged.has("format"))
        assertFalse("stale formatted_body must not survive the edit", merged.has("formatted_body"))
        assertFalse(merged.has("fi.mau.whatsapp.undecryptable"))

        // The rendered caption is the edit's own text, not the decrypt-failure notice.
        val media = parseMediaMessage(content = merged, body = merged.optString("body"))
        assertEquals("Troca de Pcm's", media?.caption)
    }

    @Test
    fun `captionless image edit leaves no caption behind`() {
        val merged = mergedEditedContent(undecryptableNotice(), imageNewContent(body = null))

        assertFalse("the pre-edit body must not survive as a caption", merged.has("body"))
        val media = parseMediaMessage(content = merged, body = merged.optString("body", ""))
        assertNull(media?.caption)
    }

    @Test
    fun `reply relation is preserved when the edit omits it`() {
        val original = JSONObject(
            """
            {
              "body": "hi",
              "msgtype": "m.text",
              "m.relates_to": { "m.in_reply_to": { "event_id": "${'$'}target" } }
            }
            """.trimIndent(),
        )
        val merged = mergedEditedContent(original, JSONObject("""{"body":"hi there","msgtype":"m.text"}"""))

        assertEquals(
            "\$target",
            merged.optJSONObject("m.relates_to")?.optJSONObject("m.in_reply_to")?.optString("event_id"),
        )
        assertEquals("hi there", merged.optString("body"))
    }

    @Test
    fun `aggregated reactions bucket survives the merge`() {
        val original = JSONObject("""{"body":"hi","msgtype":"m.text","reactions":{"👍":1}}""")
        val merged = mergedEditedContent(original, JSONObject("""{"body":"hi there","msgtype":"m.text"}"""))

        assertEquals(1, merged.optJSONObject("reactions")?.optInt("👍"))
    }

    @Test
    fun `an edit that supplies its own relation keeps it`() {
        val original = JSONObject("""{"body":"hi","msgtype":"m.text","m.relates_to":{"rel_type":"m.thread"}}""")
        val merged = mergedEditedContent(
            original,
            JSONObject("""{"body":"hi there","msgtype":"m.text","m.relates_to":{"rel_type":"m.replace"}}"""),
        )

        assertEquals("m.replace", merged.optJSONObject("m.relates_to")?.optString("rel_type"))
    }

    @Test
    fun `plain text edit still replaces body and formatting`() {
        val original = JSONObject(
            """{"body":"one","format":"org.matrix.custom.html","formatted_body":"<b>one</b>","msgtype":"m.text"}""",
        )
        val merged = mergedEditedContent(
            original,
            JSONObject(
                """{"body":"two","format":"org.matrix.custom.html","formatted_body":"<i>two</i>","msgtype":"m.text"}""",
            ),
        )

        assertEquals("two", merged.optString("body"))
        assertEquals("<i>two</i>", merged.optString("formatted_body"))
        assertTrue(merged.has("format"))
    }
}
