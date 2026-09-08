package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.parseMxcMediaLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `parseMxcMediaLink` in `utils/MxcLinkViewer.kt`.
 *
 * A link to Matrix media reaches the renderer in three shapes and the encryption flag lives in a
 * different place from the mxc URI itself, which is exactly the kind of thing that silently
 * degrades into "opens a relative URL in the browser".
 */
class MxcLinkParsingTest {

    @Test
    fun `raw mxc uri parses`() {
        val link = parseMxcMediaLink("mxc://nope.chat/XPmUOHlmFfoYBqxdPrrpoKhT")
        assertEquals("mxc://nope.chat/XPmUOHlmFfoYBqxdPrrpoKhT", link?.mxc)
        assertEquals(false, link?.encrypted)
    }

    @Test
    fun `gomuks relative href parses and carries the flag`() {
        val link = parseMxcMediaLink("_gomuks/media/nope.chat/XPmUOHlmFfoYBqxdPrrpoKhT?encrypted=false")
        assertEquals("mxc://nope.chat/XPmUOHlmFfoYBqxdPrrpoKhT", link?.mxc)
        assertEquals(false, link?.encrypted)

        val encrypted = parseMxcMediaLink("_gomuks/media/nope.chat/abc?encrypted=true")
        assertEquals("mxc://nope.chat/abc", encrypted?.mxc)
        assertTrue(encrypted?.encrypted == true)
    }

    @Test
    fun `absolute gomuks url parses`() {
        val link = parseMxcMediaLink("https://gomuks.example.org/_gomuks/media/nope.chat/abc?encrypted=true")
        assertEquals("mxc://nope.chat/abc", link?.mxc)
        assertTrue(link?.encrypted == true)
    }

    @Test
    fun `data-mxc wins over the href but the flag still comes from the href`() {
        val link = parseMxcMediaLink(
            href = "_gomuks/media/nope.chat/abc?encrypted=true",
            dataMxc = "mxc://other.server/def",
        )
        assertEquals("mxc://other.server/def", link?.mxc)
        assertTrue(link?.encrypted == true)
    }

    @Test
    fun `non-media links are not claimed`() {
        assertNull(parseMxcMediaLink("https://example.org/some/page"))
        assertNull(parseMxcMediaLink("https://matrix.to/#/@user:example.org"))
        assertNull(parseMxcMediaLink("matrix:u/user:example.org"))
        // Malformed: no media id after the server.
        assertNull(parseMxcMediaLink("mxc://nope.chat"))
        assertNull(parseMxcMediaLink("_gomuks/media/nope.chat"))
    }
}
