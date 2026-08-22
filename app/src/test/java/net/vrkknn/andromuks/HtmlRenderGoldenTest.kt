package net.vrkknn.andromuks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import net.vrkknn.andromuks.utils.renderHtmlToAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-output tests for the HTML **renderer** — the `appendHtmlNode` / `appendHtmlTag` /
 * `appendBlockQuote` / `appendUnorderedList` family in `utils/html.kt` that turns the parsed
 * [net.vrkknn.andromuks.utils.HtmlNode] tree into an `AnnotatedString`.
 *
 * `HtmlParserTest` covers the parse; nothing covered the render, which is where the line
 * bookkeeping lives — and the line bookkeeping is where the bugs are. Four shipped defects were
 * found by writing this file, all of them invisible to a parser test:
 *
 *  - a quote at the start of a message emitted a blank first line (`length > 0` guard missing from
 *    two of eleven otherwise identical block guards);
 *  - a quote ending in `<br/>` — what gomuks emits for a one-line markdown quote — left a dangling
 *    "│" on a line of its own;
 *  - raw text after a `<br/>` inside a quote opened a *second* prefixed line, so `one<br/>two`
 *    rendered as three lines with an empty "│" wedged between them;
 *  - `<ul>`/`<ol>` opened with an unconditional newline, so a message starting with a list started
 *    with a blank line.
 *
 * Assertions are on the exact string, newlines included. That is the point: every one of those
 * defects was a stray or missing `\n`, and a test that trimmed or normalised whitespace would have
 * passed against all four.
 *
 * **Which render path this is.** [renderHtmlToAnnotatedString] drives the same node walk that
 * `HtmlMessageText` does, but it is a plain function, so it runs here with no Compose runtime. Two
 * deliberate divergences from the message path are worth knowing when reading a golden below:
 *
 *  1. It passes `spoilerContext = null` and `inlineCodeBlocks = null`, so spoilers render
 *     unwrapped and `<pre><code>` takes the preformatted path rather than the tappable preview.
 *  2. It does not insert the extra blank line `HtmlMessageText` puts between consecutive `<p>`,
 *     and it trims one trailing newline where the message path trims all of them.
 *
 * Folding the two walks into one shared function is the follow-up that would close that gap; these
 * goldens are what makes doing it safe.
 */
class HtmlRenderGoldenTest {

    private fun render(html: String): String = renderHtmlToAnnotatedString(html, Color.Unspecified).text

    private fun rendered(html: String): AnnotatedString = renderHtmlToAnnotatedString(html, Color.Unspecified)

    /** The substring a span covers, for asserting that styling landed on the right characters. */
    private fun AnnotatedString.styled(predicate: (AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>) -> Boolean): List<String> =
        spanStyles.filter(predicate).map { text.substring(it.start, it.end) }

    // ---------------------------------------------------------------- blockquotes

    @Test
    fun `a quote that ends in a line break renders without a dangling prefix`() {
        // The reported case: gomuks sanitizes "> quote\n\n- attribution" to exactly this.
        assertEquals(
            "│ quoted\n\ntail",
            render("<blockquote>quoted<br/></blockquote><br/>tail"),
        )
    }

    @Test
    fun `a quote at the very start does not emit a leading blank line`() {
        assertEquals("│ quoted", render("<blockquote>quoted</blockquote>"))
    }

    @Test
    fun `a line break inside a quote continues the same quote, one prefix per line`() {
        assertEquals("│ one\n│ two", render("<blockquote>one<br/>two</blockquote>"))
    }

    @Test
    fun `a doubled line break inside a quote keeps the blank quoted line`() {
        assertEquals("│ one\n│ \n│ two", render("<blockquote>one<br/><br/>two</blockquote>"))
    }

    @Test
    fun `paragraph-shaped quotes render one prefixed line per paragraph`() {
        assertEquals("│ one\n│ two", render("<blockquote><p>one</p><p>two</p></blockquote>"))
    }

    @Test
    fun `raw text and a paragraph in the same quote do not double up the prefix`() {
        assertEquals("│ raw\n│ para", render("<blockquote>raw<br/><p>para</p></blockquote>"))
    }

    @Test
    fun `a nested quote deepens the prefix`() {
        assertEquals(
            "│ outer\n││ deep",
            render("<blockquote><p>outer</p><blockquote><p>deep</p></blockquote></blockquote>"),
        )
    }

