package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.parseGetProfileResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for the gomuks `get_profile` response.
 *
 * The backend moved the Matrix profile object one level down, under `"profile"`, and added a
 * sibling `"bio"` (its server-side rendering of MSC4440's `gay.fomx.biography`). Both of our
 * parse sites read the old flat top level, so against the new backend every global profile
 * lookup produced a blank `MemberProfile` — poisoning `ProfileCache`, wiping the persisted
 * `current_user_display_name` / `current_user_avatar_mxc` sentinels (see docs/USER_PROFILES.md)
 * and leaving `{profile, bio}` sitting in `arbitraryFields` to be rendered as raw JSON cards.
 *
 * These pin both shapes: the nested one and the flat one we still accept from a backend that
 * predates the change. They assert on parsed *values*, so they also fail loudly if the real
 * `org.json` dependency is dropped from the unit-test classpath (see app/build.gradle.kts).
 */
class GetProfileResponseParsingTest {

    // ---------------------------------------------------------------- fixtures

    private fun nested(profileBody: String, bioBody: String? = null): JSONObject = JSONObject().apply {
        put("profile", JSONObject(profileBody))
        if (bioBody != null) put("bio", JSONObject(bioBody))
    }

    private val fullProfile = """
        {
          "displayname": "Alice",
          "avatar_url": "mxc://example.org/avatar",
          "m.tz": "Europe/Lisbon",
          "io.fsky.nyx.pronouns": [ { "language": "en", "summary": "she/her" } ]
        }
    """.trimIndent()

    // ---------------------------------------------------------------- nested shape

    @Test
    fun `nested profile object yields display name avatar timezone and pronouns`() {
        val parsed = parseGetProfileResponse(nested(fullProfile))

        assertEquals("Alice", parsed.displayName)
        assertEquals("mxc://example.org/avatar", parsed.avatarUrl)
        assertEquals("Europe/Lisbon", parsed.timezone)
        assertEquals(1, parsed.pronouns?.size)
        assertEquals("she/her", parsed.pronouns?.first()?.summary)
        assertEquals("en", parsed.pronouns?.first()?.language)
    }

    @Test
    fun `flat legacy shape parses identically`() {
        val parsed = parseGetProfileResponse(JSONObject(fullProfile))

        assertEquals("Alice", parsed.displayName)
        assertEquals("mxc://example.org/avatar", parsed.avatarUrl)
        assertEquals("Europe/Lisbon", parsed.timezone)
        assertEquals("she/her", parsed.pronouns?.first()?.summary)
    }

    // ---------------------------------------------------------------- blank sentinels

    @Test
    fun `absent display name and avatar are empty strings not null`() {
        val parsed = parseGetProfileResponse(nested("""{ }"""))

        assertEquals("", parsed.displayName)
        assertEquals("", parsed.avatarUrl)
    }

    @Test
    fun `the literal string null is treated as blank`() {
        val parsed = parseGetProfileResponse(
            nested("""{ "displayname": "null", "avatar_url": "   " }"""),
        )

        assertEquals("", parsed.displayName)
        assertEquals("", parsed.avatarUrl)
    }

    // ---------------------------------------------------------------- timezone precedence

    @Test
    fun `m tz wins over the legacy msc4175 key`() {
        val parsed = parseGetProfileResponse(
            nested("""{ "m.tz": "Europe/Lisbon", "us.cloke.msc4175.tz": "America/New_York" }"""),
        )

        assertEquals("Europe/Lisbon", parsed.timezone)
    }

    @Test
    fun `the legacy msc4175 key is used when m tz is absent`() {
        val parsed = parseGetProfileResponse(
            nested("""{ "us.cloke.msc4175.tz": "America/New_York" }"""),
        )

        assertEquals("America/New_York", parsed.timezone)
    }

    @Test
    fun `no timezone key at all yields null`() {
        assertNull(parseGetProfileResponse(nested("""{ "displayname": "Alice" }""")).timezone)
    }

    // ---------------------------------------------------------------- pronouns

    @Test
    fun `pronoun language defaults to en and blank summaries are skipped`() {
        val parsed = parseGetProfileResponse(
            nested(
                """
                {
                  "io.fsky.nyx.pronouns": [
                    { "summary": "they/them" },
                    { "language": "pt", "summary": "" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1, parsed.pronouns?.size)
        assertEquals("they/them", parsed.pronouns?.first()?.summary)
        assertEquals("en", parsed.pronouns?.first()?.language)
    }

    @Test
    fun `an empty pronouns array yields null rather than an empty list`() {
        assertNull(parseGetProfileResponse(nested("""{ "io.fsky.nyx.pronouns": [] }""")).pronouns)
    }

    // ---------------------------------------------------------------- bio

    @Test
    fun `bio is read from the top level not from inside profile`() {
        val parsed = parseGetProfileResponse(
            nested(fullProfile, """{ "html": "<p>hello</p>", "edit_source": "hello" }"""),
        )

        assertNotNull(parsed.bio)
        assertEquals("<p>hello</p>", parsed.bio?.html)
        assertEquals("hello", parsed.bio?.editSource)
    }

    @Test
    fun `bio without edit source is another users profile and stays uneditable`() {
        val parsed = parseGetProfileResponse(nested(fullProfile, """{ "html": "<p>hello</p>" }"""))

        assertEquals("<p>hello</p>", parsed.bio?.html)
        assertNull(parsed.bio?.editSource)
    }

    @Test
    fun `absent or blank bio yields null`() {
        assertNull(parseGetProfileResponse(nested(fullProfile)).bio)
        assertNull(parseGetProfileResponse(nested(fullProfile, """{ "html": "" }""")).bio)
    }

    // ---------------------------------------------------------------- arbitrary fields

    @Test
    fun `wrapper keys never leak into arbitrary fields`() {
        val parsed = parseGetProfileResponse(
            nested(fullProfile, """{ "html": "<p>hello</p>" }"""),
        )

        assertFalse(parsed.arbitraryFields.containsKey("profile"))
        assertFalse(parsed.arbitraryFields.containsKey("bio"))
    }

    @Test
    fun `the raw biography field is not duplicated into arbitrary fields`() {
        val parsed = parseGetProfileResponse(
            nested(
                """{ "gay.fomx.biography": { "m.text": [ { "body": "hello" } ] } }""",
                """{ "html": "<p>hello</p>" }""",
            ),
        )

        assertFalse(parsed.arbitraryFields.containsKey("gay.fomx.biography"))
        assertEquals("<p>hello</p>", parsed.bio?.html)
    }

    @Test
    fun `unknown custom keys inside profile are passed through`() {
        val parsed = parseGetProfileResponse(
            nested(
                """
                {
                  "displayname": "Alice",
                  "m.status": { "text": "afk", "emoji": "🌴" },
                  "chat.commet.profile_banner": "mxc://example.org/banner",
                  "some.count": 3,
                  "some.flag": true
                }
                """.trimIndent(),
            ),
        )

        assertEquals(4, parsed.arbitraryFields.size)
        assertTrue(parsed.arbitraryFields["m.status"] is JSONObject)
        assertEquals("mxc://example.org/banner", parsed.arbitraryFields["chat.commet.profile_banner"])
        assertEquals(3, parsed.arbitraryFields["some.count"])
        assertEquals(true, parsed.arbitraryFields["some.flag"])
    }

    @Test
    fun `known typed keys are not repeated in arbitrary fields`() {
        val parsed = parseGetProfileResponse(nested(fullProfile))

        assertTrue(parsed.arbitraryFields.isEmpty())
    }
}
