package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.HtmlNode
import net.vrkknn.andromuks.utils.HtmlParser
import net.vrkknn.andromuks.utils.trimEdgeLineBreaks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `List<HtmlNode>.trimEdgeLineBreaks`, the helper the blockquote renderer applies to a
 * quote's children.
 *
 * The bug it fixes: gomuks sanitizes a one-line markdown quote to
 * `<blockquote>text<br/></blockquote>`, and the renderer answers every line break by opening a new
 * prefixed quote line ("│ "). The trailing break therefore opened a line that never received any
 * content, and the message rendered with a stray "│" under the quote. Interior breaks must still
 * survive — those are the real line separators inside a multi-line quote.
 */
class HtmlEdgeLineBreakTest {

    private fun blockquoteChildren(html: String): List<HtmlNode> =
        (HtmlParser.parse(html).first { it is HtmlNode.Tag && it.name == "blockquote" } as HtmlNode.Tag).children

    @Test
    fun `a trailing break is dropped`() {
        val trimmed = blockquoteChildren("<blockquote>quoted<br/></blockquote>").trimEdgeLineBreaks()

        assertEquals(1, trimmed.size)
        assertTrue(trimmed.single() is HtmlNode.Text)
    }

    @Test
    fun `a leading break is dropped`() {
        val trimmed = blockquoteChildren("<blockquote><br/>quoted</blockquote>").trimEdgeLineBreaks()

        assertEquals(1, trimmed.size)
        assertTrue(trimmed.single() is HtmlNode.Text)
    }

    @Test
    fun `an interior break is kept`() {
        val trimmed = blockquoteChildren("<blockquote>one<br/>two<br/></blockquote>").trimEdgeLineBreaks()

        assertEquals(3, trimmed.size)
        assertTrue(trimmed[1] is HtmlNode.LineBreak)
    }

    @Test
    fun `a list of nothing but breaks trims to empty`() {
        val trimmed = blockquoteChildren("<blockquote><br/><br/></blockquote>").trimEdgeLineBreaks()

        assertTrue(trimmed.isEmpty())
    }

    @Test
    fun `a list with no edge breaks is returned unchanged`() {
        val children = blockquoteChildren("<blockquote>one<br/>two</blockquote>")

        assertSame(children, children.trimEdgeLineBreaks())
    }
}
