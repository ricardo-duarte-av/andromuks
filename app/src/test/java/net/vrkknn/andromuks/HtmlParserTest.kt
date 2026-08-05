package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.HtmlNode
import net.vrkknn.andromuks.utils.HtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [HtmlParser], which turns Matrix message markup into the [HtmlNode] tree the renderer
 * walks.
 *
 * These exist because the parser used to be hand-rolled string scanning and a regex, and failed
 * the way regex HTML parsing always does. The specific failure that motivated replacing it with
 * jsoup: the attribute regex required `name=value`, so a **valueless** attribute was never
 * recorded. `<span data-mx-spoiler>` — the spec's own form for a spoiler with no reason — parsed
 * to an empty attribute map, the renderer's `attributes["data-mx-spoiler"] != null` check failed,
 * and the spoiler rendered **unmasked**. The same blind spot made `data-mx-emoticon` detection
 * work on one user's profile and not another's, depending on whether they wrote `=""`.
 *
 * So the first section below is the point of the whole exercise. The rest pins the Matrix-specific
 * behaviour the renderer depends on — the tag allowlist, `mx-reply` stripping, mxc-only images —
 * so a future parser swap has something to answer to.
 *
 * Tests target [HtmlParser.parse] rather than `parseCached`: the cache is an `android.util.LruCache`,
 * which is a no-op stub under the unit-test android.jar (see `testOptions` in app/build.gradle.kts).
 */
class HtmlParserTest {

    // ---------------------------------------------------------------- helpers

    private fun tags(nodes: List<HtmlNode>) = nodes.filterIsInstance<HtmlNode.Tag>()

    private fun firstTag(html: String): HtmlNode.Tag = tags(HtmlParser.parse(html)).first()

    /** Flattened text of a whole tree, for assertions that only care that content survived. */
    private fun textOf(nodes: List<HtmlNode>): String = buildString {
        fun walk(list: List<HtmlNode>) {
            list.forEach { node ->
                when (node) {
                    is HtmlNode.Text -> append(node.content)
                    is HtmlNode.Tag -> walk(node.children)
                    is HtmlNode.LineBreak -> append("\n")
                }
            }
        }
        walk(nodes)
    }

    // ---------------------------------------------------------------- valueless attributes

    @Test
    fun `a valueless attribute is recorded with an empty value`() {
        val tag = firstTag("<span data-mx-spoiler>hidden</span>")

        assertTrue(tag.attributes.containsKey("data-mx-spoiler"))
        assertEquals("", tag.attributes["data-mx-spoiler"])
    }

    @Test
    fun `a spoiler with a reason keeps the reason`() {
        val tag = firstTag("""<span data-mx-spoiler="plot twist">hidden</span>""")

        assertEquals("plot twist", tag.attributes["data-mx-spoiler"])
    }

    @Test
    fun `bare and empty-valued emoticon markers are both detected`() {
        // Real profiles use both spellings; the old parser only saw the second.
        val bare = firstTag(
            """<img data-mx-emoticon src="mxc://codestorm.net/DPtjJhBufsarkVUHYCnuTzrX" alt=":blobcat:" height="32">""",
        )
        val valued = firstTag(
            """<img data-mx-emoticon="" src="mxc://ilyamikcoder.com/oxTHCwPzWHarkWIBCfBLiEtr" alt=":spinny:" height="32">""",
        )

        assertTrue(bare.attributes.containsKey("data-mx-emoticon"))
        assertTrue(valued.attributes.containsKey("data-mx-emoticon"))
    }

    @Test
    fun `attribute names are lowercased and values preserved verbatim`() {
        val tag = firstTag("""<span DATA-MX-COLOR="#FE0000">red</span>""")

        assertEquals("#FE0000", tag.attributes["data-mx-color"])
    }

    // ---------------------------------------------------------------- allowlist

    @Test
    fun `a disallowed tag is unwrapped but its text survives`() {
        val nodes = HtmlParser.parse("<marquee>still readable</marquee>")

        assertTrue(tags(nodes).isEmpty())
        assertEquals("still readable", textOf(nodes))
    }

    @Test
    fun `script content does not survive`() {
        // jsoup parks script contents in a DataNode, which is not a TextNode and is dropped.
        val nodes = HtmlParser.parse("<script>alert('xss')</script>")

        assertTrue(tags(nodes).isEmpty())
        assertEquals("", textOf(nodes))
    }

    @Test
    fun `allowed tags are kept with their children`() {
        val tag = firstTag("<blockquote><p>quoted</p></blockquote>")

        assertEquals("blockquote", tag.name)
        assertEquals("p", (tag.children.first() as HtmlNode.Tag).name)
        assertEquals("quoted", textOf(tag.children))
    }

    // ---------------------------------------------------------------- mx-reply

    @Test
    fun `mx-reply is dropped with its whole subtree`() {
        val nodes = HtmlParser.parse(
            "<mx-reply><blockquote>original message</blockquote></mx-reply>the actual reply",
        )

        assertEquals("the actual reply", textOf(nodes))
        assertTrue(tags(nodes).isEmpty())
    }

    // ---------------------------------------------------------------- images

    @Test
    fun `mxc and gomuks media images are kept`() {
        assertEquals("img", firstTag("""<img src="mxc://example.org/abc" alt="x">""").name)
        assertEquals("img", firstTag("""<img src="_gomuks/media/example.org/abc" alt="x">""").name)
    }

