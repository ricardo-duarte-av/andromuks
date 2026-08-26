package net.vrkknn.andromuks

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import net.vrkknn.andromuks.utils.HtmlParser
import net.vrkknn.andromuks.utils.InlineCodeBlockPreview
import net.vrkknn.andromuks.utils.SpoilerRenderContext
import net.vrkknn.andromuks.utils.buildPlainTextAnnotatedStringWithCode
import net.vrkknn.andromuks.utils.renderHtmlNodes
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
 * **Which render path this is.** [renderHtmlNodes] is the walk `HtmlMessageText` itself calls —
 * there is no second implementation to drift from. It is a plain function rather than a composable
 * body, so it runs here with no Compose runtime, and the only thing the composable adds on top is
 * hosting the inline content these goldens assert was registered.
 *
 * `spoilerContext` and `inlineCodeBlocks` are the two arguments a caller may pass as null when it
 * has nowhere to host the interaction; both settings are covered below.
 */
class HtmlRenderGoldenTest {

    private fun rendered(html: String): AnnotatedString = renderHtmlNodes(HtmlParser.parse(html), SpanStyle(color = Color.Unspecified))

    private fun render(html: String): String = rendered(html).text

    /** [rendered] passes no code-block map, so `<pre><code>` falls back to preformatted text. */
    private fun renderWithCodeBlocks(html: String): AnnotatedString =
        renderHtmlNodes(HtmlParser.parse(html), SpanStyle(color = Color.Unspecified), inlineCodeBlocks = mutableMapOf())

