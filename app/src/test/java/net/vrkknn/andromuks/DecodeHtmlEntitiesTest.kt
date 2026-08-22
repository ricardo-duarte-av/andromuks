package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.decodeHtmlEntities
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for `decodeHtmlEntities`, which decodes entity references in text that did **not** come
 * through jsoup — a message's plain `body`, and the `latex` attribute of a `<hicli-math>` tag.
 *
 * It used to decode a numeric reference with `Int.toChar()`, which truncates to 16 bits: every
 * codepoint above U+FFFF decoded to a different character. `&#128512;` (U+1F600 😀) came out as
 * U+F600, a private-use glyph that renders as tofu. Most emoji are astral, so most emoji written
 * as numeric references were mangled.
 */
class DecodeHtmlEntitiesTest {

    @Test
    fun `a basic-plane decimal reference decodes`() {
        assertEquals("\"", decodeHtmlEntities("&#34;"))
    }

    @Test
    fun `an astral decimal reference decodes to the whole emoji`() {
        val decoded = decodeHtmlEntities("&#128512;")

        assertEquals("😀", decoded)
        // A surrogate pair: two chars, one codepoint. The truncating version returned one char.
        assertEquals(2, decoded.length)
        assertEquals(0x1F600, decoded.codePointAt(0))
    }

    @Test
    fun `an astral hex reference decodes to the whole emoji`() {
        assertEquals("😀", decodeHtmlEntities("&#x1F600;"))
        assertEquals("😀", decodeHtmlEntities("&#X1f600;"))
    }

    @Test
    fun `a reference outside the unicode range is left as literal text`() {
        assertEquals("&#1114112;", decodeHtmlEntities("&#1114112;"))
        assertEquals("&#99999999999;", decodeHtmlEntities("&#99999999999;"))
    }

    @Test
    fun `a lone surrogate is refused rather than emitted unpaired`() {
        assertEquals("&#xD800;", decodeHtmlEntities("&#xD800;"))
    }

    @Test
    fun `named entities decode`() {
        assertEquals("<b> & \"x\"", decodeHtmlEntities("&lt;b&gt; &amp; &quot;x&quot;"))
    }

    @Test
    fun `surrounding text is preserved`() {
        assertEquals("say 😀 now", decodeHtmlEntities("say &#128512; now"))
    }
}