    @Test
    fun `a remote image is refused and replaced by its alt text`() {
        val nodes = HtmlParser.parse("""<img src="https://tracker.example/pixel.gif" alt="CODESTORM">""")

        assertTrue(tags(nodes).isEmpty())
        assertEquals("CODESTORM", textOf(nodes))
    }

    @Test
    fun `a refused image falls back to title then to a placeholder`() {
        assertEquals(
            "the title",
            textOf(HtmlParser.parse("""<img src="https://example.org/a.png" title="the title">""")),
        )
        assertEquals("[Image]", textOf(HtmlParser.parse("""<img src="https://example.org/a.png">""")))
    }

    @Test
    fun `image dimensions are available to the renderer`() {
        val tag = firstTag("""<img src="mxc://codestorm.net/MthX" width="320" height="99" alt="CODESTORM">""")

        assertEquals("320", tag.attributes["width"])
        assertEquals("99", tag.attributes["height"])
    }

    // ---------------------------------------------------------------- entities

    @Test
    fun `entities are decoded exactly once`() {
        // "&amp;lt;" is the literal text "&lt;" — decoding twice would turn it into "<".
        assertEquals("&lt;", textOf(HtmlParser.parse("&amp;lt;")))
    }

    @Test
    fun `an escaped tag stays text and is not parsed as markup`() {
        val nodes = HtmlParser.parse("&lt;b&gt;not bold&lt;/b&gt;")

        assertTrue(tags(nodes).isEmpty())
        assertEquals("<b>not bold</b>", textOf(nodes))
    }

    // ---------------------------------------------------------------- whitespace

    @Test
    fun `spaces between inline tags survive`() {
        assertEquals("a b", textOf(HtmlParser.parse("<b>a</b> <b>b</b>")))
    }

    @Test
    fun `pre keeps its newlines and indentation`() {
        val tag = firstTag("<pre><code>line one\n    indented</code></pre>")

        assertEquals("line one\n    indented", textOf(tag.children))
    }

    @Test
    fun `trailing whitespace on the last text run is dropped`() {
        assertEquals("text", textOf(HtmlParser.parse("text   \n")))
    }

    // ---------------------------------------------------------------- malformed markup

    @Test
    fun `an unclosed tag still yields its content`() {
        // The old scanner logged "No closing tag found" and dropped the tag entirely.
        val nodes = HtmlParser.parse("<b>hello")

        assertEquals("b", tags(nodes).first().name)
        assertEquals("hello", textOf(nodes))
    }

    @Test
    fun `nested identical tags nest rather than closing early`() {
        val outer = firstTag("<b>a<b>b</b>c</b>")

        assertEquals("b", outer.name)
        assertEquals("abc", textOf(listOf(outer)))
        assertEquals(1, tags(outer.children).size)
    }

    @Test
    fun `an attribute value containing an angle bracket does not end the tag`() {
        val tag = firstTag("""<blockquote data-md="&gt;">quoted</blockquote>""")

        assertEquals("blockquote", tag.name)
        assertEquals(">", tag.attributes["data-md"])
        assertEquals("quoted", textOf(tag.children))
    }

    @Test
    fun `empty input yields no nodes`() {
        assertTrue(HtmlParser.parse("").isEmpty())
    }

    // ---------------------------------------------------------------- real corpus

    @Test
    fun `a bio of custom emoticons parses to img tags carrying their shortcodes`() {
        val nodes = HtmlParser.parse(
            """<img src="mxc://ilyamikcoder.com/oxTH" alt=":spinny_cat_omnisexual:" title=":spinny_cat_omnisexual:" data-mx-emoticon="" height="32"><img src="mxc://ilyamikcoder.com/fszu" alt=":spinny_cat_nb:" title=":spinny_cat_nb:" data-mx-emoticon="" height="32">""",
        )

        val images = tags(nodes)
        assertEquals(2, images.size)
        assertTrue(images.all { it.name == "img" && it.attributes.containsKey("data-mx-emoticon") })
        assertEquals(":spinny_cat_omnisexual:", images.first().attributes["alt"])
    }

    @Test
    fun `a bio mixing a banner, bare emoticons, coloured spans and a link keeps all of it`() {
        val nodes = HtmlParser.parse(
            """<img src="mxc://codestorm.net/MthX" width="320" height="99" alt="CODESTORM"><br><img data-mx-emoticon src="mxc://codestorm.net/DPtj" alt=":blobcat:" height="32"> <span data-mx-color="#d98a3d">running with the wolves</span><br>website <a href="https://codestorm.net"><span data-mx-color="#d60270">c</span></a>""",
        )

        val images = tags(nodes).filter { it.name == "img" }
        assertEquals(2, images.size)
        // The banner declares dimensions and is not an emoticon; the blobcat is the reverse.
        assertNull(images[0].attributes["data-mx-emoticon"])
        assertEquals("320", images[0].attributes["width"])
        assertNotNull(images[1].attributes["data-mx-emoticon"])
        assertNull(images[1].attributes["width"])

        assertTrue(nodes.any { it is HtmlNode.LineBreak })
        assertTrue(textOf(nodes).contains("running with the wolves"))

        val link = tags(nodes).first { it.name == "a" }
        assertEquals("https://codestorm.net", link.attributes["href"])
        assertEquals("#d60270", (link.children.first() as HtmlNode.Tag).attributes["data-mx-color"])
    }
}
