package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.InlineImageData
import net.vrkknn.andromuks.utils.InlineImageSizing
import net.vrkknn.andromuks.utils.inlineImageSizeSp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [inlineImageSizeSp], the sizing used when HTML is shown at full size (the expanded
 * profile-bio window) rather than as a chat line, where inline images are instead squashed to one
 * line of text.
 *
 * The rules being pinned: the declared aspect ratio survives every clamp, and neither bound is
 * ever exceeded — a bio is remote markup, so a `<img height="4000">` must not be able to push the
 * rest of the text out of the window.
 */
class InlineImageSizingTest {
    private fun image(width: Int?, height: Int?) = InlineImageData(
        src = "mxc://example.org/abc",
        alt = "",
        height = height ?: 32,
        declaredWidth = width,
        declaredHeight = height,
    )

    private val sizing = InlineImageSizing(maxHeightSp = 300f, maxWidthSp = 200f)

    @Test
    fun `declared size within bounds is used as-is`() {
        val (width, height) = inlineImageSizeSp(image(width = 160, height = 80), sizing)
        assertEquals(160f, width, 0.01f)
        assertEquals(80f, height, 0.01f)
    }

    @Test
    fun `too-tall image is capped by height and keeps its aspect ratio`() {
        val (width, height) = inlineImageSizeSp(image(width = 200, height = 600), sizing)
        assertEquals(300f, height, 0.01f)
        assertEquals(100f, width, 0.01f)
    }

    @Test
    fun `too-wide image is capped by width and keeps its aspect ratio`() {
        val (width, height) = inlineImageSizeSp(image(width = 800, height = 400), sizing)
        assertEquals(200f, width, 0.01f)
        assertEquals(100f, height, 0.01f)
    }

    @Test
    fun `both bounds exceeded still fits inside both`() {
        val (width, height) = inlineImageSizeSp(image(width = 4000, height = 8000), sizing)
        assertEquals(150f, width, 0.01f)
        assertEquals(300f, height, 0.01f)
    }

    @Test
    fun `undeclared size falls back to a square at the height cap`() {
        // Intrinsic size isn't known before the bitmap loads and the placeholder must be sized
        // first, so the image is fitted into a square instead.
        val (width, height) = inlineImageSizeSp(image(width = null, height = null), sizing)
        assertEquals(200f, width, 0.01f)
        assertEquals(200f, height, 0.01f)
    }

    @Test
    fun `height alone gives a square of that height`() {
        val (width, height) = inlineImageSizeSp(image(width = null, height = 64), sizing)
        assertEquals(64f, width, 0.01f)
        assertEquals(64f, height, 0.01f)
    }

    @Test
    fun `non-positive declared values are ignored`() {
        val (width, height) = inlineImageSizeSp(image(width = 0, height = 0), sizing)
        assertEquals(200f, width, 0.01f)
        assertEquals(200f, height, 0.01f)
    }
}
