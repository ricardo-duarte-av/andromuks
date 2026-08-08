package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.PerMessageProfileTrigger
import net.vrkknn.andromuks.utils.parseEntry
import net.vrkknn.andromuks.utils.parseStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser-level tests for MSC4461 per-message profile storage.
 *
 * Two things here are subtle enough to be worth pinning:
 *
 * 1. **The tolerant reader.** MSC4461 has been revised twice and the stable key kept its name each
 *    time, so the parser has to recognise a rev-3 `triggers` array and a rev-2 `trigger.prefix`
 *    object from the same key. Getting this wrong silently produces a profile with no triggers,
 *    which looks fine in the UI and simply never fires.
 * 2. **The three states of `default_profile_id`.** Absent, JSON `null` and `""` mean different
 *    things (fall through to global / fall through to global / explicitly suppress the global), and
 *    `optString` collapses all three to `""`. That collapse is exactly the bug `readNullableString`
 *    exists to prevent, so it is asserted directly.
 */
class PerMessageProfileParsingTest {

    @Test
    fun `parses rev-3 triggers with prefix and suffix`() {
        val entry = parseEntry(
            JSONObject(
                """
                {
                  "id": "cat",
                  "displayname": "Cat 🐈️",
                  "avatar_url": "mxc://example.org/abc",
                  "triggers": [
                    {"prefix": "meow ", "suffix": " meow"},
                    {"prefix": "cat: "}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertNotNull(entry)
        assertEquals("cat", entry!!.id)
        assertEquals("Cat 🐈️", entry.displayname)
        assertEquals("mxc://example.org/abc", entry.avatarUrl)
        assertEquals(
            listOf(
                PerMessageProfileTrigger("meow ", " meow"),
                PerMessageProfileTrigger("cat: "),
            ),
            entry.triggers,
        )
    }

    @Test
    fun `converts a rev-2 trigger prefix list into single-prefix triggers`() {
        val entry = parseEntry(
            JSONObject("""{"id": "cat", "trigger": {"prefix": ["mrrp:", "cat: "]}}"""),
        )

        assertNotNull(entry)
        assertEquals(
            listOf(PerMessageProfileTrigger("mrrp:"), PerMessageProfileTrigger("cat: ")),
            entry!!.triggers,
        )
        // No displayname in the source — it falls back to the id rather than rendering blank.
        assertEquals("cat", entry.displayname)
    }

    @Test
    fun `keeps unknown fields out of triggers and back into the outgoing content`() {
        val entry = parseEntry(
            JSONObject("""{"id": "cat", "displayname": "Cat", "some_future_field": "keep me", "triggers": [{"prefix": "c:"}]}"""),
        )

        assertNotNull(entry)
        assertEquals(mapOf<String, Any>("some_future_field" to "keep me"), entry!!.extras)

        // MSC4461 rev-3: everything except `triggers` is copied into the message.
        val content = entry.toContentMap()
        assertEquals("keep me", content["some_future_field"])
        assertEquals("cat", content["id"])
        assertTrue("triggers must never leak into the message", !content.containsKey("triggers"))
        assertTrue("trigger must never leak into the message", !content.containsKey("trigger"))
        // Blank avatar is omitted rather than sent as an empty string.
        assertTrue(!content.containsKey("avatar_url"))
    }

    @Test
    fun `drops profiles with no id`() {
        assertNull(parseEntry(JSONObject("""{"displayname": "Nameless"}""")))
        assertNull(parseEntry(JSONObject("""{"id": "", "displayname": "Blank"}""")))
    }

    @Test
    fun `absent default_profile_id parses as null so the global value still applies`() {
        val store = parseStore(JSONObject("""{"profiles": []}"""))
        assertNotNull(store)
        assertNull(store!!.defaultProfileId)
    }

    @Test
    fun `explicit JSON null default_profile_id parses as null`() {
        val store = parseStore(JSONObject("""{"default_profile_id": null, "profiles": []}"""))
        assertNotNull(store)
        assertNull(store!!.defaultProfileId)
    }

    @Test
    fun `empty-string default_profile_id is preserved as the suppress-global marker`() {
        val store = parseStore(JSONObject("""{"default_profile_id": "", "profiles": []}"""))
        assertNotNull(store)
        assertEquals("", store!!.defaultProfileId)
    }

    @Test
    fun `named default_profile_id is preserved`() {
        val store = parseStore(JSONObject("""{"default_profile_id": "cat", "profiles": [{"id": "cat"}]}"""))
        assertNotNull(store)
        assertEquals("cat", store!!.defaultProfileId)
        assertEquals(1, store.profiles.size)
    }

    @Test
    fun `content without a profiles array is not array-shaped storage`() {
        // The rev-1 shortcode map lives under the same stable key; parseStore must decline it so the
        // caller falls through to the legacy reader instead of yielding an empty list.
        assertNull(parseStore(JSONObject("""{"cat": {"id": "cat", "displayname": "Cat"}}""")))
        assertNull(parseStore(null))
    }

    @Test
    fun `stored order is preserved because it is match priority`() {
        val store = parseStore(
            JSONObject("""{"profiles": [{"id": "zebra"}, {"id": "aardvark"}, {"id": "moose"}]}"""),
        )
        assertNotNull(store)
        assertEquals(listOf("zebra", "aardvark", "moose"), store!!.profiles.map { it.id })
    }
}
