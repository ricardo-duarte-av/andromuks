package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.ProfileBioContent
import net.vrkknn.andromuks.utils.SPEC_BIO_SOURCE_KEY
import net.vrkknn.andromuks.utils.bioDedupeKey
import net.vrkknn.andromuks.utils.extractProfileBanner
import net.vrkknn.andromuks.utils.extractProfileBios
import net.vrkknn.andromuks.utils.extractProfileCall
import net.vrkknn.andromuks.utils.extractProfileStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure extractors that turn `get_profile`'s arbitrary profile fields into the
 * things `UserInfoScreen` renders: status, ongoing call, banner and biographies.
 *
 * These fields are vendor extensions with several competing key spellings, and the extractors
 * are the only place that knows which spellings we accept and what makes a value renderable
 * (a status needs both text and emoji; a banner must be an mxc:// String). They are pure and
 * dependency-free, so they are cheap to pin — and the alternative is discovering a regression
 * by staring at somebody's profile screen.
 *
 * [extractProfileBios] additionally merges the MSC4440 biography, which the backend delivers
 * pre-rendered in its own `bio` field rather than as a profile key; see
 * `GetProfileResponseParsingTest` and docs/USER_PROFILES.md.
 */
class ProfileFieldExtractionTest {

    // ---------------------------------------------------------------- status

    @Test
    fun `status is read from any of the accepted key spellings`() {
        listOf("m.status", "org.msc.4426.status", "org.msc4426.status").forEach { key ->
            val status = extractProfileStatus(
                mapOf(key to JSONObject("""{ "text": "afk", "emoji": "🌴" }""")),
            )

            assertEquals(key, status?.sourceKey)
            assertEquals("afk", status?.text)
            assertEquals("🌴", status?.emoji)
        }
    }

    @Test
    fun `m status wins when several spellings are present`() {
        val status = extractProfileStatus(
            mapOf(
                "org.msc4426.status" to JSONObject("""{ "text": "old", "emoji": "🕰" }"""),
                "m.status" to JSONObject("""{ "text": "new", "emoji": "✨" }"""),
            ),
        )

        assertEquals("m.status", status?.sourceKey)
        assertEquals("new", status?.text)
    }

    @Test
    fun `a status missing either text or emoji is not renderable`() {
        assertNull(extractProfileStatus(mapOf("m.status" to JSONObject("""{ "text": "afk" }"""))))
        assertNull(extractProfileStatus(mapOf("m.status" to JSONObject("""{ "emoji": "🌴" }"""))))
        assertNull(extractProfileStatus(mapOf("m.status" to "afk")))
        assertNull(extractProfileStatus(emptyMap()))
    }

    @Test
    fun `a status supplied as a map is accepted`() {
        val status = extractProfileStatus(mapOf("m.status" to mapOf("text" to "afk", "emoji" to "🌴")))

        assertEquals("afk", status?.text)
    }

    // ---------------------------------------------------------------- call

    @Test
    fun `call is read from any of the accepted key spellings`() {
        listOf("m.call", "org.msc.4426.call", "org.msc4426.call").forEach { key ->
            val call = extractProfileCall(mapOf(key to JSONObject("""{ "call_joined_ts": 1700000000000 }""")))

            assertEquals(key, call?.sourceKey)
            assertEquals(1700000000000L, call?.callJoinedTs)
        }
    }

    @Test
    fun `a call object without a timestamp is still a call`() {
        val call = extractProfileCall(mapOf("m.call" to JSONObject("{ }")))

        assertNotNull(call)
        assertNull(call?.callJoinedTs)
    }

    @Test
    fun `no call key yields null`() {
        assertNull(extractProfileCall(emptyMap()))
    }

    // ---------------------------------------------------------------- banner

    @Test
    fun `banner must be an mxc string`() {
        assertEquals(
            "mxc://example.org/banner",
            extractProfileBanner(mapOf("chat.commet.profile_banner" to "mxc://example.org/banner"))?.mxcUrl,
        )
        assertNull(extractProfileBanner(mapOf("chat.commet.profile_banner" to "https://example.org/banner.png")))
        assertNull(extractProfileBanner(mapOf("chat.commet.profile_banner" to JSONObject("{ }"))))
        assertNull(extractProfileBanner(emptyMap()))
    }

    // ---------------------------------------------------------------- bios

    private val commetHtml = JSONObject(
        """
        {
          "body": "**hello**",
          "format": "org.matrix.custom.html",
          "formatted_body": "<b>hello</b>"
        }
        """.trimIndent(),
    )

    @Test
    fun `commet bio prefers the formatted body when the format is html`() {
        val bio = extractProfileBios(mapOf("chat.commet.profile_bio" to commetHtml)).single()

        assertEquals("<b>hello</b>", bio.body)
        assertTrue(bio.isHtml)
        assertEquals("chat.commet.profile_bio", bio.sourceKey)
    }

    @Test
    fun `commet bio falls back to plain body without an html format`() {
        val bio = extractProfileBios(
            mapOf("chat.commet.profile_bio" to JSONObject("""{ "body": "hello" }""")),
        ).single()

        assertEquals("hello", bio.body)
        assertTrue(!bio.isHtml)
    }

    @Test
    fun `both vendor bios are returned when both exist`() {
        val bios = extractProfileBios(
            mapOf(
                "chat.commet.profile_bio" to commetHtml,
                "moe.sable.app.bio" to "<i>sable</i>",
            ),
        )

        assertEquals(listOf("chat.commet.profile_bio", "moe.sable.app.bio"), bios.map { it.sourceKey })
    }

    @Test
    fun `the standard bio comes first and carries its edit source`() {
        val bios = extractProfileBios(
            mapOf("chat.commet.profile_bio" to commetHtml),
            ProfileBioContent(html = "<p>standard</p>", editSource = "standard"),
        )

        assertEquals(listOf(SPEC_BIO_SOURCE_KEY, "chat.commet.profile_bio"), bios.map { it.sourceKey })
        assertEquals("<p>standard</p>", bios.first().body)
        assertTrue(bios.first().isHtml)
        assertEquals("standard", bios.first().editSource)
    }

    @Test
    fun `vendor bios have no edit source`() {
        val bio = extractProfileBios(mapOf("chat.commet.profile_bio" to commetHtml)).single()

        assertNull(bio.editSource)
    }

    @Test
    fun `no bios at all yields an empty list`() {
        assertTrue(extractProfileBios(emptyMap()).isEmpty())
    }

    // ------------------------------------------------------- duplicate bios

    /**
     * The same bio mirrored across all three fields, taken verbatim from a real profile that
     * rendered as three cards labelled Bio, About and Bio.
     */
    @Test
    fun `a bio mirrored across all three fields renders once`() {
        val html =
            "<p>Sometimes i feel like im simply a collection of mask.</p>\n" +
                "<p>Hollow inside, with only emptiness behind all of these facades.</p>"
        val bios = extractProfileBios(
            mapOf(
                "chat.commet.profile_bio" to JSONObject()
                    .put("format", "org.matrix.custom.html")
                    .put("formatted_body", html),
                "moe.sable.app.bio" to html,
            ),
            ProfileBioContent(html = html, editSource = null),
        )

        assertEquals(listOf(SPEC_BIO_SOURCE_KEY), bios.map { it.sourceKey })
    }

    @Test
    fun `a plain text copy of an html bio counts as the same bio`() {
        val bios = extractProfileBios(
            mapOf(
                "chat.commet.profile_bio" to JSONObject("""{ "body": "hello there" }"""),
                "moe.sable.app.bio" to "<p>hello there</p>",
            ),
        )

        assertEquals(listOf("chat.commet.profile_bio"), bios.map { it.sourceKey })
    }

    @Test
    fun `genuinely different bios are all kept`() {
        val bios = extractProfileBios(
            mapOf(
                "chat.commet.profile_bio" to commetHtml,
                "moe.sable.app.bio" to "<i>something else entirely</i>",
            ),
        )

        assertEquals(listOf("chat.commet.profile_bio", "moe.sable.app.bio"), bios.map { it.sourceKey })
    }

    @Test
    fun `deduping keeps the editable copy`() {
        val bios = extractProfileBios(
            mapOf("moe.sable.app.bio" to "<b>hello</b>"),
            ProfileBioContent(html = "<b>hello</b>", editSource = "**hello**"),
        )

        assertEquals("**hello**", bios.single().editSource)
    }

    @Test
    fun `dedupe key ignores markup and whitespace but not wording`() {
        assertEquals(bioDedupeKey("<p>hello  there</p>"), bioDedupeKey("hello\nthere"))
        assertNotEquals(bioDedupeKey("hello there"), bioDedupeKey("hello world"))
    }

    /**
     * A bio containing the literal text `&lt;b&gt;` arrives as `&amp;lt;b&amp;gt;`. Decoding
     * `&amp;` last means it decodes one step, to text — decoding it first would produce `<b>`
     * and turn a user's escaped text into markup.
     */
    @Test
    fun `dedupe key decodes an escaped entity only once`() {
        assertEquals("&lt;b&gt;", bioDedupeKey("&amp;lt;b&amp;gt;"))
    }
}
