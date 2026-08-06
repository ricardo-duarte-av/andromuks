package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.HtmlSegment
import net.vrkknn.andromuks.utils.splitTopLevelBlockImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [splitTopLevelBlockImages].
 *
 * A picture cannot be inline text content: the line it lands on takes its height from the text
 * style, so a 99dp image on a 17dp line has the following text drawn straight through it. Real
 * images are therefore pulled out and laid out as blocks, and the two things that must hold are
 * pinned here — a custom emoji is *not* pulled out (it belongs on the line), and document order
 * survives, so a banner written above the text renders above the text.
 *
 * The markup is gomuks' sanitized form, copied from a logcat of a real profile bio: sizes live in
 * a CSS style, `data-mx-emoticon` has become `class="hicli-custom-emoji"`, and `mxc://` has become
 * `_gomuks/media/…`.
 */
class BlockImageSplitTest {
    private val banner =
        """<img alt="CODESTORM" src="_gomuks/media/codestorm.net/MthXEsBp?encrypted=false" loading="lazy" """ +
            """class="hicli-inline-img hicli-sized-inline-img" style="width: 320.00px; height: 99.00px;">"""
    private val emoji =
        """<img alt=":blobcat:" title=":blobcat:" src="_gomuks/media/codestorm.net/DPtjJhBu?encrypted=false" """ +
            """loading="lazy" class="hicli-inline-img hicli-custom-emoji">"""

    @Test
    fun `pulls out the sized image and keeps the emoji inline, in order`() {
        val segments = splitTopLevelBlockImages("$banner<br>$emoji hello $emoji<br>bye")

        assertEquals(2, segments.size)
        val image = segments[0] as HtmlSegment.BlockImage
        assertEquals(320, image.width)
        assertEquals(99, image.height)
        assertEquals("CODESTORM", image.alt)

        val markup = segments[1] as HtmlSegment.Markup
        // Both emoji stayed put, and the <br> that only separated them from the banner is gone.
        assertEquals(2, Regex("hicli-custom-emoji").findAll(markup.html).count())
        assertTrue(markup.html.contains("hello"))
        assertTrue(markup.html.startsWith("<img"))
    }

    @Test
    fun `text before an image keeps its place`() {
        val segments = splitTopLevelBlockImages("intro$banner tail")

        assertEquals(3, segments.size)
        assertTrue((segments[0] as HtmlSegment.Markup).html.contains("intro"))
        assertTrue(segments[1] is HtmlSegment.BlockImage)
        assertTrue((segments[2] as HtmlSegment.Markup).html.contains("tail"))
    }

    @Test
    fun `an image nested in other markup stays in the flow`() {
        val segments = splitTopLevelBlockImages("<p>$banner</p>")

        assertEquals(1, segments.size)
        assertTrue(segments[0] is HtmlSegment.Markup)
    }

    @Test
    fun `attribute sizes work too, for markup no sanitizer touched`() {
        val segments = splitTopLevelBlockImages("""<img src="mxc://s/id" width="320" height="99" alt="x">""")

        val image = segments.single() as HtmlSegment.BlockImage
        assertEquals(320, image.width)
        assertEquals(99, image.height)
        assertEquals("mxc://s/id", image.src)
    }

    @Test
    fun `a remote src is never pulled out`() {
        // Loading it would leak the reader's IP to a third-party host. Left in the markup run,
        // where the parser turns it into its alt text.
        val segments = splitTopLevelBlockImages("""<img src="https://evil.example/x.png" width="320" height="99">""")

        assertTrue(segments.single() is HtmlSegment.Markup)
    }

    @Test
    fun `markup with no images comes back as one run`() {
        val segments = splitTopLevelBlockImages("<p>just <b>text</b></p>")

        assertEquals(1, segments.size)
        assertTrue((segments[0] as HtmlSegment.Markup).html.contains("just"))
    }
}