    @Test
    fun `a quote separates itself from the text around it`() {
        assertEquals("before\n│ q\nafter", render("before<blockquote>q</blockquote>after"))
    }

    // ---------------------------------------------------------------- lists

    @Test
    fun `a list at the very start does not emit a leading blank line`() {
        assertEquals("• a\n• b", render("<ul><li>a</li><li>b</li></ul>"))
    }

    @Test
    fun `an ordered list numbers its items from one`() {
        assertEquals("1. a\n2. b", render("<ol><li>a</li><li>b</li></ol>"))
    }

    @Test
    fun `a list separates itself from preceding text without doubling an existing break`() {
        assertEquals("intro\n• a\ntail", render("intro<ul><li>a</li></ul>tail"))
        // The paragraph already closed with a newline; the list must not add a blank line.
        assertEquals("intro\n• a", render("<p>intro</p><ul><li>a</li></ul>"))
    }

    // ---------------------------------------------------------------- other blocks

    @Test
    fun `a header is followed by its body on the next line`() {
        assertEquals("Title\nbody", render("<h1>Title</h1><p>body</p>"))
    }

    @Test
    fun `a horizontal rule renders on its own line`() {
        assertEquals("a\n────────\nb", render("a<hr/>b"))
    }

    @Test
    fun `a preformatted block keeps its own newlines and closes its line`() {
        assertEquals("line1\nline2\ntail", render("<pre><code>line1\nline2</code></pre>tail"))
    }

    @Test
    fun `consecutive paragraphs are separated by a single newline on this path`() {
        // See the class doc: the message path adds one more blank line between <p> siblings.
        assertEquals("one\ntwo", render("<p>one</p><p>two</p>"))
    }

    @Test
    fun `a line break between plain text renders as one newline`() {
        assertEquals("a\nb", render("a<br/>b"))
    }

    // ---------------------------------------------------------------- whitespace and text

    @Test
    fun `source indentation inside a block is collapsed away`() {
        // Trailing space is left alone — it is invisible and the end-trim only strips newlines.
        assertEquals("indented ", render("<p>\n  indented\n</p>"))
    }

    @Test
    fun `a space between two inline tags survives`() {
        assertEquals("bold it", render("<strong>bold</strong> <em>it</em>"))
    }

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("just text", render("just text"))
    }

    @Test
    fun `entities are decoded once, so an escaped entity stays literal`() {
        // "&amp;amp;" is the literal text "&amp;", not a second round of decoding. The astral
        // codepoint must survive as one emoji rather than a truncated private-use char.
        assertEquals("\"q\" &amp; 😀", render("&#34;q&#34; &amp;amp; &#128512;"))
    }

    // ---------------------------------------------------------------- pills and links

    @Test
    fun `a mention pill is followed by exactly one space`() {
        assertEquals("hi lda bye", render("hi <a href=\"https://matrix.to/#/@lda:unredacted.org\">lda</a> bye"))
    }

    @Test
    fun `a link renders its text inline and underlines it`() {
        val out = rendered("before <a href=\"https://x.example\">x</a> after")

        assertEquals("before x after", out.text)
        assertEquals(listOf("x"), out.styled { it.item.textDecoration == TextDecoration.Underline })
        assertTrue(out.getStringAnnotations("URL", 0, out.length).any { it.item == "https://x.example" })
    }

    // ---------------------------------------------------------------- inline styling

    @Test
    fun `bold and italic land on the characters they wrap`() {
        val out = rendered("<p>a <strong>b <em>c</em></strong> d</p>")

        assertEquals("a b c d", out.text)
        // Bold arrives as two runs — the nested <em> opens a new span — so compare the covered
        // text, not the run boundaries, which are an implementation detail.
        assertEquals("b c", out.styled { it.item.fontWeight == FontWeight.Bold }.joinToString(""))
        assertEquals("c", out.styled { it.item.fontStyle == FontStyle.Italic }.joinToString(""))
    }

    @Test
    fun `inline code is monospaced without disturbing the surrounding text`() {
        val out = rendered("use <code>foo()</code> now")

        assertEquals("use foo() now", out.text)
        assertEquals(listOf("foo()"), out.styled { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `strikethrough applies to del and s alike`() {
        assertEquals(listOf("gone"), rendered("<del>gone</del>").styled { it.item.textDecoration == TextDecoration.LineThrough })
        assertEquals(listOf("gone"), rendered("<s>gone</s>").styled { it.item.textDecoration == TextDecoration.LineThrough })
    }
}
