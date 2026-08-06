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

    /** Stands in for the caller's inline size — one line of text. */
    private val lineHeight = 20f

    private fun size(data: InlineImageData) = inlineImageSizeSp(data, sizing, fallbackHeightSp = lineHeight)

    @Test
    fun `declared size within bounds is used as-is`() {
        val (width, height) = size(image(width = 160, height = 80))
        assertEquals(160f, width, 0.01f)
        assertEquals(80f, height, 0.01f)
    }

    @Test
    fun `too-tall image is capped by height and keeps its aspect ratio`() {
        val (width, height) = size(image(width = 200, height = 600))
        assertEquals(300f, height, 0.01f)
        assertEquals(100f, width, 0.01f)
    }

    @Test
    fun `too-wide image is capped by width and keeps its aspect ratio`() {
        val (width, height) = size(image(width = 800, height = 400))
        assertEquals(200f, width, 0.01f)
        assertEquals(100f, height, 0.01f)
    }

    @Test
    fun `both bounds exceeded still fits inside both`() {
        val (width, height) = size(image(width = 4000, height = 8000))
        assertEquals(150f, width, 0.01f)
        assertEquals(300f, height, 0.01f)
    }

    @Test
    fun `a small declared size is never enlarged`() {
        // The regression this pins: a bio full of height="32" emoticons must stay a bio full of
        // 32-high emoticons, not a column of full-width images.
        val (width, height) = size(image(width = null, height = 32))
        assertEquals(32f, width, 0.01f)
        assertEquals(32f, height, 0.01f)
    }

    @Test
    fun `undeclared size keeps the ordinary inline size`() {
        // Intrinsic size isn't known before the bitmap loads and the placeholder must be sized
        // first — sizing these from the cap is what blew gomuks-rendered bios apart.
        val (width, height) = size(image(width = null, height = null))
        assertEquals(lineHeight, width, 0.01f)
        assertEquals(lineHeight, height, 0.01f)
    }

    @Test
    fun `non-positive declared values are ignored`() {
        val (width, height) = size(image(width = 0, height = 0))
        assertEquals(lineHeight, width, 0.01f)
        assertEquals(lineHeight, height, 0.01f)
    }
}