    private fun plain(text: String): AnnotatedString =
        buildPlainTextAnnotatedStringWithCode(text, SpanStyle(color = Color.Unspecified), SpanStyle(fontFamily = FontFamily.Monospace))

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
    fun `consecutive paragraphs are separated by a blank line`() {
        assertEquals("one\n\ntwo", render("<p>one</p><p>two</p>"))
        // Only between paragraph siblings — a block of another kind gets the single break.
        assertEquals("one\n│ q", render("<p>one</p><blockquote>q</blockquote>"))
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

    // ---------------------------------------------------------------- spoilers and code blocks

    @Test
    fun `an unrevealed spoiler is masked to the same length as its text`() {
        val nodes = HtmlParser.parse("<span data-mx-spoiler>secret</span>")
        val context = SpoilerRenderContext(mutableStateMapOf())

        val out = renderHtmlNodes(nodes, SpanStyle(color = Color.Unspecified), spoilerContext = context)

        assertEquals("secret".length, out.text.length)
        assertEquals("<****>", out.text)
        // The annotation is what makes the run tappable in the message path.
        assertTrue(out.getStringAnnotations("SPOILER", 0, out.length).isNotEmpty())
    }

    @Test
    fun `a revealed spoiler shows its text`() {
        val nodes = HtmlParser.parse("<span data-mx-spoiler>secret</span>")
        val states = mutableStateMapOf("spoiler_0" to true)

        val out = renderHtmlNodes(nodes, SpanStyle(color = Color.Unspecified), spoilerContext = SpoilerRenderContext(states))

        assertEquals("secret", out.text)
    }

    @Test
    fun `without a spoiler context the content renders plainly`() {
        // A caller with nowhere to host the tap passes null; the text must still be readable
        // rather than stuck masked forever.
        assertEquals("secret", render("<span data-mx-spoiler>secret</span>"))
    }

    @Test
    fun `a long code block is truncated to a preview that keeps the full source`() {
        val code = (1..12).joinToString("\n") { "line$it" }
        val previews = mutableMapOf<String, InlineCodeBlockPreview>()

        val out = renderHtmlNodes(
            HtmlParser.parse("<pre><code>$code</code></pre>"),
            SpanStyle(color = Color.Unspecified),
            inlineCodeBlocks = previews,
        )

        assertTrue(out.text.startsWith("line1\nline2"))
        assertTrue(out.text.contains("... (4 more lines, tap to view full code)"))
        assertEquals(1, previews.size)
        val preview = previews.values.single()
        assertEquals(12, preview.totalLines)
        assertEquals(code, preview.fullCode)
        assertTrue(out.getStringAnnotations("CODE_BLOCK", 0, out.length).isNotEmpty())
    }

    @Test
    fun `without a code block map the source renders in full as preformatted text`() {
        val code = (1..12).joinToString("\n") { "line$it" }

        assertEquals(code, render("<pre><code>$code</code></pre>"))
    }

    // ---------------------------------------------------------------- inline content registration

    @Test
    fun `an inline image registers a placeholder rather than emitting its source`() {
        val images = mutableMapOf<String, net.vrkknn.andromuks.utils.InlineImageData>()

        val out = renderHtmlNodes(
            HtmlParser.parse("""a <img src="mxc://x.example/abc" alt=":cat:" height="32"> b"""),
            SpanStyle(color = Color.Unspecified),
            inlineImages = images,
        )

        assertEquals(1, images.size)
        assertEquals("mxc://x.example/abc", images.values.single().src)
        assertEquals(32, images.values.single().declaredHeight)
        // The placeholder is a zero-width space; the mxc URI must never reach the text.
        assertTrue(out.text.contains("\u200B"))
        assertTrue(!out.text.contains("mxc://"))
    }

    // ---------------------------------------------------------------- empty blocks

    /**
     * A `<pre>` is not allowed inside a `<p>`, so `HtmlParser` closes the paragraph implicitly the
     * way a browser does — leaving an empty `<p></p>` on each side of the block. That shape ships
     * from matrix-hookshot's default webhook formatter, which emits exactly
     * `<p>Received webhook data:</p><p><pre><code…>…</code></pre></p>`, and each empty paragraph
     * used to open a line of its own on top of the consecutive-paragraph blank line: three
     * newlines between the sentence and the JSON where the well-formed markup gives one.
     */
    @Test
    fun `a paragraph wrapping a code block renders as if the markup were well formed`() {
        val json = "{\n  \"status\": \"firing\"\n}"
        val wrapped = "<p>Received webhook data:</p><p><pre><code class=\"language-json\">$json</code></pre></p>"
        val wellFormed = "<p>Received webhook data:</p><pre><code class=\"language-json\">$json</code></pre>"

        assertEquals("Received webhook data:\n$json", renderWithCodeBlocks(wrapped).text)
        assertEquals(renderWithCodeBlocks(wellFormed).text, renderWithCodeBlocks(wrapped).text)
    }

    @Test
    fun `an empty paragraph between two paragraphs does not add a line`() {
        assertEquals("a\n\nb", render("<p>a</p><p></p><p>b</p>"))
        assertEquals("a\n\nb", render("<p>a</p><p>   </p><p>b</p>"))
        assertEquals(render("<p>a</p><p>b</p>"), render("<p>a</p><p></p><p>b</p>"))
    }

    @Test
    fun `an empty paragraph leading or trailing the message contributes nothing`() {
        assertEquals("a", render("<p></p><p>a</p>"))
        assertEquals("a", render("<p>a</p><p></p>"))
    }

    @Test
    fun `a paragraph holding only a line break or an image still renders`() {
        // Emptiness is about output, not children: <br> and <img> put something on the line even
        // with nothing inside them. The <br> paragraph keeps both of its separators and adds its
        // own break between them — a lot of blank lines, but the sender asked for every one.
        assertEquals("a\n\n\n\n\nb", render("<p>a</p><p><br></p><p>b</p>"))
        assertTrue(render("<p>a</p><p><img src=\"mxc://x.example/abc\"></p><p>b</p>").contains("\u200B"))
    }

    @Test
    fun `a paragraph hidden with display none contributes nothing`() {
        assertEquals("a\n\nb", render("<p>a</p><p style=\"display: none\">hidden</p><p>b</p>"))
    }

    // ---------------------------------------------------------------- plain-text code fences

    /**
     * The `formatted_body`-less counterpart of the case above: hookshot's plain body is
     * `"Received webhook data:\n\n```json\n\n<json>\n\n```"`, and the blank lines padding the
     * fence used to survive as blank output lines inside the code block.
     */
    @Test
    fun `blank lines padding a code fence are dropped`() {
        val out = plain("Received webhook data:\n\n```json\n\n{\n  \"a\": 1\n}\n\n```")

        assertEquals("Received webhook data:\n\n{\n  \"a\": 1\n}\n", out.text)
    }

    @Test
    fun `blank lines inside a code fence are kept`() {
        assertEquals("one\n\nthree", plain("```\none\n\nthree\n```").text.trimEnd('\n'))
    }

    @Test
    fun `a message opening with a code fence does not start on a blank line`() {
        assertEquals("code", plain("```\ncode\n```").text.trimEnd('\n'))
    }

    @Test
    fun `an unterminated code fence still renders its contents`() {
        assertEquals("intro\n\ncode", plain("intro\n```\ncode").text.trimEnd('\n'))
    }

    @Test
    fun `strikethrough applies to del and s alike`() {
        assertEquals(listOf("gone"), rendered("<del>gone</del>").styled { it.item.textDecoration == TextDecoration.LineThrough })
        assertEquals(listOf("gone"), rendered("<s>gone</s>").styled { it.item.textDecoration == TextDecoration.LineThrough })
    }
}
