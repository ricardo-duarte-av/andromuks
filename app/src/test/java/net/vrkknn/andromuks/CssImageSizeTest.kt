package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.parseCssImageSizePx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [parseCssImageSizePx].
 *
 * gomuks' sanitizer does not keep an `<img>`'s `width`/`height` attributes. It rewrites the tag —
 * `mxc://` becomes `_gomuks/media/…`, `data-mx-emoticon` becomes `class="hicli-custom-emoji"` —
 * and puts the size in CSS. Reading only the attributes made every image in a server-rendered
 * profile bio arrive sizeless, so a 320x99 banner was drawn as a 17dp square.
 *
 * The strings below are copied from a real logcat of that bio, so this pins the actual wire form
 * rather than an idea of it.
 */
class CssImageSizeTest {
    @Test
    fun `parses the sanitizer's own form`() {
        val (width, height) = parseCssImageSizePx("width: 320.00px; height: 99.00px;")
        assertEquals(320, width)
        assertEquals(99, height)
    }

    @Test
    fun `a custom emoji carries no style and stays sizeless`() {
        val (width, height) = parseCssImageSizePx(null)
        assertNull(width)
        assertNull(height)
    }

    @Test
    fun `tolerates spacing, casing, integers and reversed order`() {
        val (width, height) = parseCssImageSizePx("HEIGHT:12PX;width :  48px")
        assertEquals(48, width)
        assertEquals(12, height)
    }

    @Test
    fun `ignores other declarations`() {
        val (width, height) = parseCssImageSizePx("max-width: 100%; color: red; width: 64px")
        assertEquals(64, width)
        assertNull(height)
    }

    @Test
    fun `ignores units it cannot resolve without a layout`() {
        val (width, height) = parseCssImageSizePx("width: 50%; height: 3em")
        assertNull(width)
        assertNull(height)
    }

    @Test
    fun `ignores a property that merely ends in width`() {
        // `max-width: 320px` is not the image's width. The regex anchors on a declaration
        // boundary, so the `-width` suffix must not match.
        val (width, height) = parseCssImageSizePx("max-width: 320px; min-height: 99px")
        assertNull(width)
        assertNull(height)
    }

    @Test
    fun `rounds fractional pixels and drops non-positive values`() {
        assertEquals(33, parseCssImageSizePx("width: 32.6px").first)
        assertNull(parseCssImageSizePx("width: 0.0px").first)
    }
}
